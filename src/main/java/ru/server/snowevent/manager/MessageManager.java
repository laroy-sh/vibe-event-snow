package ru.server.snowevent.manager;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MessageManager {

    private static final String DEFAULT_LANGUAGE = "ru";

    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    private String language;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String configuredLanguage = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        language = isSupported(configuredLanguage) ? configuredLanguage.toLowerCase() : DEFAULT_LANGUAGE;

        File file = new File(plugin.getDataFolder(), "messages/" + language + ".yml");
        messages = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled = loadBundled(language);

        if (messages.getKeys(true).isEmpty()) {
            if (bundled != null) {
                messages = bundled;
            } else {
                YamlConfiguration defaultFallback = loadBundled(DEFAULT_LANGUAGE);
                messages = defaultFallback == null ? new YamlConfiguration() : defaultFallback;
            }
        } else if (bundled != null) {
            messages.setDefaults(bundled);
            messages.options().copyDefaults(true);
        }
    }

    public String get(String key) {
        return colorize(messages.getString(key, key));
    }

    public String get(String key, Map<String, String> placeholders) {
        String value = get(key);
        if (placeholders == null || placeholders.isEmpty()) {
            return value;
        }

        String resolved = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue();
            resolved = resolved.replace("{" + entry.getKey() + "}", replacement);
        }
        return resolved;
    }

    public List<String> getList(String key, Map<String, String> placeholders) {
        List<String> values = messages.getStringList(key);
        if (values.isEmpty()) {
            return List.of(get(key, placeholders));
        }

        List<String> resolved = new ArrayList<>();
        for (String value : values) {
            resolved.add(resolvePlaceholders(colorize(value), placeholders));
        }
        return resolved;
    }

    public void send(CommandSender sender, String key) {
        if (sender != null) {
            sender.sendMessage(get(key));
        }
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        if (sender != null) {
            sender.sendMessage(get(key, placeholders));
        }
    }

    public String getLanguage() {
        return language;
    }

    private boolean isSupported(String value) {
        return value != null && (value.equalsIgnoreCase("ru") || value.equalsIgnoreCase("en"));
    }

    private YamlConfiguration loadBundled(String lang) {
        try (InputStream inputStream = plugin.getResource("messages/" + lang + ".yml")) {
            if (inputStream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String colorize(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private String resolvePlaceholders(String value, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return value;
        }

        String resolved = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String replacement = entry.getValue() == null ? "" : entry.getValue();
            resolved = resolved.replace("{" + entry.getKey() + "}", replacement);
        }
        return resolved;
    }
}
