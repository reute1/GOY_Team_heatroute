package ru.lct.heatroute.core.route;

import ru.lct.heatroute.core.geo.Geo;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Пространственная модель ограничений для расчётного ДУ:
 * запрещённые объекты (обход с отступом), пересекаемые объекты (спецпроход) и новая сеть.
 * Главная операция — {@link #eval}: проверка прямого отрезка трассы по правилам таблицы 2.
 */
final class Env {
    static final double NODE_MARGIN = 0.12;
    static final double NEW_NET_SEPARATION = 0.3;

    final InputData data;
    final RuleBook rules;
    final int designDn;
    final double hw;

    // --- запрещённые объекты
    final SegGrid pGrid = new SegGrid(50);
    double[] pClear = new double[256];
    double maxPClear;
    /** Полигоны запрещённых объектов: id → кольца. */
    final List<double[][]> pPolys = new ArrayList<>();
    final List<double[]> pPolyBox = new ArrayList<>();
    final List<InputData.Restriction> pPolyOwner = new ArrayList<>();
    private final Map<Long, int[]> polyCells = new HashMap<>();
    private static final double POLY_CELL = 100;

    // --- пересекаемые объекты (спецпроход)
    static final class CFeat {
        RuleBook.Restriction rule;
        boolean poly;
        double[][] rings;
        double effClear;
        int netLine = -1;
        Object srcId;
    }

    final List<CFeat> cFeats = new ArrayList<>();
    final SegGrid cGrid = new SegGrid(50);
    double maxCClear;

    // --- новая сеть (растёт во время расчёта)
    SegGrid nGrid = new SegGrid(50);
    java.util.BitSet nDead = new java.util.BitSet();

    void resetNew() {
        nGrid = new SegGrid(50);
        nDead = new java.util.BitSet();
    }

    /** Отрезок не пересекает новую сеть и не подходит к ней ближе допустимого (кроме исключений). */
    boolean newNetClear(double ax, double ay, double bx, double by, IntPredicate newExempt, SegGrid.Ctx ctx) {
        if (nGrid.size == 0) {
            return true;
        }
        return nGrid.query(ax, ay, bx, by, NEW_NET_SEPARATION, ctx, id -> {
            if (nDead.get(id) || (newExempt != null && newExempt.test(nGrid.owner[id]))) {
                return true;
            }
            return Geo.segSegDist(ax, ay, bx, by, nGrid.ax[id], nGrid.ay[id], nGrid.bx[id], nGrid.by[id])
                    >= NEW_NET_SEPARATION;
        });
    }

    /** Сетка существующей сети для поиска мест присоединения. */
    final SegGrid netGrid = new SegGrid(50);

    Env(InputData data, RuleBook rules, int designDn) {
        this.data = data;
        this.rules = rules;
        this.designDn = designDn;
        RuleBook.Pipe p = rules.pipeByDn(designDn);
        this.hw = (p != null ? p.width : rules.pipes.get(0).width) / 2;
        build();
    }

    private void build() {
        for (InputData.Restriction r : data.restrictions) {
            RuleBook.Restriction rule = r.rule;
            if (rule.kind == RuleBook.Kind.IGNORE) {
                continue;
            }
            if (rule.kind == RuleBook.Kind.PROHIBITED) {
                double c = rule.clearance(designDn) + hw;
                for (double[][] poly : r.shape.polygons) {
                    int pid = pPolys.size();
                    double[][] rings = orient(poly);
                    pPolys.add(rings);
                    pPolyOwner.add(r);
                    double[] box = bbox(rings[0]);
                    pPolyBox.add(box);
                    indexPolyBox(pid, box);
                    for (double[] ring : rings) {
                        addRingSegs(pGrid, ring, true, pid, c);
                    }
                }
                for (double[] line : r.shape.lines) {
                    // линия — владелец -1 (не исключается как «собственный полигон»)
                    addRingSegs(pGrid, line, false, -1, c);
                }
                maxPClear = Math.max(maxPClear, c);
            } else {
                double own = rule.ownWidth > 0 ? rule.ownWidth / 2 : 0;
                double eff = rule.clearance(designDn) + hw + own;
                for (double[][] poly : r.shape.polygons) {
                    CFeat f = new CFeat();
                    f.rule = rule;
                    f.poly = true;
                    f.rings = orient(poly);
                    f.effClear = eff;
                    f.srcId = r.id;
                    int id = cFeats.size();
                    cFeats.add(f);
                    for (double[] ring : f.rings) {
                        addRingSegs(cGrid, ring, true, id, 0);
                    }
                }
                for (double[] line : r.shape.lines) {
                    CFeat f = new CFeat();
                    f.rule = rule;
                    f.poly = false;
                    f.rings = new double[][]{line};
                    f.effClear = eff;
                    f.srcId = r.id;
                    int id = cFeats.size();
                    cFeats.add(f);
                    addRingSegs(cGrid, line, false, id, 0);
                }
                maxCClear = Math.max(maxCClear, eff);
            }
        }
        // существующая сеть — пересечение без врезки = спецпроход
        for (int i = 0; i < data.network.size(); i++) {
            InputData.NetLine nl = data.network.get(i);
            CFeat f = new CFeat();
            f.rule = rules.existingNetwork;
            f.poly = false;
            f.rings = new double[][]{nl.coords};
            f.effClear = rules.existingNetwork.clearance + hw + rules.widthForDn(nl.dn) / 2;
            f.netLine = i;
            f.srcId = nl.id;
            int id = cFeats.size();
            cFeats.add(f);
            addRingSegs(cGrid, nl.coords, false, id, 0);
            addRingSegs(netGrid, nl.coords, false, i, 0);
            maxCClear = Math.max(maxCClear, f.effClear);
        }
    }

    /** Внешнее кольцо — против часовой, дыры — по часовой: материал объекта всегда слева. */
    private static double[][] orient(double[][] poly) {
        double[][] r = new double[poly.length][];
        for (int i = 0; i < poly.length; i++) {
            double a = Geo.signedArea2(poly[i]);
            boolean ccw = a > 0;
            boolean want = i == 0;
            r[i] = ccw == want ? poly[i] : Geo.reverseRing(poly[i]);
        }
        return r;
    }

    private static double[] bbox(double[] ring) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < ring.length; i += 2) {
            b[0] = Math.min(b[0], ring[i]);
            b[1] = Math.min(b[1], ring[i + 1]);
            b[2] = Math.max(b[2], ring[i]);
            b[3] = Math.max(b[3], ring[i + 1]);
        }
        return b;
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    private void indexPolyBox(int pid, double[] b) {
        for (int cx = (int) Math.floor(b[0] / POLY_CELL); cx <= (int) Math.floor(b[2] / POLY_CELL); cx++) {
            for (int cy = (int) Math.floor(b[1] / POLY_CELL); cy <= (int) Math.floor(b[3] / POLY_CELL); cy++) {
                long k = key(cx, cy);
                int[] a = polyCells.get(k);
                if (a == null) {
                    polyCells.put(k, new int[]{pid});
                } else {
                    int[] n = Arrays.copyOf(a, a.length + 1);
                    n[a.length] = pid;
                    polyCells.put(k, n);
                }
            }
        }
    }

    private void addRingSegs(SegGrid g, double[] c, boolean closed, int owner, double clear) {
        int n = c.length / 2;
        int m = closed ? n : n - 1;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % n;
            int id = g.add(c[2 * i], c[2 * i + 1], c[2 * j], c[2 * j + 1], owner);
            if (g == pGrid) {
                if (id >= pClear.length) {
                    pClear = Arrays.copyOf(pClear, pClear.length * 2);
                }
                pClear[id] = clear;
            }
        }
    }

    /** Полигон pid принадлежит исключённому объекту ОКС (индекс ограничения exemptR). */
    boolean exempt(int pid, int exemptR) {
        return exemptR >= 0 && pid >= 0 && pPolyOwner.get(pid).index == exemptR;
    }

    /** Индекс запрещённого полигона, содержащего точку, или -1. */
    int polygonAt(double x, double y) {
        int[] a = polyCells.get(key((int) Math.floor(x / POLY_CELL), (int) Math.floor(y / POLY_CELL)));
        if (a == null) {
            return -1;
        }
        for (int pid : a) {
            double[] b = pPolyBox.get(pid);
            if (x < b[0] || x > b[2] || y < b[1] || y > b[3]) {
                continue;
            }
            double[][] rings = pPolys.get(pid);
            if (Geo.pointInRing(x, y, rings[0])) {
                boolean inHole = false;
                for (int h = 1; h < rings.length; h++) {
                    if (Geo.pointInRing(x, y, rings[h])) {
                        inHole = true;
                        break;
                    }
                }
                if (!inHole) {
                    return pid;
                }
            }
        }
        return -1;
    }

    /** Ближайший запрещённый полигон «типа oks» в радиусе r от точки (для точки подключения на границе). */
    int nearestOksPolygon(double x, double y, double r) {
        int best = -1;
        double bd = r;
        for (int pid = 0; pid < pPolys.size(); pid++) {
            if (!"oks".equals(pPolyOwner.get(pid).type)) {
                continue;
            }
            double[] b = pPolyBox.get(pid);
            if (x < b[0] - r || x > b[2] + r || y < b[1] - r || y > b[3] + r) {
                continue;
            }
            for (double[] ring : pPolys.get(pid)) {
                int n = ring.length / 2;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    double d = Geo.pointSegDist(x, y, ring[2 * i], ring[2 * i + 1], ring[2 * j], ring[2 * j + 1]);
                    if (d < bd) {
                        bd = d;
                        best = pid;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Точка свободна: вне запрещённых полигонов и их отступов, вне зон отступа пересекаемых объектов.
     */
    boolean pointFree(double x, double y, double slack, int exemptPoly, Set<Integer> exemptC, SegGrid.Ctx ctx) {
        int pid = polygonAt(x, y);
        if (pid >= 0 && !exempt(pid, exemptPoly)) {
            return false;
        }
        boolean ok = pGrid.query(x, y, x, y, maxPClear + slack, ctx, id -> {
            if (exempt(pGrid.owner[id], exemptPoly)) {
                return true;
            }
            return Geo.pointSegDist(x, y, pGrid.ax[id], pGrid.ay[id], pGrid.bx[id], pGrid.by[id]) >= pClear[id] + slack;
        });
        if (!ok) {
            return false;
        }
        return cGrid.query(x, y, x, y, maxCClear + slack, ctx, id -> {
            int f = cGrid.owner[id];
            if (exemptC != null && exemptC.contains(f)) {
                return true;
            }
            CFeat cf = cFeats.get(f);
            if (cf.poly && Geo.pointInRing(x, y, cf.rings[0])) {
                return false;
            }
            return Geo.pointSegDist(x, y, cGrid.ax[id], cGrid.ay[id], cGrid.bx[id], cGrid.by[id]) >= cf.effClear + slack;
        });
    }

    /** Пересекаемые объекты (id), касающиеся точки (для исключений в месте врезки). */
    Set<Integer> crossablesTouching(double x, double y, double tol, SegGrid.Ctx ctx) {
        Set<Integer> s = new HashSet<>();
        cGrid.query(x, y, x, y, tol, ctx, id -> {
            if (Geo.pointSegDist(x, y, cGrid.ax[id], cGrid.ay[id], cGrid.bx[id], cGrid.by[id]) <= tol) {
                s.add(cGrid.owner[id]);
            }
            return true;
        });
        return s;
    }

    // ------------------------------------------------------------------ eval

    /**
     * Проверка прямого отрезка A→B.
     *
     * @param exemptPoly  индекс ограничения ОКС, к которому идёт финальный участок (-1 — нет)
     * @param exemptC     пересекаемые объекты, исключённые из проверки (сеть в месте врезки)
     * @param newExempt   предикат исключения отрезков новой сети (место присоединения к ветви)
     * @param checkNew    проверять ли пересечения с новой сетью
     * @return описание отрезка или null, если отрезок недопустим
     */
    Edge eval(double ax, double ay, double bx, double by, int exemptPoly, Set<Integer> exemptC,
              IntPredicate newExempt, boolean checkNew, SegGrid.Ctx ctx) {
        double len = Geo.dist(ax, ay, bx, by);
        if (len < 1e-6) {
            return new Edge(0, 0, Edge.NONE);
        }
        // 1. запрещённые объекты
        boolean ok = pGrid.query(ax, ay, bx, by, maxPClear, ctx, id -> {
            if (exempt(pGrid.owner[id], exemptPoly)) {
                return true;
            }
            return Geo.segSegDist(ax, ay, bx, by, pGrid.ax[id], pGrid.ay[id], pGrid.bx[id], pGrid.by[id])
                    >= pClear[id] - 1e-6;
        });
        if (!ok) {
            return null;
        }
        // 2. новая сеть
        if (checkNew && !newNetClear(ax, ay, bx, by, newExempt, ctx)) {
            return null;
        }
        // 3. пересекаемые объекты: собрать отрезки по объектам
        Map<Integer, List<Integer>> byFeat = new HashMap<>();
        cGrid.query(ax, ay, bx, by, maxCClear, ctx, id -> {
            int f = cGrid.owner[id];
            if (exemptC != null && exemptC.contains(f)) {
                return true;
            }
            byFeat.computeIfAbsent(f, k -> new ArrayList<>()).add(id);
            return true;
        });
        if (byFeat.isEmpty()) {
            return new Edge(len, len, Edge.NONE);
        }
        double ux = (bx - ax) / len;
        double uy = (by - ay) / len;
        List<double[]> intervals = new ArrayList<>(); // s0, s1, K
        for (Map.Entry<Integer, List<Integer>> e : byFeat.entrySet()) {
            CFeat f = cFeats.get(e.getKey());
            RuleBook.Restriction rule = f.rule;
            double m = rule.margin;
            // пересечения
            List<double[]> hits = new ArrayList<>(); // s, angleDeg
            for (int id : e.getValue()) {
                double t = Geo.segIntersectT(ax, ay, bx, by, cGrid.ax[id], cGrid.ay[id], cGrid.bx[id], cGrid.by[id]);
                if (!Double.isNaN(t)) {
                    double ang = Geo.lineAngleDeg(ux, uy, cGrid.bx[id] - cGrid.ax[id], cGrid.by[id] - cGrid.ay[id]);
                    hits.add(new double[]{t * len, ang});
                }
            }
            hits.sort((p, q) -> Double.compare(p[0], q[0]));
            // слить совпадающие (пересечение через вершину)
            List<double[]> uh = new ArrayList<>();
            for (double[] h : hits) {
                if (!uh.isEmpty() && h[0] - uh.get(uh.size() - 1)[0] < 1e-4) {
                    double[] last = uh.get(uh.size() - 1);
                    last[1] = Math.min(last[1], h[1]);
                } else {
                    uh.add(h);
                }
            }
            List<double[]> zones = new ArrayList<>(); // зоны, где отступ не проверяется
            if (f.poly) {
                // интервалы внутри полигона
                boolean inside = Geo.pointInRing(ax, ay, f.rings[0]);
                double start = inside ? 0 : Double.NaN;
                double startAng = 90;
                List<double[]> ins = new ArrayList<>();
                for (double[] h : uh) {
                    if (Double.isNaN(start)) {
                        start = h[0];
                        startAng = h[1];
                    } else {
                        ins.add(new double[]{start, h[0], Math.min(startAng, h[1])});
                        start = Double.NaN;
                    }
                }
                if (!Double.isNaN(start)) {
                    ins.add(new double[]{start, len, startAng});
                }
                for (double[] in : ins) {
                    if (in[1] - in[0] < 1e-6) {
                        continue; // касание
                    }
                    if (rule.minAngleDeg > 0 && in[2] < rule.minAngleDeg - 1e-6) {
                        return null;
                    }
                    double s0 = in[0] - m;
                    double s1 = in[1] + m;
                    if (s0 < -1e-6 || s1 > len + 1e-6) {
                        return null; // спецпроход должен целиком лежать на одном прямом участке
                    }
                    double sin = Math.sin(Math.toRadians(Math.max(in[2], 1)));
                    double ext = Math.max(m, f.effClear / sin);
                    if (ext > m + 2.0) {
                        return null; // слишком острый угол — трасса идёт вдоль объекта
                    }
                    intervals.add(new double[]{Math.max(0, s0), Math.min(len, s1), rule.kSpecial});
                    zones.add(new double[]{in[0] - ext, in[1] + ext});
                }
            } else {
                for (double[] h : uh) {
                    if (rule.minAngleDeg > 0 && h[1] < rule.minAngleDeg - 1e-6) {
                        return null;
                    }
                    double s0 = h[0] - m;
                    double s1 = h[0] + m;
                    if (s0 < -1e-6 || s1 > len + 1e-6) {
                        return null;
                    }
                    double sin = Math.sin(Math.toRadians(Math.max(h[1], 1)));
                    double ext = Math.max(m, f.effClear / sin);
                    if (ext > m + 2.0) {
                        return null;
                    }
                    intervals.add(new double[]{Math.max(0, s0), Math.min(len, s1), rule.kSpecial});
                    zones.add(new double[]{h[0] - ext, h[0] + ext});
                }
            }
            // отступ вне зон пересечения
            List<double[]> free = complement(zones, len);
            for (double[] fr : free) {
                double px = ax + ux * fr[0];
                double py = ay + uy * fr[0];
                double qx = ax + ux * fr[1];
                double qy = ay + uy * fr[1];
                for (int id : e.getValue()) {
                    double d = Geo.segSegDist(px, py, qx, qy, cGrid.ax[id], cGrid.ay[id], cGrid.bx[id], cGrid.by[id]);
                    if (d < f.effClear - 1e-6) {
                        return null;
                    }
                }
                if (f.poly && fr[1] - fr[0] > 1e-6) {
                    double mx = ax + ux * (fr[0] + fr[1]) / 2;
                    double my = ay + uy * (fr[0] + fr[1]) / 2;
                    if (Geo.pointInRing(mx, my, f.rings[0])) {
                        return null;
                    }
                }
            }
        }
        if (intervals.isEmpty()) {
            return new Edge(len, len, Edge.NONE);
        }
        double[] pieces = mergePieces(intervals);
        double lenK = len;
        for (int i = 0; i < pieces.length; i += 3) {
            lenK += (pieces[i + 1] - pieces[i]) * (pieces[i + 2] - 1);
        }
        return new Edge(len, lenK, pieces);
    }

    private static List<double[]> complement(List<double[]> zones, double len) {
        List<double[]> res = new ArrayList<>();
        zones.sort((p, q) -> Double.compare(p[0], q[0]));
        double cur = 0;
        for (double[] z : zones) {
            if (z[0] > cur) {
                res.add(new double[]{cur, Math.min(z[0], len)});
            }
            cur = Math.max(cur, z[1]);
            if (cur >= len) {
                break;
            }
        }
        if (cur < len) {
            res.add(new double[]{cur, len});
        }
        return res;
    }

    /** Разбиение объединения интервалов на куски с максимальным K на каждом. */
    private static double[] mergePieces(List<double[]> iv) {
        List<Double> bp = new ArrayList<>();
        for (double[] i : iv) {
            bp.add(i[0]);
            bp.add(i[1]);
        }
        bp.sort(Double::compare);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < bp.size(); i++) {
            double a = bp.get(i);
            double b = bp.get(i + 1);
            if (b - a < 1e-6) {
                continue;
            }
            double mid = (a + b) / 2;
            double k = 0;
            for (double[] x : iv) {
                if (mid > x[0] && mid < x[1]) {
                    k = Math.max(k, x[2]);
                }
            }
            if (k == 0) {
                continue;
            }
            if (!out.isEmpty()) {
                double[] last = out.get(out.size() - 1);
                if (Math.abs(last[1] - a) < 1e-6 && last[2] == k) {
                    last[1] = b;
                    continue;
                }
            }
            out.add(new double[]{a, b, k});
        }
        double[] r = new double[out.size() * 3];
        for (int i = 0; i < out.size(); i++) {
            r[3 * i] = out.get(i)[0];
            r[3 * i + 1] = out.get(i)[1];
            r[3 * i + 2] = out.get(i)[2];
        }
        return r;
    }

    // ------------------------------------------------------------------ nodes

    /** Кандидатные вершины графа видимости вокруг выпуклых углов препятствий. */
    List<double[]> generateNodes(SegGrid.Ctx ctx) {
        List<double[]> raw = new ArrayList<>();
        for (int pid = 0; pid < pPolys.size(); pid++) {
            double c = pPolyOwner.get(pid).rule.clearance(designDn) + hw;
            for (double[] ring : pPolys.get(pid)) {
                cornerNodes(simplify(ring, true, SIMPLIFY_TOL), true, c + NODE_MARGIN + SIMPLIFY_TOL, raw);
            }
        }
        for (InputData.Restriction r : data.restrictions) {
            if (r.rule.kind == RuleBook.Kind.PROHIBITED) {
                double c = r.rule.clearance(designDn) + hw;
                for (double[] line : r.shape.lines) {
                    cornerNodes(doubled(simplify(line, false, SIMPLIFY_TOL)), true, c + NODE_MARGIN + SIMPLIFY_TOL, raw);
                }
            }
        }
        for (CFeat f : cFeats) {
            if (f.poly) {
                for (double[] ring : f.rings) {
                    cornerNodes(simplify(ring, true, SIMPLIFY_TOL), true, f.effClear + NODE_MARGIN + SIMPLIFY_TOL, raw);
                }
            } else {
                cornerNodes(doubled(simplify(f.rings[0], false, SIMPLIFY_TOL)), true,
                        f.effClear + NODE_MARGIN + SIMPLIFY_TOL, raw);
            }
        }
        // фильтр: свободные точки, без дублей ближе 0.5 м
        Map<Long, List<double[]>> hash = new HashMap<>();
        List<double[]> res = new ArrayList<>();
        for (double[] p : raw) {
            if (!pointFree(p[0], p[1], 0.05, -1, null, ctx)) {
                continue;
            }
            long k = key((int) Math.floor(p[0] / 0.5), (int) Math.floor(p[1] / 0.5));
            boolean dup = false;
            outer:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<double[]> l = hash.get(key((int) Math.floor(p[0] / 0.5) + dx, (int) Math.floor(p[1] / 0.5) + dy));
                    if (l != null) {
                        for (double[] q : l) {
                            if (Geo.dist(p, q) < 0.5) {
                                dup = true;
                                break outer;
                            }
                        }
                    }
                }
            }
            if (dup) {
                continue;
            }
            hash.computeIfAbsent(k, kk -> new ArrayList<>()).add(p);
            res.add(p);
        }
        return res;
    }

    static final double SIMPLIFY_TOL = 0.5;

    /**
     * Упрощение Дугласа–Пекера (только для генерации узлов графа: смещение узлов увеличивается
     * на допуск, поэтому узлы остаются вне истинной зоны отступа).
     */
    static double[] simplify(double[] c, boolean closed, double tol) {
        int n = c.length / 2;
        if (n <= 4) {
            return c;
        }
        boolean[] keep = new boolean[n];
        int split = n - 1;
        if (closed) {
            // замкнутое кольцо: делим на две части по самой удалённой от первой вершины точке
            double best = -1;
            for (int i = 1; i < n; i++) {
                double d = Geo.dist(c[0], c[1], c[2 * i], c[2 * i + 1]);
                if (d > best) {
                    best = d;
                    split = i;
                }
            }
            keep[0] = true;
            keep[split] = true;
            dp(c, 0, split, tol, keep);
            dpWrap(c, split, n, tol, keep);
        } else {
            keep[0] = true;
            keep[n - 1] = true;
            dp(c, 0, n - 1, tol, keep);
        }
        int k = 0;
        for (boolean b : keep) {
            if (b) {
                k++;
            }
        }
        if (closed && k < 3) {
            return c;
        }
        double[] r = new double[k * 2];
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                r[j++] = c[2 * i];
                r[j++] = c[2 * i + 1];
            }
        }
        return r;
    }

    private static void dp(double[] c, int a, int b, double tol, boolean[] keep) {
        if (b - a < 2) {
            return;
        }
        double best = -1;
        int idx = -1;
        for (int i = a + 1; i < b; i++) {
            double d = Geo.pointSegDist(c[2 * i], c[2 * i + 1], c[2 * a], c[2 * a + 1], c[2 * b], c[2 * b + 1]);
            if (d > best) {
                best = d;
                idx = i;
            }
        }
        if (best > tol) {
            keep[idx] = true;
            dp(c, a, idx, tol, keep);
            dp(c, idx, b, tol, keep);
        }
    }

    /** DP для хвоста кольца split..n-1,0 (индексы по модулю n). */
    private static void dpWrap(double[] c, int split, int n, double tol, boolean[] keep) {
        int m = n - split + 1;
        double[] t = new double[m * 2];
        for (int i = 0; i < m; i++) {
            int src = (split + i) % n;
            t[2 * i] = c[2 * src];
            t[2 * i + 1] = c[2 * src + 1];
        }
        boolean[] k2 = new boolean[m];
        dp(t, 0, m - 1, tol, k2);
        for (int i = 1; i < m - 1; i++) {
            if (k2[i]) {
                keep[(split + i) % n] = true;
            }
        }
    }

    /** Линия как «кольцо» туда-обратно: обе стороны и торцы получают узлы. */
    private static double[] doubled(double[] line) {
        int n = line.length / 2;
        if (n < 2) {
            return line;
        }
        double[] r = new double[(2 * n - 2) * 2];
        int k = 0;
        for (int i = 0; i < n; i++) {
            r[k++] = line[2 * i];
            r[k++] = line[2 * i + 1];
        }
        for (int i = n - 2; i >= 1; i--) {
            r[k++] = line[2 * i];
            r[k++] = line[2 * i + 1];
        }
        return r;
    }

    /**
     * Узлы у выпуклых (со стороны свободного пространства) вершин кольца, материал слева.
     * Дуга скругления отступа описывается ломаной, чьи хорды не заходят внутрь радиуса r.
     */
    private static void cornerNodes(double[] ring, boolean closed, double r, List<double[]> out) {
        int n = ring.length / 2;
        if (n < 2) {
            return;
        }
        for (int i = 0; i < n; i++) {
            int ip = (i - 1 + n) % n;
            int in = (i + 1) % n;
            double vx = ring[2 * i];
            double vy = ring[2 * i + 1];
            double e1x = vx - ring[2 * ip];
            double e1y = vy - ring[2 * ip + 1];
            double e2x = ring[2 * in] - vx;
            double e2y = ring[2 * in + 1] - vy;
            double l1 = Math.hypot(e1x, e1y);
            double l2 = Math.hypot(e2x, e2y);
            if (l1 < 1e-9 || l2 < 1e-9) {
                continue;
            }
            e1x /= l1;
            e1y /= l1;
            e2x /= l2;
            e2y /= l2;
            double cr = Geo.cross(e1x, e1y, e2x, e2y);
            double dt = e1x * e2x + e1y * e2y;
            double turn = Math.atan2(cr, dt); // >0 — левый поворот = выпуклая вершина материала
            if (turn < -1e-9 || (Math.abs(turn) < 1e-9 && dt > 0)) {
                continue; // вогнутая или прямая вершина
            }
            if (Math.abs(turn) < 1e-9 && dt < 0) {
                turn = Math.PI; // разворот (торец линии)
            }
            // нормали вправо (от материала)
            double a1 = Math.atan2(-e1x, e1y);
            int k = Math.max(1, (int) Math.ceil(turn / Math.toRadians(45)));
            double step = turn / k;
            double rr = r / Math.cos(step / 2);
            for (int j = 0; j <= k; j++) {
                double a = a1 + j * step;
                // крайние точки дуги (j=0,k) лежат на нормалях рёбер — на расстоянии rr ≥ r
                out.add(new double[]{vx + rr * Math.cos(a), vy + rr * Math.sin(a)});
            }
        }
    }
}
