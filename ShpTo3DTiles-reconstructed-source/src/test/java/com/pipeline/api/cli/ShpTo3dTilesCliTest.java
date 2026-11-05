package com.pipeline.api.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.GeometryDiagnostics;
import com.pipeline.api.gltf.Mesh;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShpTo3dTilesCliTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void texturedWallSplitKeepsRoofFacesOutOfFacadeFloorClipping() throws Exception {
      BuildingFootprintDTO footprint = new BuildingFootprintDTO();
      footprint.setBid(1L);
      footprint.setWkt("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
      footprint.setBaseZ(0.0);
      footprint.setJzgd(24.0);
      footprint.setFloors(8);
      BuildingExtruder.Profile profile = new BuildingExtruder.Profile();
      profile.parapetMinLc = Integer.MAX_VALUE;
      profile.shaftMinLc = Integer.MAX_VALUE;

      BuildingExtruder.Segment wall = BuildingExtruder.extrude(footprint, profile).stream()
         .filter(segment -> "wall".equals(segment.material))
         .findFirst()
         .orElseThrow();
      int capFaceCount = wall.roofFaceIndices.size() + wall.bottomFaceIndices.size();

      for (double[] vertex : wall.verts) {
         vertex[2] += vertex[0] * vertex[0] * 0.001;
      }
      Mesh mesh = new Mesh();
      ShpTo3dTilesCli.appendLayeredWall(mesh, wall, "wall_layered", 0.0, 3.0, 8, 0);

      assertEquals(capFaceCount, mesh.groupFor("roof_white").size());
      assertFalse(mesh.groupFor("wall_layered").isEmpty());
   }

   @Test
   void failedBuildingExtrusionIsLoggedAndDoesNotPreventTheNextBuilding() throws Exception {
      Path log = GeometryDiagnostics.initialize(temporaryDirectory, temporaryDirectory.resolve("buildings.shp"));
      BuildingFootprintDTO invalid = footprint("NOT A WKT GEOMETRY", 4);
      invalid.setDiagnosticContext("shpRow=3948 featureId=test.3948 geometryField=the_geom idField=bid idValue=3948 fields={name=bad-footprint}");

      ShpTo3dTilesCli.ExtrusionResult skipped = ShpTo3dTilesCli.extrudeSafely(
         invalid, 3947L, 42, LodProfile.NONE, new BuildingExtruder.Profile()
      );
      BuildingFootprintDTO valid = footprint("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))", 1);
      ShpTo3dTilesCli.ExtrusionResult generated = ShpTo3dTilesCli.extrudeSafely(
         valid, 3948L, 43, LodProfile.NONE, new BuildingExtruder.Profile()
      );

      assertTrue(skipped.segments().isEmpty());
      assertNull(skipped.footprint());
      assertTrue(generated.segments().stream().anyMatch(segment -> "wall".equals(segment.material)));

      String diagnostics = Files.readString(log);
      assertTrue(diagnostics.contains("ERROR stage=building-extrusion"));
      assertTrue(diagnostics.contains("buildingId=3947"));
      assertTrue(diagnostics.contains("shpOrdinal=43 lod=NONE"));
      assertTrue(diagnostics.contains("shpRow=3948"));
      assertTrue(diagnostics.contains("fields={name=bad-footprint}"));
   }

   private static BuildingFootprintDTO footprint(String wkt, int floors) {
      BuildingFootprintDTO footprint = new BuildingFootprintDTO();
      footprint.setBid(3947L);
      footprint.setWkt(wkt);
      footprint.setBaseZ(0.0);
      footprint.setJzgd(24.0);
      footprint.setFloors(floors);
      return footprint;
   }
}
