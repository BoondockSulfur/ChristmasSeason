package de.boondocksulfur.christmas.manager;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;
import de.boondocksulfur.christmas.util.LanguageManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Harmless Wichtel pranks ({@code wichtel.pranks} in mobs.yml). Called from the Wichtel's AI
 * task, i.e. on its own thread; effects on a player are handed to the player's thread.
 *
 * <p>Players in creative or spectator mode, opted-out players and players inside WorldGuard
 * regions or GriefPrevention claims are never pranked.
 */
class WichtelPranks {

    private static final int BLOCK_SEARCH_RADIUS = 4;
    private static final int HIDE_TICKS = 100;

    private final ChristmasSeason plugin;
    private final WichtelManager manager;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();
    private final Map<UUID, Long> lastPrank = new ConcurrentHashMap<>();

    WichtelPranks(ChristmasSeason plugin, WichtelManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
    }

    void tick(Mob wichtel) {
        FileConfiguration cfg = plugin.getMobConfig();
        if (!cfg.getBoolean("wichtel.pranks.enabled", true)) return;
        double radius = cfg.getDouble("wichtel.pranks.radius", 8);

        if (manager.carriesLoot(wichtel) && cfg.getBoolean("wichtel.pranks.runAway", true)) {
            Player chaser = nearestTarget(wichtel, radius, 0);
            if (chaser != null) {
                runAway(wichtel, chaser);
                return;
            }
        }

        if (random.nextDouble() >= cfg.getDouble("wichtel.pranks.chance", 0.1)) return;
        long cooldownMs = Math.max(0, cfg.getLong("wichtel.pranks.playerCooldownSeconds", 90)) * 1000L;
        Player target = nearestTarget(wichtel, radius, cooldownMs);
        if (target == null) return;

        List<Runnable> pranks = new ArrayList<>();
        if (cfg.getBoolean("wichtel.pranks.snowball", true)) pranks.add(() -> throwSnowball(wichtel, target));
        if (cfg.getBoolean("wichtel.pranks.tieShoes", true)) pranks.add(() -> tieShoes(wichtel, target));
        if (cfg.getBoolean("wichtel.pranks.doorsAndCandles", true)) {
            Block block = findDoorOrCandle(wichtel);
            if (block != null) pranks.add(() -> toggleBlock(block));
        }
        if (cfg.getBoolean("wichtel.pranks.hide", true) && !wichtel.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            pranks.add(() -> hide(wichtel));
        }
        if (pranks.isEmpty()) return;

        lastPrank.put(target.getUniqueId(), System.currentTimeMillis());
        pranks.get(random.nextInt(pranks.size())).run();
    }

    /** @return the closest player that may be pranked, or {@code null} */
    private Player nearestTarget(Mob wichtel, double radius, long cooldownMs) {
        long now = System.currentTimeMillis();
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : wichtel.getNearbyEntities(radius, radius, radius)) {
            if (!(e instanceof Player p)) continue;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (plugin.getOptOutManager().isOptedOut(p, OptOutManager.Category.WICHTEL)) continue;
            if (cooldownMs > 0 && now - lastPrank.getOrDefault(p.getUniqueId(), 0L) < cooldownMs) continue;
            if (manager.isMischiefProtected(p.getLocation())) continue;
            double d = p.getLocation().distanceSquared(wichtel.getLocation());
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    private void runAway(Mob wichtel, Player chaser) {
        Vector away = wichtel.getLocation().toVector().subtract(chaser.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
        Location dest = wichtel.getLocation().add(away.normalize().multiply(8));
        wichtel.getPathfinder().moveTo(dest, 1.4);
        if (random.nextDouble() < 0.3) giggle(wichtel);
    }

    private void throwSnowball(Mob wichtel, Player target) {
        Vector dir = target.getEyeLocation().toVector().subtract(wichtel.getEyeLocation().toVector());
        if (dir.lengthSquared() < 0.01) return;
        double distance = dir.length();
        dir.normalize().multiply(1.1);
        dir.setY(dir.getY() + 0.02 * distance); // slight arc
        wichtel.launchProjectile(Snowball.class, dir);
        wichtel.getWorld().playSound(wichtel.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 0.6f, 1.4f);
    }

    private void tieShoes(Mob wichtel, Player target) {
        giggle(wichtel);
        scheduler.runForEntity(target, () -> {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, true, true));
            target.playSound(target.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1.2f);
            lang.send(target, "mobs.wichtel.tie-shoes");
        });
    }

