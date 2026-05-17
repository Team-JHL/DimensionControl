package de.jakomi1.dimensionControl.utils;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DimensionUtils {
    private static final String DIMENSION_PREFIX = "CDim-"; // Custom dimensions prefix
    public final File worldContainer;

    public DimensionUtils() {
        this.worldContainer = Bukkit.getWorldContainer();
    }

    /**
     * Loads or creates all dimensions whose folders start with "CDim-".
     */
    public void setupCustomDimensions() {
        File[] files = worldContainer.listFiles();
        if (files == null) return;

        for (File worldFolder : files) {
            if (worldFolder.isDirectory() && worldFolder.getName().startsWith(DIMENSION_PREFIX)) {
                setupWorld(worldFolder.getName());
            }
        }
    }

    /**
     * Initializes a single world: unloads it, deletes the session.lock, and reloads it.
     * @param worldName The name of the world folder (including prefix).
     */
    private void setupWorld(String worldName) {
        // If the world is already loaded, unload it first
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            Bukkit.unloadWorld(loaded, true);
        }

        // Remove session.lock so WorldCreator can work cleanly
        File sessionLock = new File(worldContainer, worldName + "/session.lock");
        if (sessionLock.exists() && !sessionLock.delete()) {
            Bukkit.getLogger().warning("Could not delete session.lock in world '" + worldName + "'");
        }

        // Create or load the world (again)
        try {
            WorldCreator creator = new WorldCreator(worldName);
            // Optional: customize config, e.g. void generator
            // creator.environment(World.Environment.NORMAL);
            // creator.generator(new EmptyChunkGenerator());

            World world = Bukkit.createWorld(creator);
            if (world == null) {
                Bukkit.getLogger().warning("World '" + worldName + "' could not be created.");
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("Error while creating/loading custom world '" + worldName + "': " + e.getMessage());
        }
    }

    /**
     * Returns a list of all custom dimensions (without prefix).
     */
    public List<String> getCustomDimensions() {
        List<String> dims = new ArrayList<>();
        File[] files = worldContainer.listFiles();
        if (files == null) return dims;

        for (File worldFolder : files) {
            String name = worldFolder.getName();
            if (worldFolder.isDirectory() && name.startsWith(DIMENSION_PREFIX)) {
                dims.add(name.substring(DIMENSION_PREFIX.length()));
            }
        }
        return dims;
    }

    /*
    public void listCustomDimensions(CommandSender sender) {
        List<String> dims = getCustomDimensions();
        if (dims.isEmpty()) {
            sender.sendMessage("No custom dimensions found.");
        } else {
            sender.sendMessage("Custom dimensions:");
            dims.forEach(d -> sender.sendMessage("- " + d));
        }
    }
    */
}
