package ru.server.snowevent;

import org.bukkit.plugin.java.JavaPlugin;
import ru.server.snowevent.commands.EventSnowCmd;
import ru.server.snowevent.listeners.LobbyListener;
import ru.server.snowevent.listeners.MatchListener;
import ru.server.snowevent.manager.GameManager;
import ru.server.snowevent.manager.MessageManager;

import java.io.File;

public final class SnowEventPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private GameManager gameManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledMessages();
        configManager = new ConfigManager(this);
        messageManager = new MessageManager(this);
        gameManager = new GameManager(this);

        EventSnowCmd eventSnowCmd = new EventSnowCmd(this);
        var pluginCommand = getCommand("eventsnow");
        if (pluginCommand == null) {
            throw new IllegalStateException("Command 'eventsnow' is not defined in plugin.yml");
        }
        pluginCommand.setExecutor(eventSnowCmd);
        pluginCommand.setTabCompleter(eventSnowCmd);

        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.shutdown();
            gameManager = null;
        }
        messageManager = null;
        if (configManager != null) {
            configManager.save();
            configManager = null;
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    private void saveBundledMessages() {
        File messagesDirectory = new File(getDataFolder(), "messages");
        if (!messagesDirectory.exists() && !messagesDirectory.mkdirs()) {
            getLogger().warning("Failed to create messages directory.");
        }

        saveResourceIfMissing("messages/ru.yml");
        saveResourceIfMissing("messages/en.yml");
    }

    private void saveResourceIfMissing(String resourcePath) {
        File targetFile = new File(getDataFolder(), resourcePath);
        if (!targetFile.exists()) {
            saveResource(resourcePath, false);
        }
    }
}
