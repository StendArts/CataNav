"""
Extraction du réseau praticable d'un plan GeoTIFF (WGS84) pour l'hybridation carte (module KAL).

Le plan des catacombes représente les galeries de deux façons :
  - galeries étroites : deux parois en traits gris neutres (~165), intérieur blanc ;
  - galeries larges / salles : parois grises et intérieur gris très clair (~240-245).
Les rues de surface sont des traits gris plus clairs (~230) et le texte a un cœur foncé (< 110).

Zone praticable = (galeries à double paroi, comblées) ∪ (intérieurs gris clair et leurs parois),
élargie de ±wall_band_radius_px,
après suppression du texte, des petits fragments et des zones exclues (encarts, légende…).

Sortie : fichier binaire .iaumap lu par fr.inertialnav.iau.map.RasterNetworkMap
  "IAUMAP01" | int largeur | int hauteur | double lon_gauche | double lat_haut
  | double dlon_par_px | double dlat_par_px | UTF nom | gzip(bitset ligne par ligne, MSB d'abord)
(gros-boutiste, format java.io.DataOutputStream ; chaque ligne est complétée à l'octet)

Usage : python build_network_map.py [catacombes_config.json]
Dépendances : numpy, opencv-python, tifffile, Pillow
"""
import gzip
import json
import struct
import sys
from pathlib import Path

import cv2
import numpy as np
import tifffile
from PIL import Image


def load_geotiff(path):
    with tifffile.TiffFile(path) as tif:
        geo = tif.geotiff_metadata
        img = tif.pages[0].asarray()
    if geo is None:
        raise ValueError("Pas de géoréférencement GeoTIFF")
    if int(geo.get("GeographicTypeGeoKey", 0)) != 4326:
        raise ValueError("Seul le WGS84 géographique (EPSG:4326) est pris en charge")
    sx, sy = geo["ModelPixelScale"][:2]
    tp = geo["ModelTiepoint"]
    i, j = tp[0], tp[1]
    lon0 = tp[3] - i * sx  # bord gauche du pixel (0, 0) (RasterPixelIsArea)
    lat0 = tp[4] + j * sy  # bord haut
    return img[..., :3], lon0, lat0, sx, sy


def disk(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))


def extract(img, cfg):
    px = img.astype(np.int16)
    mx = px.max(axis=2)
    mn = px.min(axis=2)
    neutral = (mx - mn) <= cfg["neutral_max_saturation"]
    bluish = (px[..., 2] - px[..., 0]) > 20

    # Texte : cœur foncé (gris neutre ou bleu marine), dilaté pour emporter l'anticrénelage
    text = ((neutral | bluish) & (mx < cfg["text_max_grey"])).astype(np.uint8)
    text = cv2.dilate(text, disk(cfg["text_dilation_px"] // 2))

    walls = (neutral & (mx >= cfg["wall_min_grey"]) & (mx < cfg["wall_max_grey"])).astype(np.uint8)
    walls[text > 0] = 0

    fill = (neutral & (mx >= cfg["fill_min_grey"]) & (mx <= cfg["fill_max_grey"])).astype(np.uint8)
    fill = cv2.morphologyEx(fill, cv2.MORPH_OPEN, disk(cfg["fill_open_px"] // 2))

    # Galeries étroites = DOUBLE trait : la fermeture comble l'espace entre les deux parois,
    # l'ouverture élimine ensuite les traits simples (arcs décoratifs, limites, rues sombres).
    corridors = cv2.morphologyEx(walls, cv2.MORPH_CLOSE, disk(cfg["corridor_close_px"]))
    corridors = cv2.morphologyEx(corridors, cv2.MORPH_OPEN, disk(cfg["corridor_open_px"]))
    # Parois simples bordant un intérieur gris clair (galeries larges)
    fill_walls = walls & cv2.dilate(fill, disk(cfg["wall_band_radius_px"] + 1))

    walk = cv2.dilate(corridors | fill_walls, disk(cfg["wall_band_radius_px"])) | fill

    # Zones exclues (encarts, légendes, titre, marges)
    m = cfg["margin_px"]
    walk[:m, :] = 0
    walk[-m:, :] = 0
    walk[:, :m] = 0
    walk[:, -m:] = 0
    for r in cfg["exclude_rects"]:
        walk[r["y0"]:r["y1"], r["x0"]:r["x1"]] = 0
    for c in cfg["exclude_circles"]:
        cv2.circle(walk, (c["x"], c["y"]), c["r"], 0, thickness=-1)

    # Fragments isolés (symboles, restes de lettres)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(walk, connectivity=8)
    keep = np.zeros(n, dtype=bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= cfg["min_component_px"]
    return keep[lab]


def write_iaumap(path, mask, lon0, lat0, sx, sy, name):
    h, w = mask.shape
    bits = np.packbits(mask.astype(np.uint8), axis=1, bitorder="big")  # lignes complétées à l'octet
    name_bytes = name.encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"IAUMAP01")
        f.write(struct.pack(">ii", w, h))
        f.write(struct.pack(">dddd", lon0, lat0, sx, sy))
        f.write(struct.pack(">H", len(name_bytes)) + name_bytes)  # format writeUTF (ASCII/UTF-8 BMP)
        f.write(gzip.compress(bits.tobytes(), compresslevel=9))


def write_preview(path, img, mask, cfg, size=2000):
    ov = (img.astype(np.float32) * 0.4 + 255 * 0.6).astype(np.uint8)
    ov[mask] = (40, 110, 230)
    for r in cfg["exclude_rects"]:
        cv2.rectangle(ov, (r["x0"], r["y0"]), (r["x1"], r["y1"]), (230, 40, 40), 6)
    for c in cfg["exclude_circles"]:
        cv2.circle(ov, (c["x"], c["y"]), c["r"], (230, 40, 40), 6)
    Image.fromarray(ov).resize((size, size), Image.BOX).save(path)


def main():
    cfg_path = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).with_name("catacombes_config.json"))
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    base = cfg_path.parent

    img, lon0, lat0, sx, sy = load_geotiff(cfg["source"])
    mask = extract(img, cfg)

    out = (base / cfg["output"]).resolve()
    write_iaumap(out, mask, lon0, lat0, sx, sy, cfg["name"])
    write_preview(base / cfg["preview"], img, mask, cfg)

    h, w = mask.shape
    print(f"Image {w}x{h}, pixel {sx:.3e}° x {sy:.3e}°, coin NO ({lat0:.7f}, {lon0:.7f})")
    print(f"Pixels praticables : {mask.sum()} ({mask.mean() * 100:.2f} %)")
    print(f"Écrit : {out} ({out.stat().st_size / 1024:.0f} Kio)")


if __name__ == "__main__":
    main()