    /** @return a random wooden door, trapdoor, gate or lit candle near the Wichtel, or {@code null} */
    private Block findDoorOrCandle(Mob wichtel) {
        Block center = wichtel.getLocation().getBlock();
        List<Block> found = new ArrayList<>();
        for (int dx = -BLOCK_SEARCH_RADIUS; dx <= BLOCK_SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -BLOCK_SEARCH_RADIUS; dz <= BLOCK_SEARCH_RADIUS; dz++) {
                    Block b = center.getRelative(dx, dy, dz);
                    if (isPrankBlock(b) && !manager.isMischiefProtected(b.getLocation())) found.add(b);
                }
            }
        }
        return found.isEmpty() ? null : found.get(random.nextInt(found.size()));
    }

    private static boolean isPrankBlock(Block b) {
        BlockData data = b.getBlockData();
        if (Tag.CANDLES.isTagged(b.getType()) || Tag.CANDLE_CAKES.isTagged(b.getType())) {
            return data instanceof Lightable l && l.isLit();
        }
        if (Tag.WOODEN_DOORS.isTagged(b.getType())) {
            return data instanceof Bisected bi && bi.getHalf() == Bisected.Half.BOTTOM;
        }
        return Tag.WOODEN_TRAPDOORS.isTagged(b.getType()) || Tag.FENCE_GATES.isTagged(b.getType());
    }

    private void toggleBlock(Block block) {
        BlockData data = block.getBlockData();
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        if (data instanceof Lightable lit && !(data instanceof Openable)) {
            lit.setLit(false);
            block.setBlockData(lit);
            block.getWorld().playSound(at, Sound.BLOCK_CANDLE_EXTINGUISH, 1f, 1f);
            block.getWorld().spawnParticle(Particle.SMOKE, at, 6, 0.1, 0.2, 0.1, 0.01);
            return;
        }
        if (!(data instanceof Openable door)) return;
        boolean open = !door.isOpen();
        if (Tag.WOODEN_DOORS.isTagged(block.getType())) {
            Block top = block.getRelative(0, 1, 0);
            if (top.getType() == block.getType() && top.getBlockData() instanceof Openable upper) {
                upper.setOpen(open);
                top.setBlockData(upper, false);
            }
        }
        door.setOpen(open);
        block.setBlockData(door, true);
        Sound sound;
        if (Tag.FENCE_GATES.isTagged(block.getType())) sound = open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE;
        else if (Tag.WOODEN_TRAPDOORS.isTagged(block.getType())) sound = open ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE;
        else sound = open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE;
        block.getWorld().playSound(at, sound, 1f, 1f);
    }

    private void hide(Mob wichtel) {
        wichtel.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, HIDE_TICKS, 0, false, false, false));
        wichtel.setCustomNameVisible(false);
        wichtel.getWorld().spawnParticle(Particle.SNOWFLAKE, wichtel.getLocation().add(0, 0.5, 0), 25, 0.3, 0.4, 0.3, 0.02);
        giggle(wichtel);
        scheduler.runForEntityLater(wichtel, () -> {
            if (wichtel.isValid()) wichtel.setCustomNameVisible(true);
        }, () -> { }, HIDE_TICKS);
    }

    private void giggle(Mob wichtel) {
        wichtel.getWorld().playSound(wichtel.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 1.8f);
    }
}
