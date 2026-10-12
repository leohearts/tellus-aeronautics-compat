# Tellus and Sable: what actually conflicts

Sable keeps assembled vehicles in a reserved chunk grid of the overworld, and Tellus replaces
the overworld's coordinate packing, its height range and its bounds. This file is the list of
places where those two meet, what this mod does about each, and what is left for Sable or
Tellus to fix. Everything here was read out of the shipped jars (bytecode and annotations) or
measured on a running 1.21.1 server with this pack; the numbers are Tellus 0.8.3 and Sable
2.0.5.

## Fixed by this mod

### 1. Tellus' coordinate packing cannot represent Sable's default plot grid

Tellus replaces `BlockPos.asLong/getX/getY/getZ` for the whole JVM
(`mixin.BlockPosHighYMixin` → `worldgen.HighYPackedCoordinateProfile`). The profile is on by
default (`tellus.experimentalHeight.coordinateProfile` defaults to `global_mercator_land_v2`),
it is not per-dimension, and it uses a dense linear index instead of vanilla's bit field:

```
X, Z in [-20037509, 20037508]     Y in [-832, 10639]
pack(x, y, z) = ((z + 20037509) * 40075018 + (x + 20037509)) * 11472 + ySlot
```

`asLong` runs the coordinates through `packClamped`, so anything outside that box is **silently
clamped onto the world edge** rather than rejected. `Level.isInWorldBoundsHorizontal` is
narrowed to the same box (`mixin.LevelHighYPackedCoordinateMixin`), and in experimental-height
worlds `EarthChunkGenerator` throws `IllegalStateException` for any chunk whose 16x16 range
leaves it (`ExperimentalHeightSupport#validateHorizontalRangeOrThrow`), which completes the
chunk future exceptionally.

Sable's grid is 128x128 plots of 128x128 chunks starting at plot 10000, i.e. blocks
**20480000..20742143** — outside the box on both axes. Every block of every plot therefore
collapses onto `x = 20037508`, and the chunks out there fail to generate. This mod rewrites
`originX`/`originZ` on the container after construction (`SubLevelContainerMixin`), moving the
grid to plot 9000: blocks **18432000..18694143**, inside the clamp. `inBounds` and every other
use read the patched fields, so both sides agree.

### 2. The reserved square is a hole in the Tellus world

Sable's `plot.ServerChunkCacheMixin` treats *every* chunk inside the grid square as plot space,
not just the chunks that hold a sub-level: `inBounds` is a plain chunk-range test
(`((chunkX >> 7) - originX) in [0, 128)`), `getChunkNow` returns the plot chunk or null,
`getChunkFutureMainThread` hands back a shared `EmptyLevelChunk`, `hasChunk`/`isPositionTicking`
return false and `addRegionTicket` is cancelled. `plot.ChunkMapMixin` does the same for chunk
tracking and spawning.

Measured on a server with this mod: `/forceload add 18433000 18433000` followed by
`/setblock 18433000 100 18433000 stone` answers `That position is not loaded` — the ticket is
dropped and the chunk never loads. The same two commands at 18430000, just outside the square,
work and generate real terrain. So the square is inert: nothing generates, nothing loads, and a
player who travels there sees void.

That is Sable's design, not something this mod can change: the grid needs 262144x262144 blocks
of contiguous overworld chunk space, and Tellus generates terrain over the entire
±20037508 square, so there is no free region to move it to. With the default Mercator
projection the square is at 165.6..167.9°E, 83.7..84.6°S — Antarctica. It is 18432000 blocks
from the origin, so nobody stumbles into it, but it is not outside the world border (see 3) and
an operator can teleport there.

### 3. The world border does not match Tellus' coordinate clamp

`ExperimentalHeightSupport#configureWorldBorder` calls
`WorldBorder.setAbsoluteMaxSize(20037509)` on the overworld, and Tellus never calls `setSize`.
`setAbsoluteMaxSize` only stores the cap, so the border keeps its vanilla size: measured
`/worldborder get` on a running Tellus server answers **59999968** blocks, i.e. ±29999984. The
border therefore allows X/Z up to 29999984 while the coordinate packing stops at 20037508, and
the band between the two aliases every block onto the world edge. This is Tellus' own
inconsistency and this mod does not touch the world border — shrinking it to Tellus' own cap
would cut the reachable world down to ±10018754, which is half the planet.

## Not fixable from here

### 4. Physics is f32 in absolute world coordinates

Sable hands the sub-level's absolute world position to Rapier and reads it straight back
(`RapierPhysicsPipeline#add/#readPose` → `Rapier3D#createSubLevel/#getPose`), and the shipped
native is built with f32 (`cvtpd2ps`/`cvtsd2ss` on the way in, `movss` on the way out;
`glam` f32, `rapier3d`/`parry3d` without `-f64`). f32 resolution is 0.5 blocks at |x| = 4.19M
and 2 blocks at 16.78M. On a vanilla world that is irrelevant; on Tellus players routinely
cross millions of blocks, so vehicles far from the origin jitter and lose contact precision.
Fixing it needs a scene origin inside Sable and its native, not a compat mixin.

### 5. Tellus' weather hooks use the block's own coordinates

`mixin.BiomeFreezeMixin` and `mixin.BiomePrecipitationMixin` gate water freezing, snow and
precipitation on `TellusRealtimeState#realWorldTemperatureC`, which samples absolute block X/Z.
Inside a sub-level those coordinates are plot coordinates, so a vehicle's own blocks follow the
weather of the reserved square rather than the weather where it is flying.

### 6. Sub-level storage is tied to the world's height range

Sable sizes each plot chunk from the parent level (`LevelPlot#newEmptyChunk` allocates
`Level.getSectionsCount()` sections, 693 on a Tellus overworld against 24 vanilla) and
`ServerLevelPlot#save/#load` key the saved sections by section *index*, mapping back through
`ServerLevel#getSectionYFromSectionIndex`. The index depends on the level's minimum build
height, so changing Tellus' height settings after sub-levels exist shifts or drops their saved
sections. Those settings are chosen at world creation, so this only bites a world that is
re-created or edited later. It also means every sub-level chunk costs ~29x the vanilla section
work, and `SubLevelsBlockSkyMixinHelper` raycasts to y=10447 for every sky test.

## Existing sub-levels survive the grid move

The plot slot is stored origin-relative — `ServerLevelPlot#save` writes
`plot_x = plotPos.x - container.getOrigin().x` (a local index 0..127) and
`SubLevelContainer#allocateSubLevel` adds the origin back — while the pose and bounds are
absolute world values. Sub-levels saved before the move therefore come back in the new grid
with their world position untouched. There is no migration step and nothing has to be
assembled again.

## Not about Sable

`biolith` rejects the `tellus:earth` dimension type it does not know, so its features are
skipped in the Tellus overworld and in Sable's auxiliary levels:

```
[biolith/]: Ignoring world 'minecraft:overworld'; unknown dimension type: tellus:earth
[biolith/]: Ignoring world 'minecraft:server_faucet_test_level'; unknown dimension type: tellus:earth
```

`c2me` is a separate problem and is covered in the README: it replaces `ServerChunkCache`
itself, which is what Sable's plot hooks are attached to.
