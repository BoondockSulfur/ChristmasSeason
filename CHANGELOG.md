# Changelog

All notable changes to the ChristmasSeason plugin will be documented in this file.

## [2.4.0] - 2026-09-23

**Reliability and feature release:** major backup and biome-safety fixes, thread-safe biome tools, restart-safe tracking, a full English language pass, and several long-requested features.

**Upgrade Priority:** HIGH - every server that uses the backup system or custom biomes

### ⚠️ Important for server owners
- **Backups:** WAL data is now included correctly in all backups. Before this release, timestamp and emergency backups could silently miss the most recent chunks.
- **Custom biomes:** Terralith and data-pack biomes are now restored correctly instead of coming back as plains.
- **Biome tools:** `/xmas biome compare` and `fix-diff` are now thread-safe on Paper and Folia.

### Highlights
- **Schedule** - the event switches itself on and off by date, including windows that span New Year.
- **Advent calendar** - `/advent` opens one door per player and day, with configurable rewards.
- **Clean restores** - snow and ice are removed reliably, while pre-existing and player-placed snow/ice are kept.
- **Restart-safe** - gift chests, decorations and event mobs are recognised again after a restart or reload.
- **Faster and lighter** - biome snapshots are 16× smaller and the restore no longer causes tick spikes.
- **More control** - multiple snow worlds, exclusion zones, configurable height range, runtime feature toggles, PlaceholderAPI and API events.

### Compatibility
- Paper, Purpur and Folia; Minecraft 1.21.3+ and 26.x. `api-version` is now `1.21.3`, so older servers refuse the plugin with a clear message instead of crashing later.
- Config files stay compatible; all new sections and keys are optional.
- Snapshot databases from older versions are still read; new snapshots use the new format.
- Language files were reworked. Delete `messages_en.yml`/`messages_de.yml` from the plugin folder if you never edited them, otherwise the bundled defaults fill in the missing keys.

---

### Full changelog

#### Added
- **Schedule** (`schedule.*`) - the event activates and deactivates itself by date; windows may span New Year (`12-01` → `01-06`). Only state transitions trigger changes, so a manual `/xmas off` remains respected until the next transition.
- **Advent calendar** - `/advent` opens one door per player and day (`advent.*`): default items, a weighted random pool, per-day overrides, console commands, optional catch-up of missed days. Claims are stored in `data/advent.yml` and reset each year. `AdventClaimEvent` for other plugins.
- **Multiple snow worlds** - `snowWorlds` list next to the legacy `snowWorld`. All managers, cleanup, adoption, and snowstorm logic now support every listed world.
- **Biome exclusion zones** (`biome.exclude.*`) - rectangles, WorldGuard region IDs (`id` or `world:id`) or all GriefPrevention claims are skipped by the biome bubble and by convert-all.
- **Configurable height range** - `biome.changeMinY` / `biome.changeMaxY` replace the fixed 50-200 range. The snow/ice clean-up on restore is limited to the same range plus an 8-block margin, so natural altitude snow above it is left alone.
- **Instant snow** (`biome.instantSnow.*`, off by default) - snow layers on the surface and frozen still water the moment a chunk is converted.
- **`/xmas biome convert-all [radius] [world] confirm`** - budgeted conversion of every generated chunk around spawn, useful for pre-generated maps. `cancel` stops it; progress is shown in `/xmas status`.
- **`/xmas biome info`** - shows current and original biome, snapshot state and exclusion for the spot you stand on.
- **Weighted loot entries** - every loot list accepts `{material, amount: "1-3", weight, name, lore, enchantments, glow}` besides `MATERIAL:amount`. Chest item counts and rare chances are configurable (`gifts.contents.*`).
- **Gift opening** - the first opener is recorded (`data/stats.yml`), `/xmas stats [player]`, optional `gifts.broadcastOnOpen`, sounds and particles on spawn and open (`gifts.effects.*`), `GiftOpenEvent`. `GiftSpawnEvent` is cancellable.
- **Runtime feature toggles** - `/xmas feature <biome|snowstorm|decoration|gifts|wichtel|elves|snowmen|advent> on|off`, persisted to the config.
- **Per-player spawn caps** - `wichtel/elves/snowmen.maxNearPlayer` within `spawning.nearRadius` (0 = off, world caps unchanged).
- **Permissions** - `xmas.advent` (default true), `xmas.bypass.snowmen`. Snowmen also ignore spectators.
- **PlaceholderAPI** expansion `%xmas_...%` (active, snowstorm, days_until_start, days_left, gifts_opened, gifts_opened_total, advent_claimed, advent_today, tracked_mobs, tracked_gifts).
- **API events** in `de.boondocksulfur.christmas.api`: `XmasStateChangeEvent`, `GiftSpawnEvent`, `GiftOpenEvent`, `AdventClaimEvent`.
- **Announcements** on activation and deactivation (`announcements.*`); richer `/xmas status` (worlds, tracked objects, snapshot chunks, restore/conversion progress, schedule, gifts opened).
- Snowstorm mode `none` now really leaves the weather alone (it was documented but not implemented).
- `elves.lifetimeSeconds` / `elves.world` (default to the wichtel values).
- Custom or partial language files fall back to the bundled English texts instead of `[Missing: key]`.

