# ShpTo3DTiles standalone source

This project is the extracted standalone converter from the former backend
source tree. It contains only the GUI/CLI SHP-to-3D-Tiles tool and its geometry,
OBJ, GLB, texture, b3dm, and tileset implementation.

It intentionally excludes Spring Boot, controllers, PostGIS mappers, query
services, database configuration, and the former API DTOs.

The historical `com.pipeline.api` Java package name is retained so the
extracted implementation remains source-compatible with the released tool;
there is no API server in this project.

## Build

Requires Java 17 and Maven:

```powershell
mvn clean package
```

The shaded executable jar is written to `target/shp-to-3dtiles-tool-1.1.0.jar`.

## Run

```powershell
.\run-cli.bat --help
.\run-cli.bat --shp D:\data\buildings.shp --out D:\data\tiles
.\run-gui.bat
```

The converter reads Polygon/MultiPolygon Shapefiles, resolves CRS from an
explicit `.prj` or the companion file, extrudes buildings, optionally adds
parapets/roof equipment rooms and facade textures, and writes `tileset.json`
plus one or more `b3dm` files.

## Four optimization stages

1. **In-memory conversion pipeline**: building geometry is converted directly
   from `Mesh` to GLB bytes and then wrapped as b3dm. The optimized path does
   not write temporary OBJ or GLB files.
2. **Spatial batching and hierarchy**: building centroids are ordered along a
   Morton curve and grouped with `--tile-max-contents`. Spatially compact
   batches reduce request count and allow Cesium to cull content earlier.
3. **Batch LOD chain**: `--building-lod-chain true` emits L1, L2 and L3 content
   for each spatial batch. Cesium selects the appropriate level using geometric
   error. Leave this disabled when the smallest payload is more important than
   close-range detail. The default batch size is 32 buildings; the chain uses
   geometric errors 18/3/0 and progressively stronger footprint simplification
   for L1/L2, while L3 uses a 0.05 m footprint tolerance.
4. **GPU and transfer reduction**: GLB output reuses indexed vertices, uses
   16-bit indices whenever possible, disables double-sided rendering for closed
   optimized building meshes, and the bundled Web server serves JSON, b3dm and
   GLB responses with gzip when the browser requests it.

Recommended interactive-preview command:

```powershell
py -3 ..\web_viewer_server.py
```

Then open `http://127.0.0.1:8930/`. The workbench starts conversion jobs,
shows conversion time and output metrics, and loads a result in Cesium.

The workbench also has a large-area stress panel. Its default projected extent
is 1,000 km by 750 km (750,000 km2) with 25,000 building polygons. It writes
`stress_buildings.shp/.shx/.dbf/.prj` and can feed the generated SHP directly
into the converter. GPU dedicated memory and utilization are sampled from
`nvidia-smi` when available; Cesium geometry and texture cache sizes plus the
browser JS heap are shown alongside it. WebGL does not expose per-tab VRAM,
so the GPU figure is the adapter-wide value.

Recommended balance for large building datasets:

```powershell
.\run-cli.bat --shp D:\data\buildings.shp --out D:\data\tiles `
  --lod L2_STANDARD --tile-max-contents 32 --parallelism 4
```

Use `--building-lod-chain true` for progressive detail. With `--textures true`,
the default chain merges the distant, untextured L1 buildings into regional
meshes; each region has per-building textured L2 children with L3 descendants.
All levels use REPLACE and errors 18/3/0. L1 uses the actual simplified building
geometry, not a box proxy. This reduces distant-view requests and model objects
while retaining the existing detailed geometry, materials and compact atlas.

Textured L1 regions are split along their longest horizontal axis, limited by
`--tile-max-contents` (default 32 buildings), a 512 m horizontal span and
20,000 triangles. A single oversized building remains intact. Use a fresh
output directory when comparing results. `--legacy-per-building true` retains
the previous individual L1/L2/L3 chain for comparison. Without a LOD chain,
textured output remains per-building; untextured output keeps its existing
batching path. Existing output directories must be regenerated to benefit.

The texture pipeline recognizes common usage-field variants such as
`bld_usage`, `bldUsage`, and `bldUseage`. In textured LOD chains, only vertical
wall geometry receives the facade atlas; roofs, parapets, and rooftop equipment
keep their own solid materials. Atlas cells include duplicated edge padding and
UV inset to prevent filtering seams. Atlas generation preserves the supplied
source images without adding procedural facade details.


## Textured LOD cache budget

Textured LOD generation defaults to a 384 x 256 shared atlas; L1 stays untextured.
Use `--texture-atlas-width 384|768|1536` (default 384) or the web viewer quality
selector. This option applies to shared-atlas LOD chains, not standalone embedded
textures. The compact mode preserves atlas layout/normalized UVs but trades
close-range sharpness for a smaller per-model texture footprint. Larger atlases
can trigger Cesium memory-driven refinement changes with thousands of buildings.
The web server uses `.build-check/classes`; Java source changes must be compiled
there before testing through the web UI. The existing executable JAR is not
updated by this development deployment.
