package com.leohearts.telluscompat;

import com.mojang.logging.LogUtils;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysics;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Replaces Sable's per-dimension air pressure curve with one derived from Tellus' own
 * height mapping.
 *
 * <p>Sable's default curve is {@code exp(-0.004 * (y - sea level))}, which assumes a
 * vanilla 384 block tall world (pressure halves every 173 blocks). Tellus worlds are far
 * taller, so an airship loses all lift long before it reaches the mountains. This mod
 * rebuilds the curve from Tellus' settings using a real atmospheric scale height.
 */
@Mod(TellusAeronauticsCompat.MOD_ID)
public final class TellusAeronauticsCompat {

    public static final String MOD_ID = "tellus_aeronautics_compat";
    static final Logger LOG = LogUtils.getLogger();
    /** Above any datapack-provided curve, so the runtime value wins. */
    private static final int PRIORITY = 1000;

    static void applyCurves(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            // instanceof loads Tellus classes, so gate on the package name first.
            if (!generator.getClass().getName().startsWith("com.yucareux.tellus.")) {
                continue;
            }
            if (!(generator instanceof EarthChunkGenerator tellus)) {
                continue;
            }

            EarthGeneratorSettings settings = tellus.settings();
            PressureCurve curve = new PressureCurve(settings);
            DimensionPhysics defaults = DimensionPhysicsData.getDefault(level);
            DimensionPhysics physics = new DimensionPhysics(
                    level.dimension().location(),
                    PRIORITY,
                    defaults.universalDrag(),
                    defaults.baseGravity(),
                    Optional.of(1.0),
                    Optional.of(curve.buildBezier(level.getMinBuildHeight(), level.getMaxBuildHeight())),
                    defaults.magneticNorth(),
                    defaults.ignoreChunks());
            DimensionPhysicsData.addPhysicsWithPriority(level.dimension(), physics);

            int top = level.getMaxBuildHeight() - 1;
            LOG.info("{}: sea level y={}, {} m per block, pressure at y={} is {}",
                    level.dimension().location(), curve.seaLevelY(),
                    String.format("%.2f", curve.metresPerBlock()), top,
                    String.format("%.5f", curve.pressureAt(top)));
        }
    }
}