#### Fixed
- **CRITICAL: Backups were incomplete.** The snapshot database runs in WAL mode; timestamp backups (`/xmas off`), emergency backups (server stop) and SAFE backups taken while the event was running copied only the main file and silently lost every chunk still in the write-ahead log. All copies now force a WAL checkpoint first, and the emergency backup is written after the database has been closed.
- **CRITICAL: Custom biomes restored as PLAINS.** Snapshots stored biome keys without their namespace, so Terralith and data-pack biomes could not be resolved on restore. New snapshots store the full key; old snapshots are still read as `minecraft:` biomes.
- **CRITICAL: `/xmas biome compare` and `fix-diff` accessed the world from an async thread** (`IllegalStateException: Asynchronous chunk load!` on Paper, region violations on Folia). The backup database is still read asynchronously, but every chunk comparison and restore now runs on the chunk's owning thread with `biome.restore.perTick` chunks in flight.
- **`/xmas reload` (and a second `/xmas on`) orphaned gift chests permanently** - lifetime tasks were cancelled and the tracker cleared. Stopping a manager no longer drops its tracking.
- **Event objects were lost after a server restart** - mobs, decorations and gift chests had no cap, no lifetime and were not removed by `/xmas off`. They are now recognised by their tags and markers and adopted on startup and on chunk load; `/xmas off` additionally sweeps all loaded chunks.
- **Emergency backups piled up** - one full database copy per normal shutdown, never rotated. Only the newest three are kept.
- **`ChunkLoadEvent` bypassed the per-tick budget** - every loaded chunk was snapshotted and converted synchronously, including pre-generated chunks far from any player. Chunks are now only queued when a player is within the bubble radius (Folia: handled by the per-player timer).
- **Wichtel and elves deleted every dropped item in reach**, including player death drops. They now only collect the plugin's decoration items (`wichtel.stealOnlyDecorations: true`; `false` allows other items but never player drops). Random hops no longer teleport mobs into blocks or off ledges.
- **Snow and ice were left behind after `/xmas off`**, for several reasons that are all fixed:
  - Region tasks still in flight after `/xmas off` could re-convert chunks the restore had just reset (Folia). Chunk processing now stops as soon as the event is off or a restore runs.
  - The surface check used a heightmap that ignores single snow layers. The clean-up now uses the `WORLD_SURFACE` heightmap and also checks the block below for ice.
  - Minecraft blurs biome borders by a few blocks, so snow also fell on the edges of chunks that were never converted and therefore never cleaned. The restore now cleans a four-block strip of every untouched neighbouring chunk.
  - Grass, podzol and mycelium under removed snow layers kept their white `snowy` top until the next block update. The state is reset explicitly now.
- **Gaps behind fast-moving players on Folia** - chunks that did not fit into the per-run budget were dropped once the player moved on, and chunks that were not loaded yet were given up after three attempts. Every chunk seen in the bubble radius now stays in a per-player queue (nearest first) until it is converted.
- **`/xmas storm off` did not end an auto-mode storm** - it now pauses the auto phases until `/xmas storm on` or the next reload, and resends clear weather even if the server flag was already off.
- The spawn helper no longer falls back to the player's own position; a candidate more than 24 blocks above the player is rejected instead of a fixed `Y > 100` rule that worked badly on high worlds.
- Gift chests are never placed next to another chest (no accidental double chests with player storage).
- Region plugins are now declared as `softdepend`, so the WorldGuard/GriefPrevention hook is available on the first start.
- Database statistics report MB and KB with matching units.

