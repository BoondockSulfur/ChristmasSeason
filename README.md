# ChristmasSeason v2.4 🎄

**Transform your Minecraft world into a winter wonderland!**

A comprehensive Christmas plugin featuring biome snowfall, snowstorms, NPCs, gifts, and much more. Now with **Multi-Platform Support** for Paper, Purpur, **and Folia** (MC 1.21.3+ and 26.x)!

---

## 🌟 Features

### ❄️ Dynamic Biome System
- **Player-Bubble:** Snow biomes appear in a configurable radius around players
- **3D-Biome Changes:** Works with 1.18+ Multi-Y-Level Biomes
- **Automatic Restore:** Original biomes are saved in SQLite database
- **Smart Caching:** Performance-optimized with budget system (prevents TPS spikes)
- **Blacklist:** Nether/End/Cave biomes remain unchanged

### 🌨️ Snowstorms
- **Auto Mode:** Alternates between snowfall and sunshine
- **Manual Mode:** Permanent snowfall
- **None Mode:** Biome changes only, no weather

### 🎁 Interactive Elements
- **Gifts:** Randomly spawning chests with loot (common/extra/rare)
- **Decorations:** Glowing items spawn around players
- **Wichtel:** Mischievous baby zombies that collect decoration items and hop around
- **Elves:** Friendly allays that do the same
- **Snowmen:** Aggressive snow golems that throw snowballs

### 📅 Season & Calendar
- **Schedule:** activates and deactivates the event automatically by date (`schedule.*`, year-spanning windows like 12-01 → 01-06)
- **Advent calendar:** `/advent` opens one door per day with configurable rewards, per-day overrides and console commands
- **Multiple worlds:** `snowWorlds` list in addition to `snowWorld`
- **Exclusion zones:** rectangles, WorldGuard region IDs or all GriefPrevention claims stay free of snow biomes
- **Instant snow:** optional snow layers and frozen water the moment a chunk is converted
- **Whole-world conversion:** `/xmas biome convert-all` for maps and servers that want everything white
- **Runtime toggles:** `/xmas feature <name> on|off` without editing the config
- **Gift statistics:** first opener is tracked, `/xmas stats`, PlaceholderAPI placeholders, API events for other plugins

### 🛡️ Protection & Safety
- **Region Protection:** No spawns in WorldGuard regions or GriefPrevention claims (soft dependency)
- **Backup System:** Automatic SAFE/timestamp/emergency backups of the biome database (WAL-safe, rotated)
- **Startup Safety Checks:** DB integrity check, crash detection via emergency backups
- **Update Checker:** `/xmas update check` + clickable admin notifications (Modrinth/GitHub), can be disabled
- **Restart-safe tracking:** gift chests, decorations and event mobs are recognised again after a restart or `/xmas reload` (caps, lifetimes and `/xmas off` cleanup keep working)
- **Custom biome support:** snapshots store fully namespaced biome keys, so Terralith/data-pack biomes restore correctly

### 🔧 Performance Features
- **SQLite Snapshots:** Compressed biome storage (~5-10 MB instead of 156 MB)
- **Unlimited Chunks:** No more 2000-chunk limit
- **Budget System:** Max chunks per tick configurable (`perTickBudget`)
- **Multi-Threading Ready:** Folia support with regionalized threading

---

## 🚀 Multi-Platform Support

**One JAR works on all platforms!**

| Platform | Status | Scheduler Type | Performance |
|----------|--------|----------------|-------------|
| **Paper** | ✅ Tested (1.21.11, 26.2) | Global Timer | 18-20 TPS |
| **Folia** | ✅ Tested (1.21.11) | Player-based Entity Scheduler | Optimal for 50+ players |
| **Purpur** | ✅ Compatible | Global Timer | 18-20 TPS |

**Automatic Detection:** The plugin detects the platform on startup and chooses the optimal strategy!

> **Note:** Since v2.3.0 the plugin uses Paper's Adventure API - plain Spigot is no longer supported. Use Paper or a Paper fork.

---

## 📦 Installation

