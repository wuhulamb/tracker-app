#!/usr/bin/env python3
"""
下载上海范围 OpenStreetMap（WGS-84 / "GPS 坐标"）栅格瓦片，生成 MBTiles 离线底图。
注：OSM 切片为 WGS-84 标准墨卡托，App 显示时无需再做 GCJ 纠偏；
国内需在代理环境运行（proxychains4 -f cfg python3 tools/download_tiles_osm.py）。

用法:
    python3 tools/download_tiles.py [输出文件]
    默认输出: maps/shanghai.mbtiles

生成后导入手机:
    adb shell mkdir -p /sdcard/Android/data/com.xu.locationtracker/files/maps
    adb push maps/shanghai.mbtiles /sdcard/Android/data/com.xu.locationtracker/files/maps/

范围:
    上海市全域 z10-13 (概览)
    中心城区+近郊 z14-16 (详细)
"""
import math
import os
import sqlite3
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT = sys.argv[1] if len(sys.argv) > 1 else "maps/shanghai-osm.mbtiles"
URL_TMPL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
WORKERS = 6  # OSM 侧低并发，避免触发限流
TIMEOUT = 15
RETRIES = 3

# (west, south, east, north, minz, maxz)
REGIONS = [
    (120.85, 30.65, 122.10, 31.90, 10, 13),   # 全市概览
    (121.10, 30.90, 121.90, 31.50, 14, 16),   # 中心城区+近郊
    (121.385, 30.985, 121.507, 31.083, 17, 17),   # 核心圈 z17（华东师大闵行 ±6km）
    (121.415, 31.007, 121.477, 31.061, 18, 18),   # 核心区 z18（±3km）
]


def deg2xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return max(0, min(n - 1, x)), max(0, min(n - 1, y))


def region_tiles(w, s, e, n, z):
    x0, y0 = deg2xy(w, n, z)  # 北边纬度决定 y 起点
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


def fetch(z, x, y):
    url = URL_TMPL.format(x=x, y=y, z=z)
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                data = r.read()
            if data and len(data) > 100:
                return data
        except Exception:
            time.sleep(0.3 * (attempt + 1))
    return None


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    db = sqlite3.connect(OUT)
    db.executescript("""
        CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT);
        CREATE TABLE IF NOT EXISTS tiles (
            zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        CREATE UNIQUE INDEX IF NOT EXISTS tile_index
            ON tiles (zoom_level, tile_column, tile_row);
    """)
    for k, v in [("name", "Shanghai OSM Offline"), ("type", "baselayer"),
                 ("version", "1.0"), ("format", "png"),
                 ("bounds", "120.85,30.65,122.10,31.90"),
                 ("minzoom", "10"), ("maxzoom", "18"),
                 ("attribution", "OpenStreetMap"),
                 ("gcj", "false")]:
        db.execute("INSERT OR REPLACE INTO metadata VALUES (?,?)", (k, v))
    db.commit()

    have = set()
    for z, x, tms_y in db.execute("SELECT zoom_level, tile_column, tile_row FROM tiles"):
        have.add((z, x, (1 << z) - 1 - tms_y))
    todo = [t for t in all_tiles() if t not in have]
    total = len(todo)
    print(f"已有 {len(have)} 瓦片，待下载 {total}，线程 {WORKERS}")

    lock = threading.Lock()
    done = [0, 0, time.time()]  # ok, fail, start

    def work(t):
        z, x, y = t
        data = fetch(z, x, y)
        with lock:
            if data is None:
                done[1] += 1
            else:
                done[0] += 1
                if done[0] % 500 == 0:
                    el = time.time() - done[2]
                    rate = done[0] / max(el, 0.1)
                    eta = (total - done[0]) / max(rate, 0.1)
                    print(f"  {done[0]}/{total} fail={done[1]}  "
                          f"{rate:.1f}片/秒  剩余约{eta/60:.1f}分钟", flush=True)
        if data is None:
            return None
        tms_y = (2 ** z - 1) - y
        return (z, x, tms_y, data)

    # 批量缓冲，降低 sqlite 开销
    batch = []

    def flush():
        if batch:
            db.executemany("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)", batch)
            db.commit()
            batch.clear()

    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = [ex.submit(work, t) for t in todo]
        for f in as_completed(futs):
            r = f.result()
            if r:
                batch.append(r)
                if len(batch) >= 500:
                    flush()
    flush()

    n = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
    size = os.path.getsize(OUT) / 1e6
    db.close()
    print(f"完成：共 {n} 瓦片，文件 {size:.1f} MB → {OUT}")
    print("导入手机：")
    print("  adb shell mkdir -p /sdcard/Android/data/com.xu.locationtracker/files/maps")
    print(f"  adb push {OUT} /sdcard/Android/data/com.xu.locationtracker/files/maps/")


if __name__ == "__main__":
    main()