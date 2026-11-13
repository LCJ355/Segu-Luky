# -*- coding: utf-8 -*-
"""
Analizador de calibracion de Segu-Luky (V-1.1s).

Objetivo: reunir datos de varios moviles/actividades y recomendar la
SENSIBILIDAD (umbral de golpe en g) que evite falsos SOS.

Uso:
  python analizar_calibracion.py <carpeta | archivos .csv | archivos .zip> ...

Acepta:
  - Carpetas (busca *.csv y *.zip dentro).
  - CSV sueltos.
  - ZIP exportados por la app (lee los CSV y ajustes.txt de dentro).

Salida:
  - Informe por dispositivo+actividad en pantalla.
  - Archivo "recomendaciones.txt" junto al primer elemento de entrada.

Criterio de recomendacion (evitar falsos SOS):
  La sensibilidad debe quedar por ENCIMA del maximo de aceleracion dinamica
  observado en conduccion/uso normal. Se recomienda:
      sensibilidad = redondeo_a_0.5( max_normal_g * 1.20 )   (minimo 1.5 g)
  donde max_normal_g es el maximo entre todas las actividades del dispositivo.
  Se avisa si el maximo se acerca a 7 g (posible sensor ruidoso o mal soporte).
"""
import glob
import io
import math
import os
import sys
import zipfile
from collections import defaultdict

G = 9.80665


