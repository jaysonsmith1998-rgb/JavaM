# Shangri-La Caves — Fabric mod (v16)

Port of the v15 datapack to a Fabric mod for Minecraft 26.1.2.

## What this mod does

- Adds the `shangri_la:cherry_cavern` biome — cherry-grove themed underground
  void with pink particles, grass-and-dirt floors, scattered cherry trees, and
  peaceful spawners.
- Places the biome via a custom `BiomeSource` (`shangri_la:shangri_la`) that
  wraps the vanilla overworld biome source. Inside designated **regions**, the
  custom source returns `cherry_cavern`. Outside regions, vanilla biomes play
  unchanged.
- Carves the chambers with a custom `WorldCarver` (`shangri_la:chamber`) that
  produces a single contained dome-shaped void per region — **never extends
  outside the region footprint**. This is the entire reason the datapack was
  abandoned: vanilla `minecraft:cave` carvers couldn't be contained.
- Generates vanilla plains villages inside the chambers, with overridden
  `streets`/`terminators` template pools forced to `rigid` projection so the
  village doesn't shoot up to the surface heightmap.

## Region layout

- Grid cells of 5,000 blocks each
- 50% probability per cell that a cell hosts a region
- Region center jittered within the cell (always ≥ 256 blocks from any cell
  boundary, so regions never touch)
- Chamber: 192-block horizontal radius, Y from –30 to 30, dome profile
  (half-ellipse taper from full height at center to 0 at edge)
- Salt is fixed (`ShangriLaRegion.DEFAULT_SALT`) — same region coordinates in
  every world. Change in `ShangriLaRegion.java` for per-world variation.

## Why the new architecture solves the datapack ceiling

| Datapack problem (v15) | Mod solution |
|---|---|
| `minecraft:cave` carver leaks 50–100+ blocks outside biome | Custom `ChamberCarver` has strict per-column geometry check — no escape possible |
| Village pieces sit on synthetic `beard_box` floor regardless of cave shape | Real flat chamber floor; `terrain_adaptation: "none"` on the structure |
| 6D noise param tuning can only approximate "rare but large" | Region size and rarity set directly in code (`ShangriLaRegion` constants) |
| Aquifers flood the chamber | Custom carver doesn't sample the aquifer, so no fluid leaks in. `liquid_settings: "ignore_waterlogging"` still cleans up village water blocks |

## Project structure

```
shangri_la_mod/
├── build.gradle, gradle.properties, settings.gradle
├── gradle/wrapper/gradle-wrapper.properties
└── src/main/
    ├── java/com/shangrila/
    │   ├── ShangriLaMod.java
    │   ├── registry/ModRegistries.java
    │   └── world/
    │       ├── ShangriLaRegion.java     -- pure-Java geometry
    │       ├── ShangriLaBiomeSource.java -- wraps MultiNoiseBiomeSource
    │       └── ChamberCarver.java       -- bounded chamber carver
    └── resources/
        ├── fabric.mod.json, pack.mcmeta
        └── data/
            ├── minecraft/dimension/overworld.json          -- our biome source
            ├── minecraft/worldgen/template_pool/village/plains/  -- rigid override
            └── shangri_la/
                ├── worldgen/biome/cherry_cavern.json
                ├── worldgen/configured_carver/chamber.json
                ├── worldgen/configured_feature/*.json
                ├── worldgen/placed_feature/*.json
                ├── worldgen/structure/cherry_cavern_chamber.json
                ├── worldgen/structure_set/cherry_cavern_chamber.json
                ├── worldgen/template_pool/cherry_cavern/village_start.json
                └── tags/worldgen/biome/has_structure/cherry_cavern_chamber.json
```

## Build

Requires JDK 25 and IntelliJ 2025.3 or higher (for mixin support).

```bash
# Set up wrapper
gradle wrapper --gradle-version 9.4.0

# Build the mod jar
./gradlew build

# Output:
build/libs/shangri-la-1.0.0.jar
```

Drop `build/libs/shangri-la-1.0.0.jar` into your `mods/` folder alongside
Fabric API (0.148.2+26.1.2 or newer) and Fabric Loader (0.18.4 or newer).

## Known concerns to verify on first compile

These are minor API uncertainties — my reference docs cover MC versions
adjacent to 26.1.2, and a small handful of method names may have shifted
slightly. If the build fails, look at these first:

1. **`MultiNoiseBiomeSource.CODEC.codec().fieldOf("wrapped")`** — if the
   compiler complains, the `.codec()` conversion may be unnecessary in 26.1.
   Try `MultiNoiseBiomeSource.CODEC.fieldOf("wrapped")` directly (MapCodec has
   its own fieldOf in some versions).
2. **`CarverConfiguration.CODEC.codec()`** — same potential simplification.
3. **`ChunkPos#getMinBlockX()`** — alternate name in some Mojang revs is
   `getMinBlockX()` or via `SectionPos.blockToSectionCoord` math. The
   construction `chunkPos.x * 16` always works as a fallback.
4. **`ResourceLocation.fromNamespaceAndPath`** — if missing, use
   `new ResourceLocation("shangri_la", path)` (deprecated but functional).

If you hit any of these, the fixes are mechanical (1-line edits).

## Tuning

All region/chamber parameters are constants at the top of
`ShangriLaRegion.java`. Recompile after changes:

```java
public static final int GRID_SIZE = 5_000;
public static final double REGION_PROBABILITY = 0.5;
public static final int REGION_RADIUS_BLOCKS = 192;
public static final int CHAMBER_Y_MIN = -30;
public static final int CHAMBER_Y_MAX =  30;
```

Village spacing lives in
`data/shangri_la/worldgen/structure_set/cherry_cavern_chamber.json`. Current
spacing (80) ensures every region gets at least one village.
