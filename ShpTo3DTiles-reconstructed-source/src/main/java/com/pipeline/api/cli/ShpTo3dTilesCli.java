package com.pipeline.api.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.GeometryDiagnostics;
import com.pipeline.api.geom.WhiteModelObjWriter;
import com.pipeline.api.gltf.Mesh;
import com.pipeline.api.gltf.ObjToGlb;
import com.pipeline.api.scene.BuildingTextureSelector;
import com.pipeline.api.scene.ObjToGlbConverter;
import com.pipeline.api.scene.TilesetWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.Property;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

public final class ShpTo3dTilesCli {
   private static final String DEFAULT_HEIGHT_ATTR = "maxz";
   private static final String DEFAULT_BASE_Z_ATTR = "minz";
   private static final String DEFAULT_FLOORS_ATTR = "lc";
   private static final String DEFAULT_USAGE_ATTR = "bld_usage";
   private static final String DEFAULT_ID_ATTR = "bid";
   private static final String[] USAGE_ATTR_ALIASES = new String[]{"bld_usage", "bldUseage", "bldUsage", "building_use", "usage"};
   private static final int ATLAS_WIDTH = 1536;
   private static final int ATLAS_HEIGHT = 1024;
   private static final int ATLAS_CELL = 512;
   private static final int ATLAS_GUTTER = 8;

   private ShpTo3dTilesCli() {
   }

   public static void main(String[] var0) throws Exception {
      ShpTo3dTilesCli.Options var1 = ShpTo3dTilesCli.Options.parse(var0);
      if (var1.help) {
         ShpTo3dTilesCli.Options.printUsage();
      } else {
         var1.validate();
         Files.createDirectories(var1.outDir);
          Path diagnosticLog = GeometryDiagnostics.initialize(var1.outDir, var1.shp);
          System.out.println("[geometry-diagnostics] log=" + diagnosticLog.toAbsolutePath());
         Path var2 = var1.outDir.resolve("obj");
         Path var3 = var1.outDir.resolve("glb-intermediate");
         Path var4 = var1.outDir.resolve("glb");
         Files.createDirectories(var2);
         Files.createDirectories(var3);
         Files.createDirectories(var4);
         Files.writeString(var2.resolve("white.mtl"), WhiteModelObjWriter.mtlContent(), StandardCharsets.UTF_8);
         Path var5 = var1.outDir.resolve("glb").resolve("textures");
         Files.createDirectories(var5);
         if (var1.textures) {
            for (String var7 : Set.copyOf(List.of(var1.whiteTexture, var1.layeredTexture))) {
               Files.write(var5.resolve(BuildingTextureSelector.outputName(var7)), BuildingTextureSelector.load(var7));
            }
         }

          List<BuildingRecord> var26 = readShapefile(var1);
         assignWkBldIdsByAdjacency(var26, var1.outDir);
         if (var26.isEmpty()) {
            TilesetWriter.write(var1.outDir, 0.0, 0.0, 0.0, List.of());
            System.out.println("[ShpTo3dTilesCli] no polygon features found");
         } else {
            double[] var27 = centroidMean(var26);
            ShpTo3dTilesCli.CoordinateFrame var8 = ShpTo3dTilesCli.CoordinateFrame.create(var1.prjCrs, var27);
            if (var8 == null) {
               if (var1.prjCrs == null) {
                  System.err
                     .println(
                        "[ShpTo3dTilesCli] WARNING: no .prj found (neither --prj nor a companion file). Falling back to treating source coordinates as lon/lat - if the data is projected, the tileset will be misplaced. Use --prj <file.prj> to specify the CRS."
                     );
               }

               var8 = ShpTo3dTilesCli.CoordinateFrame.unprojected(var27[0], var27[1]);
            }

            ShpTo3dTilesCli.CoordinateFrame var9 = var8;
            System.out
               .printf(Locale.ROOT, "[ShpTo3dTilesCli] source centroid=(%.3f, %.3f) -> WGS84=(%.6f, %.6f)%n", var27[0], var27[1], var9.lonDeg, var9.latDeg);
            if (var1.hasWkBldId(var26)) {
               List var29 = convertWkBldGroups(var26, var1, var9, var4);
               TilesetWriter.write(
                  var1.outDir,
                  var9.lonDeg,
                  var9.latDeg,
                  0.0,
                  var29,
                  new TilesetWriter.Options(var1.tileMaxContents, var1.tileMaxDepth, 1.0, var1.tileExternalDepth)
               );
               System.out.printf(Locale.ROOT, "[wkBldId merge] groups=%d buildings=%d output=%s%n", var29.size(), var26.size(), var1.outDir.toAbsolutePath());
               return;
            }

            System.out
               .printf(
                  Locale.ROOT,
                  "[ShpTo3dTilesCli] convert workers=%d inFlight<=%d lod=%s resume=%s%n",
                  Math.min(var1.parallelism, Math.max(1, var26.size())),
                  Math.min(var1.parallelism, Math.max(1, var26.size())) * 2,
                  var1.lod,
                  var1.resume
               );
            if (!var1.legacyPerBuilding && !var1.textures) {
               List var28 = convertOptimizedBatches(var26, var1, var9, var4);
               if (var1.buildingLodChain) {
                  var28 = addRegionalOverview(var26, var28, var1, var9, var4);
               }

               TilesetWriter.write(
                  var1.outDir,
                  var9.lonDeg,
                  var9.latDeg,
                  0.0,
                  var28,
                  new TilesetWriter.Options(var1.tileMaxContents, var1.tileMaxDepth, 1.0, var1.tileExternalDepth)
               );
               System.out
                  .printf(
                     Locale.ROOT, "[ShpTo3dTilesCli] optimized batches=%d buildings=%d output=%s%n", var28.size(), var26.size(), var1.outDir.toAbsolutePath()
                  );
            } else {
               BatchProgressLogger var10 = new BatchProgressLogger(
                  "convert/" + var1.lod.name().toLowerCase(Locale.ROOT), var26.size(), var1.progressIntervalSeconds
               );
               int var11 = Math.min(var1.parallelism, Math.max(1, var26.size()));
               ExecutorService var12 = Executors.newFixedThreadPool(var11);
               ExecutorCompletionService<ConversionResult> var13 = new ExecutorCompletionService<>(var12);
               ArrayList<TilesetWriter.Tile> var14 = new ArrayList<>();
               int var15 = 0;
               int var16 = 0;
               int var17 = 0;
               int var18 = 0;

               try {
                  int var19 = Math.max(1, var11 * 2);

                  for (ShpTo3dTilesCli.BuildingRecord var21 : var26) {
                     var13.submit(() -> convertOne(var21, var1, var9, var2, var3, var4));
                     if (++var15 - var16 >= var19) {
                        ShpTo3dTilesCli.ConversionResult var22 = (ShpTo3dTilesCli.ConversionResult)var13.take().get();
                        var16++;
                        if (var22.tile != null) {
                           var14.add(var22.tile);
                           var17++;
                        } else {
                           var18++;
                        }

                        var10.complete(var22.tile != null);
                        logConversionResult(var22);
                     }
                  }

                  while (var16 < var15) {
                     ShpTo3dTilesCli.ConversionResult var30 = (ShpTo3dTilesCli.ConversionResult)var13.take().get();
                     var16++;
                     if (var30.tile != null) {
                        var14.add(var30.tile);
                        var17++;
                     } else {
                        var18++;
                     }

                     var10.complete(var30.tile != null);
                     logConversionResult(var30);
                  }
               } finally {
                  var12.shutdown();
                  if (!var12.awaitTermination(1L, TimeUnit.MINUTES)) {
                     var12.shutdownNow();
                  }
               }

               var14.sort(Comparator.comparing(TilesetWriter.Tile::uri));
               var10.report(true);
               if (var1.textures && var1.buildingLodChain && !var1.legacyPerBuilding) {
                  var14 = new ArrayList<>(batchTexturedL1(var26, var14, var1, var9, var4));
               }

               if (var1.buildingLodChain) {
                  var14 = new ArrayList<>(addRegionalOverview(var26, var14, var1, var9, var4));
               }

               TilesetWriter.write(
                  var1.outDir,
                  var9.lonDeg,
                  var9.latDeg,
                  0.0,
                  var14,
                  new TilesetWriter.Options(var1.tileMaxContents, var1.tileMaxDepth, 1.0, var1.tileExternalDepth)
               );
               System.out.printf(Locale.ROOT, "[ShpTo3dTilesCli] input=%s features=%d ok=%d failed=%d%n", var1.shp, var26.size(), var17, var18);
               System.out.println("[ShpTo3dTilesCli] output=" + var1.outDir.toAbsolutePath());
            }
         }
      }
   }

   private static List<ShpTo3dTilesCli.BuildingRecord> readShapefile(ShpTo3dTilesCli.Options var0) throws IOException {
      HashMap<String, URL> var1 = new HashMap<>();
      URL var2 = var0.shp.toUri().toURL();
      var1.put("url", var2);
      if (var0.prj != null) {
         String var3 = readPrjText(var0.prj);
         var0.prjCrs = parseCrs(var3);
         System.out.println("[ShpTo3dTilesCli] using explicit .prj: " + var0.prj.toAbsolutePath());
      } else {
         Path var35 = var0.shp.toAbsolutePath().getParent().resolve(stripShpExt(var0.shp.getFileName().toString()) + ".prj");
         if (Files.exists(var35)) {
            String var4 = readPrjText(var35);
            var0.prjCrs = parseCrs(var4);
         }
      }

      ArrayList<BuildingRecord> var36 = new ArrayList<>();
      DataStore var37 = DataStoreFinder.getDataStore(var1);

      try {
         if (var37 == null) {
            throw new IOException("cannot open shapefile: " + var0.shp);
         }

         String var5 = var37.getTypeNames()[0];
         SimpleFeatureSource var6 = var37.getFeatureSource(var5);
         LinkedHashMap<String, String> var7 = new LinkedHashMap<>();

         for (String var9 : parseMetadataFields(var0.metadataFields)) {
            String var10 = resolveAttributeName(var6, var9);
            var7.put(var9, var10);
            if (var10 == null) {
               System.err.println("[metadata] Missing SHP field '" + var9 + "'; exporting null.");
            }
         }

         System.out.println("[metadata] exported fields=" + var7.keySet());
         String var41 = resolveAttributeName(var6, var0.usageAttr, USAGE_ATTR_ALIASES);
         System.out.printf(Locale.ROOT, "[textures] usage attribute requested='%s' resolved='%s'%n", var0.usageAttr, var41 == null ? "<none>" : var41);
         SimpleFeatureCollection var44 = var6.getFeatures();
         WKTWriter var46 = new WKTWriter();
         int var11 = 0;
          int sourceRow = 0;

         try (SimpleFeatureIterator var12 = var44.features()) {
            while (var12.hasNext()) {
               SimpleFeature var13 = var12.next();
                sourceRow++;
               if (var13.getDefaultGeometry() instanceof Geometry var14 && !var14.isEmpty()) {
                  long var52 = longValue(attr(var13, var0.idAttr), var11 + 1L);
                  BuildingFootprintDTO var17 = new BuildingFootprintDTO();
                  var17.setBid(var52);
                   var17.setDiagnosticContext(featureDiagnosticContext(var13, sourceRow, var0.idAttr, attr(var13, var0.idAttr)));
                  double var18 = doubleValue(attr(var13, var0.heightAttr), Double.NaN);
                  double var20 = doubleValue(attr(var13, var0.baseZAttr), Double.NaN);
                  double var22 = doubleValue(attr(var13, var0.floorsAttr), Double.NaN);
                  if (!Double.isFinite(var18)
                     || !Double.isFinite(var20)
                     || var18 <= var20
                     || !Double.isFinite(var22)
                     || var22 < 1.0
                     || var22 != Math.rint(var22)
                     || var22 > 10000.0
                     || !Double.isFinite(var18 - var20)) {
                      GeometryDiagnostics.log(
                         "ERROR stage=attribute-validation "
                            + featureDiagnosticContext(var13, sourceRow, var0.idAttr, attr(var13, var0.idAttr))
                            + " " + var0.heightAttr + "=" + safeLogValue(attr(var13, var0.heightAttr))
                            + " " + var0.baseZAttr + "=" + safeLogValue(attr(var13, var0.baseZAttr))
                            + " " + var0.floorsAttr + "=" + safeLogValue(attr(var13, var0.floorsAttr))
                      );
                     throw new IOException(
                        "Invalid elevation/storey data at feature "
                           + var13.getID()
                           + ": "
                           + var0.heightAttr
                           + "="
                           + var18
                           + ", "
                           + var0.baseZAttr
                           + "="
                           + var20
                           + ", "
                           + var0.floorsAttr
                           + "="
                           + var22
                           + ". Require finite maxz > minz and integer LC in [1,10000]."
                     );
                  }

                  var17.setJzgd(var18 - var20);
                  var17.setBaseZ(var20);
                  int var24 = (int)var22;
                  var17.setFloors(var24);
                  LinkedHashMap<String, Object> var25 = new LinkedHashMap<>();

                  for (Entry var27 : var7.entrySet()) {
                     var25.put((String)var27.getKey(), metadataValue(attr(var13, (String)var27.getValue())));
                  }

                  var17.setProperties(var25);
                  var17.setBldUsage(stringValue(attr(var13, var41), ""));
                  var17.setWkt(var46.write(var14));
                  var36.add(new ShpTo3dTilesCli.BuildingRecord(var52, var11, var17));
                  var11++;
               }
            }
         }
      } finally {
         if (var37 != null) {
            var37.dispose();
         }
      }

      int var38 = 0;
      int var39 = 0;
      int var40 = 0;

      for (ShpTo3dTilesCli.BuildingRecord var45 : var36) {
         Integer var47 = var45.dto.getFloors();
         if (var47 != null && var47 > 0) {
            var38++;
         }

         if (var47 != null && var47 >= var0.parapetMinLc) {
            var39++;
         }

         if (var47 != null && var47 >= var0.shaftMinLc) {
            var40++;
         }
      }

      System.out
         .printf(
            Locale.ROOT,
            "[ShpTo3dTilesCli] floors attr='%s': %d/%d buildings have floors, %d >= parapet-min-lc(%d), %d >= shaft-min-lc(%d)%n",
            var0.floorsAttr,
            var38,
            var36.size(),
            var39,
            var0.parapetMinLc,
            var40,
            var0.shaftMinLc
         );
      if (var38 == 0) {
         System.out
            .println(
               "[ShpTo3dTilesCli] WARNING: no building has a usable floors value — parapets and rooftop shafts will NOT be generated. Check the --floors-attr field name."
            );
      }

      long var43 = var36.stream().filter(var0x -> var0x.dto.getBldUsage() != null && !var0x.dto.getBldUsage().isBlank()).count();
      System.out.printf(Locale.ROOT, "[textures] usage values: %d/%d buildings%n", var43, var36.size());
      TreeMap<String, Integer> var48 = new TreeMap<>();

      for (ShpTo3dTilesCli.BuildingRecord var50 : var36) {
         String var51 = var0.textureStem(var50.dto);
         var48.merge(var51, 1, Integer::sum);
      }

      System.out.println("[textures] assignments=" + var48);
      return var36;
   }

