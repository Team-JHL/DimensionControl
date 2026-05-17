package de.jakomi1.dimensionControl.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static de.jakomi1.dimensionControl.DimensionControl.messages;
import static de.jakomi1.dimensionControl.DimensionControl.plugin;

/**
 * Command executor for dimension control, updated to Adventure API (1.21.4).
 */
public class DimensionControlCommand implements CommandExecutor, TabCompleter {
    private List<String> customDimensions = new ArrayList<>();

    public DimensionControlCommand() {
        copyPresetsFromJar();
        loadCustomDimensions();
        updateCustomDimensionsList();
    }

    private boolean hasDimensionPermission(Player player, String sub) {
        return player.hasPermission("dimensioncontrol.dimension.*") || player.hasPermission("dimensioncontrol.dimension." + sub);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !hasDimensionPermission(player, args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "")) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.no_perm");
            return true;
        }
        if (args.length < 1) {
            sendFormatted(sender, NamedTextColor.RED, "messages.usage.general");
            return false;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.add");
                    return false;
                }
                String name = args[1];
                String preset = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                return addDimension(sender, name, preset);
            }
            case "remove" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.remove");
                    return false;
                }
                return removeDimension(sender, args[1]);
            }
            case "teleport" -> {
                if (args.length < 3) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.teleport");
                    return false;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.error.dim_not_loaded", args[1]);
                    return false;
                }
                return handleTeleport(sender, target, args);
            }
            case "list" -> {
                listDimensions(sender);
                return true;
            }
            case "reset" -> {
                if (args.length < 2) {
                    sendFormatted(sender, NamedTextColor.RED, "messages.usage.reset");
                    return false;
                }
                String name = args[1];
                String preset = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                removeDimension(sender, name);
                return addDimension(sender, name, preset);
            }
            default -> {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.unknown_action");
                return false;
            }
        }
    }

    private void sendFormatted(CommandSender sender, NamedTextColor color, String key, String... args) {
        String text = format(key, args);
        sender.sendMessage(Component.text(text).color(color));
    }

    private boolean removeDimension(CommandSender sender, String dimensionName) {
        String full = "CDim-" + dimensionName;
        World world = Bukkit.getWorld(full);

        if (world != null) {
            World overworld = Bukkit.getWorlds().get(0);
            for (Player player : world.getPlayers()) {
                int highestY = overworld.getHighestBlockYAt(0, 0);
                player.teleport(new Location(overworld, 0.5, highestY + 1, 0.5));
                sendFormatted(player, NamedTextColor.YELLOW, "messages.warn.teleported_player", dimensionName);
            }
            Bukkit.unloadWorld(world, false);
        }

        File dir = new File(Bukkit.getWorldContainer(), full);
        if (dir.exists()) {
            deleteDirectory(dir);
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.remove", full);
        } else {
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.list_empty");
        }

        updateCustomDimensionsList();
        return true;
    }

    private boolean addDimension(CommandSender sender, String dimensionName, String seedOrPreset) {
        String full = "CDim-" + dimensionName;
        File worldFolder = new File(Bukkit.getWorldContainer(), full);
        if (worldFolder.exists()) {
            sendFormatted(sender, NamedTextColor.YELLOW, "messages.error.dim_exists", full);
            return false;
        }

        WorldCreator creator = new WorldCreator(full).environment(World.Environment.NORMAL);

        if (seedOrPreset != null) {
            try {
                long seed = Long.parseLong(seedOrPreset);
                creator.seed(seed);
                sendFormatted(sender, NamedTextColor.GREEN, "messages.success.seed_applied", String.valueOf(seed));
            } catch (NumberFormatException e) {
                File presetDir = new File(plugin.getDataFolder(), "presets/" + seedOrPreset);
                if (presetDir.exists() && presetDir.isDirectory()) {
                    sendFormatted(sender, NamedTextColor.GREEN, "messages.success.preset_found");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            String[] toCopy = {"region", "playerdata", "data", "advancements", "stats"};
                            for (String folderName : toCopy) {
                                File sourceFolder = new File(presetDir, folderName);
                                if (sourceFolder.exists()) {
                                    copyFolder(sourceFolder.toPath(), new File(worldFolder, folderName).toPath());
                                }
                            }
                            File levelDat = new File(presetDir, "level.dat");
                            if (levelDat.exists()) {
                                Files.copy(levelDat.toPath(), new File(worldFolder, "level.dat").toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                            File oldPaper = new File(presetDir, "paper-world.yml");
                            if (oldPaper.exists()) {
                                Files.copy(oldPaper.toPath(), new File(worldFolder, "paper-world.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> createWorldAsyncSafe(sender, creator, full, worldFolder));
                        } catch (IOException io) {
                            sendFormatted(sender, NamedTextColor.RED, "messages.error.copy_error", io.getMessage());
                        }
                    });
                    return true;
                } else {
                    sendFormatted(sender, NamedTextColor.YELLOW, "messages.error.preset_not_found", seedOrPreset);
                }
            }
        }

        sendFormatted(sender, NamedTextColor.GREEN, "messages.warn.wait");
        createWorldAsyncSafe(sender, creator, full, worldFolder);
        return true;
    }

    private void createWorldAsyncSafe(CommandSender sender, WorldCreator creator, String fullName, File worldFolder) {
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.world_create_fail", fullName);
            return;
        }
        int y = world.getHighestBlockYAt(0, 0) + 1;
        world.setSpawnLocation(new Location(world, 0.5, y, 0.5));
        sendFormatted(sender, NamedTextColor.GREEN, "messages.success.add", fullName);
        updateCustomDimensionsList();
    }

    private boolean handleTeleport(CommandSender sender, Player target, String[] args) {
        String dim = args[2];
        double x = 0.5, y = 100.5, z = 0.5;
        String dir = null;
        if (args.length >= 5) {
            try {
                x = Double.parseDouble(args[3]);
                y = Double.parseDouble(args[4]);
                z = Double.parseDouble(args[5]);
                dir = args.length == 7 ? args[6].toLowerCase() : null;
            } catch (NumberFormatException e) {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.invalid_coords");
                return false;
            }
        }
        String full = "CDim-" + dim;
        World world = Bukkit.getWorld(full);
        if (world == null) {
            sendFormatted(sender, NamedTextColor.RED, "messages.error.dim_not_loaded", full);
            listDimensions(sender);
            return false;
        }
        Location loc = new Location(world, x, y, z);
        if (dir != null) switch (dir) {
            case "north" -> loc.setYaw(180);
            case "south" -> loc.setYaw(0);
            case "west" -> loc.setYaw(90);
            case "east" -> loc.setYaw(-90);
            default -> {
                sendFormatted(sender, NamedTextColor.RED, "messages.error.invalid_direction");
                return false;
            }
        };
        target.teleport(loc);
        return true;
    }

    private boolean listDimensions(CommandSender sender) {
        String joined = String.join(", ", customDimensions);
        if (customDimensions.isEmpty()) {
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.list_empty");
        } else {
            sendFormatted(sender, NamedTextColor.GREEN, "messages.success.list", joined);
        }
        return true;
    }

    // Helpers: localization, IO, presets, tabs
    private String format(String key, String... args) {
        String tpl = messages.getOrDefault(key, key);
        for (int i = 0; i < args.length; i++) tpl = tpl.replace("{" + i + "}", args[i]);
        return tpl;
    }

    private void loadCustomDimensions() {
        customDimensions = Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(n -> n.startsWith("CDim-"))
                .map(n -> n.substring(5))
                .collect(Collectors.toList());
    }

    private void updateCustomDimensionsList() { loadCustomDimensions(); }

    private void deleteDirectory(File dir) {
        try {
            Files.walk(dir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            plugin.getLogger().warning(e.getMessage());
        }
    }

    private void copyFolder(Path src, Path tgt) throws IOException {
        Files.walk(src).forEach(p -> {
            try {
                Path d = tgt.resolve(src.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(d);
                else Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    private void copyPresetsFromJar() {
        File presetDir = new File(plugin.getDataFolder(), "presets");
        if (!presetDir.exists()) presetDir.mkdirs();
        try (JarFile jar = new JarFile(new File(plugin.getClass().getProtectionDomain()
                .getCodeSource().getLocation().toURI()))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().startsWith("presets/") && !e.isDirectory()) {
                    File out = new File(plugin.getDataFolder(), e.getName());
                    if (!out.exists()) {
                        out.getParentFile().mkdirs();
                        try (InputStream in = plugin.getResource(e.getName().substring(8));
                             FileOutputStream fos = new FileOutputStream(out)) {
                            in.transferTo(fos);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) { // "" hier für die Hauptaktion prüfen
            if (hasDimensionPermission(player,"add")) completions.add("add");
            if (hasDimensionPermission(player,"remove")) completions.add("remove");
            if (hasDimensionPermission(player,"teleport")) completions.add("teleport");
            if (hasDimensionPermission(player,"list")) completions.add("list");
            if (hasDimensionPermission(player,"reset")) completions.add("reset");
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("teleport")) {
            if ((args[0].equalsIgnoreCase("remove") && hasDimensionPermission(player,"remove")) ||
                    (args[0].equalsIgnoreCase("reset") && hasDimensionPermission(player,"reset"))) {
                completions.addAll(customDimensions);
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("reset")) && hasDimensionPermission(player,"add")) {
            File presetFolder = new File(plugin.getDataFolder(), "presets");
            completions.add(String.valueOf(player.getWorld().getSeed()));
            if (presetFolder.exists()) {
                for (File file : Objects.requireNonNull(presetFolder.listFiles())) {
                    if (file.isDirectory()) completions.add(file.getName());
                }
            }
        } else if (args[0].equalsIgnoreCase("teleport") && hasDimensionPermission(player,"teleport")) {
            Entity entity = (Entity) sender;
            int x = (int) entity.getLocation().getX();
            int y = (int) entity.getLocation().getY();
            int z = (int) entity.getLocation().getZ();
            switch (args.length) {
                case 2 -> Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
                case 3 -> completions.addAll(customDimensions);
                case 4 -> completions.add(x + " " + y + " " + z);
                case 5 -> completions.add(y + " " + z);
                case 6 -> completions.add(String.valueOf(z));
                case 7 -> completions.addAll(Arrays.asList("north", "south", "west", "east"));
            }
        }

        return completions;
    }

}