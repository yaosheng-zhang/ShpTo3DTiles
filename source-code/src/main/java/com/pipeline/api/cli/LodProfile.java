package com.pipeline.api.cli;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

/** Per-building geometry/detail profile. This is an intermediate LOD stage. */
public enum LodProfile {
    NONE(0.0, true, true),
    L1_SIMPLE(2.0, false, false),
    L2_STANDARD(0.5, true, false),
    L3_DETAIL(0.05, true, true);

    private final double tolerance;
    private final boolean parapet;
    private final boolean shaft;

    LodProfile(double tolerance, boolean parapet, boolean shaft) {
        this.tolerance = tolerance;
        this.parapet = parapet;
        this.shaft = shaft;
    }

    public Geometry simplify(Geometry geometry) {
        if (tolerance <= 0 || geometry == null || geometry.isEmpty()) return geometry;
        return TopologyPreservingSimplifier.simplify(geometry, tolerance);
    }

    public boolean includeParapet() { return parapet; }
    public boolean includeShaft() { return shaft; }
}
