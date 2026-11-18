package de.boondocksulfur.christmas.manager;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.LanguageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Friendly elf help ({@code elves.helpers} in mobs.yml). Called from the elf's AI task, i.e.
 * on its own thread; effects on a player are handed to the player's thread.
 *
 * <p>Items a Wichtel dropped keep their owner and item frame. Elves pick them up, glide to
 * the owner or the frame and hand them over; if neither is reachable the item is dropped
 * again where the elf is. Treats are handed over the same way.
 */
class ElfHelpers {

    private static final double REACH = 1.8;
    private static final double FLIGHT_STEP = 0.5;   // blocks per flight step
    private static final long FLIGHT_PERIOD = 2L;    // ticks between steps, i.e. 5 blocks per second
    private static final int FLIGHT_TIMEOUT_TICKS = 600;
    private static final double MAX_RETURN_DISTANCE = 48;
    private static final double GIFT_SEARCH_DISTANCE = 64;
    private static final int CROP_RADIUS = 4;
    private static final int MAX_CROPS = 6;

    private final ChristmasSeason plugin;
    private final WichtelManager manager;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();
    private final Map<UUID, Long> lastHelp = new ConcurrentHashMap<>();
    private final Map<UUID, WrappedTask> flights = new ConcurrentHashMap<>();

    ElfHelpers(ChristmasSeason plugin, WichtelManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    /** @return {@code true} while the elf is flying to a player, frame or thief */
    boolean isBusy(Entity elf) {
        return flights.containsKey(elf.getUniqueId());
    }

    void tick(LivingEntity elf) {
        if (isBusy(elf)) return;
        FileConfiguration cfg = plugin.getMobConfig();
        boolean enabled = cfg.getBoolean("elves.helpers.enabled", true);
        boolean returnLoot = enabled && cfg.getBoolean("elves.helpers.returnLoot", true);

        if (manager.carriesLoot(elf)) {
            if (returnLoot || manager.isTreat(elf)) startDelivery(elf);
            else manager.returnLoot(elf);
            return;
        }
        if (!enabled) return;
        double radius = cfg.getDouble("elves.helpers.radius", 10);
        if (random.nextDouble() >= cfg.getDouble("elves.helpers.chance", 0.15)) return;

        List<Runnable> options = new ArrayList<>();
        if (cfg.getBoolean("elves.helpers.chaseWichtel", true)) {
            LivingEntity thief = nearestThief(elf, radius);
            if (thief != null) options.add(() -> chase(elf, thief));
        }

        long cooldownMs = Math.max(0, cfg.getLong("elves.helpers.playerCooldownSeconds", 120)) * 1000L;
        Player player = nearestPlayer(elf, radius, cooldownMs);
        if (player != null) {
            if (cfg.getBoolean("elves.helpers.heal", true)
                    && player.getHealth() <= cfg.getDouble("elves.helpers.healBelowHealth", 8)) {
                // A hurt player always comes first
                markHelped(player);
                heal(elf, player);
                return;
            }
            double treatDistance = cfg.getDouble("elves.helpers.treatDistance", 3);
            if (cfg.getBoolean("elves.helpers.treats", true)
                    && player.getLocation().distanceSquared(elf.getLocation()) <= treatDistance * treatDistance) {
                ItemStack treat = randomTreat(cfg.getStringList("elves.helpers.treatItems"));
                if (treat != null) options.add(() -> { markHelped(player); giveTreat(elf, player, treat); });
            }
            if (cfg.getBoolean("elves.helpers.giftTrail", true)) {
                Location gift = plugin.getGiftManager().nearestGift(player.getLocation(), GIFT_SEARCH_DISTANCE);
                if (gift != null) options.add(() -> { markHelped(player); giftTrail(player, gift); });
            }
        }
        if (cfg.getBoolean("elves.helpers.growCrops", true)) {
            List<Block> crops = findCrops(elf);
            if (!crops.isEmpty()) options.add(() -> growCrops(crops));
        }
        if (!options.isEmpty()) options.get(random.nextInt(options.size())).run();
    }

    // ------------------------------------------------------------- loot

    private boolean ownerReachable(UUID owner, LivingEntity elf) {
        if (owner == null) return false;
        Player p = Bukkit.getPlayer(owner);
        return p != null && p.isOnline() && p.getWorld().equals(elf.getWorld())
                && p.getLocation().distanceSquared(elf.getLocation()) <= MAX_RETURN_DISTANCE * MAX_RETURN_DISTANCE
                && !plugin.getOptOutManager().isOptedOut(p, OptOutManager.Category.ELVES);
    }

    /**
     * Flies the carried item to its item frame, its owner or the spot it was taken from and
     * hands it over there. Targets out of reach get the item back by Wichtel magic.
     */
    private void startDelivery(LivingEntity elf) {
        String frameRef = manager.lootFrame(elf);
        if (frameRef != null) {
            Location frameLoc = inReach(WichtelManager.frameLocation(frameRef), elf);
            if (frameLoc == null) {
                manager.returnLoot(elf);
                return;
            }
            fly(elf, () -> frameLoc, () -> handOverToFrame(elf, frameRef, frameLoc));
            return;
        }
        UUID owner = manager.lootOwner(elf);
        if (ownerReachable(owner, elf)) {
            Player player = Bukkit.getPlayer(owner);
            boolean treat = manager.isTreat(elf);
            fly(elf, () -> player.isOnline() && player.getWorld().equals(elf.getWorld())
                            ? player.getLocation().add(0, 1.0, 0) : null,
                    () -> handOverToPlayer(elf, player, treat));
            return;
        }
        Location origin = inReach(manager.lootOrigin(elf), elf);
        if (origin == null || manager.isTreat(elf)) {
            manager.returnLoot(elf);
            return;
        }
        fly(elf, () -> origin, () -> {
            WichtelManager.Loot loot = manager.removeLoot(elf);
            if (loot != null) manager.sendBack(loot, origin);
        });
    }

    /** @return {@code loc} if it is in the elf's world and in range, otherwise {@code null} */
    private static Location inReach(Location loc, LivingEntity elf) {
        if (loc == null || !loc.getWorld().equals(elf.getWorld())) return null;
        return loc.distanceSquared(elf.getLocation()) <= MAX_RETURN_DISTANCE * MAX_RETURN_DISTANCE ? loc : null;
    }

    private void handOverToPlayer(LivingEntity elf, Player player, boolean treat) {
        WichtelManager.Loot carried = manager.removeLoot(elf);
        if (carried == null) return;
        ItemStack loot = carried.item();
        Location fallback = elf.getLocation();
        elf.getWorld().playSound(fallback, Sound.ENTITY_ALLAY_ITEM_GIVEN, 1f, 1.2f);
        scheduler.runForEntityLater(player, () -> {
            for (ItemStack rest : player.getInventory().addItem(loot).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            lang.send(player, treat ? "mobs.elves.treat" : "mobs.elves.return-loot");
        }, () -> scheduler.runAtLocation(fallback, () -> fallback.getWorld().dropItemNaturally(fallback, loot)), 1L);
    }

    private void handOverToFrame(LivingEntity elf, String frameRef, Location frameLoc) {
        UUID frameId = WichtelManager.frameId(frameRef);
        for (Entity e : elf.getNearbyEntities(REACH + 1, REACH + 1, REACH + 1)) {
            if (e instanceof ItemFrame frame && frame.getUniqueId().equals(frameId) && frame.isValid()
                    && !frame.isFixed() && frame.getItem().getType().isAir()) {
                WichtelManager.Loot loot = manager.removeLoot(elf);
                if (loot == null) return;
                frame.setItem(loot.item(), false);
                elf.getWorld().playSound(frameLoc, Sound.ENTITY_ALLAY_ITEM_GIVEN, 1f, 1.2f);
                elf.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, frameLoc, 8, 0.3, 0.3, 0.3, 0);
                return;
            }
        }
        manager.returnLoot(elf); // frame gone or refilled meanwhile: the item waits below it
    }

    private LivingEntity nearestThief(LivingEntity elf, double radius) {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : elf.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof LivingEntity le) || !le.getScoreboardTags().contains(WichtelManager.TAG_WICHTEL)) continue;
            if (!manager.carriesLoot(le)) continue;
            double d = le.getLocation().distanceSquared(elf.getLocation());
            if (d < bestSq) {
                bestSq = d;
                best = le;
            }
        }
        return best;
    }

    /** The elf swoops in and takes the loot off the Wichtel. */
    private void chase(LivingEntity elf, LivingEntity thief) {
        fly(elf, () -> thief.isValid() ? thief.getLocation().add(0, 1.2, 0) : null, () -> scheduler.runForEntity(thief, () -> {
            if (!thief.isValid() || !manager.carriesLoot(thief)) return;
            WichtelManager.Loot loot = manager.removeLoot(thief);
            if (loot == null) return;
            Location at = thief.getLocation();
            scheduler.runForEntityLater(elf, () -> {
                if (elf.isValid() && !manager.carriesLoot(elf)) manager.takeLoot(elf, loot);
                else manager.sendBack(loot, at);
            }, () -> manager.sendBack(loot, at), 1L);
            thief.getWorld().playSound(thief.getLocation(), Sound.ENTITY_WITCH_HURT, 0.7f, 1.8f);
            thief.getWorld().spawnParticle(Particle.POOF, thief.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02);
        }));
    }

    /**
     * Glides the elf to a (possibly moving) target in small steps and runs {@code arrived} once
     * it is in reach. The allay AI is paused meanwhile, otherwise it pulls the elf elsewhere.
     * If the target disappears or is not reached in time, carried loot goes back by magic.
     */
    private void fly(LivingEntity elf, Supplier<Location> target, Runnable arrived) {
        UUID id = elf.getUniqueId();
        if (elf instanceof Mob mob) mob.setAware(false);
        int[] ticks = {0};
        WrappedTask task = scheduler.runForEntityTimer(elf, () -> {
            ticks[0] += FLIGHT_PERIOD;
            Location to = target.get();
            Location from = elf.getLocation();
            if (to == null || !to.getWorld().equals(from.getWorld()) || ticks[0] > FLIGHT_TIMEOUT_TICKS) {
                land(elf);
                manager.returnLoot(elf);
                return;
            }
            double distance = from.distance(to);
            if (distance <= REACH) {
                land(elf);
                arrived.run();
                return;
            }
            Vector dir = to.toVector().subtract(from.toVector()).normalize();
            Location next = from.clone().add(dir.clone().multiply(Math.min(FLIGHT_STEP, distance)));
            // Rise over walls instead of flying into them
            if (!next.getBlock().isPassable()) next = from.clone().add(0, FLIGHT_STEP, 0);
            next.setDirection(dir);
            if (scheduler.isFolia()) elf.teleportAsync(next);
            else elf.teleport(next);
        }, () -> flights.remove(id), 1L, FLIGHT_PERIOD);
        if (task != null) flights.put(id, task);
        else if (elf instanceof Mob mob) mob.setAware(true);
    }

    private void land(LivingEntity elf) {
        WrappedTask task = flights.remove(elf.getUniqueId());
        if (task != null) task.cancel();
        if (elf instanceof Mob mob) mob.setAware(true);
    }

    // ---------------------------------------------------------- players

    private Player nearestPlayer(LivingEntity elf, double radius, long cooldownMs) {
        long now = System.currentTimeMillis();
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : elf.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player p) || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (plugin.getOptOutManager().isOptedOut(p, OptOutManager.Category.ELVES)) continue;
            if (cooldownMs > 0 && now - lastHelp.getOrDefault(p.getUniqueId(), 0L) < cooldownMs) continue;
            double d = p.getLocation().distanceSquared(elf.getLocation());
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private void markHelped(Player player) {
        lastHelp.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void heal(LivingEntity elf, Player player) {
        elf.getWorld().playSound(elf.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.3f);
        scheduler.runForEntity(player, () -> {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1, false, true, true));
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.8, 0), 5, 0.4, 0.3, 0.4, 0);
            lang.send(player, "mobs.elves.heal");
        });
    }

    private ItemStack randomTreat(List<String> entries) {
        List<ItemStack> treats = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = LootManager.splitMaterialAmount(entry);
            Material m = Material.matchMaterial(parts[0]);
            if (m == null || !m.isItem()) continue;
            int amount = parts[1] == null ? 1 : Math.max(1, Math.min(m.getMaxStackSize(), Integer.parseInt(parts[1])));
            treats.add(new ItemStack(m, amount));
        }
        return treats.isEmpty() ? null : treats.get(random.nextInt(treats.size()));
    }

    /** The elf takes the treat into its hand and hands it over (players within treatDistance only). */
    private void giveTreat(LivingEntity elf, Player player, ItemStack treat) {
        manager.takeLoot(elf, new WichtelManager.Loot(treat, player.getUniqueId(), null, null, true));
        elf.getWorld().playSound(elf.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1f, 1.2f);
        startDelivery(elf);
    }

    /** A sparkle line from the player towards the gift, shown twice; only the player sees it. */
    private void giftTrail(Player player, Location gift) {
        Runnable draw = () -> {
            if (!player.isOnline() || !player.getWorld().equals(gift.getWorld())) return;
            Location eye = player.getEyeLocation();
            Vector dir = gift.clone().add(0.5, 0.5, 0.5).toVector().subtract(eye.toVector());
            double length = Math.min(dir.length(), 24);
            if (length < 1) return;
            dir.normalize();
            for (double d = 1; d <= length; d += 0.5) {
                player.spawnParticle(Particle.END_ROD, eye.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
            }
        };
        scheduler.runForEntity(player, () -> {
            draw.run();
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
            lang.send(player, "mobs.elves.gift-trail");
        });
        scheduler.runForEntityLater(player, draw, () -> { }, 40L);
    }

    private List<Block> findCrops(LivingEntity elf) {
        List<Block> crops = new ArrayList<>();
        Block center = elf.getLocation().getBlock();
        for (int dx = -CROP_RADIUS; dx <= CROP_RADIUS; dx++) {
            for (int dy = -3; dy <= 1; dy++) {
                for (int dz = -CROP_RADIUS; dz <= CROP_RADIUS; dz++) {
                    Block b = center.getRelative(dx, dy, dz);
                    // Pitcher crops grow into two blocks - changing the age alone would break them
                    if (!Tag.CROPS.isTagged(b.getType()) || b.getType() == Material.PITCHER_CROP) continue;
                    if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()
                            && !manager.isMischiefProtected(b.getLocation())) {
                        crops.add(b);
                    }
                }
            }
        }
        return crops;
    }

    private void growCrops(List<Block> crops) {
        java.util.Collections.shuffle(crops, random);
        for (Block b : crops.subList(0, Math.min(MAX_CROPS, crops.size()))) {
            if (!(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge()) continue;
            a.setAge(a.getAge() + 1);
            b.setBlockData(a);
            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5), 4, 0.25, 0.25, 0.25, 0);
        }
    }
}
