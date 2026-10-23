package ru.server.snowevent.manager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import ru.server.snowevent.ConfigManager;
import ru.server.snowevent.SnowEventPlugin;
import ru.server.snowevent.data.CuboidZone;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class GameManager {

    public enum State {
        INACTIVE,
        LOBBY,
        ENDING,
        MATCH
    }

    private static final int MIN_PLAYERS_TO_START = 2;
    private static final int START_COUNTDOWN_SECONDS = 15;
    private static final int STARTING_LIVES = 3;
    private static final int STARTING_SNOWBALL_AMOUNT = 64;
    private static final long MATCH_END_TELEPORT_DELAY_TICKS = 5L * 20L;
    private static final String SNAPSHOT_FILE_NAME = "inventory-snapshots.yml";

    private final SnowEventPlugin plugin;
    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final File snapshotFile;

    private State state = State.INACTIVE;
    private final List<UUID> preRed = new ArrayList<>();
    private final List<UUID> preBlue = new ArrayList<>();
    private final List<UUID> matchRed = new ArrayList<>();
    private final List<UUID> matchBlue = new ArrayList<>();
    private final Map<UUID, Integer> playerLives = new HashMap<>();
    private final Map<UUID, Integer> hitStats = new HashMap<>();
    private final Map<UUID, Integer> headshotStats = new HashMap<>();
    private final Map<UUID, InventorySnapshot> inventorySnapshots = new HashMap<>();

    private BossBar lobbyBossBar;
    private BossBar matchBossBar;
    private BukkitRunnable countdownTask;
    private BukkitRunnable matchTimerTask;
    private int countdownSeconds = START_COUNTDOWN_SECONDS;
    private int matchSecondsRemaining;

    public GameManager(SnowEventPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.messageManager = plugin.getMessageManager();
        this.snapshotFile = new File(plugin.getDataFolder(), SNAPSHOT_FILE_NAME);
        loadSnapshots();
        validateState();
    }

    public synchronized void validateState() {
        if (configManager == null
                || configManager.getLobbyRed() == null
                || configManager.getLobbyBlue() == null
                || configManager.getGameRed() == null
                || configManager.getGameBlue() == null
                || configManager.getSpawnRed() == null
                || configManager.getSpawnBlue() == null) {
            state = State.INACTIVE;
            cancelLobbyCountdown();
            cancelMatchTimer();
            hideAndRemoveMatchBossBar();
            clearLobbyPresence();
            restoreMatchParticipants();
            clearMatchData();
            return;
        }

        if (state == State.INACTIVE) {
            state = State.LOBBY;
        }
    }

    public synchronized void checkLobbyStatus() {
        if (state != State.LOBBY) {
            return;
        }

        removeOffline(preRed);
        removeOffline(preBlue);

        int uniquePlayers = countUniqueLobbyPlayers();
        if (uniquePlayers < MIN_PLAYERS_TO_START) {
            cancelLobbyCountdown();
            return;
        }

        if (countdownTask == null) {
            startLobbyCountdown();
        } else {
            updateLobbyBossBar();
        }
    }

    public synchronized boolean clearEventRespawnIfNeeded(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Location respawnLocation = player.getRespawnLocation();
        if (!isEventRespawnLocation(respawnLocation)) {
            return false;
        }

        player.setRespawnLocation(null, true);
        return true;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized List<UUID> getPreRed() {
        return preRed;
    }

    public synchronized List<UUID> getPreBlue() {
        return preBlue;
    }

    public synchronized List<UUID> getMatchRed() {
        return matchRed;
    }

    public synchronized List<UUID> getMatchBlue() {
        return matchBlue;
    }

    public synchronized Map<UUID, Integer> getPlayerLives() {
        return playerLives;
    }

    public synchronized void updateMatchBossBar() {
        if (matchBossBar == null) {
            return;
        }

        int redLives = getTeamLives(matchRed);
        int blueLives = getTeamLives(matchBlue);
        int totalLives = Math.max(1, redLives + blueLives);

        matchBossBar.setTitle(messageManager.get("bossbar.match-status", Map.of(
                "red_lives", String.valueOf(redLives),
                "blue_lives", String.valueOf(blueLives),
                "time", formatTime(matchSecondsRemaining)
        )));
        matchBossBar.setProgress(Math.max(0.0D, Math.min(1.0D, redLives / (double) totalLives)));

        Set<UUID> participants = new LinkedHashSet<>();
        participants.addAll(matchRed);
        participants.addAll(matchBlue);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (participants.contains(onlinePlayer.getUniqueId())) {
                if (!matchBossBar.getPlayers().contains(onlinePlayer)) {
                    matchBossBar.addPlayer(onlinePlayer);
                }
            } else if (matchBossBar.getPlayers().contains(onlinePlayer)) {
                matchBossBar.removePlayer(onlinePlayer);
            }
        }
    }

    public synchronized boolean isMatchParticipant(UUID playerId) {
        return playerId != null && (matchRed.contains(playerId) || matchBlue.contains(playerId));
    }

    public synchronized boolean isAliveParticipant(UUID playerId) {
        return isMatchParticipant(playerId) && playerLives.getOrDefault(playerId, 0) > 0;
    }

    public synchronized Location getTeamSpawn(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        if (matchRed.contains(playerId)) {
            return configManager.getSpawnRed();
        }
        if (matchBlue.contains(playerId)) {
            return configManager.getSpawnBlue();
        }
        return null;
    }

    public synchronized int decrementLife(UUID playerId) {
        if (playerId == null || !playerLives.containsKey(playerId)) {
            return 0;
        }

        int remainingLives = Math.max(0, playerLives.get(playerId) - 1);
        playerLives.put(playerId, remainingLives);
        updateMatchBossBar();
        return remainingLives;
    }

    public synchronized void recordHit(UUID playerId, boolean headshot) {
        if (playerId == null || !isMatchParticipant(playerId)) {
            return;
        }

        hitStats.merge(playerId, 1, Integer::sum);
        if (headshot) {
            headshotStats.merge(playerId, 1, Integer::sum);
        }
    }

    public synchronized void handlePlayerQuit(UUID playerId) {
        if (playerId == null) {
            return;
        }

        boolean changed = preRed.remove(playerId);
        changed |= preBlue.remove(playerId);

        if ((state == State.MATCH || state == State.ENDING) && isMatchParticipant(playerId)) {
            restorePlayerSnapshot(playerId, Bukkit.getPlayer(playerId));
            matchRed.remove(playerId);
            matchBlue.remove(playerId);
            playerLives.remove(playerId);
            updateMatchBossBar();
            checkMatchEnd();
            return;
        }

        matchRed.remove(playerId);
        matchBlue.remove(playerId);
        playerLives.remove(playerId);

        if (changed && state == State.LOBBY) {
            checkLobbyStatus();
        }
    }

    public synchronized void handleMatchDeparture(Player player) {
        if (player == null || state != State.MATCH) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!isMatchParticipant(playerId)) {
            return;
        }

        restorePlayerSnapshot(playerId, player);
        playerLives.put(playerId, 0);
        matchRed.remove(playerId);
        matchBlue.remove(playerId);
        playerLives.remove(playerId);
        updateMatchBossBar();
        checkMatchEnd();
    }

    public synchronized void handleWorldDeparture(Player player) {
        if (player == null || state != State.MATCH) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!isMatchParticipant(playerId)) {
            return;
        }

        restorePlayerSnapshot(playerId, player);
        matchRed.remove(playerId);
        matchBlue.remove(playerId);
        playerLives.remove(playerId);
        updateMatchBossBar();
        checkMatchEnd();
    }

    public synchronized void restorePendingSnapshot(Player player) {
        if (player == null || isMatchParticipant(player.getUniqueId())) {
            return;
        }
        restorePlayerSnapshot(player.getUniqueId(), player);
    }

    public synchronized void checkMatchEnd() {
        if (state != State.MATCH) {
            return;
        }

        int redAlive = getAliveCount(matchRed);
        int blueAlive = getAliveCount(matchBlue);

        if (redAlive <= 0 && blueAlive <= 0) {
            stopMatch("team.none", List.of());
            return;
        }
        if (redAlive <= 0) {
            stopMatch("team.blue", getAlivePlayers(matchBlue));
            return;
        }
        if (blueAlive <= 0) {
            stopMatch("team.red", getAlivePlayers(matchRed));
        }
    }

    public synchronized void stopMatch(String winnerTeamKey, List<UUID> winners) {
        if (state != State.MATCH) {
            return;
        }

        state = State.ENDING;
        hideAndRemoveMatchBossBar();
        cancelMatchTimer();

        Set<UUID> participants = new LinkedHashSet<>();
        participants.addAll(matchRed);
        participants.addAll(matchBlue);

        String winnerTeam = messageManager.get(winnerTeamKey == null ? "team.none" : winnerTeamKey);
        broadcastMatchResult(winnerTeamKey, winnerTeam, winners, participants);
        playMatchEndSound();

        new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (GameManager.this) {
                    for (UUID playerId : participants) {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player != null && player.isOnline()) {
                            player.setGameMode(GameMode.SURVIVAL);
                            restorePlayerSnapshot(playerId, player);
                            player.performCommand("spawn");
                        }
                    }

                    clearMatchData();
                    state = State.INACTIVE;
                    validateState();
                }
            }
        }.runTaskLater(plugin, MATCH_END_TELEPORT_DELAY_TICKS);
    }

    public synchronized void shutdown() {
        cancelLobbyCountdown();
        cancelMatchTimer();
        hideAndRemoveMatchBossBar();
        clearLobbyPresence();
        restoreMatchParticipants();
        clearMatchData();
        state = State.INACTIVE;
    }

    private void startLobbyCountdown() {
        countdownSeconds = START_COUNTDOWN_SECONDS;
        createLobbyBossBarIfNeeded();
        updateLobbyBossBar();

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (GameManager.this) {
                    if (state != State.LOBBY) {
                        cancelLobbyCountdown();
                        return;
                    }

                    removeOffline(preRed);
                    removeOffline(preBlue);

                    if (countUniqueLobbyPlayers() < MIN_PLAYERS_TO_START) {
                        cancelLobbyCountdown();
                        return;
                    }

                    if (countdownSeconds <= 0) {
                        cancelInternalTask();
                        startMatch();
                        return;
                    }

                    updateLobbyBossBar();
                    countdownSeconds--;
                }
            }
        };

        countdownTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void startMatch() {
        validateState();
        if (state != State.LOBBY) {
            return;
        }

        Location spawnRed = configManager.getSpawnRed();
        Location spawnBlue = configManager.getSpawnBlue();
        if (spawnRed == null || spawnBlue == null) {
            state = State.INACTIVE;
            cancelLobbyCountdown();
            return;
        }

        state = State.MATCH;
        hideAndRemoveLobbyBossBar();

        matchRed.clear();
        matchBlue.clear();
        playerLives.clear();

        appendUnique(matchRed, preRed);
        appendUnique(matchBlue, preBlue);
        rebalanceTeams();

        List<UUID> redSnapshot = List.copyOf(matchRed);
        List<UUID> blueSnapshot = List.copyOf(matchBlue);

        preRed.clear();
        preBlue.clear();

        for (UUID playerId : redSnapshot) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                savePlayerSnapshot(player);
                player.getInventory().clear();
                player.setGameMode(GameMode.SURVIVAL);
                player.setHealth(Math.min(player.getMaxHealth(), 20.0D));
                player.teleport(spawnRed.clone());
                player.getInventory().setItem(0, new ItemStack(Material.SNOWBALL, STARTING_SNOWBALL_AMOUNT));
                playerLives.put(playerId, STARTING_LIVES);
            }
        }

        for (UUID playerId : blueSnapshot) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                savePlayerSnapshot(player);
                player.getInventory().clear();
                player.setGameMode(GameMode.SURVIVAL);
                player.setHealth(Math.min(player.getMaxHealth(), 20.0D));
                player.teleport(spawnBlue.clone());
                player.getInventory().setItem(0, new ItemStack(Material.SNOWBALL, STARTING_SNOWBALL_AMOUNT));
                playerLives.put(playerId, STARTING_LIVES);
            }
        }

        createMatchBossBarIfNeeded();
        startMatchTimer();
        updateMatchBossBar();
    }

    private void startMatchTimer() {
        cancelMatchTimer();
        matchSecondsRemaining = configManager.getMatchDurationSeconds();

        matchTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (GameManager.this) {
                    if (state != State.MATCH) {
                        cancelMatchTimer();
                        return;
                    }

                    updateMatchBossBar();
                    if (matchSecondsRemaining <= 0) {
                        cancelMatchTimer();
                        stopMatchByScore();
                        return;
                    }

                    matchSecondsRemaining--;
                }
            }
        };

        matchTimerTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void rebalanceTeams() {
        LinkedHashSet<UUID> redUnique = new LinkedHashSet<>(matchRed);
        LinkedHashSet<UUID> blueUnique = new LinkedHashSet<>(matchBlue);
        blueUnique.removeAll(redUnique);

        matchRed.clear();
        matchRed.addAll(redUnique);
        matchBlue.clear();
        matchBlue.addAll(blueUnique);

        while (Math.abs(matchRed.size() - matchBlue.size()) > 1) {
            if (matchRed.size() > matchBlue.size()) {
                UUID moved = matchRed.remove(matchRed.size() - 1);
                if (!matchBlue.contains(moved)) {
                    matchBlue.add(moved);
                }
            } else {
                UUID moved = matchBlue.remove(matchBlue.size() - 1);
                if (!matchRed.contains(moved)) {
                    matchRed.add(moved);
                }
            }
        }
    }

    private void appendUnique(List<UUID> target, List<UUID> source) {
        for (UUID playerId : source) {
            Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !target.contains(playerId)) {
                target.add(playerId);
            }
        }
    }

    private void createLobbyBossBarIfNeeded() {
        if (lobbyBossBar == null) {
            lobbyBossBar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
        }
    }

    private void createMatchBossBarIfNeeded() {
        if (matchBossBar == null) {
            matchBossBar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
        }
    }

    private void updateLobbyBossBar() {
        if (lobbyBossBar == null) {
            return;
        }

        lobbyBossBar.setTitle(messageManager.get("bossbar.lobby-countdown", Map.of(
                "seconds", String.valueOf(countdownSeconds)
        )));
        lobbyBossBar.setProgress(Math.max(0.0D, Math.min(1.0D, countdownSeconds / (double) START_COUNTDOWN_SECONDS)));

        Set<UUID> activePlayers = new LinkedHashSet<>();
        activePlayers.addAll(preRed);
        activePlayers.addAll(preBlue);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (activePlayers.contains(onlinePlayer.getUniqueId())) {
                if (!lobbyBossBar.getPlayers().contains(onlinePlayer)) {
                    lobbyBossBar.addPlayer(onlinePlayer);
                }
            } else if (lobbyBossBar.getPlayers().contains(onlinePlayer)) {
                lobbyBossBar.removePlayer(onlinePlayer);
            }
        }
    }

    private void cancelLobbyCountdown() {
        countdownSeconds = START_COUNTDOWN_SECONDS;
        cancelInternalTask();
        hideAndRemoveLobbyBossBar();
    }

    private void cancelInternalTask() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelMatchTimer() {
        if (matchTimerTask != null) {
            matchTimerTask.cancel();
            matchTimerTask = null;
        }
    }

    private void hideAndRemoveLobbyBossBar() {
        if (lobbyBossBar != null) {
            lobbyBossBar.removeAll();
            lobbyBossBar.setVisible(false);
            lobbyBossBar = null;
        }
    }

    private void hideAndRemoveMatchBossBar() {
        if (matchBossBar != null) {
            matchBossBar.removeAll();
            matchBossBar.setVisible(false);
            matchBossBar = null;
        }
    }

    private int countUniqueLobbyPlayers() {
        Set<UUID> uniquePlayers = new LinkedHashSet<>();
        uniquePlayers.addAll(preRed);
        uniquePlayers.addAll(preBlue);
        return uniquePlayers.size();
    }

    private int getTeamLives(List<UUID> team) {
        int totalLives = 0;
        for (UUID playerId : team) {
            totalLives += playerLives.getOrDefault(playerId, 0);
        }
        return totalLives;
    }

    private int getAliveCount(List<UUID> team) {
        int alive = 0;
        for (UUID playerId : team) {
            if (playerLives.getOrDefault(playerId, 0) > 0) {
                alive++;
            }
        }
        return alive;
    }

    private List<UUID> getAlivePlayers(List<UUID> team) {
        List<UUID> alivePlayers = new ArrayList<>();
        for (UUID playerId : team) {
            if (playerLives.getOrDefault(playerId, 0) > 0) {
                alivePlayers.add(playerId);
            }
        }
        return alivePlayers;
    }

    private String formatWinnerNames(List<UUID> winners) {
        if (winners == null || winners.isEmpty()) {
            return messageManager.get("team.none");
        }

        String names = winners.stream()
                .map(Bukkit::getOfflinePlayer)
                .map(offlinePlayer -> offlinePlayer.getName() == null ? "" : offlinePlayer.getName())
                .filter(name -> !name.isBlank())
                .collect(Collectors.joining(", "));

        return names.isBlank() ? messageManager.get("team.none") : names;
    }

    private void stopMatchByScore() {
        int redLives = getTeamLives(matchRed);
        int blueLives = getTeamLives(matchBlue);

        if (redLives > blueLives) {
            stopMatch("team.red", getAlivePlayers(matchRed));
            return;
        }
        if (blueLives > redLives) {
            stopMatch("team.blue", getAlivePlayers(matchBlue));
            return;
        }

        int redAlive = getAliveCount(matchRed);
        int blueAlive = getAliveCount(matchBlue);
        if (redAlive > blueAlive) {
            stopMatch("team.red", getAlivePlayers(matchRed));
            return;
        }
        if (blueAlive > redAlive) {
            stopMatch("team.blue", getAlivePlayers(matchBlue));
            return;
        }

        stopMatch("team.none", List.of());
    }

    private void broadcastMatchResult(String winnerTeamKey, String winnerTeam, List<UUID> winners, Set<UUID> participants) {
        Set<UUID> winnerSet = getWinnerTeamPlayers(winnerTeamKey, winners);
        String prefix = ChatColor.GOLD + "Vibe" + ChatColor.WHITE + "Snow" + ChatColor.AQUA + "Event";
        String resultText = "team.none".equals(winnerTeamKey)
                ? ChatColor.YELLOW + " Ничья!"
                : ChatColor.WHITE + " Победила " + ChatColor.GREEN + formatWinnerTeamName(winnerTeamKey, winnerTeam)
                + ChatColor.WHITE + " команда!";
        List<String> lines = new ArrayList<>();

        lines.add("");
        lines.add(prefix + resultText);
        lines.add(ChatColor.GRAY + "Топ раунда:");

        List<UUID> topPlayers = participants.stream()
                .sorted((first, second) -> {
                    int hitCompare = Integer.compare(hitStats.getOrDefault(second, 0), hitStats.getOrDefault(first, 0));
                    if (hitCompare != 0) {
                        return hitCompare;
                    }
                    return Integer.compare(headshotStats.getOrDefault(second, 0), headshotStats.getOrDefault(first, 0));
                })
                .toList();

        if (topPlayers.isEmpty()) {
            lines.add(ChatColor.GRAY + "Нет статистики попаданий.");
        } else {
            for (UUID playerId : topPlayers) {
                ChatColor playerColor = winnerSet.contains(playerId) ? ChatColor.GREEN : ChatColor.RED;
                lines.add(ChatColor.GRAY + "- "
                        + playerColor + formatPlayerName(playerId)
                        + ChatColor.WHITE + " " + hitStats.getOrDefault(playerId, 0) + " попаданий"
                        + ChatColor.AQUA + " " + headshotStats.getOrDefault(playerId, 0) + " в голову");
            }
        }

        lines.add(ChatColor.GRAY + "Счет жизней: "
                + ChatColor.RED + "Красные " + getTeamLives(matchRed)
                + ChatColor.GRAY + " | "
                + ChatColor.BLUE + "Синие " + getTeamLives(matchBlue));
        lines.add("");

        broadcastEventLines(lines);
        sendMatchResultTitle(participants, winnerTeamKey, winnerTeam);
        plugin.getLogger().info("Match result sent directly to " + Bukkit.getOnlinePlayers().size() + " online player(s).");
    }

    private void broadcastEventLines(List<String> lines) {
        for (String line : lines) {
            Bukkit.getConsoleSender().sendMessage(line);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String line : lines) {
                player.sendMessage(line);
            }
        }
    }

    private Set<UUID> getWinnerTeamPlayers(String winnerTeamKey, List<UUID> fallbackWinners) {
        if ("team.red".equals(winnerTeamKey)) {
            return new LinkedHashSet<>(matchRed);
        }
        if ("team.blue".equals(winnerTeamKey)) {
            return new LinkedHashSet<>(matchBlue);
        }
        return fallbackWinners == null ? Set.of() : new LinkedHashSet<>(fallbackWinners);
    }

    private void sendMatchResultTitle(Set<UUID> participants, String winnerTeamKey, String winnerTeam) {
        String title = ChatColor.GOLD + "Vibe" + ChatColor.WHITE + "Snow" + ChatColor.AQUA + "Event";
        String subtitle = "team.none".equals(winnerTeamKey)
                ? ChatColor.YELLOW + "Ничья!"
                : ChatColor.WHITE + "Победила " + ChatColor.GREEN + formatWinnerTeamName(winnerTeamKey, winnerTeam)
                + ChatColor.WHITE + " команда!";

        for (UUID playerId : participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendTitle(title, subtitle, 10, 80, 20);
            }
        }
    }

    private void playMatchEndSound() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.15F);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6F, 1.6F);
        }
    }

    private String formatTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format("%02d:%02d", safeSeconds / 60, safeSeconds % 60);
    }

    private String formatPlayerName(UUID playerId) {
        if (playerId == null) {
            return "Unknown";
        }

        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null || name.isBlank() ? playerId.toString() : name;
    }

    private String formatWinnerTeamName(String winnerTeamKey, String fallback) {
        if ("team.red".equals(winnerTeamKey)) {
            return "красная";
        }
        if ("team.blue".equals(winnerTeamKey)) {
            return "синяя";
        }
        return fallback == null || fallback.isBlank() ? "победившая" : fallback;
    }

    private void removeOffline(List<UUID> players) {
        players.removeIf(uuid -> uuid == null || !Bukkit.getOfflinePlayer(uuid).isOnline());
    }

    private void clearLobbyPresence() {
        preRed.clear();
        preBlue.clear();
    }

    private void clearMatchData() {
        matchRed.clear();
        matchBlue.clear();
        playerLives.clear();
        hitStats.clear();
        headshotStats.clear();
    }

    private void savePlayerSnapshot(Player player) {
        if (player == null) {
            return;
        }

        inventorySnapshots.putIfAbsent(player.getUniqueId(), InventorySnapshot.capture(player, getSafeRespawnLocation(player)));
        saveSnapshots();
    }

    private Location getSafeRespawnLocation(Player player) {
        Location respawnLocation = player.getRespawnLocation();
        if (isEventRespawnLocation(respawnLocation)) {
            return null;
        }
        return respawnLocation == null ? null : respawnLocation.clone();
    }

    private boolean isEventRespawnLocation(Location location) {
        if (location == null) {
            return false;
        }

        if (isSameBlock(location, configManager.getSpawnRed()) || isSameBlock(location, configManager.getSpawnBlue())) {
            return true;
        }

        CuboidZone gameRed = configManager.getGameRed();
        CuboidZone gameBlue = configManager.getGameBlue();
        return gameRed != null && gameRed.contains(location)
                || gameBlue != null && gameBlue.contains(location);
    }

    private static boolean isSameBlock(Location first, Location second) {
        if (first == null || second == null || first.getWorld() == null || second.getWorld() == null) {
            return false;
        }
        return first.getWorld().getName().equals(second.getWorld().getName())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void restoreMatchParticipants() {
        Set<UUID> participants = new LinkedHashSet<>();
        participants.addAll(matchRed);
        participants.addAll(matchBlue);

        for (UUID playerId : participants) {
            restorePlayerSnapshot(playerId, playerId == null ? null : Bukkit.getPlayer(playerId));
        }
    }

    private void restorePlayerSnapshot(UUID playerId, Player player) {
        if (playerId == null) {
            return;
        }

        InventorySnapshot snapshot = inventorySnapshots.get(playerId);
        if (snapshot == null || player == null || !player.isOnline()) {
            return;
        }

        snapshot.restore(player);
        inventorySnapshots.remove(playerId);
        saveSnapshots();
    }

    private void loadSnapshots() {
        if (!snapshotFile.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(snapshotFile);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                inventorySnapshots.put(UUID.fromString(key), InventorySnapshot.load(section));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid inventory snapshot entry: " + key);
            }
        }

        if (!inventorySnapshots.isEmpty()) {
            plugin.getLogger().info("Loaded " + inventorySnapshots.size()
                    + " pending inventory snapshot(s); they will be restored when those players join.");
        }
    }

    private void saveSnapshots() {
        if (inventorySnapshots.isEmpty()) {
            if (snapshotFile.exists() && !snapshotFile.delete()) {
                plugin.getLogger().warning("Failed to delete " + SNAPSHOT_FILE_NAME + ".");
            }
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, InventorySnapshot> entry : inventorySnapshots.entrySet()) {
            entry.getValue().save(yaml.createSection(entry.getKey().toString()));
        }

        try {
            yaml.save(snapshotFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to save inventory snapshots: " + exception.getMessage());
        }
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        if (items == null) {
            return new ItemStack[0];
        }

        ItemStack[] clone = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            clone[index] = items[index] == null ? null : items[index].clone();
        }
        return clone;
    }

    private record InventorySnapshot(
            ItemStack[] storageContents,
            ItemStack[] armorContents,
            ItemStack[] extraContents,
            GameMode gameMode,
            Location respawnLocation
    ) {

        private static InventorySnapshot capture(Player player, Location respawnLocation) {
            return new InventorySnapshot(
                    cloneItems(player.getInventory().getStorageContents()),
                    cloneItems(player.getInventory().getArmorContents()),
                    cloneItems(player.getInventory().getExtraContents()),
                    player.getGameMode(),
                    respawnLocation == null ? null : respawnLocation.clone()
            );
        }

        private static InventorySnapshot load(ConfigurationSection section) {
            GameMode gameMode = GameMode.valueOf(section.getString("game-mode", GameMode.SURVIVAL.name()));
            Location respawnLocation = null;
            World world = Bukkit.getWorld(section.getString("respawn.world", ""));
            if (world != null) {
                respawnLocation = new Location(
                        world,
                        section.getDouble("respawn.x"),
                        section.getDouble("respawn.y"),
                        section.getDouble("respawn.z"),
                        (float) section.getDouble("respawn.yaw"),
                        (float) section.getDouble("respawn.pitch")
                );
            }

            return new InventorySnapshot(
                    readItems(section, "storage"),
                    readItems(section, "armor"),
                    readItems(section, "extra"),
                    gameMode,
                    respawnLocation
            );
        }

        private void save(ConfigurationSection section) {
            section.set("storage", Arrays.asList(storageContents));
            section.set("armor", Arrays.asList(armorContents));
            section.set("extra", Arrays.asList(extraContents));
            section.set("game-mode", gameMode.name());
            if (respawnLocation != null && respawnLocation.getWorld() != null) {
                section.set("respawn.world", respawnLocation.getWorld().getName());
                section.set("respawn.x", respawnLocation.getX());
                section.set("respawn.y", respawnLocation.getY());
                section.set("respawn.z", respawnLocation.getZ());
                section.set("respawn.yaw", respawnLocation.getYaw());
                section.set("respawn.pitch", respawnLocation.getPitch());
            }
        }

        private static ItemStack[] readItems(ConfigurationSection section, String path) {
            List<?> values = section.getList(path, List.of());
            ItemStack[] items = new ItemStack[values.size()];
            for (int index = 0; index < values.size(); index++) {
                items[index] = values.get(index) instanceof ItemStack item ? item : null;
            }
            return items;
        }

        private void restore(Player player) {
            player.getInventory().clear();
            player.getInventory().setStorageContents(cloneItems(storageContents));
            player.getInventory().setArmorContents(cloneItems(armorContents));
            player.getInventory().setExtraContents(cloneItems(extraContents));
            player.setGameMode(gameMode);
            player.setRespawnLocation(respawnLocation == null ? null : respawnLocation.clone(), true);
            player.updateInventory();
        }
    }
}
