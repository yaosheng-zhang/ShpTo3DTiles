package com.pipeline.api.cli;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

public enum LodProfile {
   NONE(0.0, true, true),
   L0_OVERVIEW(8.0, false, false),
   L1_SIMPLE(3.0, false, false),
   L2_STANDARD(1.5, true, false),
   L3_DETAIL(0.05, true, true);

   private final double tolerance;
   private final boolean parapet;
   private final boolean shaft;

   LodProfile(double var3, boolean var5, boolean var6) {
      this.tolerance = var3;
      this.parapet = var5;
      this.shaft = var6;
   }

   public Geometry simplify(Geometry var1) {
      return !(this.tolerance <= 0.0) && var1 != null && !var1.isEmpty() ? TopologyPreservingSimplifier.simplify(var1, this.tolerance) : var1;
   }

   public boolean includeParapet() {
      return this.parapet;
   }

   public boolean includeShaft() {
      return this.shaft;
   }
}
