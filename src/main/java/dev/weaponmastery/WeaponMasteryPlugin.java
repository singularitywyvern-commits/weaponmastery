package dev.weaponmastery;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Weapon Mastery: every weapon tracks its own kills (stored on the item).
 * 4 tiers, 5 kills each, stat-only bonuses, no particles / visual effects.
 */
public final class WeaponMasteryPlugin extends JavaPlugin implements Listener {

    // ---- Tunables -------------------------------------------------------
    static final int KILLS_PER_TIER = 5;
    static final int MAX_TIER = 4;
    static final int MAX_KILLS = KILLS_PER_TIER * MAX_TIER;
    static final long SAME_VICTIM_COOLDOWN_MS = 5L * 60L * 1000L;
    /** Set to true if kills on mobs should also count (no anti-farm for mobs). */
    static final boolean COUNT_MOB_KILLS = false;

    private static final String[] ROMAN = {"-", "I", "II", "III", "IV"};
    private static final DecimalFormat NUM =
            new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));

    // ---- Bonus definitions (totals at Master / Tier IV) -----------------
    /**
     * @param key       short id
     * @param label     text shown in lore
     * @param attr      item attribute to modify, or null if handled by events
     * @param attrTotal attribute amount at Master (ADD_NUMBER)
     * @param shown     amount shown in lore at Master (percent if percent==true)
     */
    record Bonus(String key, String label, Attribute attr, double attrTotal, double shown, boolean percent) {}

    enum Kind {
        SWORD("Sword",
                new Bonus("attack_damage", "attack damage", Attribute.ATTACK_DAMAGE, 2, 2, false),
                new Bonus("attack_speed", "attack speed", Attribute.ATTACK_SPEED, 0.4, 0.4, false)),
        AXE("Axe",
                new Bonus("attack_damage", "attack damage", Attribute.ATTACK_DAMAGE, 3, 3, false),
                // attack_knockback attribute: 1 point = Knockback I (0.5 strength); base hit is 0.4,
                // so +25% of base = 0.1 strength = 0.2 points.
                new Bonus("knockback", "knockback", Attribute.ATTACK_KNOCKBACK, 0.2, 25, true)),
        MACE("Mace",
                new Bonus("attack_damage", "attack damage", Attribute.ATTACK_DAMAGE, 3, 3, false),
                new Bonus("reach", "block reach", Attribute.ENTITY_INTERACTION_RANGE, 0.5, 0.5, false)),
        TRIDENT("Trident",
                new Bonus("attack_damage", "melee damage", Attribute.ATTACK_DAMAGE, 2, 2, false),
                new Bonus("reach", "block reach", Attribute.ENTITY_INTERACTION_RANGE, 1, 1, false),
                new Bonus("projectile_damage", "thrown damage", null, 0, 10, true)),
        BOW("Bow",
                new Bonus("projectile_damage", "arrow damage", null, 0, 20, true),
                new Bonus("speed", "draw speed", null, 0, 15, true)),
        CROSSBOW("Crossbow",
                new Bonus("projectile_damage", "bolt damage", null, 0, 20, true),
                new Bonus("speed", "reload speed", null, 0, 20, true));

        final String display;
        final List<Bonus> bonuses;

        Kind(String display, Bonus... bonuses) {
            this.display = display;
            this.bonuses = List.of(bonuses);
        }

        /** Fraction (e.g. 0.2 = +20%) of a percent bonus at the given tier. */
        double fraction(String key, int tier) {
            for (Bonus b : bonuses) {
                if (b.key().equals(key)) return (b.shown() / 100.0) * tier / MAX_TIER;
            }
            return 0;
        }

        static Kind of(Material m) {
            String n = m.name();
            if (n.endsWith("_SWORD")) return SWORD;
            if (n.endsWith("_AXE")) return AXE;
            if (m == Material.MACE) return MACE;
            if (m == Material.TRIDENT) return TRIDENT;
            if (m == Material.BOW) return BOW;
            if (m == Material.CROSSBOW) return CROSSBOW;
            return null;
        }
    }

    // ---- State ----------------------------------------------------------
    private NamespacedKey killsKey;
    private NamespacedKey loreLinesKey;
    private NamespacedKey projMultKey;
    private final Map<String, Long> recentKills = new HashMap<>();
    private final Set<UUID> reloading = new HashSet<>();

    @Override
    public void onEnable() {
        killsKey = new NamespacedKey(this, "mastery_kills");
        loreLinesKey = new NamespacedKey(this, "mastery_lore_lines");
        projMultKey = new NamespacedKey(this, "mastery_proj_mult");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WeaponMastery enabled.");
    }

    // ---- Item helpers ---------------------------------------------------
    private boolean hasKillData(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(killsKey, PersistentDataType.INTEGER);
    }

    private int getKills(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(killsKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static int tierOf(int kills) {
        return Math.min(MAX_TIER, kills / KILLS_PER_TIER);
    }

    private NamespacedKey modifierKey(Bonus b) {
        return new NamespacedKey(this, "mastery_" + b.key());
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** Writes kill count, attribute bonuses and lore onto the item. */
    private void applyMastery(ItemStack item, Kind kind, int kills) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        int tier = tierOf(kills);

        meta.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, kills);

        // --- attribute modifiers (real stat changes on the item) ---
        boolean hasAttrBonus = false;
        for (Bonus b : kind.bonuses) {
            if (b.attr() != null) hasAttrBonus = true;
        }
        if (hasAttrBonus && (tier > 0 || meta.getAttributeModifiers() != null)) {
            // Adding any custom modifier replaces the item's default ones, so copy defaults first.
            if (meta.getAttributeModifiers() == null) {
                var defaults = item.getType().getDefaultAttributeModifiers(EquipmentSlot.HAND);
                for (var entry : defaults.entries()) {
                    meta.addAttributeModifier(entry.getKey(), entry.getValue());
                }
            }
            for (Bonus b : kind.bonuses) {
                if (b.attr() == null) continue;
                NamespacedKey mk = modifierKey(b);
                Collection<AttributeModifier> existing = meta.getAttributeModifiers(b.attr());
                if (existing != null) {
                    for (AttributeModifier m : new ArrayList<>(existing)) {
                        if (m.getKey().equals(mk)) meta.removeAttributeModifier(b.attr(), m);
                    }
                }
                if (tier > 0) {
                    meta.addAttributeModifier(b.attr(), new AttributeModifier(
                            mk,
                            b.attrTotal() * tier / MAX_TIER,
                            AttributeModifier.Operation.ADD_NUMBER,
                            EquipmentSlotGroup.MAINHAND));
                }
            }
        }

        // --- lore: remove our old lines (always the last N), append fresh ones ---
        List<Component> lore = new ArrayList<>();
        List<Component> current = meta.lore();
        if (current != null) lore.addAll(current);
        Integer oldCount = meta.getPersistentDataContainer().get(loreLinesKey, PersistentDataType.INTEGER);
        if (oldCount != null && oldCount > 0 && lore.size() >= oldCount) {
            for (int i = 0; i < oldCount; i++) lore.remove(lore.size() - 1);
        }

        List<Component> mine = new ArrayList<>();
        String header = kind.display + " Mastery" + (tier == 0 ? "" : " " + ROMAN[tier]) + (tier == MAX_TIER ? " (Master)" : "");
        mine.add(line(header, NamedTextColor.GOLD));
        for (int t = 1; t <= MAX_TIER; t++) {
            int progress = Math.max(0, Math.min(KILLS_PER_TIER, kills - (t - 1) * KILLS_PER_TIER));
            NamedTextColor color = progress >= KILLS_PER_TIER ? NamedTextColor.GREEN
                    : (progress > 0 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
            mine.add(line(kind.display + " Mastery " + ROMAN[t] + ": " + progress + "/" + KILLS_PER_TIER + " kills", color));
        }
        if (tier == 0) {
            mine.add(line("No bonuses yet", NamedTextColor.GRAY));
        } else {
            for (Bonus b : kind.bonuses) {
                double amount = b.shown() * tier / MAX_TIER;
                String text = "+" + NUM.format(amount) + (b.percent() ? "% " : " ") + b.label();
                mine.add(line(text, NamedTextColor.BLUE));
            }
        }
        lore.addAll(mine);
        meta.getPersistentDataContainer().set(loreLinesKey, PersistentDataType.INTEGER, mine.size());
        meta.lore(lore);

        item.setItemMeta(meta);
    }

    // ---- Crafting: mark freshly crafted weapons --------------------------
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (e.isRepair()) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null) return;
        Kind kind = Kind.of(result.getType());
        if (kind == null || hasKillData(result)) return;
        ItemStack copy = result.clone();
        applyMastery(copy, kind, 0);
        e.getInventory().setResult(copy);
    }

    // ---- Kill tracking --------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        boolean victimIsPlayer = victim instanceof Player;
        if (!victimIsPlayer && !COUNT_MOB_KILLS) return;

        EntityDamageEvent last = victim.getLastDamageCause();
        if (!(last instanceof EntityDamageByEntityEvent byEntity)) return;

        Entity damager = byEntity.getDamager();
        Player killer = null;
        ItemStack weapon = null;
        EquipmentSlot slot = null;
        Trident thrown = null;

        if (damager instanceof Player p) {
            killer = p;
            slot = EquipmentSlot.HAND;
            weapon = p.getInventory().getItemInMainHand();
        } else if (damager instanceof AbstractArrow arrow) {
            ProjectileSource src = arrow.getShooter();
            if (src instanceof Player p) {
                killer = p;
                if (arrow instanceof Trident t) {
                    thrown = t;
                    weapon = t.getItemStack();
                } else {
                    PlayerInventory inv = p.getInventory();
                    ItemStack main = inv.getItemInMainHand();
                    ItemStack off = inv.getItemInOffHand();
                    if (main.getType() == Material.BOW || main.getType() == Material.CROSSBOW) {
                        slot = EquipmentSlot.HAND;
                        weapon = main;
                    } else if (off.getType() == Material.BOW || off.getType() == Material.CROSSBOW) {
                        slot = EquipmentSlot.OFF_HAND;
                        weapon = off;
                    }
                }
            }
        }

        if (killer == null || weapon == null || killer.equals(victim)) return;
        Kind kind = Kind.of(weapon.getType());
        if (kind == null) return;

        int kills = getKills(weapon);
        if (kills >= MAX_KILLS) return;

        // Anti-farming: same victim within 5 minutes doesn't count.
        if (victimIsPlayer) {
            long now = System.currentTimeMillis();
            recentKills.values().removeIf(t -> now - t > SAME_VICTIM_COOLDOWN_MS);
            String key = killer.getUniqueId() + ":" + victim.getUniqueId();
            Long prev = recentKills.get(key);
            if (prev != null && now - prev < SAME_VICTIM_COOLDOWN_MS) {
                killer.sendActionBar(line("Same player killed too recently - kill doesn't count.", NamedTextColor.RED));
                return;
            }
            recentKills.put(key, now);
        }

        int oldTier = tierOf(kills);
        int newKills = kills + 1;
        ItemStack updated = weapon.clone();
        applyMastery(updated, kind, newKills);

        if (thrown != null) {
            thrown.setItemStack(updated);
        } else if (slot == EquipmentSlot.HAND) {
            killer.getInventory().setItemInMainHand(updated);
        } else if (slot == EquipmentSlot.OFF_HAND) {
            killer.getInventory().setItemInOffHand(updated);
        }

        int newTier = tierOf(newKills);
        if (newTier > oldTier) {
            killer.sendMessage(line(kind.display + " Mastery " + ROMAN[newTier] + " reached!"
                    + (newTier == MAX_TIER ? " Your weapon is now a Master." : ""), NamedTextColor.GOLD));
        } else {
            int progress = newKills - newTier * KILLS_PER_TIER;
            killer.sendActionBar(line(kind.display + " Mastery " + ROMAN[Math.min(MAX_TIER, newTier + 1)]
                    + ": " + progress + "/" + KILLS_PER_TIER + " kills", NamedTextColor.YELLOW));
        }
    }

    // ---- Ranged bonuses -------------------------------------------------
    /** Bow / crossbow: tag projectile with damage multiplier, apply draw speed to bows. */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        ItemStack bow = e.getBow();
        if (bow == null) return;
        Kind kind = Kind.of(bow.getType());
        if (kind != Kind.BOW && kind != Kind.CROSSBOW) return;
        int tier = tierOf(getKills(bow));
        if (tier == 0) return;

        Entity proj = e.getProjectile();
        double mult = 1.0 + kind.fraction("projectile_damage", tier);
        proj.getPersistentDataContainer().set(projMultKey, PersistentDataType.DOUBLE, mult);

        if (kind == Kind.BOW) {
            // Faster draw: a partial draw behaves as if it were drawn longer.
            double force = e.getForce();
            if (force > 0 && force < 1.0) {
                double s = -1.0 + Math.sqrt(1.0 + 3.0 * force);         // inverse of f = (s^2 + 2s) / 3
                double s2 = Math.min(1.0, s * (1.0 + kind.fraction("speed", tier)));
                double force2 = Math.min(1.0, (s2 * s2 + 2.0 * s2) / 3.0);
                Vector v = proj.getVelocity();
                proj.setVelocity(v.multiply(force2 / force));
                if (force2 >= 1.0 && proj instanceof AbstractArrow aa) aa.setCritical(true);
            }
        }
    }

    /** Trident: tag thrown trident with its damage multiplier. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Trident t)) return;
        if (!(t.getShooter() instanceof Player)) return;
        ItemStack item = t.getItemStack();
        int tier = tierOf(getKills(item));
        if (tier == 0) return;
        double mult = 1.0 + Kind.TRIDENT.fraction("projectile_damage", tier);
        t.getPersistentDataContainer().set(projMultKey, PersistentDataType.DOUBLE, mult);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow arrow)) return;
        Double mult = arrow.getPersistentDataContainer().get(projMultKey, PersistentDataType.DOUBLE);
        if (mult != null) e.setDamage(e.getDamage() * mult);
    }

    // ---- Crossbow reload speed ------------------------------------------
    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        ItemStack item = e.getItem();
        if (hand == null || item == null || item.getType() != Material.CROSSBOW) return;
        if (!(item.getItemMeta() instanceof CrossbowMeta cm) || cm.hasChargedProjectiles()) return;
        int tier = tierOf(getKills(item));
        if (tier == 0) return;

        Player p = e.getPlayer();
        int quickCharge = item.getEnchantmentLevel(Enchantment.QUICK_CHARGE);
        int baseTicks = Math.max(1, 25 - 5 * quickCharge);
        double bonus = Kind.CROSSBOW.fraction("speed", tier);
        int delay = Math.max(1, (int) Math.ceil(baseTicks / (1.0 + bonus)));
        if (delay >= baseTicks) return;
        if (!reloading.add(p.getUniqueId())) return;

        getServer().getScheduler().runTaskLater(this, () -> {
            reloading.remove(p.getUniqueId());
            finishReload(p, hand);
        }, delay);
    }

    private void finishReload(Player p, EquipmentSlot hand) {
        if (!p.isOnline() || !p.isHandRaised()) return;
        PlayerInventory inv = p.getInventory();
        ItemStack held = inv.getItem(hand);
        if (held == null || held.getType() != Material.CROSSBOW) return;
        if (!(held.getItemMeta() instanceof CrossbowMeta cm) || cm.hasChargedProjectiles()) return;

        ItemStack ammo = takeAmmo(p);
        if (ammo == null) return;

        int count = held.getEnchantmentLevel(Enchantment.MULTISHOT) > 0 ? 3 : 1;
        List<ItemStack> charged = new ArrayList<>();
        for (int i = 0; i < count; i++) charged.add(ammo.clone());
        cm.setChargedProjectiles(charged);
        held.setItemMeta(cm);
        inv.setItem(hand, held);
        p.clearActiveItem();
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_CROSSBOW_LOADING_END, 1.0f, 1.0f);
    }

    /** Removes one projectile item from the player's inventory (offhand first) and returns a single copy. */
    private ItemStack takeAmmo(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean consume = p.getGameMode() != GameMode.CREATIVE;

        ItemStack off = inv.getItemInOffHand();
        if (isAmmo(off)) {
            ItemStack one = off.clone();
            one.setAmount(1);
            if (consume) {
                off.setAmount(off.getAmount() - 1);
                inv.setItemInOffHand(off.getAmount() <= 0 ? null : off);
            }
            return one;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (isAmmo(s)) {
                ItemStack one = s.clone();
                one.setAmount(1);
                if (consume) {
                    s.setAmount(s.getAmount() - 1);
                    inv.setItem(i, s.getAmount() <= 0 ? null : s);
                }
                return one;
            }
        }
        return null;
    }

    private static boolean isAmmo(ItemStack s) {
        if (s == null) return false;
        Material m = s.getType();
        return m == Material.ARROW || m == Material.SPECTRAL_ARROW
                || m == Material.TIPPED_ARROW || m == Material.FIREWORK_ROCKET;
    }
}
