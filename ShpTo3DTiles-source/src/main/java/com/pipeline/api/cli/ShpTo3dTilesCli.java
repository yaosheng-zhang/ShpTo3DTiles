package com.pipeline.api.cli;

import java.util.Map;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.WhiteModelObjWriter;
import com.pipeline.api.gltf.Mesh;
import com.pipeline.api.gltf.ObjToGlb;
import com.pipeline.api.scene.B3dmWriter;
import com.pipeline.api.scene.BuildingTextureSelector;
import com.pipeline.api.scene.ObjToGlbConverter;
import com.pipeline.api.scene.TilesetWriter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

public final class ShpTo3dTilesCli {
   private static final String DEFAULT_HEIGHT_ATTR = "jzgd";
   private static final String DEFAULT_BASE_Z_ATTR = "base_z";
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
         Path var2 = var1.outDir.resolve("obj");
         Path var3 = var1.outDir.resolve("glb");
         Path var4 = var1.outDir.resolve("b3dm");
         Files.createDirectories(var2);
         Files.createDirectories(var3);
         Files.createDirectories(var4);
         Files.writeString(var2.resolve("white.mtl"), WhiteModelObjWriter.mtlContent(), StandardCharsets.UTF_8);
         Path var5 = var1.outDir.resolve("b3dm").resolve("textures");
         Files.createDirectories(var5);
         if (var1.textures && var1.buildingLodChain) {
            writeTextureAtlas(var5.resolve("atlas.jpg"), var1.textureAtlasWidth);
         }

