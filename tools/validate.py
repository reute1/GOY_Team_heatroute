"""
Независимая проверка выходного GeoJSON по правилам технического приложения (без зависимостей).
python3 validate.py input.geojson output.geojson
"""
import json, math, sys
from collections import defaultdict

# ---------------------------------------------------------------- UTM 37N (Крюгер)
A = 6378137.0; F = 1 / 298.257223563; K0 = 0.9996
N = F / (2 - F); AA = A / (1 + N) * (1 + N * N / 4 + N ** 4 / 64)
AL = [N / 2 - 2 * N * N / 3 + 5 * N ** 3 / 16, 13 * N * N / 48 - 3 * N ** 3 / 5, 61 * N ** 3 / 240]
C2N = 2 * math.sqrt(N) / (1 + N); LON0 = math.radians(39)

def utm(lon, lat):
    phi = math.radians(lat); dl = math.radians(lon) - LON0; s = math.sin(phi)
    t = math.sinh(math.atanh(s) - C2N * math.atanh(C2N * s))
    xi = math.atan2(t, math.cos(dl)); eta = math.atanh(math.sin(dl) / math.sqrt(1 + t * t))
    x, y = eta, xi
    for j in range(1, 4):
        x += AL[j - 1] * math.cos(2 * j * xi) * math.sinh(2 * j * eta)
        y += AL[j - 1] * math.sin(2 * j * xi) * math.cosh(2 * j * eta)
    return (500000 + K0 * AA * x, K0 * AA * y)

PIPES = [(50,3.5,181,74023,.4),(65,8.3,245,78631,.43),(80,13.2,327,83530,.47),(100,22.3,419,89748,.51),
         (125,40.2,554,97275,.6),(150,65.1,696,105507,.65),(200,152.3,1042,120275,.88),(250,274.9,1379,135323,1.05),
         (300,437.4,1718,150022,1.15),(400,943.1,2477,190299,1.37),(500,1663.4,3245,224137,1.67),(600,2627.7,4037,264790,1.85),
         (700,3735.1,4775,324298,2.05),(800,5296.8,5644,325996,2.25),(900,7165,6518,327693,2.45),(1000,9391.8,7419,418777,2.65),
         (1200,15012.8,9288,428074,3.1),(1400,22501.9,11276,683417,3.45)]
PIPE = {p[0]: p for p in PIPES}
PROHIB = {"park": 1, "social_area": 1, "prohibited_site": 1, "water": 1, "railway": 1}
SPECIAL = {"road": (1.5, 45, 1.6), "tram_tracks": (1.5, 45, 1.75), "gas_pipeline": (2.0, 0, 1.25), "power_cable": (2.0, 0, 1.15)}

def chamber_cost(dn):
    return 3e6 if dn <= 200 else 5e6 if dn <= 500 else 8e6 if dn <= 1000 else 12e6

def d(a, b): return math.hypot(a[0] - b[0], a[1] - b[1])

def pseg(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]; l = dx * dx + dy * dy
    t = 0 if l == 0 else max(0, min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l))
    return math.hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1])

def inter(a, b, c, e):
    def cr(o, p, q): return (p[0] - o[0]) * (q[1] - o[1]) - (p[1] - o[1]) * (q[0] - o[0])
    d1, d2, d3, d4 = cr(c, e, a), cr(c, e, b), cr(a, b, c), cr(a, b, e)
    return (d1 > 0) != (d2 > 0) and (d3 > 0) != (d4 > 0)

def segseg(a, b, c, e):
    if inter(a, b, c, e): return 0.0
    return min(pseg(a, c, e), pseg(b, c, e), pseg(c, a, b), pseg(e, a, b))

def inring(p, ring):
    x, y = p; c = False
    for i in range(len(ring)):
        x1, y1 = ring[i - 1]; x2, y2 = ring[i]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: c = not c
    return c

