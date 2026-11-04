# ShpTo3DTiles 重建源码

这是一个依据已编译应用逆向分析并重建的 Java 项目，用于将建筑 Shapefile 转换为 3D Tiles 1.1。当前可编译、可测试，并提供桌面 GUI 和命令行入口。仓库只包含重建源码，不包含原始参考 JAR 或反编译对照副本。

> 本项目是依据已编译 JAR 反编译和修复得到的重建实现，不是原始作者源码，也不保证与原程序在所有输入和运行环境下完全等价。

## 功能

- 读取 Polygon / MultiPolygon 建筑 Shapefile，读取 DBF 属性并导出选定的 feature 属性。
- 根据 SHP 的 `.prj` 或显式指定的 `.prj` 转换坐标；生成 3D Tiles 1.1 tileset 和 GLB 模型。
- 生成拉伸建筑主体、顶盖、可配置女儿墙和屋顶机房；支持单模型 LOD 或 L1/L2/L3 建筑 LOD 链。
- 可生成外立面纹理。顶盖和底面在挤出阶段被明确标记；纹理分层时它们不会再被误当作外墙切分，外墙仍按楼层处理。
- 修复/清理部分无效几何和重复点；顶盖使用 JTS 三角剖分，并检查生成三角形的覆盖面积。
- 每次转换在输出目录写入独立的 `geometry-diagnostics-时间戳.log`，用于记录出错建筑的 ID、SHP 行号、字段和几何阶段。

## 项目结构

```text
src/main/java/       当前构建使用的重建实现
src/main/resources/  默认纹理资源
src/test/java/       几何、日志和纹理分层回归测试
REVERSE-ENGINEERING.md 逆向来源、方法和已知限制记录
run-gui.bat          Windows GUI 启动脚本
run-cli.bat          Windows CLI 启动脚本
```

仓库根目录另有 Viewer 页面 `webviewer.html`、`webviewer.css`、`webviewer.js`，文件选择桥接 `file-picker.js`、`native-picker.ps1`，以及离线使用的 Cesium 1.118 静态资源 `cesium/`。Cesium 的 Apache 2.0 许可证随资源保存在 `../cesium/LICENSE.md`。

## 环境要求

- JDK 17（JDK 8 不兼容）
- Maven 3.8 或更高版本
- 首次构建需要访问 Maven Central 和 OSGeo Maven 仓库，或事先准备好依赖缓存

GeoTools、JTS 等依赖会打入最终的 shaded JAR。构建后运行转换不需要单独安装这些 Java 库。

## 构建与测试

在项目根目录运行：

```powershell
mvn clean verify
```

可运行的整合 JAR 输出到：

```text
target/shp-to-3dtiles-tool-1.1.0.jar
```

`mvn verify` 会运行测试并完成打包。当前回归测试覆盖几何清理、顶盖面积、纹理分层时顶盖/底面的材质分流以及诊断日志。

## 启动

打开桌面界面：

```powershell
java -jar target/shp-to-3dtiles-tool-1.1.0.jar
```

Windows 也可以运行：

```powershell
.\run-gui.bat
```

查看命令行参数：

```powershell
java -cp target/shp-to-3dtiles-tool-1.1.0.jar com.pipeline.api.cli.ShpTo3dTilesCli --help
```

执行转换示例：

```powershell
java -cp target/shp-to-3dtiles-tool-1.1.0.jar com.pipeline.api.cli.ShpTo3dTilesCli `
  --shp "D:\data\buildings.shp" `
  --out "D:\data\tiles-output" `
  --prj "D:\data\buildings.prj" `
  --textures true `
  --building-lod-chain true
