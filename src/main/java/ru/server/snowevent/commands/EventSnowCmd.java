package ru.server.snowevent.commands;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.server.snowevent.ConfigManager;
import ru.server.snowevent.SnowEventPlugin;
import ru.server.snowevent.manager.MessageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EventSnowCmd implements CommandExecutor, TabCompleter {

    private static final long EVENT_TELEPORT_COOLDOWN_MILLIS = 10_000L;
    private static final List<String> ROOT_ARGS = List.of(
            "spawn",
            "lobby_red",
            "lobby_blue",
            "zone_red",
            "zone_blue",
            "setspawn_red",
            "setspawn_blue"
    );
    private static final List<String> POSITION_ARGS = List.of("pos1", "pos2");

    private final SnowEventPlugin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final Map<UUID, Location> pos1Selections = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2Selections = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastEventTeleport = new ConcurrentHashMap<>();

    public EventSnowCmd(SnowEventPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.messageManager = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "command.only-player");
            return true;
        }

        String[] normalizedArgs = normalizeArgs(args);
        if (normalizedArgs.length == 0) {
            return handleEventTeleport(player);
        }

        if (normalizedArgs[0].equalsIgnoreCase("spawn")) {
            if (!player.hasPermission("snowevent.admin")) {
                messageManager.send(player, "command.no-permission");
                return true;
            }
            return handleEventSpawnSave(player);
        }

        if (!player.hasPermission("snowevent.admin")) {
            messageManager.send(player, "command.no-permission");
            return true;
        }

        return switch (normalizedArgs[0].toLowerCase(Locale.ROOT)) {
            case "lobby_red" -> handleZoneSelection(player, normalizedArgs, "lobby-zones.red", "lobby_red");
            case "lobby_blue" -> handleZoneSelection(player, normalizedArgs, "lobby-zones.blue", "lobby_blue");
            case "zone_red" -> handleZoneSelection(player, normalizedArgs, "game-zones.red", "zone_red");
            case "zone_blue" -> handleZoneSelection(player, normalizedArgs, "game-zones.blue", "zone_blue");
            case "setspawn_red" -> handleTeamSpawnSave(player, "spawn-points.red", "red");
            case "setspawn_blue" -> handleTeamSpawnSave(player, "spawn-points.blue", "blue");
            default -> {
                sendUsage(player);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return ROOT_ARGS;
        }

        if (args.length == 1) {
            if ("event".equalsIgnoreCase(alias)) {
                return filterSuggestions(List.of("snow"), args[0]);
            }
            return filterSuggestions(ROOT_ARGS, args[0]);
        }

        String[] normalizedArgs = normalizeArgs(args);
        if (normalizedArgs.length == 0) {
            return List.of();
        }

        if (normalizedArgs.length == 1) {
            String currentInput = args[args.length - 1];
            if ("event".equalsIgnoreCase(alias) && args.length == 2 && args[0].equalsIgnoreCase("snow")) {
                return filterSuggestions(ROOT_ARGS, currentInput);
            }
            if (requiresPosition(normalizedArgs[0].toLowerCase(Locale.ROOT))) {
                return filterSuggestions(POSITION_ARGS, currentInput);
            }
            return List.of();
        }

        if (normalizedArgs.length == 2 && requiresPosition(normalizedArgs[0].toLowerCase(Locale.ROOT))) {
            return filterSuggestions(POSITION_ARGS, args[args.length - 1]);
        }

        return List.of();
    }

    private boolean handleZoneSelection(Player player, String[] args, String configPath, String zoneName) {
        if (args.length < 2) {
            messageManager.send(player, "command.invalid-position");
            return true;
        }

        Block targetBlock = player.getTargetBlockExact(5);
        if (targetBlock == null) {
            messageManager.send(player, "command.no-target-block");
            return true;
        }

        Location selected = targetBlock.getLocation();
        if (selected == null || selected.getWorld() == null) {
            messageManager.send(player, "command.invalid-target-location");
            return true;
        }

        UUID playerId = player.getUniqueId();

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "pos1" -> {
                pos1Selections.put(playerId, selected);
                messageManager.send(player, "command.position-saved", Map.of(
                        "position", "pos1",
                        "zone", zoneName
                ));
            }
            case "pos2" -> {
                pos2Selections.put(playerId, selected);
                messageManager.send(player, "command.position-saved", Map.of(
                        "position", "pos2",
                        "zone", zoneName
                ));
            }
            default -> {
                messageManager.send(player, "command.invalid-position");
                return true;
            }
        }

        Location pos1 = pos1Selections.get(playerId);
        Location pos2 = pos2Selections.get(playerId);
        if (pos1 != null && pos2 != null) {
            try {
                configManager.saveZone(configPath, pos1, pos2);
                pos1Selections.remove(playerId);
                pos2Selections.remove(playerId);
                plugin.getGameManager().validateState();
                messageManager.send(player, "command.zone-saved", Map.of("zone", zoneName));
            } catch (IllegalArgumentException exception) {
                messageManager.send(player, "command.zone-save-failed");
            }
        }

        return true;
    }

    private boolean handleEventTeleport(Player player) {
        Location eventSpawn = configManager.getEventSpawn();
        if (eventSpawn == null || eventSpawn.getWorld() == null) {
            messageManager.send(player, "command.event-spawn-missing");
            return true;
        }

        long now = System.currentTimeMillis();
        long lastTeleportTime = lastEventTeleport.getOrDefault(player.getUniqueId(), 0L);
        long remainingMillis = EVENT_TELEPORT_COOLDOWN_MILLIS - (now - lastTeleportTime);
        if (remainingMillis > 0L) {
            long seconds = Math.max(1L, (long) Math.ceil(remainingMillis / 1000.0D));
            messageManager.send(player, "command.event-spawn-cooldown", Map.of("seconds", String.valueOf(seconds)));
            return true;
        }

        player.teleport(eventSpawn);
        lastEventTeleport.put(player.getUniqueId(), now);
        messageManager.send(player, "command.event-spawn-teleported");
        return true;
    }

    private boolean handleEventSpawnSave(Player player) {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            messageManager.send(player, "command.spawn-save-failed");
            return true;
        }

        try {
            configManager.saveSpawn("event-spawn", location);
            messageManager.send(player, "command.event-spawn-saved");
        } catch (IllegalArgumentException exception) {
            messageManager.send(player, "command.spawn-save-failed");
        }
        return true;
    }

    private boolean handleTeamSpawnSave(Player player, String configPath, String spawnName) {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            messageManager.send(player, "command.spawn-save-failed");
            return true;
        }

        try {
            configManager.saveSpawn(configPath, location);
            plugin.getGameManager().validateState();
            messageManager.send(player, "command.spawn-saved", Map.of("team", spawnName));
        } catch (IllegalArgumentException exception) {
            messageManager.send(player, "command.spawn-save-failed");
        }
        return true;
    }

    private String[] normalizeArgs(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("snow")) {
            String[] normalized = new String[args.length - 1];
            System.arraycopy(args, 1, normalized, 0, normalized.length);
            return normalized;
        }
        return args;
    }

    private boolean requiresPosition(String value) {
        return value.equals("lobby_red")
                || value.equals("lobby_blue")
                || value.equals("zone_red")
                || value.equals("zone_blue");
    }

    private List<String> filterSuggestions(List<String> suggestions, String input) {
        String normalizedInput = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.startsWith(normalizedInput)) {
                result.add(suggestion);
            }
        }
        return result;
    }

    private void sendUsage(Player player) {
        messageManager.send(player, "command.usage.1");
        messageManager.send(player, "command.usage.2");
        messageManager.send(player, "command.usage.3");
        messageManager.send(player, "command.usage.4");
        messageManager.send(player, "command.usage.5");
        messageManager.send(player, "command.usage.6");
        messageManager.send(player, "command.usage.7");
        messageManager.send(player, "command.usage.8");
    }
}