#### Changed
- Snapshots now store biomes per 4×4 cell, reducing biome snapshot data by 16× while remaining backwards-compatible. Each snapshot also records which columns already had snow or ice on the surface; those columns are kept on restore.
- Snow layers and ice placed by players while the event is active are recorded and kept on restore, also inside the cleaned border strips.
- The snow/ice clean-up on restore checks the surface of every column instead of every block between Y 50 and 200, which makes restores a lot cheaper. `fix-diff` cleans snow and ice as well.
- Biome writes and samples work per 4×4×4 cell (16× fewer `setBiome` calls per chunk).
- Chunks set with `/xmas biome set` are protected from the automatic bubble until the next stop or restore.
- Update checker: `updateChecker.enabled` / `notifyOps` config keys, only Modrinth *release* versions count, notifications with clickable Modrinth and GitHub links.
- New config keys: `biome.restore.removeSnowLayers`, `biome.restore.removeIce`, `wichtel.stealOnlyDecorations`, `snowmen.lifetimeSeconds` (default 600, 0 = never).
- Default `language` is now `en`.
- All source comments, Javadoc, debug output and hardcoded messages are English; every player and console message goes through the language files (both files reworked, unused keys removed).

#### Removed
- Empty `GiftOpenListener` placeholder (replaced by the real gift-open tracking); `OrphanedMobCleanupListener` replaced by `TrackedObjectListener` (adopt or remove).

## [2.3.0] - 2026-07-03

**Major Update:** Minecraft 26.x support, full Adventure API migration, and a deep thread-safety/reliability overhaul of the entire 2.2.0 feature set.

**Upgrade Priority:** HIGH - contains critical Folia fixes and data-safety hardening

### Added
- **Minecraft 26.x support** - one JAR covers 1.21.3-1.21.11 (Java 21) AND the year-based 26.x versions (Java 25); compiled against Paper API `26.1.2.build.72-stable`, `api-version: "1.21"` remains the minimum
- **Gift chest protection** - hoppers and explosions can no longer empty/destroy gift chests; gift chests carry a PersistentDataContainer marker so cleanup can never delete player chests (configurable via `gifts.protectChests`)
- **Orphaned mob cleanup** - event mobs whose chunks were unloaded during `/xmas off` are removed automatically when their chunks load while the event is inactive
- **Config validation on startup/reload** - warns about unknown materials in loot tables and an invalid `biome.target`
- Tab completion for `/xmas` now includes biome names from the registry

### Changed
- **Full Adventure API migration** - Component-based entity/item/chest names and broadcasts, `RegistryAccess` instead of `Registry.BIOME`, zero deprecated API usage in the build
- **Reliable `/xmas off` restore** - completion is tracked per chunk (atomic counters); failed chunks keep their snapshots and are retried on the next run; accurate final statistics; reentrancy guard against double `/xmas off`; `/xmas on` during a running restore can no longer leak the database
- **Folia hardening of the 2.2.0 feature line** - all entity-scheduler tasks use retired-callbacks (killed event mobs no longer clog the spawn caps), one shared FoliaLib instance, spawn limits re-checked inside region tasks
- JAR size reduced from ~13.6 MB to ~5 MB (excluded unused SQLite natives)
- `/xmas reload` restarts ALL managers, so new config values apply immediately
- Language files normalized to `&` color codes; literal `&` ("Wichtel & Elfen") no longer mangled; console logs free of raw color codes
- Removed ~200 lines of dead code (legacy 2D snapshot format, disabled seed-restore machinery)

### Compatibility
- **Paper/Purpur/Folia only** - plain Spigot is no longer supported (Adventure API required)
- **Minimum is Minecraft 1.21.3** - on 1.21.1/1.21.2 the plugin fails with `IncompatibleClassChangeError` (Biome changed from enum to interface in 1.21.3)

### Verified
- Live function tests on Paper 26.2 (Java 25), Paper 1.21.11 and Folia 1.21.11 with bot-driven end-to-end runs: biome bubble, 180-208-chunk restores without errors, backup commands, kill-wave spawn-cap test - zero exceptions

## [2.2.0] - 2026-04-25