1. **Download:** Get `ChristmasSeason-2.4.0.jar`
2. **Installation:** Copy the JAR to the `plugins/` folder
3. **Server Start:** Start your server (Paper/Purpur/Folia)
4. **Configuration:** Adjust `config.yml` (optional)
5. **Activation:** `/xmas on` - Done! 🎄

**Requirements:**
- Minecraft 1.21.3+ or 26.x (`api-version: 1.21.3` - older servers refuse to load the plugin instead of crashing later)
- Java 21+ (Minecraft 26.x servers require Java 25+)
- Paper/Purpur/Folia server (no plain Spigot - Adventure API required)

---

## 🎮 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/xmas on` | Activates ChristmasSeason | `xmas.admin` |
| `/xmas off` | Deactivates and restores biomes | `xmas.admin` |
| `/xmas status` | Shows status (active/inactive) | `xmas.admin` |
| `/xmas reload` | Reloads configuration | `xmas.admin` |
| `/xmas biome set <biome> [radius]` | Manually sets biomes (only when active) | `xmas.admin` |
| `/xmas biome clearsnap` | Deletes biome snapshot database (guarded) | `xmas.admin` |
| `/xmas biome info` | Why is this spot snowy? Current vs. original biome, snapshot, exclusion | `xmas.admin` |
| `/xmas biome compare <backup>` | Compares current biomes with a backup | `xmas.admin` |
| `/xmas biome fix-diff <backup> confirm` | Restores differing chunks from a backup | `xmas.admin` |
| `/xmas backup <list\|restore\|create\|clear>` | Manages biome database backups | `xmas.admin` |
| `/xmas update check` | Checks Modrinth/GitHub for updates | `xmas.admin` |
| `/xmas biome convert-all [radius] [world] confirm` | Converts every generated chunk around spawn (budgeted) | `xmas.admin` |
| `/xmas feature <name> <on\|off>` | Toggles biome/snowstorm/decoration/gifts/wichtel/elves/snowmen/advent at runtime | `xmas.admin` |
| `/xmas stats [player]` | Gift statistics (top 10 + player) | `xmas.admin` |
| `/advent [status\|<day>]` | Opens today's advent door / shows the calendar | `xmas.advent` (default: everyone) |

**Permissions:** `xmas.admin` (all admin commands), `xmas.advent` (calendar, default true), `xmas.bypass.snowmen` (never targeted by snowmen).

**PlaceholderAPI** (optional): `%xmas_active%`, `%xmas_snowstorm%`, `%xmas_days_until_start%`, `%xmas_days_left%`, `%xmas_gifts_opened%`, `%xmas_gifts_opened_total%`, `%xmas_advent_claimed%`, `%xmas_advent_today%`, `%xmas_tracked_mobs%`, `%xmas_tracked_gifts%`.

**API events** (`de.boondocksulfur.christmas.api`): `XmasStateChangeEvent`, `GiftSpawnEvent` (cancellable), `GiftOpenEvent`, `AdventClaimEvent` (cancellable).

**Examples:**
```
/xmas on                          # Starts winter wonderland
/xmas biome set snowy_plains 2    # Changes 5x5 chunks to snowy plains
/xmas off                         # Restores everything back
```

---

## ⚙️ Configuration

### Default Settings (recommended):

