#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Local web app: SHP -> 3D Tiles converter frontend + Cesium preview.

Routes:
  GET  /                     -> webviewer.html
  GET  /api/status           -> {java, jar}
  GET  /api/browse?path=...  -> directory listing (dirs + .shp/.prj files, tileset flag)
  POST /api/convert          -> start a conversion job, returns {job}
  GET  /api/job?id=...       -> job status {running, code, log}
  GET  /fs/<abs path>        -> serve any local file (tileset.json + b3dm bridge)
  everything else            -> static files under project root
"""
import json
import logging
from logging.handlers import RotatingFileHandler
import gzip
import os
import shutil
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote

ROOT = Path(__file__).resolve().parent
SERVER_STARTED_AT = time.time()
LOGGER = logging.getLogger("web-viewer")
CESIUM_BUILD = ROOT / "offline-package-build" / "cesium" / "Build" / "Cesium"

JAVA_CANDIDATES = [
    ROOT / "ShpTo3DTiles" / "runtime" / "bin" / "java.exe",
    ROOT / "release-1.1.0" / "ShpTo3DTiles" / "runtime" / "bin" / "java.exe",
    ROOT / "release" / "ShpTo3DTiles" / "runtime" / "bin" / "java.exe",
    Path(r"D:\Java\bin\java.exe"),
]
JAR_CANDIDATES = [
    ROOT / "ShpTo3DTiles-source" / "target" / "shp-to-3dtiles-tool-1.1.0.jar",
    ROOT / "ShpTo3DTiles" / "app" / "shp-to-3dtiles-gui.jar",
    ROOT / "release-1.1.0" / "ShpTo3DTiles" / "app" / "shp-to-3dtiles-gui.jar",
    ROOT / "release" / "ShpTo3DTiles" / "app" / "shp-to-3dtiles-gui.jar",
    ROOT / "target" / "shp-to-3dtiles-gui.jar",
]

OPTIMIZED_JAR_NAME = "shp-to-3dtiles-tool-1.1.0.jar"
SOURCE_ROOT = ROOT / "ShpTo3DTiles-source"
EXPLODED_CLASSES = SOURCE_ROOT / ".build-check" / "classes"
EXPLODED_LIB = SOURCE_ROOT / ".build-check" / "jar" / "BOOT-INF" / "lib"
EXPLODED_RESOURCES = SOURCE_ROOT / "src" / "main" / "resources"
CLI_MAIN_CLASS = "com.pipeline.api.cli.ShpTo3dTilesCli"

CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0

JOBS = {}
JOBS_LOCK = threading.Lock()
LOG_CAP = 2000


def find_first(paths):
    for p in paths:
        if p.is_file():
            return p
    return None


def find_converter():
    """Prefer the exploded classpath because it preserves GeoTools services."""
    cli_class = EXPLODED_CLASSES / Path(*CLI_MAIN_CLASS.split("."))
    cli_class = cli_class.with_suffix(".class")
    if cli_class.is_file() and EXPLODED_LIB.is_dir() and any(EXPLODED_LIB.glob("*.jar")):
        classpath = os.pathsep.join([
            str(EXPLODED_CLASSES),
            str(EXPLODED_LIB / "*"),
            str(EXPLODED_RESOURCES),
        ])
        return {
            "mode": "exploded-classpath",
            "path": str(EXPLODED_CLASSES),
            "classpath": classpath,
        }
    jar = find_first(JAR_CANDIDATES)
    if jar:
        mode = "optimized-jar" if jar.name == OPTIMIZED_JAR_NAME else "boot-jar"
        return {"mode": mode, "path": str(jar)}
    return None


def find_offline_viewer():
    candidates = [
        ROOT / "ShpTo3DTiles" / "app" / "shp-to-3dtiles-offline.jar",
        ROOT / "ShpTo3DTiles-source" / "target" / "shp-to-3dtiles-offline.jar",
    ]
    return find_first(candidates)


class ViewerHTTPServer(ThreadingHTTPServer):
    # A browser may request many tiles as well as styles, workers and telemetry.
    request_queue_size = 128

    def handle_error(self, request, client_address):
        LOGGER.exception("Request handler failed for %s", client_address)


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, fmt, *args):
        LOGGER.info("%s %s", self.client_address[0], fmt % args)

    def translate_path(self, path):
        # Serve the bundled Cesium build under the URL prefix used by the page.
        # Cesium.js loads workers, widgets, shaders and assets relative to this
        # base URL, so the whole Build/Cesium tree must share the same mapping.
        request_path = unquote(urlparse(path).path)
        if request_path == "/cesium" or request_path.startswith("/cesium/"):
            relative = request_path[len("/cesium"):].lstrip("/")
            candidate = (CESIUM_BUILD / relative).resolve()
            try:
                candidate.relative_to(CESIUM_BUILD.resolve())
            except ValueError:
                return str(CESIUM_BUILD / "__not_found__")
            return str(candidate)
        return super().translate_path(path)

    # ---------- helpers ----------

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length).decode("utf-8"))

    # ---------- routing ----------

    def do_GET(self):
        parsed = urlparse(self.path)
        q = parse_qs(parsed.query)
        if parsed.path == "/":
            self.path = "/webviewer.html"
            return super().do_GET()
        if parsed.path == "/api/status":
            converter = find_converter()
            repaired_stress = ROOT / "stress-output-repaired" / "tileset.json"
            continuous_stress = ROOT / "stress-output-continuous" / "tileset.json"
            legacy_stress = ROOT / "stress-output-lod" / "tileset.json"
            return self._json({
                "serverPid": os.getpid(),
                "serverStartedAt": SERVER_STARTED_AT,
                "java": str(find_first(JAVA_CANDIDATES) or ""),
                "jar": str(find_first(JAR_CANDIDATES) or ""),
                "converter": converter or {},
                "root": str(ROOT),
                "stressOutput": str(continuous_stress.parent if continuous_stress.is_file()
                                     else repaired_stress.parent if repaired_stress.is_file()
                                     else legacy_stress.parent if legacy_stress.is_file() else ""),
            })
        if parsed.path == "/api/browse":
            return self.api_browse(q)
        if parsed.path == "/api/job":
            return self.api_job(q)
        if parsed.path == "/api/inspect":
            return self.api_inspect(q)
        if parsed.path == "/api/telemetry":
            return self.api_telemetry()
        if parsed.path.startswith("/fs/"):
            return self.serve_fs(parsed.path[len("/fs/"):])
        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/convert":
            return self.api_convert()
        if parsed.path == "/api/generate-stress":
            return self.api_generate_stress()
        self._json({"error": "not found"}, 404)

    # ---------- /fs/<abs path> file bridge ----------

    def serve_fs(self, raw):
        p = Path(unquote(raw))
        if not p.is_absolute():
            return self._json({"error": "absolute path required"}, 400)
        if not p.is_file():
            return self._json({"error": "not found: " + str(p)}, 404)
        try:
            data = p.read_bytes()
        except OSError as ex:
            return self._json({"error": str(ex)}, 500)
        self.send_response(200)
        ctype = {
            ".json": "application/json",
            ".b3dm": "application/octet-stream",
            ".glb": "model/gltf-binary",
            ".jpg": "image/jpeg",
        }.get(p.suffix.lower(), "application/octet-stream")
        accepts_gzip = "gzip" in (self.headers.get("Accept-Encoding") or "").lower()
        if accepts_gzip and p.suffix.lower() in (".json", ".b3dm", ".glb") and len(data) > 1024:
            data = gzip.compress(data, compresslevel=5)
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Vary", "Accept-Encoding")
        self.send_header("Content-Type", ctype)
        # Converted files are frequently regenerated under the same names.
        # Disable browser caching so Cesium never keeps an earlier white model.
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # ---------- directory browsing ----------

    def api_browse(self, q):
        raw = (q.get("path") or [""])[0].strip()
        if not raw:
            drives = []
            for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
                d = letter + ":\\"
                if os.path.exists(d):
                    drives.append(d)
            return self._json({"drives": drives})
        p = Path(raw)
        try:
            p = p.resolve()
        except OSError:
            return self._json({"error": "bad path"}, 400)
        if not p.is_dir():
            return self._json({"error": "not a directory: " + str(p)}, 400)
        dirs, files = [], []
        try:
            for e in sorted(p.iterdir(), key=lambda x: x.name.lower()):
                try:
                    if e.is_dir():
                        dirs.append(e.name)
                    elif e.is_file() and e.suffix.lower() in (".shp", ".prj"):
                        files.append(e.name)
                except OSError:
                    continue
        except PermissionError:
            return self._json({"error": "permission denied"}, 403)
        parent = str(p.parent)
        if parent == str(p):
            parent = ""
        return self._json({
            "path": str(p),
            "parent": parent,
            "dirs": [{"name": d, "tileset": (p / d / "tileset.json").is_file()} for d in dirs],
            "files": files,
        })

    # ---------- conversion jobs ----------

    def api_generate_stress(self):
        req = self._body_json()
        out = Path(req.get("out") or (ROOT / "stress-data"))
        try:
            count = max(1, min(250000, int(req.get("count") or 25000)))
            width_km = float(req.get("widthKm") or 1000)
            height_km = float(req.get("heightKm") or 750)
            seed = int(req.get("seed") or 20260929)
            pattern = str(req.get("pattern") or "clustered")
        except (TypeError, ValueError):
            return self._json({"error": "压测参数无效"}, 400)
        if width_km <= 0 or height_km <= 0:
            return self._json({"error": "范围必须大于 0"}, 400)
        if pattern not in ("clustered", "continuous"):
            return self._json({"error": "未知的压力数据分布模式"}, 400)
        script = ROOT / "generate_stress_shp.py"
        if not script.is_file():
            return self._json({"error": "缺少 generate_stress_shp.py"}, 500)
        out.mkdir(parents=True, exist_ok=True)
        args = [sys.executable, str(script), "--out", str(out),
                "--count", str(count), "--width-km", str(width_km),
                "--height-km", str(height_km), "--seed", str(seed),
                "--pattern", pattern]
        job_id = os.urandom(8).hex()
        job = {
            "kind": "generate-stress", "cmd": args, "log": [],
            "running": True, "code": None, "out": str(out.resolve()),
            "startedAt": time.monotonic(), "elapsedMs": 0,
        }
        with JOBS_LOCK:
            JOBS[job_id] = job
        self._start_subprocess_job(job, args)
        return self._json({"job": job_id, "out": job["out"]})

    def _start_subprocess_job(self, job, args):
        def run():
            try:
                proc = subprocess.Popen(
                    args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    text=True, encoding="utf-8", errors="replace",
                    creationflags=CREATE_NO_WINDOW,
                )
                for line in proc.stdout:
                    line = line.rstrip("\r\n")
                    job["log"].append(line)
                    if len(job["log"]) > LOG_CAP:
                        del job["log"][:-LOG_CAP]
                job["code"] = proc.wait()
            except Exception as ex:  # noqa: BLE001
                job["log"].append("[server] " + str(ex))
                job["code"] = -1
            finally:
                job["elapsedMs"] = round((time.monotonic() - job["startedAt"]) * 1000)
                job["running"] = False
        threading.Thread(target=run, daemon=True).start()

    def api_convert(self):
        req = self._body_json()
        shp = Path(req.get("shp") or "")
        out = Path(req.get("out") or "")
        if not shp.is_file():
            return self._json({"error": "SHP 文件不存在: " + str(shp)}, 400)
        if not str(out).strip():
            return self._json({"error": "输出目录不能为空"}, 400)
        out.mkdir(parents=True, exist_ok=True)

        java = find_first(JAVA_CANDIDATES)
        converter = find_converter()
        if not java or not converter:
            return self._json({"error": "找不到 Java 运行时或转换器"}, 500)

        if converter["mode"] == "exploded-classpath":
            args = [str(java), "-Dfile.encoding=UTF-8", "-cp", converter["classpath"],
                    CLI_MAIN_CLASS, "--shp", str(shp), "--out", str(out)]
        elif converter["mode"] == "optimized-jar":
            args = [str(java), "-Dfile.encoding=UTF-8", "-cp", converter["path"],
                    CLI_MAIN_CLASS,
                    "--shp", str(shp), "--out", str(out)]
        else:
            args = [str(java), "-Dloader.main=com.pipeline.api.cli.ShpTo3dTilesCli",
                    "-Dfile.encoding=UTF-8", "-jar", converter["path"],
                    "--shp", str(shp), "--out", str(out)]
        prj = (req.get("prj") or "").strip()
        if prj:
            if not Path(prj).is_file():
                return self._json({"error": "PRJ 文件不存在: " + prj}, 400)
            args += ["--prj", prj]

        p = req.get("params") or {}
        atlas_width = str(p.get("textureAtlasWidth", 384))
        if atlas_width not in ("384", "768", "1536"):
            return self._json({"error": "textureAtlasWidth must be 384, 768 or 1536"}, 400)
        args += ["--texture-atlas-width", atlas_width]

        def num(key, cli, default):
            v = str(p.get(key, default)).strip()
            if v:
                args.extend([cli, v])

        num("parapetMinLc",       "--parapet-min-lc",      "5")
        num("parapetMinHeight",   "--parapet-min-height",  "0.80")
        num("parapetMaxHeight",   "--parapet-max-height",  "1.40")
        num("parapetPerFloor",    "--parapet-per-floor",   "0.04")
        num("parapetThickness",   "--parapet-thickness",   "0.40")
        num("shaftMinLc",         "--shaft-min-lc",        "9")
        num("shaftInset",         "--shaft-inset",         "1.50")
        num("shaftClearance",     "--shaft-clearance",     "2.00")
        num("shaftMaxLen",        "--shaft-max-len",       "8.00")
        num("shaftMaxWid",        "--shaft-max-wid",       "6.00")
        num("shaftMaxHeight",     "--shaft-max-height",    "6.00")
        num("shaftCenterRatio",   "--shaft-center-ratio",  "0.80")
        if p.get("textures"):
            args += ["--textures", "true"]
        if p.get("buildingLodChain"):
            args += ["--building-lod-chain", "true"]
        if p.get("legacyPerBuilding"):
            args += ["--legacy-per-building", "true"]
        num("lod", "--lod", "L2_STANDARD")
        num("tileMaxContents", "--tile-max-contents", "32")
        num("tileMaxDepth", "--tile-max-depth", "12")
        num("tileExternalDepth", "--tile-external-depth", "2")
        num("parallelism", "--parallelism", "4")
        num("progressIntervalSeconds", "--progress-interval-seconds", "5")
        if p.get("resume"):
            args += ["--resume", "true"]
        if p.get("keepIntermediate"):
            args += ["--keep-intermediate", "true"]

        job_id = os.urandom(8).hex()
        job = {
            "cmd": args,
            "log": ["[server] command: " + subprocess.list2cmdline(args)],
            "running": True,
            "code": None,
            "out": str(out.resolve()),
            "startedAt": time.monotonic(),
            "elapsedMs": 0,
        }
        with JOBS_LOCK:
            JOBS[job_id] = job

        def run():
            try:
                proc = subprocess.Popen(
                    args,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    creationflags=CREATE_NO_WINDOW,
                )
                for line in proc.stdout:
                    line = line.rstrip("\r\n")
                    job["log"].append(line)
                    if len(job["log"]) > LOG_CAP:
                        del job["log"][:-LOG_CAP]
                job["code"] = proc.wait()
            except Exception as ex:  # noqa: BLE001
                job["log"].append("[server] " + str(ex))
                job["code"] = -1
            finally:
                job["elapsedMs"] = round((time.monotonic() - job["startedAt"]) * 1000)
                job["running"] = False

        threading.Thread(target=run, daemon=True).start()
        return self._json({"job": job_id, "out": job["out"]})

    def api_job(self, q):
        job_id = (q.get("id") or [""])[0]
        with JOBS_LOCK:
            job = JOBS.get(job_id)
        if job is None:
            return self._json({"error": "unknown job"}, 404)
        return self._json({
            "running": job["running"],
            "code": job["code"],
            "log": job["log"][-60:],
            "out": job["out"],
            "elapsedMs": job["elapsedMs"],
            "kind": job.get("kind", "convert"),
        })

    def api_telemetry(self):
        payload = {
            "timestamp": time.time(),
            "server": {},
            "gpu": [],
        }
        try:
            import psutil
            vm = psutil.virtual_memory()
            payload["server"] = {
                "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
                "systemUsedBytes": vm.used,
                "systemTotalBytes": vm.total,
            }
        except Exception:
            pass
        smi = shutil.which("nvidia-smi")
        if smi:
            try:
                raw = subprocess.check_output([
                    smi, "--query-gpu=index,name,memory.used,memory.total,utilization.gpu",
                    "--format=csv,noheader,nounits"],
                    text=True, encoding="utf-8", errors="replace", timeout=2,
                    stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    creationflags=CREATE_NO_WINDOW)
                for line in raw.splitlines():
                    parts = [x.strip() for x in line.split(",")]
                    if len(parts) >= 5:
                        try:
                            payload["gpu"].append({
                                "index": parts[0], "name": parts[1],
                                "usedMiB": float(parts[2]), "totalMiB": float(parts[3]),
                                "utilization": float(parts[4]), "source": "nvidia-smi",
                            })
                        except ValueError:
                            continue
            except (OSError, subprocess.SubprocessError):
                pass
        return self._json(payload)

    def api_inspect(self, q):
        raw = (q.get("path") or [""])[0].strip()
        if not raw:
            return self._json({"error": "path is required"}, 400)
        p = Path(raw).resolve()
        if p.is_file() and p.name.lower() == "tileset.json":
            root = p.parent
        elif p.is_dir():
            root = p
        else:
            return self._json({"error": "output directory or tileset.json not found"}, 404)

        files = [f for f in root.rglob("*") if f.is_file()]
        b3dm = [f for f in files if f.suffix.lower() == ".b3dm"]
        json_files = [f for f in files if f.suffix.lower() == ".json"]
        total_bytes = sum(f.stat().st_size for f in files)
        content_nodes = 0
        lod_nodes = 0
        tileset = root / "tileset.json"
        if tileset.is_file():
            try:
                doc = json.loads(tileset.read_text(encoding="utf-8"))

                def walk(node):
                    nonlocal content_nodes, lod_nodes
                    if not isinstance(node, dict):
                        return
                    if "content" in node:
                        content_nodes += 1
                    if node.get("children") and len(node["children"]) == 1:
                        child = node["children"][0]
                        if isinstance(child, dict) and "content" in child:
                            lod_nodes += 1
                    for child in node.get("children", []) or []:
                        walk(child)

                walk(doc.get("root", {}))
            except (OSError, ValueError):
                pass
        return self._json({
            "root": str(root),
            "files": len(files),
            "jsonFiles": len(json_files),
            "b3dmFiles": len(b3dm),
            "contentNodes": content_nodes,
            "lodNodes": lod_nodes,
            "bytes": total_bytes,
        })


def main():
    log_dir = ROOT / "logs"
    log_dir.mkdir(exist_ok=True)
    handler = RotatingFileHandler(log_dir / "web-viewer.log", maxBytes=5_000_000,
                                  backupCount=3, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    LOGGER.setLevel(logging.INFO)
    LOGGER.addHandler(handler)
    port = int(os.environ.get("PORT") or 8930)
    server = ViewerHTTPServer(("127.0.0.1", port), Handler)
    LOGGER.info("Server started pid=%s port=%s", os.getpid(), port)
    java = find_first(JAVA_CANDIDATES)
    converter = find_converter()
    print(f"[web-viewer] http://127.0.0.1:{port}/")
    print(f"[web-viewer] java: {java}")
    print(f"[web-viewer] converter: {converter}")
    try:
        server.serve_forever()
    finally:
        LOGGER.info("Server stopped pid=%s", os.getpid())
        server.server_close()


if __name__ == "__main__":
    main()
