package ru.alphagrief.chatpolls;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ChatPollsPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Integer> votes = new HashMap<>();
    private final Set<UUID> voted = new HashSet<>();
    private final List<Integer> presetOrder = new ArrayList<>();
    private YamlConfiguration data;
    private File dataFile;
    private String question = "";
    private List<String> options = new ArrayList<>();
    private int correct = -1;
    private boolean active;
    private long pollId;
    private int presetCursor;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        long ticks = Math.max(1, getConfig().getLong("settings.auto-interval-minutes", 60)) * 60L * 20L;
        if (getConfig().getBoolean("settings.auto-polls", true)) {
            Bukkit.getScheduler().runTaskTimer(this, this::startAutomaticPoll, ticks, ticks);
        }
        getLogger().info("ChatPolls enabled.");
    }

    @Override public void onDisable() { saveData(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("This command is for players."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("end")) {
            if (!p.hasPermission("chatpolls.admin")) { msg(p, "admin-only"); return true; }
            endPoll(); p.sendMessage(prefix() + color("&aОпрос завершён.")); return true;
        }
        openMain(p);
        return true;
    }

    private void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&0AlphaGrief &8• &0Голосования"));
        item(inv, 11, Material.PAPER, "&6&lГолосовать", "&7Открыть активный опрос");
        item(inv, 15, Material.BOOK, "&eРезультаты", "&7Посмотреть текущие результаты");
        if (p.hasPermission("chatpolls.admin")) item(inv, 22, Material.COMMAND_BLOCK, "&c&lУправление", "&7Создать опрос • пресеты • завершить");
        p.openInventory(inv);
    }

    private void openAdmin(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&0AlphaGrief &8• &0Управление"));
        item(inv, 10, Material.LIME_DYE, "&aСоздать Да/Нет", "&7Быстрый опрос");
        item(inv, 12, Material.BOOK, "&eЗапустить пресет", "&7Случайный вопрос из 10");
        item(inv, 14, Material.PAPER, "&bРезультаты", "&7Посмотреть статистику");
        item(inv, 16, Material.BARRIER, "&cЗавершить опрос", "&7Закрыть текущий опрос");
        item(inv, 22, Material.ARROW, "&7Назад", "&7В главное меню");
        p.openInventory(inv);
    }

    private void openVote(Player p) {
        if (!active) { msg(p, "no-poll"); return; }
        if (voted.contains(p.getUniqueId())) { msg(p, "already-voted"); return; }
        Inventory inv = Bukkit.createInventory(null, 27, color("&0Голосование"));
        item(inv, 4, Material.WRITABLE_BOOK, "&6&l" + question, "&7Выбери один вариант");
        for (int i = 0; i < Math.min(7, options.size()); i++) {
            Material m = options.size() == 2 && i == 0 ? Material.LIME_DYE : options.size() == 2 && i == 1 ? Material.RED_DYE : Material.PAPER;
            item(inv, 10 + i, m, "&f" + options.get(i), "&7Нажми, чтобы проголосовать");
        }
        p.openInventory(inv);
    }

    private void openResults(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, color("&0Результаты опроса"));
        item(inv, 4, Material.BOOK, active ? "&6" + question : "&7Нет активного опроса", "&7Всего голосов: " + voted.size());
        for (int i = 0; i < options.size() && i < 7; i++) {
            int count = votes.getOrDefault(UUID.nameUUIDFromBytes((pollId + ":" + i).getBytes()), 0);
            item(inv, 10 + i, Material.PAPER, "&f" + options.get(i), "&7Голосов: &e" + count);
        }
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (!title.startsWith(color("&0AlphaGrief")) && !title.equals(color("&0Голосование")) && !title.equals(color("&0Результаты опроса"))) return;
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot >= e.getView().getTopInventory().getSize()) return;
        if (title.equals(color("&0AlphaGrief &8• &0Голосования"))) {
            if (slot == 11) openVote(p);
            else if (slot == 15) openResults(p);
            else if (slot == 22 && p.hasPermission("chatpolls.admin")) openAdmin(p);
        } else if (title.equals(color("&0AlphaGrief &8• &0Управление"))) {
            if (slot == 10) { createPoll("Нравится ли тебе обновление AlphaGrief?", List.of("Да", "Нет"), -1); p.closeInventory(); msg(p, "poll-created"); announce(); }
            else if (slot == 12) { startAutomaticPoll(); p.closeInventory(); }
            else if (slot == 14) openResults(p);
            else if (slot == 16) { endPoll(); p.closeInventory(); }
            else if (slot == 22) openMain(p);
        } else if (title.equals(color("&0Голосование"))) {
            if (slot >= 10 && slot < 10 + options.size() && active) castVote(p, slot - 10);
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) {
        String t = e.getView().getTitle();
        if (t.startsWith(color("&0AlphaGrief")) || t.equals(color("&0Голосование")) || t.equals(color("&0Результаты опроса"))) e.setCancelled(true);
    }

    private void castVote(Player p, int option) {
        if (!active || option < 0 || option >= options.size() || voted.contains(p.getUniqueId())) return;
        voted.add(p.getUniqueId());
        votes.put(UUID.nameUUIDFromBytes((pollId + ":" + option).getBytes()), votes.getOrDefault(UUID.nameUUIDFromBytes((pollId + ":" + option).getBytes()), 0) + 1);
        p.closeInventory();
        msg(p, "vote-success");
        if (correct >= 0) {
            String path = option == correct ? "settings.correct-commands" : "settings.wrong-commands";
            for (String cmd : getConfig().getStringList(path)) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        saveData();
    }

    private void createPoll(String q, List<String> opts, int answer) {
        question = q; options = new ArrayList<>(opts); correct = answer; active = true; pollId++;
        votes.clear(); voted.clear(); saveData();
    }

    private void startAutomaticPoll() {
        List<?> presets = getConfig().getList("presets");
        if (presets == null || presets.isEmpty()) return;
        if (presetOrder.isEmpty() || presetCursor >= presetOrder.size()) {
            presetOrder.clear();
            for (int i = 0; i < presets.size(); i++) presetOrder.add(i);
            Collections.shuffle(presetOrder);
            presetCursor = 0;
        }
        Object obj = presets.get(presetOrder.get(presetCursor++));
        if (!(obj instanceof Map<?, ?> map)) return;
        Object q = map.get("question"), o = map.get("options");
        if (q == null || !(o instanceof List<?> list) || list.size() < 2) return;
        List<String> opts = list.stream().map(String::valueOf).toList();
        createPoll(String.valueOf(q), opts, -1);
        announce();
    }

    private void announce() {
        if (!active) return;
        String line = getConfig().getString("messages.announcement", "&6ОПРОС: %question% — /polls");
        Bukkit.broadcastMessage(prefix() + color(line.replace("%question%", question)));
    }

    private void endPoll() { active = false; saveData(); }

    private void loadData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        data = YamlConfiguration.loadConfiguration(dataFile);
        active = data.getBoolean("active", false);
        question = data.getString("question", "");
        options = data.getStringList("options");
        correct = data.getInt("correct", -1);
        pollId = data.getLong("poll-id", 0);
        presetCursor = data.getInt("preset-cursor", 0);
        for (String s : data.getStringList("voted")) try { voted.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        ConfigurationSection sec = data.getConfigurationSection("votes");
        if (sec != null) for (String k : sec.getKeys(false)) try { votes.put(UUID.fromString(k), sec.getInt(k)); } catch (IllegalArgumentException ignored) {}
        for (int i : data.getIntegerList("preset-order")) presetOrder.add(i);
    }

    private void saveData() {
        if (data == null) data = new YamlConfiguration();
        data.set("active", active); data.set("question", question); data.set("options", options);
        data.set("correct", correct); data.set("poll-id", pollId); data.set("preset-cursor", presetCursor);
        data.set("voted", voted.stream().map(UUID::toString).toList());
        data.set("votes", null); for (Map.Entry<UUID,Integer> en : votes.entrySet()) data.set("votes." + en.getKey(), en.getValue());
        data.set("preset-order", presetOrder);
        try { data.save(dataFile); } catch (IOException ex) { getLogger().severe("Could not save data.yml: " + ex.getMessage()); }
    }

    private void item(Inventory inv, int slot, Material mat, String name, String lore) {
        ItemStack stack = new ItemStack(mat); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name)); meta.setLore(List.of(color(lore))); stack.setItemMeta(meta); inv.setItem(slot, stack);
    }
    private String prefix() { return color(getConfig().getString("settings.prefix", "&6AlphaGrief &8» &7")); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private void msg(Player p, String key) { p.sendMessage(prefix() + color(getConfig().getString("messages." + key, ""))); }
}