```yaml
active: false
snowWorld: "world"
snowWorlds: []            # additional snow worlds
language: "en"  # en or de

schedule:
  enabled: false
  start: "12-01"          # MM-dd, may span New Year
  end: "01-06"
  timezone: ""            # e.g. Europe/Berlin (also used by the advent calendar)

biome:
  enabled: true
  target: "SNOWY_PLAINS"
  changeMinY: 50            # Y range of surface biomes that are changed/restored;
  changeMaxY: 200           # snow/ice clean-up on restore covers this range (+8 blocks) only
  enableSnapshot: true      # Important for restore!

  playerBubble:
    enabled: true
    radiusChunks: 2         # 5x5 chunks around player
    refreshClient: true     # Immediate client updates
    tickIntervalTicks: 40   # Every 2 seconds
    perTickBudget: 12       # 12 chunks per tick (fast!)

  exclude:
    areas: []               # - {world: world, x1: -100, z1: -100, x2: 100, z2: 100}
    worldGuardRegions: []   # - spawn   or   - world:spawn
    griefPreventionClaims: false

  instantSnow:
    enabled: false          # snow layers + frozen water right when a chunk is converted
    coverage: 0.8

  restore:
    perTick: 4              # 4 chunks per tick during /xmas off (also concurrency of compare/fix-diff)
    removeSnowLayers: true  # remove snow layers on restore where the original biome is not snowy
    removeIce: true         # replace plain ice with water where the original biome is not icy

snowstorm:
  enabled: true
  mode: auto                # auto = phases, manual = always on, none = weather untouched
  auto:
    onSeconds: 150
    offSeconds: 45

decoration:
  enabled: true
  intervalSeconds: 35
  spawnChance: 0.35
  lifetimeSeconds: 180
  glow: true

gifts:
  enabled: true
  globalIntervalSeconds: 160
  chancePerInterval: 0.35
  lifetimeSeconds: 300
  broadcastOnSpawn: true
  broadcastOnOpen: false
  contents:
    commonItems: "4-7"
    extraItems: "1-3"
    rareChance: 0.6
  lootTables:               # "MATERIAL:amount" or {material, amount: "1-3", weight, name, lore, enchantments, glow}
    rare:
      - material: DIAMOND_SWORD
        weight: 1
        name: "&bIcicle"
        enchantments: {sharpness: 2}

wichtel:
  enabled: true
  spawnIntervalSeconds: 45
  maxPerWorld: 6
  maxNearPlayer: 0          # per-player cap within spawning.nearRadius (0 = off)
  stealOnlyDecorations: true  # false = also collect other dropped items (never player drops)

elves:
  enabled: true
  spawnIntervalSeconds: 60
  maxPerWorld: 4

snowmen:
  enabled: true
  spawnIntervalSeconds: 45
  maxPerWorld: 6
  lifetimeSeconds: 600      # 0 = stay until /xmas off
  attackChance: 0.15

advent:
  enabled: true
  month: 12
  firstDay: 1
  lastDay: 24
  allowCatchUp: false
  default:
    items: [COOKIE:4]       # given every day
    randomPool:             # one weighted pick per day
      - {material: DIAMOND, weight: 1}
      - {material: EMERALD, amount: "2-4", weight: 3}
  days:
    "24":
      items: [{material: ENCHANTED_GOLDEN_APPLE, name: "&6Christmas Apple"}]

updateChecker:
  enabled: true
  notifyOps: true
```

> **Restore and player-placed blocks:** on `/xmas off` snow layers and plain ice on the surface of
> the restored area are removed wherever the original biome is not naturally snowy/icy. Columns that
> already had snow or ice before the event, and snow/ice placed by players while the event was
> active, are kept. Snow blocks, packed ice and blue ice are never touched. `removeSnowLayers` /
> `removeIce` switch the clean-up off entirely.

> **Custom biomes:** `biome.target` accepts namespaced keys such as `terralith:alpine_grove`.
> Snapshots taken with v2.4.0+ store the full key; older snapshots (v2.3.0 and before) are read
> as vanilla `minecraft:` biomes.

---

## 🔧 Performance Tuning

### For Paper/Purpur:

**Standard (recommended):**
- `perTickBudget: 12` - Fast updates without lag
- `tickIntervalTicks: 40` - Every 2 seconds

**Weak Servers:**
- `perTickBudget: 6` - Safer with TPS issues
- `tickIntervalTicks: 60` - Every 3 seconds

**Strong Servers:**
- `perTickBudget: 25` - Instant updates
- `tickIntervalTicks: 20` - Every second

### For Folia:

**Recommended (parallel threads!):**
- `perTickBudget: 25` - All chunks instantly
- `radiusChunks: 3` - Larger radius (7x7)
- `tickIntervalTicks: 20` - Very frequent updates