**Major Update:** Region protection, backup system, update checker, bug fixes, data safety, tab completion, and bStats.

**Upgrade Priority:** HIGH - Critical thread-safety and data integrity fixes

### Added
- **WorldGuard & GriefPrevention** - No spawns in protected regions/claims (soft dependency, configurable)
- **Automatic Backup System** - SAFE/Timestamp/Emergency backups in `world/christmas_backups/`
- **Update Checker** - Modrinth + GitHub fallback, OP notifications on join
- **Biome Compare & Fix** - `/xmas biome compare` and `/xmas biome fix-diff` for recovery
- **Full Tab Completion** - Context-aware suggestions for all commands
- **bStats Metrics** (Plugin ID: 30930)
- **Startup Safety Checks** - DB integrity check, crash detection, missing-DB warnings
- **Data Loss Protection** - `clearsnap` blocked when active, backup failure warnings, smart rotation
- Entity spawn fixes: No more spawns on roofs, in trees, or in water

### Fixed (17 Bugs)
- **CRITICAL:** Backup system non-functional (wrong DB filename)
- **CRITICAL:** Folia crash on start/join (`Bukkit.getScheduler()` → `FoliaSchedulerHelper`)
- **HIGH:** 6 thread-safety issues (restore counters, restore guard, HashSets, SQLite, LanguageManager)
- **HIGH:** Restore permanently blocked after empty snapshot or null DB
- **MEDIUM:** Cave biomes overwritten during seed-restore (Y=64 only → per Y-level)
- **MEDIUM:** NPE in `stopFeatures()` on failed startup
- **MEDIUM:** SnowstormManager orphaned tasks after `stop()`
- **LOW:** DB header EOF check, version parse fix, SnowmanDamageListener cleanup

### Changed
- `softdepend: [WorldGuard, GriefPrevention]` in plugin.yml
- New `regionIntegration` config section
- Intelligent backup rotation (largest backup never deleted)

## [2.1.0] - 2025-12-25

**Minor Update:** Critical Folia compatibility fixes and complete internationalization overhaul.

This release fixes multiple critical thread-safety violations on Folia servers and completes the internationalization system. The biome restore mechanism has been completely rewritten to work correctly with Folia's regionalized threading model.

**Upgrade Priority:** HIGH - Recommended for all Folia servers experiencing crashes during `/xmas off`

### Fixed
- **CRITICAL: Folia performance issue - TPS drops to 16 during biome changes**
  - Fixed `ensureAroundPlayerFolia()` scheduling all chunks around player simultaneously
  - **Root cause**: With radius=2, all 25 chunks were scheduled in parallel every tick (no budget limit)
  - **Result**: Massive TPS spike when processing 25 chunks × 9,728 biomes each = 240k+ biome changes
  - **Solution**: Added `perTickBudget` respect - only schedules max 12 chunks per player tick
  - Chunks now distributed across multiple ticks (e.g., 25 chunks over 3 ticks instead of instant)
  - Performance now matches Paper/Spigot budget-based system

- **Client-side biome caching after `/xmas off`**
  - Fixed default value for `biome.playerBubble.refreshClient` from `false` to `true`
  - Clients now correctly see restored biomes without needing to move away and return
  - Chunk refresh packets now sent by default (can be disabled in config for performance)

- **CRITICAL: Biome snapshot bug in `/xmas biome set` command**
  - Fixed chunks not being restored correctly after manual biome changes
  - **Root cause**: `setBiomeAroundPlayer()` added chunks to `knownSnapshotChunks` cache BEFORE calling `snapshotIfAbsent()`
  - **Result**: No snapshot was created for manually changed chunks → wrong biome restored on `/xmas off`
  - **Solution**: Removed premature cache addition, let `snapshotIfAbsent()` manage the cache correctly
  - Fixes issue where chunks changed with `/xmas biome set` were not restored to original biomes

- **CRITICAL: Chunks not loaded during `/xmas on` were never processed**
  - Implemented automatic retry mechanism for chunks that fail to load
  - Chunks are now retried up to 3 times before being skipped
  - Prevents "missing chunks" issue where distant/unloaded chunks stay unchanged
  - Added `chunkRetryCount` tracking map with automatic cleanup to prevent memory leaks
  - Debug logging for chunks that are skipped after max retries

