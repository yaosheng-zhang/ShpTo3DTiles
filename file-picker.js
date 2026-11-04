(() => {
  "use strict";
  const choices = {
    shpPath: { kind: "shp", title: "选择建筑 SHP" },
    prjPath: { kind: "prj", title: "选择坐标系 PRJ", optional: true },
    whiteTexture: { kind: "texture", title: "选择白模纹理", optional: true },
    layeredTexture: { kind: "texture", title: "选择分层线纹理", optional: true },
    outPath: { kind: "directory", title: "选择输出目录" },
    resultPath: { kind: "result", title: "选择 tileset.json" }
  };
  let choosing = false;
  for (const [id, settings] of Object.entries(choices)) {
    const input = document.getElementById(id);
    input.readOnly = true;
    input.placeholder = settings.optional ? "未选择，使用默认设置" : settings.title;
    const wrapper = document.createElement("div");
    wrapper.className = "path-picker";
    input.before(wrapper);
    wrapper.appendChild(input);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary-action";
    button.textContent = "选择…";
    button.setAttribute("aria-label", settings.title);
    wrapper.appendChild(button);
    const message = document.createElement("div");
    message.className = "picker-status";
    message.setAttribute("role", "status");
    wrapper.after(message);
    async function select() {
      if (choosing) return;
      choosing = true;
      button.disabled = true;
      message.textContent = "请在系统窗口中选择，或取消返回。";
      try {
        const response = await fetch("/api/pick", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ kind: settings.kind, initial: input.value.trim() })
        });
        const text = await response.text();
        if (!text.trim()) throw new Error(`文件选择服务返回了空响应（HTTP ${response.status}）`);
        let result;
        try {
          result = JSON.parse(text);
        } catch (error) {
          throw new Error(`文件选择服务返回内容无效（HTTP ${response.status}）`);
        }
        if (!response.ok || result.error) throw new Error(result.error || "文件选择窗口打开失败");
        if (!result.cancelled && result.path) {
          input.value = result.path;
          input.title = result.path;
          input.dispatchEvent(new Event("change", { bubbles: true }));
          if (settings.kind === "texture") {
            const textures = document.getElementById("textures");
            textures.checked = true;
            textures.dispatchEvent(new Event("change", { bubbles: true }));
          }
        }
        message.textContent = "";
      } catch (error) {
        message.textContent = error.message;
      } finally {
        choosing = false;
        button.disabled = false;
      }
    }
    button.addEventListener("click", select);
    input.addEventListener("click", select);
    if (settings.optional) {
      const clear = document.createElement("button");
      clear.type = "button";
      clear.className = "secondary-action";
      clear.textContent = "默认";
      clear.setAttribute("aria-label", settings.title + "：恢复默认");
      clear.addEventListener("click", () => {
        if (choosing) return;
        input.value = ""; input.title = "";
        input.dispatchEvent(new Event("change", { bubbles: true }));
        message.textContent = "";
      });
      wrapper.appendChild(clear);
    }
  }
})();
