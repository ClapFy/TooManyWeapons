package com.clapmc.dragonweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Admin-tier apocalyptic trident — intentionally broken for operator fun.
 * CustomModelData 90201 for resource pack.
 */
public final class TideHitManager implements Listener {

    private static final int CUSTOM_MODEL_DATA = 90201;
    private static final int MODE_COUNT = 14;
    private static final long SUMMONED_WATER_MS = 30_000L;
    private static final long CRYO_SPHERE_MS = 60_000L;
    private static final long ICE_REALM_AUTO_CLEANUP_TICKS = 18_000L;
    private static final int RESERVOIR_MAX = 999;
    private static final int GATHER_CHUNK = 120;
    private static final int SURF_DRAIN_INTERVAL = 40;
    private static final List<String> MODE_NAMES = List.of(
            "gather", "blast", "wall", "lift", "surf", "tsunami", "maelstrom",
            "deluge", "ravage", "execution", "cryo", "grasp", "hydro", "rift");

    private final NamespacedKey itemKey;
    private final NamespacedKey reservoirKey;
    private final NamespacedKey modeKey;
    private final NamespacedKey surfKey;
    private final NamespacedKey hydroKey;

    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastGatherMs = new HashMap<>();
    private final Map<UUID, Long> lastCombatMs = new HashMap<>();
    private final Map<UUID, Integer> surfTickCounter = new HashMap<>();
    private final Map<UUID, Integer> hydroDrainCounter = new HashMap<>();
    private final Map<UUID, UUID> tideGraspTarget = new HashMap<>();
    private final Queue<BlockRevert> pendingBlockReverts = new PriorityQueue<>(Comparator.comparingLong(BlockRevert::revertAtMs));
    private final Map<UUID, UUID> tideRealmBuddy = new HashMap<>();
    private final Map<UUID, IceRealmSession> realmParticipant = new HashMap<>();

