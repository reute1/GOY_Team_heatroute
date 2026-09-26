"""Быстрая отрисовка входа и результата в PNG (для отладки): python3 plot.py in.geojson out.geojson variant png [cx cy r]"""
import json, sys, math
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

inp = json.load(open(sys.argv[1], encoding="utf-8"))
out = json.load(open(sys.argv[2], encoding="utf-8"))
variant = sys.argv[3]
png = sys.argv[4]
lat0 = 55.698
def P(c):
    return ((c[0] - 37.64) * 111320 * math.cos(math.radians(lat0)), (c[1] - lat0) * 110574)

fig, ax = plt.subplots(figsize=(16, 13))
colors = {"oks": "#c9c9c9", "water": "#9ecae1", "railway": "#bcbddc", "road": "#fdd49e", "park": "#a1d99b"}
for f in inp["features"]:
    p = f["properties"]; g = f["geometry"]; t = p["object_type"]
    if t == "restriction":
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]] if g["type"] == "Polygon" else []
        for poly in polys:
            for i, ring in enumerate(poly):
                xs, ys = zip(*[P(c) for c in ring])
                ax.fill(xs, ys, color=colors.get(p["restriction_type"], "#fcbba1") if i == 0 else "white",
                        ec="#888", lw=0.4, zorder=1 if i == 0 else 2)
        if g["type"] in ("LineString", "MultiLineString"):
            lines = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]
            for l in lines:
                xs, ys = zip(*[P(c) for c in l])
                ax.plot(xs, ys, color="#e6550d" if "gas" in p["restriction_type"] else "#756bb1", lw=1.2, ls="--", zorder=3)
    elif t == "heat_network":
        xs, ys = zip(*[P(c) for c in g["coordinates"]])
        ax.plot(xs, ys, color="#d62728", lw=2.5, zorder=4)
    elif t == "heat_chamber":
        x, y = P(g["coordinates"]); ax.plot(x, y, "s", color="#d62728", ms=6, zorder=5)
    elif t == "source":
        x, y = P(g["coordinates"]); ax.plot(x, y, "*", color="k", ms=16, zorder=6)
    elif t == "oks_connection_point":
        x, y = P(g["coordinates"]); ax.plot(x, y, "o", color="#2ca02c", ms=7, zorder=7)
        ax.annotate(str(p["id"]), (x, y), xytext=(4, 4), textcoords="offset points", fontsize=9, color="#2ca02c", zorder=8)

for f in out["features"]:
    p = f["properties"]
    if p.get("variant_id") != variant:
        continue
    g = f["geometry"]; t = p["object_type"]
    if t == "heat_network":
        xs, ys = zip(*[P(c) for c in g["coordinates"]])
        lw = 1 + p["diameter"] / 60
        ax.plot(xs, ys, color="#e377c2" if p["laying_method"] == "special" else "#1f77b4", lw=lw, zorder=9, solid_capstyle="round")
    elif t == "heat_chamber":
        x, y = P(g["coordinates"]); ax.plot(x, y, "D", color="#ff7f0e", mec="k", ms=7, zorder=10)
    elif t == "technical_node":
        x, y = P(g["coordinates"]); ax.plot(x, y, "o", color="white", mec="k", ms=4, zorder=10)
    elif t == "variant_summary":
        ax.set_title(f"{variant}: S={p['score']:.3f}  cost={p['calculated_cost']/1e6:.1f} млн  L={p['new_network_length']:.0f} м  "
                     f"неподкл.={p['unconnected_oks_ids']}")
ax.set_aspect("equal")
if len(sys.argv) > 7:
    cx, cy, r = map(float, sys.argv[5:8])
    ax.set_xlim(cx - r, cx + r); ax.set_ylim(cy - r, cy + r)
plt.tight_layout()
plt.savefig(png, dpi=110)