- **CRITICAL: Complete internationalization of all console logs**
  - Fixed English language module not being loaded correctly from JAR
  - Replaced ALL 68 hardcoded German strings with LanguageManager calls:
    - **BiomeSnapshotDatabase.java**: 34 strings replaced
    - **BiomeSnowManager.java**: 33 strings replaced
    - **SnowmanManager.java**: 1 string replaced
  - All console log messages now properly respect the `language` setting in config.yml
  - Verified: Zero hardcoded German strings remaining in user-facing logs

- **CRITICAL: Folia thread safety violations in biome restore**
  - Fixed `IllegalStateException` crash during `/xmas off` biome restoration on Folia servers
  - **Root cause**: Batch processing accessed chunks from different regions in single Location Scheduler task
  - **Error**: "Thread failed main thread check: Async chunk retrieval"
  - **Solution**: Each chunk now processed on its own Location Scheduler task (prevents cross-region access)
  - Removed batch optimization that violated Folia's region threading model
  - Improved chunk loading: Now uses blocking load (`generate=true`) for higher success rate
  - Added double-check verification to ensure chunks are actually loaded before restore
  - Fixed error counting to accurately track failed chunk restores

- **CRITICAL: Folia thread safety violations in cleanup methods**
  - Fixed `IllegalStateException` crash when running `/xmas off` on Folia servers
  - **Root cause**: Using `world.getEntitiesByClass()` and direct `entity.remove()` calls access entities from other regions, violating Folia's thread ownership model
  - **DecorationManager.java**: Replaced unsafe entity iteration with UUID tracking
    - Added `trackedDecorations` ConcurrentHashMap for thread-safe entity tracking
    - Track decorations when spawned, untrack when lifetime expires
    - cleanup() now uses `Bukkit.getEntity(uuid)` + `scheduler.runForEntity()` pattern
  - **SnowmanManager.java**: Replaced unsafe entity iteration and counting
    - Added `trackedSnowmen` ConcurrentHashMap for thread-safe entity tracking
    - Replaced `getEntitiesByClass()` loop with `trackedSnowmen.size()` for counting
    - cleanup() now schedules removal on each entity's owning region thread
    - Properly cancels attack tasks before entity removal
  - **WichtelManager.java**: Fixed direct entity.remove() calls in cleanup()
    - cleanup() now schedules Wichtel/Elfen removal on each entity's owning region thread
    - Properly cancels steal tasks before entity removal
    - Uses same UUID tracking + entity scheduler pattern
  - All entity removals now scheduled on correct region threads (Folia-safe)
  - **GiftManager**: Already Folia-safe (uses Location Scheduler for block operations)

### Changed
- **Enhanced Language Files**
  - Added 70+ new translation keys to `messages_de.yml`:
    - `log.biome.*` - 35+ keys for biome restore operations, errors, and status
    - `log.database.*` - 35+ keys for database operations, compression, errors
    - `log.cleanup.snowman-world-not-found` - Snowman cleanup error
  - Added matching English translations to `messages_en.yml`
  - All formatting codes (§6, §7, §a, §c, §f, etc.) preserved in translations

- **Improved Translation Coverage**
  - Database operations (open, close, clear, compression, decompression)
  - Biome restore operations (start, progress, completion, errors)
  - Error messages and warnings with detailed context
  - Statistics and progress information
  - Chunk loading/restoration errors with coordinates

- **Improved Language Loading Diagnostics**
  - `LanguageManager` now logs:
    - Current language being loaded
    - Absolute file path of language file
    - File existence status and size in bytes
    - Source of language data (disk vs JAR)
    - Number of keys loaded successfully
  - Better error messages when resources are missing from JAR
  - Added resource enumeration in severe error cases for debugging

- **Build Configuration**
  - Added explicit Maven resources configuration in `pom.xml`
  - Ensures all `.yml` and `.yaml` files are properly packaged in JAR
  - Language files (`messages_de.yml`, `messages_en.yml`) now correctly included

### Technical
- All log messages now use `plugin.getLanguageManager().getMessage()` or `.get()`
- Proper placeholder support with {0}, {1}, {2} format for dynamic values
- Technical/internal logs (resource loading, language manager) intentionally kept in English
- Updated `pom.xml` with explicit `<resources>` section
- Added better exception handling in `ChristmasSeason.saveResourceIfAbsent()`
- Enhanced logging shows resource extraction status with file sizes
- Maintains backward compatibility with existing configurations

