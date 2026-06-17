package de.jakomi1.dimensionControl.utils;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public final class DimensionUtils {

    public static final String DIMENSION_PREFIX = "CDim-";
    private static final String DIMENSION_NAMESPACE = "minecraft";

    private final JavaPlugin plugin;
    private final File worldContainer;

    public DimensionUtils(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.worldContainer = Bukkit.getWorldContainer();
    }

    public void setupCustomDimensions() {
        for (String shortName : getCustomDimensions()) {
            ensureWorldLoaded(shortName);
        }
    }

    public CompletableFuture<Void> setupCustomDimensionsAsync() {
        return getCustomDimensionsAsync().thenAccept(shortNames -> {
            for (String shortName : shortNames) {
                ensureWorldLoaded(shortName);
            }
        });
    }

    public void syncPluginPresets() {
        Path presetRoot = plugin.getDataFolder().toPath().resolve("presets");

        try {
            Files.createDirectories(presetRoot);

            Path pluginPath = Path.of(
                    plugin.getClass()
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );

            if (!Files.isRegularFile(pluginPath)) {
                return;
            }

            try (var jar = new java.util.jar.JarFile(pluginPath.toFile())) {

                var entries = jar.entries();

                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();

                    if (entry.isDirectory() || !entry.getName().startsWith("presets/")) {
                        continue;
                    }

                    String relative = entry.getName().substring("presets/".length());

                    Path out = presetRoot.resolve(relative);
                    Files.createDirectories(out.getParent());

                    try (InputStream in = plugin.getResource(entry.getName().substring("presets/".length()))) {
                        if (in == null) {
                            continue;
                        }
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

        } catch (Exception ex) {
            plugin.getLogger().warning("Failed syncing plugin presets: " + ex.getMessage());
        }
    }

    public List<String> getCustomDimensions() {
        TreeSet<String> names = new TreeSet<>();

        scanLegacyWorldFolders(names);
        scanMigratedWorldFolders(names);

        Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> name.startsWith(DIMENSION_PREFIX))
                .map(name -> name.substring(DIMENSION_PREFIX.length()))
                .forEach(names::add);

        return new ArrayList<>(names);
    }


    public Path resolvePresetFolderSync(String presetName) {
        if (presetName == null || presetName.isBlank()) {
            return null;
        }

        try {
            Path presetsRoot = plugin.getDataFolder().toPath().resolve("presets");

            if (!Files.isDirectory(presetsRoot)) {
                return null;
            }

            Path exact = presetsRoot.resolve(presetName);
            if (Files.isDirectory(exact)) {
                return exact;
            }

            try (Stream<Path> stream = Files.list(presetsRoot)) {
                return stream
                        .filter(Files::isDirectory)
                        .filter(path ->
                                path.getFileName()
                                        .toString()
                                        .equalsIgnoreCase(presetName))
                        .findFirst()
                        .orElse(null);
            }

        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to resolve preset folder: " + presetName);
            return null;
        }
    }

    public CompletableFuture<List<String>> getCustomDimensionsAsync() {
        return CompletableFuture.supplyAsync(this::getCustomDimensions, asyncExecutor());
    }

    public boolean dimensionExists(String fullWorldName) {
        if (Bukkit.getWorld(fullWorldName) != null) {
            return true;
        }
        return Files.exists(getLegacyWorldPath(fullWorldName)) || Files.exists(getMigratedWorldPath(fullWorldName));
    }

    public Path getLegacyWorldPath(String fullWorldName) {
        return worldContainer.toPath().resolve(fullWorldName);
    }

    public Path getMigratedWorldPath(String fullWorldName) {
        String primaryWorldName = getPrimaryWorldFolderName();
        return worldContainer.toPath()
                .resolve(primaryWorldName)
                .resolve("dimensions")
                .resolve(DIMENSION_NAMESPACE)
                .resolve(fullWorldName.toLowerCase(Locale.ROOT));
    }

    public void ensureWorldLoaded(String shortName) {
        String fullWorldName = DIMENSION_PREFIX + shortName;

        if (Bukkit.getWorld(fullWorldName) != null) {
            return;
        }

        Path legacyPath = getLegacyWorldPath(fullWorldName);
        Path migratedPath = getMigratedWorldPath(fullWorldName);

        try {
            boolean legacyExists = Files.exists(legacyPath);
            boolean migratedExists = Files.exists(migratedPath);

            if (!legacyExists && !migratedExists) {
                return;
            }

            if (legacyExists && migratedExists) {
                deleteDirectory(legacyPath);
            }

            World world = Bukkit.createWorld(new WorldCreator(fullWorldName));
            if (world == null) {
                plugin.getLogger().warning("Custom world could not be loaded: " + fullWorldName);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Error while loading custom world '" + fullWorldName + "': " + ex.getMessage());
        }
    }

    public void deleteDimensionData(String fullWorldName) throws IOException {
        deleteDirectory(getLegacyWorldPath(fullWorldName));
        deleteDirectory(getMigratedWorldPath(fullWorldName));
    }

    public boolean unloadWorldIfLoaded(String fullWorldName, boolean save) {
        World world = Bukkit.getWorld(fullWorldName);
        if (world == null) {
            return true;
        }
        return Bukkit.unloadWorld(world, save);
    }

    public CompletableFuture<PresetImportResult> importPresetAsync(String presetName, String fullWorldName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path presetRoot = resolvePresetFolder(presetName);
                if (presetRoot == null) {
                    throw new IOException("Preset not found: " + presetName);
                }

                Path contentRoot = resolvePresetContentRoot(presetRoot);
                PresetWorldData metadata = readPresetWorldData(contentRoot);

                Path target = getMigratedWorldPath(fullWorldName);
                deleteDirectory(target);
                Files.createDirectories(target);

                copyPresetContent(contentRoot, presetRoot, target);
                cleanupImportedWorldTarget(target);

                return new PresetImportResult(presetRoot, target, metadata);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor());
    }

    public CompletableFuture<PresetWorldData> readPresetWorldDataAsync(Path presetRoot) {
        return CompletableFuture.supplyAsync(() -> readPresetWorldData(presetRoot), asyncExecutor());
    }

    public WorldCreator createCreatorFromPreset(String fullWorldName, PresetWorldData data) {
        WorldCreator creator = new WorldCreator(fullWorldName)
                .environment(World.Environment.NORMAL);

        if (data != null && data.seed() != null) {
            creator.seed(data.seed());
        } else {
            creator.seed(randomSeed());
        }

        if (data != null && data.generateStructures() != null) {
            creator.generateStructures(data.generateStructures());
        }

        if (data != null && data.hardcore() != null) {
            creator.hardcore(data.hardcore());
        }

        return creator;
    }

    public void applyPresetMetadata(World world, PresetWorldData data) {
        if (world == null || data == null) {
            return;
        }

        if (data.difficulty() != null) {
            world.setDifficulty(data.difficulty());
        }

        if (data.spawnX() != null && data.spawnY() != null && data.spawnZ() != null) {
            float yaw = data.spawnYaw() != null ? data.spawnYaw() : 0.0f;
            float pitch = data.spawnPitch() != null ? data.spawnPitch() : 0.0f;

            world.setSpawnLocation(new Location(
                    world,
                    data.spawnX(),
                    data.spawnY(),
                    data.spawnZ(),
                    yaw,
                    pitch
            ));
        }
    }

    public void preparePresetWorldTarget(Path target) throws IOException {
        deleteDirectory(target);
        Files.createDirectories(target);
    }

    private Executor asyncExecutor() {
        return runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
    }

    private Path resolvePresetFolder(String presetName) throws IOException {
        Path presetsRoot = plugin.getDataFolder().toPath().resolve("presets");
        Path exact = presetsRoot.resolve(presetName);
        if (Files.isDirectory(exact)) {
            return exact;
        }

        if (!Files.isDirectory(presetsRoot)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(presetsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(presetName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Path resolvePresetContentRoot(Path presetRoot) {
        Path nestedWorld = presetRoot.resolve("world");
        if (Files.isDirectory(nestedWorld) && !hasWorldLikeContent(presetRoot)) {
            return nestedWorld;
        }
        return presetRoot;
    }

    private boolean hasWorldLikeContent(Path dir) {
        return Files.isDirectory(dir.resolve("region"))
                || Files.isDirectory(dir.resolve("entities"))
                || Files.isDirectory(dir.resolve("poi"))
                || Files.isDirectory(dir.resolve("playerdata"))
                || Files.isDirectory(dir.resolve("advancements"))
                || Files.isDirectory(dir.resolve("stats"));
    }

    public PresetWorldData readPresetWorldData(Path presetRoot) {
        Path levelDat = presetRoot.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            return PresetWorldData.empty();
        }

        try {
            Object root = readCompressedNbt(levelDat);
            if (root == null) {
                return PresetWorldData.empty();
            }

            Object data = getCompound(root, "Data");
            if (data == null) {
                data = root;
            }

            Long seed = tryReadLong(data, "WorldGenSettings", "seed");
            if (seed == null) seed = tryReadLong(data, "WorldGenSettings", "Seed");
            if (seed == null) seed = tryReadLong(data, "RandomSeed");
            if (seed == null) seed = tryReadLong(data, "Seed");

            Boolean generateStructures = tryReadBoolean(data, "WorldGenSettings", "generate_features");
            if (generateStructures == null) generateStructures = tryReadBoolean(data, "WorldGenSettings", "generateStructures");
            if (generateStructures == null) generateStructures = tryReadBoolean(data, "WorldGenSettings", "MapFeatures");
            if (generateStructures == null) generateStructures = tryReadBoolean(data, "generate_features");
            if (generateStructures == null) generateStructures = tryReadBoolean(data, "MapFeatures");

            Boolean hardcore = tryReadBoolean(data, "hardcore");
            if (hardcore == null) hardcore = tryReadBoolean(data, "Hardcore");

            Difficulty difficulty = readDifficulty(data);

            Integer spawnX = tryReadInt(data, "SpawnX");
            Integer spawnY = tryReadInt(data, "SpawnY");
            Integer spawnZ = tryReadInt(data, "SpawnZ");

            Float spawnYaw = tryReadFloat(data, "SpawnAngle");
            Float spawnPitch = tryReadFloat(data, "SpawnPitch");

            return new PresetWorldData(seed, generateStructures, hardcore, difficulty, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read preset metadata from " + levelDat + ": " + ex.getMessage());
            return PresetWorldData.empty();
        }
    }

    private Object readCompressedNbt(Path levelDat) throws Exception {
        Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");

        Method chosen = null;
        for (Method method : nbtIoClass.getMethods()) {
            if (!method.getName().equals("readCompressed")) {
                continue;
            }
            if (method.getParameterCount() == 1 && InputStream.class.isAssignableFrom(method.getParameterTypes()[0])) {
                chosen = method;
                break;
            }
            if (method.getParameterCount() == 1 && Path.class.isAssignableFrom(method.getParameterTypes()[0])) {
                chosen = method;
                break;
            }
        }

        if (chosen == null) {
            return null;
        }

        Object result;
        if (chosen.getParameterTypes()[0].isAssignableFrom(InputStream.class)) {
            try (InputStream in = Files.newInputStream(levelDat)) {
                result = chosen.invoke(null, in);
            }
        } else {
            result = chosen.invoke(null, levelDat);
        }

        return result;
    }

    private Object getCompound(Object tag, String key) {
        try {
            Method method = tag.getClass().getMethod("getCompound", String.class);
            return method.invoke(tag, key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long tryReadLong(Object tag, String... path) {
        Object current = navigateCompoundPath(tag, path, path.length - 1);
        if (current == null) {
            return null;
        }

        try {
            Method method = current.getClass().getMethod("getLong", String.class);
            Object value = method.invoke(current, path[path.length - 1]);
            return value instanceof Number number ? number.longValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer tryReadInt(Object tag, String... path) {
        Object current = navigateCompoundPath(tag, path, path.length - 1);
        if (current == null) {
            return null;
        }

        try {
            Method method = current.getClass().getMethod("getInt", String.class);
            Object value = method.invoke(current, path[path.length - 1]);
            return value instanceof Number number ? number.intValue() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Float tryReadFloat(Object tag, String... path) {
        Object current = navigateCompoundPath(tag, path, path.length - 1);
        if (current == null) {
            return null;
        }

        try {
            Method method;
            try {
                method = current.getClass().getMethod("getFloat", String.class);
            } catch (NoSuchMethodException ex) {
                method = current.getClass().getMethod("getDouble", String.class);
            }

            Object value = method.invoke(current, path[path.length - 1]);
            if (value instanceof Number number) {
                return number.floatValue();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean tryReadBoolean(Object tag, String... path) {
        Object current = navigateCompoundPath(tag, path, path.length - 1);
        if (current == null) {
            return null;
        }

        try {
            Method method = current.getClass().getMethod("getBoolean", String.class);
            Object value = method.invoke(current, path[path.length - 1]);
            return value instanceof Boolean b ? b : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object navigateCompoundPath(Object root, String[] path, int stopsBeforeLeaf) {
        Object current = root;
        for (int i = 0; i < stopsBeforeLeaf; i++) {
            if (current == null) {
                return null;
            }
            current = getCompound(current, path[i]);
        }
        return current;
    }

    private Difficulty readDifficulty(Object data) {
        try {
            Method method = data.getClass().getMethod("getInt", String.class);
            Object value = method.invoke(data, "Difficulty");
            if (!(value instanceof Number number)) {
                return null;
            }

            return switch (number.intValue()) {
                case 0 -> Difficulty.PEACEFUL;
                case 1 -> Difficulty.EASY;
                case 2 -> Difficulty.NORMAL;
                case 3 -> Difficulty.HARD;
                default -> null;
            };
        } catch (Exception ignored) {
            return null;
        }
    }
    private void copyPresetContent(Path sourceRoot,
                                   Path presetRoot,
                                   Path targetRoot) throws IOException {

        if (Files.exists(sourceRoot)) {

            try (Stream<Path> walk = Files.walk(sourceRoot)) {

                for (Path path : walk.toList()) {

                    Path relative = sourceRoot.relativize(path);

                    if (relative.toString().isEmpty()) {
                        continue;
                    }

                    if (shouldSkipPresetFile(relative)) {
                        continue;
                    }

                    Path normalizedTarget =
                            targetRoot.toAbsolutePath().normalize();

                    Path destination =
                            normalizedTarget
                                    .resolve(relative)
                                    .normalize();

                    /*
                     * ZIP SLIP / PATH ESCAPE PROTECTION
                     */

                    if (!destination.startsWith(normalizedTarget)) {

                        throw new IOException(
                                "Invalid preset entry outside target folder: "
                                        + relative);
                    }

                    if (Files.isDirectory(path)) {

                        Files.createDirectories(destination);

                    } else {

                        Files.createDirectories(
                                Objects.requireNonNull(
                                        destination.getParent()));

                        Files.copy(path,
                                destination,
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        copyIfExists(
                presetRoot.resolve("paper-world.yml"),
                targetRoot.resolve("paper-world.yml"));

        copyIfExists(
                presetRoot.resolve("paper-world-defaults.yml"),
                targetRoot.resolve("paper-world-defaults.yml"));
    }

    private boolean shouldSkipPresetFile(Path relative) {
        String lower = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);

        return lower.equals("level.dat")
                || lower.equals("level.dat_old")
                || lower.equals("uid.dat")
                || lower.equals("session.lock");
    }

    private void cleanupImportedWorldTarget(Path worldPath) throws IOException {
        Files.deleteIfExists(worldPath.resolve("level.dat"));
        Files.deleteIfExists(worldPath.resolve("level.dat_old"));
        Files.deleteIfExists(worldPath.resolve("uid.dat"));
        Files.deleteIfExists(worldPath.resolve("session.lock"));
    }

    private void copyIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            Files.createDirectories(Objects.requireNonNull(target.getParent()));
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void scanLegacyWorldFolders(TreeSet<String> names) {
        File[] files = worldContainer.listFiles();
        if (files == null) {
            return;
        }

        for (File folder : files) {
            if (!folder.isDirectory()) {
                continue;
            }

            String name = folder.getName();
            if (!name.regionMatches(true, 0, DIMENSION_PREFIX, 0, DIMENSION_PREFIX.length())) {
                continue;
            }

            if (!looksLikeWorldFolder(folder.toPath())) {
                continue;
            }

            names.add(name.substring(DIMENSION_PREFIX.length()));
        }
    }

    private void scanMigratedWorldFolders(TreeSet<String> names) {
        Path migratedRoot = worldContainer.toPath()
                .resolve(getPrimaryWorldFolderName())
                .resolve("dimensions")
                .resolve(DIMENSION_NAMESPACE);

        if (!Files.isDirectory(migratedRoot)) {
            return;
        }

        try (Stream<Path> stream = Files.list(migratedRoot)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                String folderName = path.getFileName().toString();
                if (!folderName.regionMatches(true, 0, DIMENSION_PREFIX, 0, DIMENSION_PREFIX.length())) {
                    return;
                }
                if (!looksLikeWorldFolder(path)) {
                    return;
                }
                names.add(folderName.substring(DIMENSION_PREFIX.length()));
            });
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not scan migrated dimensions: " + ex.getMessage());
        }
    }

    private boolean looksLikeWorldFolder(Path folder) {
        return Files.exists(folder.resolve("level.dat"))
                || Files.exists(folder.resolve("region"))
                || Files.exists(folder.resolve("entities"))
                || Files.exists(folder.resolve("poi"));
    }

    private String getPrimaryWorldFolderName() {
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().get(0).getName();
        }
        return "world";
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

    public record PresetWorldData(
            Long seed,
            Boolean generateStructures,
            Boolean hardcore,
            Difficulty difficulty,
            Integer spawnX,
            Integer spawnY,
            Integer spawnZ,
            Float spawnYaw,
            Float spawnPitch
    ) {
        public static PresetWorldData empty() {
            return new PresetWorldData(null, null, null, null, null, null, null, null, null);
        }
    }

    public record PresetImportResult(
            Path presetRoot,
            Path targetWorldPath,
            PresetWorldData metadata
    ) {}
}