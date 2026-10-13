#!/usr/bin/env python3
"""Generate a large projected polygon SHP for 3D Tiles stress testing.

The default extent is 1,000 km by 750 km (750,000 km2). Geometry is written
as a stream so the generator remains small and predictable for large counts.
"""

from __future__ import annotations

import argparse
import math
import random
import struct
from pathlib import Path


FIELD_DEFS = [
    ("bld_id", "N", 12, 0),
    ("jzgd", "N", 12, 2),
    ("base_z", "N", 12, 2),
    ("lc", "N", 8, 0),
    ("bldUseage", "C", 40, 0),
]
USAGES = ["residential", "commercial", "industrial", "public", "storage"]


def ring_for_building(cx: float, cy: float, width: float, depth: float,
                      shape: int) -> list[tuple[float, float]]:
    """Make one clockwise, valid simple ring with a varied urban footprint."""
    w, h = width, depth
    chamfer = min(w, h) * 0.16
    shapes = [
        [(0, 0), (0, h), (w, h), (w, 0)],
        [(chamfer, 0), (0, chamfer), (0, h-chamfer), (chamfer, h),
         (w-chamfer, h), (w, h-chamfer), (w, chamfer), (w-chamfer, 0)],
        [(0, 0), (0, h), (w*.58, h), (w*.58, h*.52), (w, h*.52), (w, 0)],
        [(0, 0), (0, h), (w*.24, h), (w*.24, h*.48),
         (w*.76, h*.48), (w*.76, h), (w, h), (w, 0)],
        [(w*.14, 0), (0, h), (w, h), (w*.86, 0)],
        [(0, 0), (0, h*.45), (w*.28, h*.45), (w*.28, h),
         (w*.72, h), (w*.72, h*.45), (w, h*.45), (w, 0)],
    ]
    local = shapes[shape % len(shapes)]
    return [(cx + x - w/2, cy + y - h/2) for x, y in local] + [
        (cx + local[0][0] - w/2, cy + local[0][1] - h/2)]


def write_polygon(shp, shx, index: int, points: list[tuple[float, float]],
                  offset_words: int) -> int:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    content = bytearray(struct.pack("<i", 5))
    content += struct.pack("<4d", min(xs), min(ys), max(xs), max(ys))
    content += struct.pack("<2i", 1, len(points))
    content += struct.pack("<i", 0)
    for x, y in points:
        content += struct.pack("<2d", x, y)
    content_words = len(content) // 2
    shp.write(struct.pack(">2i", index + 1, content_words))
    shp.write(content)
    shx.write(struct.pack(">2i", offset_words, content_words))
    return offset_words + 4 + content_words


def generate_continuous(out: Path, count: int, width_km: float,
                        height_km: float, seed: int) -> dict[str, object]:
    """Dense continuous urban fabric, with varied L/U/T/chamfered footprints."""
    out.mkdir(parents=True, exist_ok=True)
    stem = out / "stress_buildings"
    width, height = width_km * 1000.0, height_km * 1000.0
    cols = max(1, math.ceil(math.sqrt(count * width / height)))
    rows = math.ceil(count / cols)
    cell_x, cell_y = width / cols, height / rows
    rng = random.Random(seed)
    shp_path, shx_path = stem.with_suffix(".shp"), stem.with_suffix(".shx")
    dbf_rows: list[tuple[str, ...]] = []
    with shp_path.open("w+b") as shp, shx_path.open("w+b") as shx:
        shp.write(make_header(50, 0.0, 0.0, width, height))
        shx.write(make_header(50, 0.0, 0.0, width, height))
        offset_words = 50
        for index in range(count):
            col, row = index % cols, index // cols
            cx = (col + 0.5) * cell_x + (rng.random() - 0.5) * cell_x * 0.12
            cy = (row + 0.5) * cell_y + (rng.random() - 0.5) * cell_y * 0.12
            bw = cell_x * (0.68 + rng.random() * 0.24)
            bd = cell_y * (0.68 + rng.random() * 0.24)
            cx = min(max(cx, bw / 2), width - bw / 2)
            cy = min(max(cy, bd / 2), height - bd / 2)
            points = ring_for_building(cx, cy, bw, bd, rng.randrange(6))
            offset_words = write_polygon(shp, shx, index, points, offset_words)
            floors = (18 + int(rng.random() * 42)) if rng.random() < 0.16 else (
                2 + int(rng.random() * 24))
            dbf_rows.append((str(index + 1), f"{floors * 3.2:.2f}", "0.00",
                             str(floors), USAGES[index % len(USAGES)]))
        shp.seek(0)
        shp.write(make_header(offset_words, 0.0, 0.0, width, height))
        shx.seek(0)
        shx.write(make_header(50 + count * 4, 0.0, 0.0, width, height))
    write_dbf(stem.with_suffix(".dbf"), dbf_rows)
    write_prj(stem.with_suffix(".prj"))
    return {
        "shp": str(stem.with_suffix(".shp").resolve()),
        "prj": str(stem.with_suffix(".prj").resolve()),
        "count": count, "pattern": "continuous-varied-footprints",
        "widthKm": width_km, "heightKm": height_km,
        "areaKm2": width_km * height_km,
        "grid": {"columns": cols, "rows": rows},
        "bytes": sum(p.stat().st_size for p in out.glob("stress_buildings.*")),
    }


