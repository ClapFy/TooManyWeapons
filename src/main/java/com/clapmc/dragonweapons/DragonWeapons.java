package com.clapmc.dragonweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.util.Vector;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DragonWeapons extends JavaPlugin implements Listener {

    private final NamespacedKey enderBowKey = itemKey("ender_bow");
    private final NamespacedKey enderArrowKey = itemKey("ender_arrow");
    private final NamespacedKey explosiveBowKey = itemKey("explosive_bow");
    private final NamespacedKey explosiveArrowKey = itemKey("explosive_arrow");
    private final NamespacedKey stormTridentKey = itemKey("storm_trident");
    private final NamespacedKey voidScytheKey = itemKey("void_scythe");
    private final NamespacedKey voidScytheCraftedKey = itemKey("void_scythe_crafted");
    private final NamespacedKey phoenixBowKey = itemKey("phoenix_bow");
    private final NamespacedKey phoenixBowCraftedKey = itemKey("phoenix_bow_crafted");
    private final NamespacedKey phoenixArrowKey = itemKey("phoenix_arrow");
    private final NamespacedKey waterBowKey = itemKey("water_bow");
    private final NamespacedKey waterBowCraftedKey = itemKey("water_bow_crafted");
    private final NamespacedKey waterArrowKey = itemKey("water_arrow");
    private final NamespacedKey tidalTridentKey = itemKey("tidal_trident");
    private final NamespacedKey voidSphereKey = itemKey("void_sphere");

    private final Map<UUID, Double> trackedArrowAngles = new HashMap<>();
    private final Map<UUID, Vector> trackedArrowLastVelocity = new HashMap<>();

    private final Map<UUID, Long> tidalChargeStartMs = new HashMap<>();
    private final Map<UUID, Long> tidalAuraUntilMs = new HashMap<>();
    private final Map<UUID, Integer> tidalAirDashCooldown = new HashMap<>();
    private final Map<UUID, Integer> tidalAirVortexCount = new HashMap<>();

    private TideHitManager tideHitManager;

    private final List<NamespacedKey> registeredRecipeKeys = new ArrayList<>();

    @Override
    public void onEnable() {
        tideHitManager = new TideHitManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(tideHitManager, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            tickEnderBowAura();
            tickArrowTracers();
            tickTidalCharges();
            tickTidalAirDash();
            tickTidalWaterAura();
            tickVoidSphereHolders();
        }, 0L, 1L);

        registerPhoenixBowCraftRecipe();
        registerWaterBowCraftRecipe();
        registerVoidScytheCraftRecipe();
        registerVoidSphereCraftRecipe();

        getLogger().info("DragonWeapons enabled!");
    }

    @Override
    public void onDisable() {
        if (tideHitManager != null) {
            tideHitManager.shutdown();
        }
        restoreTrackedArrowGravity();
        for (NamespacedKey key : registeredRecipeKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
        getLogger().info("DragonWeapons disabled.");
    }

    private void registerPhoenixBowCraftRecipe() {
        NamespacedKey key = new NamespacedKey(this, "phoenix_bow_craft");
        ShapedRecipe recipe = new ShapedRecipe(key, buildPhoenixBowCrafted().clone());
        recipe.shape("ABC", "DEF", "GHI");
        recipe.setIngredient('A', Material.NETHER_STAR);
        recipe.setIngredient('B', Material.ENDER_EYE);
        recipe.setIngredient('C', Material.NETHER_STAR);
        recipe.setIngredient('D', Material.FIRE_CHARGE);
        recipe.setIngredient('E', new RecipeChoice.ExactChoice(new ItemStack(Material.BOW)));
        recipe.setIngredient('F', Material.FIRE_CHARGE);
        recipe.setIngredient('G', Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        recipe.setIngredient('H', Material.SOUL_SAND);
        recipe.setIngredient('I', Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        recipe.setGroup("dragonweapons");
        registerRecipe(key, recipe);
    }

    private void registerWaterBowCraftRecipe() {
        NamespacedKey key = new NamespacedKey(this, "water_bow_craft");
        ShapedRecipe recipe = new ShapedRecipe(key, buildWaterBowCrafted().clone());
        recipe.shape("ABC", "DEF", "GHI");
        recipe.setIngredient('A', Material.PRISMARINE_SHARD);
        recipe.setIngredient('B', Material.HEART_OF_THE_SEA);
        recipe.setIngredient('C', Material.PRISMARINE_SHARD);
        recipe.setIngredient('D', Material.PRISMARINE_SHARD);
        recipe.setIngredient('E', new RecipeChoice.ExactChoice(new ItemStack(Material.BOW)));
        recipe.setIngredient('F', Material.PRISMARINE_SHARD);
        recipe.setIngredient('G', Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
        recipe.setIngredient('H', new RecipeChoice.ExactChoice(new ItemStack(Material.TRIDENT)));
        recipe.setIngredient('I', Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE);
        recipe.setGroup("dragonweapons");
        registerRecipe(key, recipe);
    }

    private void registerVoidScytheCraftRecipe() {
        NamespacedKey key = new NamespacedKey(this, "void_scythe_craft");
        ShapedRecipe recipe = new ShapedRecipe(key, buildVoidScytheCrafted().clone());
        recipe.shape("ABC", "DEF", "GHI");
        recipe.setIngredient('A', Material.WITHER_ROSE);
        recipe.setIngredient('B', Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        recipe.setIngredient('C', Material.WITHER_ROSE);
        recipe.setIngredient('D', Material.NETHERITE_BLOCK);
        recipe.setIngredient('E', new RecipeChoice.ExactChoice(new ItemStack(Material.NETHERITE_SWORD)));
        recipe.setIngredient('F', Material.NETHERITE_BLOCK);
        recipe.setIngredient('G', Material.SOUL_SAND);
        recipe.setIngredient('H', Material.SOUL_SAND);
        recipe.setIngredient('I', Material.SOUL_SAND);
        recipe.setGroup("dragonweapons");
        registerRecipe(key, recipe);
    }

    private void registerVoidSphereCraftRecipe() {
        NamespacedKey key = new NamespacedKey(this, "void_sphere_craft");
        ShapedRecipe recipe = new ShapedRecipe(key, buildVoidSphere().clone());
        recipe.shape("ABC", "DEF", "GHI");
        recipe.setIngredient('A', Material.NETHERITE_BLOCK);
        recipe.setIngredient('B', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('C', Material.NETHERITE_BLOCK);
        recipe.setIngredient('D', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('E', Material.NETHERITE_BLOCK);
        recipe.setIngredient('F', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('G', Material.NETHERITE_BLOCK);
        recipe.setIngredient('H', Material.WITHER_SKELETON_SKULL);
        recipe.setIngredient('I', Material.NETHERITE_BLOCK);
        recipe.setGroup("dragonweapons");
        registerRecipe(key, recipe);
    }

    private void registerRecipe(NamespacedKey key, ShapedRecipe recipe) {
        if (Bukkit.addRecipe(recipe)) {
            registeredRecipeKeys.add(key);
        } else {
            getLogger().warning("Could not register recipe " + key + "; that key is already in use.");
        }
    }

    private boolean isCraftedPhoenixBow(ItemStack stack) {
        if (stack == null || stack.getType() != Material.BOW || !stack.hasItemMeta()) return false;
        return hasItemTag(stack.getItemMeta().getPersistentDataContainer(), phoenixBowCraftedKey);
    }

    private boolean isCraftedWaterBow(ItemStack stack) {
        if (stack == null || stack.getType() != Material.BOW || !stack.hasItemMeta()) return false;
        return hasItemTag(stack.getItemMeta().getPersistentDataContainer(), waterBowCraftedKey);
    }

    private boolean isCraftedVoidScythe(ItemStack stack) {
        if (stack == null || stack.getType() != Material.NETHERITE_SWORD || !stack.hasItemMeta()) return false;
        return hasItemTag(stack.getItemMeta().getPersistentDataContainer(), voidScytheCraftedKey);
    }

    private boolean isCraftedRestrictedItem(ItemStack stack) {
        return isCraftedPhoenixBow(stack) || isCraftedWaterBow(stack) || isCraftedVoidScythe(stack);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvilCraftedRestricted(PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(0);
        if (!isCraftedRestrictedItem(base)) return;
        ItemStack result = event.getResult();
        if (result == null || !result.hasItemMeta()) return;
        var ench = result.getEnchantments();
        if (ench.containsKey(Enchantment.MENDING) || ench.containsKey(Enchantment.UNBREAKING)) {
            event.setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchantCraftedRestricted(EnchantItemEvent event) {
        if (!isCraftedRestrictedItem(event.getItem())) return;
        for (Enchantment e : event.getEnchantsToAdd().keySet()) {
            if (e == Enchantment.MENDING || e == Enchantment.UNBREAKING) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchantCommandCraftedRestricted(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().toLowerCase(Locale.ROOT).split("\\s+");
        String command = parts[0];
        if (!command.equals("/enchant") && !command.equals("/minecraft:enchant")) return;
        if (!isCraftedRestrictedItem(event.getPlayer().getInventory().getItemInMainHand())) return;
        if (parts.length < 3) return;
        String enchantment = parts[2];
        if (enchantment.equals("mending") || enchantment.equals("minecraft:mending")
                || enchantment.equals("unbreaking") || enchantment.equals("minecraft:unbreaking")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "You cannot add Mending or Unbreaking to this crafted item.", NamedTextColor.RED));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "enderbow", "dragonbow" -> give(sender, args, "dragonweapons.enderbow", buildEnderBow(), "Dragon Bow", NamedTextColor.DARK_PURPLE);
            case "explosivebow"   -> give(sender, args, "dragonweapons.explosivebow", buildExplosiveBow(), "Explosive Bow", NamedTextColor.RED);
            case "phoenixbow"     -> give(sender, args, "dragonweapons.phoenixbow", buildPhoenixBow(), "Phoenix Bow", NamedTextColor.GOLD);
            case "waterbow"       -> give(sender, args, "dragonweapons.waterbow", buildWaterBow(), "Water Bow", NamedTextColor.AQUA);
            case "stormtrident"   -> give(sender, args, "dragonweapons.stormtrident", buildStormTrident(), "Storm Trident", NamedTextColor.AQUA);
            case "tidaltrident"   -> give(sender, args, "dragonweapons.tidaltrident", buildTidalTrident(), "Tidal Trident", NamedTextColor.BLUE);
            case "voidscythe"     -> give(sender, args, "dragonweapons.voidscythe", buildVoidScythe(), "Void Scythe", NamedTextColor.DARK_GRAY);
            case "voidsphere"     -> give(sender, args, "dragonweapons.voidsphere", buildVoidSphere(), "Void Sphere", NamedTextColor.DARK_PURPLE);
            case "tidehit"        -> give(sender, args, "dragonweapons.tidehit", tideHitManager.buildTideHit(), "Tide Hit", NamedTextColor.AQUA);
            case "tideguide"      -> {
                tideHitManager.sendGuide(sender);
                yield true;
            }
            case "tideleave"    -> {
                if (!(sender instanceof Player pl)) {
                    sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                    yield true;
                }
                yield tideHitManager.handleTideLeaveCommand(pl);
            }
            case "tidemode"     -> tideHitManager.handleTideModeCommand(sender, args);
            default               -> false;
        };
    }

    private boolean give(CommandSender sender, String[] args, String perm, ItemStack item, String name, NamedTextColor color) {
        Player target = resolveTarget(sender, args, perm);
        if (target == null) return true;

        Map<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        overflow.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        target.sendMessage(Component.text("You received the ", NamedTextColor.GREEN)
                .append(Component.text(name, color)).append(Component.text("!", NamedTextColor.GREEN)));
        if (!sender.equals(target))
            sender.sendMessage(Component.text("Gave ", NamedTextColor.GREEN)
                    .append(Component.text(name, color))
                    .append(Component.text(" to ", NamedTextColor.GREEN))
                    .append(Component.text(target.getName(), NamedTextColor.YELLOW))
                    .append(Component.text("!", NamedTextColor.GREEN)));
        return true;
    }

    private Player resolveTarget(CommandSender sender, String[] args, String selfPerm) {
        if (args.length >= 1) {
            if (!sender.hasPermission("dragonweapons.others")) {
                sender.sendMessage(Component.text("You don't have permission to give to others!", NamedTextColor.RED));
                return null;
            }
            Player p = Bukkit.getPlayerExact(args[0]);
            if (p == null) {
                sender.sendMessage(Component.text("Player '" + args[0] + "' is not online.", NamedTextColor.RED));
                return null;
            }
            return p;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
            return null;
        }
        if (!p.hasPermission(selfPerm)) {
            p.sendMessage(Component.text("You don't have permission!", NamedTextColor.RED));
            return null;
        }
        return p;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        tidalChargeStartMs.remove(id);
        tidalAuraUntilMs.remove(id);
        tidalAirDashCooldown.remove(id);
        tidalAirVortexCount.remove(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTidalTridentInteract(PlayerInteractEvent event) {
        if (!(event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) return;
        ItemStack item = event.getItem();
        if (!isTidalTrident(item)) return;
        Player player = event.getPlayer();
        if (!isPlayerOnGround(player)) {
            return;
        }
        tidalChargeStartMs.put(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTidalTridentLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        if (!(trident.getShooter() instanceof Player player)) return;
        if (!isTidalTrident(trident.getItem())) return;
        event.setCancelled(true);
        long started = tidalChargeStartMs.getOrDefault(player.getUniqueId(), System.currentTimeMillis());
        tidalChargeStartMs.remove(player.getUniqueId());
        if (player.isSneaking()) return;
        long holdMs = Math.max(0, System.currentTimeMillis() - started);
        if (holdMs >= 5000) {
            int extraSeconds = (int) ((holdMs - 5000) / 1000L);
            fireThickWaterBeam(player, extraSeconds);
        } else {
            fireThinWaterBeam(player);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.0f, 0.95f);
        extendTidalAura(player, 1600L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;
        ItemStack bow = event.getBow();
        if (bow == null) return;
        ItemMeta bowMeta = bow.getItemMeta();
        if (bowMeta == null) return;
        if (hasItemTag(bowMeta.getPersistentDataContainer(), enderBowKey)) {
            trackArrow(arrow, enderArrowKey, true);
            return;
        }
        if (hasItemTag(bowMeta.getPersistentDataContainer(), explosiveBowKey)) {
            trackArrow(arrow, explosiveArrowKey, false);
            return;
        }
        if (hasItemTag(bowMeta.getPersistentDataContainer(), phoenixBowKey)) {
            trackArrow(arrow, phoenixArrowKey, false);
            return;
        }
        if (hasItemTag(bowMeta.getPersistentDataContainer(), waterBowKey)) {
            trackArrow(arrow, waterArrowKey, false);
        }
    }

    private void trackArrow(Arrow arrow, NamespacedKey projectileKey, boolean showEnderTracer) {
        UUID id = arrow.getUniqueId();
        arrow.getPersistentDataContainer().set(projectileKey, PersistentDataType.BYTE, (byte) 1);
        arrow.setGravity(false);
        trackedArrowLastVelocity.put(id, arrow.getVelocity().clone());
        if (showEnderTracer) {
            trackedArrowAngles.put(id, 0.0);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (hasItemTag(arrow.getPersistentDataContainer(), enderArrowKey)) {
            untrackArrow(arrow.getUniqueId());
            if (event.getHitBlock() != null) {
                Location impact = event.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
                createEnderHurricane(impact);
            }
            return;
        }
        if (hasItemTag(arrow.getPersistentDataContainer(), explosiveArrowKey)) {
            untrackArrow(arrow.getUniqueId());
            Location impact;
            if (event.getHitBlock() != null) {
                impact = event.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
            } else if (event.getHitEntity() != null) {
                impact = event.getHitEntity().getLocation().add(0, 0.5, 0);
            } else {
                impact = arrow.getLocation();
            }
            createExplosion(impact);
            return;
        }
        if (hasItemTag(arrow.getPersistentDataContainer(), phoenixArrowKey)) {
            untrackArrow(arrow.getUniqueId());
            if (event.getHitEntity() instanceof LivingEntity living)
                living.setFireTicks(120);
            Location impact = event.getHitBlock() != null
                    ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                    : (event.getHitEntity() != null ? event.getHitEntity().getLocation().add(0, 0.5, 0) : arrow.getLocation());
            createPhoenixBurst(impact);
            return;
        }
        if (hasItemTag(arrow.getPersistentDataContainer(), waterArrowKey)) {
            untrackArrow(arrow.getUniqueId());
            if (event.getHitEntity() instanceof LivingEntity living) {
                living.setFireTicks(0);
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
            }
            Location impact = event.getHitBlock() != null
                    ? event.getHitBlock().getLocation().add(0.5, 0.5, 0.5)
                    : (event.getHitEntity() != null ? event.getHitEntity().getLocation().add(0, 0.5, 0) : arrow.getLocation());
            createWaterBurst(impact);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnderArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) return;
        if (!hasItemTag(arrow.getPersistentDataContainer(), enderArrowKey)) return;

        untrackArrow(arrow.getUniqueId());

        if (event.getEntity() instanceof LivingEntity living) {
            living.setNoDamageTicks(0);
        }
        event.setDamage(1_000_000.0);

        Entity hit = event.getEntity();
        // Creakings can reject otherwise lethal damage through their heart mechanic.
        if (hit.getType() == EntityType.CREAKING) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (hit.isValid() && !hit.isDead()) hit.remove();
            }, 1L);
        }

        createEnderHurricane(hit.getLocation().add(0, 1, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onStormTridentHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Trident trident)) return;
        ItemStack item = trident.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!hasItemTag(item.getItemMeta().getPersistentDataContainer(), stormTridentKey)) return;
        Entity hit = event.getEntity();
        Location impact = hit.getLocation();
        Location particles = impact.clone().add(0, 1, 0);
        hit.getWorld().strikeLightning(impact);
        hit.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, particles, 25, 0.3, 0.5, 0.3, 0.15);
        hit.getWorld().spawnParticle(Particle.END_ROD, particles, 10, 0.2, 0.3, 0.2, 0.05);
        hit.getWorld().playSound(impact, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidScytheHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() != Material.NETHERITE_SWORD || !main.hasItemMeta()) return;
        if (!hasItemTag(main.getItemMeta().getPersistentDataContainer(), voidScytheKey)) return;
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        living.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 1));
        living.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
        Location loc = living.getLocation().add(0, 1, 0);
        living.getWorld().spawnParticle(Particle.SOUL, loc, 20, 0.3, 0.4, 0.3, 0.06);
        living.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 12, 0.2, 0.3, 0.2, 0.04);
        living.getWorld().playSound(loc, Sound.ENTITY_WITHER_HURT, 0.6f, 1.2f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidSphereMeleeHit(EntityDamageByEntityEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity living)) return;
        if (!isVoidSphere(player.getInventory().getItemInMainHand())) return;
        living.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 1));
        Location loc = living.getLocation().add(0, 1, 0);
        living.getWorld().spawnParticle(Particle.SOUL, loc, 10, 0.25, 0.35, 0.25, 0.02);
        living.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 4, 0.15, 0.2, 0.15, 0.01);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidSphereBlockPlace(BlockPlaceEvent event) {
        ItemStack stack = event.getItemInHand();
        if (stack != null && isVoidSphere(stack)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidSphereItemFrame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        Player player = event.getPlayer();
        ItemStack item = event.getHand() == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (isVoidSphere(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVoidSphereDamageItemFrame(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isVoidSphere(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true);
    }

    private void tickArrowTracers() {
        final double minimumSpeed = 0.01;
        Iterator<Map.Entry<UUID, Vector>> velocityIterator = trackedArrowLastVelocity.entrySet().iterator();
        while (velocityIterator.hasNext()) {
            Map.Entry<UUID, Vector> entry = velocityIterator.next();
            UUID uid = entry.getKey();
            Entity e = Bukkit.getEntity(uid);
            if (!(e instanceof Arrow arrow) || !arrow.isValid()) {
                velocityIterator.remove();
                trackedArrowAngles.remove(uid);
                continue;
            }
            Vector vel = arrow.getVelocity();
            double speed = vel.length();
            Vector lastVel = entry.getValue();
            if (speed < minimumSpeed && lastVel.lengthSquared() > 0.0001) {
                arrow.setVelocity(lastVel.clone());
            } else if (speed >= minimumSpeed) {
                entry.setValue(vel.clone());
            }
            if (hasItemTag(arrow.getPersistentDataContainer(), phoenixArrowKey)) {
                World w = arrow.getWorld();
                Location loc = arrow.getLocation();
                w.spawnParticle(Particle.FLAME, loc, 2, 0.05, 0.05, 0.05, 0.02);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }

        Iterator<Map.Entry<UUID, Double>> tracerIterator = trackedArrowAngles.entrySet().iterator();
        while (tracerIterator.hasNext()) {
            Map.Entry<UUID, Double> entry = tracerIterator.next();
            UUID uid = entry.getKey();
            Entity e = Bukkit.getEntity(uid);
            if (!(e instanceof Arrow arrow) || !arrow.isValid()) {
                tracerIterator.remove();
                trackedArrowLastVelocity.remove(uid);
                continue;
            }
            double angle = entry.getValue();
            World world = arrow.getWorld();
            Vector vel = arrow.getVelocity();
            double speed = vel.length();
            if (speed < minimumSpeed) continue;

            Vector forward = vel.clone().normalize();
            Vector up = Math.abs(forward.getY()) < 0.99
                    ? new Vector(0, 1, 0)
                    : new Vector(1, 0, 0);
            Vector right = forward.getCrossProduct(up).normalize();
            Vector realUp = right.getCrossProduct(forward).normalize();

            for (int arm = 0; arm < 3; arm++) {
                double a = angle + (2 * Math.PI * arm / 3);
                double r = 0.25;
                Location tip = arrow.getLocation().add(forward.clone().multiply(-0.3));
                Location loc = tip
                        .add(right.clone().multiply(Math.cos(a) * r))
                        .add(realUp.clone().multiply(Math.sin(a) * r));
                world.spawnParticle(Particle.END_ROD, loc, 1, 0, 0, 0, 0);
                if (arm == 0) world.spawnParticle(Particle.PORTAL, loc, 1, 0, 0, 0, 0);
            }
            entry.setValue(angle + 0.55);
        }
    }

    private void untrackArrow(UUID id) {
        trackedArrowAngles.remove(id);
        trackedArrowLastVelocity.remove(id);
    }

    private void restoreTrackedArrowGravity() {
        for (UUID id : trackedArrowLastVelocity.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Arrow arrow && arrow.isValid()) {
                arrow.setGravity(true);
            }
        }
        trackedArrowAngles.clear();
        trackedArrowLastVelocity.clear();
    }

    private void createEnderHurricane(Location center) {
        World world = center.getWorld();
        if (world == null) return;
        Location base = center.clone();

        world.playSound(base, Sound.ENTITY_ENDER_DRAGON_FLAP, 2.5f, 0.6f);
        world.playSound(base, Sound.BLOCK_PORTAL_AMBIENT,       2.0f, 0.9f);

        new BukkitRunnable() {
            int    tick  = 0;
            double angle = 0;

            @Override
            public void run() {
                if (tick >= 50) { cancel(); return; }

                double height = (double) tick * 0.3;
                double r      = 1.0;

                for (int dot = 0; dot < 2; dot++) {
                    double a   = angle + (dot * Math.PI);
                    double x   = Math.cos(a) * r;
                    double z   = Math.sin(a) * r;
                    Location loc = base.clone().add(x, height, z);

                    world.spawnParticle(Particle.PORTAL,        loc, 4, 0.06, 0.06, 0.06, 0.05);
                    world.spawnParticle(Particle.END_ROD,       loc, 2, 0.03, 0.03, 0.03, 0.08);
                    spawnDragonBreath(world, loc, 2, 0.06, 0.06, 0.06, 0.02);
                }
                angle += 0.5;
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Paper 1.21.11 added float data to dragon-breath particles; older 1.21 releases expect none. */
    private static void spawnDragonBreath(
            World world,
            Location location,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double extra
    ) {
        if (Particle.DRAGON_BREATH.getDataType() == Float.class) {
            world.spawnParticle(
                    Particle.DRAGON_BREATH,
                    location,
                    count,
                    offsetX,
                    offsetY,
                    offsetZ,
                    extra,
                    1.0f
            );
            return;
        }
        world.spawnParticle(
                Particle.DRAGON_BREATH,
                location,
                count,
                offsetX,
                offsetY,
                offsetZ,
                extra
        );
    }

    private boolean isEnderBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && hasItemTag(meta.getPersistentDataContainer(), enderBowKey);
    }

    private void tickEnderBowAura() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isEnderBow(player.getInventory().getItemInMainHand())
                    && !isEnderBow(player.getInventory().getItemInOffHand())) {
                continue;
            }
            Location center = player.getLocation().add(0, 1, 0);
            double phase = player.getTicksLived() * 0.18;
            for (int arm = 0; arm < 3; arm++) {
                double angle = phase + arm * (2 * Math.PI / 3);
                Location point = center.clone().add(Math.cos(angle) * 0.8, arm * 0.35, Math.sin(angle) * 0.8);
                player.getWorld().spawnParticle(Particle.PORTAL, point, 2, 0.03, 0.03, 0.03, 0.02);
                player.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            }
        }
    }

    private ItemStack buildEnderBow() {
        ItemStack item = new ItemStack(Material.BOW);
        item.addUnsafeEnchantment(Enchantment.POWER, 1000);
        item.addUnsafeEnchantment(Enchantment.PUNCH, 1000);
        item.addUnsafeEnchantment(Enchantment.FLAME, 1000);
        item.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1000);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1000);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("☽ Dragon Bow", NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Shoots arrows that call the Dragon's wrath."),
                lore("■ Hold to activate the Ender Spiral aura"),
                lore("■ Arrow hits block → End particle hurricane"),
                lore("■ Arrow hits entity → 1,000,000 damage")));
        meta.getPersistentDataContainer().set(enderBowKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildExplosiveBow() {
        ItemStack item = new ItemStack(Material.BOW);
        item.addUnsafeEnchantment(Enchantment.POWER, 5);
        item.addUnsafeEnchantment(Enchantment.PUNCH, 2);
        item.addUnsafeEnchantment(Enchantment.FLAME, 1);
        item.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("☄ Explosive Bow", NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Arrows explode on impact."),
                lore("■ Block or entity hit → explosion (fire, block damage)")));
        meta.getPersistentDataContainer().set(explosiveBowKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void createExplosion(Location center) {
        if (center.getWorld() == null) return;
        center.getWorld().createExplosion(center, 4f, true, true);
    }

    private void createPhoenixBurst(Location center) {
        World world = center.getWorld();
        if (world == null) return;
        world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.7f);
        world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 1f, 0.9f);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Block b = center.clone().add(x, 0, z).getBlock();
            if (b.getType().isAir() && b.getRelative(0, -1, 0).getType().isSolid())
                b.setType(Material.FIRE);
        }
        world.spawnParticle(Particle.FLAME, center, 30, 0.4, 0.4, 0.4, 0.04);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 15, 0.3, 0.3, 0.3, 0.03);
    }

    private ItemStack buildStormTrident() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        item.addUnsafeEnchantment(Enchantment.LOYALTY, 3);
        item.addUnsafeEnchantment(Enchantment.CHANNELING, 1);
        item.addUnsafeEnchantment(Enchantment.IMPALING, 5);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("⛈ Storm Trident", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Strikes lightning on hit."),
                lore("■ Entity hit → lightning + electric particles")));
        meta.getPersistentDataContainer().set(stormTridentKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildVoidScythe() {
        return buildVoidScythe(false);
    }

    private ItemStack buildVoidScytheCrafted() {
        return buildVoidScythe(true);
    }

    private ItemStack buildVoidScythe(boolean crafted) {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        if (!crafted) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        }
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("☠ Void Scythe", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Drains the soul of your foes."),
                lore("■ Hit → Wither II + Darkness, soul particles")));
        meta.getPersistentDataContainer().set(voidScytheKey, PersistentDataType.BYTE, (byte) 1);
        if (crafted) {
            meta.getPersistentDataContainer().set(voidScytheCraftedKey, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildVoidSphere() {
        ItemStack item = new ItemStack(Material.WITHER_SKELETON_SKULL);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("Void Sphere", NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("A fragment of the void — power demands a price."),
                lore("■ Hold (main/off) — Speed II, Strength II, Invisibility; Hunger II."),
                lore("■ Strike (main hand) — Wither. Cannot be placed as a skull block.")));
        meta.getPersistentDataContainer().set(voidSphereKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isVoidSphere(ItemStack stack) {
        if (stack == null || stack.getType() != Material.WITHER_SKELETON_SKULL || !stack.hasItemMeta()) return false;
        return hasItemTag(stack.getItemMeta().getPersistentDataContainer(), voidSphereKey);
    }

    private ItemStack buildPhoenixBow() {
        return buildPhoenixBow(false);
    }

    private ItemStack buildPhoenixBowCrafted() {
        return buildPhoenixBow(true);
    }

    private ItemStack buildPhoenixBow(boolean crafted) {
        ItemStack item = new ItemStack(Material.BOW);
        item.addUnsafeEnchantment(Enchantment.POWER, 5);
        item.addUnsafeEnchantment(Enchantment.PUNCH, 2);
        item.addUnsafeEnchantment(Enchantment.FLAME, 1);
        item.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        if (!crafted) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        }
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("🔥 Phoenix Bow", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Arrows leave a trail of fire and ignite on impact."),
                lore("■ Fire trail in flight → burst of flames + ground fire on hit")));
        meta.getPersistentDataContainer().set(phoenixBowKey, PersistentDataType.BYTE, (byte) 1);
        if (crafted) {
            meta.getPersistentDataContainer().set(phoenixBowCraftedKey, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }


    private ItemStack buildWaterBow() {
        return buildWaterBow(false);
    }

    private ItemStack buildWaterBowCrafted() {
        return buildWaterBow(true);
    }

    private ItemStack buildWaterBow(boolean crafted) {
        ItemStack item = new ItemStack(Material.BOW);
        item.addUnsafeEnchantment(Enchantment.POWER, 5);
        item.addUnsafeEnchantment(Enchantment.PUNCH, 1);
        item.addUnsafeEnchantment(Enchantment.INFINITY, 1);
        if (!crafted) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        }
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("🌊 Water Bow", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("A bow touched by ocean magic."),
                lore("■ Arrow hit → water burst + extinguish fire + slowness")));
        meta.getPersistentDataContainer().set(waterBowKey, PersistentDataType.BYTE, (byte) 1);
        if (crafted) {
            meta.getPersistentDataContainer().set(waterBowCraftedKey, PersistentDataType.BYTE, (byte) 1);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildTidalTrident() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        item.addUnsafeEnchantment(Enchantment.IMPALING, 5);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        item.addUnsafeEnchantment(Enchantment.MENDING, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.text("🌊 Tidal Trident", NamedTextColor.BLUE)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lore("Tap throw: focused water beam (stun + damage)."),
                lore("Hold 5s+: charged beam (kills entities + breaks blocks)."),
                lore("Sneak+hold: constant non-breaking high-damage water beam."),
                lore("In air + right-click: riptide launch forward."),
                lore("Sneak beam extinguishes fire; range extends to render distance after ~2s hold."),
                lore("After 5s charge, each extra second adds +10 blocks range.")));
        meta.getPersistentDataContainer().set(tidalTridentKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTidalTrident(ItemStack item) {
        if (item == null || item.getType() != Material.TRIDENT || !item.hasItemMeta()) return false;
        return hasItemTag(item.getItemMeta().getPersistentDataContainer(), tidalTridentKey);
    }

    private static boolean isPlayerOnGround(Player player) {
        return ((LivingEntity) player).isOnGround();
    }

    private static boolean isValidBlockY(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private void extendTidalAura(Player player, long durationMs) {
        long until = System.currentTimeMillis() + durationMs;
        tidalAuraUntilMs.merge(player.getUniqueId(), until, Math::max);
    }

    private void tickTidalWaterAura() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean charging = p.isHandRaised() && isTidalTrident(p.getInventory().getItemInMainHand());
            boolean window = tidalAuraUntilMs.getOrDefault(p.getUniqueId(), 0L) > now;
            if (!charging && !window) continue;
            spawnTidalWaterAuraRing(p);
        }
        tidalAuraUntilMs.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private void spawnTidalWaterAuraRing(Player player) {
        Location base = player.getLocation().clone().add(0, 0.85, 0);
        World w = player.getWorld();
        double phase = player.getTicksLived() * 0.04;
        int n = 12;
        double r = 0.72;
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n + phase;
            double x = Math.cos(ang) * r;
            double z = Math.sin(ang) * r;
            w.spawnParticle(Particle.DRIPPING_WATER, base.clone().add(x, 0, z), 1, 0, 0, 0, 0);
        }
    }

    private void tickVoidSphereHolders() {
        final int dur = 45;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack main = p.getInventory().getItemInMainHand();
            ItemStack off = p.getInventory().getItemInOffHand();
            if (!isVoidSphere(main) && !isVoidSphere(off)) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dur, 1, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, dur, 1, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, dur, 0, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, dur, 1, false, true, true));
        }
    }

    private void tickTidalCharges() {
        Iterator<Map.Entry<UUID, Long>> it = tidalChargeStartMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !player.isHandRaised() || !isTidalTrident(player.getInventory().getItemInMainHand())) {
                it.remove();
                continue;
            }
            extendTidalAura(player, 280L);
            if (player.isSneaking()) {
                // Shift mode is a constant high-damage beam and does not build thick-beam charge.
                long shiftHeldMs = System.currentTimeMillis() - entry.getValue();
                fireShiftWaterBeam(player, shiftHeldMs);
                continue;
            }
            long heldMs = System.currentTimeMillis() - entry.getValue();
            if (heldMs < 5000) continue;
            int chargedSeconds = (int) ((heldMs - 5000L) / 1000L) + 1;
            int count = Math.min(80, chargedSeconds * 10);
            Location center = player.getLocation().add(0, 1.1, 0);
            player.getWorld().spawnParticle(Particle.SPLASH, center, count, 0.45, 0.75, 0.45, 0.08);
            player.getWorld().spawnParticle(Particle.BUBBLE, center, Math.max(8, chargedSeconds * 5), 0.4, 0.6, 0.4, 0.03);
        }
    }

    /**
     * While in air, holding right-click (use) with Tidal Trident: repeat dash forward — hold to “fly”.
     * Throttled to every 5 ticks (~0.25s) so velocity stays controllable.
     */
    private void tickTidalAirDash() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean holdFly = isTidalTrident(player.getInventory().getItemInMainHand())
                    && !isPlayerOnGround(player)
                    && player.isHandRaised();
            if (!holdFly) {
                tidalAirDashCooldown.remove(id);
                tidalAirVortexCount.remove(id);
                continue;
            }
            int wait = tidalAirDashCooldown.getOrDefault(id, 0);
            if (wait > 0) {
                tidalAirDashCooldown.put(id, wait - 1);
                continue;
            }
            launchTidalRiptideAir(player);
            tidalAirDashCooldown.put(id, 4);
        }
    }

    private void launchTidalRiptideAir(Player player) {
        extendTidalAura(player, 3200L);
        Vector dir = player.getEyeLocation().getDirection().normalize();
        player.setVelocity(dir.multiply(2.2));
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.72f, 1.0f);
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.38f, 1.2f);
        int n = tidalAirVortexCount.merge(player.getUniqueId(), 1, Integer::sum);
        if (n % 3 == 0) {
            scheduleRiptideDashVortex(player, world);
        }
    }

    private void scheduleRiptideDashVortex(Player player, World world) {
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                if (!player.getWorld().equals(world)) {
                    cancel();
                    return;
                }
                if (tick >= 14) {
                    cancel();
                    return;
                }
                spawnRiptideDashVortex(player, world, tick);
                tick++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private static void spawnRiptideDashVortex(Player player, World world, int tick) {
        Location base = player.getLocation();
        double spin = tick * 0.75;
        int layers = 7;
        int points = 14;
        for (int layer = 0; layer < layers; layer++) {
            double y = 0.2 + layer * 0.26;
            double radius = 0.38 + layer * 0.06;
            double layerTwist = layer * 0.35;
            for (int i = 0; i < points; i++) {
                double ang = 2 * Math.PI * i / points + spin + layerTwist;
                double x = Math.cos(ang) * radius;
                double z = Math.sin(ang) * radius;
                Location p = base.clone().add(x, y, z);
                Color c = (i + layer) % 2 == 0
                        ? Color.fromRGB(255, 255, 255)
                        : Color.fromRGB(160, 210, 255);
                float size = 0.85f + (layer % 3) * 0.06f;
                world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(c, size));
            }
        }
    }

    private void fireThinWaterBeam(Player player) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location origin = player.getEyeLocation().clone();
        Set<UUID> hit = new HashSet<>();
        for (double d = 0.5; d <= 35; d += 0.5) {
            Location point = origin.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SPLASH, point, 4, 0.08, 0.08, 0.08, 0.02);
            world.spawnParticle(Particle.BUBBLE, point, 2, 0.05, 0.05, 0.05, 0.01);
            for (Entity entity : world.getNearbyEntities(point, 1.0, 1.0, 1.0)) {
                if (!(entity instanceof LivingEntity living) || entity.getUniqueId().equals(player.getUniqueId())) continue;
                if (!hit.add(entity.getUniqueId())) continue;
                living.damage(8.0, player);
                living.setVelocity(new Vector(0, 0, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 8));
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 1.0f, 1.15f);
        extendTidalAura(player, 700L);
    }

    private void fireThickWaterBeam(Player player, int extraSeconds) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location origin = player.getEyeLocation().clone();
        // First 5s are pure warm-up. Range scales only with seconds after 5s.
        double range = 10 + (extraSeconds * 10.0);
        double radius = 2.5;
        Set<UUID> hit = new HashSet<>();

        for (double d = 1; d <= range; d += 1.0) {
            Location point = origin.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SPLASH, point, 40, radius, 3.0, radius, 0.12);
            world.spawnParticle(Particle.BUBBLE, point, 28, radius, 3.0, radius, 0.05);
            world.spawnParticle(Particle.NAUTILUS, point, 10, radius * 0.6, 2.5, radius * 0.6, 0.08);

            for (Entity entity : world.getNearbyEntities(point, radius, 3.0, radius)) {
                if (!(entity instanceof LivingEntity living) || entity.getUniqueId().equals(player.getUniqueId())) continue;
                if (!hit.add(entity.getUniqueId())) continue;
                living.setNoDamageTicks(0);
                living.damage(1_000_000.0, player);
                living.setVelocity(new Vector(0, 0, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 10));
            }

            int blockRadius = 3;
            for (int x = -blockRadius; x <= blockRadius; x++) {
                for (int y = -3; y <= 2; y++) {
                    for (int z = -blockRadius; z <= blockRadius; z++) {
                        if ((x * x) + (z * z) > (blockRadius * blockRadius)) continue;
                        int blockY = point.getBlockY() + y;
                        if (!isValidBlockY(world, blockY)) continue;
                        Block block = world.getBlockAt(point.getBlockX() + x, blockY, point.getBlockZ() + z);
                        Material type = block.getType();
                        if (type == Material.AIR || type == Material.WATER || type == Material.LAVA) continue;
                        if (type == Material.BEDROCK || type == Material.BARRIER || type == Material.REINFORCED_DEEPSLATE
                                || type == Material.END_PORTAL_FRAME || type == Material.COMMAND_BLOCK
                                || type == Material.CHAIN_COMMAND_BLOCK || type == Material.REPEATING_COMMAND_BLOCK) continue;
                        block.breakNaturally();
                    }
                }
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_DROWNED_SHOOT, 2.0f, 0.6f);
        world.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 1.6f, 0.7f);
        extendTidalAura(player, 3500L);
    }

    private void fireShiftWaterBeam(Player player, long heldMs) {
        World world = player.getWorld();
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Location origin = player.getEyeLocation().clone();
        Set<UUID> hit = new HashSet<>();
        // Range ramps to render distance (view distance in chunks * 16) after holding ~2 seconds
        int viewChunks = player.getClientViewDistance();
        double maxRange = viewChunks > 0 ? viewChunks * 16.0 : 512.0;
        double heldSec = heldMs / 1000.0;
        double range = heldSec >= 2.0 ? maxRange : Math.min(maxRange, 45.0 + (heldSec / 2.0) * (maxRange - 45.0));

        for (double d = 0.5; d <= range; d += 0.5) {
            Location point = origin.clone().add(dir.clone().multiply(d));
            world.spawnParticle(Particle.SPLASH, point, 10, 0.22, 0.22, 0.22, 0.03);
            world.spawnParticle(Particle.BUBBLE, point, 6, 0.18, 0.18, 0.18, 0.02);
            world.spawnParticle(Particle.NAUTILUS, point, 2, 0.1, 0.1, 0.1, 0.05);

            // Extinguish fire blocks along the beam
            if (isValidBlockY(world, point.getBlockY())) {
                Block block = point.getBlock();
                if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                    block.setType(Material.AIR);
                }
            }

            for (Entity entity : world.getNearbyEntities(point, 1.35, 1.35, 1.35)) {
                if (!(entity instanceof LivingEntity living) || entity.getUniqueId().equals(player.getUniqueId())) continue;
                if (!hit.add(entity.getUniqueId())) continue;
                living.setFireTicks(0);
                living.setNoDamageTicks(0);
                living.damage(14.0, player);
                living.setVelocity(new Vector(0, 0, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 7));
            }
        }
        world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.55f, 1.55f);
        extendTidalAura(player, 500L);
    }

    private void createWaterBurst(Location center) {
        World world = center.getWorld();
        if (world == null) return;
        world.playSound(center, Sound.ENTITY_PLAYER_SPLASH, 1.1f, 0.9f);
        world.playSound(center, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.8f, 1.2f);
        world.spawnParticle(Particle.SPLASH, center, 35, 0.45, 0.35, 0.45, 0.08);
        world.spawnParticle(Particle.BUBBLE, center, 25, 0.35, 0.25, 0.35, 0.02);
        world.spawnParticle(Particle.NAUTILUS, center, 12, 0.3, 0.3, 0.3, 0.1);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            Block b = center.clone().add(x, 0, z).getBlock();
            if (b.getType() == Material.FIRE) b.setType(Material.AIR);
        }
    }

    private Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static NamespacedKey itemKey(String value) {
        NamespacedKey key = NamespacedKey.fromString("dragonweapons:" + value);
        if (key == null) {
            throw new IllegalArgumentException("Invalid DragonWeapons item key: " + value);
        }
        return key;
    }

    private static boolean hasItemTag(PersistentDataContainer data, NamespacedKey key) {
        if (data.has(key, PersistentDataType.BYTE)) {
            return true;
        }
        NamespacedKey legacyKey = NamespacedKey.fromString("godkit:" + key.getKey());
        return legacyKey != null && data.has(legacyKey, PersistentDataType.BYTE);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();
        if (cmd.equals("tidemode")) {
            return tideHitManager.tabCompleteTideMode(sender, args);
        }
        boolean givesWeapon = switch (cmd) {
            case "enderbow", "dragonbow", "explosivebow", "phoenixbow", "waterbow",
                    "stormtrident", "tidaltrident", "voidscythe", "voidsphere", "tidehit" -> true;
            default -> false;
        };
        return givesWeapon && args.length == 1 && sender.hasPermission("dragonweapons.others")
                ? playerNames(args[0])
                : List.of();
    }

    private List<String> playerNames(String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .toList();
    }
}
