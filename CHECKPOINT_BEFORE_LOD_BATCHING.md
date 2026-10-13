# Checkpoint before distant-view LOD batching

This commit preserves the current implementation as a rollback baseline. It does
not claim that all rendering performance issues have been resolved.

## Implemented and verified

- L1 remains untextured. Textured L2/L3 reference a shared atlas.
- CLI --texture-atlas-width supports 384, 768 and 1536; default 384x256.
  The web UI exposes the same quality selection.
- The user confirmed the compact-atlas comparison no longer flickered at the
  same view. Reduced per-model texture accounting pressure is the leading
  explanation; no automated GPU benchmark has been completed.
- Viewer diagnostics include effective SSE, tile loads/unloads, visible LOD
  counts and freeze mode. Out-of-order asynchronous loads cannot add duplicate
  tilesets. Connection health checks and bounded error logging are present.
- Python viewer serves the local Cesium bundle, supports detached startup,
  rotating logs and hidden nvidia-smi subprocesses.
- Full standalone Java javac build and Python/JS syntax checks passed.
- Web conversion of the 1,449-building sample succeeded with zero failures.
  L1/L2/L3 each have 1,449 contents; only L2/L3 are textured. The generated atlas
  exactly matches the user-validated compact comparison.
- Output: C:/Users/zzzzzys/Desktop/output-atlas-production (not tracked).
- Earlier 16-worker HTTP validation read 4,349 resources successfully. This is
  server validation, not an FPS or GPU benchmark.

## Known limitations and next work

- Enabling textures still selects per-building conversion. In that path,
  tileMaxContents=32 limits spatial group size; it does not merge buildings.
- No renderable regional overview exists above L1. At high altitude, many
  independent L1 requests/models produce slow, incremental appearance.
- Next: spatial batching for textured generation, prioritizing merged L1;
  evaluate renderable regional overview content and geometry/payload budgets.
- Current content errors are 18/3/0. Earlier 120/35/0 settings were withdrawn:
  larger geometricError refines earlier; spacing is not hysteresis.
- CLI and GLB writer were recovered from compiled classes; decompiler variable
  names and raw generics remain. Historical source-code is a separate API tree
  and must not be mixed blindly with ShpTo3DTiles-source.
- Compact atlas reduces close-range texture sharpness. Textured mesh batching
  has not yet been implemented.

## Repository and runtime

Tracks both source trees, build metadata, web assets/server, helper scripts,
project documentation and source resource textures. Generated tiles, SHP data,
Java runtime, Cesium distribution, dependencies and logs remain on disk but are
ignored. This source checkpoint alone is not an offline executable package.

Build details: ShpTo3DTiles-source/README.md. The web server currently prefers
.build-check/classes. Existing packaged JARs were not updated by development
classpath deployment. Local launch: python start_web_viewer.py, port 8930.
Cesium resource path: offline-package-build/cesium/Build/Cesium.
