package ru.server.snowevent.listeners;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import ru.server.snowevent.ConfigManager;
import ru.server.snowevent.SnowEventPlugin;
import ru.server.snowevent.data.CuboidZone;
import ru.server.snowevent.manager.GameManager;
import ru.server.snowevent.manager.MessageManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MatchListener implements Listener {

    private static final long SHOT_COOLDOWN_MILLIS = 600L;
    private static final long SNOWBALL_REFILL_INTERVAL_TICKS = 30L * 20L;
    private static final int HEADSHOT_FREEZE_TICKS = 3 * 20;
    private static final int SNOWBALL_REFILL_AMOUNT = 32;

    private final SnowEventPlugin plugin;
    private final ConfigManager configManager;
    private final GameManager gameManager;
    private final MessageManager messageManager;
    private final Map<UUID, Long> lastShot = new HashMap<>();

    public MatchListener(SnowEventPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.gameManager = plugin.getGameManager();
        this.messageManager = plugin.getMessageManager();
        startTerritoryTask();
        startSnowballRefillTask();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSnowballThrow(PlayerInteractEvent event) {
        if (event == null || gameManager.getState() != GameManager.State.MATCH) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !gameManager.isAliveParticipant(player.getUniqueId())) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        if (event.getItem() == null || event.getItem().getType() != Material.SNOWBALL) {
            return;
        }

        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long lastShotTime = lastShot.getOrDefault(playerId, 0L);
        if (now - lastShotTime < SHOT_COOLDOWN_MILLIS) {
            event.setCancelled(true);
            return;
        }

        lastShot.put(playerId, now);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSnowballDamage(EntityDamageByEntityEvent event) {
        if (event == null || gameManager.getState() != GameManager.State.MATCH) {
            return;
        }

        Entity targetEntity = event.getEntity();
        if (!(targetEntity instanceof Player target)) {
            return;
        }

        Entity damager = event.getDamager();
        if (!(damager instanceof Snowball snowball)) {
            return;
        }

        Projectile projectile = snowball;
        Object shooterObject = projectile.getShooter();
        if (!(shooterObject instanceof Player shooter)) {
            return;
        }

        UUID shooterId = shooter.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (!gameManager.isAliveParticipant(shooterId) || !gameManager.isAliveParticipant(targetId)) {
            event.setCancelled(true);
            return;
        }

        boolean sameTeam = gameManager.getMatchRed().contains(shooterId) && gameManager.getMatchRed().contains(targetId)
                || gameManager.getMatchBlue().contains(shooterId) && gameManager.getMatchBlue().contains(targetId);
        if (sameTeam) {
            event.setCancelled(true);
            return;
        }

        Location snowballLocation = snowball.getLocation();
        Location eyeLocation = target.getEyeLocation();
        if (snowballLocation == null || eyeLocation == null) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        boolean headshot = snowballLocation.getY() >= eyeLocation.getY() - 0.3D;
        if (headshot) {
            target.setFreezeTicks(Math.max(target.getFreezeTicks(), HEADSHOT_FREEZE_TICKS));
        }

        double damage = headshot ? 4.0D : 2.0D;
        gameManager.recordHit(shooterId, headshot);
        spawnHitEffects(target, headshot);
        applyMatchDamage(target, damage);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMatchDamage(EntityDamageEvent event) {
        if (event == null || gameManager.getState() != GameManager.State.MATCH) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!gameManager.isAliveParticipant(playerId)) {
            return;
        }

        event.setCancelled(true);
        applyMatchDamage(player, event.getFinalDamage());
    }

    private void applyMatchDamage(Player player, double damage) {
        if (player == null || damage <= 0.0D) {
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!gameManager.isAliveParticipant(playerId)) {
            return;
        }

        double finalHealth = player.getHealth() - damage;
        if (finalHealth > 0.0D) {
            player.setHealth(finalHealth);
            return;
        }

        player.setHealth(Math.min(player.getMaxHealth(), 20.0D));

        int remainingLives = gameManager.decrementLife(playerId);
        if (remainingLives > 0) {
            Location teamSpawn = gameManager.getTeamSpawn(playerId);
            if (teamSpawn != null) {
                player.teleport(teamSpawn.clone());
            }
            Bukkit.broadcastMessage(messageManager.get("match.life-lost", Map.of(
                    "player", player.getName(),
                    "lives", String.valueOf(remainingLives)
            )));
        } else {
            player.setGameMode(GameMode.SPECTATOR);
            spawnEliminationEffects(player);
            Bukkit.broadcastMessage(messageManager.get("match.eliminated", Map.of(
                    "player", player.getName()
            )));
        }

        gameManager.checkMatchEnd();
    }

    private void spawnHitEffects(Player target, boolean headshot) {
        if (target == null || target.getWorld() == null) {
            return;
        }

        Location location = headshot ? target.getEyeLocation() : target.getLocation().add(0.0D, 1.0D, 0.0D);
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, location, headshot ? 24 : 12, 0.28D, 0.22D, 0.28D, 0.02D);
        target.getWorld().playSound(location, Sound.BLOCK_SNOW_HIT, 0.75F, headshot ? 1.35F : 1.05F);
    }

    private void spawnEliminationEffects(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }

        Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.getWorld().spawnParticle(Particle.SNOWFLAKE, location, 60, 0.7D, 0.55D, 0.7D, 0.08D);
        player.getWorld().spawnParticle(Particle.FIREWORK, location, 20, 0.35D, 0.35D, 0.35D, 0.03D);
        player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, location, 48, 0.45D, 0.55D, 0.45D, 0.12D);
        player.getWorld().playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8F, 1.25F);
        player.getWorld().playSound(location, Sound.ITEM_TOTEM_USE, 0.75F, 1.15F);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        handleDeparture(event.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        handleDeparture(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }

        Player player = event.getPlayer();
        if (gameManager.getState() != GameManager.State.MATCH || !gameManager.isMatchParticipant(player.getUniqueId())) {
            return;
        }
        if (isEventWorld(player)) {
            return;
        }

        Bukkit.broadcastMessage(messageManager.get("match.world-left", Map.of(
                "player", player.getName()
        )));
        gameManager.handleWorldDeparture(player);
    }

    private boolean isEventWorld(Player player) {
        if (player == null || player.getWorld() == null) {
            return false;
        }

        String worldName = player.getWorld().getName();
        CuboidZone redZone = configManager.getGameRed();
        CuboidZone blueZone = configManager.getGameBlue();
        return redZone != null && redZone.worldName().equals(worldName)
                || blueZone != null && blueZone.worldName().equals(worldName);
    }

    private void startTerritoryTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }

                if (gameManager.getState() != GameManager.State.MATCH) {
                    lastShot.clear();
                    return;
                }

                CuboidZone redZone = configManager.getGameRed();
                CuboidZone blueZone = configManager.getGameBlue();
                if (redZone == null || blueZone == null) {
                    return;
                }

                for (UUID playerId : List.copyOf(gameManager.getMatchRed())) {
                    handleTerritory(playerId, blueZone);
                }

                for (UUID playerId : List.copyOf(gameManager.getMatchBlue())) {
                    handleTerritory(playerId, redZone);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void startSnowballRefillTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    cancel();
                    return;
                }

                if (gameManager.getState() != GameManager.State.MATCH) {
                    return;
                }

                refillSnowballs(gameManager.getMatchRed());
                refillSnowballs(gameManager.getMatchBlue());
            }
        }.runTaskTimer(plugin, SNOWBALL_REFILL_INTERVAL_TICKS, SNOWBALL_REFILL_INTERVAL_TICKS);
    }

    private void refillSnowballs(Iterable<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            if (playerId == null || !gameManager.isAliveParticipant(playerId)) {
                continue;
            }

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }

            player.getInventory().addItem(new ItemStack(Material.SNOWBALL, SNOWBALL_REFILL_AMOUNT));
        }
    }

    private void handleTerritory(UUID playerId, CuboidZone enemyZone) {
        if (playerId == null || enemyZone == null || !gameManager.isAliveParticipant(playerId)) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        Location location = player.getLocation();
        if (!enemyZone.contains(location)) {
            return;
        }

        applyMatchDamage(player, 2.0D);
        player.sendTitle(
                messageManager.get("match.enemy-territory.title"),
                messageManager.get("match.enemy-territory.subtitle"),
                0,
                20,
                10
        );
    }

    private void handleDeparture(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        lastShot.remove(playerId);
        if (gameManager.getState() != GameManager.State.MATCH || !gameManager.isMatchParticipant(playerId)) {
            return;
        }

        Bukkit.broadcastMessage(messageManager.get("match.deserter", Map.of(
                "player", player.getName()
        )));
        gameManager.handleMatchDeparture(player);
    }
}
