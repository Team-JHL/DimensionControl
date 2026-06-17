package de.jakomi1.dimensionControl.commands;

import de.jakomi1.dimensionControl.DimensionControl;
import de.jakomi1.dimensionControl.utils.DimensionUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DimensionControlCommand implements CommandExecutor, TabCompleter {

    private static final String DIM_PREFIX = "CDim-";
    private static final String PERMISSION_BASE = "dimensioncontrol.dimension.";

    private final JavaPlugin plugin;
    private final Map<String, String> messages;
    private final SortedSet<String> customDimensions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public DimensionControlCommand() {
        this(DimensionControl.plugin, DimensionControl.messages);
    }

    public DimensionControlCommand(JavaPlugin plugin, Map<String, String> messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");

        bootstrapPresets();
        refreshCustomDimensions();
    }

    private boolean hasDimensionPermission(Player player, String subCommand) {
        return player.hasPermission(PERMISSION_BASE + "*")
                || player.hasPermission(PERMISSION_BASE + subCommand);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendFormatted(sender, NamedTextColor.RED, "messages.usage.general");
            return false;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if (sender instanceof Player player && !hasDimensionPermission(player, action)) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.no_perm");
            return true;
        }

        switch (action) {
            case "add" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.add");
                    return false;
                }

                String name = args[1];
                String presetOrSeed = args.length >= 3 ? String.join(" ", copyOfRange(args, 2, args.length)) : null;
                return addDimension(sender, name, presetOrSeed);
            }

            case "remove" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.remove");
                    return false;
                }
                return removeDimension(sender, args[1]);
            }

            case "reset" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.reset");
                    return false;
                }

                String name = args[1];
                String presetOrSeed = args.length >= 3 ? String.join(" ", copyOfRange(args, 2, args.length)) : null;

                removeDimension(sender, name);

                Bukkit.getScheduler().runTaskLater(plugin, () -> addDimension(sender, name, presetOrSeed), 20L);
                return true;
            }

            case "teleport" -> {
                if (args.length < 3) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.teleport");
                    return false;
                }
                return teleportToDimension(sender, args);
            }

            case "list" -> {
                listDimensions(sender);
                return true;
            }

            default -> {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.unknown_action");
                return false;
            }
        }
    }

    private boolean addDimension(CommandSender sender, String dimensionName, String presetOrSeed) {
        String fullName = DIM_PREFIX + dimensionName;

        if (dimensionName == null || !dimensionName.matches("[a-zA-Z0-9]+")) {
            sendFormatted(sender, NamedTextColor.RED,
                    "messages.error.invalid_dim_name",
                    dimensionName);
            return false;
        }

        if (Bukkit.getWorld(fullName) != null || dimensionExistsOnDisk(fullName)) {
            sendFormatted(sender, NamedTextColor.YELLOW,
                    "messages.error.dim_exists",
                    fullName);
            return true;
        }

        if (presetOrSeed == null || presetOrSeed.isBlank()) {
            long seed = randomSeed();

            WorldCreator creator = new WorldCreator(fullName)
                    .environment(World.Environment.NORMAL)
                    .seed(seed);

            sendFormatted(sender, NamedTextColor.GREEN, "messages.warn.wait");
            createWorldSync(sender, creator, fullName, null);
            return true;
        }

        Long explicitSeed = tryParseLong(presetOrSeed);
        if (explicitSeed != null) {
            WorldCreator creator = new WorldCreator(fullName)
                    .environment(World.Environment.NORMAL)
                    .seed(explicitSeed);

            sendFormatted(sender, NamedTextColor.GREEN,
                    "messages.success.seed_applied",
                    String.valueOf(explicitSeed));

            sendFormatted(sender, NamedTextColor.GREEN, "messages.warn.wait");

            createWorldSync(sender, creator, fullName, null);
            return true;
        }

        Path presetRoot = DimensionControl.dimensionUtils.resolvePresetFolderSync(presetOrSeed);

        if (presetRoot == null) {
            sendFormatted(sender, NamedTextColor.YELLOW,
                    "messages.error.preset_not_found",
                    presetOrSeed);
            return true;
        }

        sendFormatted(sender, NamedTextColor.GREEN, "messages.warn.wait");

        DimensionControl.dimensionUtils
                .importPresetAsync(presetOrSeed, fullName)
                .whenComplete((result, throwable) -> {

                    if (throwable != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getLogger().warning(
                                    "Preset import failed: " + throwable.getMessage());

                            sendFormatted(sender, NamedTextColor.RED,
                                    "messages.error.copy_error",
                                    throwable.getMessage());
                        });
                        return;
                    }

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            WorldCreator creator = DimensionControl.dimensionUtils
                                    .createCreatorFromPreset(fullName, result.metadata());

                            createWorldSync(sender, creator, fullName, result.metadata());

                        } catch (Exception ex) {
                            plugin.getLogger().warning(
                                    "Failed to create preset world '" + fullName + "': " + ex.getMessage());

                            sendFormatted(sender, NamedTextColor.RED,
                                    "messages.error.world_create_fail",
                                    fullName);
                        }
                    });
                });

        return true;
    }

    private void createWorldSync(CommandSender sender, WorldCreator creator, String fullName, DimensionUtils.PresetWorldData data) {
        try {
            Path legacy = DimensionControl.dimensionUtils.getLegacyWorldPath(fullName);
            Path migrated = DimensionControl.dimensionUtils.getMigratedWorldPath(fullName);

            if (Files.exists(legacy) && Files.exists(migrated)) {
                deleteDirectory(legacy);
            }

            World world = Bukkit.createWorld(creator);
            if (world == null) {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.world_create_fail", fullName);
                return;
            }

            if (data != null) {
                DimensionControl.dimensionUtils.applyPresetMetadata(world, data);
                world.save();
            }

            refreshCustomDimensions();
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.add", fullName);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to create world '" + fullName + "': " + ex.getMessage());
            sendFormatted(sender, NamedTextColor.RED, "messages.error.world_create_fail", fullName);
        }
    }

    private boolean removeDimension(CommandSender sender, String dimensionName) {
        String fullName = DIM_PREFIX + dimensionName;
        World world = Bukkit.getWorld(fullName);

        if (world != null) {
            World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (mainWorld != null) {
                Location safe = mainWorld.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
                for (Player player : world.getPlayers()) {
                    player.teleport(safe);
                    sendFormatted(player, NamedTextColor.YELLOW, "messages.warn.teleported_player", dimensionName);
                }
            }

            Bukkit.unloadWorld(world, false);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DimensionControl.dimensionUtils.deleteDimensionData(fullName);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    refreshCustomDimensions();
                    sendFormatted(sender, NamedTextColor.GREEN, "messages.success.remove", fullName);
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sendFormatted(sender, NamedTextColor.RED, "messages.error.copy_error", ex.getMessage()));
            }
        });

        return true;
    }

    private boolean teleportToDimension(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.dim_not_loaded", args[1]);
            return false;
        }

        String fullName = DIM_PREFIX + args[2];
        World world = Bukkit.getWorld(fullName);
        if (world == null) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.dim_not_loaded", fullName);
            listDimensions(sender);
            return false;
        }

        Location destination = world.getSpawnLocation().clone();

        if (args.length >= 6) {
            double x;
            double y;
            double z;
            try {
                x = Double.parseDouble(args[3]);
                y = Double.parseDouble(args[4]);
                z = Double.parseDouble(args[5]);
            } catch (NumberFormatException ex) {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.invalid_coords");
                return false;
            }

            destination = new Location(world, x, y, z);

            if (args.length >= 7) {
                String direction = args[6].toLowerCase(Locale.ROOT);
                switch (direction) {
                    case "north" -> destination.setYaw(180f);
                    case "south" -> destination.setYaw(0f);
                    case "west" -> destination.setYaw(90f);
                    case "east" -> destination.setYaw(-90f);
                    default -> {
                        sendFormatted(sender, NamedTextColor.RED, "messages.error.invalid_direction");
                        return false;
                    }
                }
            }
        }

        target.teleport(destination);
        return true;
    }

    private boolean listDimensions(CommandSender sender) {
        if (customDimensions.isEmpty()) {
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.list_empty");
        } else {
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.list", String.join(", ", customDimensions));
        }
        return true;
    }

    private void bootstrapPresets() {
        File presetFolder = new File(plugin.getDataFolder(), "presets");
        if (!presetFolder.exists() && !presetFolder.mkdirs()) {
            plugin.getLogger().warning("Konnte Preset-Ordner nicht erstellen: " + presetFolder.getAbsolutePath());
            return;
        }

        copyJarPresets();
        extractZipPresets(presetFolder.toPath());
    }

    private void copyJarPresets() {
        try {
            Path pluginPath = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(pluginPath)) {
                return;
            }

            try (JarFile jar = new JarFile(pluginPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith("presets/")) {
                        continue;
                    }

                    String resourcePath = entry.getName().substring("presets/".length());
                    File out = new File(plugin.getDataFolder(), entry.getName());

                    if (resourcePath.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        Path temp = Files.createTempFile("dimensioncontrol-preset-", ".zip");
                        try (InputStream in = plugin.getResource(resourcePath)) {
                            if (in == null) {
                                Files.deleteIfExists(temp);
                                continue;
                            }
                            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                        }

                        try {
                            importZipPreset(
                                    temp,
                                    plugin.getDataFolder().toPath().resolve("presets"),
                                    false
                            );
                        } finally {
                            Files.deleteIfExists(temp);
                        }
                        continue;
                    }

                    out.getParentFile().mkdirs();
                    try (InputStream in = plugin.getResource(resourcePath)) {
                        if (in == null) {
                            continue;
                        }
                        try (FileOutputStream fos = new FileOutputStream(out, false)) {
                            in.transferTo(fos);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed copying jar presets: " + ex.getMessage());
        }
    }

    private void extractZipPresets(Path presetRoot) {
        File[] zips = presetRoot.toFile().listFiles(file ->
                file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));

        if (zips == null) {
            return;
        }

        for (File zip : zips) {
            try {
                importZipPreset(zip.toPath(), presetRoot, true);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed importing zip preset " + zip.getName() + ": " + ex.getMessage());
            }
        }
    }

    private void importZipPreset(Path zipPath, Path presetRoot, boolean deleteSourceAfter) throws IOException {
        String zipName = stripExtension(zipPath.getFileName().toString());

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            String singleFolder = detectSingleTopLevelFolder(zip);
            String targetName = singleFolder != null ? singleFolder : zipName;

            Path target = presetRoot.resolve(targetName);

            deleteDirectory(target);
            Files.createDirectories(target);

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = normalizeZipEntryName(entry.getName());

                if (entryName.isBlank()) {
                    continue;
                }

                String relative = resolveZipEntryName(entryName, singleFolder);
                if (relative == null || relative.isBlank()) {
                    continue;
                }

                Path out = target.resolve(relative).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("Zip Slip");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(Objects.requireNonNull(out.getParent()));
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        if (deleteSourceAfter) {
            Files.deleteIfExists(zipPath);
        }
    }

    private String detectSingleTopLevelFolder(ZipFile zip) {
        String folder = null;

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = normalizeZipEntryName(entry.getName());

            if (name.isBlank()) {
                continue;
            }

            int slash = name.indexOf('/');
            if (slash < 0) {
                return null;
            }

            String top = name.substring(0, slash);

            if (folder == null) {
                folder = top;
            } else if (!folder.equals(top)) {
                return null;
            }
        }

        return folder;
    }

    private String resolveZipEntryName(String entry, String singleRoot) {
        if (singleRoot == null) {
            return entry;
        }

        String prefix = singleRoot + "/";
        if (entry.startsWith(prefix)) {
            return entry.substring(prefix.length());
        }

        return null;
    }

    private String normalizeZipEntryName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private void refreshCustomDimensions() {
        customDimensions.clear();
        customDimensions.addAll(DimensionControl.dimensionUtils.getCustomDimensions());
    }

    private boolean dimensionExistsOnDisk(String fullWorldName) {
        return Files.exists(DimensionControl.dimensionUtils.getLegacyWorldPath(fullWorldName))
                || Files.exists(DimensionControl.dimensionUtils.getMigratedWorldPath(fullWorldName));
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    private long randomSeed() {
        return ThreadLocalRandom.current().nextLong();
    }

    private Long tryParseLong(String value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private void sendFormatted(CommandSender sender, NamedTextColor color, String key, String... args) {
        sender.sendMessage(Component.text(format(key, args)).color(color));
    }

    private String format(String key, String... args) {
        String template = messages.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", args[i]);
        }
        return template;
    }

    private String[] copyOfRange(String[] source, int from, int to) {
        if (from >= to) {
            return new String[0];
        }

        String[] out = new String[to - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean hasDirectoryContent(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            if (hasDimensionPermission(player, "add")) out.add("add");
            if (hasDimensionPermission(player, "remove")) out.add("remove");
            if (hasDimensionPermission(player, "reset")) out.add("reset");
            if (hasDimensionPermission(player, "teleport")) out.add("teleport");
            if (hasDimensionPermission(player, "list")) out.add("list");

            return filterByPrefix(out, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "remove", "reset" -> {
                if (args.length == 2) {
                    out.addAll(customDimensions);
                    return filterByPrefix(out, args[1]);
                }

                if (args.length == 3 && sub.equals("reset")) {
                    out.add(String.valueOf(randomSeed()));
                    addPresetFolderNames(out);
                    return filterByPrefix(out, args[2]);
                }
            }

            case "add" -> {
                if (args.length == 3) {
                    out.add(String.valueOf(randomSeed()));
                    addPresetFolderNames(out);
                    return filterByPrefix(out, args[2]);
                }
            }

            case "teleport" -> {
                if (args.length == 2) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    return filterByPrefix(out, args[1]);
                }

                if (args.length == 3) {
                    out.addAll(customDimensions);
                    return filterByPrefix(out, args[2]);
                }

                if (args.length == 7) {
                    out.addAll(List.of("north", "south", "west", "east"));
                    return filterByPrefix(out, args[6]);
                }
            }
        }

        return out;
    }

    private void addPresetFolderNames(List<String> out) {
        File folder = new File(plugin.getDataFolder(), "presets");
        File[] children = folder.listFiles(File::isDirectory);

        if (children == null) {
            return;
        }

        for (File child : children) {
            out.add(child.getName());
        }
    }

    private List<String> filterByPrefix(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);

        return values.stream()
                .distinct()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
}