         List<ShpTo3dTilesCli.BuildingRecord> var6 = readShapefile(var1);
         if (var6.isEmpty()) {
            TilesetWriter.write(var1.outDir, 0.0, 0.0, 0.0, List.of());
            System.out.println("[ShpTo3dTilesCli] no polygon features found");
         } else {
            double[] var7 = centroidMean(var6);
            ShpTo3dTilesCli.CoordinateFrame var8 = ShpTo3dTilesCli.CoordinateFrame.create(var1.prjCrs, var7);
            if (var8 == null) {
               if (var1.prjCrs == null) {
                  System.err
                     .println(
                        "[ShpTo3dTilesCli] WARNING: no .prj found (neither --prj nor a companion file). Falling back to treating source coordinates as lon/lat - if the data is projected, the tileset will be misplaced. Use --prj <file.prj> to specify the CRS."
                     );
               }

               var8 = ShpTo3dTilesCli.CoordinateFrame.unprojected(var7[0], var7[1]);
            }

            ShpTo3dTilesCli.CoordinateFrame var9 = var8;
            System.out
               .printf(Locale.ROOT, "[ShpTo3dTilesCli] source centroid=(%.3f, %.3f) -> WGS84=(%.6f, %.6f)%n", var7[0], var7[1], var9.lonDeg, var9.latDeg);
            System.out
               .printf(
                  Locale.ROOT,
                  "[ShpTo3dTilesCli] convert workers=%d inFlight<=%d lod=%s resume=%s%n",
                  Math.min(var1.parallelism, Math.max(1, var6.size())),
                  Math.min(var1.parallelism, Math.max(1, var6.size())) * 2,
                  var1.lod,
                  var1.resume
               );
            if (!var1.legacyPerBuilding && !var1.textures) {
               List var26 = convertOptimizedBatches(var6, var1, var9, var4);
               TilesetWriter.write(
                  var1.outDir,
                  var9.lonDeg,
                  var9.latDeg,
                  0.0,
                  var26,
                  new TilesetWriter.Options(var1.tileMaxContents, var1.tileMaxDepth, 1.0, var1.tileExternalDepth)
               );
               System.out
                  .printf(
                     Locale.ROOT, "[ShpTo3dTilesCli] optimized batches=%d buildings=%d output=%s%n", var26.size(), var6.size(), var1.outDir.toAbsolutePath()
                  );
            } else {
               BatchProgressLogger var10 = new BatchProgressLogger(
                  "convert/" + var1.lod.name().toLowerCase(Locale.ROOT), var6.size(), var1.progressIntervalSeconds
               );
               int var11 = Math.min(var1.parallelism, Math.max(1, var6.size()));
               ExecutorService var12 = Executors.newFixedThreadPool(var11);
               ExecutorCompletionService var13 = new ExecutorCompletionService(var12);
               ArrayList var14 = new ArrayList();
               int var15 = 0;
               int var16 = 0;
               int var17 = 0;
               int var18 = 0;

               try {
                  int var19 = Math.max(1, var11 * 2);

                  for (ShpTo3dTilesCli.BuildingRecord var21 : var6) {
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
                     ShpTo3dTilesCli.ConversionResult var27 = (ShpTo3dTilesCli.ConversionResult)var13.take().get();
                     var16++;
                     if (var27.tile != null) {
                        var14.add(var27.tile);
                        var17++;
                     } else {
                        var18++;
                     }

                     var10.complete(var27.tile != null);
                     logConversionResult(var27);
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
                  var14 = new ArrayList<>(batchTexturedL1(var6, var14, var1, var9, var4));
               }
               TilesetWriter.write(
                  var1.outDir,
                  var9.lonDeg,
                  var9.latDeg,
                  0.0,
                  var14,
                  new TilesetWriter.Options(var1.tileMaxContents, var1.tileMaxDepth, 1.0, var1.tileExternalDepth)
               );
               System.out.printf(Locale.ROOT, "[ShpTo3dTilesCli] input=%s features=%d ok=%d failed=%d%n", var1.shp, var6.size(), var17, var18);
               System.out.println("[ShpTo3dTilesCli] output=" + var1.outDir.toAbsolutePath());
            }
         }
      }
   }

   private static List<ShpTo3dTilesCli.BuildingRecord> readShapefile(ShpTo3dTilesCli.Options var0) throws IOException {
      HashMap var1 = new HashMap();
      URL var2 = var0.shp.toUri().toURL();
      var1.put("url", var2);
      if (var0.prj != null) {
         String var3 = Files.readString(var0.prj, StandardCharsets.US_ASCII).trim();
         var0.prjCrs = parseCrs(var3);
         System.out.println("[ShpTo3dTilesCli] using explicit .prj: " + var0.prj.toAbsolutePath());
      } else {
         Path var26 = var0.shp.toAbsolutePath().getParent().resolve(stripShpExt(var0.shp.getFileName().toString()) + ".prj");
         if (Files.exists(var26)) {
            String var4 = Files.readString(var26, StandardCharsets.US_ASCII).trim();
            var0.prjCrs = parseCrs(var4);
         }
      }

      ArrayList<ShpTo3dTilesCli.BuildingRecord> var27 = new ArrayList<>();
      DataStore var28 = DataStoreFinder.getDataStore(var1);

      try {
         if (var28 == null) {
            throw new IOException("cannot open shapefile: " + var0.shp);
         }

         String var5 = var28.getTypeNames()[0];
         SimpleFeatureSource var6 = var28.getFeatureSource(var5);
         String var7 = resolveAttributeName(var6, var0.usageAttr, USAGE_ATTR_ALIASES);
         System.out.printf(Locale.ROOT, "[textures] usage attribute requested='%s' resolved='%s'%n", var0.usageAttr, var7 == null ? "<none>" : var7);
         SimpleFeatureCollection var8 = var6.getFeatures();
         WKTWriter var9 = new WKTWriter();
         int var10 = 0;
         SimpleFeatureIterator var11 = var8.features();

         try {
            while (var11.hasNext()) {
               SimpleFeature var12 = (SimpleFeature)var11.next();
               if (var12.getDefaultGeometry() instanceof Geometry var14 && !var14.isEmpty()) {
                  long var15 = longValue(attr(var12, var0.idAttr), var10 + 1L);
                  BuildingFootprintDTO var17 = new BuildingFootprintDTO();
                  var17.setBid(var15);
                  var17.setJzgd(doubleValue(attr(var12, var0.heightAttr), 3.0));
                  var17.setBaseZ(doubleValue(attr(var12, var0.baseZAttr), 0.0));
                  int var18 = intValue(attr(var12, var0.floorsAttr), 0);
                  if (var18 <= 0) {
                     var18 = Math.max(0, (int)Math.round(doubleValue(attr(var12, var0.floorsAttr + "1"), 0.0)));
                  }

                  var17.setFloors(var18);
                  var17.setBldUsage(stringValue(attr(var12, var7), ""));
                  var17.setWkt(var9.write(var14));
                  var27.add(new ShpTo3dTilesCli.BuildingRecord(var15, var10, var17));
                  var10++;
               }
            }
         } catch (Throwable var24) {
            if (var11 != null) {
               try {
                  var11.close();
               } catch (Throwable var23) {
                  var24.addSuppressed(var23);
               }
            }

            throw var24;
         }

         if (var11 != null) {
            var11.close();
         }
      } finally {
         if (var28 != null) {
            var28.dispose();
         }
      }

      int var29 = 0;
      int var30 = 0;
      int var31 = 0;

      for (ShpTo3dTilesCli.BuildingRecord var34 : var27) {
         Integer var35 = var34.dto.getFloors();
         if (var35 != null && var35 > 0) {
            var29++;
         }

         if (var35 != null && var35 >= var0.parapetMinLc) {
            var30++;
         }

         if (var35 != null && var35 >= var0.shaftMinLc) {
            var31++;
         }
      }

      System.out
         .printf(
            Locale.ROOT,
            "[ShpTo3dTilesCli] floors attr='%s': %d/%d buildings have floors, %d >= parapet-min-lc(%d), %d >= shaft-min-lc(%d)%n",
            var0.floorsAttr,
            var29,
            var27.size(),
            var30,
            var0.parapetMinLc,
            var31,
            var0.shaftMinLc
         );
      if (var29 == 0) {
         System.out
            .println(
               "[ShpTo3dTilesCli] WARNING: no building has a usable floors value — parapets and rooftop shafts will NOT be generated. Check the --floors-attr field name."
            );
      }

      long var33 = var27.stream().filter(var0x -> var0x.dto.getBldUsage() != null && !var0x.dto.getBldUsage().isBlank()).count();
      System.out.printf(Locale.ROOT, "[textures] usage values: %d/%d buildings%n", var33, var27.size());
      TreeMap<String, Integer> var36 = new TreeMap<>();

      for (ShpTo3dTilesCli.BuildingRecord var38 : var27) {
         String var39 = BuildingTextureSelector.selectStem(var38.dto.getBldUsage(), var38.dto.getFloors());
         var36.merge(var39, 1, Integer::sum);
      }

      System.out.println("[textures] assignments=" + var36);
      return var27;
   }

   private static String resolveAttributeName(SimpleFeatureSource var0, String var1, String... var2) {
      List<AttributeDescriptor> var3 = ((SimpleFeatureType)var0.getSchema()).getAttributeDescriptors();
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
      } else {
         try {
            CoordinateReferenceSystem var5 = CRS.decode("EPSG:4326", true);
            if (CRS.equalsIgnoreMetadata(var0, var5)) {
               return new double[]{var1, var3};
            } else {
               MathTransform var6 = CRS.findMathTransform(var0, var5, true);
               double[] var7 = new double[]{var1, var3};
               var6.transform(var7, 0, var7, 0, 1);
               return new double[]{var7[0], var7[1]};
            }
         } catch (Exception var8) {
            System.err.println("[ShpTo3dTilesCli] CRS->WGS84 transform failed: " + var8.getMessage());
            return null;
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
      var0 = spatialOrder(var0);
      int var4 = Math.max(1, var1.tileMaxContents);
      ArrayList var5 = new ArrayList();
      int var6 = 0;

      for (int var7 = 0; var7 < var0.size(); var7 += var4) {
         int var8 = Math.min(var0.size(), var7 + var4);
         List var9 = var0.subList(var7, var8);
         ShpTo3dTilesCli.ConversionResult var10 = convertOptimizedBatch(var9, var6++, var1, var2, var3);
         if (var10.tile != null) {
            var5.add(var10.tile);
         } else {
            logConversionResult(var10);
         }
      }

      return var5;
   }

   /** Merge only the far white level; keep per-building textured descendants. */
   private static List<TilesetWriter.Tile> batchTexturedL1(
      List<BuildingRecord> records, List<TilesetWriter.Tile> details,
      Options options, CoordinateFrame frame, Path output) throws Exception {
      Map<String, BuildingRecord> byUri = new java.util.HashMap<>();
      for (BuildingRecord record : records) {
         byUri.put(String.format(Locale.ROOT, "b3dm/b%012d_%06d_l2.b3dm", record.id, record.ordinal), record);
      }
      List<TilesetWriter.Tile> result = new ArrayList<>();
      partitionTexturedL1(new ArrayList<>(details), byUri, options, frame, output, result);
      System.out.printf("[L1 batching] buildings=%d batches=%d maxBuildings=%d maxTriangles=20000 maxSpan=512m%n",
                        details.size(), result.size(), options.tileMaxContents);
      return result;
   }

   private static void partitionTexturedL1(
      List<TilesetWriter.Tile> details, Map<String, BuildingRecord> records,
      Options options, CoordinateFrame frame, Path output,
      List<TilesetWriter.Tile> result) throws Exception {
      if (details.isEmpty()) return;
      double[] bounds = null;
      for (TilesetWriter.Tile tile : details) {
         double[] c = tile.localBoxCenter(), h = tile.localBoxHalf();
         double[] b = {c[0]-h[0], c[1]-h[1], c[2]-h[2], c[0]+h[0], c[1]+h[1], c[2]+h[2]};
         bounds = bounds == null ? b : unionBox(bounds, b);
      }
      boolean split = details.size() > options.tileMaxContents ||
                      Math.max(bounds[3]-bounds[0], bounds[4]-bounds[1]) > 512;
      Mesh mesh = new Mesh();
      if (!split || details.size() == 1) {
         // Strict conversion: never publish a parent that silently omits a child.
         for (TilesetWriter.Tile tile : details) {
            BuildingRecord record = records.get(tile.uri());
            if (record == null) throw new IllegalStateException("Missing L1 record: " + tile.uri());
            List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(
               copyForLod(record.dto, LodProfile.L1_SIMPLE), options.toProfile(LodProfile.L1_SIMPLE));
            if (segments.isEmpty()) throw new IllegalStateException("Empty L1: " + tile.uri());
            bounds = unionBox(bounds, shiftAndBox(segments, frame));
            appendSegments(mesh, segments);
         }
         split = split || mesh.triangleCount() > 20000 ||
                 Math.max(bounds[3]-bounds[0], bounds[4]-bounds[1]) > 512;
      }
      if (split && details.size() > 1) {
         final int axis = bounds[3]-bounds[0] >= bounds[4]-bounds[1] ? 0 : 1;
         details.sort(Comparator.comparingDouble((TilesetWriter.Tile t) -> t.localBoxCenter()[axis])
                                .thenComparing(TilesetWriter.Tile::uri));
         int middle = details.size()/2;
         partitionTexturedL1(new ArrayList<>(details.subList(0, middle)), records, options, frame, output, result);
         partitionTexturedL1(new ArrayList<>(details.subList(middle, details.size())), records, options, frame, output, result);
         return;
      }
      ObjToGlb.Options glb = new ObjToGlb.Options();
      glb.inputAxis = ObjToGlb.Axis.Z;
      glb.outputAxis = ObjToGlb.Axis.Y;
      glb.generateNormalsIfMissing = true;
      glb.forceFlat = true;
      glb.indexVertices = true;
      glb.doubleSided = false;
      String name = String.format(Locale.ROOT, "region_%06d_l1.b3dm", result.size());
      Path target = output.resolve(name), temp = output.resolve(name + ".tmp");
      Files.write(temp, B3dmWriter.wrap(ObjToGlb.convert(mesh, glb)));
      moveReplacing(temp, target);
      String uri = "b3dm/" + name;
      TilesetWriter.Tile parent = toTile(uri, bounds, List.of(new TilesetWriter.LodContent(uri, 18.0)));
      result.add(new TilesetWriter.Tile(uri, parent.localBoxCenter(), parent.localBoxHalf(), parent.lods(), details));
   }

   private static List<ShpTo3dTilesCli.BuildingRecord> spatialOrder(List<ShpTo3dTilesCli.BuildingRecord> var0) {
      ArrayList<ShpTo3dTilesCli.PositionedRecord> var1 = new ArrayList<>(var0.size());
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
      var1.sort(Comparator.comparingLong((ShpTo3dTilesCli.PositionedRecord var8x) -> {
         int var9 = (int)Math.round(65535.0 * (var8x.x - var23) / var25);
         int var10x = (int)Math.round(65535.0 * (var8x.y - var24) / var26);
         return morton16(var9, var10x);
      }));
      ArrayList<ShpTo3dTilesCli.BuildingRecord> var19 = new ArrayList<>(var0.size());

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
         // Distances are metres in the batch-local frame: keep L1 visible at a
         // coarse range, replace it with L2, then request full detail only nearby.
         double[] var6 = var2.buildingLodChain ? new double[]{18.0, 3.0, 0.0} : new double[]{0.0};
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
               byte[] var13 = B3dmWriter.wrap(var12);
               String var14 = var2.buildingLodChain ? "_l" + (var9 + 1) : "";
               String var15 = String.format(Locale.ROOT, "tile_%06d%s.b3dm", var1, var14);
               Path var16 = var4.resolve(var15);
               Path var17 = var16.resolveSibling(var15 + ".tmp");
               Files.write(var17, var13);
               moveReplacing(var17, var16);
               var7.add(new TilesetWriter.LodContent("b3dm/" + var15, var6[var9]));
            }
         }

         if (!var7.isEmpty() && var8 != null) {
            String var19 = ((TilesetWriter.LodContent)var7.get(0)).uri();
            TilesetWriter.Tile var20 = var2.buildingLodChain ? toTile(var19, var8, var7) : toTile(var19, var8);
            return new ShpTo3dTilesCli.ConversionResult((long)var1, var20, null);
         } else {
            return new ShpTo3dTilesCli.ConversionResult((long)var1, null, "empty optimized batch");
         }
      } catch (Exception var18) {
         return new ShpTo3dTilesCli.ConversionResult((long)var1, null, var18.getClass().getSimpleName() + ": " + var18.getMessage());
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
               appendSegments(var4, var10);
            }
         } catch (Exception var12) {
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

   private static void appendSegments(Mesh var0, List<BuildingExtruder.Segment> var1) {
      for (BuildingExtruder.Segment var3 : var1) {
         int var4 = var0.positions.size();

         for (double[] var6 : var3.verts) {
            var0.positions.add(new float[]{(float)var6[0], (float)var6[1], (float)var6[2]});
         }

         List var8 = var0.groupFor(var3.material);

         for (int[] var7 : var3.faces) {
            if (var7.length >= 3) {
               var8.add(new Mesh.Tri(var4 + var7[0] - 1, var4 + var7[1] - 1, var4 + var7[2] - 1, -1, -1, -1));
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
      Object var6 = null;
      Object var7 = null;

      try {
         BuildingFootprintDTO var8 = copyForLod(var0.dto, var1.lod);
         BuildingExtruder.Profile var9 = var1.toProfile(var1.lod);
         List var10 = BuildingExtruder.extrude(var8, var9);
         if (var10.isEmpty()) {
            return new ShpTo3dTilesCli.ConversionResult(var0.id, null, "empty extrusion");
         } else {
            String var11 = String.format(Locale.ROOT, "b%012d_%06d", var0.id, var0.ordinal);
            var6 = var3.resolve(var11 + ".obj");
            var7 = var4.resolve(var11 + ".glb");
            Path var12 = var5.resolve(var11 + ".b3dm");
            Path var13 = var12.resolveSibling(var12.getFileName() + ".done");
            String var14 = var1.resumeKey(var0);
            if (var1.resume && Files.exists(var13) && Files.size(var12) > 0L && var14.equals(Files.readString(var13, StandardCharsets.US_ASCII).trim())) {
               return new ShpTo3dTilesCli.ConversionResult(var0.id, toTile("b3dm/" + var12.getFileName(), shiftAndBox(var10, var2)), null);
            } else {
               double[] var15 = shiftAndBox(var10, var2);
               WhiteModelObjWriter.writeObj((Path)var6, var0.id, var10);
               byte[] var16 = var1.textures ? BuildingTextureSelector.selectJpeg(var8.getBldUsage(), var8.getFloors()) : null;
               int var17 = var8.getFloors() == null ? 1 : Math.max(1, var8.getFloors());
               double var18 = Math.max(0.5, var8.getJzgd() / var17);
               new ObjToGlbConverter().convert((Path)var6, (Path)var7, var16, var15[2], var18, 4.0);
               Path var20 = var12.resolveSibling(var12.getFileName() + ".tmp");
               Files.write(var20, B3dmWriter.wrap(Files.readAllBytes((Path)var7)));
               moveReplacing(var20, var12);
               Files.writeString(var13, var14 + "\n", StandardCharsets.US_ASCII);
               if (!var1.keepIntermediate) {
                  Files.deleteIfExists((Path)var6);
                  Files.deleteIfExists((Path)var7);
               }

               return new ShpTo3dTilesCli.ConversionResult(var0.id, toTile("b3dm/" + var12.getFileName(), var15), null);
            }
         }
      } catch (Exception var21) {
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
         // Smaller L2 error postpones full-detail refinement; this is not hysteresis.
         double[] var10 = new double[]{18.0, 3.0, 0.0};

         for (int var11 = (var1.textures && !var1.legacyPerBuilding ? 1 : 0); var11 < var9.length; var11++) {
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

               Path var27 = var3.resolve(var6 + "_l" + (var11 + 1) + ".obj");
               Path var17 = var4.resolve(var6 + "_l" + (var11 + 1) + ".glb");
               Path var18 = var5.resolve(var6 + "_l" + (var11 + 1) + ".b3dm");
               Path var19 = var18.resolveSibling(var18.getFileName() + ".done");
               String var20 = var1.resumeKey(var0) + "|chain|" + var12.name();
               if (!var1.resume || !Files.exists(var19) || Files.size(var18) <= 0L || !var20.equals(Files.readString(var19, StandardCharsets.US_ASCII).trim())) {
                  WhiteModelObjWriter.writeObj(var27, var0.id, var14);
                  double var22 = Math.max(0.5, var13.getJzgd());
                  if (var1.textures && var12 != LodProfile.L1_SIMPLE) {
                     String var24 = BuildingTextureSelector.selectStem(var13.getBldUsage(), var13.getFloors());
                     ShpTo3dTilesCli.AtlasCell var25 = atlasCell(var24);
                     new ObjToGlbConverter()
                        .convertAtlas(var27, var17, "textures/atlas.jpg", var15[2], var22, 4.0, var25.scaleU, var25.scaleV, var25.offsetU, var25.offsetV);
                  } else {
                     new ObjToGlbConverter().convert(var27, var17);
                  }

                  Path var28 = var18.resolveSibling(var18.getFileName() + ".tmp");
                  Files.write(var28, B3dmWriter.wrap(Files.readAllBytes(var17)));
                  moveReplacing(var28, var18);
                  Files.writeString(var19, var20 + "\n", StandardCharsets.US_ASCII);
                  if (!var1.keepIntermediate) {
                     Files.deleteIfExists(var27);
                     Files.deleteIfExists(var17);
                  }
               }

               var7.add(new TilesetWriter.LodContent("b3dm/" + var18.getFileName(), var10[var11]));
            }
         }

         return !var7.isEmpty() && var8 != null
            ? new ShpTo3dTilesCli.ConversionResult(var0.id, toTile(((TilesetWriter.LodContent)var7.get(0)).uri(), var8, var7), null)
            : new ShpTo3dTilesCli.ConversionResult(var0.id, null, "empty LOD chain");
      } catch (Exception var26) {
         return new ShpTo3dTilesCli.ConversionResult(var0.id, null, var26.getClass().getSimpleName() + ": " + var26.getMessage());
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

   private static void writeTextureAtlas(Path var0, int width) throws IOException {
      BufferedImage var1 = new BufferedImage(1536, 1024, 1);
      Graphics2D var2 = var1.createGraphics();
      var2.setColor(Color.WHITE);
      var2.fillRect(0, 0, var1.getWidth(), var1.getHeight());
      String[] var3 = new String[]{"db_sumo_bui_001", "dise_G1_5", "dise_G1_dx_01", "dise_G1_dx_02", "dise_G1_dx_05", "dise_G1_dx_06"};

      for (int var4 = 0; var4 < var3.length; var4++) {
         try (InputStream var5 = ShpTo3dTilesCli.class.getResourceAsStream("/textures/" + var3[var4] + ".jpg")) {
            if (var5 != null) {
               BufferedImage var6 = ImageIO.read(var5);
               int var7 = darkTopBorderHeight(var6);
               int var8 = var4 % 3 * 512;
               int var9 = var4 / 3 * 512;
               int var10 = var8 + 8;
               int var11 = var9 + 8;
               short var12 = 496;
               var2.drawImage(var6, var10, var11, var10 + var12, var11 + var12, 0, var7, var6.getWidth(), var6.getHeight(), null);
               var2.drawImage(var6, var8, var11, var10, var11 + var12, 0, var7, 1, var6.getHeight(), null);
               var2.drawImage(var6, var10 + var12, var11, var8 + 512, var11 + var12, var6.getWidth() - 1, var7, var6.getWidth(), var6.getHeight(), null);
               var2.drawImage(var6, var10, var9, var10 + var12, var11, 0, var7, var6.getWidth(), var7 + 1, null);
               var2.drawImage(var6, var10, var11 + var12, var10 + var12, var9 + 512, 0, var6.getHeight() - 1, var6.getWidth(), var6.getHeight(), null);
               var2.setColor(new Color(var6.getRGB(0, var7)));
               var2.fillRect(var8, var9, 8, 8);
               var2.setColor(new Color(var6.getRGB(var6.getWidth() - 1, var7)));
               var2.fillRect(var10 + var12, var9, 8, 8);
               var2.setColor(new Color(var6.getRGB(0, var6.getHeight() - 1)));
               var2.fillRect(var8, var11 + var12, 8, 8);
               var2.setColor(new Color(var6.getRGB(var6.getWidth() - 1, var6.getHeight() - 1)));
               var2.fillRect(var10 + var12, var11 + var12, 8, 8);
            }
         }
      }

      var2.dispose();
      if (width != var1.getWidth()) {
         BufferedImage resized = new BufferedImage(width, width * 2 / 3, BufferedImage.TYPE_INT_RGB);
         Graphics2D graphics = resized.createGraphics();
         graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                   java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
         graphics.drawImage(var1, 0, 0, resized.getWidth(), resized.getHeight(), null);
         graphics.dispose();
         var1 = resized;
      }
      // Preserve atlas layout and normalized UVs. Smaller images reduce the
      // per-model texture accounting used by Cesium's tile cache.
      if (!ImageIO.write(var1, "jpg", var0.toFile())) throw new IOException("JPEG writer unavailable");
      System.out.printf("[textures] atlas dimensions=%dx%d%n", var1.getWidth(), var1.getHeight());
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
      Geometry var3 = new WKTReader().read(var0.getWkt());
      Geometry var4 = var1.simplify(var3);
      var2.setWkt(new WKTWriter().write(var4));
      return var2;
   }

   private static double doubleValue(Object var0, double var1) {
      if (var0 instanceof Number var3) {
         return var3.doubleValue();
      } else {
         if (var0 != null) {
            try {
               return Double.parseDouble(var0.toString().trim());
            } catch (NumberFormatException var4) {
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
      } else {
         Object var2 = var0.getAttribute(var1);
         if (var2 != null) {
            return var2;
         } else {
            for (AttributeDescriptor var4 : var0.getFeatureType().getAttributeDescriptors()) {
               if (var4.getLocalName().equalsIgnoreCase(var1)) {
                  return var0.getAttribute(var4.getLocalName());
               }
            }

            return null;
         }
      }
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
         } else {
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
      String heightAttr = "jzgd";
      String baseZAttr = "base_z";
      String floorsAttr = "lc";
      String usageAttr = "bld_usage";
      boolean textures;
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
            "ecef-enu-v3-compact-atlas",
            Integer.toString(this.textureAtlasWidth),
            this.lod.name(),
            this.idAttr,
            this.heightAttr,
            this.baseZAttr,
            this.floorsAttr,
            this.usageAttr,
            Boolean.toString(this.textures),
            Integer.toString(this.parapetMinLc),
            Double.toString(this.parapetThickness),
            Integer.toString(this.shaftMinLc),
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
                  case "--height-attr":
                     var1.heightAttr = var4;
                     break;
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
                     if (var1.textureAtlasWidth != 384 && var1.textureAtlasWidth != 768 && var1.textureAtlasWidth != 1536)
                        throw new IllegalArgumentException("--texture-atlas-width must be 384, 768 or 1536");
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
         if (this.shp == null || !Files.exists(this.shp)) {
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
         System.out
            .println(
               "SHP -> OBJ -> GLB -> b3dm -> 3D Tiles\n\nThe tileset root lon/lat are derived from the source CRS\n(explicit --prj wins, else the SHP companion .prj) and the\ncentroid mean of every footprint, projected to WGS84.\nNo --lon/--lat/--origin arguments are needed.\n\nRequired:\n  --shp <file.shp>          input polygon shapefile (with .prj alongside)\n  --out <directory>         output directory\n\nCoordinate system:\n  --prj <file.prj>          optional explicit .prj (WKT). Use it when\n                            the SHP has no companion .prj, when the\n                            companion one is wrong, or to reuse one\n                            .prj for a batch of shapefiles.\n\nOptional SHP field overrides (defaults shown):\n  --id-attr <name>           default: bid\n  --height-attr <name>       default: jzgd\n  --base-z-attr <name>       default: base_z\n  --floors-attr <name>       default: lc\n  --usage-attr <name>        default: bld_usage\n\nGeneration switches:\n  --texture-atlas-width 384|768|1536\n                             shared LOD atlas width, default: 384\n  --textures true|false       embed facade textures, default: false\n  --building-lod-chain true|false\n                             regional white L1 + textured L2/L3, default: false\n  --legacy-per-building true|false\n                             use the pre-optimized OBJ pipeline, default: false\n  --keep-intermediate true|false\n                             keep obj/ and glb/ files, default: false\n  --resume true|false          reuse completed .b3dm + .done outputs\n  --parallelism N              conversion workers, default: min(8, CPU)\n  --progress-interval-seconds N\n                             progress log interval, default: 5\n  --lod NONE|L1_SIMPLE|L2_STANDARD|L3_DETAIL\n                             per-building simplification/detail level\n  --tile-max-contents N      batch/spatial capacity, default: 32\n  --tile-max-depth N         max spatial tree depth, default: 12\n  --tile-external-depth N    write nested tilesets from this depth, default: 2\n\nParapet knobs (metres unless noted):\n  --parapet-min-lc      N     lc threshold, default: 5\n  --parapet-min-height   m     default: 0.80\n  --parapet-max-height   m     default: 1.40\n  --parapet-per-floor    m     default: 0.04\n  --parapet-thickness    m     ring wall thickness, default: 0.40\n\nRooftop machine-room knobs (single shaft, metres):\n  --shaft-min-lc         N     lc threshold, default: 9\n  --shaft-inset          m     footprint inward offset, default: 1.50\n  --shaft-clearance      m     shaft-to-boundary clearance, default: 2.00\n  --shaft-max-len        m     max long axis, default: 8.00\n  --shaft-max-wid        m     max short axis, default: 6.00\n  --shaft-max-height     m     max rise, default: 6.00\n  --shaft-center-ratio  ratio central band / full span, default: 0.80\n"
            );
      }
   }

   private record PositionedRecord(ShpTo3dTilesCli.BuildingRecord record, double x, double y) {
   }
}