def write_dbf(path: Path, rows: list[tuple[str, ...]]) -> None:
    field_count = len(FIELD_DEFS)
    record_len = 1 + sum(x[2] for x in FIELD_DEFS)
    header_len = 32 + field_count * 32 + 1
    with path.open("wb") as out:
        out.write(struct.pack("<BBBBLHH20x", 0x03, 126, 1, 1,
                              len(rows), header_len, record_len))
        for name, kind, length, decimals in FIELD_DEFS:
            raw = name.encode("ascii")[:10].ljust(11, b"\0")
            out.write(raw)
            out.write(kind.encode("ascii"))
            out.write(b"\0" * 4)
            out.write(bytes((length, decimals)))
            out.write(b"\0" * 14)
        out.write(b"\r")
        for row in rows:
            out.write(b" ")
            for (name, kind, length, decimals), value in zip(FIELD_DEFS, row):
                if kind == "C":
                    raw = str(value).encode("utf-8", errors="replace")[:length]
                    out.write(raw.ljust(length, b" "))
                else:
                    out.write(str(value).encode("ascii")[:length].rjust(length, b" "))
        out.write(b"\x1a")


def write_prj(path: Path) -> None:
    # WGS 84 / Pseudo-Mercator, metres. The converter can transform this to WGS84.
    text = ('PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["WGS 84",'
            'DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
            'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],'
            'PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],'
            'PARAMETER["scale_factor",1],PARAMETER["false_easting",0],'
            'PARAMETER["false_northing",0],UNIT["metre",1],AXIS["Easting",EAST],'
            'AXIS["Northing",NORTH],AUTHORITY["EPSG","3857"]]')
    path.write_text(text, encoding="ascii")


def make_header(file_length_words: int, xmin: float, ymin: float,
                xmax: float, ymax: float) -> bytes:
    header = bytearray(100)
    struct.pack_into(">6i", header, 0, 9994, 0, 0, 0, 0, 0)
    struct.pack_into(">i", header, 24, file_length_words)
    struct.pack_into("<2i", header, 28, 1000, 5)
    struct.pack_into("<4d", header, 36, xmin, ymin, xmax, ymax)
    return bytes(header)


