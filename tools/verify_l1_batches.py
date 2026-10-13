"""Validate generated textured LOD batches, including external tilesets and GLBs.

Usage: python tools/verify_l1_batches.py OUTPUT --buildings 1449 [--baseline OLD]
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct


def glb(path):
    data = path.read_bytes()
    assert data[:4] == b"b3dm", path
    assert struct.unpack_from("<I", data, 8)[0] == len(data), path
    offset = 28 + sum(struct.unpack_from("<4I", data, 12))
    assert data[offset:offset + 4] == b"glTF", path
    length = struct.unpack_from("<I", data, offset + 12)[0]
    doc = json.loads(data[offset + 20:offset + 20 + length])
    triangles = sum(doc["accessors"][p["indices"]]["count"] // 3
                    for m in doc["meshes"] for p in m["primitives"])
    for image in doc.get("images", []):
        if "uri" in image:
            assert (path.parent / image["uri"]).is_file(), (path, image)
    return doc, triangles, len(data)


def enclosed(parent, child):
    # Generator emits axis-aligned local boxes; compare all three axes.
    for axis, half in enumerate((3, 7, 11)):
        assert abs(child[axis] - parent[axis]) + child[half] <= parent[half] + 1e-5, (parent, child)


def canonical_glb(path):
    # Java Map.of() property order can vary by process; compare JSON semantics
    # and actual binary buffers rather than serialized JSON key order.
    data = path.read_bytes()
    offset = 28 + sum(struct.unpack_from("<4I", data, 12))
    length = struct.unpack_from("<I", data, offset + 12)[0]
    return json.loads(data[offset + 20:offset + 20 + length]), data[offset + 20 + length:]


def verify(root, expected, baseline=None, max_buildings=32):
    counts = Counter()
    visited = set()
    contents = set()
    ids = {2: set(), 3: set()}
    batch_sizes = []
    batch_triangles = []
    l1_bytes = 0

    def walk(node, file):
        nonlocal l1_bytes
        children = node.get("children", [])
        for child in children:
            enclosed(node["boundingVolume"]["box"], child["boundingVolume"]["box"])
        uri = node.get("content", {}).get("uri")
        if uri:
            path = (file.parent / uri).resolve()
            assert path.is_file(), path
            if path.suffix == ".json":
                assert path not in visited, path
                nested = json.loads(path.read_text("utf8"))["root"]
                enclosed(node["boundingVolume"]["box"], nested["boundingVolume"]["box"])
                visited.add(path)
                walk(nested, path)
            else:
                assert path not in contents, path
                contents.add(path)
                doc, triangles, size = glb(path)
                if path.name.startswith("region_"):
                    counts["L1"] += 1
                    l1_bytes += size
                    assert not doc.get("textures"), path
                    assert node["refine"] == "REPLACE" and node["geometricError"] == 18
                    assert 1 <= len(children) <= max_buildings, path
                    batch_sizes.append(len(children))
                    batch_triangles.append(triangles)
                    box = node["boundingVolume"]["box"]
                    if len(children) > 1:
                        assert triangles <= 20000, path
                        assert 2 * max(box[3], box[7]) <= 512 + 1e-5, path
                    for child in children:
                        assert child["content"]["uri"].endswith("_l2.b3dm"), path
                else:
                    level = int(path.stem[-1])
                    assert level in (2, 3), path
                    counts[f"L{level}"] += 1
                    assert doc.get("textures"), path
                    assert node["geometricError"] == (3 if level == 2 else 0), path
                    building = path.stem.rsplit("_l", 1)[0]
                    assert building not in ids[level], path
                    ids[level].add(building)
                    if level == 2:
                        assert len(children) == 1, path
                        assert Path(children[0]["content"]["uri"]).stem == building + "_l3", path
                    else:
                        assert not children, path
                    if baseline:
                        old = baseline / "b3dm" / path.name
                        assert canonical_glb(path) == canonical_glb(old), path
        for child in children:
            walk(child, file)

    file = root / "tileset.json"
    visited.add(file.resolve())
    walk(json.loads(file.read_text("utf8"))["root"], file)
    assert counts["L2"] == counts["L3"] == expected, counts
    assert ids[2] == ids[3] and sum(batch_sizes) == expected
    actual = set(p.resolve() for p in root.rglob("*.b3dm"))
    assert actual == contents, "Unreferenced or missing B3DM files"
    return {"buildings": expected, "contentCounts": dict(counts), "jsonFilesReferenced": len(visited),
            "l1RequestReductionPercent": round(100 * (1 - counts["L1"] / expected), 2),
            "l1Bytes": l1_bytes, "maxBatchBuildings": max(batch_sizes),
            "maxL1Triangles": max(batch_triangles), "l1Triangles": sum(batch_triangles),
            "detailGeometryAndMaterialsMatchBaseline": bool(baseline)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--buildings", required=True, type=int)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    report = verify(args.output.resolve(), args.buildings, args.baseline)
    print(json.dumps(report, indent=2))
    (args.output / "batch-validation.json").write_text(json.dumps(report, indent=2), encoding="utf8")
