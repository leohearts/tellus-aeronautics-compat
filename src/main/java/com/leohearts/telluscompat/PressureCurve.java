package com.leohearts.telluscompat;

import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.TerrainHeightTransform;
import dev.ryanhcode.sable.physics.config.dimension_physics.BezierResourceFunction;

/**
 * Maps Tellus block Y back to a real altitude so the air pressure can follow the real
 * atmosphere.
 *
 * <p>Tellus does not put one block per metre. It scales every elevation by
 * {@code height_scale / world_scale}, and while automatic height scaling is on it also
 * stretches it by the Mercator scale factor of the column's latitude so the terrain keeps its
 * real shape. The block to metre ratio therefore depends on latitude, which a single
 * per-dimension pressure curve cannot express; the spawn latitude is used as the
 * representative one.
 *
 * <p>The forward direction is delegated to Tellus' own {@link TerrainHeightTransform} instead
 * of being reimplemented, so it stays correct across Tellus versions and handles the
 * experimental height increase, which compresses land onto a soft ceiling and ocean onto a
 * depth limit. The inverse is a bisection over that same function.
 */
public final class PressureCurve {

    /** Real atmospheric scale height in metres: pressure drops to 1/e every 8500 m. */
    private static final double SCALE_HEIGHT = 8500.0;
    /** Inversion range in metres: past the Mariana Trench, and past anything Tellus maps. */
    private static final double ALTITUDE_MIN = -12000.0;
    private static final double ALTITUDE_MAX = 100000.0;
    /** Bisection steps; 40 halves the 112 km range to far below a metre. */
    private static final int ALTITUDE_STEPS = 40;
    /** Bezier control point count and spacing, in blocks. */
    private static final int MAX_POINTS = 64;
    private static final double POINT_SPACING = 250.0;
    /** Central difference step used for the Bezier tangents, in blocks. */
    private static final double SLOPE_STEP = 0.5;

    private final EarthGeneratorSettings settings;
    private final double latitude;

    public PressureCurve(EarthGeneratorSettings settings) {
        this.settings = settings;
        this.latitude = settings.spawnLatitude();
    }

    /** Real altitude in metres to block Y. */
    public double toY(double altitudeMeters) {
        double blocks = TerrainHeightTransform.scaledElevationBlocksAtLatitude(
                altitudeMeters,
                latitude,
                settings.effectiveVerticalWorldScale(),
                settings.effectiveTerrestrialHeightScale(),
                settings.effectiveOceanicHeightScale(),
                settings.experimentalIncreaseHeight(),
                settings.automaticHeightScaling());
        return Double.isFinite(blocks) ? settings.effectiveHeightOffset() + blocks
                                       : settings.effectiveHeightOffset();
    }

    /** Block Y to real altitude in metres. A saturated mapping yields the range bound. */
    public double toAltitude(double y) {
        double lo = ALTITUDE_MIN;
        double hi = ALTITUDE_MAX;
        for (int i = 0; i < ALTITUDE_STEPS; i++) {
            double mid = (lo + hi) / 2.0;
            if (toY(mid) < y) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2.0;
    }

    /** Relative air pressure at a given block Y. */
    public double pressureAt(double y) {
        return Math.exp(-toAltitude(y) / SCALE_HEIGHT);
    }

    /** The block Y that altitude 0 m maps to. */
    public int seaLevelY() {
        return settings.effectiveHeightOffset();
    }

    /** How many metres of real altitude one block of climb is worth, near sea level. */
    public double metresPerBlock() {
        return 1.0 / (toY(1.0) - toY(0.0));
    }

    /** Samples the pressure curve into the control points Sable evaluates. */
    public BezierResourceFunction buildBezier(int minY, int maxY) {
        BezierResourceFunction function = new BezierResourceFunction();
        int span = maxY - minY;
        int steps = (int) Math.max(4, Math.min(MAX_POINTS, Math.round(span / POINT_SPACING)));
        for (int i = 0; i <= steps; i++) {
            double y = minY + span * (double) i / steps;
            double pressure = pressureAt(y);
            double slope = (pressureAt(y + SLOPE_STEP) - pressureAt(y - SLOPE_STEP)) / (2.0 * SLOPE_STEP);
            function.addPoint(new BezierResourceFunction.BezierPoint(y, pressure, slope));
        }
        return function;
    }
}
