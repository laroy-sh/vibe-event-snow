package ru.server.snowevent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ru.server.snowevent.data.CuboidZone;

public final class ConfigManager {

    private static final int DEFAULT_MATCH_DURATION_SECONDS = 300;

    private final JavaPlugin plugin;
    private FileConfiguration config;

    private int matchDurationSeconds;
    private CuboidZone lobbyRed;
    private CuboidZone lobbyBlue;
    private CuboidZone gameRed;
    private CuboidZone gameBlue;
    private Location eventSpawn;
    private Location spawnRed;
    private Location spawnBlue;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        config = plugin.getConfig();

        lobbyRed = loadZone("lobby-zones.red");
        lobbyBlue = loadZone("lobby-zones.blue");
        gameRed = loadZone("game-zones.red");
        gameBlue = loadZone("game-zones.blue");
        eventSpawn = loadLocation("event-spawn");
        spawnRed = loadLocation("spawn-points.red");
        spawnBlue = loadLocation("spawn-points.blue");
        matchDurationSeconds = Math.max(30, config.getInt("match-duration-seconds", DEFAULT_MATCH_DURATION_SECONDS));
    }

    public void save() {
        plugin.saveConfig();
    }

    public CuboidZone getLobbyRed() {
        return lobbyRed;
    }

    public CuboidZone getLobbyBlue() {
        return lobbyBlue;
    }

    public CuboidZone getGameRed() {
        return gameRed;
    }

    public CuboidZone getGameBlue() {
        return gameBlue;
    }

    public Location getSpawnRed() {
        return cloneLocation(spawnRed);
    }

    public Location getSpawnBlue() {
        return cloneLocation(spawnBlue);
    }

    public Location getEventSpawn() {
        return cloneLocation(eventSpawn);
    }

    public int getMatchDurationSeconds() {
        return matchDurationSeconds;
    }

    public void saveZone(String path, Location p1, Location p2) {
        validateLocations(path, p1, p2);

        String worldName = p1.getWorld().getName();
        double minX = Math.min(p1.getX(), p2.getX());
        double minY = Math.min(p1.getY(), p2.getY());
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxX = Math.max(p1.getX(), p2.getX());
        double maxY = Math.max(p1.getY(), p2.getY());
        double maxZ = Math.max(p1.getZ(), p2.getZ());

        CuboidZone zone = new CuboidZone(worldName, minX, minY, minZ, maxX, maxY, maxZ);

        config.set(path + ".world", zone.worldName());
        config.set(path + ".minX", zone.minX());
        config.set(path + ".minY", zone.minY());
        config.set(path + ".minZ", zone.minZ());
        config.set(path + ".maxX", zone.maxX());
        config.set(path + ".maxY", zone.maxY());
        config.set(path + ".maxZ", zone.maxZ());
        plugin.saveConfig();

        setZoneCache(path, zone);
    }

    public void saveSpawn(String path, Location loc) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be null or blank");
        }
        if (loc == null || loc.getWorld() == null) {
            throw new IllegalArgumentException("Location and its world cannot be null");
        }

        Location stored = loc.clone();

        config.set(path + ".world", stored.getWorld().getName());
        config.set(path + ".x", stored.getX());
        config.set(path + ".y", stored.getY());
        config.set(path + ".z", stored.getZ());
        config.set(path + ".yaw", stored.getYaw());
        config.set(path + ".pitch", stored.getPitch());
        plugin.saveConfig();

        setSpawnCache(path, stored);
    }

    private CuboidZone loadZone(String path) {
        String worldName = config.getString(path + ".world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        try {
            return new CuboidZone(
                    worldName,
                    config.getDouble(path + ".minX"),
                    config.getDouble(path + ".minY"),
                    config.getDouble(path + ".minZ"),
                    config.getDouble(path + ".maxX"),
                    config.getDouble(path + ".maxY"),
                    config.getDouble(path + ".maxZ")
            );
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid cuboid data at path: " + path);
            return null;
        }
    }

    private Location loadLocation(String path) {
        String worldName = config.getString(path + ".world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
                world,
                config.getDouble(path + ".x"),
                config.getDouble(path + ".y"),
                config.getDouble(path + ".z"),
                (float) config.getDouble(path + ".yaw"),
                (float) config.getDouble(path + ".pitch")
        );
    }

    private void setZoneCache(String path, CuboidZone zone) {
        switch (path) {
            case "lobby-zones.red" -> lobbyRed = zone;
            case "lobby-zones.blue" -> lobbyBlue = zone;
            case "game-zones.red" -> gameRed = zone;
            case "game-zones.blue" -> gameBlue = zone;
            default -> {
            }
        }
    }

    private void setSpawnCache(String path, Location location) {
        Location stored = cloneLocation(location);
        switch (path) {
            case "event-spawn" -> eventSpawn = stored;
            case "spawn-points.red" -> spawnRed = stored;
            case "spawn-points.blue" -> spawnBlue = stored;
            default -> {
            }
        }
    }

    private void validateLocations(String path, Location p1, Location p2) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be null or blank");
        }
        if (p1 == null || p2 == null || p1.getWorld() == null || p2.getWorld() == null) {
            throw new IllegalArgumentException("Locations and their worlds cannot be null");
        }
        if (!p1.getWorld().getName().equals(p2.getWorld().getName())) {
            throw new IllegalArgumentException("Both locations must be in the same world");
        }
    }

    private Location cloneLocation(Location location) {
        return location == null ? null : location.clone();
    }
}
