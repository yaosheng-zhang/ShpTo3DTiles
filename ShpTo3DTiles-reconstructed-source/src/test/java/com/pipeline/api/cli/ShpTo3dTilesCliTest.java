package com.pipeline.api.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.gltf.Mesh;
import org.junit.jupiter.api.Test;

class ShpTo3dTilesCliTest {
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
}
