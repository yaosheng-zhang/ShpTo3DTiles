package com.pipeline.api.geom;

import com.pipeline.api.dto.BuildingFootprintDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingExtruderTest {

    @Test
    void lowRiseWithoutParapetStillReachesBuildingHeight() throws Exception {
        List<BuildingExtruder.Segment> segments = extrude(
                "POLYGON((0 0, 24 0, 24 18, 0 18, 0 0))", 4, 12.0);

        assertEquals(0, segments.stream().filter(s -> "parapet".equals(s.material)).count());
        assertEquals(12.0, maxZ(segments), 1e-9);
    }

    @Test
    void parapetHeightGrowsByFloorAndStopsAtMaximum() throws Exception {
        assertEquals(0.30, parapetHeight(extrude(square(), 5, 18.0)), 1e-9);
        assertEquals(0.80, parapetHeight(extrude(square(), 10, 33.0)), 1e-9);
        assertEquals(1.60, parapetHeight(extrude(square(), 30, 93.0)), 1e-9);
    }

    @Test
    void machineRoomIsOneSimpleBlockOnRegularRoof() throws Exception {
        List<BuildingExtruder.Segment> shafts = shaftSegments(
                extrude("POLYGON((0 0, 30 0, 30 30, 0 30, 0 0))", 18, 57.0));

        // Always exactly one shaft, regardless of roof aspect.
        assertEquals(1, shafts.size());
        assertEquals("bldg_1_shaft", shafts.get(0).groupName);
        assertTrue(rectBoundaryClearance(shafts.get(0), 30.0, 30.0) >= 3.5 - 1e-9);
    }

    @Test
    void elongatedRoofStillGetsExactlyOneBlock() throws Exception {
        List<BuildingExtruder.Segment> shafts = shaftSegments(
                extrude("POLYGON((0 0, 80 0, 80 16, 0 16, 0 0))", 18, 57.0));

        // Quantity decision removed — every building with lc>=9 gets one shaft.
        assertEquals(1, shafts.size());
        assertEquals("bldg_1_shaft", shafts.get(0).groupName);
        assertTrue(rectBoundaryClearance(shafts.get(0), 80.0, 16.0) >= 3.5 - 1e-9);
    }

    @Test
    void buildingBelowMachineRoomGateGetsNoBlock() throws Exception {
        assertEquals(0, shaftSegments(extrude(square(), 8, 27.0)).size());
    }

    private static String square() {
        return "POLYGON((0 0, 30 0, 30 30, 0 30, 0 0))";
    }

    private static List<BuildingExtruder.Segment> extrude(String wkt, int floors, double height)
            throws Exception {
        BuildingFootprintDTO dto = new BuildingFootprintDTO();
        dto.setBid(1L);
        dto.setBaseZ(0.0);
        dto.setJzgd(height);
        dto.setFloors(floors);
        dto.setWkt(wkt);
        return BuildingExtruder.extrude(dto);
    }

    private static List<BuildingExtruder.Segment> shaftSegments(
            List<BuildingExtruder.Segment> segments) {
        return segments.stream().filter(s -> "shaft".equals(s.material)).toList();
    }

    private static double parapetHeight(List<BuildingExtruder.Segment> segments) {
        BuildingExtruder.Segment parapet = segments.stream()
                .filter(s -> "parapet".equals(s.material))
                .findFirst()
                .orElseThrow();
        double min = parapet.verts.stream().mapToDouble(v -> v[2]).min().orElseThrow();
        double max = parapet.verts.stream().mapToDouble(v -> v[2]).max().orElseThrow();
        return max - min;
    }

    private static double maxZ(List<BuildingExtruder.Segment> segments) {
        return segments.stream()
                .flatMap(s -> s.verts.stream())
                .mapToDouble(v -> v[2])
                .max()
                .orElseThrow();
    }

    private static double rectBoundaryClearance(BuildingExtruder.Segment segment,
                                                double width, double height) {
        double minX = segment.verts.stream().mapToDouble(v -> v[0]).min().orElseThrow();
        double maxX = segment.verts.stream().mapToDouble(v -> v[0]).max().orElseThrow();
        double minY = segment.verts.stream().mapToDouble(v -> v[1]).min().orElseThrow();
        double maxY = segment.verts.stream().mapToDouble(v -> v[1]).max().orElseThrow();
        return Math.min(Math.min(minX, width - maxX), Math.min(minY, height - maxY));
    }
}