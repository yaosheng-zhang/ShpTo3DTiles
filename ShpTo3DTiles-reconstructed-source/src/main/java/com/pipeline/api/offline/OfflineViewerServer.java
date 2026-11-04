package com.pipeline.api.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class OfflineViewerServer {
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final String CLI_MAIN = "com.pipeline.api.cli.ShpTo3dTilesCli";
   private static final Pattern CONTENT = Pattern.compile("\\\"content\\\"");
   private static final Pattern LOD = Pattern.compile("(?:_l[123]\\\\?\\\"|geometricError\\\"\\s*:\\s*(?:18|10|0))");
   private final Path root;
   private final Path jarPath;
   private final int port;
   private final Map<String, OfflineViewerServer.Job> jobs = new ConcurrentHashMap<>();
   private final ExecutorService jobExecutor = Executors.newCachedThreadPool();

   private OfflineViewerServer(Path var1, Path var2, int var3) {
      this.root = var1.toAbsolutePath().normalize();
      this.jarPath = var2.toAbsolutePath().normalize();
      this.port = var3;
   }

   public static void main(String[] var0) throws Exception {
      int var1 = 8930;
      Path var2 = Path.of(System.getProperty("offline.root", "."));

      for (int var3 = 0; var3 < var0.length; var3++) {
         if ("--port".equals(var0[var3]) && var3 + 1 < var0.length) {
            var1 = Integer.parseInt(var0[++var3]);
         } else if ("--root".equals(var0[var3]) && var3 + 1 < var0.length) {
            var2 = Path.of(var0[++var3]);
         }
      }

      Path var5 = Path.of(OfflineViewerServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      OfflineViewerServer var4 = new OfflineViewerServer(var2, var5, var1);
      var4.start();
   }

   private void start() throws IOException {
      HttpServer var1 = HttpServer.create(new InetSocketAddress("127.0.0.1", this.port), 0);
      var1.createContext("/api/status", this::status);
      var1.createContext("/api/convert", this::convert);
      var1.createContext("/api/job", this::jobStatus);
      var1.createContext("/api/inspect", this::inspect);
      var1.createContext("/api/patch-geometric-error", this::patchGeometricError);
      var1.createContext("/api/commit-geometric-error", this::commitGeometricError);
      var1.createContext("/api/telemetry", this::telemetry);
      var1.createContext("/api/browse", this::browse);
      var1.createContext("/api/pick", this::pick);
      var1.createContext("/fs/", this::serveAbsoluteFile);
      var1.createContext("/", this::serveStatic);
      var1.setExecutor(Executors.newCachedThreadPool());
      var1.start();
      System.out.printf("[offline-viewer] http://127.0.0.1:%d/%n", this.port);
      System.out.printf("[offline-viewer] root=%s%n", this.root);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         var1.stop(0);
         this.jobExecutor.shutdownNow();
      }));
   }

   private void status(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         ObjectNode var2 = JSON.createObjectNode();
         var2.put("java", javaExecutable().toString());
         var2.put("jar", this.jarPath.toString());
         var2.put("root", this.root.toString());
         var2.put("stressOutput", "");
         ObjectNode var3 = var2.putObject("converter");
         var3.put("mode", "offline-jar");
         var3.put("path", this.jarPath.toString());
         var3.put("classpath", this.jarPath.toString());
         sendJson(var1, var2);
      }
   }

   private void convert(HttpExchange var1) throws IOException {
      if (method(var1, "POST")) {
         JsonNode var2;
         try {
            var2 = JSON.readTree(var1.getRequestBody());
         } catch (Exception var12) {
            sendError(var1, 400, "请求 JSON 无效: " + var12.getMessage());
            return;
         }

         String var3 = text(var2, "shp");
         String var4 = text(var2, "out");
         if (!var3.isBlank() && !var4.isBlank()) {
            Path var5 = Path.of(var3);
            if (Files.isRegularFile(var5) && var3.toLowerCase().endsWith(".shp")) {
               ArrayList var6 = new ArrayList();
               var6.add(javaExecutable().toString());
               var6.add("-Dfile.encoding=UTF-8");
               var6.add("-cp");
               var6.add(this.jarPath.toString());
               var6.add("com.pipeline.api.cli.ShpTo3dTilesCli");
               var6.add("--shp");
               var6.add(var3);
               var6.add("--out");
               var6.add(var4);
               String var7 = text(var2, "prj");
               if (!var7.isBlank()) {
                  var6.add("--prj");
                  var6.add(var7);
               }

               JsonNode var8 = var2.path("params");
               var6.add("--metadata-fields");
               var6.add(var8.path("metadataFields").asText("bldUseage,entityid,wkBldId").trim());
               addParam(var8, var6, "whiteTexture", "--white-texture", "db_sumo_bui_001");
               addParam(var8, var6, "layeredTexture", "--layered-texture", "dise_G1_dx_02");
               addParam(var8, var6, "floorLineThreshold", "--floor-line-threshold", "10");
               addParam(var8, var6, "parapetMinLc", "--parapet-min-lc", "5");
               addParam(var8, var6, "parapetMinHeight", "--parapet-min-height", "0.80");
               addParam(var8, var6, "parapetMaxHeight", "--parapet-max-height", "1.40");
               addParam(var8, var6, "parapetPerFloor", "--parapet-per-floor", "0.04");
               addParam(var8, var6, "parapetThickness", "--parapet-thickness", "0.40");
               addParam(var8, var6, "shaftMinLc", "--shaft-min-lc", "9");
               addParam(var8, var6, "shaftInset", "--shaft-inset", "1.50");
               addParam(var8, var6, "shaftClearance", "--shaft-clearance", "2.00");
               addParam(var8, var6, "shaftMaxLen", "--shaft-max-len", "8.00");
               addParam(var8, var6, "shaftMaxWid", "--shaft-max-wid", "6.00");
               addParam(var8, var6, "shaftMaxHeight", "--shaft-max-height", "6.00");
               addParam(var8, var6, "shaftCenterRatio", "--shaft-center-ratio", "0.80");
               addParam(var8, var6, "lod", "--lod", "NONE");
               addParam(var8, var6, "tileMaxContents", "--tile-max-contents", "32");
               addParam(var8, var6, "tileMaxDepth", "--tile-max-depth", "12");
               addParam(var8, var6, "tileExternalDepth", "--tile-external-depth", "2");
               addParam(var8, var6, "parallelism", "--parallelism", "4");
               addParam(var8, var6, "progressIntervalSeconds", "--progress-interval-seconds", "5");
               var6.add("--textures");
               var6.add(Boolean.toString(var8.path("textures").asBoolean(true)));
               addBoolean(var8, var6, "buildingLodChain", "--building-lod-chain");
               addBoolean(var8, var6, "legacyPerBuilding", "--legacy-per-building");
               addBoolean(var8, var6, "resume", "--resume");
               addBoolean(var8, var6, "keepIntermediate", "--keep-intermediate");
               String var9 = UUID.randomUUID().toString().replace("-", "");
               OfflineViewerServer.Job var10 = new OfflineViewerServer.Job(var9, var6, var4);
               this.jobs.put(var9, var10);
               this.jobExecutor.submit(() -> this.runJob(var10));
               ObjectNode var11 = JSON.createObjectNode();
               var11.put("job", var9);
               var11.put("out", Path.of(var4).toAbsolutePath().normalize().toString());
               sendJson(var1, var11);
            } else {
               sendError(var1, 400, "SHP 文件不存在或扩展名错误: " + var3);
            }
         } else {
            sendError(var1, 400, "输入 SHP 和输出目录不能为空");
         }
      }
   }

   private void runJob(OfflineViewerServer.Job var1) {
      var1.log.add("[offline-server] command: " + String.join(" ", var1.command));
      long var2 = System.nanoTime();

      try {
         Process var4 = new ProcessBuilder(var1.command).redirectErrorStream(true).start();

         try (InputStream var5 = var4.getInputStream()) {
            byte[] var6 = new byte[4096];
            StringBuilder var7 = new StringBuilder();

            int var8;
            while ((var8 = var5.read(var6)) >= 0) {
               String var9 = new String(var6, 0, var8, StandardCharsets.UTF_8);

               for (int var10 = 0; var10 < var9.length(); var10++) {
                  char var11 = var9.charAt(var10);
                  if (var11 == '\n') {
                     var1.addLog(var7.toString());
                     var7.setLength(0);
                  } else if (var11 != '\r') {
                     var7.append(var11);
                  }
               }
            }

            if (!var7.isEmpty()) {
               var1.addLog(var7.toString());
            }
         }

         var1.code = var4.waitFor();
      } catch (Exception var19) {
         var1.addLog("[offline-server] " + var19);
         var1.code = -1;
      } finally {
         var1.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - var2);
         var1.running = false;
      }
   }

   private void jobStatus(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         String var2 = query(var1.getRequestURI(), "id");
         OfflineViewerServer.Job var3 = this.jobs.get(var2);
         if (var3 == null) {
            sendError(var1, 404, "unknown job");
         } else {
            ObjectNode var4 = JSON.createObjectNode();
            var4.put("running", var3.running);
            var4.put("code", var3.code == null ? 0 : var3.code);
            var4.put("out", var3.out);
            var4.put("elapsedMs", var3.elapsedMs);
            var4.put("kind", "convert");
            ArrayNode var5 = var4.putArray("log");
            synchronized (var3.log) {
               for (String var8 : var3.log) {
                  var5.add(var8);
               }
            }

            sendJson(var1, var4);
         }
      }
   }

   private void patchGeometricError(HttpExchange var1) throws IOException {
      if (method(var1, "POST")) {
         try {
            JsonNode var2 = JSON.readTree(var1.getRequestBody());
            Path var3 = Path.of(text(var2, "source")).toAbsolutePath().normalize();
            if (Files.isDirectory(var3)) {
               var3 = var3.resolve("tileset.json");
            }

            if (!Files.isRegularFile(var3) || !var3.getFileName().toString().equalsIgnoreCase("tileset.json")) {
               sendError(var1, 400, "tileset.json not found: " + var3);
               return;
            }

            double[] var4 = new double[]{var2.path("l1").asDouble(-1.0), var2.path("l2").asDouble(-1.0), var2.path("l3").asDouble(-1.0)};

            for (double var8 : var4) {
               if (!Double.isFinite(var8) || var8 < 0.0) {
                  sendError(var1, 400, "geometricError must be >= 0");
                  return;
               }
            }

            Path var11 = var3.getParent().toAbsolutePath().normalize();
            JsonNode var12 = JSON.readTree(Files.readAllBytes(var3));
            int var13 = patchGeometricErrorDocument(var12, var3, var11, var4, new HashSet<>());
            Path var14 = var3.resolveSibling("tileset.preview-geometric-error.json");
            Files.write(var14, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(var12));
            ObjectNode var9 = JSON.createObjectNode();
            var9.put("path", var14.toString());
            var9.put("changed", var13);
            sendJson(var1, var9);
         } catch (Exception var10) {
            sendError(var1, 400, var10.toString());
         }
      }
   }

   private static int patchGeometricErrorDocument(JsonNode var0, Path var1, Path var2, double[] var3, Set<Path> var4) throws IOException {
      Path var5 = var1.toAbsolutePath().normalize();
      return var5.startsWith(var2) && var4.add(var5) ? patchGeometricErrorNode(var0.path("root"), var5, var2, var3, var4) : 0;
   }

   private static int patchGeometricErrorNode(JsonNode var0, Path var1, Path var2, double[] var3, Set<Path> var4) throws IOException {
      if (var0 != null && var0.isObject()) {
         int var5 = 0;
         JsonNode var6 = var0.path("content");
         String var7 = var6.path("uri").asText("");
         String var8 = var7.toLowerCase(Locale.ROOT);

         for (int var9 = 1; var9 <= 3; var9++) {
            if (var8.endsWith("_l" + var9 + ".b3dm") || var8.endsWith("_l" + var9 + ".glb")) {
               ((ObjectNode)var0).put("geometricError", var3[var9 - 1]);
               var5++;
               break;
            }
         }

         if (var6.isObject() && var7.toLowerCase(Locale.ROOT).endsWith(".json")) {
            Path var13 = resolveTilesetUri(var1, var7, var2);
            if (var13 != null && Files.isRegularFile(var13)) {
               boolean var10 = var4.contains(var13.toAbsolutePath().normalize());
               JsonNode var11 = JSON.readTree(Files.readAllBytes(var13));
               var5 += patchGeometricErrorDocument(var11, var13, var2, var3, var4);
               Path var12 = previewPath(var13);
               if (!var10) {
                  Files.write(var12, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(var11));
               }

               ((ObjectNode)var6).put("uri", relativeUri(var1.getParent(), var12));
            }
         }

         for (JsonNode var15 : var0.path("children")) {
            var5 += patchGeometricErrorNode(var15, var1, var2, var3, var4);
         }

         return var5;
      } else {
         return 0;
      }
   }

   private static Path resolveTilesetUri(Path var0, String var1, Path var2) {
      try {
         String var3 = URI.create(var1).getPath();
         if (var3 != null && !var3.isBlank() && !var3.startsWith("/")) {
            Path var4 = var0.getParent().resolve(var3).normalize().toAbsolutePath();
            return var4.startsWith(var2) ? var4 : null;
         } else {
            return null;
         }
      } catch (RuntimeException var5) {
         return null;
      }
   }

   private static Path previewPath(Path var0) {
      String var1 = var0.getFileName().toString();
      int var2 = var1.toLowerCase(Locale.ROOT).lastIndexOf(".json");
      return var0.resolveSibling((var2 < 0 ? var1 : var1.substring(0, var2)) + ".preview-geometric-error.json");
   }

   private static String relativeUri(Path var0, Path var1) {
      String var2 = var0.relativize(var1).toString().replace('\\', '/');

      try {
         return new URI(null, null, var2, null).toASCIIString();
      } catch (URISyntaxException var4) {
         throw new IllegalArgumentException("Invalid tileset URI: " + var2, var4);
      }
   }

   private void commitGeometricError(HttpExchange var1) throws IOException {
      if (method(var1, "POST")) {
         try {
            JsonNode var2 = JSON.readTree(var1.getRequestBody());
            Path var3 = Path.of(text(var2, "source")).toAbsolutePath().normalize();
            if (Files.isDirectory(var3)) {
               var3 = var3.resolve("tileset.json");
            }

            Path var4;
            Path var5;
            if (var3.getFileName().toString().equalsIgnoreCase("tileset.preview-geometric-error.json")) {
               var4 = var3;
               var5 = var3.resolveSibling("tileset.json");
            } else {
               var5 = var3;
               var4 = var3.resolveSibling("tileset.preview-geometric-error.json");
            }

            if (!Files.isRegularFile(var5) || !Files.isRegularFile(var4)) {
               Path var11 = !Files.isRegularFile(var4) ? var4 : var5;
               sendError(var1, 404, "找不到 geometricError 预览或原始 tileset: " + var11);
               return;
            }

            Path var6 = var5.getParent().toAbsolutePath().normalize();
            HashSet var7 = new HashSet();
            commitPreviewDocument(var4, var6, var7);
            Path var8 = var5.resolveSibling("tileset.json.backup");
            ObjectNode var9 = JSON.createObjectNode();
            var9.put("path", var5.toString());
            var9.put("backup", var8.toString());
            sendJson(var1, var9);
         } catch (Exception var10) {
            sendError(var1, 500, var10.toString());
         }
      }
   }

   private static void commitPreviewDocument(Path var0, Path var1, Set<Path> var2) throws IOException {
      Path var3 = var0.toAbsolutePath().normalize();
      if (!var3.startsWith(var1)) {
         throw new IOException("Preview tileset escapes output directory: " + var0);
      }

      Path var4 = originalPath(var3);
      if (var2.add(var4)) {
         JsonNode var5 = JSON.readTree(Files.readAllBytes(var3));
         commitPreviewNode(var5.path("root"), var3, var1, var2);
         if (var4.startsWith(var1) && Files.isRegularFile(var4)) {
            Path var6 = var4.resolveSibling(var4.getFileName() + ".backup");
            Files.copy(var4, var6, StandardCopyOption.REPLACE_EXISTING);
            Files.write(var4, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(var5));
            Files.deleteIfExists(var3);
         } else {
            throw new IOException("Original tileset not found: " + var4);
         }
      }
   }

   private static void commitPreviewNode(JsonNode var0, Path var1, Path var2, Set<Path> var3) throws IOException {
      if (var0 != null && var0.isObject()) {
         JsonNode var4 = var0.path("content");
         String var5 = var4.path("uri").asText("");
         if (var4.isObject() && var5.toLowerCase(Locale.ROOT).endsWith(".preview-geometric-error.json")) {
            Path var6 = resolveTilesetUri(var1, var5, var2);
            if (var6 == null) {
               throw new IOException("Invalid preview tileset URI: " + var5);
            }

            if (!var3.contains(originalPath(var6))) {
               commitPreviewDocument(var6, var2, var3);
            }

            ((ObjectNode)var4).put("uri", relativeUri(var1.getParent(), originalPath(var6)));
         }

         for (JsonNode var7 : var0.path("children")) {
            commitPreviewNode(var7, var1, var2, var3);
         }
      }
   }

   private static Path originalPath(Path var0) {
      String var1 = var0.getFileName().toString();
      String var2 = ".preview-geometric-error.json";
      if (var1.toLowerCase(Locale.ROOT).endsWith(var2)) {
         var1 = var1.substring(0, var1.length() - var2.length()) + ".json";
      }

      return var0.resolveSibling(var1).toAbsolutePath().normalize();
   }

   private void inspect(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         String var2 = query(var1.getRequestURI(), "path");
         if (var2 != null && !var2.isBlank()) {
            Path var3 = Path.of(var2).toAbsolutePath().normalize();
            if (Files.isRegularFile(var3)) {
               var3 = var3.getParent();
            }

            if (var3 != null && Files.isDirectory(var3)) {
               long var4 = 0L;
               long var6 = 0L;
               long var8 = 0L;
               long var10 = 0L;
               long var12 = 0L;
               long var14 = 0L;

                try (Stream<Path> var16 = Files.walk(var3)) {
                   for (Path var18 : (Iterable<Path>)var16::iterator) {
                     if (Files.isRegularFile(var18)) {
                        var6++;
                        var4 += Files.size(var18);
                        String var19 = var18.getFileName().toString().toLowerCase();
                        if (var19.endsWith(".glb")) {
                           var10++;
                        }

                        if (var19.endsWith(".json")) {
                           var8++;
                           String var20 = new String(Files.readAllBytes(var18), StandardCharsets.UTF_8);
                           var12 += count(CONTENT, var20);
                           var14 += count(LOD, var20);
                        }
                     }
                  }
               }

               ObjectNode var23 = JSON.createObjectNode();
               var23.put("root", var3.toAbsolutePath().normalize().toString());
               var23.put("files", var6);
               var23.put("jsonFiles", var8);
               var23.put("glbFiles", var10);
               var23.put("contentNodes", var12);
               var23.put("lodNodes", var14);
               var23.put("bytes", var4);
               sendJson(var1, var23);
            } else {
               sendError(var1, 400, "输出目录不存在");
            }
         } else {
            sendError(var1, 400, "输出目录或 tileset.json 路径不能为空");
         }
      }
   }

   private void telemetry(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         ObjectNode var2 = JSON.createObjectNode();
         var2.put("timestamp", Instant.now().toEpochMilli() / 1000.0);
         Runtime var3 = Runtime.getRuntime();
         ObjectNode var4 = var2.putObject("server");
         var4.put("rssBytes", var3.totalMemory() - var3.freeMemory());
         var4.put("systemUsedBytes", 0);
         var4.put("systemTotalBytes", var3.maxMemory());
         var2.putArray("gpu");
         sendJson(var1, var2);
      }
   }

   private void pick(HttpExchange var1) throws IOException {
      if (method(var1, "POST")) {
         try {
            JsonNode var2 = JSON.readTree(var1.getRequestBody());
            String var3 = var2.path("kind").asText();
            if (!List.of("shp", "prj", "texture", "directory", "result").contains(var3)) {
               sendError(var1, 400, "Unsupported file selection type");
               return;
            }

            String var4 = var2.path("initial").asText("");
            if (var4.isBlank()) {
               var4 = this.root.toString();
            }

            Process var5 = new ProcessBuilder(
                  "powershell.exe",
                  "-NoProfile",
                  "-STA",
                  "-WindowStyle",
                  "Hidden",
                  "-ExecutionPolicy",
                  "Bypass",
                  "-File",
                  this.root.resolve("native-picker.ps1").toString(),
                  "-Kind",
                  var3,
                  "-InitialPath",
                  var4
               )
               .redirectErrorStream(true)
               .start();
            String var6 = new String(var5.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\ufeff", "").trim();
            var5.waitFor();
            JsonNode var7 = JSON.readTree(var6);
            if (var7.has("error")) {
               sendError(var1, 500, var7.path("error").asText());
               return;
            }

            sendJson(var1, var7);
         } catch (Exception var8) {
            sendError(var1, 500, "Cannot open system file picker: " + var8.getMessage());
         }
      }
   }

   private void browse(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         String var2 = query(var1.getRequestURI(), "path");
         if (!var2.isBlank()) {
            Path var12 = Path.of(var2).toAbsolutePath().normalize();
            if (!Files.isDirectory(var12)) {
               sendError(var1, 400, "目录不存在");
            } else {
               ObjectNode var13 = JSON.createObjectNode();
               var13.put("path", var12.toAbsolutePath().normalize().toString());
               var13.put("parent", var12.getParent() == null ? "" : var12.getParent().toString());
               ArrayNode var14 = var13.putArray("dirs");
               ArrayNode var15 = var13.putArray("files");

                try (Stream<Path> var16 = Files.list(var12)) {
                  var16.sorted()
                     .forEach(
                        var2x -> {
                           if (Files.isDirectory(var2x)) {
                              var14.add(var2x.getFileName().toString());
                           } else if (Files.isRegularFile(var2x)
                              && (
                                 var2x.toString().toLowerCase().matches(".*\\.(shp|prj|jpg|jpeg|png)$")
                                    || var2x.getFileName().toString().equalsIgnoreCase("tileset.json")
                              )) {
                              var15.add(var2x.getFileName().toString());
                           }
                        }
                     );
               }

               sendJson(var1, var13);
            }
         } else {
            ObjectNode var3 = JSON.createObjectNode();
            ArrayNode var4 = var3.putArray("drives");

            for (File var8 : File.listRoots()) {
               var4.add(var8.toString());
            }

            sendJson(var1, var3);
         }
      }
   }

   private void serveAbsoluteFile(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         String var2 = var1.getRequestURI().getRawPath().substring("/fs/".length());

         Path var3;
         try {
            var3 = Path.of(URLDecoder.decode(var2, StandardCharsets.UTF_8));
         } catch (Exception var5) {
            sendError(var1, 400, "路径无效");
            return;
         }

         if (var3.isAbsolute() && Files.isRegularFile(var3)) {
            sendBytes(var1, Files.readAllBytes(var3), contentType(var3), true);
         } else {
            sendError(var1, 404, "文件不存在");
         }
      }
   }

   private void serveStatic(HttpExchange var1) throws IOException {
      if (method(var1, "GET")) {
         String var2 = var1.getRequestURI().getPath();
         if (var2.equals("/") || var2.isBlank()) {
            var2 = "/webviewer.html";
         }

         Path var3 = this.root.resolve(var2.substring(1)).normalize();
         if (var3.startsWith(this.root) && Files.isRegularFile(var3)) {
            sendBytes(var1, Files.readAllBytes(var3), contentType(var3), false);
         } else {
            sendError(var1, 404, "资源不存在");
         }
      }
   }

   private static void addParam(JsonNode var0, List<String> var1, String var2, String var3, String var4) {
      JsonNode var5 = var0 == null ? null : var0.get(var2);
      String var6 = var5 != null && !var5.isNull() && !var5.asText().isBlank() ? var5.asText() : var4;
      var1.add(var3);
      var1.add(var6);
   }

   private static void addBoolean(JsonNode var0, List<String> var1, String var2, String var3) {
      if (var0 != null && var0.path(var2).asBoolean(false)) {
         var1.add(var3);
         var1.add("true");
      }
   }

   private static String text(JsonNode var0, String var1) {
      return var0 == null ? "" : var0.path(var1).asText("").trim();
   }

   private static int count(Pattern var0, String var1) {
      Matcher var2 = var0.matcher(var1);
      int var3 = 0;

      while (var2.find()) {
         var3++;
      }

      return var3;
   }

   private static Path javaExecutable() {
      Path var0 = Path.of(System.getProperty("java.home"), "bin");
      return var0.resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
   }

   private static String query(URI var0, String var1) {
      String var2 = var0.getRawQuery();
      if (var2 == null) {
         return "";
      }

      for (String var6 : var2.split("&")) {
         String[] var7 = var6.split("=", 2);
         if (var7.length == 2 && var1.equals(URLDecoder.decode(var7[0], StandardCharsets.UTF_8))) {
            return URLDecoder.decode(var7[1], StandardCharsets.UTF_8);
         }
      }

      return "";
   }

   private static String contentType(Path var0) {
      String var1 = var0.getFileName().toString().toLowerCase();
      if (var1.endsWith(".html")) {
         return "text/html; charset=utf-8";
      } else if (var1.endsWith(".js")) {
         return "application/javascript; charset=utf-8";
      } else if (var1.endsWith(".css")) {
         return "text/css; charset=utf-8";
      } else if (var1.endsWith(".json")) {
         return "application/json; charset=utf-8";
      } else if (var1.endsWith(".jpg") || var1.endsWith(".jpeg")) {
         return "image/jpeg";
      } else if (var1.endsWith(".png")) {
         return "image/png";
      } else if (var1.endsWith(".glb")) {
         return "model/gltf-binary";
      } else {
         return var1.endsWith(".wasm") ? "application/wasm" : "application/octet-stream";
      }
   }

   private static boolean method(HttpExchange var0, String var1) throws IOException {
      if (!var1.equalsIgnoreCase(var0.getRequestMethod())) {
         var0.sendResponseHeaders(405, -1L);
         return false;
      } else {
         return true;
      }
   }

   private static void sendError(HttpExchange var0, int var1, String var2) throws IOException {
      ObjectNode var3 = JSON.createObjectNode();
      var3.put("error", var2);
      sendJson(var0, var1, var3);
   }

   private static void sendJson(HttpExchange var0, JsonNode var1) throws IOException {
      sendJson(var0, 200, var1);
   }

   private static void sendJson(HttpExchange var0, int var1, JsonNode var2) throws IOException {
      sendBytes(var0, JSON.writeValueAsBytes(var2), "application/json; charset=utf-8", false, var1);
   }

   private static void sendBytes(HttpExchange var0, byte[] var1, String var2, boolean var3) throws IOException {
      sendBytes(var0, var1, var2, var3, 200);
   }

   private static void sendBytes(HttpExchange var0, byte[] var1, String var2, boolean var3, int var4) throws IOException {
      Headers var5 = var0.getResponseHeaders();
      var5.set("Content-Type", var2);
      if (var3) {
         var5.set("Cache-Control", "no-store");
      } else if (var2.startsWith("text/") || var2.startsWith("application/javascript") || var2.startsWith("application/json")) {
         var5.set("Cache-Control", "no-store, no-cache, must-revalidate");
      }

      var0.sendResponseHeaders(var4, var1.length);

      try (OutputStream var6 = var0.getResponseBody()) {
         var6.write(var1);
      }
   }

   private static final class Job {
      final String id;
      final List<String> command;
      final String out;
      final List<String> log = Collections.synchronizedList(new ArrayList<>());
      volatile boolean running = true;
      volatile Integer code;
      volatile long elapsedMs;

      Job(String var1, List<String> var2, String var3) {
         this.id = var1;
         this.command = List.copyOf(var2);
         this.out = var3;
      }

      void addLog(String var1) {
         synchronized (this.log) {
            this.log.add(var1);
            if (this.log.size() > 2000) {
               this.log.remove(0);
            }
         }
      }
   }
}