   private static String resolveAttributeName(SimpleFeatureSource var0, String var1, String... var2) {
      List<AttributeDescriptor> var3 = var0.getSchema().getAttributeDescriptors();
      if (var1 != null) {
         for (AttributeDescriptor var5 : var3) {
            if (var5.getLocalName().equalsIgnoreCase(var1)) {
               return var5.getLocalName();
            }
         }
      }

      for (String var7 : var2) {
         for (AttributeDescriptor var9 : var3) {
            if (var9.getLocalName().equalsIgnoreCase(var7)) {
               return var9.getLocalName();
            }
         }
      }

      return null;
   }

   private static CoordinateReferenceSystem parseCrs(String var0) {
      try {
         return CRS.parseWKT(var0);
      } catch (Exception var2) {
         System.err.println("[ShpTo3dTilesCli] failed to parse .prj WKT: " + var2.getMessage());
         return null;
      }
   }

   private static double[] toLonLat(CoordinateReferenceSystem var0, double var1, double var3) {
      if (var0 == null) {
         return null;
      }

      try {
         CoordinateReferenceSystem var5 = CRS.decode("EPSG:4326", true);
         if (CRS.equalsIgnoreMetadata(var0, var5)) {
            return new double[]{var1, var3};
         }

         MathTransform var6 = CRS.findMathTransform(var0, var5, true);
         double[] var7 = new double[]{var1, var3};
         var6.transform(var7, 0, var7, 0, 1);
         return new double[]{var7[0], var7[1]};
      } catch (Exception var8) {
         System.err.println("[ShpTo3dTilesCli] CRS->WGS84 transform failed: " + var8.getMessage());
         return null;
      }
   }

   private static String readPrjText(Path var0) throws IOException {
      byte[] var1 = Files.readAllBytes(var0);
      byte var2 = 0;
      Charset var3 = StandardCharsets.UTF_8;
      if (var1.length >= 3 && (var1[0] & 255) == 239 && (var1[1] & 255) == 187 && (var1[2] & 255) == 191) {
         var2 = 3;
      } else if (var1.length >= 2 && (var1[0] & 255) == 255 && (var1[1] & 255) == 254) {
         var2 = 2;
         var3 = StandardCharsets.UTF_16LE;
      } else if (var1.length >= 2 && (var1[0] & 255) == 254 && (var1[1] & 255) == 255) {
         var2 = 2;
         var3 = StandardCharsets.UTF_16BE;
      }

      String var4 = new String(var1, var2, var1.length - var2, var3).trim();
      if (var4.indexOf(65533) < 0) {
         return var4;
      } else {
         String var5 = new String(var1, var2, var1.length - var2, Charset.forName("GB18030")).trim();
         if (var5.indexOf(65533) < 0) {
            System.err.println("[ShpTo3dTilesCli] PRJ was not valid UTF-8; decoded as GB18030: " + var0);
            return var5;
         } else {
            return new String(var1, var2, var1.length - var2, StandardCharsets.US_ASCII).trim();
         }
      }
   }

   private static String stripShpExt(String var0) {
      int var1 = var0.toLowerCase(Locale.ROOT).lastIndexOf(".shp");
      return var1 <= 0 ? var0 : var0.substring(0, var1);
   }

   private static double[] centroidMean(List<ShpTo3dTilesCli.BuildingRecord> var0) {
      double var1 = 0.0;
      double var3 = 0.0;
      int var5 = 0;

      for (ShpTo3dTilesCli.BuildingRecord var7 : var0) {
         try {
            Geometry var8 = new WKTReader().read(var7.dto.getWkt());
            Coordinate var9 = var8.getCentroid().getCoordinate();
            var1 += var9.x;
            var3 += var9.y;
            var5++;
         } catch (Exception var10) {
         }
      }

      return var5 == 0 ? new double[]{0.0, 0.0} : new double[]{var1 / var5, var3 / var5};
   }

   private static double[] shiftAndBox(List<BuildingExtruder.Segment> var0, ShpTo3dTilesCli.CoordinateFrame var1) {
      double var2 = Double.POSITIVE_INFINITY;
      double var4 = var2;
      double var6 = var2;
      double var8 = Double.NEGATIVE_INFINITY;
      double var10 = var8;
      double var12 = var8;

      for (BuildingExtruder.Segment var15 : var0) {
         for (double[] var17 : var15.verts) {
            double[] var18 = var1.toLocalEnu(var17[0], var17[1], var17[2]);
            var17[0] = var18[0];
            var17[1] = var18[1];
            var17[2] = var18[2];
            var2 = Math.min(var2, var17[0]);
            var4 = Math.min(var4, var17[1]);
            var6 = Math.min(var6, var17[2]);
            var8 = Math.max(var8, var17[0]);
            var10 = Math.max(var10, var17[1]);
            var12 = Math.max(var12, var17[2]);
         }
      }

      return new double[]{var2, var4, var6, var8, var10, var12};
   }

   private static TilesetWriter.Tile toTile(String var0, double[] var1) {
      return new TilesetWriter.Tile(
         var0,
         new double[]{(var1[0] + var1[3]) / 2.0, (var1[1] + var1[4]) / 2.0, (var1[2] + var1[5]) / 2.0},
         new double[]{Math.max(0.5, (var1[3] - var1[0]) / 2.0), Math.max(0.5, (var1[4] - var1[1]) / 2.0), Math.max(0.5, (var1[5] - var1[2]) / 2.0)}
      );
   }

   private static TilesetWriter.Tile toTile(String var0, double[] var1, List<TilesetWriter.LodContent> var2) {
      TilesetWriter.Tile var3 = toTile(var0, var1);
      return new TilesetWriter.Tile(var3.uri(), var3.localBoxCenter(), var3.localBoxHalf(), var2);
   }

   private static List<TilesetWriter.Tile> convertOptimizedBatches(
      List<ShpTo3dTilesCli.BuildingRecord> var0, ShpTo3dTilesCli.Options var1, ShpTo3dTilesCli.CoordinateFrame var2, Path var3
   ) throws Exception {
      List var11 = spatialOrder(var0);
      int var4 = Math.max(1, var1.tileMaxContents);
      ArrayList var5 = new ArrayList();
      int var6 = 0;

      for (int var7 = 0; var7 < var11.size(); var7 += var4) {
         int var8 = Math.min(var11.size(), var7 + var4);
         List var9 = var11.subList(var7, var8);
         ShpTo3dTilesCli.ConversionResult var10 = convertOptimizedBatch(var9, var6++, var1, var2, var3);
         if (var10.tile != null) {
            var5.add(var10.tile);
         } else {
            logConversionResult(var10);
         }
      }

      return var5;
   }

   private static List<TilesetWriter.Tile> batchTexturedL1(
      List<ShpTo3dTilesCli.BuildingRecord> var0, List<TilesetWriter.Tile> var1, ShpTo3dTilesCli.Options var2, ShpTo3dTilesCli.CoordinateFrame var3, Path var4
   ) throws Exception {
      HashMap var5 = new HashMap();

      for (ShpTo3dTilesCli.BuildingRecord var7 : var0) {
         var5.put(String.format(Locale.ROOT, "glb/b%012d_%06d_l2.glb", var7.id, var7.ordinal), var7);
      }

      ArrayList var8 = new ArrayList();
      partitionTexturedL1(new ArrayList<>(var1), var5, var2, var3, var4, var8);
      System.out
         .printf("[L1 batching] buildings=%d batches=%d maxBuildings=%d maxTriangles=20000 maxSpan=512m%n", var1.size(), var8.size(), var2.tileMaxContents);
      return var8;
   }

   private static List<TilesetWriter.Tile> addRegionalOverview(
      List<ShpTo3dTilesCli.BuildingRecord> var0, List<TilesetWriter.Tile> var1, ShpTo3dTilesCli.Options var2, ShpTo3dTilesCli.CoordinateFrame var3, Path var4
   ) throws Exception {
      if (var1 != null && !var1.isEmpty() && var0 != null && !var0.isEmpty()) {
         ArrayList var5 = new ArrayList(var0);
         ArrayList var6 = new ArrayList(var1);
         ArrayList var7 = new ArrayList();
         int[] var8 = new int[]{0};
         partitionRegionalOverview(var5, var6, var2, var3, var4, var7, var8);
         return var7.isEmpty() ? var1 : var7;
      } else {
         return var1;
      }
   }