def percentile(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f = int(math.floor(k))
    c = int(math.ceil(k))
    if f == c:
        return s[f]
    return s[f] * (c - k) + s[c] * (k - f)


def stats(values):
    if not values:
        return None
    mean = sum(values) / len(values)
    var = sum((v - mean) ** 2 for v in values) / len(values)
    return {
        "n": len(values),
        "mean": mean,
        "std": math.sqrt(var),
        "p95": percentile(values, 95),
        "p99": percentile(values, 99),
        "p999": percentile(values, 99.9),
        "max": max(values),
    }


def parse_stream(text):
    meta = {}
    rows = defaultdict(list)
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#"):
            if "=" in line:
                k, v = line[1:].split("=", 1)
                meta[k.strip()] = v.strip()
            continue
        parts = line.split(",")
        if len(parts) < 5 or parts[0] == "tipo":
            continue
        try:
            t = float(parts[1]); a = float(parts[2]); b = float(parts[3]); c = float(parts[4])
            speed = None
            if len(parts) > 10 and parts[10].strip():
                speed = float(parts[10])
        except ValueError:
            continue
        rows[parts[0]].append((t, a, b, c, speed))
    return meta, rows


def dynamic_g_samples(rows):
    """Pares (aceleracion dinamica en g, velocidad km/h o None)."""
    lin = rows.get("lin", [])
    lin_meaningful = lin and any(abs(a) + abs(b) + abs(c) > 0.05 for (_, a, b, c, _) in lin)
    if lin_meaningful:
        return [
            (math.sqrt(a * a + b * b + c * c) / G, speed)
            for (_, a, b, c, speed) in lin
        ]
    accel = rows.get("accel", [])
    grav = rows.get("grav", [])
    if accel and grav:
        grav_map = [(t, a, b, c) for (t, a, b, c, _) in grav]
        out = []
        for (t, a, b, c, speed) in accel:
            ga, gb, gc = nearest_gravity(grav_map, t)
            out.append((math.sqrt((a - ga) ** 2 + (b - gb) ** 2 + (c - gc) ** 2) / G, speed))
        return out
    if accel:
        return [
            (abs(math.sqrt(a * a + b * b + c * c) / G - 1.0), speed)
            for (_, a, b, c, speed) in accel
        ]
    return []


def dynamic_g(rows):
    return [value for value, _ in dynamic_g_samples(rows)]


def nearest_gravity(grav, t):
    if not grav:
        return (0.0, 0.0, G)
    lo, hi = 0, len(grav) - 1
    while lo < hi:
        mid = (lo + hi) // 2
        if grav[mid][0] < t:
            lo = mid + 1
        else:
            hi = mid
    best = grav[lo]
    if lo > 0 and abs(grav[lo - 1][0] - t) < abs(best[0] - t):
        best = grav[lo - 1]
    return (best[1], best[2], best[3])


def collect(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            files.extend(glob.glob(os.path.join(p, "**", "*.csv"), recursive=True))
            files.extend(glob.glob(os.path.join(p, "**", "*.zip"), recursive=True))
        else:
            files.extend(glob.glob(p))
    return sorted(set(files))


def iter_csv_from(path):
    """Devuelve lista de (nombre, texto) para un CSV o ZIP."""
    out = []
    if path.lower().endswith(".zip"):
        try:
            with zipfile.ZipFile(path) as z:
                for name in z.namelist():
                    if name.lower().endswith(".csv"):
                        out.append((name, z.read(name).decode("utf-8", "replace")))
        except Exception as exc:
            print("  ! ZIP no legible:", path, exc)
    elif path.lower().endswith(".csv"):
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            out.append((os.path.basename(path), fh.read()))
    return out


def round_half(x):
    return math.ceil(x * 2.0) / 2.0


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    files = collect(args)
    if not files:
        print("No se encontraron CSV ni ZIP en:", args)
        return

    # device -> support -> activity -> datos
    data = defaultdict(lambda: defaultdict(lambda: defaultdict(
        lambda: {"dyn": [], "samples": [], "gyro": [], "speed": [], "hz": [], "files": set()}
    )))
    seen = set()

    for path in files:
        for name, text in iter_csv_from(path):
            meta, rows = parse_stream(text)
            device = meta.get("dispositivo", "desconocido")
            support = (meta.get("soporte", "sin_definir").split(" ")[0] or "sin_definir")
            activity = meta.get("actividad", name).split(" ")[0] or name
            key = (device, support, name)
            if key in seen:
                continue
            seen.add(key)
            samples = dynamic_g_samples(rows)
            dyn = [value for value, _ in samples]
            gyro = [math.sqrt(a * a + b * b + c * c) for (_, a, b, c, _) in rows.get("gyro", [])]
            speed = [c * 3.6 for (_, _, _, c, _) in rows.get("gps", [])]
            entry = data[device][support][activity]
            entry["dyn"].extend(dyn)
            entry["samples"].extend(samples)
            entry["gyro"].extend(gyro)
            entry["speed"].extend(speed)
            times = [t for (t, _, _, _, _) in rows.get("lin", [])] or \
                    [t for (t, _, _, _, _) in rows.get("accel", [])]
            if len(times) > 1 and times[-1] > times[0]:
                entry["hz"].append((len(times) - 1) * 1000.0 / (times[-1] - times[0]))
            entry["files"].add(name)

    report = io.StringIO()

    def out(line=""):
        print(line)
        report.write(line + "\n")

    out("=" * 78)
    out("INFORME DE CALIBRACION SEGU-LUKY")
    out("=" * 78)
    out("Archivos analizados: %d" % len(files))
    out("Combinaciones dispositivo/soporte: %d" % sum(len(v) for v in data.values()))

    bands = [
        ("0-12.5", 0.0, 12.5),
        ("12.5-25", 12.5, 25.0),
        ("25-37.5", 25.0, 37.5),
        ("37.5-50", 37.5, 50.0),
        ("50+", 50.0, float("inf")),
    ]
    global_reco = 0.0
    for device in sorted(data):
        for support in sorted(data[device]):
            out("")
            out("DISPOSITIVO: %s · SOPORTE: %s" % (device, support))
            out("-" * 78)
            device_max = 0.0
            for activity in sorted(data[device][support]):
                e = data[device][support][activity]
                s = stats(e["dyn"])
                if not s:
                    continue
                device_max = max(device_max, s["max"])
                line = ("  %-14s n=%-7d media=%.2fg std=%.2f p99=%.2fg p99.9=%.2fg max=%.2fg  (archivos=%d)"
                        % (activity, s["n"], s["mean"], s["std"], s["p99"], s["p999"], s["max"], len(e["files"])))
                out(line)
                gy = stats(e["gyro"])
                if gy:
                    out("      giro: media=%.2f p99=%.2f max=%.2f rad/s" % (gy["mean"], gy["p99"], gy["max"]))
                sp = stats(e["speed"])
                if sp and sp["max"] > 1:
                    out("      velocidad: media=%.0f p95=%.0f max=%.0f km/h" % (sp["mean"], sp["p95"], sp["max"]))
                hz = stats(e["hz"])
                if hz:
                    out("      muestreo real: media=%.0f minimo=%.0f maximo=%.0f medidas/s"
                        % (hz["mean"], min(e["hz"]), hz["max"]))
                for label, low, high in bands:
                    values = [g for g, speed in e["samples"]
                              if speed is not None and low <= speed < high]
                    bs = stats(values)
                    if bs and bs["n"] >= 50:
                        out("      %-8s km/h n=%-7d p99=%.2fg p99.9=%.2fg max=%.2fg"
                            % (label, bs["n"], bs["p99"], bs["p999"], bs["max"]))
            reco = round_half(max(device_max * 1.20, 1.5))
            global_reco = max(global_reco, reco)
            out("  -> SENSIBILIDAD RECOMENDADA para este soporte: %.1f g" % reco)
            if device_max > 5.0:
                out("     AVISO: se observaron %.1fg en uso normal; revisa el soporte del movil (vibracion)." % device_max)

    out("")
    out("=" * 78)
    out("RECOMENDACION GLOBAL (segura para el peor caso observado): %.1f g" % global_reco)
    out("Configura ese valor en la app: SENSIBILIDAD A LOS GOLPES.")
    out("Nota: cuanto mas alto, menos falsos SOS pero mas facil perder un golpe real.")
    out("=" * 78)

    outdir = args[0] if os.path.isdir(args[0]) else os.path.dirname(os.path.abspath(files[0]))
    try:
        with open(os.path.join(outdir, "recomendaciones.txt"), "w", encoding="utf-8") as fh:
            fh.write(report.getvalue())
        print("\nGuardado:", os.path.join(outdir, "recomendaciones.txt"))
    except Exception as exc:
        print("\nNo se pudo guardar recomendaciones.txt:", exc)


if __name__ == "__main__":
    main()
