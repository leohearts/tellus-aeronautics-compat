package com.leohearts.telluscompat.mixin;

import com.leohearts.telluscompat.SablePlotSpace;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects Sable's sub-level plot grid into a region Tellus can actually represent. Both
 * containers build on this constructor, so one hook covers the server and the client, and
 * both sides must agree or they disagree about where sub-levels are.
 *
 * <p>The origin is written after construction rather than at the call site: the origin is
 * passed in as a compile time constant that Sable's own mixin has already inlined, so there
 * is nothing left to intercept. Nothing in the constructor reads it back, and every other use
 * goes through the fields.
 *
 * <p>See {@link SablePlotSpace} for why the default origin does not work.
 */
@Mixin(SubLevelContainer.class)
public abstract class SubLevelContainerMixin {

    @Mutable
    @Shadow
    @Final
    private int originX;

    @Mutable
    @Shadow
    @Final
    private int originZ;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void telluscompat$movePlotGrid(CallbackInfo ci) {
        this.originX = SablePlotSpace.ORIGIN;
        this.originZ = SablePlotSpace.ORIGIN;
        SablePlotSpace.reportMovedGrid();
    }
}
