# Reverse-engineering record

## Brief

- Objective: recover a standalone Java source tree from the supplied
  `ShpTo3DTiles-optimized.zip` package.
- Evidence carrier: the package's
  `ShpTo3DTiles-optimized/app/shp-to-3dtiles-gui.jar`.
- Scope: application classes under `com.pipeline.api`; embedded third-party
  dependencies are excluded.
- Method: extract `com.pipeline.api` bytecode from the packaged JAR and
  decompile it with Vineflower 1.12.0. CFR 0.152 output was retained locally
  for comparison but is excluded from this repository. `src/main/java` has
  target-derived collection/local types repaired against bytecode signatures
  and validated by compilation; no other source tree was used as
  implementation input.

## Evidence and limits

- The supplied ZIP contains GUI/offline fat JARs and launcher configuration,
  but no source JAR and no `.java` files.
- No source tree or source JAR outside the supplied ZIP was used to reconstruct
  the implementation. No tests from the older `ShpTo3DTiles-source` directory
  were used or copied into this reconstruction.
- Decompilation preserves recoverable bytecode behavior, not original source
  text, comments, formatting, or necessarily all generic/source constructs.
- The supplied package does not include authoritative build metadata. The
  Maven dependency set was derived from application imports and the fat JAR's
  embedded libraries.
- `mvn verify` succeeds with JDK 17 and produces the shaded executable JAR.
  The current tree contains three JUnit test classes covering geometry
  normalization and roof caps, textured roof/facade separation, and diagnostic
  logging. The latest verification ran 8 tests with no failures.
- CLI and OBJ-to-GLB help entry points both start successfully from the shaded
  JAR.
- End-to-end smoke conversion of a local 1,449-feature Shapefile succeeded
  with textures disabled. It produced 12 GLBs and a 3D Tiles 1.1 tileset. All
  referenced content paths exist; all GLB headers report magic `glTF`, version
  2, and a declared byte length matching each file. The conversion output was
  written under `%TEMP%`.
- This bounded smoke test does not prove equivalence across all inputs, LODs,
  GUI interaction, or failure/recovery paths. A focused regression test covers
  the textured LOD roof/facade classification fix; it is not a substitute for
  conversion against every production SHP dataset.
- No REA MCP/native-analysis tool was available in this session, so the
  documented local artifact workflow was used instead.

## Hashes

SHA-256 values are recorded at generation time below. Recompute them if either
artifact is replaced.

| Artifact | SHA-256 |
| --- | --- |
| `ShpTo3DTiles-optimized.zip` | `E353F4162A859594172B918EFC046C30B51C023AB743A6E1DE467DC7B7F69FB6` |
| `ShpTo3DTiles-optimized/app/shp-to-3dtiles-gui.jar` | `3DB0351FC583E839C73EA47A0A1CDB64282FAE3C7F8AA28256E7BFF6238CDB4B` |

## Validation status

- Reconstructed application Java source files: 21; raw CFR comparison sources
  are kept outside the published repository.
- Compile/package: passed with JDK 17; the current source tree passes `mvn
  verify` and all 8 automated tests.
- CLI help and packaged-SHP conversion smoke checks: passed as described above.
- This record describes provenance and known limits; it is not a claim of
  complete equivalence.
