(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  let viewer = null;
  let activeTileset = null;
  let jobTimer = null;
  let stressTimer = null;
  let lodLoads = 0;
  let lodUnloads = 0;
  let lastLodSample = 0;
  let visibleLodTiles = new Set();
  let previousVisibleLodTiles = new Set();
  let visibleLodChanges = 0;
  let visibleLodCounts = [0, 0, 0];
  let tileFailureCount = 0;
  let recentTileFailure = "";
  let serverUnavailable = false;
  let healthCheckPending = false;
  let serverPid = null;
  let tilesetLoadGeneration = 0;

  const log = (message, replace) => {
    const el = $("logOutput");
    if (replace) el.textContent = "";
    el.textContent = (el.textContent + "\n" + message).trimStart().split("\n").slice(-300).join("\n");
    el.scrollTop = el.scrollHeight;
  };
  const bytes = (n) => {
    if (!Number.isFinite(n)) return "--";
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
  };
  const mib = (n) => Number.isFinite(n) ? `${(n / 1024 / 1024).toFixed(0)} MB` : "--";
  const setMetric = (id, value) => $(id).textContent = value;

  function setStatus(text, ready) {
    $("serverStatus").textContent = text;
    $("serverDot").classList.toggle("ready", Boolean(ready));
  }

  async function getJson(url, options) {
    const response = await fetch(url, options);
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || `HTTP ${response.status}`);
    return body;
  }

  async function checkServer() {
    try {
      const status = await getJson("/api/status");
      const converter = status.converter || {};
      const ready = Boolean(converter.path);
      setStatus(ready ? "转换器已就绪" : "找不到转换器", ready);
      log(ready ? `运行模式: ${converter.mode}\n运行时: ${converter.path}` : "请先构建 ShpTo3DTiles-source", true);
      if (!$("shpPath").value) $("shpPath").value = `${status.root}\\建筑底面\\建筑底面.shp`;
      if (!$("outPath").value) $("outPath").value = `${status.root}\\texture-output`;
      if (!$("resultPath").value) {
        const optimized = `${status.root}\\output-optimized`;
        $("resultPath").value = status.optimizedOutput || optimized;
      }
    } catch (error) {
      setStatus("服务不可用", false);
      log(error.message, true);
    }
  }

  async function checkServerHealth() {
    if (healthCheckPending) return;
    healthCheckPending = true;
    try {
      const status = await getJson("/api/status", { signal: AbortSignal.timeout(4000), cache: "no-store" });
      const restarted = serverPid !== null && status.serverPid !== serverPid;
      if (serverUnavailable || restarted) {
        setStatus("服务已恢复，请重新加载模型", true);
        log("本地服务已恢复或重启。点击“在地图中打开”重建加载状态，重试失败的瓦片。", false);
      }
      serverPid = status.serverPid;
      serverUnavailable = false;
    } catch (error) {
      setStatus("本地服务连接中断", false);
      if (!serverUnavailable) log("无法连接本地服务，瓦片加载将失败。先恢复服务，再重新加载模型。", false);
      serverUnavailable = true;
    } finally {
      healthCheckPending = false;
    }
  }

  function initViewer() {
    if (!window.Cesium) {
      log("Cesium CDN 未加载，无法显示地图。", false);
      return;
    }
    viewer = new Cesium.Viewer("cesiumContainer", {
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
      selectionIndicator: false,
      infoBox: false,
      // The offline package deliberately has no online imagery provider.
      // Buildings remain fully viewable on a local ellipsoid without a network.
      baseLayer: false
    });
    viewer.scene.debugShowFramesPerSecond = false;
    viewer.scene.preRender.addEventListener(() => visibleLodTiles.clear());
    let last = performance.now();
    let frames = 0;
    viewer.scene.postRender.addEventListener(() => {
      if (activeTileset) {
        if (visibleLodTiles.size !== previousVisibleLodTiles.size ||
            [...visibleLodTiles].some((uri) => !previousVisibleLodTiles.has(uri))) {
          visibleLodChanges += 1;
        }
        previousVisibleLodTiles = new Set(visibleLodTiles);
        visibleLodCounts = [0, 0, 0];
        for (const uri of visibleLodTiles) {
          const match = /_l([123])\.b3dm(?:[?#]|$)/i.exec(uri);
          if (match) visibleLodCounts[Number(match[1]) - 1] += 1;
        }
      }
      frames += 1;
      const now = performance.now();
      if (now - last > 1000) {
        setMetric("metricFps", Math.round(frames * 1000 / (now - last)));
        frames = 0;
        last = now;
      }
    });
  }

  async function inspect(path) {
    const result = await getJson(`/api/inspect?path=${encodeURIComponent(path)}`);
    setMetric("metricB3dm", result.b3dmFiles);
    setMetric("metricContent", result.contentNodes);
    setMetric("metricLod", result.lodNodes);
    setMetric("metricBytes", bytes(result.bytes));
    return result;
  }

  function updateCesiumTelemetry() {
    const stats = activeTileset && activeTileset.statistics;
    if (!stats) return;
    const loaded = Number(stats.numberOfTilesWithContentReady || 0);
    const processing = Number(stats.numberOfTilesProcessing || 0);
    setMetric("metricTiles", `${loaded} / ${processing}`);
    setMetric("metricGeometry", mib(Number(stats.geometryByteLength || 0)));
    setMetric("metricTextures", mib(Number(stats.texturesByteLength || 0)));
    const now = performance.now();
    if (now - lastLodSample >= 2000) {
      if (tileFailureCount) {
        log(`本周期 ${tileFailureCount} 个瓦片加载失败；最近一次：${recentTileFailure}`, false);
        tileFailureCount = 0;
      }
      const effective = activeTileset.memoryAdjustedScreenSpaceError;
      $("lodDiagnostic").textContent =
        `LOD ${activeTileset.debugFreezeFrame ? "已冻结" : "自动"} | SSE 设置 ${activeTileset.maximumScreenSpaceError.toFixed(1)} / 实际 ${effective.toFixed(1)} | 请求中 ${stats.numberOfPendingRequests || 0} / 处理中 ${processing} | 可见 L1/L2/L3 ${visibleLodCounts.join("/")} | 本周期绘制集合变化 ${visibleLodChanges} / 加载 ${lodLoads} / 卸载 ${lodUnloads} | ${activeTileset.tilesLoaded ? "当前视图加载完成" : "仍在加载"} | 内存 ${mib(activeTileset.totalMemoryUsageInBytes)}`;
      visibleLodChanges = 0;
      lodLoads = 0;
      lodUnloads = 0;
      lastLodSample = now;
    }
  }

  async function updateTelemetry() {
    // Rendering diagnostics must remain live even if the GPU query is slow.
    updateCesiumTelemetry();
    try {
      const result = await getJson(`/api/telemetry?t=${Date.now()}`);
      const gpu = result.gpu && result.gpu[0];
      setMetric("metricGpu", gpu ? `${Math.round(gpu.usedMiB)} / ${Math.round(gpu.totalMiB)} MB · ${Math.round(gpu.utilization)}%` : "不可用");
      const memory = performance.memory;
      setMetric("metricJs", memory ? `${Math.round(memory.usedJSHeapSize / 1048576)} / ${Math.round(memory.jsHeapSizeLimit / 1048576)} MB` : "不可用");
    } catch (error) {
      setMetric("metricGpu", "不可用");
      setMetric("metricJs", "不可用");
    }
  }

  async function loadTileset() {
    const raw = $("resultPath").value.trim();
    if (!raw) throw new Error("请填写输出目录或 tileset.json 路径");
    const tilesetPath = raw.toLowerCase().endsWith("tileset.json") ? raw : `${raw.replace(/[\\/]$/, "")}/tileset.json`;
    if (!viewer) throw new Error("Cesium 尚未加载");
    const generation = ++tilesetLoadGeneration;
    const urlPath = tilesetPath.replace(/\\/g, "/");
    const url = `/fs/${encodeURI(urlPath)}?v=${Date.now()}`;
    $("emptyState").style.display = "none";
    $("tilesetLabel").textContent = "正在加载";
    const sse = Number($("sse").value);
    const nextTileset = await Cesium.Cesium3DTileset.fromUrl(url, {
      maximumScreenSpaceError: sse,
      cacheBytes: 256 * 1024 * 1024,
      maximumCacheOverflowBytes: 128 * 1024 * 1024,
      dynamicScreenSpaceError: false,
      foveatedScreenSpaceError: false,
      skipLevelOfDetail: false,
      immediatelyLoadDesiredLevelOfDetail: false,
      preferLeaves: false,
      preloadFlightDestinations: true
    });
    // An older asynchronous request must never add a second, overlapping model.
    if (generation !== tilesetLoadGeneration) {
      nextTileset.destroy();
      return;
    }
    if (activeTileset) viewer.scene.primitives.remove(activeTileset);
    activeTileset = nextTileset;
    viewer.scene.primitives.add(nextTileset);
    lodLoads = 0;
    lodUnloads = 0;
    lastLodSample = performance.now();
    visibleLodTiles.clear();
    previousVisibleLodTiles.clear();
    visibleLodChanges = 0;
    activeTileset.tileVisible.addEventListener((tile) => {
      if (tile.content && tile.content.url) visibleLodTiles.add(tile.content.url);
    });
    activeTileset.allTilesLoaded.addEventListener(() => {
      log("当前视图请求及处理已完成：请对照此时闪烁是否停止。", false);
    });
    $("freezeLodButton").textContent = "冻结当前 LOD（排查闪烁）";
    activeTileset.tileLoad.addEventListener(() => { lodLoads += 1; });
    activeTileset.tileUnload.addEventListener(() => { lodUnloads += 1; });
    activeTileset.tileFailed.addEventListener((error) => {
      tileFailureCount += 1;
      recentTileFailure = `${error.url}: ${error.message}`;
    });
    await viewer.zoomTo(activeTileset);
    if (generation !== tilesetLoadGeneration) return;
    $("tilesetLabel").textContent = tilesetPath.split(/[\\/]/).pop();
    await inspect(raw);
    log(`已加载: ${tilesetPath}`, false);
  }

  async function startConversion() {
    const numeric = (id) => {
      const value = Number($(id).value);
      if (!Number.isFinite(value)) throw new Error(`${$(id).previousElementSibling.textContent}必须是数字`);
      return value;
    };
    const params = {
      lod: $("lod").value,
      tileMaxContents: numeric("tileMaxContents"),
      tileMaxDepth: numeric("tileMaxDepth"),
      tileExternalDepth: numeric("tileExternalDepth"),
      parallelism: numeric("parallelism"),
      progressIntervalSeconds: numeric("progressInterval"),
      parapetMinLc: numeric("parapetMinLc"),
      parapetMinHeight: numeric("parapetMinHeight"),
      parapetMaxHeight: numeric("parapetMaxHeight"),
      parapetPerFloor: numeric("parapetPerFloor"),
      parapetThickness: numeric("parapetThickness"),
      shaftMinLc: numeric("shaftMinLc"),
      shaftInset: numeric("shaftInset"),
      shaftClearance: numeric("shaftClearance"),
      shaftMaxLen: numeric("shaftMaxLen"),
      shaftMaxWid: numeric("shaftMaxWid"),
      shaftMaxHeight: numeric("shaftMaxHeight"),
      shaftCenterRatio: numeric("shaftCenterRatio"),
      buildingLodChain: $("lodChain").checked,
      textures: $("textures").checked,
      textureAtlasWidth: numeric("textureAtlasWidth"),
      resume: $("resume").checked,
      keepIntermediate: $("keepIntermediate").checked,
      legacyPerBuilding: $("legacy").checked
    };
    for (const id of ["tileMaxContents", "tileMaxDepth", "tileExternalDepth", "parallelism",
      "progressInterval", "parapetThickness", "shaftInset", "shaftMaxLen", "shaftMaxWid",
      "shaftMaxHeight"]) {
      const key = id === "progressInterval" ? "progressIntervalSeconds" : id;
      if (params[key] <= 0) throw new Error(`${$(id).previousElementSibling.textContent}必须大于 0`);
    }
    for (const key of ["tileMaxContents", "tileMaxDepth", "tileExternalDepth", "parallelism",
      "progressIntervalSeconds", "parapetMinLc", "shaftMinLc"]) {
      if (!Number.isInteger(params[key])) throw new Error("层数、数量、深度和并行度必须是整数");
    }
    if (params.parapetMinHeight < 0 || params.parapetPerFloor < 0 || params.shaftClearance < 0) {
      throw new Error("高度增量和净距不能为负数");
    }
    if (params.tileExternalDepth >= params.tileMaxDepth) throw new Error("外置层级深度必须小于最大树深");
    if (params.parapetMaxHeight < params.parapetMinHeight) throw new Error("女儿墙最大高度不能小于最小高度");
    if (params.parapetThickness <= 0) throw new Error("女儿墙厚度必须大于 0");
    if (params.shaftCenterRatio <= 0 || params.shaftCenterRatio > 1) throw new Error("中心采样比例必须在 0 到 1 之间");
    const payload = {
      shp: $("shpPath").value.trim(),
      prj: $("prjPath").value.trim(),
      out: $("outPath").value.trim(),
      params
    };
    if (!payload.shp || !payload.out) throw new Error("输入 SHP 和输出目录不能为空");
    const result = await getJson("/api/convert", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    $("resultPath").value = result.out;
    $("jobState").textContent = `任务 ${result.job} 运行中`;
    log(`开始任务 ${result.job}`, true);
    clearInterval(jobTimer);
    jobTimer = setInterval(() => pollJob(result.job), 700);
  }

  async function startStressGeneration() {
    const payload = {
      out: $("stressOut").value.trim(),
      count: Number($("stressCount").value),
      widthKm: Number($("stressWidth").value),
      heightKm: Number($("stressHeight").value),
      seed: Number($("stressSeed").value),
      pattern: $("stressPattern").value
    };
    if (!payload.out || !payload.count || !payload.widthKm || !payload.heightKm) {
      throw new Error("压测输出目录、数量和范围不能为空");
    }
    const result = await getJson("/api/generate-stress", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    $("stressState").textContent = `生成任务 ${result.job} 运行中`;
    log(`开始生成 ${payload.pattern === "continuous" ? "连续城区" : "分散城市簇"} ${payload.widthKm} x ${payload.heightKm} km / ${payload.count} 栋`, true);
    clearInterval(stressTimer);
    stressTimer = setInterval(() => pollStress(result.job, result.out), 700);
  }

  async function pollStress(id, out) {
    try {
      const job = await getJson(`/api/job?id=${encodeURIComponent(id)}`);
      $("logOutput").textContent = job.log.join("\n");
      $("logOutput").scrollTop = $("logOutput").scrollHeight;
      $("stressState").textContent = job.running ? "生成中" : `生成完成，退出码 ${job.code}`;
      if (!job.running) {
        clearInterval(stressTimer);
        if (job.code === 0) {
          const generated = `${out.replace(/[\\/]$/, "")}\\stress_buildings.shp`;
          const stressOutput = `${out.replace(/[\\/]$/, "")}\\stress-output-${$("stressPattern").value === "continuous" ? "continuous" : "lod"}`;
          $("shpPath").value = generated;
          $("prjPath").value = generated.replace(/\.shp$/i, ".prj");
          $("outPath").value = stressOutput;
          $("resultPath").value = stressOutput;
          log(`压测 SHP 已生成: ${generated}`, false);
        }
      }
    } catch (error) {
      clearInterval(stressTimer);
      $("stressState").textContent = "生成任务查询失败";
      log(error.message, false);
    }
  }

  async function pollJob(id) {
    try {
      const job = await getJson(`/api/job?id=${encodeURIComponent(id)}`);
      $("logOutput").textContent = job.log.join("\n");
      $("logOutput").scrollTop = $("logOutput").scrollHeight;
      $("jobState").textContent = job.running ? "转换中" : `完成，退出码 ${job.code}`;
      if (!job.running) {
        clearInterval(jobTimer);
        setMetric("metricElapsed", job.elapsedMs ? `${(job.elapsedMs / 1000).toFixed(2)} s` : "--");
        if (job.code === 0) await inspect(job.out);
      }
    } catch (error) {
      clearInterval(jobTimer);
      $("jobState").textContent = "任务查询失败";
      log(error.message, false);
    }
  }

  $("freezeLodButton").addEventListener("click", () => {
    if (!activeTileset) return;
    activeTileset.debugFreezeFrame = !activeTileset.debugFreezeFrame;
    $("freezeLodButton").textContent = activeTileset.debugFreezeFrame ? "恢复自动 LOD" : "冻结当前 LOD（排查闪烁）";
    log(activeTileset.debugFreezeFrame ? "已冻结瓦片选择：观察闪烁是否停止。此模式用于诊断，请勿用它评估移动视角的加载效果。" : "已恢复自动 LOD。", false);
  });
  $("convertButton").addEventListener("click", () => startConversion().catch((e) => log(e.message, false)));
  $("stressButton").addEventListener("click", () => startStressGeneration().catch((e) => log(e.message, false)));
  $("stressPattern").addEventListener("change", () => {
    if ($("stressPattern").value === "continuous") {
      $("stressCount").value = "250000";
      $("stressWidth").value = "20";
      $("stressHeight").value = "15";
      $("stressOut").value = `${$("stressOut").value.replace(/[\\/]$/, "")}-continuous`;
    } else {
      $("stressCount").value = "25000";
      $("stressWidth").value = "1000";
      $("stressHeight").value = "750";
      $("stressOut").value = $("stressOut").value.replace(/-continuous$/, "");
    }
    $("stressButton").textContent = $("stressPattern").value === "continuous"
      ? "生成连续城区多边形 SHP" : "生成 75 万 km² SHP";
  });
  $("inspectButton").addEventListener("click", () => inspect($("resultPath").value.trim()).catch((e) => log(e.message, false)));
  $("loadButton").addEventListener("click", () => loadTileset().catch((e) => log(e.message, false)));
  $("sse").addEventListener("input", (event) => $("sseValue").textContent = event.target.value);
  initViewer();
  checkServer().then(() => {
    const path = new URLSearchParams(location.search).get("result");
    if (path) { $("resultPath").value = path; return loadTileset(); }
  }).catch((e) => log(e.message, false));
  updateTelemetry();
  setInterval(updateTelemetry, 1000);
  setInterval(checkServerHealth, 3000);
})();
