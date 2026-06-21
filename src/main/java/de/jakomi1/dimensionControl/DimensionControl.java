package de.jakomi1.dimensionControl;

import com.cjcrafter.foliascheduler.ServerImplementation;
import de.jakomi1.dimensionControl.command.DimensionControlCommand;
import de.jakomi1.dimensionControl.util.CommandUtils;
import de.jakomi1.dimensionControl.util.DimensionUtils;
import dev.faststats.bukkit.BukkitContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DimensionControl extends JavaPlugin {
    private final BukkitContext context = new BukkitContext.Factory(this, "75404d5e6f74c65c3534f1b2514ea253")
            .metrics(dev.faststats.Metrics.Factory::create)
            .create();
    public static DimensionUtils dimensionUtils;
    public static Map<String, String> messages;
    public static List<Listener> listeners = List.of();
    public static List<CommandUtils.CommandData> commands;
    public static DimensionControl plugin;
    public static ServerImplementation scheduler;
    public static final Component chatPrefix = Component.text("[", NamedTextColor.GRAY).append(Component.text("DC", NamedTextColor.GREEN)).append(Component.text("] ", NamedTextColor.GRAY));
    @Override
    public void onEnable() {
        plugin = this;
        scheduler = new com.cjcrafter.foliascheduler.FoliaCompatibility(plugin).getServerImplementation();

        new Metrics(this, 32078);
        context.ready();

        getDataFolder().mkdirs();
        saveDefaultConfig();

        saveResource("lang/en_us.yml", true);
        saveResource("lang/de_de.yml", true);
        loadLanguage();

        dimensionUtils = new DimensionUtils(this);

        DimensionControlCommand dimensionCommand = new DimensionControlCommand(this, messages);
        commands = List.of(
                new CommandUtils.CommandData("dimension", dimensionCommand, dimensionCommand)
        );

        dimensionUtils.syncPluginPresets();
        scheduler.global().runDelayed(this::loadWorlds, 1L);
        scheduler.global().runAtFixedRate(this::backupWorlds, 20L * 60 * 5, 20L * 60 * 2);

        registerAllListener();
        registerAllCommands();
    }

    @Override
    public void onDisable() {
        context.shutdown();
    }

    private void loadLanguage() {
        String locale = getConfig().getString("lang.selected", "en_us");

        File langFile = new File(getDataFolder(), "lang/" + locale + ".yml");
        if (!langFile.exists()) {
            langFile = new File(getDataFolder(), "lang/en_us.yml");
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);
        messages = new HashMap<>();

        ConfigurationSection msgSection = cfg.getConfigurationSection("messages");
        if (msgSection == null) {
            getLogger().severe("No messages section found in language file: " + langFile.getName());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        loadMessagesRecursive(msgSection, "messages");
    }

    private void loadMessagesRecursive(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix + "." + key;

            if (value instanceof ConfigurationSection configurationSection) {
                loadMessagesRecursive(configurationSection, path);
            } else if (value != null) {
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

    private void loadWorlds() {
        if (dimensionUtils != null) {
            dimensionUtils.setupCustomDimensions();
        }
    }

    private void backupWorlds() {
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
    }


}