**Why?** Folia uses regionalized threads - each player gets their own timer!

---

## 📊 Performance Benchmarks

**Tested on Paper (1422 chunks):**
- **Biome Restore:** 17.8 seconds (99.86% success rate)
- **TPS:** Stable 18-20 TPS during `/xmas on`
- **Chunk Processing:** 12 chunks/tick = ~2 ticks (0.1s) for player bubble

**Folia Advantages:**
- Parallel chunk processing across regions
- No main thread blocking
- Better for 50+ players

---

## 🐛 Troubleshooting

### Problem: `NoSuchMethodError: teleportAsync` (Spigot)
**Cause:** Old plugin version before v2.0.0
**Solution:** Update to ChristmasSeason v2.1.0+ (Multi-Platform Support)

### Problem: `UnsupportedOperationException: Must use teleportAsync` (Folia)
**Cause:** Old plugin version before v2.0.0
**Solution:** Update to ChristmasSeason v2.1.0+ (Multi-Platform Support)

### Problem: `IllegalStateException` crashes during `/xmas off` on Folia
**Cause:** Thread safety violations in old versions
**Solution:** Update to ChristmasSeason v2.1.0+ (Critical Folia fixes included)

### Problem: TPS drops on Paper
**Solution:** Reduce `perTickBudget` to 6 or increase `tickIntervalTicks` to 60

### Problem: Biomes don't change
**Solution:**
1. Check `/xmas status` - is `active: true`?
2. Check `snowWorld: "world"` in config.yml
3. Are you in the correct world?

### Problem: Chunk stripes after `/xmas off`
**Solution:**
1. `/xmas on` - Reactivate ChristmasSeason
2. `/xmas biome set snowy_plains 2` - Repair manually
3. `/xmas off` - Restore (now with manual fixes)

### Problem: `/xmas biome set` doesn't work
**Solution:** The command only works when ChristmasSeason is active (`/xmas on`)

### Problem: Emergency backups after every restart
**Cause:** The server was stopped while the event was active - that is normal during the season.
**Solution:** Nothing to do. Only the newest three emergency backups are kept; `/xmas backup clear` removes them.

### Problem: Snow layers or ice remain after `/xmas off`
**Cause:** The restore only removes snow/ice where the *original* biome is not naturally snowy or icy (snowy_*, frozen_*, ice_*, grove, jagged_peaks). Snow in those biomes is natural and stays.
**Check:** stand on the spot and run `/xmas biome info` - it shows the original biome and whether it counts as naturally snowy.
**Note:** `/xmas storm off` now pauses the auto phases until `/xmas storm on` or the next reload.

### Problem: Custom biomes came back as plains
**Cause:** Snapshot taken with v2.3.0 or older (keys stored without namespace).
**Solution:** Update to v2.4.0 before `/xmas on`; existing old snapshots can be fixed with `/xmas biome fix-diff <backup>` only if a backup from a newer version exists.

---

## 🔄 Migration Guide

### From v2.3.0 to v2.4.0

1. **Stop the server**
2. **Replace the JAR** with `ChristmasSeason-2.4.0.jar`
3. **Start the server** - done!

**Changes:**
- ✅ Config compatible - new optional sections: `schedule`, `advent`, `announcements`, `spawning`, `biome.exclude`, `biome.instantSnow`, `biome.convertAll`, `gifts.contents`, `gifts.effects`, plus keys `snowWorlds`, `biome.restore.removeSnowLayers/removeIce`, `wichtel.stealOnlyDecorations`, `*.maxNearPlayer`, `snowmen.lifetimeSeconds`, `elves.lifetimeSeconds/world`, `updateChecker.*`
- ✅ New commands `/advent`, `/xmas feature`, `/xmas stats`, `/xmas biome convert-all`; new permissions `xmas.advent`, `xmas.bypass.snowmen`
- ✅ Database compatible - new snapshots use namespaced biome keys, old ones are still read
- ✅ `api-version` raised to `1.21.3` (the real minimum since v2.3.0)
- ⚠️ Language files were reworked: delete `messages_en.yml`/`messages_de.yml` from the plugin folder if you never edited them, otherwise the bundled defaults fill in the new keys

