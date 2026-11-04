package com.pipeline.api.geom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pipeline.api.dto.BuildingFootprintDTO;
import java.util.List;
import org.locationtech.jts.io.WKTReader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BuildingExtruderTest {
   @Test
   void extrudesParapetForConcaveFootprint() throws Exception {
      BuildingFootprintDTO footprint = footprint(
         "POLYGON ((0 0, 20 0, 20 6, 8 6, 8 20, 0 20, 0 0))"
      );

      List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(footprint);

      assertTrue(segments.stream().anyMatch(segment -> "parapet".equals(segment.material)));
      assertTrue(segments.stream().filter(segment -> "parapet".equals(segment.material))
         .allMatch(segment -> !segment.faces.isEmpty()));
   }

   @Test
   void extrudesParapetWhenInsetSplitsAtNarrowNeck() throws Exception {
      BuildingFootprintDTO footprint = footprint(
         "POLYGON ((0 0, 10 0, 10 4.75, 20 4.75, 20 0, 30 0, 30 10, 20 10, 20 5.25, 10 5.25, 10 10, 0 10, 0 0))"
      );

      List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(footprint);
      List<BuildingExtruder.Segment> parapets = segments.stream()
         .filter(segment -> "parapet".equals(segment.material))
         .toList();

      assertFalse(parapets.isEmpty());
      assertTrue(parapets.stream().allMatch(segment -> !segment.faces.isEmpty()));
   }

   @Test
   void normalizesConsecutiveDuplicateCoordinates() throws Exception {
      BuildingFootprintDTO footprint = footprint(
         "POLYGON ((0 0, 20 0, 20 0, 20 20, 0 20, 0 0, 0 0))"
      );

      List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(footprint);

      assertTrue(segments.stream().anyMatch(segment -> "wall".equals(segment.material)));
      assertTrue(segments.stream().anyMatch(segment -> "parapet".equals(segment.material)));
      assertTrue(segments.stream().allMatch(segment -> !segment.faces.isEmpty()));
   }

   @Test
   void repairsSelfTouchingFootprintBeforeExtrusion() throws Exception {
      BuildingFootprintDTO footprint = footprint(
         "POLYGON ((0 0, 10 0, 10 10, 5 5, 0 10, 5 5, 0 0))"
      );

      List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(footprint);

      assertTrue(segments.stream().anyMatch(segment -> "wall".equals(segment.material)));
      assertTrue(segments.stream().allMatch(segment -> !segment.faces.isEmpty()));
   }

   @Test
   void roofCapPreservesSubMillimeterDistinctVertices() throws Exception {
      String wkt = "POLYGON ((0 0, 20 0, 20 20, 10 20, 10 19, 9.9996 19.0002, 9.9992 19, 9 19, 9 20, 0 20, 0 0))";
      BuildingFootprintDTO footprint = footprint(wkt);
      BuildingExtruder.Profile profile = new BuildingExtruder.Profile();
      profile.parapetMinLc = Integer.MAX_VALUE;
      profile.shaftMinLc = Integer.MAX_VALUE;

      List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(footprint, profile);
      BuildingExtruder.Segment wall = segments.stream()
         .filter(segment -> "wall".equals(segment.material))
         .findFirst()
         .orElseThrow();
      double expectedArea = new WKTReader().read(wkt).getArea();

      assertEquals(expectedArea, roofArea(wall, footprint.getJzgd()), 1.0e-9);
   }

   @Test
   void marksOnlyTopCapTrianglesAsRoofFaces() throws Exception {
      BuildingFootprintDTO footprint = footprint("POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0))");
      BuildingExtruder.Profile profile = new BuildingExtruder.Profile();
      profile.parapetMinLc = Integer.MAX_VALUE;
      profile.shaftMinLc = Integer.MAX_VALUE;

      BuildingExtruder.Segment wall = BuildingExtruder.extrude(footprint, profile).stream()
         .filter(segment -> "wall".equals(segment.material))
         .findFirst()
         .orElseThrow();

      assertEquals(2, wall.roofFaceIndices.size());
      for (int faceIndex : wall.roofFaceIndices) {
         int[] face = wall.faces.get(faceIndex);
         assertTrue(java.util.Arrays.stream(face).allMatch(index ->
            Math.abs(wall.verts.get(index - 1)[2] - footprint.getJzgd()) < 1.0e-9
         ));
      }
   }

   private static double roofArea(BuildingExtruder.Segment segment, double roofZ) {
      double area = 0.0;
      for (int[] face : segment.faces) {
         double[] a = segment.verts.get(face[0] - 1);
         double[] b = segment.verts.get(face[1] - 1);
         double[] c = segment.verts.get(face[2] - 1);
         if (Math.abs(a[2] - roofZ) > 1.0e-9 || Math.abs(b[2] - roofZ) > 1.0e-9 || Math.abs(c[2] - roofZ) > 1.0e-9) {
            continue;
         }
         area += Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])) * 0.5;
      }
      return area;
   }

   private static BuildingFootprintDTO footprint(String wkt) {
      BuildingFootprintDTO footprint = new BuildingFootprintDTO();
      footprint.setBid(6792L);
      footprint.setWkt(wkt);
      footprint.setBaseZ(0.0);
      footprint.setJzgd(20.0);
      footprint.setFloors(8);
      return footprint;
   }
}
