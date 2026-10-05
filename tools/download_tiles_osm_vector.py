#!/usr/bin/env python3
"""下载上海范围 OSM 矢量瓦片（MVT/Mapbox Vector Tile），生成 MBTiles 离线包。
数据源：Maptoolkit.org 公共矢量瓦片（基于 OSM，WGS-84）。

用途：个人技术验证（注意：Maptoolkit 社区许可禁止离线化，请勿分发/商用）。

用法:
    proxychains4 -f /tmp/pc1080.conf python3 tools/download_tiles_osm_vector.py [输出文件]
默认输出: maps/shanghai-osm-vector.mbtiles
"""
import math
import os
import sqlite3
import sys
import threading
import time
import zlib
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT = sys.argv[1] if len(sys.argv) > 1 else "maps/shanghai-osm-vector.mbtiles"
# 版本号来自 tileset: https://tiles.maptoolkit.org/mtk.json
TILESET = "https://tiles.maptoolkit.org/mtk.json"
URL_TMPL = "https://tiles.maptoolkit.org/{version}/mtk/{z}/{x}/{y}.mvt"
WORKERS = 6
TIMEOUT = 20
RETRIES = 3

# (west, south, east, north, minz, maxz) —— 先核心验证区（华东师大闵行 ±5km）
REGIONS = [
    (120.85, 30.65, 122.10, 31.90, 10, 13),   # 全市概览
    (121.10, 30.90, 121.90, 31.50, 14, 15),   # 中心城区+近郊
    (121.385, 30.985, 121.507, 31.083, 15, 15),   # 核心圈 z15 细化
]


def deg2xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return max(0, min(n - 1, x)), max(0, min(n - 1, y))


def region_tiles(w, s, e, n, z):
    x0, y0 = deg2xy(w, n, z)
    x1, y1 = deg2xy(e, s, z)
    return [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def all_tiles():
    seen, tiles = set(), []
    for (w, s, e, n, zmin, zmax) in REGIONS:
        for z in range(zmin, zmax + 1):
            for t in region_tiles(w, s, e, n, z):
                if t not in seen:
                    seen.add(t)
                    tiles.append(t)
    return tiles


def fetch_version():
    req = urllib.request.Request(TILESET, headers={"User-Agent": "LocationTracker-dev/0.1"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        import json
        return json.load(r)["tiles"][0].split("/")[3]  # v28092026


def fetch(z, x, y, version):
    url = URL_TMPL.format(version=version, x=x, y=y, z=z)
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "LocationTracker-dev/0.1",
                "Accept-Encoding": "gzip",
            })
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                data = r.read()
                if r.headers.get("Content-Encoding") == "gzip":
                    data = zlib.decompress(data, zlib.MAX_WBITS | 16)
            if data and len(data) > 40 and data[0] == 0x1a:  # MVT 首字节=layer 字段 tag
                return data
        except Exception:
            time.sleep(0.3 * (attempt + 1))
    return None


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    version = fetch_version()
    print("tileset version:", version)

    db = sqlite3.connect(OUT)
    db.executescript("""
        CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT);
        CREATE TABLE IF NOT EXISTS tiles (
            zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        CREATE UNIQUE INDEX IF NOT EXISTS tile_index
            ON tiles (zoom_level, tile_column, tile_row);
    """)
    for k, v in [("name", "Shanghai OSM Vector Offline"), ("type", "baselayer"),
                 ("version", version), ("format", "mvt"), ("type.layers", ""),
                 ("bounds", "120.85,30.65,122.10,31.90"),
                 ("minzoom", "10"), ("maxzoom", "15"),
                 ("attribution", "OpenStreetMap contributors / Maptoolkit.org")]:
        db.execute("INSERT OR REPLACE INTO metadata VALUES (?,?)", (k, v))
    db.commit()

    have = set()
    for z, x, tms_y in db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
        have.add((z, x, (1 << z) - 1 - tms_y))
    todo = [t for t in all_tiles() if t not in have]
    total = len(todo)
    print(f"待下载 {total} 片，线程 {WORKERS}")

    lock = threading.Lock()
    done = [0, 0, time.time()]

    def work(t):
        z, x, y = t
        data = fetch(z, x, y, version)
        with lock:
            done[0 if data else 1] += 1
            if done[0] % 200 == 0:
                el = time.time() - done[2]
                rate = done[0] / max(el, 0.1)
                eta = (total - done[0]) / max(rate, 0.1)
                print(f"  {done[0]}/{total} fail={done[1]} {rate:.1f}片/秒 剩余{eta/60:.1f}分", flush=True)
        if data is None:
            return None
        tms_y = (2 ** z - 1) - y
        return (z, x, tms_y, data)

    batch = []

    def flush():
        if batch:
            db.executemany("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)", batch)
            db.commit()
            batch.clear()

    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for f in as_completed([ex.submit(work, t) for t in todo]):
            r = f.result()
            if r:
                batch.append(r)
                if len(batch) >= 300:
                    flush()
    flush()

    n = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
    size = os.path.getsize(OUT) / 1e6
    db.close()
    print(f"完成：共 {n} 瓦片，文件 {size:.1f} MB → {OUT}")
    print(f"  adb push {OUT} /sdcard/Android/data/com.xu.locationtracker/files/maps/")


if __name__ == "__main__":
    main()