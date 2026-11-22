
package ru.alphagrief.alphachatguard;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class AlphaChatGuard extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, String> lastMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMessageTimes = new ConcurrentHashMap<>();

    private static final Pattern TELEGRAM = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me)(?:/|\\b)"
    );

    private static final Pattern DISCORD = Pattern.compile(
            "(?i)(?:https?://)?(?:www\\.)?(?:discord\\.gg|discord(?:app)?\\.com/invite)(?:/|\\b)"
    );

    private static final Pattern WEBSITE = Pattern.compile(
            "(?i)(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:com|net|org|ru|gg|me|io|xyz|info|site|online|shop|top|dev|eu|de|uk|рф)(?:/\\S*)?"
    );

    private static final Pattern IP_ADDRESS = Pattern.compile(
            "(?<![\\d.])(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?::\\d{1,5})?(?![\\d.])"
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("acg").setExecutor(this);

        getLogger().info("AlphaChatGuard enabled!");
        getLogger().info("Anti-Advertisement: ON");
        getLogger().info("Anti-Spam: ON");
    }

    @Override
    public void onDisable() {
        lastMessages.clear();
        lastMessageTimes.clear();

        getLogger().info("AlphaChatGuard disabled!");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {

        Player player = event.getPlayer();

        // Игнорирование для администраторов
        if (player.hasPermission("alphachatguard.bypass")) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText()
                .serialize(event.message());

        if (message.isBlank()) {
            return;
        }

        // Проверка ссылок
        if (isLinkBlocked(message)) {
            event.setCancelled(true);

            sendMessage(player, "link-blocked");
            return;
        }

        UUID uuid = player.getUniqueId();

        // Нормализация сообщения
        String normalized = normalize(message);

        // Проверка одинаковых сообщений
        if (getConfig().getBoolean("settings.anti-spam.duplicate-messages", true)) {

            String previous = lastMessages.get(uuid);

            if (previous != null && previous.equals(normalized)) {
                event.setCancelled(true);

                sendMessage(player, "spam-blocked");
                return;
            }
        }

        // Проверка задержки между сообщениями
        if (getConfig().getBoolean("settings.anti-spam.enabled", true)) {

            int cooldown = getConfig().getInt(
                    "settings.anti-spam.cooldown", 2
            );

            if (cooldown > 0) {

                long now = System.currentTimeMillis();

                Long previousTime = lastMessageTimes.get(uuid);

                if (previousTime != null) {

                    long elapsed = now - previousTime;
                    long required = cooldown * 1000L;

                    if (elapsed < required) {

                        event.setCancelled(true);

                        long remaining = (required - elapsed + 999) / 1000;

                        sendMessage(player, "cooldown",
                                "{seconds}", String.valueOf(remaining));

                        return;
                    }
                }
            }
        }

        // Сохраняем последнее сообщение
        lastMessages.put(uuid, normalized);
        lastMessageTimes.put(uuid, System.currentTimeMillis());
    }

    private boolean isLinkBlocked(String message) {

        if (!getConfig().getBoolean("settings.links.enabled", true)) {
            return false;
        }

        if (getConfig().getBoolean("settings.links.telegram", true)
                && TELEGRAM.matcher(message).find()) {
            return true;
        }

        if (getConfig().getBoolean("settings.links.discord", true)
                && DISCORD.matcher(message).find()) {
            return true;
        }

        if (getConfig().getBoolean("settings.links.websites", true)
                && WEBSITE.matcher(message).find()) {
            return true;
        }

        if (getConfig().getBoolean("settings.links.ip-addresses", true)
                && IP_ADDRESS.matcher(message).find()) {
            return true;
        }

        return false;
    }

    private String normalize(String message) {

        if (!getConfig().getBoolean(
                "settings.anti-spam.normalize-messages", true)) {
            return message.trim();
        }

        return message
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void sendMessage(Player player, String path) {
        sendMessage(player, path, null, null);
    }

    private void sendMessage(
            Player player,
            String path,
            String placeholder,
            String replacement
    ) {

        String prefix = getConfig().getString(
                "messages.prefix", "&6AlphaGrief &8» "
        );

        String message = getConfig().getString(
                "messages." + path, "&cОшибка сообщения!"
        );

        if (placeholder != null && replacement != null) {
            message = message.replace(placeholder, replacement);
        }

        String finalMessage = ChatColor.translateAlternateColorCodes(
                '&', prefix + message
        );

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.sendMessage(finalMessage);
            }
        });
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {

        if (!sender.hasPermission("alphachatguard.admin")) {
            sendConsoleMessage(sender, "no-permission");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {

            reloadConfig();

            sendConsoleMessage(sender, "reload");

            getLogger().info("Configuration reloaded!");

            return true;
        }

        sendConsoleMessage(sender, "usage");
        return true;
    }

    private void sendConsoleMessage(CommandSender sender, String path) {

        String prefix = getConfig().getString(
                "messages.prefix", "&6AlphaGrief &8» "
        );

        String message = getConfig().getString(
                "messages." + path, "&cUnknown message!"
        );

        sender.sendMessage(
                ChatColor.translateAlternateColorCodes(
                        '&', prefix + message
                )
        );
    }
}
