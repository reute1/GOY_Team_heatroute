"""Стресс-набор: конкурсный набор, размноженный сеткой K×K со сдвигом (id уникальны, источник один).
python3 make_big.py data/dataset.geojson K out.geojson [шаг_м]"""
import json, math, sys

src, k, dst = sys.argv[1], int(sys.argv[2]), sys.argv[3]
step = float(sys.argv[4]) if len(sys.argv) > 4 else 2200.0
d = json.load(open(src, encoding="utf-8"))
lat0 = 55.7
dlon = step / (111320 * math.cos(math.radians(lat0)))
dlat = step / 110540


def shift(c, ox, oy):
    if isinstance(c[0], (int, float)):
        return [c[0] + ox, c[1] + oy] + list(c[2:])
    return [shift(x, ox, oy) for x in c]


out = []
for i in range(k):
    for j in range(k):
        tile = i * k + j
        for f in d["features"]:
            p = dict(f["properties"])
            if p["object_type"] == "source" and tile > 0:
                continue
            if tile > 0:
                p["id"] = f"{p['id']}_t{tile}"
            g = f["geometry"]
            g = None if g is None else {"type": g["type"], "coordinates": shift(g["coordinates"], j * dlon, i * dlat)}
            out.append({"type": "Feature", "properties": p, "geometry": g})
json.dump({"type": "FeatureCollection", "features": out}, open(dst, "w", encoding="utf-8"), ensure_ascii=False)
print(f"{len(out)} объектов, {k * k} копий")
