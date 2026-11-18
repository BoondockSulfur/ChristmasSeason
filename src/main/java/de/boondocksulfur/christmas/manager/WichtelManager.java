package de.boondocksulfur.christmas.manager;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import de.boondocksulfur.christmas.ChristmasSeason;
import de.boondocksulfur.christmas.util.LanguageManager;
import de.boondocksulfur.christmas.util.SpawnUtil;
import de.boondocksulfur.christmas.util.FoliaSchedulerHelper;

import java.util.*;

/**
 * Spawns "Wichtel" (baby zombies) and "Elves" (allays) that collect decoration items and
 * hop around. Wichtel carry off loose items and play pranks ({@link WichtelPranks}), elves
 * help players and bring stolen items back ({@link ElfHelpers}). Mobs are tagged with
 * scoreboard tags so they can be recognised and adopted after a restart or reload.
 */
public class WichtelManager {

    public static final String TAG_WICHTEL = "XMAS_WICHTEL";
    public static final String TAG_ELF     = "XMAS_ELF";

    private final ChristmasSeason plugin;
    private final LanguageManager lang;
    private final FoliaSchedulerHelper scheduler;
    private final Random random = new Random();

    private final Map<UUID, WrappedTask> playerWichtelTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, WrappedTask> playerElfTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /** Per-mob AI tasks (entity scheduler). */
    private final Map<UUID, WrappedTask> entityStealTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long DEATH_PROTECTION_MS = 5 * 60 * 1000L;
    private static final double DEATH_PROTECTION_RADIUS_SQ = 8 * 8;
    private record DeathMark(String world, double x, double y, double z, long time) {}
    private final Collection<DeathMark> recentDeaths = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Tracked mobs (UUID -> world name, for the per-world caps); mutated from several region threads. */
    private final Map<UUID, String> trackedWichtel = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, String> trackedElfen   = new java.util.concurrent.ConcurrentHashMap<>();

    /** Carried loot: a copy of the item, the owner's UUID and the item frame or spot it came from. */
    private final NamespacedKey lootItemKey;
    private final NamespacedKey lootOwnerKey;
    private final NamespacedKey lootFrameKey;
    private final NamespacedKey lootOriginKey;
    private final NamespacedKey lootTreatKey;

    private final WichtelPranks pranks;
    private final ElfHelpers helpers;

    public WichtelManager(ChristmasSeason plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.scheduler = plugin.getFoliaScheduler();
        this.lootItemKey = new NamespacedKey(plugin, "loot_item");
        this.lootOwnerKey = new NamespacedKey(plugin, "loot_owner");
        this.lootFrameKey = new NamespacedKey(plugin, "loot_frame");
        this.lootOriginKey = new NamespacedKey(plugin, "loot_origin");
        this.lootTreatKey = new NamespacedKey(plugin, "loot_treat");
        this.pranks = new WichtelPranks(plugin, this);
        this.helpers = new ElfHelpers(plugin, this);
    }

    // -------------------------------------------------------------- lifecycle

    public void start() {
        stop();
        plugin.debug("WichtelManager started (per-player spawns, per-entity AI)");
    }

    /**
     * Stops spawn and AI timers. Tracking sets are kept so that {@code /xmas reload}
     * keeps the spawn caps intact; AI tasks are re-armed by {@link LoadedObjectSync}.
     */
    public void stop() {
        for (WrappedTask task : playerWichtelTasks.values()) {
            if (task != null) task.cancel();
        }
        for (WrappedTask task : playerElfTasks.values()) {
            if (task != null) task.cancel();
        }
        playerWichtelTasks.clear();
        playerElfTasks.clear();

        for (WrappedTask task : entityStealTasks.values()) {
            if (task != null) task.cancel();
        }
        entityStealTasks.clear();
    }

    /** Starts the Wichtel and elf spawn timers for a player. */
    public void startPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();

