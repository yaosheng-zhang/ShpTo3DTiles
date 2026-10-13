package com.pipeline.api.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Small dependency-free HTTP host for the offline release package.
 * It serves the local workbench/Cesium assets and starts the CLI in a child
 * JVM, so the Web package does not require Python or an Internet connection.
 */
public final class OfflineViewerServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLI_MAIN = "com.pipeline.api.cli.ShpTo3dTilesCli";
    private static final Pattern CONTENT = Pattern.compile("\\\"content\\\"");
    private static final Pattern LOD = Pattern.compile("(?:_l[123]\\\\?\\\"|geometricError\\\"\\s*:\\s*(?:18|10|0))");

    private final Path root;
    private final Path jarPath;
    private final int port;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newCachedThreadPool();

    private OfflineViewerServer(Path root, Path jarPath, int port) {
        this.root = root.toAbsolutePath().normalize();
        this.jarPath = jarPath.toAbsolutePath().normalize();
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port = 8930;
        Path root = Path.of(System.getProperty("offline.root", "."));
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if ("--root".equals(args[i]) && i + 1 < args.length) root = Path.of(args[++i]);
        }
        Path jar = Path.of(OfflineViewerServer.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        OfflineViewerServer app = new OfflineViewerServer(root, jar, port);
        app.start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/status", this::status);
        server.createContext("/api/convert", this::convert);
        server.createContext("/api/job", this::jobStatus);
        server.createContext("/api/inspect", this::inspect);
        server.createContext("/api/telemetry", this::telemetry);
        server.createContext("/api/browse", this::browse);
        server.createContext("/fs/", this::serveAbsoluteFile);
        server.createContext("/", this::serveStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("[offline-viewer] http://127.0.0.1:%d/%n", port);
        System.out.printf("[offline-viewer] root=%s%n", root);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            jobExecutor.shutdownNow();
        }));
    }

    private void status(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        ObjectNode result = JSON.createObjectNode();
        result.put("java", javaExecutable().toString());
        result.put("jar", jarPath.toString());
        result.put("root", root.toString());
        result.put("stressOutput", "");
        ObjectNode converter = result.putObject("converter");
        converter.put("mode", "offline-jar");
        converter.put("path", jarPath.toString());
        converter.put("classpath", jarPath.toString());
        sendJson(exchange, result);
    }

    private void convert(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        JsonNode request;
        try {
            request = JSON.readTree(exchange.getRequestBody());
        } catch (Exception ex) {
            sendError(exchange, 400, "请求 JSON 无效: " + ex.getMessage());
            return;
        }
        String shp = text(request, "shp");
        String out = text(request, "out");
        if (shp.isBlank() || out.isBlank()) {
            sendError(exchange, 400, "输入 SHP 和输出目录不能为空");
            return;
        }
        Path shpPath = Path.of(shp);
        if (!Files.isRegularFile(shpPath) || !shp.toLowerCase().endsWith(".shp")) {
            sendError(exchange, 400, "SHP 文件不存在或扩展名错误: " + shp);
            return;
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(jarPath.toString());
        command.add(CLI_MAIN);
        command.add("--shp"); command.add(shp);
        command.add("--out"); command.add(out);
        String prj = text(request, "prj");
        if (!prj.isBlank()) { command.add("--prj"); command.add(prj); }
        JsonNode params = request.path("params");
        addParam(params, command, "parapetMinLc", "--parapet-min-lc", "5");
        addParam(params, command, "parapetMinHeight", "--parapet-min-height", "0.80");
        addParam(params, command, "parapetMaxHeight", "--parapet-max-height", "1.40");
        addParam(params, command, "parapetPerFloor", "--parapet-per-floor", "0.04");
        addParam(params, command, "parapetThickness", "--parapet-thickness", "0.40");
        addParam(params, command, "shaftMinLc", "--shaft-min-lc", "9");
        addParam(params, command, "shaftInset", "--shaft-inset", "1.50");
        addParam(params, command, "shaftClearance", "--shaft-clearance", "2.00");
        addParam(params, command, "shaftMaxLen", "--shaft-max-len", "8.00");
        addParam(params, command, "shaftMaxWid", "--shaft-max-wid", "6.00");
        addParam(params, command, "shaftMaxHeight", "--shaft-max-height", "6.00");
        addParam(params, command, "shaftCenterRatio", "--shaft-center-ratio", "0.80");
        addParam(params, command, "lod", "--lod", "L2_STANDARD");
        addParam(params, command, "tileMaxContents", "--tile-max-contents", "32");
        addParam(params, command, "tileMaxDepth", "--tile-max-depth", "12");
        addParam(params, command, "tileExternalDepth", "--tile-external-depth", "2");
        addParam(params, command, "parallelism", "--parallelism", "4");
        addParam(params, command, "progressIntervalSeconds", "--progress-interval-seconds", "5");
        addBoolean(params, command, "textures", "--textures");
        addBoolean(params, command, "buildingLodChain", "--building-lod-chain");
        addBoolean(params, command, "legacyPerBuilding", "--legacy-per-building");
        addBoolean(params, command, "resume", "--resume");
        addBoolean(params, command, "keepIntermediate", "--keep-intermediate");

        String id = UUID.randomUUID().toString().replace("-", "");
        Job job = new Job(id, command, out);
        jobs.put(id, job);
        jobExecutor.submit(() -> runJob(job));
        ObjectNode response = JSON.createObjectNode();
        response.put("job", id);
        response.put("out", Path.of(out).toAbsolutePath().normalize().toString());
        sendJson(exchange, response);
    }

    private void runJob(Job job) {
        job.log.add("[offline-server] command: " + String.join(" ", job.command));
        long started = System.nanoTime();
        try {
            Process process = new ProcessBuilder(job.command)
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                StringBuilder line = new StringBuilder();
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                    for (int i = 0; i < chunk.length(); i++) {
                        char c = chunk.charAt(i);
                        if (c == '\n') { job.addLog(line.toString()); line.setLength(0); }
                        else if (c != '\r') line.append(c);
                    }
                }
                if (!line.isEmpty()) job.addLog(line.toString());
            }
            job.code = process.waitFor();
        } catch (Exception ex) {
            job.addLog("[offline-server] " + ex);
            job.code = -1;
        } finally {
            job.elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            job.running = false;
        }
    }

    private void jobStatus(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        String id = query(exchange.getRequestURI(), "id");
        Job job = jobs.get(id);
        if (job == null) { sendError(exchange, 404, "unknown job"); return; }
        ObjectNode result = JSON.createObjectNode();
        result.put("running", job.running);
        result.put("code", job.code == null ? 0 : job.code);
        result.put("out", job.out);
        result.put("elapsedMs", job.elapsedMs);
        result.put("kind", "convert");
        var logs = result.putArray("log");
        synchronized (job.log) { for (String line : job.log) logs.add(line); }
        sendJson(exchange, result);
    }

    private void inspect(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        Path path = Path.of(query(exchange.getRequestURI(), "path"));
        if (Files.isRegularFile(path)) path = path.getParent();
        if (path == null || !Files.isDirectory(path)) { sendError(exchange, 400, "输出目录不存在"); return; }
        long bytes = 0, files = 0, jsonFiles = 0, b3dmFiles = 0, content = 0, lod = 0;
        try (var stream = Files.walk(path)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                files++; bytes += Files.size(file);
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".b3dm")) b3dmFiles++;
                if (!name.endsWith(".json")) continue;
                jsonFiles++;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                content += count(CONTENT, text);
                lod += count(LOD, text);
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("root", path.toAbsolutePath().normalize().toString());
        result.put("files", files); result.put("jsonFiles", jsonFiles);
        result.put("b3dmFiles", b3dmFiles); result.put("contentNodes", content);
        result.put("lodNodes", lod); result.put("bytes", bytes);
        sendJson(exchange, result);
    }

    private void telemetry(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        ObjectNode result = JSON.createObjectNode();
        result.put("timestamp", Instant.now().toEpochMilli() / 1000.0);
        Runtime runtime = Runtime.getRuntime();
        ObjectNode memory = result.putObject("server");
        memory.put("rssBytes", runtime.totalMemory() - runtime.freeMemory());
        memory.put("systemUsedBytes", 0); memory.put("systemTotalBytes", runtime.maxMemory());
        result.putArray("gpu");
        sendJson(exchange, result);
    }

    private void browse(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        Path path = Path.of(query(exchange.getRequestURI(), "path"));
        if (!Files.isDirectory(path)) { sendError(exchange, 400, "目录不存在"); return; }
        ObjectNode result = JSON.createObjectNode(); result.put("path", path.toAbsolutePath().normalize().toString());
        var dirs = result.putArray("dirs"); var files = result.putArray("files");
        try (var stream = Files.list(path)) {
            stream.sorted().forEach(p -> {
                if (Files.isDirectory(p)) dirs.add(p.getFileName().toString());
                else if (p.toString().toLowerCase().endsWith(".shp") || p.toString().toLowerCase().endsWith(".prj")) files.add(p.getFileName().toString());
            });
        }
        sendJson(exchange, result);
    }

    private void serveAbsoluteFile(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        String raw = exchange.getRequestURI().getRawPath().substring("/fs/".length());
        Path file;
        try { file = Path.of(URLDecoder.decode(raw, StandardCharsets.UTF_8)); }
        catch (Exception ex) { sendError(exchange, 400, "路径无效"); return; }
        if (!file.isAbsolute() || !Files.isRegularFile(file)) { sendError(exchange, 404, "文件不存在"); return; }
        sendBytes(exchange, Files.readAllBytes(file), contentType(file), true);
    }

    private void serveStatic(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        String raw = exchange.getRequestURI().getPath();
        if (raw.equals("/") || raw.isBlank()) raw = "/webviewer.html";
        Path file = root.resolve(raw.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) { sendError(exchange, 404, "资源不存在"); return; }
        sendBytes(exchange, Files.readAllBytes(file), contentType(file), false);
    }

    private static void addParam(JsonNode params, List<String> command, String key, String flag, String fallback) {
        JsonNode value = params == null ? null : params.get(key);
        String text = value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
        command.add(flag); command.add(text);
    }

    private static void addBoolean(JsonNode params, List<String> command, String key, String flag) {
        if (params != null && params.path(key).asBoolean(false)) { command.add(flag); command.add("true"); }
    }

    private static String text(JsonNode node, String key) { return node == null ? "" : node.path(key).asText("").trim(); }
    private static int count(Pattern pattern, String value) { var m = pattern.matcher(value); int n = 0; while (m.find()) n++; return n; }
    private static Path javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        return bin.resolve(System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
    }
    private static String query(URI uri, String key) {
        String raw = uri.getRawQuery(); if (raw == null) return "";
        for (String pair : raw.split("&")) { String[] kv = pair.split("=", 2); if (kv.length == 2 && key.equals(URLDecoder.decode(kv[0], StandardCharsets.UTF_8))) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8); }
        return "";
    }
    private static String contentType(Path path) {
        String s = path.getFileName().toString().toLowerCase();
        if (s.endsWith(".html")) return "text/html; charset=utf-8";
        if (s.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (s.endsWith(".css")) return "text/css; charset=utf-8";
        if (s.endsWith(".json")) return "application/json; charset=utf-8";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }
    private static boolean method(HttpExchange exchange, String expected) throws IOException { if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return false; } return true; }
    private static void sendError(HttpExchange e, int status, String message) throws IOException { ObjectNode o = JSON.createObjectNode(); o.put("error", message); sendJson(e, status, o); }
    private static void sendJson(HttpExchange e, JsonNode value) throws IOException { sendJson(e, 200, value); }
    private static void sendJson(HttpExchange e, int status, JsonNode value) throws IOException { sendBytes(e, JSON.writeValueAsBytes(value), "application/json; charset=utf-8", false, status); }
    private static void sendBytes(HttpExchange e, byte[] data, String type, boolean noStore) throws IOException { sendBytes(e, data, type, noStore, 200); }
    private static void sendBytes(HttpExchange e, byte[] data, String type, boolean noStore, int status) throws IOException {
        Headers headers = e.getResponseHeaders(); headers.set("Content-Type", type); if (noStore) headers.set("Cache-Control", "no-store");
        e.sendResponseHeaders(status, data.length); try (OutputStream out = e.getResponseBody()) { out.write(data); }
    }

    private static final class Job {
        final String id; final List<String> command; final String out; final List<String> log = Collections.synchronizedList(new ArrayList<>());
        volatile boolean running = true; volatile Integer code; volatile long elapsedMs;
        Job(String id, List<String> command, String out) { this.id = id; this.command = List.copyOf(command); this.out = out; }
        void addLog(String line) { synchronized (log) { log.add(line); if (log.size() > 2000) log.remove(0); } }
    }
}
