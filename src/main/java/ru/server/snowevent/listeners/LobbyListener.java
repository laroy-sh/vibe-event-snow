package ru.server.snowevent.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import ru.server.snowevent.ConfigManager;
import ru.server.snowevent.SnowEventPlugin;
import ru.server.snowevent.data.CuboidZone;
import ru.server.snowevent.manager.GameManager;

import java.util.List;
import java.util.UUID;

public final class LobbyListener implements Listener {

    private final SnowEventPlugin plugin;
    private final ConfigManager configManager;
    private final GameManager gameManager;

    public LobbyListener(SnowEventPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.gameManager = plugin.getGameManager();
        clearOnlineEventRespawns();
        startLobbyTask();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        gameManager.clearEventRespawnIfNeeded(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        gameManager.handlePlayerQuit(event.getPlayer().getUniqueId());
    }

    private void startLobbyTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }

                gameManager.validateState();
                if (gameManager.getState() != GameManager.State.LOBBY) {
                    return;
                }

                CuboidZone lobbyRed = configManager.getLobbyRed();
                CuboidZone lobbyBlue = configManager.getLobbyBlue();
                if (lobbyRed == null || lobbyBlue == null) {
                    return;
                }

                boolean changed = false;
                List<UUID> preRed = gameManager.getPreRed();
                List<UUID> preBlue = gameManager.getPreBlue();

                synchronized (gameManager) {
                    preRed.removeIf(uuid -> uuid == null || Bukkit.getPlayer(uuid) == null);
                    preBlue.removeIf(uuid -> uuid == null || Bukkit.getPlayer(uuid) == null);

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        UUID playerId = player.getUniqueId();
                        boolean inRed = lobbyRed.contains(player.getLocation());
                        boolean inBlue = lobbyBlue.contains(player.getLocation());

                        if (inRed) {
                            changed |= ensurePresent(preRed, playerId);
                            changed |= ensureAbsent(preBlue, playerId);
                            continue;
                        }

                        if (inBlue) {
                            changed |= ensurePresent(preBlue, playerId);
                            changed |= ensureAbsent(preRed, playerId);
                            continue;
                        }

                        changed |= ensureAbsent(preRed, playerId);
                        changed |= ensureAbsent(preBlue, playerId);
                    }
                }

                if (changed) {
                    gameManager.checkLobbyStatus();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void clearOnlineEventRespawns() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                gameManager.clearEventRespawnIfNeeded(player);
            }
        });
    }

    private boolean ensurePresent(List<UUID> list, UUID playerId) {
        if (playerId == null || list.contains(playerId)) {
            return false;
        }
        list.add(playerId);
        return true;
    }

    private boolean ensureAbsent(List<UUID> list, UUID playerId) {
        return playerId != null && list.remove(playerId);
    }
}
