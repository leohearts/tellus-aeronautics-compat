package com.leohearts.telluscompat.mixin;

import com.mojang.logging.LogUtils;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.BalloonBuilder;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.graph.BalloonLayerGraph;
import dev.ryanhcode.sable.util.LevelAccelerator;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a hot air balloon's fill from walking off into the world.
 *
 * <p>{@code upwardsBiasedFloodFill} fills one Y level with a breadth first search, then calls
 * itself on the block above. Nothing bounds either direction except the world's build height,
 * so a burner that is not inside an airtight envelope — or one whose envelope has a gap — is
 * walked to the top of the world across the entire connected air region.
 *
 * <p>On a vanilla sized world that is a few hundred frames and a small area, and nobody
 * notices. On a Tellus world the build height is 10448, so the climb is over ten thousand
 * frames: the client's render thread dies with a StackOverflowError, and a dedicated server,
 * whose stack survives, stalls for tens of seconds inside one tick. Inside a Sable sub-level it
 * is worse again, because the fill spreads through the plot's empty chunks and loads them as
 * it goes.
 *
 * <p>So both directions get a bound, and tripping either one ends the fill instead of letting
 * it keep going. The vertical bound matches what the mod already does when it runs off the top
 * of the world ({@code isOutsideBuildHeight} returns false there, which aborts the build). A
 * real envelope is a few dozen blocks across and tall, so neither bound is ever reached by a
 * balloon that is actually enclosed.
 */
@Mixin(BalloonBuilder.class)
public class BalloonFloodFillMixin {

    /** Deeper than any real envelope, shallow enough that a runaway cannot exhaust the stack. */
    private static final int MAX_ASCENT = 128;

    /** Chebyshev radius around the block the fill started from. */
    private static final int MAX_RADIUS = 48;

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<BlockPos> ROOT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TRIPPED = ThreadLocal.withInitial(() -> false);

    private static boolean telluscompat$reported = false;

    /** Wraps the whole fill so the radius has an origin and the state is cleared afterwards. */
    @Redirect(
        method = "buildBalloon",
        at = @At(
            value = "INVOKE",
            target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonBuilder;"
                + "upwardsBiasedFloodFill(Ldev/ryanhcode/sable/util/LevelAccelerator;"
                + "Lnet/minecraft/core/BlockPos;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;)Z"
        )
    )
    private static boolean telluscompat$boundedFill(
        LevelAccelerator accelerator,
        BlockPos startPos,
        BalloonLayerGraph outputGraph,
        BalloonLayerGraph existingGraph,
        BalloonLayerGraph mainGraph
    ) {
        ROOT.set(startPos.immutable());
        TRIPPED.set(false);
        try {
            return BalloonBuilder.upwardsBiasedFloodFill(accelerator, startPos, outputGraph, existingGraph, mainGraph);
        } finally {
            ROOT.remove();
            TRIPPED.remove();
            DEPTH.remove();
        }
    }

    @Redirect(
        method = "upwardsBiasedFloodFill",
        at = @At(
            value = "INVOKE",
            target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonBuilder;"
                + "upwardsBiasedFloodFill(Ldev/ryanhcode/sable/util/LevelAccelerator;"
                + "Lnet/minecraft/core/BlockPos;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;"
                + "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/graph/BalloonLayerGraph;)Z"
        )
    )
    private static boolean telluscompat$limitAscent(
        LevelAccelerator accelerator,
        BlockPos startPos,
        BalloonLayerGraph outputGraph,
        BalloonLayerGraph existingGraph,
        BalloonLayerGraph mainGraph
    ) {
        int depth = DEPTH.get();
        if (depth >= MAX_ASCENT || TRIPPED.get()) {
            telluscompat$report();
            return false;
        }
        DEPTH.set(depth + 1);
        try {
            return BalloonBuilder.upwardsBiasedFloodFill(accelerator, startPos, outputGraph, existingGraph, mainGraph);
        } finally {
            DEPTH.set(depth);
        }
    }

    @Redirect(
        method = "upwardsBiasedFloodFill",
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayFIFOQueue;enqueue(J)V"
        )
    )
    private static void telluscompat$limitRadius(LongArrayFIFOQueue queue, long packed) {
        BlockPos root = ROOT.get();
        if (root == null) {
            queue.enqueue(packed);
            return;
        }
        if (Math.abs(BlockPos.getX(packed) - root.getX()) > MAX_RADIUS
                || Math.abs(BlockPos.getZ(packed) - root.getZ()) > MAX_RADIUS) {
            TRIPPED.set(true);
            return;
        }
        queue.enqueue(packed);
    }

    private static void telluscompat$report() {
        if (telluscompat$reported) {
            return;
        }
        telluscompat$reported = true;
        BlockPos from = ROOT.get();
        LogUtils.getLogger().info(
            "Hot air balloon fill from {} was stopped after {} blocks of height or a {} block radius. "
                + "There is nothing airtight enclosing the burner, or the envelope has a gap. On a Tellus world "
                + "the fill would otherwise climb to y={} and load chunks along the way.",
            from, MAX_ASCENT, MAX_RADIUS, from == null ? 0 : from.getY() + MAX_ASCENT
        );
    }
}