        WrappedTask oldWichtel = playerWichtelTasks.remove(uuid);
        if (oldWichtel != null) oldWichtel.cancel();
        WrappedTask oldElf = playerElfTasks.remove(uuid);
        if (oldElf != null) oldElf.cancel();

        if (plugin.getMobConfig().getBoolean("wichtel.enabled", true)) {
            int interval = Math.max(5, plugin.getMobConfig().getInt("wichtel.spawnIntervalSeconds", 45));
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                if (plugin.getOptOutManager().isOptedOut(player, OptOutManager.Category.WICHTEL)) return;
                spawnWichtelNearPlayer(player);
            }, () -> playerWichtelTasks.remove(uuid), 40L, interval * 20L);
            if (task != null) playerWichtelTasks.put(uuid, task);
        }

        if (plugin.getMobConfig().getBoolean("elves.enabled", true)) {
            int interval = Math.max(5, plugin.getMobConfig().getInt("elves.spawnIntervalSeconds", 60));
            WrappedTask task = scheduler.runForEntityTimer(player, () -> {
                if (!player.isOnline() || !player.isValid()) {
                    stopPlayerSpawning(player);
                    return;
                }
                if (plugin.getOptOutManager().isOptedOut(player, OptOutManager.Category.ELVES)) return;
                spawnElfNearPlayer(player);
            }, () -> playerElfTasks.remove(uuid), 60L, interval * 20L);
            if (task != null) playerElfTasks.put(uuid, task);
        }

        plugin.debug("Wichtel/elf spawning started for " + player.getName());
    }

    /** Stops the spawn timers for a player. */
    public void stopPlayerSpawning(Player player) {
        UUID uuid = player.getUniqueId();
        WrappedTask wichtelTask = playerWichtelTasks.remove(uuid);
        if (wichtelTask != null) wichtelTask.cancel();
        WrappedTask elfTask = playerElfTasks.remove(uuid);
        if (elfTask != null) elfTask.cancel();
        plugin.debug("Wichtel/elf spawning stopped for " + player.getName());
    }

    // --------------------------------------------------------------- tracking

    /** @return number of tracked Wichtel and elves */
    public int getTrackedCount() {
        return trackedWichtel.size() + trackedElfen.size();
    }

    /** @return {@code true} if the entity carries one of our tags */
    public boolean isEventMob(Entity entity) {
        Set<String> tags = entity.getScoreboardTags();
        return tags.contains(TAG_WICHTEL) || tags.contains(TAG_ELF);
    }

    /**
     * Adopts a tagged mob (after restart/reload or chunk load): tracks it, starts its
     * AI task and a fresh lifetime timer. Must run on the entity's thread.
     */
    public void adopt(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !entity.isValid()) return;
        Set<String> tags = entity.getScoreboardTags();
        UUID id = entity.getUniqueId();

        Map<UUID, String> tracker;
        if (tags.contains(TAG_WICHTEL)) tracker = trackedWichtel;
        else if (tags.contains(TAG_ELF)) tracker = trackedElfen;
        else return;
        living.setCanPickupItems(false);
        living.setCustomNameVisible(true); // a restart during a hide prank must not keep the name hidden
        if (living instanceof Mob mob) mob.setAware(true); // elves fly with their AI paused

        boolean newlyTracked = tracker.put(id, entity.getWorld().getName()) == null;
        // After a chunk reload the old task holds a stale handle - always re-arm with the live one
        WrappedTask old = entityStealTasks.remove(id);
        if (old != null) old.cancel();
        startEntityStealTask(living);
        if (newlyTracked) {
            scheduleLifetime(living, tracker);
            plugin.debug("Adopted event mob " + entity.getType() + " " + id);
        }
    }

    /** Snow worlds plus the optional extra {@code wichtel.world} / {@code elves.world}. */
    public java.util.List<World> mobWorlds() {
        java.util.LinkedHashSet<World> worlds = new java.util.LinkedHashSet<>(plugin.getSnowWorlds());
        for (String key : new String[]{"wichtel.world", "elves.world"}) {
            String extra = plugin.getMobConfig().getString(key);
            if (extra != null && !extra.isBlank()) {
                World w = Bukkit.getWorld(extra);
                if (w != null) worlds.add(w);
            }
        }
        return new java.util.ArrayList<>(worlds);
    }

    private boolean isMobWorld(World w, String key) {
        String extra = plugin.getMobConfig().getString(key);
        if (extra != null && !extra.isBlank() && extra.equals(w.getName())) return true;
        return plugin.isSnowWorld(w);
    }

    /** Removes all tracked Wichtel and elves ({@code /xmas off}); untracked ones are found by {@link LoadedObjectSync}. */
    public void cleanup() {
        int removed = 0;
        int wichtelCount = trackedWichtel.size();
        int elfenCount = trackedElfen.size();

        removed += removeTracked(trackedWichtel);
        removed += removeTracked(trackedElfen);

        lang.logInfo("log.cleanup.wichtel", removed, wichtelCount, elfenCount);
    }

    /** @return tracked mobs of this map that live in the world */
    private static int countIn(Map<UUID, String> tracker, World w) {
        int n = 0;
        for (String world : tracker.values()) if (world.equals(w.getName())) n++;
        return n;
    }

    private int removeTracked(Map<UUID, String> tracker) {
        int removed = 0;
        Iterator<UUID> it = tracker.keySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            WrappedTask stealTask = entityStealTasks.remove(uuid);
            if (stealTask != null) stealTask.cancel();

            Entity e = Bukkit.getEntity(uuid);
            if (e != null && e.isValid()) {
                scheduler.runForEntity(e, () -> {
                    if (!e.isDead() && e.isValid()) {
                        returnLoot(e);
                        e.remove();
                    }
                });
                removed++;
            }
            it.remove();
        }
        return removed;
    }

    // --------------------------------------------------------------- spawning

    /** Spawns a Wichtel near the player (on the region thread). */
    private void spawnWichtelNearPlayer(Player player) {
        World w = player.getWorld();
        if (!isMobWorld(w, "wichtel.world")) return;

        final int maxWichtel = plugin.getMobConfig().getInt("wichtel.maxPerWorld", 6);
        if (countIn(trackedWichtel, w) >= maxWichtel) return;
        if (plugin.isNearCapReached(player, "wichtel.maxNearPlayer", e -> e.getScoreboardTags().contains(TAG_WICHTEL))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            // Re-check: parallel spawns for other players may have filled the cap meanwhile
            if (countIn(trackedWichtel, w) >= maxWichtel) return;

            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);
            if (spawn == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(spawn)) {
                plugin.debug("Wichtel spawn blocked by region protection at " + spawn.getBlockX() + "," + spawn.getBlockZ());
                return;
            }

            Zombie z = (Zombie) w.spawnEntity(spawn, EntityType.ZOMBIE);
            z.setBaby();
            z.customName(lang.getComponent("entity.wichtel"));
            z.setCustomNameVisible(true);
            z.setRemoveWhenFarAway(false);
            // Items a Wichtel picks up would vanish with it when its lifetime ends
            z.setCanPickupItems(false);
            z.getScoreboardTags().add(TAG_WICHTEL);
            if (z.getEquipment() != null) z.getEquipment().clear();
            z.setTarget(null);
            trackedWichtel.put(z.getUniqueId(), w.getName());

            startEntityStealTask(z);
            scheduleLifetime(z, trackedWichtel);
        });
    }

    /** Spawns an elf near the player (on the region thread). */
    private void spawnElfNearPlayer(Player player) {
        World w = player.getWorld();
        if (!isMobWorld(w, "elves.world")) return;

        final int maxElfen = plugin.getMobConfig().getInt("elves.maxPerWorld", 4);
        if (countIn(trackedElfen, w) >= maxElfen) return;
        if (plugin.isNearCapReached(player, "elves.maxNearPlayer", e -> e.getScoreboardTags().contains(TAG_ELF))) return;

        Location playerLoc = player.getLocation();
        scheduler.runAtLocation(playerLoc, () -> {
            if (countIn(trackedElfen, w) >= maxElfen) return;

            Location spawn = SpawnUtil.findSafeSpawnLocation(w, playerLoc, 10, 5);
            if (spawn == null) return;
            if (plugin.getRegionIntegration() != null && !plugin.getRegionIntegration().canSpawnAt(spawn)) {
                plugin.debug("Elf spawn blocked by region protection at " + spawn.getBlockX() + "," + spawn.getBlockZ());
                return;
            }

            Allay a = (Allay) w.spawnEntity(spawn, EntityType.ALLAY);
            a.customName(lang.getComponent("entity.elf"));
            a.setCustomNameVisible(true);
            a.setRemoveWhenFarAway(false);
            a.setCanPickupItems(false);
            a.getScoreboardTags().add(TAG_ELF);
            trackedElfen.put(a.getUniqueId(), w.getName());

            startEntityStealTask(a);
            scheduleLifetime(a, trackedElfen);
        });
    }

    /**
     * Removes the mob after {@code wichtel.lifetimeSeconds}. On Folia the task is retired
     * if the mob dies earlier - the retired callback keeps the tracking set clean, otherwise
     * dead UUIDs would fill the spawn cap.
     */
    private void scheduleLifetime(LivingEntity entity, Map<UUID, String> tracker) {
        int wichtelLifetime = plugin.getMobConfig().getInt("wichtel.lifetimeSeconds", 240);
        int lifetime = Math.max(10, tracker == trackedElfen
                ? plugin.getMobConfig().getInt("elves.lifetimeSeconds", wichtelLifetime)
                : wichtelLifetime);
        UUID id = entity.getUniqueId();
        Runnable cleanup = () -> {
            tracker.remove(id);
            WrappedTask st = entityStealTasks.remove(id);
            if (st != null) st.cancel();
        };
        scheduler.runForEntityLater(entity, () -> {
            // Resolve by UUID: on Paper the handle may be stale after a chunk reload
            Entity live = Bukkit.getEntity(id);
            if (live != null && live.isValid()) {
                returnLoot(live);
                live.remove();
            }
            cleanup.run();
        }, cleanup, lifetime * 20L);
    }

    /** Keeps drops of a player's death safe from Wichtel until they would despawn anyway. */
    public void recordDeath(Location location) {
        long now = System.currentTimeMillis();
        recentDeaths.removeIf(d -> now - d.time() > DEATH_PROTECTION_MS);
        recentDeaths.add(new DeathMark(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), now));
    }

    private boolean nearRecentDeath(Location l) {
        long now = System.currentTimeMillis();
        for (DeathMark d : recentDeaths) {
            if (now - d.time() > DEATH_PROTECTION_MS || !d.world().equals(l.getWorld().getName())) continue;
            double dx = d.x() - l.getX(), dy = d.y() - l.getY(), dz = d.z() - l.getZ();
            if (dx * dx + dy * dy + dz * dz <= DEATH_PROTECTION_RADIUS_SQ) return true;
        }
        return false;
    }

    /** A carried item with the place it was taken from. */
    record Loot(ItemStack item, UUID owner, String frame, Location origin, boolean treat) {}

    /**
     * Sends the item an event mob carries back to where it was taken from: its item frame or
     * the spot on the ground. Treats are discarded. Called before every removal by the plugin.
     * Must run on the entity's thread.
     */
    public void returnLoot(Entity entity) {
        Loot loot = removeLoot(entity);
        if (loot == null || loot.treat()) return;
        Location from = entity.getLocation();
        from.getWorld().spawnParticle(Particle.POOF, from.clone().add(0, 0.6, 0), 8, 0.2, 0.2, 0.2, 0.02);
        sendBack(loot, from);
    }

    /**
     * On death the item would drop where the mob died: take it out of the vanilla drops and
     * send it back instead. Runs inside {@code EntityDeathEvent}.
     */
    public void handleDeath(LivingEntity entity, List<ItemStack> drops) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        byte[] stored = pdc.get(lootItemKey, PersistentDataType.BYTE_ARRAY);
        if (stored == null) return;
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(stored);
        } catch (RuntimeException e) {
            clearLootTags(pdc);
            return; // vanilla drops the held item as usual
        }
        for (Iterator<ItemStack> it = drops.iterator(); it.hasNext(); ) {
            ItemStack d = it.next();
            if (d != null && d.isSimilar(item) && d.getAmount() == item.getAmount()) {
                it.remove();
                break;
            }
        }
        if (entity.getEquipment() != null) entity.getEquipment().setItemInMainHand(null);
        Loot loot = new Loot(item, lootOwner(entity), lootFrame(entity), lootOrigin(entity), isTreat(entity));
        clearLootTags(pdc);
        if (!loot.treat()) sendBack(loot, entity.getLocation());
    }

    /**
     * Puts the item back into its frame or onto its spot. The target chunk is loaded if needed;
     * loot from before origins were recorded lands at {@code fallback}.
     */
    void sendBack(Loot loot, Location fallback) {
        Location target = loot.frame() != null ? frameLocation(loot.frame()) : loot.origin();
        Location at = target != null ? target : fallback;
        World world = at.getWorld();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        Runnable place = () -> scheduler.runAtLocation(at, () -> placeBack(loot, at));
        if (world.isChunkLoaded(cx, cz)) place.run();
        else world.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> place.run());
    }

    /** Must run on the region thread of {@code at}. */
    void placeBack(Loot loot, Location at) {
        World world = at.getWorld();
        if (loot.frame() != null) {
            UUID frameId = frameId(loot.frame());
            for (Entity e : world.getNearbyEntities(at, 1, 1, 1)) {
                if (e instanceof ItemFrame frame && frame.getUniqueId().equals(frameId) && frame.isValid()
                        && !frame.isFixed() && frame.getItem().getType().isAir()) {
                    frame.setItem(loot.item(), false);
                    returnEffect(at);
                    return;
                }
            }
        }
        // Frame gone or refilled meanwhile: the item waits below it
        Item item = world.dropItem(at, loot.item());
        item.setVelocity(new org.bukkit.util.Vector());
        if (loot.owner() != null) item.setThrower(loot.owner());
        returnEffect(at);
    }

    private static void returnEffect(Location at) {
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 0.3, 0), 12, 0.2, 0.2, 0.2, 0.01);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
    }

    /** @return the block centre of the frame reference, or {@code null} if its world is gone */
    static Location frameLocation(String frameRef) {
        String[] p = frameRef.split(";");
        World world = p.length == 5 ? Bukkit.getWorld(p[0]) : null;
        if (world == null) return null;
        try {
            return new Location(world, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]) + 0.5, Integer.parseInt(p[3]) + 0.5);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static UUID frameId(String frameRef) {
        try {
            return UUID.fromString(frameRef.substring(frameRef.lastIndexOf(';') + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Takes the carried item with its origin and forgets the tags. @return {@code null} if the hand is empty */
    Loot removeLoot(Entity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        UUID owner = lootOwner(entity);
        String frame = lootFrame(entity);
        Location origin = lootOrigin(entity);
        boolean treat = isTreat(entity);
        ItemStack item = takeHandLoot(entity);
        clearLootTags(pdc);
        return item == null ? null : new Loot(item, owner, frame, origin, treat);
    }

    void markTreat(Entity entity) {
        entity.getPersistentDataContainer().set(lootTreatKey, PersistentDataType.BYTE, (byte) 1);
    }

    boolean isTreat(Entity entity) {
        return entity.getPersistentDataContainer().has(lootTreatKey, PersistentDataType.BYTE);
    }

    /** Clears the hand and the loot copy. @return the carried item, or {@code null} */
    ItemStack takeHandLoot(Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.getEquipment() == null) return null;
        EntityEquipment eq = living.getEquipment();
        ItemStack hand = eq.getItemInMainHand();
        entity.getPersistentDataContainer().remove(lootItemKey);
        if (hand.getType().isAir()) return null;
        eq.setItemInMainHand(null);
        return hand;
    }

    boolean carriesLoot(LivingEntity entity) {
        EntityEquipment eq = entity.getEquipment();
        return eq == null || !eq.getItemInMainHand().getType().isAir();
    }

    void takeLoot(LivingEntity entity, Loot loot) {
        EntityEquipment eq = entity.getEquipment();
        eq.setItemInMainHand(loot.item());
        eq.setItemInMainHandDropChance(2.0f); // above 1 = always dropped, durability untouched
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        clearLootTags(pdc);
        pdc.set(lootItemKey, PersistentDataType.BYTE_ARRAY, loot.item().serializeAsBytes());
        if (loot.owner() != null) pdc.set(lootOwnerKey, PersistentDataType.STRING, loot.owner().toString());
        if (loot.frame() != null) pdc.set(lootFrameKey, PersistentDataType.STRING, loot.frame());
        if (loot.origin() != null) pdc.set(lootOriginKey, PersistentDataType.STRING, locationRef(loot.origin()));
        if (loot.treat()) markTreat(entity);
    }

    /** @return the player the carried loot belongs to, or {@code null} */
    UUID lootOwner(Entity entity) {
        String s = entity.getPersistentDataContainer().get(lootOwnerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** @return {@code "world;x;y;z;frameUuid"} of the item frame the loot came from, or {@code null} */
    String lootFrame(Entity entity) {
        return entity.getPersistentDataContainer().get(lootFrameKey, PersistentDataType.STRING);
    }

    /** @return the spot on the ground the loot was picked up from, or {@code null} */
    Location lootOrigin(Entity entity) {
        String s = entity.getPersistentDataContainer().get(lootOriginKey, PersistentDataType.STRING);
        if (s == null) return null;
        String[] p = s.split(";");
        World world = p.length == 4 ? Bukkit.getWorld(p[0]) : null;
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String locationRef(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ();
    }

    private void clearLootTags(PersistentDataContainer pdc) {
        pdc.remove(lootItemKey);
        pdc.remove(lootOwnerKey);
        pdc.remove(lootFrameKey);
        pdc.remove(lootOriginKey);
        pdc.remove(lootTreatKey);
    }

    static String frameRef(ItemFrame frame) {
        Location l = frame.getLocation();
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ() + ";" + frame.getUniqueId();
    }

    boolean isMischiefProtected(Location at) {
        return plugin.getRegionIntegration() != null && plugin.getRegionIntegration().isMischiefProtected(at);
    }

    private boolean isStealable(ItemStack stack, Location at, Set<Material> blacklist) {
        if (stack == null || stack.getType().isAir()) return false;
        String name = stack.getType().name();
        // Containers carry their contents - losing one would hurt far more than a prank should
        if (name.endsWith("SHULKER_BOX") || name.endsWith("BUNDLE") || blacklist.contains(stack.getType())) return false;
        if (at.getWorld().getDifficulty() == Difficulty.PEACEFUL) return false; // zombies vanish without drops
        if (nearRecentDeath(at)) return false;
        return !isMischiefProtected(at);
    }

    private static Set<Material> readBlacklist(List<String> names) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) set.add(m);
        }
        return set;
    }

    /**
     * Per-mob AI on the entity scheduler: removes decoration items in reach, lets Wichtel
     * carry off one item lying on the ground or sitting in an item frame, and hops around.
     *
     * <p>Items that other plugins mark as not pickable (shop displays) and fixed item frames
     * are left alone.
     */
    private void startEntityStealTask(LivingEntity entity) {
        UUID entityId = entity.getUniqueId();
        double radius = plugin.getMobConfig().getDouble("wichtel.stealRadius", 3.2);
        boolean isWichtel = entity.getScoreboardTags().contains(TAG_WICHTEL);
        boolean stealItems = isWichtel && plugin.getMobConfig().getBoolean("wichtel.steal.items", true);
        boolean stealFrames = isWichtel && plugin.getMobConfig().getBoolean("wichtel.steal.itemFrames", true);
        double stealChance = plugin.getMobConfig().getDouble("wichtel.steal.chance", 0.25);
        Set<Material> blacklist = readBlacklist(plugin.getMobConfig().getStringList("wichtel.steal.blacklist"));
        double tpChance = 0.3;

        WrappedTask task = scheduler.runForEntityTimer(entity, () -> {
            if (!entity.isValid() || entity.isDead()) {
                WrappedTask oldTask = entityStealTasks.remove(entityId);
                if (oldTask != null) oldTask.cancel();
                trackedWichtel.remove(entityId);
                trackedElfen.remove(entityId);
                return;
            }

            for (Entity near : entity.getNearbyEntities(radius, radius, radius)) {
                if (near instanceof Item item) {
                    if (plugin.getDecorationManager().isDecoration(item)) {
                        item.remove();
                    } else if (stealItems && !carriesLoot(entity) && random.nextDouble() < stealChance
                            && item.isValid() && item.canMobPickup() && item.getPickupDelay() < Short.MAX_VALUE
                            && isStealable(item.getItemStack(), item.getLocation(), blacklist)) {
                        UUID owner = item.getThrower();
                        if (owner != null && plugin.getOptOutManager().isOptedOut(owner, OptOutManager.Category.WICHTEL)) continue;
                        takeLoot(entity, new Loot(item.getItemStack(), owner, null, item.getLocation(), false));
                        item.remove();
                    } else {
                        continue;
                    }
                } else if (near instanceof ItemFrame frame) {
                    if (!stealFrames || frame.isFixed() || carriesLoot(entity) || random.nextDouble() >= stealChance
                            || !isStealable(frame.getItem(), frame.getLocation(), blacklist)) {
                        continue;
                    }
                    takeLoot(entity, new Loot(frame.getItem(), null, frameRef(frame), null, false));
                    frame.setItem(null, false);
                } else {
                    continue;
                }
                entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            }

            if (isWichtel) {
                if (entity instanceof Mob mob) pranks.tick(mob);
            } else {
                helpers.tick(entity);
            }
            if (!entity.isValid()) return;

            // Elves carrying something or on their way somewhere do not hop
            if (!isWichtel && (carriesLoot(entity) || helpers.isBusy(entity))) return;
            if (random.nextDouble() < tpChance) {
                int dx = random.nextInt(3) - 1;
                int dz = random.nextInt(3) - 1;
                if (dx == 0 && dz == 0) return;
                Location newLoc = entity.getLocation().add(dx, 0, dz);
                // Only hop into free space with ground below (never into walls or off ledges)
                if (!newLoc.getBlock().isPassable()
                        || !newLoc.clone().add(0, 1, 0).getBlock().isPassable()
                        || newLoc.clone().add(0, -1, 0).getBlock().isPassable()) {
                    return;
                }
                if (scheduler.isFolia()) {
                    entity.teleportAsync(newLoc);
                } else {
                    entity.teleport(newLoc);
                }
            }
        }, () -> {
            // Retired: the mob was removed - drop it from tracking immediately
            entityStealTasks.remove(entityId);
            trackedWichtel.remove(entityId);
            trackedElfen.remove(entityId);
        }, 40L, 40L);

        if (task != null) {
            entityStealTasks.put(entityId, task);
        }
    }
}