### From v2.0.0 to v2.1.0

**Recommended for all Folia servers!** This update fixes critical thread-safety issues.

1. **Stop the server**
2. **Replace the JAR** with `ChristmasSeason-2.1.0.jar`
3. **Start the server** - done!

**Changes:**
- ✅ Config remains identical
- ✅ Database fully compatible
- ✅ Critical Folia fixes (no more crashes during `/xmas off`)
- ✅ Complete internationalization (all logs respect `language` setting)
- ✅ Fixed TPS drops on Folia with `perTickBudget` enforcement

### From v1.4.1

**Good news:** Version 2.1 is fully compatible!

1. **Stop the server**
2. **Replace the old JAR** with `ChristmasSeason-2.1.0.jar`
3. **Start the server** - done!

**Changes:**
- ✅ Config remains identical (optional: add `perTickBudget: 12`)
- ✅ Database compatible (SQLite automatically migrated)
- ✅ Same performance on Paper as v1.4.1
- ✅ Bonus: Now works on Folia too!

---

## 📝 Changelog

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

**v2.4.0 Highlights:**
- 🔥 Backups are complete again (WAL checkpoint before every copy - timestamp and emergency backups silently lost the newest chunks before)
- 🔥 Custom/data-pack biomes restore correctly (namespaced snapshot keys)
- 🔥 `/xmas biome compare` and `fix-diff` no longer touch the world from an async thread
- 🔄 Restart/reload-safe tracking of gift chests, decorations and event mobs
- 🧹 Emergency backups rotated (max 3); chunk loads no longer bypass the per-tick budget
- 🧝 Wichtel/elves only collect decoration items (never player drops); safer hopping
- 🌐 All code comments, Javadoc and messages in English (German language file included)
- 📅 Schedule, advent calendar, multiple snow worlds, exclusion zones, instant snow, whole-world conversion, weighted loot, runtime feature toggles, gift statistics, PlaceholderAPI and API events

**v2.1.0 Highlights:**
- 🔥 **CRITICAL:** Fixed Folia crashes during `/xmas off` (thread-safety violations)
- 🔥 **CRITICAL:** Fixed TPS drops to 16 on Folia (proper `perTickBudget` enforcement)
- 🔥 **CRITICAL:** Fixed `/xmas biome set` not creating snapshots correctly
- 🌐 Complete internationalization (all 68+ log messages now respect `language` setting)
- 🔄 Automatic chunk retry mechanism (fixes "missing chunks" issue)
- ✅ Fixed client-side biome caching after `/xmas off`

**v2.0.0 Highlights:**
- 🎄 Multi-Platform Support (Paper/Purpur/Folia)
- ⚡ Chunk Queue System (prevents TPS spikes)
- 🐛 Race Condition Fixes (biome set, restore)
- 🚀 2x faster biome updates (perTickBudget: 12)
- 🔧 Improved cache management

---

## 📜 License

**MIT License**

Copyright (c) 2025 Boondock_Sulfur

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

---

## 🎯 Developer Notes

### Dependencies:
- **Paper API** 26.1.2.build.72-stable (one JAR covers 1.21.3+ and 26.x, api-version 1.21.3)
- **FoliaLib** 0.4.3 (shaded & relocated)
- **SQLite JDBC** 3.45.0.0 (shaded)

### Build:
```bash
mvn clean package
```

### Scheduler Usage:
```java
FoliaSchedulerHelper scheduler = new FoliaSchedulerHelper(plugin);

// Global Task (Weather, etc.)
scheduler.runGlobalTask(() -> { ... });

// Location-based Task (Chunks, Blocks)
scheduler.runAtLocation(location, () -> { ... });

// Entity-based Task (Mobs, Items)
scheduler.runForEntity(entity, () -> { ... });
```

---

**Have fun with ChristmasSeason v2.4! 🎄❄️**