## [2.0.0] - 2025-12-19

### Added
- **🎄 Multi-Platform Support** - Plugin now runs on Spigot, Paper, Purpur AND Folia!
  - Automatic platform detection at runtime
  - Single JAR works on all server types (no separate builds needed)
  - Zero configuration required - works out of the box
  - **Smart Scheduler Strategy:**
    - **Paper/Spigot/Purpur:** Global timer (preserves v1.4.1 architecture & performance)
    - **Folia:** Player-based Entity Scheduler (regionalized threading)

- **FoliaLib Integration**
  - Added FoliaLib 0.4.3 dependency (shaded & relocated)
  - Seamless multi-platform scheduler abstraction
  - Automatic fallback to Bukkit scheduler on non-Folia servers

- **Folia-Specific Optimizations**
  - Region-based scheduling for biome processing (uses Global/Region Scheduler)
  - Entity-based scheduling for mobs (Wichtel, Elfen, Schneemänner)
  - Location-based scheduling for blocks (Gifts, Decorations)
  - Full support for Folia's regionalized multithreading

- **Chunk Queue System** (NEW!)
  - Budget-based chunk processing prevents TPS spikes
  - Config option: `perTickBudget` (default: 12 chunks/tick)
  - Prevents lag when players move quickly into new areas
  - Queue distributes chunk updates across multiple ticks

- **Manual Biome Correction Command**
  - `/xmas biome set <biome> [radius]` - Manually fix biome issues
  - Only works when ChristmasSeason is active (prevents permanent changes)
  - Includes automatic snapshot creation
  - Protection against global timer conflicts

### Changed
- **Complete Scheduler Migration**
  - All managers migrated from Bukkit scheduler to FoliaLib
  - `BiomeSnowManager`: Multi-platform scheduler strategy (Global on Paper, Entity on Folia)
  - `DecorationManager`: Entity scheduler for item lifetimes
  - `GiftManager`: Location scheduler for chest placement/removal
  - `SnowstormManager`: Global scheduler for weather control
  - `WichtelManager`: Entity scheduler for mob management
  - `SnowmanManager`: Entity scheduler for snowman behavior
  - `XmasCommand`: Global scheduler for delayed operations

- **New Scheduler Helper**
  - Created `FoliaSchedulerHelper` utility class
  - Platform-agnostic API for scheduling tasks
  - Automatic platform detection (isFolia() method)
  - Supports all scheduler types: Global, Region, Entity, Location, Async

- **Task Types Updated**
  - Replaced `BukkitTask` with `WrappedTask` (FoliaLib wrapper)
  - Replaced `BukkitRunnable` with lambda expressions
  - All scheduler calls now go through FoliaSchedulerHelper

- **Performance Improvements**
  - `perTickBudget` increased from 6 → 12 (2x faster biome updates)
  - Cache management optimized (processedChunks, knownSnapshotChunks)
  - Chunk queue prevents processing duplicates