    public TideHitManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = itemKey("tide_hit");
        this.reservoirKey = itemKey("tide_reservoir");
        this.modeKey = itemKey("tide_mode");
        this.surfKey = itemKey("tide_surf");
        this.hydroKey = itemKey("tide_hydro");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHudAndSurf, 0L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickReverts, 0L, 1L);
    }

    private static NamespacedKey itemKey(String value) {
        NamespacedKey key = NamespacedKey.fromString("dragonweapons:" + value);
        if (key == null) {
            throw new IllegalArgumentException("Invalid DragonWeapons item key: " + value);
        }
        return key;
    }

    private static NamespacedKey legacyKey(NamespacedKey currentKey) {
        NamespacedKey key = NamespacedKey.fromString("godkit:" + currentKey.getKey());
        if (key == null) {
            throw new IllegalArgumentException("Invalid legacy item key: " + currentKey.getKey());
        }
        return key;
    }

    public ItemStack buildTideHit() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        item.addUnsafeEnchantment(Enchantment.LOYALTY, 5);
        item.addUnsafeEnchantment(Enchantment.IMPALING, 10);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 10);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setUnbreakable(true);
        var customModelData = meta.getCustomModelDataComponent();
        customModelData.setFloats(List.of((float) CUSTOM_MODEL_DATA));
        meta.setCustomModelDataComponent(customModelData);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.displayName(Component.text("☠ Tide Hit — ADMIN", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                loreLine("Apocalyptic operator weapon — not balanced."),
                loreLine("Gather @ 0%: steals nearest water in loaded world — beams from everywhere."),
                loreLine("Drop (Q): cycle modes · /tidemode · /tideguide · /tideleave in Ice Rift"),
                Component.empty(),
                loreLine("Gather … Hydro · Rift = burn all tide, End ice pool"),
                loreLine("Summoned water fades in 30s · CMD " + CUSTOM_MODEL_DATA),
                loreLine("CMD " + CUSTOM_MODEL_DATA + " — trident texture override")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private static Component loreLine(String text) {
        return Component.text(text, NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false);
    }

    public boolean isTideHit(ItemStack stack) {
        if (stack == null || stack.getType() != Material.TRIDENT || !stack.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        return data.has(itemKey, PersistentDataType.BYTE)
                || data.has(legacyKey(itemKey), PersistentDataType.BYTE);
    }

    private ItemStack resolveTideHitStack(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            ItemStack s = player.getInventory().getItemInOffHand();
            return isTideHit(s) ? s : null;
        }
        if (hand == EquipmentSlot.HAND) {
            ItemStack s = player.getInventory().getItemInMainHand();
            return isTideHit(s) ? s : null;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isTideHit(main)) {
            return main;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return isTideHit(off) ? off : null;
    }

    public void sendGuide(CommandSender sender) {
        sender.sendMessage(Component.text("—— Tide Hit (ADMIN / OP) ——", NamedTextColor.RED, TextDecoration.BOLD));
        sender.sendMessage(Component.text("/tidehit — all modes work at 0 tide except Rift (needs ≥1).", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Drop (Q): cycle 14 modes.", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("0 Gather · right-click — pulls nearest water (loaded chunks), removes block, works at 0% tide.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("   Beams converge from all directions + main stream from the source.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("1 Blast · left — obliterating beam, breaks blocks, extreme damage.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("2 Wall · sneak+left — huge ice fortress (temporary).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("3 Lift · right entity — launch targets into the stratosphere.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("4 Surf · sneak+right air — god-speed in water + particle storm.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("5 Tsunami · left — cone + temp flood (water fades 30s).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("6 Maelstrom · left — yank every mob in range toward you.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("7 Deluge · left — flood sphere (water fades 30s).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("8 Ravage · sneak+left — delete blocks in a beam (not bedrock).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("9 Execution · left — one-shot ray to entities in sight.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("10 Cryo · right entity — launch skyward + solid ice sphere (suffocation trap, ice ~1 min).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("11 Grasp · right entity — they follow your aim; right air/block to release (aura).", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("12 Hydro · sneak+left — toggle water-jet stand; move with look, leg particles, drains tide.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("13 Rift · left — burns ALL tide; frozen island ~110 blocks above ground near main island.", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("   Melee-tag a living mob/player in Rift first, then activate to bring them.", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("   /tideleave — exit (guest alone leaves; host exit returns everyone).", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/tidemode <0–13|name> — set mode (names: gather, blast, wall, … rift).", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("   /tidemode <player> <mode> — set another player’s mode (needs dragonweapons.others).", NamedTextColor.GRAY));
    }

    public boolean handleTideModeCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dragonweapons.tidemode")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendTideModeUsage(sender);
            return true;
        }
        Player target;
        String token;
        if (args.length == 1) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(Component.text("Console: use /tidemode <player> <mode>", NamedTextColor.RED));
                return true;
            }
            target = self;
            token = args[0];
        } else {
            if (!sender.hasPermission("dragonweapons.others")) {
                sender.sendMessage(Component.text("You can’t set other players’ modes.", NamedTextColor.RED));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not online: " + args[0], NamedTextColor.RED));
                return true;
            }
            token = args[1];
        }
        Integer mode = parseTideModeToken(token);
        if (mode == null) {
            sender.sendMessage(Component.text("Unknown mode: " + token + ". Use 0–13 or a mode name.", NamedTextColor.RED));
            sendTideModeUsage(sender);
            return true;
        }
        setMode(target, mode);
        Component msg = Component.text("Tide mode: ", NamedTextColor.AQUA).append(Component.text(modeName(mode), NamedTextColor.GOLD));
        target.sendMessage(msg);
        if (!target.equals(sender)) {
            sender.sendMessage(Component.text("Set ", NamedTextColor.GREEN)
                    .append(Component.text(target.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" to ", NamedTextColor.GREEN))
                    .append(Component.text(modeName(mode), NamedTextColor.GOLD)));
        }
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
        return true;
    }

    private void sendTideModeUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /tidemode <0–13|name>", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Names: gather, blast, wall, lift, surf, tsunami, maelstrom, deluge, ravage, execution, cryo, grasp, hydro, rift", NamedTextColor.DARK_GRAY));
    }

    public List<String> tabCompleteTideMode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dragonweapons.tidemode")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (sender instanceof Player) {
                addMatching(suggestions, MODE_NAMES, args[0]);
            }
            if (sender.hasPermission("dragonweapons.others")) {
                addMatching(suggestions, Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
            }
            return suggestions;
        }
        if (args.length == 2 && sender.hasPermission("dragonweapons.others")) {
            List<String> suggestions = new ArrayList<>();
            addMatching(suggestions, MODE_NAMES, args[1]);
            return suggestions;
        }
        return List.of();
    }

    private static void addMatching(List<String> output, Collection<String> candidates, String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .forEach(output::add);
    }

    private static Integer parseTideModeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        try {
            int n = Integer.parseInt(s);
            if (n >= 0 && n < MODE_COUNT) {
                return n;
            }
            return null;
        } catch (NumberFormatException ignored) {
            // Non-numeric input may still be a named mode.
        }
        return switch (s) {
            case "gather", "g" -> 0;
            case "blast", "b" -> 1;
            case "wall", "w" -> 2;
            case "lift", "l" -> 3;
            case "surf", "s" -> 4;
            case "tsunami", "tsu" -> 5;
            case "maelstrom", "mael" -> 6;
            case "deluge", "del" -> 7;
            case "ravage", "rav" -> 8;
            case "execution", "exec", "x" -> 9;
            case "cryo", "c" -> 10;
            case "grasp", "grab" -> 11;
            case "hydro", "h" -> 12;
            case "rift", "ice_realm", "realm" -> 13;
            default -> null;
        };
    }

    /**
     * Called from /tideleave — guest leaves alone; host exit returns everyone and tears down the pocket.
     */
    public boolean handleTideLeaveCommand(Player player) {
        if (!player.hasPermission("dragonweapons.tideleave")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        IceRealmSession session = realmParticipant.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Component.text("You are not in an Ice Rift.", NamedTextColor.GRAY));
            return true;
        }
        if (session.hostId.equals(player.getUniqueId())) {
            destroyIceRealm(session);
        } else {
            leaveRealmGuest(session, player);
        }
        return true;
    }

    public void clearPlayer(UUID id) {
        surfTickCounter.remove(id);
        lastGatherMs.remove(id);
        lastCombatMs.remove(id);
        tideGraspTarget.remove(id);
        hydroDrainCounter.remove(id);
        tideRealmBuddy.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            setHydro(p, false);
        }
    }

    public void shutdown() {
        for (IceRealmSession session : new HashSet<>(realmParticipant.values())) {
            destroyIceRealm(session);
        }
        while (!pendingBlockReverts.isEmpty()) {
            revertBlock(pendingBlockReverts.remove());
        }
        lastGatherMs.clear();
        lastCombatMs.clear();
        surfTickCounter.clear();
        hydroDrainCounter.clear();
        tideGraspTarget.clear();
        tideRealmBuddy.clear();
        realmParticipant.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        IceRealmSession s = realmParticipant.get(p.getUniqueId());
        if (s != null) {
            if (s.hostId.equals(p.getUniqueId())) {
                destroyIceRealm(s);
            } else if (s.guestId != null && s.guestId.equals(p.getUniqueId())) {
                leaveRealmGuest(s, p);
            }
        }
        clearPlayer(p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.NORMAL)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (!isTideHit(attacker.getInventory().getItemInMainHand()) && !isTideHit(attacker.getInventory().getItemInOffHand())) {
            return;
        }
        if (getMode(attacker) != 13) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim) || victim.equals(attacker)) {
            return;
        }
        tideRealmBuddy.put(attacker.getUniqueId(), victim.getUniqueId());
        attacker.sendMessage(Component.text("Tagged " + victim.getName() + " for Ice Rift — left-click to burn all tide and open the pocket.", NamedTextColor.AQUA));
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) {
            return;
        }
        ItemStack item = trident.getItem();
        if (item != null && isTideHit(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack drop = event.getItemDrop().getItemStack();
        if (!isTideHit(drop)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        int mode = (getMode(player) + 1) % MODE_COUNT;
        setMode(player, mode);
        player.sendMessage(Component.text("Tide mode: ", NamedTextColor.RED).append(Component.text(modeName(mode), NamedTextColor.GOLD)));
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.15f, 2f);
        ringParticles(player.getLocation(), player.getWorld(), Color.fromRGB(255, 40, 40), 80);
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.NORMAL)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (resolveTideHitStack(player, event.getHand()) == null) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack main = player.getInventory().getItemInMainHand();
            if (isTideHit(main)) {
                return;
            }
        }
        int mode = getMode(player);
        if (mode != 3 && mode != 10 && mode != 11) {
            return;
        }
        if (!event.getRightClicked().isValid()) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getRightClicked() instanceof LivingEntity target)) {
            return;
        }
        if (mode == 3) {
            if (cooldownCombat(player, 200)) {
                return;
            }
            addReservoir(player, -1);
            target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 200, 6, false, true, true));
            Vector up = new Vector(0, 3.5, 0);
            target.setVelocity(target.getVelocity().add(up));
            Location loc = target.getLocation().add(0, 0.5, 0);
            World w = target.getWorld();
            vortexParticles(loc, w, 120);
            w.spawnParticle(Particle.EXPLOSION, loc, 3, 0.5, 0.5, 0.5, 0);
            w.spawnParticle(Particle.SONIC_BOOM, loc, 1, 0, 0, 0, 0);
            w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.6f);
            w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1.5f);
            player.sendMessage(Component.text("Leviathan toss.", NamedTextColor.RED));
            return;
        }
        if (mode == 10) {
            if (cooldownCombat(player, 600)) {
                return;
            }
            addReservoir(player, -4);
            cryoSphereTrap(target, player);
            return;
        }
        if (mode == 11) {
            UUID cur = tideGraspTarget.get(player.getUniqueId());
            if (cur != null && cur.equals(target.getUniqueId())) {
                tideGraspTarget.remove(player.getUniqueId());
                player.sendMessage(Component.text("Released.", NamedTextColor.AQUA));
                return;
            }
            tideGraspTarget.put(player.getUniqueId(), target.getUniqueId());
            player.sendMessage(Component.text("Grasp — look to steer; right-click air to release.", NamedTextColor.LIGHT_PURPLE));
            player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.4f);
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (resolveTideHitStack(player, event.getHand()) == null) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            ItemStack main = player.getInventory().getItemInMainHand();
            if (isTideHit(main)) {
                return;
            }
        }
        Action a = event.getAction();

        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            int mode = getMode(player);
            boolean sneak = player.isSneaking();
            if (sneak) {
                if (mode == 12) {
                    toggleHydro(player);
                    return;
                }
                if (mode == 2) {
                    if (cooldownCombat(player, 400)) {
                        return;
                    }
                    addReservoir(player, -5);
                    placeMegaIceWall(player);
                } else if (mode == 8) {
                    if (cooldownCombat(player, 150)) {
                        return;
                    }
                    addReservoir(player, -1);
                    ravageBeam(player);
                }
            } else {
                if (cooldownCombat(player, mode == 9 ? 350 : mode == 13 ? 1200 : 120)) {
                    return;
                }
                if (mode == 13 && getReservoir(player) < 1) {
                    player.sendMessage(Component.text("Ice Rift needs at least 1 tide.", NamedTextColor.DARK_RED));
                    return;
                }
                switch (mode) {
                    case 1 -> {
                        addReservoir(player, -1);
                        fireMegaBlast(player);
                    }
                    case 5 -> {
                        addReservoir(player, -3);
                        tsunami(player);
                    }
                    case 6 -> {
                        addReservoir(player, -2);
                        maelstrom(player);
                    }
                    case 7 -> {
                        addReservoir(player, -5);
                        deluge(player);
                    }
                    case 9 -> {
                        addReservoir(player, -2);
                        executionRay(player);
                    }
                    case 13 -> {
                        enterIceRealm(player);
                    }
                    default -> {
                    }
                }
            }
            return;
        }

        if (a == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);
            if (getMode(player) == 11 && tideGraspTarget.containsKey(player.getUniqueId())) {
                tideGraspTarget.remove(player.getUniqueId());
                player.sendMessage(Component.text("Released.", NamedTextColor.AQUA));
                return;
            }
            if (player.isSneaking() && getMode(player) == 4) {
                toggleSurf(player);
                return;
            }
            if (getMode(player) == 0) {
                tryGather(player);
            }
            return;
        }

        if (a == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (getMode(player) == 11 && tideGraspTarget.containsKey(player.getUniqueId())) {
                tideGraspTarget.remove(player.getUniqueId());
                player.sendMessage(Component.text("Released.", NamedTextColor.AQUA));
                return;
            }
            if (getMode(player) == 0) {
                tryGather(player);
            }
        }
    }

    private void tryGather(Player player) {
        if (cooldownGather(player, 120)) {
            return;
        }
        if (getReservoir(player) >= RESERVOIR_MAX) {
            return;
        }
        Block source = findNearestWater(player);
        if (source == null) {
            player.sendMessage(Component.text("No water in loaded chunks.", NamedTextColor.DARK_GRAY));
            return;
        }
        Location from = source.getLocation().add(0.5, 0.5, 0.5);
        removeWater(source);
        addReservoir(player, GATHER_CHUNK);
        Location to = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        drawMegaStream(from, to, world);
        drawConvergingBeams(to, world, 36);
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1.5f, 0.8f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 2f);
        player.playSound(player.getLocation(), Sound.ENTITY_FISH_SWIM, 1.2f, 0.6f);
    }

    /**
     * Nearest water in loaded world: ray fan (fast), then brute scan of chunks around player if needed.
     */
    private Block findNearestWater(Player player) {
        Location pl = player.getLocation();
        World w = pl.getWorld();
        Location eye = player.getEyeLocation();
        Location torso = pl.clone().add(0, 1, 0);
        Block best = null;
        double bestSq = Double.MAX_VALUE;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 448; i++) {
            Vector dir = randomUnitVector(rnd);
            Location start = i < 224 ? eye : torso;
            RayTraceResult r = w.rayTraceBlocks(start, dir, 512, FluidCollisionMode.ALWAYS, true);
            if (r == null || r.getHitBlock() == null) {
                continue;
            }
            Block b = r.getHitBlock();
            if (!isWaterBlock(b)) {
                continue;
            }
            Location hit = b.getLocation().add(0.5, 0.5, 0.5);
            double d = pl.distanceSquared(hit);
            if (d < bestSq) {
                bestSq = d;
                best = b;
            }
        }
        if (best != null) {
            return best;
        }
        best = findNearestWaterBruteNearby(player, 5);
        if (best != null) {
            return best;
        }
        return findNearestWaterCoarseAllLoaded(player);
    }

    /**
     * Strided scan of every loaded chunk + local refine — catches occluded water anywhere in loaded world.
     */
    private Block findNearestWaterCoarseAllLoaded(Player player) {
        Location pl = player.getLocation();
        World w = pl.getWorld();
        double bestSq = Double.MAX_VALUE;
        Block coarse = null;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        for (Chunk chunk : w.getLoadedChunks()) {
            int bx = chunk.getX() << 4;
            int bz = chunk.getZ() << 4;
            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    for (int y = minY; y < maxY; y += 2) {
                        Block b = w.getBlockAt(bx + x, y, bz + z);
                        if (!isWaterBlock(b)) {
                            continue;
                        }
                        double d = pl.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5));
                        if (d < bestSq) {
                            bestSq = d;
                            coarse = b;
                        }
                    }
                }
            }
        }
        if (coarse == null) {
            return null;
        }
        int ox = coarse.getX();
        int oy = coarse.getY();
        int oz = coarse.getZ();
        Block exact = coarse;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block b = w.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (!isWaterBlock(b)) {
                        continue;
                    }
                    double d = pl.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5));
                    if (d < bestSq) {
                        bestSq = d;
                        exact = b;
                    }
                }
            }
        }
        return exact;
    }

    /**
     * Full scan of (2r+1)² chunks around player for true nearest water (rays can miss occluded pools).
     */
    private Block findNearestWaterBruteNearby(Player player, int chunkRadius) {
        Location pl = player.getLocation();
        World w = pl.getWorld();
        int cx = pl.getBlockX() >> 4;
        int cz = pl.getBlockZ() >> 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        double bestSq = Double.MAX_VALUE;
        Block best = null;
        for (int dcx = -chunkRadius; dcx <= chunkRadius; dcx++) {
            for (int dcz = -chunkRadius; dcz <= chunkRadius; dcz++) {
                int chx = cx + dcx;
                int chz = cz + dcz;
                if (!w.isChunkLoaded(chx, chz)) {
                    continue;
                }
                int bx = chx << 4;
                int bz = chz << 4;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = minY; y < maxY; y++) {
                            Block b = w.getBlockAt(bx + x, y, bz + z);
                            if (!isWaterBlock(b)) {
                                continue;
                            }
                            double d = pl.distanceSquared(b.getLocation().add(0.5, 0.5, 0.5));
                            if (d < bestSq) {
                                bestSq = d;
                                best = b;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Vector randomUnitVector(ThreadLocalRandom rnd) {
        double u = rnd.nextDouble();
        double v = rnd.nextDouble();
        double theta = 2 * Math.PI * u;
        double z = 2 * v - 1;
        double r = Math.sqrt(Math.max(0, 1 - z * z));
        return new Vector(r * Math.cos(theta), z, r * Math.sin(theta));
    }

    private static Vector horizontalPerpendicular(Vector direction) {
        Vector perpendicular = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (perpendicular.lengthSquared() < 0.01) {
            return new Vector(1, 0, 0);
        }
        return perpendicular.normalize();
    }

    private static boolean isValidBlockY(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private static void removeWater(Block block) {
        if (block.getType() == Material.WATER) {
            block.setType(Material.AIR);
            return;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Waterlogged wl && wl.isWaterlogged()) {
            wl.setWaterlogged(false);
            block.setBlockData(wl);
        }
    }

    private static boolean isWaterBlock(Block block) {
        if (block.getType() == Material.WATER) {
            return true;
        }
        BlockData data = block.getBlockData();
        return data instanceof Waterlogged wl && wl.isWaterlogged();
    }

    private void drawMegaStream(Location from, Location to, World world) {
        Vector step = to.toVector().subtract(from.toVector());
        double len = Math.max(0.1, step.length());
        int segments = Math.min(140, Math.max(28, (int) (len * 1.8)));
        step.normalize().multiply(len / segments);
        Location cur = from.clone();
        for (int i = 0; i < segments; i++) {
            world.spawnParticle(Particle.DRIPPING_WATER, cur, 8, 0.15, 0.15, 0.15, 0);
            world.spawnParticle(Particle.SPLASH, cur, 12, 0.2, 0.2, 0.2, 0.05);
            world.spawnParticle(Particle.BUBBLE_POP, cur, 6, 0.12, 0.12, 0.12, 0.02);
            world.spawnParticle(Particle.GLOW, cur, 4, 0.1, 0.1, 0.1, 0);
            world.spawnParticle(Particle.DUST, cur, 5, 0.2, 0.2, 0.2, 0,
                    new Particle.DustOptions(Color.fromRGB(80, 200, 255), 1.4f));
            cur.add(step);
        }
    }

    private void drawConvergingBeams(Location to, World world, int beamCount) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int b = 0; b < beamCount; b++) {
            Vector dir = randomUnitVector(rnd);
            double radius = 18 + rnd.nextDouble() * 72;
            Location from = to.clone().add(dir.clone().multiply(radius));
            drawThinStream(from, to, world);
        }
    }

    private void drawThinStream(Location from, Location to, World world) {
        Vector step = to.toVector().subtract(from.toVector());
        double len = Math.max(0.1, step.length());
        int segments = Math.min(80, Math.max(10, (int) (len * 0.9)));
        step.normalize().multiply(len / segments);
        Location cur = from.clone();
        for (int i = 0; i < segments; i++) {
            world.spawnParticle(Particle.SPLASH, cur, 4, 0.08, 0.08, 0.08, 0.02);
            world.spawnParticle(Particle.BUBBLE, cur, 3, 0.06, 0.06, 0.06, 0.01);
            world.spawnParticle(Particle.DUST, cur, 3, 0.1, 0.1, 0.1, 0,
                    new Particle.DustOptions(Color.fromRGB(120, 210, 255), 1.0f));
            cur.add(step);
        }
    }

    private void fireMegaBlast(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location origin = player.getEyeLocation().clone();
        Set<UUID> damaged = new HashSet<>();
        for (double d = 0.35; d <= 55; d += 0.35) {
            Location point = origin.clone().add(dir.clone().multiply(d));
            blastParticles(point, world, 28);
            if (isValidBlockY(world, point.getBlockY())) {
                breakWeakBlock(point.getBlock());
            }
            for (Entity e : world.getNearbyEntities(point, 2.2, 2.2, 2.2)) {
                if (!(e instanceof LivingEntity living) || e.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (!damaged.add(e.getUniqueId())) {
                    continue;
                }
                living.damage(500.0, player);
                living.setVelocity(dir.clone().multiply(2.8).add(new Vector(0, 0.6, 0)));
                living.setFireTicks(0);
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_SHOOT, 0.6f, 0.5f);
    }

    private static void breakWeakBlock(Block b) {
        Material t = b.getType();
        if (t == Material.AIR || t == Material.WATER || t == Material.LAVA || t == Material.CAVE_AIR || t == Material.VOID_AIR) {
            return;
        }
        if (t == Material.BEDROCK || t == Material.BARRIER || t == Material.END_PORTAL_FRAME || t == Material.END_PORTAL) {
            return;
        }
        if (t.getHardness() >= 0 && t.getHardness() < 80) {
            b.breakNaturally();
        }
    }

    private void placeMegaIceWall(Player player) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Vector perp = horizontalPerpendicular(look);
        long revertAt = System.currentTimeMillis() + 45000L;
        for (int row = -7; row <= 7; row++) {
            for (int h = 0; h < 8; h++) {
                Location at = eye.clone().add(look.clone().multiply(6)).add(perp.clone().multiply(row));
                at.add(0, h - 2, 0);
                if (!isValidBlockY(world, at.getBlockY())) {
                    continue;
                }
                Block t = at.getBlock();
                if (t.getType() == Material.AIR || t.getType() == Material.WATER || t.getType() == Material.SHORT_GRASS
                        || t.getType() == Material.TALL_GRASS || t.getType() == Material.SNOW) {
                    placeTemporaryBlock(t, h % 3 == 0 ? Material.BLUE_ICE : Material.PACKED_ICE, revertAt);
                    world.spawnParticle(Particle.SNOWFLAKE, at, 4, 0.2, 0.2, 0.2, 0);
                }
            }
        }
        world.playSound(eye, Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);
        world.spawnParticle(Particle.EXPLOSION, eye.clone().add(look.clone().multiply(6)), 2, 2, 2, 2, 0);
    }

    private void ravageBeam(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location o = player.getEyeLocation().clone();
        for (double d = 0; d <= 42; d += 0.4) {
            Location p = o.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SONIC_BOOM, p, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.DUST, p, 8, 0.15, 0.15, 0.15, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 50, 50), 1.8f));
            if (!isValidBlockY(world, p.getBlockY())) {
                continue;
            }
            Block b = p.getBlock();
            Material t = b.getType();
            if (t != Material.AIR && t != Material.WATER && t != Material.VOID_AIR && t != Material.CAVE_AIR) {
                if (t != Material.BEDROCK && t != Material.BARRIER) {
                    b.setType(Material.AIR);
                    world.spawnParticle(Particle.BLOCK, p, 24, 0.3, 0.3, 0.3, 0.1, t.createBlockData());
                }
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1f, 0.6f);
    }

    private void tsunami(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector right = horizontalPerpendicular(dir);
        Location o = player.getEyeLocation().clone();
        Set<UUID> hit = new HashSet<>();
        Set<String> waterPlaced = new HashSet<>();
        long waterUntil = System.currentTimeMillis() + SUMMONED_WATER_MS;
        for (double d = 1; d <= 35; d += 1.2) {
            Location center = o.clone().add(dir.clone().multiply(d));
            for (double w = -4; w <= 4; w += 1.1) {
                Location p = center.clone().add(right.clone().multiply(w));
                tsunamiParticles(p, world);
                if (!isValidBlockY(world, p.getBlockY())) {
                    continue;
                }
                Block at = p.getBlock();
                breakWeakBlock(at);
                Block place = at;
                if (place.getType() == Material.AIR || place.getType() == Material.CAVE_AIR || place.getType() == Material.VOID_AIR) {
                    String key = world.getUID() + ":" + place.getX() + "," + place.getY() + "," + place.getZ();
                    if (waterPlaced.add(key)) {
                        placeTemporaryBlock(place, Material.WATER, waterUntil);
                    }
                }
                for (Entity e : world.getNearbyEntities(p, 2.5, 2.5, 2.5)) {
                    if (!(e instanceof LivingEntity living) || !hit.add(e.getUniqueId())) {
                        continue;
                    }
                    if (e.getUniqueId().equals(player.getUniqueId())) {
                        continue;
                    }
                    living.damage(200.0, player);
                    living.setVelocity(dir.clone().multiply(1.8).add(new Vector(0, 0.4, 0)));
                }
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.7f);
    }

    private void cryoSphereTrap(LivingEntity target, Player player) {
        World w = target.getWorld();
        double safeY = Math.min(w.getMaxHeight() - 6.0, target.getLocation().getY() + 52.0);
        safeY = Math.max(w.getMinHeight() + 6.0, safeY);
        Location liftTo = target.getLocation().clone();
        liftTo.setY(safeY);
        liftTo.setPitch(target.getLocation().getPitch());
        liftTo.setYaw(target.getLocation().getYaw());
        liftTo.getChunk().load(true);
        if (!target.teleport(liftTo)) {
            player.sendMessage(Component.text("Cryo teleport was blocked.", NamedTextColor.RED));
            return;
        }
        target.setVelocity(new Vector(0, 0.15, 0));
        Location center = liftTo.clone().add(0, target.getHeight() * 0.5, 0);
        double radius = 4.2;
        long revert = System.currentTimeMillis() + CRYO_SPHERE_MS;
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    double dx = x + 0.5;
                    double dy = y + 0.5;
                    double dz = z + 0.5;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > radius) {
                        continue;
                    }
                    Block b = w.getBlockAt(cx + x, cy + y, cz + z);
                    if (b.getType() == Material.BEDROCK || b.getType() == Material.BARRIER) {
                        continue;
                    }
                    placeTemporaryBlock(b, Material.PACKED_ICE, revert);
                }
            }
        }
        w.spawnParticle(Particle.EXPLOSION, center, 8, 0.8, 0.8, 0.8, 0);
        w.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
        player.sendMessage(Component.text("Cryo — solid suffocation sphere (~1 min).", NamedTextColor.AQUA));
    }

    private void maelstrom(Player player) {
        Location loc = player.getLocation();
        World w = player.getWorld();
        for (Entity e : w.getNearbyEntities(loc, 40, 40, 40)) {
            if (!(e instanceof LivingEntity living) || e.equals(player)) {
                continue;
            }
            Vector pull = player.getLocation().toVector().subtract(living.getLocation().toVector());
            if (pull.lengthSquared() < 0.0001) {
                continue;
            }
            pull.normalize().multiply(2.4);
            pull.setY(pull.getY() + 0.35);
            living.setVelocity(living.getVelocity().add(pull));
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 3));
        }
        vortexParticles(loc.clone().add(0, 1, 0), w, 200);
        w.spawnParticle(Particle.END_ROD, loc, 80, 8, 2, 8, 0.05);
        w.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.4f);
    }

    private void deluge(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location hit = player.getEyeLocation().clone().add(dir.clone().multiply(18));
        long revert = System.currentTimeMillis() + SUMMONED_WATER_MS;
        int r = 9;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) {
                        continue;
                    }
                    if (!isValidBlockY(world, hit.getBlockY() + dy)) {
                        continue;
                    }
                    Block b = hit.clone().add(dx, dy, dz).getBlock();
                    if (b.getType() == Material.AIR || b.getType() == Material.CAVE_AIR || b.getType() == Material.VOID_AIR) {
                        placeTemporaryBlock(b, Material.WATER, revert);
                    }
                }
            }
        }
        world.spawnParticle(Particle.SPLASH, hit, 400, 6, 3, 6, 0.5);
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, hit, 120, 4, 2, 4, 0.1);
        world.playSound(hit, Sound.ENTITY_PLAYER_SPLASH, 2f, 0.6f);
    }

    private void executionRay(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location o = player.getEyeLocation().clone();
        Set<UUID> got = new HashSet<>();
        for (double d = 0.5; d <= 120; d += 0.45) {
            Location p = o.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.DUST, p, 6, 0.05, 0.05, 0.05, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 0, 60), 1.2f));
            world.spawnParticle(Particle.SONIC_BOOM, p, 1, 0, 0, 0, 0);
            for (Entity e : world.getNearbyEntities(p, 1.8, 1.8, 1.8)) {
                if (!(e instanceof LivingEntity living) || e.equals(player) || !got.add(e.getUniqueId())) {
                    continue;
                }
                living.setHealth(0);
                world.spawnParticle(Particle.EXPLOSION, living.getLocation(), 2, 0.3, 0.3, 0.3, 0);
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.8f);
    }

    private static void blastParticles(Location point, World world, int count) {
        world.spawnParticle(Particle.SPLASH, point, count, 0.35, 0.35, 0.35, 0.08);
        world.spawnParticle(Particle.BUBBLE, point, count / 2, 0.25, 0.25, 0.25, 0.03);
        world.spawnParticle(Particle.DUST, point, count / 2, 0.2, 0.2, 0.2, 0,
                new Particle.DustOptions(Color.fromRGB(150, 220, 255), 1.5f));
        world.spawnParticle(Particle.GLOW, point, Math.max(2, count / 6), 0.2, 0.2, 0.2, 0);
    }

    private static void tsunamiParticles(Location p, World world) {
        world.spawnParticle(Particle.SPLASH, p, 25, 0.6, 0.4, 0.6, 0.1);
        world.spawnParticle(Particle.FISHING, p, 8, 0.3, 0.2, 0.3, 0);
        world.spawnParticle(Particle.DUST, p, 12, 0.4, 0.3, 0.4, 0,
                new Particle.DustOptions(Color.fromRGB(100, 180, 255), 1.3f));
    }

    private static void ringParticles(Location loc, World world, Color c, int points) {
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = Math.cos(a) * 2.2;
            double z = Math.sin(a) * 2.2;
            world.spawnParticle(Particle.DUST, loc.clone().add(x, 0.2, z), 3, 0, 0, 0, 0,
                    new Particle.DustOptions(c, 1.2f));
        }
    }

    private static void vortexParticles(Location base, World world, int n) {
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            double r = 1 + (i % 5) * 0.4;
            world.spawnParticle(Particle.BUBBLE, base.clone().add(Math.cos(ang) * r, i * 0.08, Math.sin(ang) * r), 1);
            world.spawnParticle(Particle.SPLASH, base.clone().add(Math.cos(ang) * r * 0.5, 0.5, Math.sin(ang) * r * 0.5), 2, 0.1, 0.1, 0.1, 0);
        }
    }

    private void tickReverts() {
        long now = System.currentTimeMillis();
        while (!pendingBlockReverts.isEmpty() && pendingBlockReverts.element().revertAtMs() <= now) {
            revertBlock(pendingBlockReverts.remove());
        }
    }

    private void placeTemporaryBlock(Block block, Material temporaryType, long revertAtMs) {
        // Keep the full block-state snapshot so containers and waterlogged blocks survive temporary abilities.
        BlockState originalState = block.getState();
        block.setType(temporaryType);
        pendingBlockReverts.add(new BlockRevert(originalState, temporaryType, revertAtMs));
    }

    private static void revertBlock(BlockRevert revert) {
        Block block = revert.originalState().getBlock();
        if (block.getType() == revert.temporaryType()) {
            revert.originalState().update(true, false);
        }
    }

    private void toggleSurf(Player player) {
        boolean on = !isSurf(player);
        setSurf(player, on);
        player.sendMessage(on
                ? Component.text("SURF — abyssal overdrive.", NamedTextColor.LIGHT_PURPLE)
                : Component.text("Surf off.", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.MUSIC_DISC_PIGSTEP, 0.3f, 2f);
    }

    private void tickGrasp() {
        Iterator<Map.Entry<UUID, UUID>> it = tideGraspTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            if (!isTideHit(p.getInventory().getItemInMainHand()) && !isTideHit(p.getInventory().getItemInOffHand())) {
                it.remove();
                continue;
            }
            if (getMode(p) != 11) {
                it.remove();
                continue;
            }
            Entity ent = Bukkit.getEntity(e.getValue());
            if (!(ent instanceof LivingEntity living) || !ent.isValid() || living.isDead()) {
                it.remove();
                continue;
            }
            if (!living.getWorld().equals(p.getWorld())) {
                it.remove();
                continue;
            }
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location anchor = eye.clone().add(dir.clone().multiply(7));
            anchor.add(0, -0.5, 0);
            Vector to = anchor.toVector().subtract(living.getLocation().toVector());
            double len = Math.max(0.15, to.length());
            to.multiply(Math.min(0.55, 4.5 / len));
            Vector blend = living.getVelocity().multiply(0.35).add(to.multiply(0.65));
            if (living instanceof Player) {
                blend.multiply(0.75);
            }
            living.setVelocity(blend);
            Location mid = living.getLocation().add(0, living.getHeight() * 0.5, 0);
            World w = p.getWorld();
            graspAura(mid, eye.clone().add(0, -0.2, 0), w);
        }
    }

    private static void graspAura(Location target, Location from, World w) {
        w.spawnParticle(Particle.ENCHANT, target, 25, 0.45, 0.6, 0.45, 0.02);
        w.spawnParticle(Particle.GLOW, target, 8, 0.3, 0.4, 0.3, 0);
        w.spawnParticle(Particle.DUST, target, 12, 0.35, 0.5, 0.35, 0,
                new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.2f));
        Vector step = target.toVector().subtract(from.toVector()).multiply(1.0 / 10);
        Location c = from.clone();
        for (int i = 0; i < 10; i++) {
            w.spawnParticle(Particle.WITCH, c, 1, 0.05, 0.05, 0.05, 0);
            c.add(step);
        }
    }

    private void tickHudAndSurf() {
        tickGrasp();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isTideHit(player.getInventory().getItemInMainHand())
                    && !isTideHit(player.getInventory().getItemInOffHand())) {
                continue;
            }
            int r = getReservoir(player);
            int m = getMode(player);
            if (m != 12) {
                setHydro(player, false);
            }
            boolean surf = isSurf(player);
            boolean hydro = m == 12 && isHydro(player);
            player.sendActionBar(Component.text()
                    .append(Component.text("☠ ", NamedTextColor.DARK_RED))
                    .append(Component.text(r + "/" + RESERVOIR_MAX, NamedTextColor.RED))
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(modeName(m), NamedTextColor.GOLD))
                    .append(surf && m == 4 ? Component.text(" · SURF++", NamedTextColor.LIGHT_PURPLE) : Component.empty())
                    .append(hydro ? Component.text(" · HYDRO", NamedTextColor.DARK_AQUA) : Component.empty()));

            if (hydro) {
                if (getReservoir(player) >= 1) {
                    int g = hydroDrainCounter.merge(player.getUniqueId(), 5, Integer::sum);
                    if (g >= SURF_DRAIN_INTERVAL) {
                        hydroDrainCounter.put(player.getUniqueId(), 0);
                        addReservoir(player, -1);
                    }
                }
                Vector look = player.getLocation().getDirection();
                Vector hor = new Vector(look.getX(), 0, look.getZ());
                if (hor.lengthSquared() > 0.01) {
                    hor.normalize().multiply(0.42);
                    Vector v = player.getVelocity();
                    player.setVelocity(new Vector(v.getX() * 0.88 + hor.getX() * 0.12, Math.min(0.35, v.getY() * 0.92 + 0.06 + look.getY() * 0.08), v.getZ() * 0.88 + hor.getZ() * 0.12));
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 30, 0, false, false, true));
                Location feet = player.getLocation().add(0, 0.1, 0);
                World pw = player.getWorld();
                pw.spawnParticle(Particle.SPLASH, feet, 18, 0.45, 0.05, 0.45, 0.06);
                pw.spawnParticle(Particle.BUBBLE_COLUMN_UP, feet, 10, 0.35, 0.02, 0.35, 0.02);
                pw.spawnParticle(Particle.DUST, feet, 14, 0.4, 0.05, 0.4, 0,
                        new Particle.DustOptions(Color.fromRGB(100, 200, 255), 1.3f));
            }

            if (surf && m == 4) {
                if (feetInWater(player)) {
                    int t = surfTickCounter.merge(player.getUniqueId(), 5, Integer::sum);
                    if (t >= SURF_DRAIN_INTERVAL) {
                        surfTickCounter.put(player.getUniqueId(), 0);
                        if (getReservoir(player) >= 1) {
                            addReservoir(player, -1);
                        }
                    }
                    player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 80, 2, false, false, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 3, false, false, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, 80, 2, false, false, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 220, 0, false, false, true));
                    Location loc = player.getLocation();
                    World w = player.getWorld();
                    w.spawnParticle(Particle.SPLASH, loc, 25, 0.8, 0.2, 0.8, 0.08);
                    w.spawnParticle(Particle.GLOW, loc, 8, 0.5, 0.1, 0.5, 0);
                    w.spawnParticle(Particle.BUBBLE_COLUMN_UP, loc, 6, 0.4, 0.1, 0.4, 0.05);
                } else {
                    surfTickCounter.put(player.getUniqueId(), 0);
                }
            }
        }
    }

    private static boolean feetInWater(Player player) {
        Block b = player.getLocation().getBlock();
        Block below = b.getRelative(0, -1, 0);
        return b.getType() == Material.WATER || below.getType() == Material.WATER
                || player.isInWater() || player.isSwimming();
    }

    private boolean cooldownGather(Player player, long gapMs) {
        return cooldownMap(lastGatherMs, player, gapMs);
    }

    private boolean cooldownCombat(Player player, long gapMs) {
        return cooldownMap(lastCombatMs, player, gapMs);
    }

    private static boolean cooldownMap(Map<UUID, Long> map, Player player, long gapMs) {
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long last = map.getOrDefault(id, 0L);
        if (now - last < gapMs) {
            return true;
        }
        map.put(id, now);
        return false;
    }

    private int getReservoir(Player player) {
        Integer v = getPlayerData(player, reservoirKey, PersistentDataType.INTEGER);
        return v == null ? 0 : Math.max(0, Math.min(RESERVOIR_MAX, v));
    }

    private void addReservoir(Player player, int delta) {
        int n = getReservoir(player) + delta;
        n = Math.max(0, Math.min(RESERVOIR_MAX, n));
        setPlayerData(player, reservoirKey, PersistentDataType.INTEGER, n);
    }

    private int getMode(Player player) {
        Byte b = getPlayerData(player, modeKey, PersistentDataType.BYTE);
        return b == null ? 0 : (b & 0xFF) % MODE_COUNT;
    }

    private void setMode(Player player, int mode) {
        setPlayerData(player, modeKey, PersistentDataType.BYTE, (byte) (mode % MODE_COUNT));
    }

    private boolean isSurf(Player player) {
        Byte b = getPlayerData(player, surfKey, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private void setSurf(Player player, boolean on) {
        setPlayerData(player, surfKey, PersistentDataType.BYTE, on ? (byte) 1 : (byte) 0);
    }

    private boolean isHydro(Player player) {
        Byte b = getPlayerData(player, hydroKey, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private void setHydro(Player player, boolean on) {
        setPlayerData(player, hydroKey, PersistentDataType.BYTE, on ? (byte) 1 : (byte) 0);
        if (!on) {
            hydroDrainCounter.remove(player.getUniqueId());
        }
    }

    private static <P, C> C getPlayerData(
            Player player, NamespacedKey key, PersistentDataType<P, C> type) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        C value = data.get(key, type);
        if (value != null) {
            return value;
        }
        NamespacedKey legacyKey = legacyKey(key);
        value = data.get(legacyKey, type);
        if (value != null) {
            data.set(key, type, value);
            data.remove(legacyKey);
        }
        return value;
    }

    private static <P, C> void setPlayerData(
            Player player, NamespacedKey key, PersistentDataType<P, C> type, C value) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(key, type, value);
        data.remove(legacyKey(key));
    }

    private void toggleHydro(Player player) {
        boolean on = !isHydro(player);
        setHydro(player, on);
        player.sendMessage(on
                ? Component.text("Hydro jet ON — move with look; leg coating.", NamedTextColor.DARK_AQUA)
                : Component.text("Hydro jet OFF.", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.6f, 1.5f);
    }

    private static String modeName(int m) {
        return switch (m) {
            case 1 -> "Blast";
            case 2 -> "Wall";
            case 3 -> "Lift";
            case 4 -> "Surf";
            case 5 -> "Tsunami";
            case 6 -> "Maelstrom";
            case 7 -> "Deluge";
            case 8 -> "Ravage";
            case 9 -> "Execution";
            case 10 -> "Cryo";
            case 11 -> "Grasp";
            case 12 -> "Hydro";
            case 13 -> "Rift";
            default -> "Gather";
        };
    }

    private static World resolveEndWorld() {
        World w = Bukkit.getWorld("world_the_end");
        if (w != null && w.getEnvironment() == World.Environment.THE_END) {
            return w;
        }
        return Bukkit.getWorlds().stream()
                .filter(x -> x.getEnvironment() == World.Environment.THE_END)
                .findFirst()
                .orElse(null);
    }

    /**
     * 16-block-tall chamber (minY..maxY inclusive). Clamped to valid world Y so blocks actually place
     * ({@code getMaxHeight()} is exclusive; Y must be &lt; getMaxHeight()).
     */
    private static int[] computeIceRealmVertical(World world, int surfaceY) {
        int minH = world.getMinHeight();
        int maxBlockY = world.getMaxHeight() - 1;
        int minClear = surfaceY + 8;
        int minY = surfaceY + 110;
        int maxY = minY + 15;

        if (maxY > maxBlockY) {
            maxY = maxBlockY;
            minY = maxY - 15;
        }
        if (minY < minClear) {
            minY = minClear;
            maxY = minY + 15;
            if (maxY > maxBlockY) {
                maxY = maxBlockY;
                minY = maxY - 15;
            }
        }
        if (minY < minH) {
            minY = minH;
            maxY = Math.min(minY + 15, maxBlockY);
        }
        return new int[]{minY, maxY};
    }

    private void enterIceRealm(Player host) {
        if (realmParticipant.containsKey(host.getUniqueId())) {
            host.sendMessage(Component.text("You are already in an Ice Rift.", NamedTextColor.RED));
            return;
        }
        int charge = getReservoir(host);
        if (charge < 1) {
            host.sendMessage(Component.text("Need tide to open a Rift.", NamedTextColor.RED));
            return;
        }
        World end = resolveEndWorld();
        if (end == null) {
            host.sendMessage(Component.text("End dimension not loaded — cannot open Rift.", NamedTextColor.RED));
            return;
        }
        LivingEntity buddy = null;
        UUID buddyId = tideRealmBuddy.remove(host.getUniqueId());
        if (buddyId != null) {
            Entity e = Bukkit.getEntity(buddyId);
            if (e instanceof LivingEntity le && le.isValid() && !le.isDead()
                    && le.getWorld().equals(host.getWorld())
                    && !realmParticipant.containsKey(le.getUniqueId())
                    && le.getLocation().distanceSquared(host.getLocation()) < 256) {
                buddy = le;
            }
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int cx = 0;
        int cz = 0;
        int surfaceY = 64;
        int minY = 0;
        int maxY = 0;
        boolean foundLocation = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            cx = rnd.nextInt(-96, 97);
            cz = rnd.nextInt(-96, 97);
            if (cx * cx + cz * cz < 24 * 24) {
                cx += cx >= 0 ? 40 : -40;
            }
            if (!isIceRealmAreaAvailable(end, cx, cz)) {
                continue;
            }
            for (int icx = (cx >> 4) - 3; icx <= (cx >> 4) + 3; icx++) {
                for (int icz = (cz >> 4) - 3; icz <= (cz >> 4) + 3; icz++) {
                    end.getChunkAt(icx, icz).load(true);
                }
            }
            surfaceY = end.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING);
            if (surfaceY <= end.getMinHeight() + 2) {
                surfaceY = 64;
            }
            int[] yb = computeIceRealmVertical(end, surfaceY);
            minY = yb[0];
            maxY = yb[1];
            if (minY >= surfaceY + 8) {
                foundLocation = true;
                break;
            }
        }
        if (!foundLocation) {
            host.sendMessage(Component.text("No safe space is available for another Ice Rift.", NamedTextColor.RED));
            return;
        }
        int minX = cx - 15;
        int maxX = cx + 15;
        int minZ = cz - 15;
        int maxZ = cz + 15;
        for (int chx = (minX >> 4) - 1; chx <= (maxX >> 4) + 1; chx++) {
            for (int chz = (minZ >> 4) - 1; chz <= (maxZ >> 4) + 1; chz++) {
                end.getChunkAt(chx, chz).load(true);
            }
        }
        buildIceRealmChamber(end, minX, maxX, minY, maxY, minZ, maxZ);
        Map<UUID, Location> returns = new HashMap<>();
        returns.put(host.getUniqueId(), host.getLocation().clone());
        if (buddy != null) {
            returns.put(buddy.getUniqueId(), buddy.getLocation().clone());
        }
        Location spawn = new Location(end, cx + 0.5, minY + 8, cz + 0.5, host.getLocation().getYaw(), host.getLocation().getPitch());
        if (!host.teleport(spawn)) {
            clearCuboid(end, minX, maxX, minY, maxY, minZ, maxZ);
            host.sendMessage(Component.text("The Ice Rift teleport was blocked.", NamedTextColor.RED));
            return;
        }
        if (buddy != null && !buddy.teleport(spawn.clone().add(3, 0, 0))) {
            host.teleport(returns.get(host.getUniqueId()));
            clearCuboid(end, minX, maxX, minY, maxY, minZ, maxZ);
            host.sendMessage(Component.text("The tagged target could not enter the Ice Rift.", NamedTextColor.RED));
            return;
        }
        addReservoir(host, -charge);
        IceRealmSession session = new IceRealmSession(host.getUniqueId(), buddy != null ? buddy.getUniqueId() : null,
                end, minX, maxX, minY, maxY, minZ, maxZ);
        session.returnLocations.putAll(returns);
        realmParticipant.put(host.getUniqueId(), session);
        if (buddy != null) {
            realmParticipant.put(buddy.getUniqueId(), session);
        }
        session.cleanupTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (realmParticipant.get(host.getUniqueId()) == session) {
                destroyIceRealm(session);
                Player h = Bukkit.getPlayer(host.getUniqueId());
                if (h != null && h.isOnline()) {
                    h.sendMessage(Component.text("The Ice Rift dissolved.", NamedTextColor.GRAY));
                }
            }
        }, ICE_REALM_AUTO_CLEANUP_TICKS);
        sendRealmLeaveButton(host);
        if (buddy instanceof Player bp) {
            sendRealmLeaveButton(bp);
        }
        host.sendMessage(Component.text("Rift open — frozen island pocket above the End (~15 min max).", NamedTextColor.AQUA));
        host.playSound(host.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1f, 1.2f);
    }

    private boolean isIceRealmAreaAvailable(World world, int centerX, int centerZ) {
        int margin = 8;
        int minX = centerX - 15 - margin;
        int maxX = centerX + 15 + margin;
        int minZ = centerZ - 15 - margin;
        int maxZ = centerZ + 15 + margin;
        return realmParticipant.values().stream().noneMatch(session -> session.world.equals(world)
                && maxX >= session.minX && minX <= session.maxX
                && maxZ >= session.minZ && minZ <= session.maxZ);
    }

    private static void buildIceRealmChamber(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        double rcx = (minX + maxX) / 2.0;
        double rcz = (minZ + maxZ) / 2.0;
        final double axisA = 13.5;
        final double axisB = 13.5;
        final double poolRadius = 6.0;
        final int poolWaterDepth = 4;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[][] spikes = new int[8][3];
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            spikes[i][0] = (int) Math.round(rcx + Math.cos(ang) * 11.5);
            spikes[i][1] = (int) Math.round(rcz + Math.sin(ang) * 11.5);
            spikes[i][2] = 5 + rnd.nextInt(4);
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double dx = x - rcx + 0.5;
                double dz = z - rcz + 0.5;
                double ell = (dx * dx) / (axisA * axisA) + (dz * dz) / (axisB * axisB);
                boolean onIsland = ell <= 1.0;
                double dist = Math.sqrt(dx * dx + dz * dz);
                boolean inPool = dist <= poolRadius;
                boolean rim = ell >= 0.76 && ell <= 1.0 && !inPool;

                int spikeH = 0;
                for (int[] s : spikes) {
                    if (s[0] == x && s[1] == z) {
                        spikeH = s[2];
                        break;
                    }
                }
                boolean lanternUnderPool = inPool && Math.abs(x - rcx) < 4.5 && Math.abs(z - rcz) < 4.5
                        && ((Math.round(x) + Math.round(z)) & 3) == 0;

                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    int rel = y - minY;
                    if (!onIsland) {
                        b.setType(Material.AIR, false);
                        continue;
                    }
                    if (rel == 0) {
                        if (inPool) {
                            b.setType(Material.BLUE_ICE, false);
                        } else if (rim) {
                            b.setType(Material.PACKED_ICE, false);
                        } else {
                            b.setType(iceRealmFloorTile(x, z, rnd), false);
                        }
                        continue;
                    }
                    if (spikeH > 0 && rel >= 1 && rel <= spikeH) {
                        if (rel == spikeH) {
                            b.setType(Material.SNOW_BLOCK, false);
                        } else {
                            b.setType(Material.ICE, false);
                        }
                        continue;
                    }
                    if (inPool) {
                        if (rel == 1 && lanternUnderPool) {
                            b.setType(Material.SEA_LANTERN, false);
                            continue;
                        }
                        if (rel >= 1 && rel <= poolWaterDepth) {
                            b.setType(Material.WATER, false);
                            continue;
                        }
                    }
                    if (rim && rel >= 1 && rel <= 4) {
                        b.setType(Material.PACKED_ICE, false);
                        continue;
                    }
                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    private static Material iceRealmFloorTile(int x, int z, ThreadLocalRandom rnd) {
        int mix = Math.floorMod(x * 31 + z * 17, 11);
        if (mix == 0 && rnd.nextInt(5) == 0) {
            return Material.ICE;
        }
        return switch (mix) {
            case 1, 2, 3 -> Material.BLUE_ICE;
            case 4, 5 -> Material.SNOW_BLOCK;
            default -> Material.PACKED_ICE;
        };
    }

    private static void clearCuboid(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t == Material.WATER
                            || t == Material.PACKED_ICE
                            || t == Material.BLUE_ICE
                            || t == Material.ICE
                            || t == Material.SNOW_BLOCK
                            || t == Material.SEA_LANTERN) {
                        b.setType(Material.AIR);
                    }
                }
            }
        }
    }

    private void destroyIceRealm(IceRealmSession s) {
        if (s.cleanupTask != null) {
            s.cleanupTask.cancel();
            s.cleanupTask = null;
        }
        clearCuboid(s.world, s.minX, s.maxX, s.minY, s.maxY, s.minZ, s.maxZ);
        for (Map.Entry<UUID, Location> e : new ArrayList<>(s.returnLocations.entrySet())) {
            UUID id = e.getKey();
            Location loc = e.getValue();
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            Player pl = Bukkit.getPlayer(id);
            if (pl != null && pl.isOnline()) {
                pl.teleport(loc);
                continue;
            }
            Entity ent = Bukkit.getEntity(id);
            if (ent instanceof LivingEntity living && !living.isDead()) {
                living.teleport(loc);
            }
        }
        realmParticipant.remove(s.hostId);
        if (s.guestId != null) {
            realmParticipant.remove(s.guestId);
        }
    }

    private void leaveRealmGuest(IceRealmSession s, Player guest) {
        if (s.guestId == null || !s.guestId.equals(guest.getUniqueId())) {
            return;
        }
        Location loc = s.returnLocations.remove(guest.getUniqueId());
        realmParticipant.remove(guest.getUniqueId());
        s.guestId = null;
        if (loc != null && loc.getWorld() != null) {
            guest.teleport(loc);
        }
        guest.sendMessage(Component.text("Returned from the Ice Rift.", NamedTextColor.AQUA));
    }

    private void sendRealmLeaveButton(Player player) {
        player.sendMessage(Component.text("Ice Rift pocket ", NamedTextColor.DARK_AQUA)
                .append(Component.text("[ Leave ]", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tideleave"))
                        .hoverEvent(HoverEvent.showText(Component.text("Return to your previous location")))));
    }

    private static final class IceRealmSession {
        final UUID hostId;
        UUID guestId;
        final World world;
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final int minZ;
        final int maxZ;
        final Map<UUID, Location> returnLocations = new HashMap<>();
        BukkitTask cleanupTask;

        IceRealmSession(UUID hostId, UUID guestId, World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.hostId = hostId;
            this.guestId = guestId;
            this.world = world;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    private record BlockRevert(BlockState originalState, Material temporaryType, long revertAtMs) {}
}
