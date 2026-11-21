"""
Génère une pyramide de tuiles « XYZ » (Web Mercator, 256 px) du plan GeoTIFF, pour l'afficher
dans l'application comme fond de carte (osmdroid, MapTileAssetsProvider).

Sortie : <tiles_output>/<tiles_name>/<z>/<x>/<y>.webp (WebP avec transparence hors du plan).
Les paramètres sont lus dans le même fichier de configuration que build_network_map.py.

Usage : python build_plan_tiles.py [catacombes_config.json]
Dépendances : numpy, opencv-python, tifffile, Pillow
"""
import json
import math
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from build_network_map import load_geotiff

TILE = 256
EARTH_CIRC = 2 * math.pi * 6378137.0


def lon_to_x(lon, z):
    return (lon + 180.0) / 360.0 * (2 ** z)


def lat_to_y(lat, z):
    s = math.sin(math.radians(lat))
    return (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * (2 ** z)


def main():
    cfg_path = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).with_name("catacombes_config.json"))
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    out_root = (cfg_path.parent / cfg["tiles_output"]).resolve() / cfg["tiles_name"]
    zmin, zmax = cfg["tiles_min_zoom"], cfg["tiles_max_zoom"]

    img, lon0, lat0, sx, sy = load_geotiff(cfg["source"])
    h, w = img.shape[:2]
    lon1, lat1 = lon0 + w * sx, lat0 - h * sy
    rgba = np.dstack([img, np.full((h, w), 255, np.uint8)])
    src_m_per_px = sy * EARTH_CIRC / 360.0

    if out_root.exists():
        shutil.rmtree(out_root)
    count = 0
    total = 0
    for z in range(zmin, zmax + 1):
        lat_c = math.radians((lat0 + lat1) / 2)
        tile_m_per_px = EARTH_CIRC * math.cos(lat_c) / (TILE * 2 ** z)
        # Pré-réduction anti-repliement pour les zooms faibles
        f = tile_m_per_px / src_m_per_px
        if f > 1.2:
            src = cv2.resize(rgba, (max(1, int(w / f)), max(1, int(h / f))), interpolation=cv2.INTER_AREA)
        else:
            src = rgba
        kx, ky = src.shape[1] / w, src.shape[0] / h

        x0, x1 = int(lon_to_x(lon0, z)), int(lon_to_x(lon1, z))
        y0, y1 = int(lat_to_y(lat0, z)), int(lat_to_y(lat1, z))
        n = 2 ** z
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                # Coordonnées géographiques des centres des pixels de la tuile
                px = (tx * TILE + np.arange(TILE) + 0.5) / (TILE * n)
                py = (ty * TILE + np.arange(TILE) + 0.5) / (TILE * n)
                lon = px * 360.0 - 180.0
                lat = np.degrees(np.arctan(np.sinh(math.pi * (1 - 2 * py))))
                map_x = ((lon - lon0) / sx * kx - 0.5).astype(np.float32)
                map_y = ((lat0 - lat) / sy * ky - 0.5).astype(np.float32)
                mx, my = np.meshgrid(map_x, map_y)
                tile = cv2.remap(src, mx, my, interpolation=cv2.INTER_LINEAR,
                                 borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0, 0))
                if tile[..., 3].max() == 0:
                    continue
                path = out_root / str(z) / str(tx) / f"{ty}.webp"
                path.parent.mkdir(parents=True, exist_ok=True)
                Image.fromarray(tile, "RGBA").save(path, "WEBP", quality=cfg["tiles_quality"], method=6)
                count += 1
                total += path.stat().st_size
        print(f"zoom {z} : {count} tuiles cumulées")
    print(f"Écrit : {out_root} ({count} tuiles, {total / 1024 / 1024:.1f} Mio)")


if __name__ == "__main__":
    main()
