"""Собирает автономную HTML-карту результата: python3 build_viewer.py out.html in1.geojson res1.geojson "Название" [in2 res2 "Название2" ...]"""
import json, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from validate import utm
X0, Y0 = utm(37.64, 55.698)
def P(c):
    x, y = utm(*c); return [round(x - X0, 1), round(y - Y0, 1)]
def flat(cs): return sum([P(c) for c in cs], [])
def pack(inp_path, out_path, name):
    inp = json.load(open(inp_path, encoding="utf-8")); out = json.load(open(out_path, encoding="utf-8"))
    D = {"name": name, "polys": [], "clines": [], "net": [], "ch": [], "pts": [], "src": None, "variants": []}
    for f in inp["features"]:
        p = f["properties"]; g = f["geometry"]; t = p["object_type"]
        if t == "restriction":
            rt = p["restriction_type"]
            if g["type"] in ("Polygon", "MultiPolygon"):
                for poly in ([g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]):
                    D["polys"].append([rt, str(p["id"]), [flat(r) for r in poly]])
            else:
                for l in ([g["coordinates"]] if g["type"] == "LineString" else g["coordinates"]):
                    D["clines"].append([rt, str(p["id"]), flat(l)])
        elif t == "heat_network": D["net"].append([str(p["id"]), p["diameter"], flat(g["coordinates"])])
        elif t == "heat_chamber": D["ch"].append([str(p["id"])] + P(g["coordinates"]))
        elif t == "oks_connection_point": D["pts"].append([str(p["id"]), p["flow_tph"]] + P(g["coordinates"]))
        elif t == "source": D["src"] = [p.get("name", "")] + P(g["coordinates"])
    vs = {}
    for f in out["features"]:
        p = f["properties"]; v = vs.setdefault(p["variant_id"], {"lines": [], "ch": [], "tn": [], "s": None}); t = p["object_type"]
        if t == "heat_network":
            d = {k: p[k] for k in ("id", "start_node_id", "end_node_id", "flow_tph", "diameter", "length", "laying_method", "cost", "k_special")}
            d["c"] = flat(f["geometry"]["coordinates"]); v["lines"].append(d)
        elif t == "heat_chamber": v["ch"].append({"id": p["id"], "dn": p["diameter"], "cost": p["cost"], "role": p.get("chamber_role"), "c": P(f["geometry"]["coordinates"])})
        elif t == "technical_node": v["tn"].append({"id": p["id"], "c": P(f["geometry"]["coordinates"])})
        else: v["s"] = p
    D["variants"] = sorted(vs.values(), key=lambda v: v["s"]["rank"])
    return D
args = sys.argv[2:]
data = [pack(args[i], args[i + 1], args[i + 2]) for i in range(0, len(args), 3)]
tpl = open(os.path.join(os.path.dirname(__file__), "viewer_template.html"), encoding="utf-8").read()
open(sys.argv[1], "w", encoding="utf-8").write(tpl.replace("__DATA__", json.dumps(data, ensure_ascii=False, separators=(",", ":"))))
