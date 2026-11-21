package ru.alphagrief.chatpolls;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PollCreationWizard implements Listener {
    private static final String TITLE = "§0Создание своего опроса";
    private final ChatPollsPlugin plugin;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, String> waiting = new ConcurrentHashMap<>();

    private static final class Draft {
        String question;
        List<String> options = new ArrayList<>();
    }

    public PollCreationWizard(ChatPollsPlugin plugin) { this.plugin = plugin; }

    public void begin(Player player) {
        if (!player.hasPermission("chatpolls.admin")) return;
        drafts.put(player.getUniqueId(), new Draft());
        open(player);
    }

    private void open(Player player) {
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);
        add(inv, 4, Material.BOOK, "&6Текущий опрос",
                "&7Вопрос: " + (d.question == null ? "не задан" : d.question),
                "&7Вариантов: " + d.options.size());
        add(inv, 11, Material.NAME_TAG, "&e1. Ввести вопрос", "&7Напиши свой текст в чат");
        add(inv, 13, Material.PAPER, "&e2. Ввести варианты", "&7Через |, например: Да | Нет | Не знаю");
        add(inv, 15, Material.EMERALD_BLOCK, "&aОпубликовать", "&7Запустить опрос на сервере");
        add(inv, 22, Material.BARRIER, "&cОтмена", "&7Закрыть создание");
        player.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (!player.hasPermission("chatpolls.admin")) { player.closeInventory(); return; }
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
        switch (event.getRawSlot()) {
            case 11 -> ask(player, "question");
            case 13 -> {
                if (d.question == null || d.question.isBlank()) {
                    player.sendMessage(prefix() + "§cСначала введи вопрос."); return;
                }
                ask(player, "options");
            }
            case 15 -> {
                if (d.question == null || d.question.isBlank() || d.options.size() < 2) {
                    player.sendMessage(prefix() + "§cНужен вопрос и минимум два варианта."); return;
                }
                plugin.publishCustomPoll(d.question, d.options);
                drafts.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(prefix() + "§aСвой опрос опубликован!");
            }
            case 22 -> {
                drafts.remove(player.getUniqueId());
                waiting.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(prefix() + "§7Создание отменено.");
            }
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (TITLE.equals(event.getView().getTitle())) event.setCancelled(true);
    }

    private void ask(Player player, String stage) {
        waiting.put(player.getUniqueId(), stage);
        player.closeInventory();
        player.sendMessage(prefix() + (stage.equals("question")
                ? "§eНапиши вопрос в чат. Для отмены введи §cотмена§e."
                : "§eНапиши варианты через §f|§e. Пример: §fДа | Нет | Возможно"));
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String stage = waiting.get(player.getUniqueId());
        if (stage == null) return;
        event.setCancelled(true);
        String input = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (input.equalsIgnoreCase("отмена")) {
                waiting.remove(player.getUniqueId());
                player.sendMessage(prefix() + "§7Ввод отменён.");
                open(player);
                return;
            }
            Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
            if ("question".equals(stage)) {
                if (input.isBlank() || input.length() > 120) {
                    player.sendMessage(prefix() + "§cВопрос должен содержать от 1 до 120 символов.");
                    return;
                }
                d.question = input;
                waiting.put(player.getUniqueId(), "options");
                player.sendMessage(prefix() + "§aВопрос сохранён. Теперь введи варианты через §f|§a.");
                player.sendMessage(prefix() + "§7Например: §fДа | Нет | Не знаю");
                return;
            }
            List<String> parsed = Arrays.stream(input.split("\\|"))
                    .map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(7).toList();
            if (parsed.size() < 2) {
                player.sendMessage(prefix() + "§cНужно минимум два разных варианта. Повтори ввод.");
                return;
            }
            d.options = new ArrayList<>(parsed);
            waiting.remove(player.getUniqueId());
            player.sendMessage(prefix() + "§aВарианты сохранены: §f" + String.join(", ", parsed));
            open(player);
        });
    }

    private void add(Inventory inv, int slot, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(Arrays.stream(lore).map(this::color).toList());
            item.setItemMeta(meta);
        }
        inv.setItem(slot, item);
    }

    private String prefix() { return color(plugin.getConfig().getString("settings.prefix", "&6AlphaGrief &8» &7")); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
}