   private static void partitionRegionalOverview(
      List<ShpTo3dTilesCli.BuildingRecord> var0,
      List<TilesetWriter.Tile> var1,
      ShpTo3dTilesCli.Options var2,
      ShpTo3dTilesCli.CoordinateFrame var3,
      Path var4,
      List<TilesetWriter.Tile> var5,
      int[] var6
   ) throws Exception {
      if (!var1.isEmpty()) {
         double[] var7 = null;

         for (TilesetWriter.Tile var9 : var1) {
            double[] var10 = var9.localBoxCenter();
            double[] var11 = var9.localBoxHalf();
            double[] var12 = new double[]{
               var10[0] - var11[0], var10[1] - var11[1], var10[2] - var11[2], var10[0] + var11[0], var10[1] + var11[1], var10[2] + var11[2]
            };
            var7 = var7 == null ? var12 : unionBox(var7, var12);
         }

         double var24 = Math.max(var7[3] - var7[0], var7[4] - var7[1]);
         boolean var25 = var1.size() > Math.max(32, var2.tileMaxContents * 8) || var24 > 8192.0;
         if (var25 && var1.size() > 1) {
            int var27 = var7[3] - var7[0] >= var7[4] - var7[1] ? 0 : 1;
            var1.sort(Comparator.<TilesetWriter.Tile>comparingDouble(var1x -> var1x.localBoxCenter()[var27]).thenComparing(TilesetWriter.Tile::uri));
            int var29 = var1.size() / 2;
            double var30 = ((TilesetWriter.Tile)var1.get(var29)).localBoxCenter()[var27];
            ArrayList var34 = new ArrayList(var1.subList(0, var29));
            ArrayList var37 = new ArrayList(var1.subList(var29, var1.size()));
            ArrayList var40 = new ArrayList();
            ArrayList var43 = new ArrayList();

            for (ShpTo3dTilesCli.BuildingRecord var46 : var0) {
               double var21 = recordCentroid(var46, var27, var3);
               (var21 < var30 ? var40 : var43).add(var46);
            }

            partitionRegionalOverview(var40, var34, var2, var3, var4, var5, var6);
            partitionRegionalOverview(var43, var37, var2, var3, var4, var5, var6);
         } else {
            Mesh var26 = new Mesh();
            double[] var28 = null;
            BuildingExtruder.Profile var13 = var2.toProfile(LodProfile.L0_OVERVIEW);
            int var14 = 0;

            for (ShpTo3dTilesCli.BuildingRecord var16 : var0) {
               try {
                  BuildingFootprintDTO var17 = copyForLod(var16.dto, LodProfile.L0_OVERVIEW);
                  List var18 = BuildingExtruder.extrude(var17, var13);
                  if (!var18.isEmpty()) {
                     double[] var19 = shiftAndBox(var18, var3);
                     var28 = var28 == null ? var19 : unionBox(var28, var19);
                     appendSegments(var26, var18, var16.dto.getProperties());
                     var14 = var26.triangleCount();
                     if (var14 > 60000) {
                        break;
                     }
                  }
               } catch (Exception var23) {
               }
            }

            if (var26.triangleCount() != 0 && var28 != null) {
               for (TilesetWriter.Tile var35 : var1) {
                  double[] var38 = var35.localBoxCenter();
                  double[] var41 = var35.localBoxHalf();
                  var28 = unionBox(
                     var28,
                     new double[]{var38[0] - var41[0], var38[1] - var41[1], var38[2] - var41[2], var38[0] + var41[0], var38[1] + var41[1], var38[2] + var41[2]}
                  );
               }

               ObjToGlb.Options var33 = new ObjToGlb.Options();
               var33.inputAxis = ObjToGlb.Axis.Z;
               var33.outputAxis = ObjToGlb.Axis.Y;
               var33.generateNormalsIfMissing = true;
               var33.forceFlat = true;
               var33.indexVertices = true;
               var33.doubleSided = false;
               var33.generator = "ShpTo3DTiles regional overview L0";
               String var36 = String.format(Locale.ROOT, "region_%06d_l0.glb", Integer.valueOf((int)(var6[0]++)));
               Path var39 = var4.resolve(var36);
               Path var42 = var4.resolve(var36 + ".tmp");
               Files.write(var42, ObjToGlb.convert(var26, var33));
               moveReplacing(var42, var39);
               String var44 = "glb/" + var36;
               double var20 = Math.max(120.0, Math.min(500.0, var24 * 0.15));
               TilesetWriter.Tile var22 = toTile(var44, var28, List.of(new TilesetWriter.LodContent(var44, var20)));
               var5.add(new TilesetWriter.Tile(var44, var22.localBoxCenter(), var22.localBoxHalf(), var22.lods(), var1));
            }
         }
      }
   }

   private static double recordCentroid(ShpTo3dTilesCli.BuildingRecord var0, int var1, ShpTo3dTilesCli.CoordinateFrame var2) {
      try {
         Coordinate var3 = new WKTReader().read(var0.dto.getWkt()).getCentroid().getCoordinate();
         double[] var4 = var2.toLocalEnu(var3.x, var3.y, 0.0);
         return var4[var1];
      } catch (Exception var5) {
         return 0.0;
      }
   }

   private static void partitionTexturedL1(
      List<TilesetWriter.Tile> var0,
      Map<String, ShpTo3dTilesCli.BuildingRecord> var1,
      ShpTo3dTilesCli.Options var2,
      ShpTo3dTilesCli.CoordinateFrame var3,
      Path var4,
      List<TilesetWriter.Tile> var5
   ) throws Exception {
      if (!var0.isEmpty()) {
         double[] var6 = null;

         for (TilesetWriter.Tile var8 : var0) {
            double[] var9 = var8.localBoxCenter();
            double[] var10 = var8.localBoxHalf();
            double[] var11 = new double[]{
               var9[0] - var10[0], var9[1] - var10[1], var9[2] - var10[2], var9[0] + var10[0], var9[1] + var10[1], var9[2] + var10[2]
            };
            var6 = var6 == null ? var11 : unionBox(var6, var11);
         }

         boolean var15 = var0.size() > var2.tileMaxContents || Math.max(var6[3] - var6[0], var6[4] - var6[1]) > 512.0;
         Mesh var16 = new Mesh();
         if (!var15 || var0.size() == 1) {
            for (TilesetWriter.Tile var20 : var0) {
               ShpTo3dTilesCli.BuildingRecord var23 = (ShpTo3dTilesCli.BuildingRecord)var1.get(var20.uri());
               if (var23 == null) {
                  throw new IllegalStateException("Missing L1 record: " + var20.uri());
               }

               List var12 = BuildingExtruder.extrude(copyForLod(var23.dto, LodProfile.L1_SIMPLE), var2.toProfile(LodProfile.L1_SIMPLE));
               if (var12.isEmpty()) {
                  throw new IllegalStateException("Empty L1: " + var20.uri());
               }

               var6 = unionBox(var6, shiftAndBox(var12, var3));
               appendSegments(var16, var12, var23.dto.getProperties());
            }

            var15 = var15 || var16.triangleCount() > 20000 || Math.max(var6[3] - var6[0], var6[4] - var6[1]) > 512.0;
         }

         if (var15 && var0.size() > 1) {
            int var19 = var6[3] - var6[0] >= var6[4] - var6[1] ? 0 : 1;
            var0.sort(Comparator.<TilesetWriter.Tile>comparingDouble(var1x -> var1x.localBoxCenter()[var19]).thenComparing(TilesetWriter.Tile::uri));
            int var22 = var0.size() / 2;
            partitionTexturedL1(new ArrayList<>(var0.subList(0, var22)), var1, var2, var3, var4, var5);
            partitionTexturedL1(new ArrayList<>(var0.subList(var22, var0.size())), var1, var2, var3, var4, var5);
         } else {
            ObjToGlb.Options var18 = new ObjToGlb.Options();
            var18.inputAxis = ObjToGlb.Axis.Z;
            var18.outputAxis = ObjToGlb.Axis.Y;
            var18.generateNormalsIfMissing = true;
            var18.forceFlat = true;
            var18.indexVertices = true;
            var18.doubleSided = false;
            String var21 = String.format(Locale.ROOT, "region_%06d_l1.glb", var5.size());
            Path var24 = var4.resolve(var21);
            Path var25 = var4.resolve(var21 + ".tmp");
            Files.write(var25, ObjToGlb.convert(var16, var18));
            moveReplacing(var25, var24);
            String var13 = "glb/" + var21;
            TilesetWriter.Tile var14 = toTile(var13, var6, List.of(new TilesetWriter.LodContent(var13, 18.0)));
            var5.add(new TilesetWriter.Tile(var13, var14.localBoxCenter(), var14.localBoxHalf(), var14.lods(), var0));
         }
      }
   }

   private static List<ShpTo3dTilesCli.BuildingRecord> spatialOrder(List<ShpTo3dTilesCli.BuildingRecord> var0) {
      ArrayList<PositionedRecord> var1 = new ArrayList<>(var0.size());
      double var2 = Double.POSITIVE_INFINITY;
      double var4 = Double.POSITIVE_INFINITY;
      double var6 = Double.NEGATIVE_INFINITY;
      double var8 = Double.NEGATIVE_INFINITY;
      WKTReader var10 = new WKTReader();

      for (ShpTo3dTilesCli.BuildingRecord var12 : var0) {
         double var13 = 0.0;
         double var15 = 0.0;

         try {
            Coordinate var17 = var10.read(var12.dto.getWkt()).getCentroid().getCoordinate();
            var13 = var17.x;
            var15 = var17.y;
         } catch (Exception var22) {
         }

         var1.add(new ShpTo3dTilesCli.PositionedRecord(var12, var13, var15));
         var2 = Math.min(var2, var13);
         var4 = Math.min(var4, var15);
         var6 = Math.max(var6, var13);
         var8 = Math.max(var8, var15);
      }

      double var23 = var2;
      double var24 = var4;
      double var25 = Math.max(1.0E-9, var6 - var2);
      double var26 = Math.max(1.0E-9, var8 - var4);
      var1.sort(Comparator.comparingLong(var8x -> {
         int var9 = (int)Math.round(65535.0 * (var8x.x - var23) / var25);
         int var10x = (int)Math.round(65535.0 * (var8x.y - var24) / var26);
         return morton16(var9, var10x);
      }));
      ArrayList<BuildingRecord> var19 = new ArrayList<>(var0.size());

      for (ShpTo3dTilesCli.PositionedRecord var21 : var1) {
         var19.add(var21.record);
      }

      return var19;
   }

   private static long morton16(int var0, int var1) {
      long var2 = 0L;

      for (int var4 = 0; var4 < 16; var4++) {
         var2 |= (var0 >>> var4 & 1L) << var4 * 2;
         var2 |= (var1 >>> var4 & 1L) << var4 * 2 + 1;
      }

      return var2;
   }

   private static ShpTo3dTilesCli.ConversionResult convertOptimizedBatch(
      List<ShpTo3dTilesCli.BuildingRecord> var0, int var1, ShpTo3dTilesCli.Options var2, ShpTo3dTilesCli.CoordinateFrame var3, Path var4
   ) {
      try {
         LodProfile[] var5 = var2.buildingLodChain
            ? new LodProfile[]{LodProfile.L1_SIMPLE, LodProfile.L2_STANDARD, LodProfile.L3_DETAIL}
            : new LodProfile[]{var2.lod};
         double[] var6 = var2.buildingLodChain ? new double[]{12.0, 4.0, 0.0} : new double[]{0.0};
         ArrayList var7 = new ArrayList();
         double[] var8 = null;

         for (int var9 = 0; var9 < var5.length; var9++) {
            ShpTo3dTilesCli.BatchMesh var10 = buildBatchMesh(var0, var2, var5[var9], var3);
            if (var10.mesh.triangleCount() != 0) {
               var8 = var8 == null ? var10.box : unionBox(var8, var10.box);
               ObjToGlb.Options var11 = new ObjToGlb.Options();
               var11.inputAxis = ObjToGlb.Axis.Z;
               var11.outputAxis = ObjToGlb.Axis.Y;
               var11.generateNormalsIfMissing = true;
               var11.forceFlat = true;
               var11.indexVertices = true;
               var11.doubleSided = false;
               var11.generator = "ShpTo3DTiles optimized indexed batch";
               byte[] var12 = ObjToGlb.convert(var10.mesh, var11);
               byte[] var13 = var12;
               String var14 = var2.buildingLodChain ? "_l" + (var9 + 1) : "";
               String var15 = String.format(Locale.ROOT, "tile_%06d%s.glb", var1, var14);
               Path var16 = var4.resolve(var15);
               Path var17 = var16.resolveSibling(var15 + ".tmp");
               Files.write(var17, var13);
               moveReplacing(var17, var16);
               var7.add(new TilesetWriter.LodContent("glb/" + var15, var6[var9]));
            }
         }

         if (!var7.isEmpty() && var8 != null) {
            String var19 = ((TilesetWriter.LodContent)var7.get(0)).uri();
            TilesetWriter.Tile var20 = var2.buildingLodChain ? toTile(var19, var8, var7) : toTile(var19, var8);
            return new ShpTo3dTilesCli.ConversionResult(var1, var20, null);
         } else {
            return new ShpTo3dTilesCli.ConversionResult(var1, null, "empty optimized batch");
         }
      } catch (Exception var18) {
         return new ShpTo3dTilesCli.ConversionResult(var1, null, var18.getClass().getSimpleName() + ": " + var18.getMessage());
      }
   }

