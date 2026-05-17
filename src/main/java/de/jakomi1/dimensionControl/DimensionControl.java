package de.jakomi1.dimensionControl;

import com.cjcrafter.foliascheduler.FoliaCompatibility;
import com.cjcrafter.foliascheduler.ServerImplementation;
import com.cjcrafter.foliascheduler.bukkit.BukkitServer;
import de.jakomi1.dimensionControl.commands.DimensionControlCommand;
import de.jakomi1.dimensionControl.utils.CommandUtils;
import de.jakomi1.dimensionControl.utils.DimensionUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class DimensionControl extends JavaPlugin {
    public static DimensionUtils dimensionUtils;
    public static Map<String, String> messages;
    public static List<Listener> listeners = List.of();
    public static List<CommandUtils.CommandData> commands;
    public static DimensionControl plugin;
    public static ServerImplementation scheduler;
    @Override
    public void onEnable() {
        plugin = this;
        // 1. Ensure the data folder exists
        scheduler = new FoliaCompatibility(plugin).getServerImplementation();
        commands = List.of(
                new CommandUtils.CommandData("dimension", new DimensionControlCommand(), new DimensionControlCommand())
        );


        getDataFolder().mkdirs();
        saveDefaultConfig();
        // 2. Extract presets
        extractPresets();
        String locale = getConfig().getString("lang.selected", "en_us");

        File langFile = new File(getDataFolder(), "lang/" + locale + ".yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        // 3. Always overwrite language files
        saveResource("lang/en_us.yml", true);
        saveResource("lang/de_de.yml", true);

        messages = new HashMap<>();

        ConfigurationSection msgSection = cfg.getConfigurationSection("messages");
        if (msgSection == null) {
            getLogger().warning("The selected language file is missing the 'messages' section. Restoring default language file...");

            // Restore default file
            saveResource("lang/" + locale + ".yml", true);

            // Reload
            cfg = YamlConfiguration.loadConfiguration(langFile);
            msgSection = cfg.getConfigurationSection("messages");

            if (msgSection == null) {
                getLogger().severe("Failed to load 'messages' section even after restoring. Plugin will disable.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        loadMessagesRecursive(msgSection, "messages");

        // 5. Initialize DimensionManager and load existing worlds
        dimensionUtils = new DimensionUtils();
        dimensionUtils.setupCustomDimensions();
        scheduler.global().runDelayed(this::loadWorlds, 1L);
        scheduler.global().runAtFixedRate(this::backupWorlds, 20L * 60 * 5, 20L * 60 * 2);

        registerAllListener();
        registerAllCommands();
    }

    private void registerCommands() {

    }

    private void registerCommand(String command, CommandExecutor executor, TabCompleter tabCompleter) {
        Objects.requireNonNull(getServer().getPluginCommand(command)).setExecutor(executor);
        Objects.requireNonNull(getServer().getPluginCommand(command)).setTabCompleter(tabCompleter);
    }

    private void loadMessagesRecursive(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix + "." + key;
            if (value instanceof ConfigurationSection) {
                loadMessagesRecursive((ConfigurationSection) value, path);
            } else {
                assert value != null;
                messages.put(path, value.toString());
            }
        }
    }
    public static void registerAllListener() {
        listeners.forEach(DimensionControl::registerListener);
    }
    public static void registerListener(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    public static void registerAllCommands() {
        commands.forEach(CommandUtils.CommandData::register);
    }
    private void extractPresets() {
        File dir = new File(getDataFolder(), "presets");
        if (!dir.exists()) dir.mkdirs();

        try {
            File jar = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!jar.getName().endsWith(".jar")) return;

            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    if (je.getName().startsWith("presets/") && !je.isDirectory()) {
                        File out = new File(getDataFolder(), je.getName());
                        out.getParentFile().mkdirs();


                            try (InputStream in = jf.getInputStream(je);
                                 FileOutputStream fos = new FileOutputStream(out, false)) {
                                byte[] buffer = new byte[1024];
                                int len;
                                while ((len = in.read(buffer)) != -1) {
                                    fos.write(buffer, 0, len);
                                }

                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error extracting presets from JAR: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        backupWorlds();
    }

    private void loadWorlds() {
        for (String shortName : dimensionUtils.getCustomDimensions()) {
            String fullName = "CDim-" + shortName;
            File worldFolder = new File(dimensionUtils.worldContainer, fullName);

            File levelDat = new File(worldFolder, "level.dat");
            if (!levelDat.exists()) {
                getLogger().warning("World '" + fullName + "' has no level.dat and will be skipped.");
                continue;
            }
            getLogger().info("Loading custom world: " + fullName);
            Bukkit.createWorld(new WorldCreator(fullName));
        }
    }

    private void backupWorlds() {
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
    }
}