def main(inp_path, out_path):
    inp = json.load(open(inp_path, encoding="utf-8")); out = json.load(open(out_path, encoding="utf-8"))
    oks = {}; chambers = {}; net = []; polys = []; lines_c = []
    for f in inp["features"]:
        p = f["properties"]; g = f["geometry"]; t = p["object_type"]
        if t == "oks_connection_point": oks[str(p["id"])] = (utm(*g["coordinates"]), p["flow_tph"])
        elif t == "heat_chamber": chambers[str(p["id"])] = utm(*g["coordinates"])
        elif t == "heat_network": net.append(([utm(*c) for c in g["coordinates"]], p["diameter"]))
        elif t == "restriction":
            rt = p["restriction_type"]
            gs = g["coordinates"]
            if g["type"] == "Polygon": gs = [gs]
            if g["type"] in ("Polygon", "MultiPolygon"):
                for poly in gs:
                    polys.append((str(p["id"]), rt, [[utm(*c) for c in ring] for ring in poly]))
            else:
                ls = [gs] if g["type"] == "LineString" else gs
                for l in ls: lines_c.append((str(p["id"]), rt, [utm(*c) for c in l]))
    variants = defaultdict(lambda: {"lines": [], "ch": {}, "tn": {}, "sum": None})
    for f in out["features"]:
        p = f["properties"]; v = variants[str(p["variant_id"])]; t = p["object_type"]
        if t == "heat_network": v["lines"].append((p, [utm(*c) for c in f["geometry"]["coordinates"]]))
        elif t == "heat_chamber": v["ch"][str(p["id"])] = (p, utm(*f["geometry"]["coordinates"]))
        elif t == "technical_node": v["tn"][str(p["id"])] = utm(*f["geometry"]["coordinates"])
        elif t == "variant_summary": v["sum"] = p
    ok_all = True
    for vid, v in sorted(variants.items()):
        errs = []; S = v["sum"]
        pos = {}
        for k, (pt, _) in oks.items(): pos[k] = pt
        for k, pt in chambers.items(): pos[k] = pt
        for k, (_, pt) in v["ch"].items(): pos[k] = pt
        for k, pt in v["tn"].items(): pos[k] = pt
        adj = defaultdict(list)
        total_len = 0; total_cost = 0
        for p, c in v["lines"]:
            s, e = str(p["start_node_id"]), str(p["end_node_id"])
            for nid, end in ((s, c[0]), (e, c[-1])):
                if nid not in pos: errs.append(f"{p['id']}: узел {nid} не найден")
                elif d(pos[nid], end) > 0.05: errs.append(f"{p['id']}: узел {nid} не совпадает с концом линии ({d(pos[nid], end):.2f} м)")
            L = sum(d(c[i], c[i + 1]) for i in range(len(c) - 1))
            if abs(L - p["length"]) > 0.05: errs.append(f"{p['id']}: длина {p['length']} ≠ {L:.3f}")
            k = p.get("k_special", 1) if p["laying_method"] == "special" else 1
            if abs(p["cost"] - p["length"] * PIPE[p["diameter"]][3] * k) > 0.05: errs.append(f"{p['id']}: стоимость не совпадает")
            if PIPE[p["diameter"]][1] < p["flow_tph"] - 1e-6: errs.append(f"{p['id']}: расход {p['flow_tph']} > пропускной способности ДУ{p['diameter']}")
            if p["laying_method"] == "special" and len(c) != 2: errs.append(f"{p['id']}: спецпроход не один прямой участок")
            for i in range(1, len(c) - 1):
                a, b, q = c[i - 1], c[i], c[i + 1]
                ux, uy, vx, vy = b[0] - a[0], b[1] - a[1], q[0] - b[0], q[1] - b[1]
                ang = math.degrees(math.acos(max(-1, min(1, (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))))))
                if ang > 90.001: errs.append(f"{p['id']}: поворот {ang:.1f}°")
            adj[s].append((e, p, c)); adj[e].append((s, p, c))
            total_len += L; total_cost += p["cost"]
        # пересечения новых линий между собой
        segs = []
        for p, c in v["lines"]:
            for i in range(len(c) - 1): segs.append((p["id"], c[i], c[i + 1]))
        for i in range(len(segs)):
            for j in range(i + 1, len(segs)):
                a = segs[i]; b = segs[j]
                if a[0] == b[0]: continue
                if inter(a[1], a[2], b[1], b[2]):
                    shared = min(d(x, y) for x in (a[1], a[2]) for y in (b[1], b[2]))
                    if shared > 0.01: errs.append(f"пересечение {a[0]} × {b[0]}")
        # дерево: корни — камеры на существующей сети
        def on_net(pt):
            return any(pseg(pt, l[i], l[i + 1]) < 0.05 for l, _ in net for i in range(len(l) - 1))
        roots = [n for n in adj if (n in chambers) or (n in v["ch"] and on_net(v["ch"][n][1]))]
        seen = set(); flows_ok = True
        for r in roots:
            if r in seen: continue
            stack = [(r, None)]; order = []; parent = {}
            while stack:
                n, par = stack.pop()
                if n in seen:
                    if par is not None: errs.append(f"цикл через узел {n}")
                    continue
                seen.add(n); order.append(n); parent[n] = par
                for m, p, c in adj[n]:
                    if m != par: stack.append((m, n))
            down = {}
            for n in reversed(order):
                fl = oks[n][1] if n in oks else 0
                for m, p, c in adj[n]:
                    if parent.get(m) == n: fl += down[m][0]
                down[n] = (fl,)
            for n in order:
                if parent[n] is None: continue
                edge = [x for x in adj[n] if x[0] == parent[n]][0]
                if abs(edge[1]["flow_tph"] - down[n][0]) > 0.01:
                    errs.append(f"{edge[1]['id']}: расход {edge[1]['flow_tph']} ≠ {down[n][0]:.3f}"); flows_ok = False
                # ДУ не уменьшается к корню
                if parent[parent[n]] is not None:
                    up = [x for x in adj[parent[n]] if x[0] == parent[parent[n]]][0]
                    if up[1]["diameter"] < edge[1]["diameter"]: errs.append(f"{up[1]['id']}: ДУ уменьшается к месту присоединения")
            # предельная длина: от каждой точки ОКС к корню
            for n in order:
                if n not in oks: continue
                run = 0; dn = None; cur = n
                while parent[cur] is not None:
                    e = [x for x in adj[cur] if x[0] == parent[cur]][0]
                    if e[1]["diameter"] != dn: run = 0; dn = e[1]["diameter"]
                    run += e[1]["length"]
                    if run > PIPE[dn][2] + 1e-6: errs.append(f"точка {n}: превышена предельная длина ДУ{dn}"); break
                    cur = parent[cur]
        for n in adj:
            if n not in seen: errs.append(f"узел {n} не связан с существующей сетью")
            deg = len(adj[n])
            if n in oks and deg != 1: errs.append(f"точка {n}: степень {deg}")
            if n in v["tn"] and deg != 2: errs.append(f"тех. узел {n}: степень {deg}")
            if deg >= 3 and not (n in v["ch"] or n in chambers): errs.append(f"разветвление вне камеры в {n}")
            extra = 0
            if n in v["ch"] and on_net(v["ch"][n][1]): extra = 2
            if n in chambers: extra = sum(1 for l, _ in net if d(l[0], chambers[n]) < .5 or d(l[-1], chambers[n]) < .5) + \
                2 * sum(1 for l, _ in net if d(l[0], chambers[n]) >= .5 and d(l[-1], chambers[n]) >= .5 and any(pseg(chambers[n], l[i], l[i+1]) < .5 for i in range(len(l)-1)))
            if (n in v["ch"] or n in chambers) and deg + extra > 4: errs.append(f"камера {n}: примыканий {deg + extra}")
        # камеры
        ch_cost = 0
        for k, (p, pt) in v["ch"].items():
            dns = [x[1]["diameter"] for x in adj[k]] + ([dn for l, dn in net if any(pseg(pt, l[i], l[i+1]) < .05 for i in range(len(l)-1))])
            if p["diameter"] != max(dns): errs.append(f"камера {k}: ДУ {p['diameter']} ≠ {max(dns)}")
            if p["cost"] != chamber_cost(p["diameter"]): errs.append(f"камера {k}: стоимость")
            ch_cost += p["cost"]
        # отступы от запрещённых объектов
        for p, c in v["lines"]:
            hw = PIPE[p["diameter"]][4] / 2
            own_oks = set()
            for nid in (str(p["start_node_id"]), str(p["end_node_id"])):
                if nid in oks:
                    own_oks |= {pid for pid, rt, rings in polys if rt == "oks" and inring(oks[nid][0], rings[0])}
            for pid, rt, rings in polys:
                if rt == "oks":
                    if pid in own_oks: continue
                    need = (5 if p["diameter"] < 500 else 7 if p["diameter"] < 900 else 9) + hw
                elif rt in PROHIB: need = PROHIB[rt] + hw
                else: continue
                for ring in rings:
                    for i in range(len(c) - 1):
                        for j in range(len(ring) - 1):
                            dd = segseg(c[i], c[i + 1], ring[j], ring[j + 1])
                            if dd < need - 0.01:
                                errs.append(f"{p['id']}: отступ до {rt} {pid} {dd:.2f} < {need:.2f}"); break
            # пересечения спецобъектов вне спецпроходов
            if p["laying_method"] == "base":
                for pid, rt, l in lines_c:
                    if rt in SPECIAL and any(inter(c[i], c[i + 1], l[j], l[j + 1]) for i in range(len(c) - 1) for j in range(len(l) - 1)):
                        errs.append(f"{p['id']}: пересечение {rt} вне спецпрохода")
        # сводка
        pen = sum(1e8 + 5e5 * oks[str(u)][1] for u in S["unconnected_oks_ids"])
        tie_cnt = sum(len(adj[k]) for k in chambers if k in adj)
        cc = total_cost + ch_cost + tie_cnt * 5e6
        if abs(cc - S["construction_cost"]) > 5: errs.append(f"construction_cost {S['construction_cost']} ≠ {cc:.2f}")
        if tie_cnt != S["existing_chamber_tie_in_count"]: errs.append("число врезок в сущ. камеры")
        if abs(total_len - S["new_network_length"]) > 0.1: errs.append("new_network_length")
        sc = 0.7 * (cc + pen) / 25e6 + 0.3 * total_len / 100
        if abs(sc - S["score"]) > 1e-3: errs.append(f"score {S['score']} ≠ {sc:.4f}")
        connected = {n for n in adj if n in oks}
        missing = set(oks) - connected - {str(u) for u in S["unconnected_oks_ids"]}
        if missing: errs.append(f"точки не подключены и не указаны в unconnected: {missing}")
        status = "OK" if not errs else f"{len(errs)} замечаний"
        print(f"{vid}: rank={S['rank']} S={S['score']:.4f} линий={len(v['lines'])} камер={len(v['ch'])} тех.узлов={len(v['tn'])} — {status}")
        for e in errs[:40]: print("   -", e)
        ok_all &= not errs
    return 0 if ok_all else 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