def generate(out: Path, count: int, width_km: float, height_km: float,
             seed: int, pattern: str = "clustered") -> dict[str, object]:
    if pattern == "continuous":
        return generate_continuous(out, count, width_km, height_km, seed)
    out.mkdir(parents=True, exist_ok=True)
    stem = out / "stress_buildings"
    width = width_km * 1000.0
    height = height_km * 1000.0
    # Spread dense, city-like clusters over the full continental extent. This
    # keeps individual footprints realistic while preserving a ~750,000 km2
    # dataset envelope and enough local density for renderer stress testing.
    cluster_count = min(64, max(4, math.ceil(count / 400)))
    cluster_cols = max(1, math.ceil(math.sqrt(cluster_count * width / height)))
    cluster_rows = math.ceil(cluster_count / cluster_cols)
    per_cluster = math.ceil(count / cluster_count)
    local_cols = max(1, math.ceil(math.sqrt(per_cluster)))
    local_rows = math.ceil(per_cluster / local_cols)
    street_x = 150.0
    street_y = 140.0
    cluster_width = max(0.0, (local_cols - 1) * street_x)
    cluster_height = max(0.0, (local_rows - 1) * street_y)
    rng = random.Random(seed)
    record_content_len = 128
    shp_length = 100 + count * (8 + record_content_len)
    shx_length = 100 + count * 8
    bbox = (0.0, 0.0, width, height)
    dbf_rows: list[tuple[str, ...]] = []
    with stem.with_suffix(".shp").open("wb") as shp, stem.with_suffix(".shx").open("wb") as shx:
        shp.write(make_header(shp_length // 2, *bbox))
        shx.write(make_header(shx_length // 2, *bbox))
        for index in range(count):
            cluster = index % cluster_count
            local = index // cluster_count
            cluster_col = cluster % cluster_cols
            cluster_row = cluster // cluster_cols
            anchor_x = cluster_width / 2 if cluster_cols == 1 else (
                cluster_width / 2 + cluster_col * (width - cluster_width) / (cluster_cols - 1))
            anchor_y = cluster_height / 2 if cluster_rows == 1 else (
                cluster_height / 2 + cluster_row * (height - cluster_height) / (cluster_rows - 1))
            local_col = local % local_cols
            local_row = local // local_cols
            cx = anchor_x + (local_col - (local_cols - 1) / 2) * street_x
            cy = anchor_y + (local_row - (local_rows - 1) / 2) * street_y
            cx += (rng.random() - 0.5) * 22.0
            cy += (rng.random() - 0.5) * 18.0
            bw = 28.0 + rng.random() * 72.0
            bd = 24.0 + rng.random() * 58.0
            x0, y0 = max(0.0, cx - bw / 2), max(0.0, cy - bd / 2)
            x1, y1 = min(width, cx + bw / 2), min(height, cy + bd / 2)
            # Shapefile exterior rings are clockwise. Reversing this order can
            # make readers classify shells as holes and create huge triangles.
            points = [(x0, y0), (x0, y1), (x1, y1), (x1, y0), (x0, y0)]
            content = bytearray()
            content += struct.pack("<i", 5)
            content += struct.pack("<4d", x0, y0, x1, y1)
            content += struct.pack("<2i", 1, 5)
            content += struct.pack("<i", 0)
            for x, y in points:
                content += struct.pack("<2d", x, y)
            shp.write(struct.pack(">2i", index + 1, record_content_len // 2))
            shp.write(content)
            shx.write(struct.pack(">2i", (100 + index * (8 + record_content_len)) // 2,
                                  record_content_len // 2))
            floors = (18 + int(rng.random() * 42)) if rng.random() < 0.18 else (
                2 + int(rng.random() * 18))
            usage = USAGES[index % len(USAGES)]
            dbf_rows.append((str(index + 1), f"{floors * 3.2:.2f}", "0.00",
                             str(floors), usage))
    write_dbf(stem.with_suffix(".dbf"), dbf_rows)
    write_prj(stem.with_suffix(".prj"))
    return {
        "shp": str(stem.with_suffix(".shp").resolve()),
        "prj": str(stem.with_suffix(".prj").resolve()),
        "count": count,
        "widthKm": width_km,
        "heightKm": height_km,
        "areaKm2": width_km * height_km,
        "clusters": cluster_count,
        "clusterCols": cluster_cols,
        "clusterRows": cluster_rows,
        "bytes": sum(p.stat().st_size for p in out.glob("stress_buildings.*")),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--count", type=int, default=25000)
    parser.add_argument("--width-km", type=float, default=1000.0)
    parser.add_argument("--height-km", type=float, default=750.0)
    parser.add_argument("--seed", type=int, default=20260929)
    parser.add_argument("--pattern", choices=("clustered", "continuous"), default="clustered")
    args = parser.parse_args()
    if args.count < 1 or args.count > 250000:
        raise SystemExit("count must be between 1 and 250000")
    if args.width_km <= 0 or args.height_km <= 0:
        raise SystemExit("extent must be positive")
    import json
    print(json.dumps(generate(Path(args.out), args.count, args.width_km,
                              args.height_km, args.seed, args.pattern), ensure_ascii=False))


if __name__ == "__main__":
    main()