```

Windows CLI 脚本可传入相同参数：

```powershell
.\run-cli.bat --shp "D:\data\buildings.shp" --out "D:\data\tiles-output" --textures true
```

## 输入数据

Shapefile 由同名 `.shp`、`.shx`、`.dbf` 等配套文件组成；坐标参考系应由同名 `.prj` 提供，或通过 `--prj` 显式指定。错误或缺失的坐标系可能导致模型定位错误。

默认属性字段：

| 用途 | 默认字段 | 说明 |
| --- | --- | --- |
| 建筑 ID | `bid` | 用于模型和诊断日志标识 |
| 顶部高程 | `maxz` | 与底部高程之差作为建筑高度 |
| 底部高程 | `minz` | 建筑拉伸起始高程 |
| 楼层数 | `lc` | 用于女儿墙、机房和纹理层高等逻辑 |
| 建筑用途 | `bld_usage` | 纹理/用途字段；可按数据调整 |

字段名称可以通过 CLI 参数覆盖。高程和楼层字段应为可解析的数值；请确认高程单位与坐标系及建筑高度要求一致。

## 常用参数

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `--shp` / `--out` | 必填 | 输入 SHP 和输出目录 |
| `--prj` | 自动读取同名文件 | 显式指定 CRS `.prj` |
| `--id-attr` | `bid` | 建筑 ID 字段 |
| `--max-z-attr` / `--min-z-attr` | `maxz` / `minz` | 顶部/底部高程字段 |
| `--floors-attr` | `lc` | 楼层数字段 |
| `--textures` | `true` | 是否生成纹理 |
| `--building-lod-chain` | `false` | 输出 L1/L2/L3 建筑模型 |
| `--lod` | `NONE` | 单模型模式简化级别：`NONE`、`L1_SIMPLE`、`L2_STANDARD`、`L3_DETAIL` |
| `--legacy-per-building` | `false` | 使用逐栋 OBJ/GLB 转换路径 |
| `--resume` | `false` | 复用输入参数匹配的已完成单体 GLB |
| `--keep-intermediate` | `false` | 保留 OBJ 和中间 GLB 文件 |
| `--metadata-fields` | `bldUseage,entityid,wkBldId` | 写入模型的属性字段列表 |
| `--parapet-min-lc` | `5` | 女儿墙生成的最小楼层数 |
| `--shaft-min-lc` | `9` | 屋顶机房生成的最小楼层数 |
| `--parallelism` | 最多 8 个 CPU 工作线程 | 转换并行度 |

完整参数及可调女儿墙、机房、LOD 树参数请运行 `--help`。

## 输出与诊断

输出目录通常包含：

```text
tileset.json
glb/                         GLB 内容及纹理资源
geometry-diagnostics-*.log   几何或拉伸阶段诊断日志
wkBldId-merge.log             合并分组信息（适用时）
```

例如 `stage=parapet` 警告表示对应建筑的女儿墙几何处理失败或被跳过；主体和顶盖是在女儿墙处理之前生成的。应结合日志里的 `buildingId`、`shpRow`、`featureId` 和 `reason` 定位原始要素。若出现 `stage=building-extrusion`，则应进一步检查该建筑主体几何。

## 本地 Viewer（可选）

源码包含离线 Viewer 服务端入口。启动它时，`--root` 目录必须包含 `webviewer.html`、`webviewer.css`、`webviewer.js`、`file-picker.js`、`native-picker.ps1`、`cesium/` 静态资源，以及转换生成的 `tileset.json`/GLB：

```powershell
java -cp target/shp-to-3dtiles-tool-1.1.0.jar com.pipeline.api.offline.OfflineViewerServer `
  --root "D:\data\tiles-output" --port 8930
```

然后访问 `http://127.0.0.1:8930/`。这些页面和 Cesium 文件位于仓库根目录，部署时需将其与 Viewer 服务端及 tileset 内容放在同一服务根目录。

## 逆向来源与发布提示

重建目标、反编译工具、哈希及验证范围见 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md)。`src/main/java` 是当前构建源码。原始参考 JAR、提取包和 CFR 对照树仅用于本地分析，不纳入仓库。此实现没有从旧 `ShpTo3DTiles-source` 复制代码。

发布重建代码和随包纹理前，请确认其发布符合你适用的授权要求。Cesium 1.118 按 Apache 2.0 发布，许可证随 `../cesium/` 资源提供。不要将内网 SHP、个人路径、转换输出目录或密钥上传到远程仓库。