### Fixed
- **CRITICAL: Multi-Platform Teleport Incompatibility**
  - Fixed `NoSuchMethodError` on Spigot (teleportAsync doesn't exist)
  - Fixed `UnsupportedOperationException` on Folia (must use teleportAsync)
  - Platform-dependent teleport: `teleportAsync()` on Folia, `teleport()` on Spigot/Paper/Purpur
  - Wichtel can now teleport correctly on all platforms

- **CRITICAL: Race Condition in `/xmas biome set`**
  - Fixed global timer overwriting manual biome changes
  - Chunks are now marked as processed BEFORE async tasks start
  - Manual changes are now persistent until `/xmas off`

- **CRITICAL: Chunk Stripes Not Restored**
  - Fixed chunks being deleted from database even when restore failed
  - Chunks now only deleted after successful restoration
  - Failed chunks remain in DB for retry on next `/xmas off`
  - Added logging for failed chunk loads

- **Database Management**
  - Improved cache clearing before/after restore operations
  - Better error handling for chunk loading failures
  - Success-only database cleanup prevents data loss

### Technical
- **Dependencies**
  - Updated from `spigot-api` to `paper-api` (1.21.3-R0.1-SNAPSHOT)
  - Added `FoliaLib` 0.4.3 (com.tcoded:FoliaLib)
  - Added tcoded-releases repository (https://repo.tcoded.com/releases)
  - FoliaLib is shaded and relocated to avoid conflicts

- **Build Configuration**
  - Updated Maven Shade plugin to include FoliaLib
  - Relocation: `com.tcoded.folialib` → `de.boondocksulfur.christmas.libs.folialib`
  - SQLite JDBC driver still bundled (3.45.0.0)

- **Version Bump**
  - Major version bump to 2.0.0 (breaking changes in internal APIs)
  - Updated version in pom.xml, plugin.yml, and READMEs
  - Platform description updated to "Spigot/Paper/Purpur/Folia"
  - Added `folia-supported: true` flag to plugin.yml (required for Folia)

### Migration Notes
- **No action required for server admins** - plugin auto-detects platform
- **For developers**: Internal scheduler API changed (use FoliaSchedulerHelper)
- **Compatible with existing configs** - no configuration changes needed
- **Works on Folia 1.20+** and all Paper/Spigot versions supporting 1.21+

---

## [1.4.1] - 2025-12-12

### Fixed
- **CRITICAL: Fixed database corruption during biome snapshot save/load**
  - Fixed stream misalignment causing garbled biome names (e.g., "MEADOWMEADOWMEADOW", "SNOWY_SLOPESFROZEN_PEAKS")
  - Fixed MEADOW biomes being incorrectly restored as PLAINS
  - Root cause: `InputStream.read()` can return fewer bytes than requested (partial reads)
  - Solution: Read in loop until all bytes are received to maintain stream alignment
  - Added validation for biome name length (max 50 bytes) to detect corruption early

- **Fixed "Unerwartetes Ende in 3D Biome-Daten!" errors**
  - Stream now properly handles partial reads without losing alignment
  - One corrupted biome no longer corrupts all following biomes (no more domino effect)

### Changed
- Improved error messages for database corruption
  - Clear instructions: `/xmas biome clearsnap` to delete corrupted database
  - Detection of stream misalignment with detailed logging

### Migration Notes
- **Old corrupted databases cannot be repaired** - they must be deleted and recreated
- Steps to migrate:
  1. `/xmas biome clearsnap` - Delete old corrupted database
  2. Restart server
  3. `/xmas on` - Create fresh snapshots with fixed code
  4. `/xmas off` - Test restore functionality
  5. Verify biomes (e.g., MEADOW) are now correctly restored

## [1.4.0] - 2025-12-12

### Fixed
- **CRITICAL: Fixed biome restoration corruption** - Biomes are now correctly restored after `/xmas off`
  - Changed biome serialization from ordinal-based (unstable) to name-based (stable)
  - Fixes issue where biomes were completely scrambled (e.g., JUNGLE became BIRCH_FOREST, OCEAN became FROZEN_PEAKS)
  - Old snapshots using ordinal format are still supported for backwards compatibility
  - **Database format updated:** New snapshots use magic byte `0x3E` (name-based), old format `0x3D` (ordinal-based) still readable

- **Fixed Y-level misalignment in biome restoration**
  - Restore now uses the correct `yStep` value from the snapshot instead of config
  - Prevents biomes from being restored at wrong vertical positions

### Added
- Extensive debug logging for biome snapshot/restore operations
  - Shows which biomes are stored in snapshots
  - Shows which biomes are restored from snapshots
  - Helps diagnose biome-related issues

### Technical Details
- Biome storage format changed from `ordinal()` (2 bytes) to `name()` (length-prefixed UTF-8 string)
- Rationale: `Biome.values()` order is not guaranteed to be stable across server restarts/reloads
- New format ensures biome names like "JUNGLE", "OCEAN" are stored exactly as they are
- Database automatically migrates old snapshots when loading

## [1.3.0] - Previous Release

### Added
- SQLite-based biome snapshot system
- 3D biome restoration (multiple Y-levels)
- Biome blacklist (prevents modifying Nether/End/Cave biomes)
- Y-level limits (only modifies surface biomes between Y=50 and Y=200)
- Snow block protection (SNOW_BLOCK preserved, only SNOW layers removed)
- Performance optimizations (chunk processing cache, budgeted restore)

### Changed
- Replaced YAML snapshot system with SQLite database
- Removed 2000 chunk limit
- Compressed storage (~5-10 MB instead of 156 MB for 10k chunks)
