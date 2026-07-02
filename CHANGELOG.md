# Changelog

All notable changes to the ChristmasSeason plugin will be documented in this file.

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