   private static ShpTo3dTilesCli.BatchMesh buildBatchMesh(
      List<ShpTo3dTilesCli.BuildingRecord> var0, ShpTo3dTilesCli.Options var1, LodProfile var2, ShpTo3dTilesCli.CoordinateFrame var3
   ) throws Exception {
      Mesh var4 = new Mesh();
      double[] var5 = null;
      BuildingExtruder.Profile var6 = var1.toProfile(var2);

      for (ShpTo3dTilesCli.BuildingRecord var8 : var0) {
         try {
            BuildingFootprintDTO var9 = copyForLod(var8.dto, var2);
            List var10 = BuildingExtruder.extrude(var9, var6);
            if (!var10.isEmpty()) {
               double[] var11 = shiftAndBox(var10, var3);
               var5 = var5 == null ? var11 : unionBox(var5, var11);
               appendSegments(var4, var10, var8.dto.getProperties());
            }
         } catch (Exception var12) {
             GeometryDiagnostics.log(
                "ERROR stage=building-extrusion buildingId=" + var8.id + " shpOrdinal=" + (var8.ordinal + 1)
                   + " " + var8.dto.getDiagnosticContext() + " reason=" + var12.getClass().getSimpleName() + ": " + var12.getMessage()
             );
            System.err
               .printf(
                  Locale.ROOT,
                  "[ShpTo3dTilesCli] skipping invalid building id=%d ordinal=%d: %s: %s%n",
                  var8.id,
                  var8.ordinal,
                  var12.getClass().getSimpleName(),
                  var12.getMessage()
               );
         }
      }

      return new ShpTo3dTilesCli.BatchMesh(var4, var5 == null ? new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0} : var5);
   }

   private static void appendSegments(Mesh var0, List<BuildingExtruder.Segment> var1, Map<String, Object> var2) {
      appendSegments(var0, var1, var2, var0.featureProperties.size(), -1, 0.0, 3.0);
   }

   private static void appendSegments(Mesh var0, List<BuildingExtruder.Segment> var1, Map<String, Object> var2, int var3, int var4, double var5, double var7) {
      if (var0.featureProperties.size() <= var3) {
         var0.featureProperties.add(var2);
      }

      for (BuildingExtruder.Segment var10 : var1) {
         int var11 = var0.positions.size();

         for (double[] var13 : var10.verts) {
            var0.positions.add(new float[]{(float)var13[0], (float)var13[1], (float)var13[2]});
         }

         List var15 = var0.groupFor(var10.material);

         for (int[] var14 : var10.faces) {
            if (var14.length >= 3) {
               var15.add(new Mesh.Tri(var11 + var14[0] - 1, var11 + var14[1] - 1, var11 + var14[2] - 1, -1, -1, -1, var3, var4, var5, var7));
            }
         }
      }
   }

   private static double[] unionBox(double[] var0, double[] var1) {
      return new double[]{
         Math.min(var0[0], var1[0]),
         Math.min(var0[1], var1[1]),
         Math.min(var0[2], var1[2]),
         Math.max(var0[3], var1[3]),
         Math.max(var0[4], var1[4]),
         Math.max(var0[5], var1[5])
      };
   }

   private static ShpTo3dTilesCli.ConversionResult convertOne(
      ShpTo3dTilesCli.BuildingRecord var0, ShpTo3dTilesCli.Options var1, ShpTo3dTilesCli.CoordinateFrame var2, Path var3, Path var4, Path var5
   ) {
      return var1.buildingLodChain ? convertOneChain(var0, var1, var2, var3, var4, var5) : convertOneSingle(var0, var1, var2, var3, var4, var5);
   }

   private static ShpTo3dTilesCli.ConversionResult convertOneSingle(
      ShpTo3dTilesCli.BuildingRecord var0, ShpTo3dTilesCli.Options var1, ShpTo3dTilesCli.CoordinateFrame var2, Path var3, Path var4, Path var5
   ) {
      Path var6 = null;
      Path var7 = null;

      try {
         BuildingFootprintDTO var8 = copyForLod(var0.dto, var1.lod);
         BuildingExtruder.Profile var9 = var1.toProfile(var1.lod);
         List var10 = BuildingExtruder.extrude(var8, var9);
         if (var10.isEmpty()) {
            return new ShpTo3dTilesCli.ConversionResult(var0.id, null, "empty extrusion");
         }

         String var11 = String.format(Locale.ROOT, "b%012d_%06d", var0.id, var0.ordinal);
         var6 = var3.resolve(var11 + ".obj");
         var7 = var4.resolve(var11 + ".glb");
         Path var12 = var5.resolve(var11 + ".glb");
         Path var13 = var12.resolveSibling(var12.getFileName() + ".done");
         String var14 = var1.resumeKey(var0);
         if (var1.resume && Files.exists(var13) && Files.size(var12) > 0L && var14.equals(Files.readString(var13, StandardCharsets.UTF_8).trim())) {
            if (!var1.keepIntermediate) {
               Files.deleteIfExists(var6);
               Files.deleteIfExists(var7);
            }

            return new ShpTo3dTilesCli.ConversionResult(var0.id, toTile("glb/" + var12.getFileName(), shiftAndBox(var10, var2)), null);
         } else {
            double[] var15 = shiftAndBox(var10, var2);
            WhiteModelObjWriter.writeObj(var6, var0.id, var10);
            String var16 = var1.textureStem(var8);
            int var17 = var8.getFloors() == null ? 1 : Math.max(1, var8.getFloors());
            double var18 = var8.getJzgd() / var17;
            if (var1.textures) {
               new ObjToGlbConverter()
                  .convertRepeatingWithProperties(
                     var6,
                     var7,
                     "textures/" + BuildingTextureSelector.outputName(var16),
                     var15[2],
                     var18,
                     BuildingTextureSelector.boundaryOffset(var16),
                     List.of(var8.getProperties())
                  );
            } else {
               new ObjToGlbConverter().convertWithProperties(var6, var7, null, var15[2], var18, 4.0, List.of(var8.getProperties()));
            }

            Path var20 = var12.resolveSibling(var12.getFileName() + ".tmp");
            Files.write(var20, Files.readAllBytes(var7));
            moveReplacing(var20, var12);
            Files.writeString(var13, var14 + "\n", StandardCharsets.UTF_8);
            if (!var1.keepIntermediate) {
               Files.deleteIfExists(var6);
               Files.deleteIfExists(var7);
            }

            return new ShpTo3dTilesCli.ConversionResult(var0.id, toTile("glb/" + var12.getFileName(), var15), null);
         }
      } catch (Exception var21) {
         GeometryDiagnostics.log(
            "ERROR stage=feature-conversion buildingId=" + var0.id + " shpOrdinal=" + (var0.ordinal + 1)
               + " " + var0.dto.getDiagnosticContext() + " reason=" + var21.getClass().getSimpleName() + ": " + var21.getMessage()
         );
         return new ShpTo3dTilesCli.ConversionResult(var0.id, null, var21.getClass().getSimpleName() + ": " + var21.getMessage());
      }
   }

   private static ShpTo3dTilesCli.ConversionResult convertOneChain(
      ShpTo3dTilesCli.BuildingRecord var0, ShpTo3dTilesCli.Options var1, ShpTo3dTilesCli.CoordinateFrame var2, Path var3, Path var4, Path var5
   ) {
      try {
         String var6 = String.format(Locale.ROOT, "b%012d_%06d", var0.id, var0.ordinal);
         ArrayList var7 = new ArrayList();
         double[] var8 = null;
         LodProfile[] var9 = new LodProfile[]{LodProfile.L1_SIMPLE, LodProfile.L2_STANDARD, LodProfile.L3_DETAIL};
         double[] var10 = new double[]{12.0, 4.0, 0.0};

         for (int var11 = var1.textures && !var1.legacyPerBuilding ? 1 : 0; var11 < var9.length; var11++) {
            LodProfile var12 = var9[var11];
            BuildingFootprintDTO var13 = copyForLod(var0.dto, var12);
            List var14 = BuildingExtruder.extrude(var13, var1.toProfile(var12));
            if (!var14.isEmpty()) {
               double[] var15 = shiftAndBox(var14, var2);
               if (var8 == null) {
                  var8 = var15;
               } else {
                  for (int var16 = 0; var16 < 3; var16++) {
                     var8[var16] = Math.min(var8[var16], var15[var16]);
                     var8[var16 + 3] = Math.max(var8[var16 + 3], var15[var16 + 3]);
                  }
               }

               Path var25 = var3.resolve(var6 + "_l" + (var11 + 1) + ".obj");
               Path var17 = var4.resolve(var6 + "_l" + (var11 + 1) + ".glb");
               Path var18 = var5.resolve(var6 + "_l" + (var11 + 1) + ".glb");
               Path var19 = var18.resolveSibling(var18.getFileName() + ".done");
               String var20 = var1.resumeKey(var0) + "|chain|" + var12.name();
               if (!var1.resume || !Files.exists(var19) || Files.size(var18) <= 0L || !var20.equals(Files.readString(var19, StandardCharsets.UTF_8).trim())) {
                  WhiteModelObjWriter.writeObj(var25, var0.id, var14);
                  double var21 = var13.getJzgd() / var13.getFloors().intValue();
                  if (var1.textures && var12 != LodProfile.L1_SIMPLE) {
                     String var23 = var1.textureStem(var13);
                     new ObjToGlbConverter()
                        .convertRepeatingWithProperties(
                           var25,
                           var17,
                           "textures/" + BuildingTextureSelector.outputName(var23),
                           var15[2],
                           var21,
                           BuildingTextureSelector.boundaryOffset(var23),
                           List.of(var13.getProperties())
                        );
                  } else {
                     new ObjToGlbConverter().convertWithProperties(var25, var17, null, var15[2], var21, 4.0, List.of(var13.getProperties()));
                  }

                  Path var26 = var18.resolveSibling(var18.getFileName() + ".tmp");
                  Files.write(var26, Files.readAllBytes(var17));
                  moveReplacing(var26, var18);
                  Files.writeString(var19, var20 + "\n", StandardCharsets.UTF_8);
                  if (!var1.keepIntermediate) {
                     Files.deleteIfExists(var25);
                     Files.deleteIfExists(var17);
                  }
               }

               if (!var1.keepIntermediate) {
                  Files.deleteIfExists(var25);
                  Files.deleteIfExists(var17);
               }

               var7.add(new TilesetWriter.LodContent("glb/" + var18.getFileName(), var10[var11]));
            }
         }

         return !var7.isEmpty() && var8 != null
            ? new ShpTo3dTilesCli.ConversionResult(var0.id, toTile(((TilesetWriter.LodContent)var7.get(0)).uri(), var8, var7), null)
            : new ShpTo3dTilesCli.ConversionResult(var0.id, null, "empty LOD chain");
      } catch (Exception var24) {
         GeometryDiagnostics.log(
            "ERROR stage=lod-chain buildingId=" + var0.id + " shpOrdinal=" + (var0.ordinal + 1)
               + " " + var0.dto.getDiagnosticContext() + " reason=" + var24.getClass().getSimpleName() + ": " + var24.getMessage()
         );
         return new ShpTo3dTilesCli.ConversionResult(var0.id, null, var24.getClass().getSimpleName() + ": " + var24.getMessage());
      }
   }

   private static ShpTo3dTilesCli.AtlasCell atlasCell(String var0) {
      String[] var1 = new String[]{"db_sumo_bui_001", "dise_G1_5", "dise_G1_dx_01", "dise_G1_dx_02", "dise_G1_dx_05", "dise_G1_dx_06"};
      int var2 = 0;

      for (int var3 = 0; var3 < var1.length; var3++) {
         if (var1[var3].equals(var0)) {
            var2 = var3;
            break;
         }
      }

      int var6 = var2 % 3 * 512 + 8;
      int var4 = var2 / 3 * 512 + 8;
      short var5 = 496;
      return new ShpTo3dTilesCli.AtlasCell((var5 - 1.0) / 1536.0, (var5 - 1.0) / 1024.0, (var6 + 0.5) / 1536.0, (1024 - var4 - var5 + 0.5) / 1024.0);
   }

   private static void writeTextureAtlas(Path var0, int var1) throws IOException {
      BufferedImage var2 = new BufferedImage(1536, 1024, 1);
      Graphics2D var3 = var2.createGraphics();
      var3.setColor(Color.WHITE);
      var3.fillRect(0, 0, var2.getWidth(), var2.getHeight());
      String[] var4 = new String[]{"db_sumo_bui_001", "dise_G1_5", "dise_G1_dx_01", "dise_G1_dx_02", "dise_G1_dx_05", "dise_G1_dx_06"};

      for (int var5 = 0; var5 < var4.length; var5++) {
         try (InputStream var6 = ShpTo3dTilesCli.class.getResourceAsStream("/textures/" + var4[var5] + ".jpg")) {
            if (var6 != null) {
               BufferedImage var7 = ImageIO.read(var6);
               int var8 = darkTopBorderHeight(var7);
               int var9 = var5 % 3 * 512;
               int var10 = var5 / 3 * 512;
               int var11 = var9 + 8;
               int var12 = var10 + 8;
               short var13 = 496;
               var3.drawImage(var7, var11, var12, var11 + var13, var12 + var13, 0, var8, var7.getWidth(), var7.getHeight(), null);
               var3.drawImage(var7, var9, var12, var11, var12 + var13, 0, var8, 1, var7.getHeight(), null);
               var3.drawImage(var7, var11 + var13, var12, var9 + 512, var12 + var13, var7.getWidth() - 1, var8, var7.getWidth(), var7.getHeight(), null);
               var3.drawImage(var7, var11, var10, var11 + var13, var12, 0, var8, var7.getWidth(), var8 + 1, null);
               var3.drawImage(var7, var11, var12 + var13, var11 + var13, var10 + 512, 0, var7.getHeight() - 1, var7.getWidth(), var7.getHeight(), null);
               var3.setColor(new Color(var7.getRGB(0, var8)));
               var3.fillRect(var9, var10, 8, 8);
               var3.setColor(new Color(var7.getRGB(var7.getWidth() - 1, var8)));
               var3.fillRect(var11 + var13, var10, 8, 8);
               var3.setColor(new Color(var7.getRGB(0, var7.getHeight() - 1)));
               var3.fillRect(var9, var12 + var13, 8, 8);
               var3.setColor(new Color(var7.getRGB(var7.getWidth() - 1, var7.getHeight() - 1)));
               var3.fillRect(var11 + var13, var12 + var13, 8, 8);
            }
         }
      }

      var3.dispose();
      if (var1 != var2.getWidth()) {
         BufferedImage var16 = new BufferedImage(var1, var1 * 2 / 3, 1);
         Graphics2D var17 = var16.createGraphics();
         var17.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
         var17.drawImage(var2, 0, 0, var16.getWidth(), var16.getHeight(), null);
         var17.dispose();
         var2 = var16;
      }

      if (!ImageIO.write(var2, "jpg", var0.toFile())) {
         throw new IOException("JPEG writer unavailable");
      }

      System.out.printf("[textures] atlas dimensions=%dx%d%n", var2.getWidth(), var2.getHeight());
      System.out.println("[textures] wrote shared atlas=" + var0.toAbsolutePath());
   }

   private static int darkTopBorderHeight(BufferedImage var0) {
      int var1 = Math.max(0, var0.getHeight() / 2);
      double var2 = rowLuminance(var0, var1);
      int var4 = Math.max(1, var0.getHeight() / 8);
      int var5 = 0;

      while (var5 < var4 && rowLuminance(var0, var5) < var2 * 0.9) {
         var5++;
      }

      return var5;
   }

   private static double rowLuminance(BufferedImage var0, int var1) {
      long var2 = 0L;
      int var4 = Math.min(64, var0.getWidth());

      for (int var5 = 0; var5 < var4; var5++) {
         int var6 = (int)((long)var5 * var0.getWidth() / var4);
         int var7 = var0.getRGB(var6, var1);
         int var8 = var7 >>> 16 & 0xFF;
         int var9 = var7 >>> 8 & 0xFF;
         int var10 = var7 & 0xFF;
         var2 += 2126L * var8 + 7152L * var9 + 722L * var10;
      }

      return var2 / (var4 * 10000.0);
   }

   private static void logConversionResult(ShpTo3dTilesCli.ConversionResult var0) {
      if (var0.error != null) {
         System.err.printf(Locale.ROOT, "[ShpTo3dTilesCli] feature %d failed: %s%n", var0.id, var0.error);
      }
   }

   private static void moveReplacing(Path var0, Path var1) throws IOException {
      try {
         Files.move(var0, var1, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException var3) {
         Files.move(var0, var1, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static BuildingFootprintDTO copyForLod(BuildingFootprintDTO var0, LodProfile var1) throws Exception {
      BuildingFootprintDTO var2 = new BuildingFootprintDTO();
      var2.setBid(var0.getBid());
      var2.setJzgd(var0.getJzgd());
      var2.setBaseZ(var0.getBaseZ());
      var2.setBldUsage(var0.getBldUsage());
      var2.setFloors(var0.getFloors());
      var2.setProperties(var0.getProperties());
      var2.setDiagnosticContext(var0.getDiagnosticContext());
      Geometry var3 = new WKTReader().read(var0.getWkt());
      Geometry var4 = var1.simplify(var3);
      var2.setWkt(new WKTWriter().write(var4));
      if (var1 == LodProfile.L0_OVERVIEW) {
         var2.setJzgd(Math.max(1.0, var0.getJzgd() * 0.65));
      }

      return var2;
   }

   private static String featureDiagnosticContext(SimpleFeature feature, int sourceRow, String idField, Object idValue) {
      StringBuilder context = new StringBuilder(256)
         .append("shpRow=").append(sourceRow)
         .append(" featureId=").append(safeLogValue(feature.getID()))
         .append(" geometryField=").append(safeLogValue(feature.getDefaultGeometryProperty().getName().getLocalPart()))
         .append(" idField=").append(safeLogValue(idField))
         .append(" idValue=").append(safeLogValue(idValue))
         .append(" fields={");
      boolean first = true;
      for (Property property : feature.getProperties()) {
         Object value = property.getValue();
         if (value instanceof Geometry) {
            continue;
         }
         if (!first) {
            context.append(", ");
         }
         context.append(property.getName().getLocalPart()).append('=').append(safeLogValue(value));
         first = false;
      }
      return context.append('}').toString();
   }

   private static String safeLogValue(Object value) {
      if (value == null) {
         return "<null>";
      }
      String text = String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
      return text.length() <= 512 ? text : text.substring(0, 509) + "...";
   }

   private static List<String> parseMetadataFields(String var0) {
      LinkedHashMap var1 = new LinkedHashMap();

      for (String var5 : var0.split("[,，;；]")) {
         String var6 = var5.trim();
         if (!var6.isEmpty()) {
            if (var6.equals("extensions") || var6.equals("extras")) {
               throw new IllegalArgumentException("Reserved batch-table field: " + var6);
            }

            var1.putIfAbsent(var6.toLowerCase(Locale.ROOT), var6);
         }
      }

      return new ArrayList<>(var1.values());
   }

   private static List<TilesetWriter.Tile> convertWkBldGroups(
      List<ShpTo3dTilesCli.BuildingRecord> var0, ShpTo3dTilesCli.Options var1, ShpTo3dTilesCli.CoordinateFrame var2, Path var3
   ) throws Exception {
      LinkedHashMap<String, List<BuildingRecord>> var4 = new LinkedHashMap<>();
      int var5 = 0;

      for (ShpTo3dTilesCli.BuildingRecord var7 : var0) {
         Object var8 = var7.dto.getProperties().get("wkBldId");
         String var9 = var8 != null && !var8.toString().isBlank() ? var8.toString().trim() : "<missing>#" + var5++;
         var4.computeIfAbsent(var9, var0x -> new ArrayList<>()).add(var7);
      }

      Path var39 = var3.resolve("textures");
      String var40 = "wkBldId-layered-repeat.jpg";
      if (var1.textures) {
         writeLayeredRepeatTexture(var39.resolve(var40), var1.layeredTexture);
      }

      List<LodProfile> var41 = var1.buildingLodChain ? List.of(LodProfile.L1_SIMPLE, LodProfile.L2_STANDARD, LodProfile.L3_DETAIL) : List.of(var1.lod);
      double[] var42 = var1.buildingLodChain ? new double[]{12.0, 4.0, 0.0} : new double[]{0.0};
      ArrayList<TilesetWriter.Tile> var10 = new ArrayList<>();
      List<List<BuildingRecord>> var11 = spatialRegionBatches(var4, 128);
      int var12 = 0;
      Path var13 = var1.outDir.resolve("wkBldId-merge.log");
      Files.writeString(var13, "[wkBldId merge] generated model groups\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

      for (List<BuildingRecord> var15 : var11) {
         String var16 = var15.stream().map(var0x -> String.valueOf(var0x.dto.getProperties().get("wkBldId"))).distinct().collect(Collectors.joining(","));
         ArrayList<TilesetWriter.LodContent> var17 = new ArrayList<>();
         double[] var18 = null;

         for (int var19 = 0; var19 < var41.size(); var19++) {
            LodProfile var20 = (LodProfile)var41.get(var19);
            Mesh var21 = new Mesh();
            double[] var22 = null;
            int var23 = 0;
            int var24 = 0;
            ArrayList<Map<String, Object>> var25 = new ArrayList<>();

            for (ShpTo3dTilesCli.BuildingRecord var27 : var15) {
               BuildingFootprintDTO var28 = copyForLod(var27.dto, var20);
               List<BuildingExtruder.Segment> var29 = BuildingExtruder.extrude(var28, var1.toProfile(var20));
               if (!var29.isEmpty()) {
                  int var30 = var25.size();
                  var25.add(new LinkedHashMap<>(var27.dto.getProperties()));
                  double[] var31 = shiftAndBox(var29, var2);
                  var22 = var22 == null ? var31 : unionBox(var22, var31);
                  double var32 = var28.getJzgd() / Math.max(1, var28.getFloors() == null ? 1 : var28.getFloors());
                  boolean var34 = var20 != LodProfile.L1_SIMPLE && var1.textures && var28.getFloors() != null && var28.getFloors() > var1.floorLineThreshold;
                  boolean var35 = var34;
                  if (var34) {
                     var24++;
                  } else {
                     var23++;
                  }

                  for (BuildingExtruder.Segment var37 : var29) {
                     String var38 = "wall".equals(var37.material)
                        ? (var35 ? "wall_layered" : "wall_white")
                        : ("shaft".equals(var37.material) ? "shaft_white" : "parapet_white");
                     if ("wall".equals(var37.material) && var35 && var32 > 0.0) {
                        appendLayeredWall(var21, var37, var38, var31[2], var32, var28.getFloors() == null ? 1 : var28.getFloors(), var30);
                     } else {
                        appendMergedSegments(var21, var37, var38, var31[2], var32, var30);
                     }
                  }

                  var30++;
               }
            }

            if (var21.triangleCount() != 0 && var22 != null) {
               var21.featureProperties.addAll(var25);
               System.out
                  .printf(
                     Locale.ROOT,
                     "[regional merge] batch=%d buildings=%d groups=%d L%d facadeCells white=%d layered=%d threshold=%d%n",
                     var12,
                     var15.size(),
                     var15.stream().map(var0x -> String.valueOf(var0x.dto.getProperties().get("wkBldId"))).distinct().count(),
                     var19 + 1,
                     var23,
                     var24,
                     var1.floorLineThreshold
                  );
               ObjToGlb.Options var45 = new ObjToGlb.Options();
               var45.inputAxis = ObjToGlb.Axis.Z;
               var45.outputAxis = ObjToGlb.Axis.Y;
               var45.generateNormalsIfMissing = true;
               var45.forceFlat = true;
               var45.indexVertices = false;
               var45.doubleSided = true;
               var45.generator = "ShpTo3DTiles wkBldId merged LOD";
               if (var1.textures) {
                  String var46 = "textures/" + BuildingTextureSelector.outputName(var1.whiteTexture);
                  String var48 = "textures/" + var40;
                  var45.baseColorTextureUri = var46;
                  var45.textureUriByMaterial = Map.of(
                     "wall_white", var46, "wall_layered", var48, "roof_white", var46, "parapet_white", var46, "shaft_white", var46
                  );
                  var45.generateUv = true;
                  var45.uvUpMin = 0.0;
                  var45.uvFloorHeight = 3.0;
                  var45.uvTileWidth = 4.0;
                  var45.texturedMaterials = Set.of("wall_white", "wall_layered", "roof_white", "parapet_white", "shaft_white");
                  var45.atlasColumns = 1;
                  var45.atlasRows = 1;
                  var45.atlasWidth = 1;
                  var45.atlasHeight = 1;
                  var45.atlasGutter = 0;
               }

               String var47 = var1.buildingLodChain ? "_l" + (var19 + 1) : "";
               String var49 = String.format(Locale.ROOT, "region_%06d%s.glb", var12, var47);
               Path var50 = var3.resolve(var49);
               Path var52 = var50.resolveSibling(var49 + ".tmp");
               Files.write(var52, ObjToGlb.convert(var21, var45));
               moveReplacing(var52, var50);
               var17.add(new TilesetWriter.LodContent("glb/" + var49, var42[var19]));
               var18 = var18 == null ? var22 : unionBox(var18, var22);
            }
         }

         if (!var17.isEmpty() && var18 != null) {
            TilesetWriter.Tile var43 = toTile(((TilesetWriter.LodContent)var17.get(0)).uri(), var18, var17);
            var10.add(var43);
            String var44 = var15.stream().map(var0x -> Long.toString(var0x.id)).collect(Collectors.joining(","));
            System.out
               .printf(
                  Locale.ROOT,
                  "[regional merge] batch=%d buildings=%d groups=%d sourceIds=[%s] lods=%d%n",
                  var12,
                  var15.size(),
                  var15.stream().map(var0x -> String.valueOf(var0x.dto.getProperties().get("wkBldId"))).distinct().count(),
                  var44,
                  var17.size()
               );
            Files.writeString(
               var13,
               String.format(
                  Locale.ROOT,
                  "regionBatch=%d buildings=%d groups=%d wkBldIds=[%s] sourceIds=[%s] floorsAndCells=[%s] lods=%d%n",
                  var12,
                  var15.size(),
                  var15.stream().map(var0x -> String.valueOf(var0x.dto.getProperties().get("wkBldId"))).distinct().count(),
                  var16,
                  var44,
                  var15.stream().map(var1x -> {
                     Integer var2x = var1x.dto.getFloors();
                     boolean var3x = var1.textures && var2x != null && var2x > var1.floorLineThreshold;
                     return var1x.id + ":LC=" + var2x + "->cell" + (var3x ? 3 : 0);
                  }).collect(Collectors.joining(",")),
                  var17.size()
               ),
               StandardCharsets.UTF_8,
               StandardOpenOption.CREATE,
               StandardOpenOption.APPEND
            );
         }

         var12++;
      }

      return var10;
   }

   private static List<List<ShpTo3dTilesCli.BuildingRecord>> spatialRegionBatches(Map<String, List<ShpTo3dTilesCli.BuildingRecord>> var0, int var1) throws Exception {
      ArrayList<Entry<String, List<BuildingRecord>>> var2 = new ArrayList<>(var0.entrySet());
      IdentityHashMap<Entry<String, List<BuildingRecord>>, double[]> var3 = new IdentityHashMap<>();
      double var4 = Double.POSITIVE_INFINITY;
      double var6 = Double.POSITIVE_INFINITY;
      double var8 = Double.NEGATIVE_INFINITY;
      double var10 = Double.NEGATIVE_INFINITY;

      for (Entry var13 : var2) {
         double[] var14 = groupCenter((List<ShpTo3dTilesCli.BuildingRecord>)var13.getValue());
         var3.put(var13, var14);
         var4 = Math.min(var4, var14[0]);
         var8 = Math.max(var8, var14[0]);
         var6 = Math.min(var6, var14[1]);
         var10 = Math.max(var10, var14[1]);
      }

      double var25 = var4;
      double var26 = var6;
      double var16 = Math.max(1.0E-9, var8 - var4);
      double var18 = Math.max(1.0E-9, var10 - var6);
      var2.sort((var9, var10x) -> {
         double[] var11 = (double[])var3.get(var9);
         double[] var12 = (double[])var3.get(var10x);
         long var13x = morton16(normalize16(var11[0], var25, var16), normalize16(var11[1], var26, var18));
         long var15 = morton16(normalize16(var12[0], var25, var16), normalize16(var12[1], var26, var18));
         return Long.compareUnsigned(var13x, var15);
      });
      ArrayList<List<BuildingRecord>> var20 = new ArrayList<>();
      ArrayList<BuildingRecord> var21 = new ArrayList<>();

       for (Entry<String, List<BuildingRecord>> var23 : var2) {
         List<BuildingRecord> var24 = var23.getValue();
         if (!var21.isEmpty() && var21.size() + var24.size() > var1) {
            var20.add(var21);
            var21 = new ArrayList();
         }

         var21.addAll(var24);
      }

      if (!var21.isEmpty()) {
         var20.add(var21);
      }

      return var20;
   }

   private static int normalize16(double var0, double var2, double var4) {
      return (int)Math.max(0L, Math.min(65535L, Math.round((var0 - var2) / var4 * 65535.0)));
   }

   private static double[] groupCenter(List<ShpTo3dTilesCli.BuildingRecord> var0) throws Exception {
      double var1 = 0.0;
      double var3 = 0.0;
      int var5 = 0;

      for (ShpTo3dTilesCli.BuildingRecord var7 : var0) {
         Geometry var8 = new WKTReader().read(var7.dto.getWkt());
         Coordinate var9 = var8.getCentroid().getCoordinate();
         if (var9 != null && Double.isFinite(var9.x) && Double.isFinite(var9.y)) {
            var1 += var9.x;
            var3 += var9.y;
            var5++;
         }
      }

      return var5 == 0 ? new double[]{0.0, 0.0} : new double[]{var1 / var5, var3 / var5};
   }

   private static void assignWkBldIdsByAdjacency(List<ShpTo3dTilesCli.BuildingRecord> var0, Path var1) throws IOException {
      if (!var0.isEmpty()) {
         ArrayList<Geometry> var2 = new ArrayList<>(var0.size());
         STRtree var3 = new STRtree();

         for (int var4 = 0; var4 < var0.size(); var4++) {
            try {
               Geometry var5 = new WKTReader().read(((ShpTo3dTilesCli.BuildingRecord)var0.get(var4)).dto.getWkt());
               var2.add(var5);
               Envelope var6 = new Envelope(var5.getEnvelopeInternal());
               var6.expandBy(0.05);
               var3.insert(var6, var4);
            } catch (Exception var13) {
               var2.add(null);
            }
         }

         var3.build();
         int[] var14 = new int[var0.size()];
         int var15 = 0;

         while (var15 < var14.length) {
            var14[var15] = var15++;
         }

         for (int var16 = 0; var16 < var2.size(); var16++) {
            Geometry var18 = (Geometry)var2.get(var16);
            if (var18 != null) {
               Envelope var7 = new Envelope(var18.getEnvelopeInternal());
               var7.expandBy(0.05);

                for (Object var9 : var3.query(var7)) {
                   Integer var10 = (Integer)var9;
                  if (var10 > var16
                     && var2.get(var10) != null
                     && (var18.intersects((Geometry)var2.get(var10)) || var18.distance((Geometry)var2.get(var10)) <= 0.05)) {
                     union(var14, var16, var10);
                  }
               }
            }
         }

         LinkedHashMap<Integer, String> var17 = new LinkedHashMap<>();
         int[] var19 = new int[]{1};
         int var20 = 0;

         for (int var21 = 0; var21 < var0.size(); var21++) {
            Object var25 = ((ShpTo3dTilesCli.BuildingRecord)var0.get(var21)).dto.getProperties().get("wkBldId");
            if (var25 != null && !var25.toString().isBlank()) {
               var17.putIfAbsent(find(var14, var21), var25.toString().trim());
            }
         }

         for (int var22 = 0; var22 < var0.size(); var22++) {
            ShpTo3dTilesCli.BuildingRecord var26 = (ShpTo3dTilesCli.BuildingRecord)var0.get(var22);
            Object var28 = var26.dto.getProperties().get("wkBldId");
            if (var28 == null || var28.toString().isBlank()) {
               int var11 = find(var14, var22);
                String var12 = var17.computeIfAbsent(var11, var1x -> String.format(Locale.ROOT, "AUTO_WK_%06d", var19[0]++));
               var26.dto.getProperties().put("wkBldId", var12);
               var20++;
            }
         }

         for (ShpTo3dTilesCli.BuildingRecord var27 : var0) {
            var27.dto.getProperties().putIfAbsent("wkBldId", "AUTO_WK_SINGLE_" + var27.id);
         }

         Path var24 = var1.resolve("wkBldId-merge.log");
         Files.writeString(
            var24,
            String.format(Locale.ROOT, "[wkBldId] adjacency tolerance=%.3f source features=%d derived=%d%n", 0.05, var0.size(), var20),
            StandardCharsets.UTF_8
         );
         System.out.printf(Locale.ROOT, "[wkBldId] adjacency assignment derived=%d/%d tolerance=%.3fm%n", var20, var0.size(), 0.05);
      }
   }

   private static int find(int[] var0, int var1) {
      while (var0[var1] != var1) {
         var0[var1] = var0[var0[var1]];
         var1 = var0[var1];
      }

      return var1;
   }

   private static void union(int[] var0, int var1, int var2) {
      int var3 = find(var0, var1);
      int var4 = find(var0, var2);
      if (var3 != var4) {
         var0[var4] = var3;
      }
   }

   private static Map<String, Object> mergedProperties(List<ShpTo3dTilesCli.BuildingRecord> var0) {
      LinkedHashMap<String, Object> var1 = new LinkedHashMap<>();
      LinkedHashSet<String> var2 = new LinkedHashSet<>();

      for (ShpTo3dTilesCli.BuildingRecord var4 : var0) {
         var2.addAll(var4.dto.getProperties().keySet());
      }

      for (String var7 : var2) {
         List<Object> var5 = var0.stream().map(var1x -> var1x.dto.getProperties().get(var7)).distinct().toList();
         var1.put(var7, var5.size() == 1 ? var5.get(0) : var0.stream().map(var1x -> var1x.dto.getProperties().get(var7)).toList());
      }

      var1.put("mergedBuildingIds", var0.stream().map(var0x -> var0x.id).toList());
      return var1;
   }

   private static void appendMergedSegments(Mesh var0, BuildingExtruder.Segment var1, String var2, double var3, double var5, int var7) {
      int var8 = var0.positions.size();

      for (double[] var10 : var1.verts) {
         var0.positions.add(asFloat3(var10));
      }

       List<Mesh.Tri> var12 = var0.groupFor(var2);

      for (int[] var11 : var1.faces) {
         if (var11.length >= 3) {
            var12.add(new Mesh.Tri(var8 + var11[0] - 1, var8 + var11[1] - 1, var8 + var11[2] - 1, -1, -1, -1, var7, 0, var3, var5));
         }
      }
   }

   private static void writeLayeredRepeatTexture(Path var0, String var1) throws IOException {
      byte[] var2 = BuildingTextureSelector.load(var1);
      BufferedImage var3 = ImageIO.read(new ByteArrayInputStream(var2));
      if (var3 == null) {
         throw new IOException("Cannot read layered texture: " + var1);
      }

      int var4 = Math.max(1, var3.getHeight() / 32);
      Graphics2D var5 = var3.createGraphics();
      var5.drawImage(var3, 0, var3.getHeight() - var4, var3.getWidth(), var3.getHeight(), 0, 0, var3.getWidth(), var4, null);
      var5.dispose();
      Files.createDirectories(var0.getParent());
      if (!ImageIO.write(var3, "jpg", var0.toFile())) {
         throw new IOException("JPEG writer unavailable");
      }
   }

   static void appendLayeredWall(Mesh var0, BuildingExtruder.Segment var1, String var2, double var3, double var5, int var7, int var8) {
      int var9 = Math.max(1, var7);

      for (int faceIndex = 0; faceIndex < var1.faces.size(); faceIndex++) {
         int[] var11 = var1.faces.get(faceIndex);
         if (var11.length >= 3) {
            double[][] var12 = new double[3][];

            for (int var13 = 0; var13 < 3; var13++) {
               double[] var14 = var1.verts.get(var11[var13] - 1);
               var12[var13] = new double[]{var14[0], var14[1], var14[2]};
            }

            if (var1.isCapFace(faceIndex)) {
               int var22 = var0.positions.size();
               var0.positions.add(asFloat3(var12[0]));
               var0.positions.add(asFloat3(var12[1]));
               var0.positions.add(asFloat3(var12[2]));
               var0.groupFor("roof_white").add(new Mesh.Tri(var22, var22 + 1, var22 + 2, -1, -1, -1, var8, 0, var3, var5));
            } else {
               for (int var21 = 0; var21 < var9; var21++) {
                  double var23 = var3 + var21 * var5;
                  double var16 = var3 + (var21 + 1) * var5;
                  List var18 = clipAtHeight(clipAtHeight(List.of(var12), var23, true), var16, false);

                  for (int var19 = 1; var19 + 1 < var18.size(); var19++) {
                     int var20 = var0.positions.size();
                     var0.positions.add(asFloat3((double[])var18.get(0)));
                     var0.positions.add(asFloat3((double[])var18.get(var19)));
                     var0.positions.add(asFloat3((double[])var18.get(var19 + 1)));
                     var0.groupFor(var2).add(new Mesh.Tri(var20, var20 + 1, var20 + 2, -1, -1, -1, var8, 0, var3, var5 + Math.max(1.0E-5, var5 * 1.0E-6)));
                  }
               }
            }
         }
      }
   }

   private static List<double[]> clipAtHeight(List<double[]> var0, double var1, boolean var3) {
      if (var0.isEmpty()) {
         return var0;
      }

      ArrayList var4 = new ArrayList(var0.size() + 1);
      double[] var5 = (double[])var0.get(var0.size() - 1);
      boolean var6 = var3 ? var5[2] >= var1 - 1.0E-8 : var5[2] <= var1 + 1.0E-8;

      for (double[] var8 : var0) {
         boolean var9 = var3 ? var8[2] >= var1 - 1.0E-8 : var8[2] <= var1 + 1.0E-8;
         if (var9 != var6) {
            double var10 = (var1 - var5[2]) / (var8[2] - var5[2]);
            var4.add(new double[]{var5[0] + var10 * (var8[0] - var5[0]), var5[1] + var10 * (var8[1] - var5[1]), var1});
         }

         if (var9) {
            var4.add(var8);
         }

         var5 = var8;
         var6 = var9;
      }

      return var4;
   }

   private static float[] asFloat3(double[] var0) {
      return new float[]{(float)var0[0], (float)var0[1], (float)var0[2]};
   }

   private static Object metadataValue(Object var0) {
      if (var0 == null || var0 instanceof String || var0 instanceof Boolean) {
         return var0;
      } else if (var0 instanceof Date var4) {
         return Instant.ofEpochMilli(var4.getTime()).toString();
      } else if (var0 instanceof Number var1) {
         double var2 = var1.doubleValue();
         if (!Double.isFinite(var2)) {
            return null;
         } else {
            return Math.abs(var2) > 9.007199254740991E15 ? var1.toString() : var1;
         }
      } else {
         return var0.toString();
      }
   }

   private static String metadataFingerprint(Map<String, Object> var0) {
      try {
         byte[] var1 = new ObjectMapper().writeValueAsBytes(var0);
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(var1));
      } catch (Exception var2) {
         throw new IllegalArgumentException("Cannot fingerprint metadata", var2);
      }
   }

   private static double doubleValue(Object var0, double var1) {
      if (var0 instanceof Number var3) {
         return var3.doubleValue();
      } else {
         if (var0 != null) {
            try {
               return Double.parseDouble(var0.toString().trim());
            } catch (NumberFormatException var5) {
            }
         }

         return var1;
      }
   }

   private static int intValue(Object var0, int var1) {
      return (int)Math.round(doubleValue(var0, var1));
   }

   private static long longValue(Object var0, long var1) {
      return Math.round(doubleValue(var0, var1));
   }

   private static String stringValue(Object var0, String var1) {
      return var0 == null ? var1 : var0.toString();
   }

   private static Object attr(SimpleFeature var0, String var1) {
      if (var1 == null) {
         return null;
      }

      Object var2 = var0.getAttribute(var1);
      if (var2 != null) {
         return var2;
      }

      for (AttributeDescriptor var4 : var0.getFeatureType().getAttributeDescriptors()) {
         if (var4.getLocalName().equalsIgnoreCase(var1)) {
            return var0.getAttribute(var4.getLocalName());
         }
      }

      return null;
   }

   private record AtlasCell(double scaleU, double scaleV, double offsetU, double offsetV) {
   }

   private record BatchMesh(Mesh mesh, double[] box) {
   }

   private record BuildingRecord(long id, int ordinal, BuildingFootprintDTO dto) {
   }

   private record ConversionResult(long id, TilesetWriter.Tile tile, String error) {
   }

   private static final class CoordinateFrame {
      private static final double A = 6378137.0;
      private static final double E2 = 0.00669437999014;
      final double lonDeg;
      final double latDeg;
      final double lon;
      final double lat;
      final double sinLon;
      final double cosLon;
      final double sinLat;
      final double cosLat;
      final double anchorX;
      final double anchorY;
      final double anchorZ;
      final MathTransform sourceToWgs84;

      private CoordinateFrame(double var1, double var3, MathTransform var5) {
         this.lonDeg = var1;
         this.latDeg = var3;
         this.lon = Math.toRadians(var1);
         this.lat = Math.toRadians(var3);
         this.sinLon = Math.sin(this.lon);
         this.cosLon = Math.cos(this.lon);
         this.sinLat = Math.sin(this.lat);
         this.cosLat = Math.cos(this.lat);
         this.sourceToWgs84 = var5;
         double[] var6 = toEcef(this.lon, this.lat, 0.0);
         this.anchorX = var6[0];
         this.anchorY = var6[1];
         this.anchorZ = var6[2];
      }

      static ShpTo3dTilesCli.CoordinateFrame create(CoordinateReferenceSystem var0, double[] var1) {
         double[] var2 = ShpTo3dTilesCli.toLonLat(var0, var1[0], var1[1]);
         if (var2 == null) {
            return null;
         }

         MathTransform var3 = null;
         if (var0 != null) {
            try {
               var3 = CRS.findMathTransform(var0, CRS.decode("EPSG:4326", true), true);
            } catch (Exception var5) {
               System.err.println("[ShpTo3dTilesCli] cannot create source->WGS84 transform: " + var5.getMessage());
               return null;
            }
         }

         return new ShpTo3dTilesCli.CoordinateFrame(var2[0], var2[1], var3);
      }

      static ShpTo3dTilesCli.CoordinateFrame unprojected(double var0, double var2) {
         return new ShpTo3dTilesCli.CoordinateFrame(var0, var2, null);
      }

      double[] toLocalEnu(double var1, double var3, double var5) {
         double var7 = var1;
         double var9 = var3;
         if (this.sourceToWgs84 != null) {
            double[] var11 = new double[]{var1, var3};

            try {
               this.sourceToWgs84.transform(var11, 0, var11, 0, 1);
            } catch (Exception var18) {
               throw new IllegalArgumentException("CRS transform failed for vertex (" + var1 + ", " + var3 + ")", var18);
            }

            var7 = var11[0];
            var9 = var11[1];
         }

         double[] var19 = toEcef(Math.toRadians(var7), Math.toRadians(var9), var5);
         double var12 = var19[0] - this.anchorX;
         double var14 = var19[1] - this.anchorY;
         double var16 = var19[2] - this.anchorZ;
         return new double[]{
            -this.sinLon * var12 + this.cosLon * var14,
            -this.sinLat * this.cosLon * var12 - this.sinLat * this.sinLon * var14 + this.cosLat * var16,
            this.cosLat * this.cosLon * var12 + this.cosLat * this.sinLon * var14 + this.sinLat * var16
         };
      }

      private static double[] toEcef(double var0, double var2, double var4) {
         double var6 = Math.sin(var2);
         double var8 = Math.cos(var2);
         double var10 = 6378137.0 / Math.sqrt(1.0 - 0.00669437999014 * var6 * var6);
         return new double[]{(var10 + var4) * var8 * Math.cos(var0), (var10 + var4) * var8 * Math.sin(var0), (var10 * 0.99330562000986 + var4) * var6};
      }
   }

   private static final class Options {
      Path shp;
      Path outDir;
      Path prj;
      CoordinateReferenceSystem prjCrs;
      String idAttr = "bid";
      String heightAttr = "maxz";
      String baseZAttr = "minz";
      int floorLineThreshold = 10;
      String floorsAttr = "lc";
      String usageAttr = "bld_usage";
      String metadataFields = "bldUseage,entityid,wkBldId";
      boolean textures = true;
      String whiteTexture = "db_sumo_bui_001";
      String layeredTexture = "dise_G1_dx_02";
      int textureAtlasWidth = 384;
      boolean buildingLodChain;
      boolean legacyPerBuilding;
      boolean keepIntermediate;
      boolean resume;
      int parallelism = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
      int progressIntervalSeconds = 5;
      LodProfile lod = LodProfile.NONE;
      int tileMaxContents = 32;
      int tileMaxDepth = 12;
      int tileExternalDepth = 2;
      int parapetMinLc = 5;
      double parapetMinHeight = 0.8;
      double parapetMaxHeight = 1.4;
      double parapetPerFloor = 0.04;
      double parapetThickness = 0.4;
      int shaftMinLc = 9;
      double shaftInset = 1.5;
      double shaftClearance = 2.0;
      double shaftMaxLen = 8.0;
      double shaftMaxWid = 6.0;
      double shaftMaxHeight = 6.0;
      double shaftCenterBoxRatio = 0.8;
      boolean help;

      String textureStem(BuildingFootprintDTO var1) {
         return var1.getFloors() > this.floorLineThreshold ? this.layeredTexture : this.whiteTexture;
      }

      boolean hasWkBldId(List<ShpTo3dTilesCli.BuildingRecord> var1) {
         return var1.stream().anyMatch(var0 -> {
            Object var1x = var0.dto.getProperties().get("wkBldId");
            return var1x != null && !var1x.toString().isBlank();
         });
      }

      BuildingExtruder.Profile toProfile() {
         return this.toProfile(this.lod);
      }

      BuildingExtruder.Profile toProfile(LodProfile var1) {
         BuildingExtruder.Profile var2 = new BuildingExtruder.Profile();
         var2.parapetMinLc = this.parapetMinLc;
         var2.parapetMinHeight = this.parapetMinHeight;
         var2.parapetMaxHeight = this.parapetMaxHeight;
         var2.parapetPerFloor = this.parapetPerFloor;
         var2.parapetThickness = this.parapetThickness;
         var2.shaftMinLc = this.shaftMinLc;
         var2.shaftInset = this.shaftInset;
         var2.shaftClearance = this.shaftClearance;
         var2.shaftMaxLen = this.shaftMaxLen;
         var2.shaftMaxWid = this.shaftMaxWid;
         var2.shaftMaxHeight = this.shaftMaxHeight;
         var2.shaftCenterBoxRatio = this.shaftCenterBoxRatio;
         if (!var1.includeParapet()) {
            var2.parapetMinLc = Integer.MAX_VALUE;
         }

         if (!var1.includeShaft()) {
            var2.shaftMinLc = Integer.MAX_VALUE;
         }

         return var2;
      }

      String resumeKey(ShpTo3dTilesCli.BuildingRecord var1) {
         return String.join(
            "|",
            "building-parapet-above-maxz-roof-cap-normal-roofmaterial-v11",
            this.metadataFields,
            ShpTo3dTilesCli.metadataFingerprint(var1.dto.getProperties()),
            this.whiteTexture,
            this.layeredTexture,
            BuildingTextureSelector.outputName(this.whiteTexture),
            BuildingTextureSelector.outputName(this.layeredTexture),
            Integer.toString(this.floorLineThreshold),
            Integer.toString(this.textureAtlasWidth),
            this.lod.name(),
            this.idAttr,
            this.heightAttr,
            this.baseZAttr,
            this.floorsAttr,
            this.usageAttr,
            Boolean.toString(this.textures),
            Integer.toString(this.parapetMinLc),
            Double.toString(this.parapetMinHeight),
            Double.toString(this.parapetMaxHeight),
            Double.toString(this.parapetPerFloor),
            Double.toString(this.parapetThickness),
            Integer.toString(this.shaftMinLc),
            Double.toString(this.shaftInset),
            Double.toString(this.shaftClearance),
            Double.toString(this.shaftMaxLen),
            Double.toString(this.shaftMaxWid),
            Double.toString(this.shaftMaxHeight),
            Double.toString(this.shaftCenterBoxRatio),
            Long.toString(var1.id),
            Integer.toString(var1.ordinal),
            Integer.toHexString(var1.dto.getWkt() == null ? 0 : var1.dto.getWkt().hashCode()),
            String.valueOf(var1.dto.getJzgd()),
            String.valueOf(var1.dto.getBaseZ()),
            String.valueOf(var1.dto.getFloors())
         );
      }

      static ShpTo3dTilesCli.Options parse(String[] var0) {
         ShpTo3dTilesCli.Options var1 = new ShpTo3dTilesCli.Options();

         for (int var2 = 0; var2 < var0.length; var2++) {
            String var3 = var0[var2];
            if (!"--help".equals(var3) && !"-h".equals(var3)) {
               if (!var3.startsWith("--") || var2 + 1 >= var0.length) {
                  throw new IllegalArgumentException("invalid argument: " + var3);
               }

               String var4 = var0[++var2];
               switch (var3) {
                  case "--shp":
                     var1.shp = Paths.get(var4);
                     break;
                  case "--out":
                     var1.outDir = Paths.get(var4);
                     break;
                  case "--prj":
                     var1.prj = Paths.get(var4);
                     break;
                  case "--id-attr":
                     var1.idAttr = var4;
                     break;
                  case "--metadata-fields":
                     var1.metadataFields = var4;
                     break;
                  case "--white-texture":
                     var1.whiteTexture = var4;
                     break;
                  case "--layered-texture":
                     var1.layeredTexture = var4;
                     break;
                  case "--floor-line-threshold":
                     var1.floorLineThreshold = Integer.parseInt(var4);
                     break;
                  case "--max-z-attr":
                  case "--height-attr":
                     var1.heightAttr = var4;
                     break;
                  case "--min-z-attr":
                  case "--base-z-attr":
                     var1.baseZAttr = var4;
                     break;
                  case "--floors-attr":
                     var1.floorsAttr = var4;
                     break;
                  case "--usage-attr":
                     var1.usageAttr = var4;
                     break;
                  case "--texture-atlas-width":
                     var1.textureAtlasWidth = Integer.parseInt(var4);
                     if (var1.textureAtlasWidth != 384 && var1.textureAtlasWidth != 768 && var1.textureAtlasWidth != 1536) {
                        throw new IllegalArgumentException("--texture-atlas-width must be 384, 768 or 1536");
                     }
                     break;
                  case "--textures":
                     var1.textures = Boolean.parseBoolean(var4);
                     break;
                  case "--building-lod-chain":
                     var1.buildingLodChain = Boolean.parseBoolean(var4);
                     break;
                  case "--legacy-per-building":
                     var1.legacyPerBuilding = Boolean.parseBoolean(var4);
                     break;
                  case "--keep-intermediate":
                     var1.keepIntermediate = Boolean.parseBoolean(var4);
                     break;
                  case "--resume":
                     var1.resume = Boolean.parseBoolean(var4);
                     break;
                  case "--parallelism":
                     var1.parallelism = Integer.parseInt(var4);
                     break;
                  case "--progress-interval-seconds":
                     var1.progressIntervalSeconds = Integer.parseInt(var4);
                     break;
                  case "--lod":
                     var1.lod = LodProfile.valueOf(var4.trim().toUpperCase(Locale.ROOT));
                     break;
                  case "--tile-max-contents":
                     var1.tileMaxContents = Integer.parseInt(var4);
                     break;
                  case "--tile-max-depth":
                     var1.tileMaxDepth = Integer.parseInt(var4);
                     break;
                  case "--tile-external-depth":
                     var1.tileExternalDepth = Integer.parseInt(var4);
                     break;
                  case "--parapet-min-lc":
                     var1.parapetMinLc = Integer.parseInt(var4);
                     break;
                  case "--parapet-min-height":
                     var1.parapetMinHeight = Double.parseDouble(var4);
                     break;
                  case "--parapet-max-height":
                     var1.parapetMaxHeight = Double.parseDouble(var4);
                     break;
                  case "--parapet-per-floor":
                     var1.parapetPerFloor = Double.parseDouble(var4);
                     break;
                  case "--parapet-thickness":
                     var1.parapetThickness = Double.parseDouble(var4);
                     break;
                  case "--shaft-min-lc":
                     var1.shaftMinLc = Integer.parseInt(var4);
                     break;
                  case "--shaft-inset":
                     var1.shaftInset = Double.parseDouble(var4);
                     break;
                  case "--shaft-clearance":
                     var1.shaftClearance = Double.parseDouble(var4);
                     break;
                  case "--shaft-max-len":
                     var1.shaftMaxLen = Double.parseDouble(var4);
                     break;
                  case "--shaft-max-wid":
                     var1.shaftMaxWid = Double.parseDouble(var4);
                     break;
                  case "--shaft-max-height":
                     var1.shaftMaxHeight = Double.parseDouble(var4);
                     break;
                  case "--shaft-center-ratio":
                     var1.shaftCenterBoxRatio = Double.parseDouble(var4);
                     break;
                  default:
                     throw new IllegalArgumentException("unknown argument: " + var3);
               }
            } else {
               var1.help = true;
            }
         }

         return var1;
      }

      void validate() {
         ShpTo3dTilesCli.parseMetadataFields(this.metadataFields);

         for (String var2 : List.of(this.whiteTexture, this.layeredTexture)) {
            if (BuildingTextureSelector.load(var2) == null) {
               throw new IllegalArgumentException("Missing texture: " + var2);
            }
         }

         if (this.floorLineThreshold < 0) {
            throw new IllegalArgumentException("floor-line-threshold must be >= 0");
         } else if (this.shp == null || !Files.exists(this.shp)) {
            throw new IllegalArgumentException("--shp must point to an existing .shp file");
         } else if (this.outDir == null) {
            throw new IllegalArgumentException("--out is required");
         } else if (this.parallelism <= 0) {
            throw new IllegalArgumentException("--parallelism must be > 0");
         } else if (this.tileMaxContents <= 0 || this.tileMaxDepth <= 0 || this.tileExternalDepth <= 0 || this.tileExternalDepth >= this.tileMaxDepth) {
            throw new IllegalArgumentException("tile hierarchy parameters are invalid");
         } else if (this.progressIntervalSeconds <= 0) {
            throw new IllegalArgumentException("--progress-interval-seconds must be > 0");
         } else if (this.prj != null && !Files.exists(this.prj)) {
            throw new IllegalArgumentException("--prj must point to an existing .prj file");
         } else if (this.parapetMinHeight < 0.0 || this.parapetMaxHeight < this.parapetMinHeight) {
            throw new IllegalArgumentException("--parapet-max-height must be >= --parapet-min-height");
         } else if (this.parapetThickness <= 0.0) {
            throw new IllegalArgumentException("--parapet-thickness must be > 0");
         } else if (this.shaftInset <= 0.0 || this.shaftClearance < 0.0 || this.shaftMaxLen <= 0.0 || this.shaftMaxWid <= 0.0 || this.shaftMaxHeight <= 0.0) {
            throw new IllegalArgumentException("shaft dimensions must be positive");
         } else if (this.shaftCenterBoxRatio <= 0.0 || this.shaftCenterBoxRatio > 1.0) {
            throw new IllegalArgumentException("--shaft-center-ratio must be in (0, 1]");
         }
      }

      static void printUsage() {
         System.out.println("--metadata-fields field1,field2  SHP properties exported per building (default bldUseage,entityid,wkBldId)\n");
         System.out
            .println(
               "--white-texture PATH    default db_sumo_bui_001\n--layered-texture PATH  default dise_G1_dx_02; used when LC > floor-line-threshold\nTexture mode repeats one image per exact floor height; no geometry floor lines are generated.\n"
            );
         System.out
            .println(
               "Elevation inputs: --max-z-attr maxz --min-z-attr minz --floors-attr lc\nHeight = maxz - minz; storey height = height / LC. Invalid records fail conversion.\n--floor-line-threshold N  use layered texture only when LC > N (default 10)\n"
            );
         System.out
            .println(
               "SHP -> GLB -> 3D Tiles 1.1\n\nThe tileset root lon/lat are derived from the source CRS\n(explicit --prj wins, else the SHP companion .prj) and the\ncentroid mean of every footprint, projected to WGS84.\nNo --lon/--lat/--origin arguments are needed.\n\nRequired:\n  --shp <file.shp>          input polygon shapefile (with .prj alongside)\n  --out <directory>         output directory (tileset.json + glb/*.glb)\n\nCoordinate system:\n  --prj <file.prj>          optional explicit .prj (WKT). Use it when\n                            the SHP has no companion .prj, when the\n                            companion one is wrong, or to reuse one\n                            .prj for a batch of shapefiles.\n\nOptional SHP field overrides (defaults shown):\n  --id-attr <name>           default: bid\n  --max-z-attr <name>        top elevation, default: maxz (--height-attr alias)\n  --min-z-attr <name>        bottom elevation, default: minz (--base-z-attr alias)\n  --floors-attr <name>       default: lc\n  --usage-attr <name>        default: bld_usage\n\nGeneration switches:\n  --texture-atlas-width 384|768|1536\n                             shared LOD atlas width, default: 384\n  --textures true|false       use shared repeating facade textures, default: true\n  --building-lod-chain true|false\n                             regional white L1 + textured L2/L3, default: false\n  --legacy-per-building true|false\n                             use the pre-optimized OBJ pipeline, default: false\n  --keep-intermediate true|false\n                             keep obj/ and glb-intermediate/ files, default: false\n  --resume true|false          reuse completed .glb + .done outputs\n  --parallelism N              conversion workers, default: min(8, CPU)\n  --progress-interval-seconds N\n                             progress log interval, default: 5\n  --lod NONE|L1_SIMPLE|L2_STANDARD|L3_DETAIL\n                             per-building simplification/detail level\n  --tile-max-contents N      batch/spatial capacity, default: 32\n  --tile-max-depth N         max spatial tree depth, default: 12\n  --tile-external-depth N    write nested tilesets from this depth, default: 2\n\nParapet knobs (metres unless noted):\n  --parapet-min-lc      N     lc threshold, default: 5\n  --parapet-min-height   m     default: 0.80\n  --parapet-max-height   m     default: 1.40\n  --parapet-per-floor    m     default: 0.04\n  --parapet-thickness    m     ring wall thickness, default: 0.40\n\nRooftop machine-room knobs (single shaft, metres):\n  --shaft-min-lc         N     default: 9\n  --shaft-inset          m     default: 1.50\n  --shaft-clearance      m     default: 2.00\n  --shaft-max-len        m     default: 8.00\n  --shaft-max-wid        m     default: 6.00\n  --shaft-max-height     m     default: 6.00\n  --shaft-center-ratio  ratio central band / full span, default: 0.80\n"
            );
      }
   }

   private record PositionedRecord(ShpTo3dTilesCli.BuildingRecord record, double x, double y) {
   }
}
