package de.jakomi1.dimensionControl.utils;

import de.jakomi1.dimensionControl.commands.EmptyTabCompleter;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import static de.jakomi1.dimensionControl.DimensionControl.plugin;

public class CommandUtils {

    public record CommandData(String command, CommandExecutor executor, TabCompleter tabCompleter) {


        public CommandData(String command, CommandExecutor executor) {
            this(command, executor, null);
        }

        public void register() {
            PluginCommand pluginCommand = plugin.getServer().getPluginCommand(command);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(executor);
                if (tabCompleter != null) {
                    pluginCommand.setTabCompleter(tabCompleter);
                } else {
                    pluginCommand.setTabCompleter(new EmptyTabCompleter());
                }
            } else {
                plugin.getLogger().warning("The command /" + command + " wasn't registered!");
            }
        }
    }
}