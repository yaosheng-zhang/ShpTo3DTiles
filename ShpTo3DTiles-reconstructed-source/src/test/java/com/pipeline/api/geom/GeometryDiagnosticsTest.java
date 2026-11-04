package com.pipeline.api.geom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeometryDiagnosticsTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void writesStandaloneRunLog() throws Exception {
      Path shapefile = temporaryDirectory.resolve("buildings.shp");
      Path log = GeometryDiagnostics.initialize(temporaryDirectory, shapefile);
      GeometryDiagnostics.log("WARN stage=parapet shpRow=6793 geometryField=the_geom fields={entityid=abc}");

      String contents = Files.readString(log);
      assertTrue(log.getFileName().toString().startsWith("geometry-diagnostics-"));
      assertTrue(contents.contains(shapefile.toString()));
      assertTrue(contents.contains("shpRow=6793"));
      assertTrue(contents.contains("geometryField=the_geom"));
      assertTrue(contents.contains("entityid=abc"));
   }
}
