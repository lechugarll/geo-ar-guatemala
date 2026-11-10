#!/usr/bin/env python3
"""
Genera app/src/main/assets/dem_guatemala.bin — modelo digital de elevación
(DEM) de Guatemala para la oclusión por terreno de GeoAR.

Fuente: tiles "terrarium" del dataset público de AWS (derivado de SRTM 30 m),
servidos sin API key desde s3.amazonaws.com/elevation-tiles-prod.

Uso (una sola vez, requiere internet):

    pip install requests pillow numpy
    python tools/generar_dem.py

Salida: grilla de 0.01° (~1.1 km) que cubre todo Guatemala, ~450 KB.
La app la detecta automáticamente en assets; si no existe usa el fallback
de oclusión por catálogo.

Formato binario (big-endian, leído por DemRepository.kt):
    "DEM1" | latNorte f64 | lonOeste f64 | paso f64 | filas i32 | cols i32 | int16[]
"""

import math
import struct
import sys
from pathlib import Path

import numpy as np
import requests
from PIL import Image
from io import BytesIO

# --- Cobertura: bounding box de Guatemala con margen ---
LAT_NORTE = 18.2
LAT_SUR   = 13.4
LON_OESTE = -92.6
LON_ESTE  = -88.0
PASO      = 0.01          # grados por celda (~1.1 km)
ZOOM      = 9             # zoom de tiles (resolución ~300 m/píxel, sobra para 1.1 km)

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
SALIDA = Path(__file__).resolve().parent.parent / "app/src/main/assets/dem_guatemala.bin"


def lonlat_a_tile(lon: float, lat: float, z: int):
    """Coordenada → índice de tile XYZ (Web Mercator)."""
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return x, y


def decodificar_terrarium(img: Image.Image) -> np.ndarray:
    """PNG terrarium → elevaciones en metros: elev = R*256 + G + B/256 - 32768."""
    rgb = np.asarray(img.convert("RGB"), dtype=np.float64)
    return rgb[..., 0] * 256.0 + rgb[..., 1] + rgb[..., 2] / 256.0 - 32768.0


def descargar_tiles():
    """Descarga todos los tiles del bbox y devuelve un mosaico con su transformación."""
    x0, y0 = lonlat_a_tile(LON_OESTE, LAT_NORTE, ZOOM)
    x1, y1 = lonlat_a_tile(LON_ESTE, LAT_SUR, ZOOM)

    ancho = (x1 - x0 + 1) * 256
    alto  = (y1 - y0 + 1) * 256
    mosaico = np.zeros((alto, ancho), dtype=np.float64)

    total = (x1 - x0 + 1) * (y1 - y0 + 1)
    n = 0
    sesion = requests.Session()
    for tx in range(x0, x1 + 1):
        for ty in range(y0, y1 + 1):
            n += 1
            print(f"  tile {n}/{total}  z{ZOOM} x{tx} y{ty}", end="\r", flush=True)
            r = sesion.get(URL.format(z=ZOOM, x=tx, y=ty), timeout=30)
            r.raise_for_status()
            elev = decodificar_terrarium(Image.open(BytesIO(r.content)))
            fy = (ty - y0) * 256
            fx = (tx - x0) * 256
            mosaico[fy:fy + 256, fx:fx + 256] = elev
    print()
    return mosaico, x0, y0


def pixel_de_lonlat(lon: float, lat: float, x0: int, y0: int):
    """Coordenada → posición de píxel (fraccional) dentro del mosaico."""
    n = 2 ** ZOOM
    px = ((lon + 180.0) / 360.0 * n - x0) * 256.0
    lat_rad = math.radians(lat)
    py = ((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n - y0) * 256.0
    return px, py


def main():
    print(f"Descargando tiles de elevación de Guatemala (zoom {ZOOM})…")
    mosaico, x0, y0 = descargar_tiles()

    filas = int(round((LAT_NORTE - LAT_SUR) / PASO)) + 1
    cols  = int(round((LON_ESTE - LON_OESTE) / PASO)) + 1
    print(f"Remuestreando a grilla {filas}×{cols} (paso {PASO}°)…")

    grilla = np.zeros((filas, cols), dtype=np.int16)
    alto, ancho = mosaico.shape
    for f in range(filas):
        lat = LAT_NORTE - f * PASO
        for c in range(cols):
            lon = LON_OESTE + c * PASO
            px, py = pixel_de_lonlat(lon, lat, x0, y0)
            ix = min(max(int(round(px)), 0), ancho - 1)
            iy = min(max(int(round(py)), 0), alto - 1)
            elev = mosaico[iy, ix]
            grilla[f, c] = np.int16(np.clip(elev, -500, 6000))

    SALIDA.parent.mkdir(parents=True, exist_ok=True)
    with open(SALIDA, "wb") as fh:
        fh.write(b"DEM1")
        fh.write(struct.pack(">dddii", LAT_NORTE, LON_OESTE, PASO, filas, cols))
        fh.write(grilla.astype(">i2").tobytes())

    kb = SALIDA.stat().st_size / 1024
    print(f"Listo: {SALIDA}  ({kb:.0f} KB)")

    # Verificación rápida con puntos conocidos
    referencias = [
        ("Volcán Tajumulco (4220 m)", 15.0433, -91.9036),
        ("Volcán de Agua (3766 m)",   14.4650, -90.7430),
        ("Ciudad de Guatemala (~1500 m)", 14.6349, -90.5069),
        ("Lago de Atitlán (~1562 m)", 14.6900, -91.2025),
    ]
    print("Verificación:")
    for nombre, lat, lon in referencias:
        f = int(round((LAT_NORTE - lat) / PASO))
        c = int(round((lon - LON_OESTE) / PASO))
        print(f"  {nombre}: DEM = {grilla[f, c]} m")


if __name__ == "__main__":
    sys.exit(main())
