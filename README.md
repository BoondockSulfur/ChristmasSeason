# ChristmasSeason v2.1 🎄

**Transform your Minecraft world into a winter wonderland!**

A comprehensive Christmas plugin featuring biome snowfall, snowstorms, NPCs, gifts, and much more. Now with **Multi-Platform Support** for Spigot, Paper, Purpur, **and Folia**!

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
- **Wichtel:** Mischievous mobs that can steal items
- **Elves:** Friendly NPCs that wander around
- **Snowmen:** Aggressive snow golems that throw snowballs

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
| **Paper** | ✅ Tested | Global Timer | 18-20 TPS |
| **Folia** | ✅ Tested | Player-based Entity Scheduler | Optimal for 50+ players |
| **Purpur** | ✅ Compatible | Global Timer | 18-20 TPS |
| **Spigot** | ✅ Compatible | Global Timer | 18-20 TPS |

**Automatic Detection:** The plugin detects the platform on startup and chooses the optimal strategy!

---

## 📦 Installation

1. **Download:** Get `ChristmasSeason-2.1.0.jar`
2. **Installation:** Copy the JAR to the `plugins/` folder
3. **Server Start:** Start your server (Spigot/Paper/Purpur/Folia)
4. **Configuration:** Adjust `config.yml` (optional)
5. **Activation:** `/xmas on` - Done! 🎄

**Requirements:**
- Minecraft 1.21+ (or Folia 1.20+)
- Java 21+
- Spigot/Paper/Purpur/Folia Server

---

## 🎮 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/xmas on` | Activates ChristmasSeason | `christmas.admin` |
| `/xmas off` | Deactivates and restores biomes | `christmas.admin` |
| `/xmas status` | Shows status (active/inactive) | `christmas.admin` |
| `/xmas reload` | Reloads configuration | `christmas.admin` |
| `/xmas biome set <biome> [radius]` | Manually sets biomes (only when active) | `christmas.admin` |
| `/xmas biome clearsnap` | Deletes biome snapshot database | `christmas.admin` |

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
language: "de"  # de or en

biome:
  enabled: true
  target: "SNOWY_PLAINS"
  enableSnapshot: true      # Important for restore!

  playerBubble:
    enabled: true
    radiusChunks: 2         # 5x5 chunks around player
    refreshClient: true     # Immediate client updates
    tickIntervalTicks: 40   # Every 2 seconds
    perTickBudget: 12       # 12 chunks per tick (fast!)

  restore:
    perTick: 4              # 4 chunks per tick during /xmas off

snowstorm:
  enabled: true
  mode: auto                # auto, manual, none
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

wichtel:
  enabled: true
  spawnIntervalSeconds: 45
  maxPerWorld: 6

elves:
  enabled: true
  spawnIntervalSeconds: 60
  maxPerWorld: 4

snowmen:
  enabled: true
  spawnIntervalSeconds: 45
  maxPerWorld: 6
  attackChance: 0.15
```

---

## 🔧 Performance Tuning

### For Paper/Spigot/Purpur:

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

---

## 🔄 Migration Guide

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

**v2.1.0 Highlights:**
- 🔥 **CRITICAL:** Fixed Folia crashes during `/xmas off` (thread-safety violations)
- 🔥 **CRITICAL:** Fixed TPS drops to 16 on Folia (proper `perTickBudget` enforcement)
- 🔥 **CRITICAL:** Fixed `/xmas biome set` not creating snapshots correctly
- 🌐 Complete internationalization (all 68+ log messages now respect `language` setting)
- 🔄 Automatic chunk retry mechanism (fixes "missing chunks" issue)
- ✅ Fixed client-side biome caching after `/xmas off`

**v2.0.0 Highlights:**
- 🎄 Multi-Platform Support (Spigot/Paper/Purpur/Folia)
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
- **Paper API** 1.21.3-R0.1-SNAPSHOT
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

**Have fun with ChristmasSeason v2.0! 🎄❄️**
