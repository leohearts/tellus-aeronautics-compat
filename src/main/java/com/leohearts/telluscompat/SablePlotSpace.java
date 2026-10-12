package com.leohearts.telluscompat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Sable keeps sub-levels (Create Aeronautics vehicles, tracks, and anything else assembled
 * into a physics body) in a reserved chunk grid of the overworld rather than in a separate
 * dimension. By default that grid starts at plot 10000 with 128x128 chunks per plot, so the
 * lowest plot chunk is 1280000 and the grid spans blocks 20480000..20740095.
 *
 * <p>Tellus' world is a Mercator projection clamped to +/-20037508 blocks. Worse, while the
 * experimental height increase is on, Tellus overwrites {@code BlockPos.asLong/getX/getY/getZ}
 * with a dense 64 bit layout that clamps X/Z to that same range, and the layout is already at
 * the 64 bit limit, so it cannot be widened. Every block of every Sable plot therefore
 * collapses onto the world edge, which is what breaks Sable's collisions and physics, and any
 * chunk out there makes Tellus' chunk generator throw outright.
 *
 * <p>Moving the grid inside the world fixes both. The grid is 128 plots of 128 chunks, i.e.
 * 262144 blocks across, so the origin has to stay at plot 9655 or below; 9000 puts it at
 * blocks 18432000..18694143, over open Pacific and far from anywhere players build.
 *
 * <p>This only changes where new sub-levels are allocated. Sub-levels that were saved at the
 * old origin stay there and have to be assembled again.
 */
public final class SablePlotSpace {

    /** Sable's own origin, kept only for the log line. */
    private static final int SABLE_DEFAULT_ORIGIN = 10000;
    /** Plots per grid side, i.e. Sable's {@code DEFAULT_LOG_SIZE_LENGTH} of 7. */
    private static final int PLOTS_PER_SIDE = 1 << 7;
    /** Chunks per plot side, i.e. Sable's {@code DEFAULT_LOG_PLOT_SIZE} of 7. */
    private static final int CHUNKS_PER_PLOT = 1 << 7;

    private static final int BLOCK_SIZE_PER_PLOT = CHUNKS_PER_PLOT * 16;

    /** The plot coordinate the grid is moved to. */
    public static final int ORIGIN = 9000;

    private static final Logger LOG = LogUtils.getLogger();
    private static boolean reported;

    /**
     * Called from the mixin. Also the thing that loads this class: {@link #ORIGIN} is a
     * compile time constant, so referencing it alone gets inlined and never runs the class
     * initialiser.
     */
    public static void reportMovedGrid() {
        if (reported) {
            return;
        }
        reported = true;
        LOG.info("Sable sub-level plot grid moved from plot {} to plot {}: blocks {}..{} "
                        + "instead of {}..{} (Tellus' world ends at +/-20037508)",
                SABLE_DEFAULT_ORIGIN, ORIGIN,
                ORIGIN * BLOCK_SIZE_PER_PLOT,
                (ORIGIN + PLOTS_PER_SIDE) * BLOCK_SIZE_PER_PLOT - 1,
                SABLE_DEFAULT_ORIGIN * BLOCK_SIZE_PER_PLOT,
                (SABLE_DEFAULT_ORIGIN + PLOTS_PER_SIDE) * BLOCK_SIZE_PER_PLOT - 1);
    }

    private SablePlotSpace() {
    }
}
