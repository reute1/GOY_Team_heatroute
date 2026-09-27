package ru.lct.heatroute.core.route;

import ru.lct.heatroute.core.geo.Geo;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;
import ru.lct.heatroute.core.model.SearchArea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;

/**
 * Построение вариантов подключения перспективных ОКС к существующей тепловой сети.
 *
 * <ol>
 *   <li>Модель ограничений для расчётного ДУ ({@link Env}) и граф видимости ({@link VisGraph}).</li>
 *   <li>Для каждой точки подключения — «вход» E на продолжении финального прямого участка
 *       от ближайшей границы собственного полигона ОКС.</li>
 *   <li>Жадное построение леса (эвристика Прима для дерева Штейнера): на каждом шаге подключается
 *       точка с наименьшим приростом итогового показателя S — либо собственной врезкой
 *       в существующую сеть, либо ответвлением от уже построенной новой сети через тепловую камеру
 *       (с учётом роста расхода и ДУ на общем участке).</li>
 *   <li>Подбор ДУ по расходу и предельной длине, стоимость, разбиение на участки
 *       (спецпроходы, технические узлы), проверка правил с фактическими ДУ.</li>
 *   <li>До трёх содержательно разных вариантов (совместное / раздельное подключение /
 *       альтернативные точки врезки), ранжирование по S.</li>
 * </ol>
 */
public final class Planner {

    public static final class Options {
        /** Максимальная длина ребра графа видимости, м. */
        public double maxEdge = 700;
        /** Радиус поиска мест присоединения к существующей сети от вершины графа, м. */
        public double tieSearchRadius = 600;
        /** Число ближайших мест присоединения, запоминаемых для вершины графа. */
        public int tieCandidates = 10;
        /** Радиус исключения точек врезки основного варианта при поиске альтернативного, м. */
        public double altTieExclusion = 40;
        public int maxVariants = 3;
        /** Предел перестроений локального улучшения порядка вставки на вариант (0 — без улучшения). */
        public int improveEvals = 2000;
        /** Страховочный предел времени локального улучшения на вариант, мс. */
        public long improveMs = 60000;
    }

    private static final int TARGET = 0;
    private static final int VERTEX = 1;
    private static final int BRANCH = 2;
    private static final int TIE_NEW = 3;
    private static final int TIE_EXIST = 4;

    private final InputData data;
    private final RuleBook rules;
    private final Options opt;
    private final PlanResult result = new PlanResult();
    /** Куда пишется диагностика построения (во время локального поиска — во временный список). */
    private List<String> warnSink = result.warnings;

    private Env env;
    private VisGraph g;
    private int designDn;
    private final SegGrid.Ctx ctx = new SegGrid.Ctx();

    // подготовка точек подключения
    /** Варианты «входа» E для каждой точки: индексы вершин графа (первый — от ближайшей границы). */
    private int[][] eOpts;
    private Edge[][] teOpts;
    private String[][] eNotes;
    private static final int MAX_ENTRY_OPTIONS = 4;
    private int[] tPoly;
    private String[] tFail;
    private int[] tRestr;

    // существующая сеть (плоский список отрезков)
    private double[] nsAx;
    private double[] nsAy;
    private double[] nsBx;
    private double[] nsBy;
    private int[] nsLine;
    /** Индексы: отрезки существующей сети (id = индекс в ns*) и существующие камеры (id = индекс камеры). */
    private final SegGrid nsGrid = new SegGrid(50);
    private final SegGrid chGrid = new SegGrid(20);

    // кэш мест присоединения к существующей сети для вершин графа
    private TieCand[][] tieCache;

    public Planner(InputData data, RuleBook rules, Options opt) {
        this.data = data;
        this.rules = rules;
        this.opt = opt;
    }

    // ================================================================== model

    private static final class TNode {
        int id;
        int kind;
        double x;
        double y;
        TNode parent;
        final List<TNode> ch = new ArrayList<>();
        int target = -1;
        int entry;
        Edge up;
        int grid = -1;
        double flow;
        int chamber = -1;
        int netDn;
        int baseAdj;
        Set<Integer> tieExempt;
        Object outId;
    }

    private static final class TieCand {
        double gx;
        double gy;
        int chamber = -1;
        int netDn;
        Set<Integer> exemptC;
        double len;
        double lenK;
        int[] nearChambers = new int[0];
        int baseAdj;
    }

    private final class Forest {
        final List<TNode> nodes = new ArrayList<>();
        final int[] chamberUsed = new int[data.chambers.size()];
        final TNode[] targetNode = new TNode[data.targets.size()];
        final List<TNode> roots = new ArrayList<>();
        /** Порядок подключения точек. */
        final List<Integer> order = new ArrayList<>();
        /** Число точек, подключённых со снятым ограничением стратегии. */
        int relaxed;

        TNode add(int kind, double x, double y) {
            TNode n = new TNode();
            n.id = nodes.size();
            n.kind = kind;
            n.x = x;
            n.y = y;
            nodes.add(n);
            return n;
        }
    }

    private static final class Goal {
        static final int NET_NEW = 0;
        static final int NET_EXIST = 1;
        static final int TREE_SEG = 2;
        static final int TREE_NODE = 3;
        int type;
        double cost;
        int u;
        double gx;
        double gy;
        TieCand tie;
        TNode seg;
        TNode snap;
        int[] path;
        int entry;
        boolean relaxed;
    }

    private static final class Strategy {
        String name;
        boolean allowBranch = true;
        List<double[]> forbiddenTies = Collections.emptyList();
        /** Итог локального улучшения — попадает в checks варианта. */
        String improveNote;
    }

    // ================================================================== run

    public PlanResult run() {
        long t0 = System.currentTimeMillis();
        double total = 0;
        for (InputData.Target t : data.targets) {
            total += t.flow;
        }
        prepareNetwork();
        if (data.area == null) {
            data.area = SearchArea.build(data.targets, data.network);
        }
        double maxGroupFlow = maxGroupFlow();
        designDn = dnOf(maxGroupFlow);
        result.stats.put("targets", data.targets.size());
        result.stats.put("total_flow_tph", round(total, 2));
        result.stats.put("max_group_flow_tph", round(maxGroupFlow, 2));
        result.stats.put("design_dn", designDn);

        env = new Env(data, rules, designDn);
        List<double[]> pts = env.generateNodes(ctx);
        int generated = pts.size();
        pts.removeIf(p -> !data.area.contains(p[0], p[1])); // вершины — только в области поиска
        int base = pts.size();
        result.stats.put("graph_nodes_outside_area", generated - base);
        prepareTargets(pts);
        g = new VisGraph(pts);
        g.build(env, opt.maxEdge);
        tieCache = new TieCand[g.n][];
        result.stats.put("graph_nodes", g.n);
        result.stats.put("graph_obstacle_nodes", base);
        result.stats.put("graph_edges", g.edgeCount);
        result.stats.put("graph_build_ms", System.currentTimeMillis() - t0);
        initSearch();

        List<PlanResult.Variant> vs = new ArrayList<>();
        Strategy s1 = new Strategy();
        s1.name = "Совместное подключение (дерево Штейнера, жадная эвристика)";
        Forest f1 = planBest(s1);
        PlanResult.Variant v1 = build(f1, s1, true);
        vs.add(v1);

        Strategy s2 = new Strategy();
        s2.name = "Раздельное подключение каждой точки";
        s2.allowBranch = false;
        vs.add(build(planBest(s2), s2, true));

        Strategy s3 = new Strategy();
        s3.name = "Совместное подключение с альтернативными точками врезки";
        List<double[]> forb = new ArrayList<>();
        for (TNode r : f1.roots) {
            forb.add(new double[]{r.x, r.y});
        }
        s3.forbiddenTies = forb;
        vs.add(build(planBest(s3), s3, true));

        // уникальность и ранжирование
        List<PlanResult.Variant> uniq = new ArrayList<>();
        Set<String> sigs = new HashSet<>();
        for (PlanResult.Variant v : vs) {
            if (sigs.add(v.signature)) {
                uniq.add(v);
            } else {
                result.warnings.add("Вариант «" + v.strategy + "» совпал с уже найденным и исключён");
            }
        }
        uniq.sort((a, b) -> Double.compare(a.score, b.score));
        for (int i = 0; i < uniq.size() && i < opt.maxVariants; i++) {
            PlanResult.Variant v = uniq.get(i);
            v.rank = i + 1;
            renameVariant(v, "v" + (i + 1));
            result.variants.add(v);
        }
        result.stats.put("total_ms", System.currentTimeMillis() - t0);
        return result;
    }

    // ================================================================== preparation

    private void prepareNetwork() {
        List<double[]> segs = new ArrayList<>();
        List<Integer> own = new ArrayList<>();
        for (int i = 0; i < data.network.size(); i++) {
            double[] c = data.network.get(i).coords;
            for (int k = 0; k + 3 < c.length; k += 2) {
                segs.add(new double[]{c[k], c[k + 1], c[k + 2], c[k + 3]});
                own.add(i);
            }
        }
        int n = segs.size();
        nsAx = new double[n];
        nsAy = new double[n];
        nsBx = new double[n];
        nsBy = new double[n];
        nsLine = new int[n];
        for (int i = 0; i < n; i++) {
            nsAx[i] = segs.get(i)[0];
            nsAy[i] = segs.get(i)[1];
            nsBx[i] = segs.get(i)[2];
            nsBy[i] = segs.get(i)[3];
            nsLine[i] = own.get(i);
            nsGrid.add(nsAx[i], nsAy[i], nsBx[i], nsBy[i], i);
        }
        for (int k = 0; k < data.chambers.size(); k++) {
            InputData.Chamber ch = data.chambers.get(k);
            chGrid.add(ch.x, ch.y, ch.x, ch.y, k);
        }
    }

    /**
     * Наибольший суммарный расход группы точек, которые могут иметь общую новую сеть.
     * Точки i и j связываются, если расстояние между ними меньше суммы их расстояний до существующей
     * сети (с запасом на обходы): иначе общий участок заведомо не короче двух отдельных подключений.
     * По этой группе выбирается расчётный ДУ отступов — а не по сумме всех точек файла.
     */
    private double maxGroupFlow() {
        int nt = data.targets.size();
        double[] dNet = data.area.dNet;
        int[] parent = new int[nt];
        for (int t = 0; t < nt; t++) {
            parent[t] = t;
        }
        for (int i = 0; i < nt; i++) {
            InputData.Target A = data.targets.get(i);
            for (int j = i + 1; j < nt; j++) {
                InputData.Target B = data.targets.get(j);
                double reach = SearchArea.DETOUR * (dNet[i] + dNet[j]) + GROUP_MARGIN;
                if (Geo.dist(A.x, A.y, B.x, B.y) < reach) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, Double> flow = new HashMap<>();
        groupOf = new int[nt];
        for (int t = 0; t < nt; t++) {
            groupOf[t] = find(parent, t);
            flow.merge(groupOf[t], data.targets.get(t).flow, Double::sum);
        }
        result.stats.put("target_groups", flow.size());
        double max = 0;
        for (double f : flow.values()) {
            max = Math.max(max, f);
        }
        return max;
    }

    /** Группа точки (представитель в системе непересекающихся множеств). */
    private int[] groupOf;

    /** Запас при группировке точек (обходы — {@link SearchArea#DETOUR}). */
    private static final double GROUP_MARGIN = 100;

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private void prepareTargets(List<double[]> pts) {
        int nt = data.targets.size();
        eOpts = new int[nt][0];
        teOpts = new Edge[nt][0];
        eNotes = new String[nt][0];
        tPoly = new int[nt];
        tFail = new String[nt];
        tRestr = new int[nt];
        for (int t = 0; t < nt; t++) {
            InputData.Target T = data.targets.get(t);
            tRestr[t] = -1;
            int pid = env.polygonAt(T.x, T.y);
            if (pid < 0) {
                pid = env.nearestOksPolygon(T.x, T.y, 3.0);
            }
            tPoly[t] = pid;
            if (pid < 0) {
                // точка вне полигонов ОКС — подключается непосредственно
                if (env.pointFree(T.x, T.y, 0.0, -1, null, ctx)) {
                    eOpts[t] = new int[]{pts.size()};
                    pts.add(new double[]{T.x, T.y});
                    teOpts[t] = new Edge[]{new Edge(0, 0, Edge.NONE)};
                    eNotes[t] = new String[]{null};
                    result.warnings.add("Точка подключения id=" + T.id + " не лежит в полигоне ОКС — подключается напрямую");
                } else {
                    tFail[t] = "точка вне полигона ОКС и в зоне ограничений";
                }
                continue;
            }
            int rIdx = env.pPolyOwner.get(pid).index;
            tRestr[t] = rIdx;
            double[][] rings = env.pPolys.get(pid);
            double c = env.pPolyOwner.get(pid).rule.clearance(designDn) + env.hw;
            List<double[]> cands = boundaryCandidates(rings, T.x, T.y);
            List<double[]> es = new ArrayList<>();
            List<Edge> ee = new ArrayList<>();
            List<String> en = new ArrayList<>();
            for (int pass = 0; pass < 2 && es.isEmpty(); pass++)
            for (int ci = 0; ci < cands.size() && ci < 120 && es.size() < MAX_ENTRY_OPTIONS; ci++) {
                double[] pb = cands.get(ci);
                double ux = pb[0] - T.x;
                double uy = pb[1] - T.y;
                double l = Math.hypot(ux, uy);
                if (l < 1e-3) {
                    ux = pb[2];
                    uy = pb[3];
                } else {
                    ux /= l;
                    uy /= l;
                    if (ci > 0 && crossesRings(rings, T.x, T.y, pb[0] - ux * 0.01, pb[1] - uy * 0.01)) {
                        continue;
                    }
                }
                for (double d = c + 0.05; d <= c + 30; d += 0.25) {
                    double ex = pb[0] + ux * d;
                    double ey = pb[1] + uy * d;
                    if (!env.pointFree(ex, ey, 0.02, -1, null, ctx)) {
                        continue;
                    }
                    boolean near = false;
                    for (double[] q : es) {
                        if (Geo.dist(ex, ey, q[0], q[1]) < 5) {
                            near = true;
                            break;
                        }
                    }
                    if (near) {
                        break;
                    }
                    Edge e = env.eval(T.x, T.y, ex, ey, rIdx, null, null, false, ctx);
                    if (e == null || (pass == 0 && e.special.length > 0)) {
                        continue; // на первом проходе финальный участок без спецпроходов
                    }
                    es.add(new double[]{ex, ey});
                    ee.add(e);
                    en.add(ci == 0 ? null : String.format(Locale.ROOT,
                            "подход от соседней границы ОКС (%.1f м): ближайшая (%.1f м) выходит во двор или узкий проём",
                            pb[4], cands.get(0)[4]));
                    break;
                }
            }
            boolean found = !es.isEmpty();
            eOpts[t] = new int[es.size()];
            teOpts[t] = ee.toArray(new Edge[0]);
            eNotes[t] = en.toArray(new String[0]);
            for (int k = 0; k < es.size(); k++) {
                eOpts[t][k] = pts.size();
                pts.add(es.get(k));
            }
            if (!found) {
                tFail[t] = "финальный прямой участок от ближайшей границы ОКС упирается в другие ограничения";
            }
        }
    }

    /** Точки границы полигона (ближайшие на каждом ребре) по возрастанию расстояния: {x, y, nx, ny, d}. */
    private static List<double[]> boundaryCandidates(double[][] rings, double px, double py) {
        List<double[]> all = new ArrayList<>();
        for (double[] ring : rings) {
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ax = ring[2 * i];
                double ay = ring[2 * i + 1];
                double bx = ring[2 * j];
                double by = ring[2 * j + 1];
                double t = Geo.projT(px, py, ax, ay, bx, by);
                double qx = ax + t * (bx - ax);
                double qy = ay + t * (by - ay);
                double l = Math.hypot(bx - ax, by - ay);
                if (l < 1e-9) {
                    continue;
                }
                all.add(new double[]{qx, qy, (by - ay) / l, -(bx - ax) / l, Geo.dist(px, py, qx, qy)});
            }
        }
        all.sort((a, b) -> Double.compare(a[4], b[4]));
        List<double[]> res = new ArrayList<>();
        for (double[] c : all) {
            boolean dup = false;
            for (double[] r : res) {
                if (Geo.dist(c[0], c[1], r[0], r[1]) < 0.5) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                res.add(c);
            }
        }
        return res;
    }

    private static boolean crossesRings(double[][] rings, double ax, double ay, double bx, double by) {
        for (double[] ring : rings) {
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                if (Geo.segIntersects(ax, ay, bx, by, ring[2 * i], ring[2 * i + 1], ring[2 * j], ring[2 * j + 1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ближайшая точка границы полигона и внешняя нормаль в ней: {x, y, nx, ny}. */
    private static double[] nearestBoundary(double[][] rings, double px, double py) {
        double best = Double.MAX_VALUE;
        double[] res = null;
        for (double[] ring : rings) {
            int n = ring.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double ax = ring[2 * i];
                double ay = ring[2 * i + 1];
                double bx = ring[2 * j];
                double by = ring[2 * j + 1];
                double t = Geo.projT(px, py, ax, ay, bx, by);
                double qx = ax + t * (bx - ax);
                double qy = ay + t * (by - ay);
                double d = Geo.dist(px, py, qx, qy);
                if (d < best) {
                    best = d;
                    double l = Math.hypot(bx - ax, by - ay);
                    res = new double[]{qx, qy, (by - ay) / l, -(bx - ax) / l};
                }
            }
        }
        return res;
    }

    // ================================================================== tie candidates

    private TieCand[] tieCands(int u) {
        TieCand[] c = tieCache[u];
        if (c != null) {
            return c;
        }
        double ux = g.x[u];
        double uy = g.y[u];
        List<double[]> feet = new ArrayList<>(); // dist, seg, fx, fy
        nsGrid.query(ux, uy, ux, uy, opt.tieSearchRadius, ctx, s -> {
            double t = Geo.projT(ux, uy, nsAx[s], nsAy[s], nsBx[s], nsBy[s]);
            double fx = nsAx[s] + t * (nsBx[s] - nsAx[s]);
            double fy = nsAy[s] + t * (nsBy[s] - nsAy[s]);
            double d = Geo.dist(ux, uy, fx, fy);
            if (d <= opt.tieSearchRadius) {
                feet.add(new double[]{d, s, fx, fy});
            }
            return true;
        });
        feet.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<TieCand> res = new ArrayList<>();
        Set<Integer> chambersDone = new HashSet<>();
        for (double[] f : feet) {
            if (res.size() >= opt.tieCandidates) {
                break;
            }
            int line = nsLine[(int) f[1]];
            double fx = f[2];
            double fy = f[3];
            // камеры в радиусе 10 м — правило обязательного использования существующей камеры
            List<Integer> near = new ArrayList<>();
            chGrid.query(fx, fy, fx, fy, rules.tieSnapRadius, ctx, k -> {
                InputData.Chamber ch = data.chambers.get(k);
                if (ch.adjacency >= 0 && Geo.dist(fx, fy, ch.x, ch.y) <= rules.tieSnapRadius) {
                    near.add(k);
                }
                return true;
            });
            Collections.sort(near);
            for (int k : near) {
                if (chambersDone.add(k)) {
                    InputData.Chamber ch = data.chambers.get(k);
                    TieCand tc = makeTie(u, ch.x, ch.y, k);
                    if (tc != null) {
                        res.add(tc);
                    }
                }
            }
            TieCand tc = makeTie(u, fx, fy, -1);
            if (tc != null) {
                tc.netDn = data.network.get(line).dn;
                tc.nearChambers = near.stream().mapToInt(Integer::intValue).toArray();
                res.add(tc);
            }
        }
        c = res.toArray(new TieCand[0]);
        tieCache[u] = c;
        return c;
    }

    private TieCand makeTie(int u, double gx, double gy, int chamber) {
        Set<Integer> ex = env.crossablesTouching(gx, gy, 0.05, ctx);
        Edge e = env.eval(g.x[u], g.y[u], gx, gy, -1, ex, null, false, ctx);
        if (e == null) {
            return null;
        }
        TieCand tc = new TieCand();
        tc.gx = gx;
        tc.gy = gy;
        tc.chamber = chamber;
        tc.exemptC = ex;
        tc.len = e.len;
        tc.lenK = e.lenK;
        int maxDn = 0;
        int adj = 0;
        for (int f : ex) {
            Env.CFeat cf = env.cFeats.get(f);
            if (cf.netLine >= 0) {
                maxDn = Math.max(maxDn, data.network.get(cf.netLine).dn);
                double[] c = cf.rings[0];
                int n = c.length / 2;
                if (Geo.dist(gx, gy, c[0], c[1]) < 0.05 || Geo.dist(gx, gy, c[2 * n - 2], c[2 * n - 1]) < 0.05) {
                    adj += 1;
                } else {
                    adj += 2;
                }
            }
        }
        tc.netDn = maxDn;
        tc.baseAdj = chamber >= 0 ? data.chambers.get(chamber).adjacency : adj;
        return tc;
    }

    // ================================================================== search

    private double[] dist;
    private int[] pred;
    private int[] doneStamp;
    private int stamp;

    private void initSearch() {
        dist = new double[g.n];
        pred = new int[g.n];
        doneStamp = new int[g.n];
    }

    private double w(double lenK, double len, double price) {
        return rules.scoreCostWeight * price * lenK / rules.scoreCostNorm
                + rules.scoreLengthWeight * len / rules.scoreLengthNorm;
    }

    private double costS(double rub) {
        return rules.scoreCostWeight * rub / rules.scoreCostNorm;
    }

    private double price(double flow) {
        RuleBook.Pipe p = rules.pipeForFlow(flow);
        return p != null ? p.pricePerM : rules.pipes.get(rules.pipes.size() - 1).pricePerM;
    }

    private int dnOf(double flow) {
        RuleBook.Pipe p = rules.pipeForFlow(flow);
        return p != null ? p.dn : rules.pipes.get(rules.pipes.size() - 1).dn;
    }

    private Goal connect(int t, Forest F, Strategy st, boolean useForbidden) {
        for (int k = 0; k < eOpts[t].length; k++) {
            Goal gl = connectFrom(t, k, F, st, useForbidden);
            if (gl != null) {
                gl.entry = k;
                return gl;
            }
        }
        return null;
    }

    private Goal connectFrom(int t, int eo, Forest F, Strategy st, boolean useForbidden) {
        InputData.Target T = data.targets.get(t);
        int e = eOpts[t][eo];
        if (!env.newNetClear(T.x, T.y, g.x[e], g.y[e], null, ctx)) {
            return null;
        }
        double ft = T.flow;
        double pr = price(ft);
        double perM = w(1, 1, pr);
        stamp++;
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        dist[e] = w(teOpts[t][eo].lenK, teOpts[t][eo].len, pr);
        pred[e] = -1;
        pq.add(new double[]{dist[e], e});
        Goal best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[1];
            double d = top[0];
            if (d > dist[u] || doneStamp[u] == stamp) {
                continue;
            }
            if (d >= bestCost) {
                break;
            }
            doneStamp[u] = stamp;
            double px = pred[u] < 0 ? T.x : g.x[pred[u]];
            double py = pred[u] < 0 ? T.y : g.y[pred[u]];
            double ux = g.x[u];
            double uy = g.y[u];
            double dirx = ux - px;
            double diry = uy - py;

            // --- (а) врезка в существующую сеть
            for (TieCand tc : tieCands(u)) {
                double c = d + w(tc.lenK, tc.len, pr);
                if (c >= bestCost) {
                    continue;
                }
                if (dirx * (tc.gx - ux) + diry * (tc.gy - uy) < -1e-9) {
                    continue; // поворот > 90°
                }
                if (useForbidden && isForbidden(st, tc.gx, tc.gy)) {
                    continue;
                }
                int type;
                double tie;
                if (tc.chamber >= 0) {
                    if (tc.baseAdj + F.chamberUsed[tc.chamber] + 1 > rules.maxChamberAdjacency) {
                        continue;
                    }
                    type = Goal.NET_EXIST;
                    tie = rules.existingChamberTieInCost;
                } else {
                    boolean mustUseChamber = false;
                    for (int k : tc.nearChambers) {
                        if (data.chambers.get(k).adjacency + F.chamberUsed[k] + 1 <= rules.maxChamberAdjacency) {
                            mustUseChamber = true;
                            break;
                        }
                    }
                    if (mustUseChamber) {
                        continue; // по правилу 10 м используется существующая камера
                    }
                    if (tc.baseAdj + 1 > rules.maxChamberAdjacency) {
                        continue;
                    }
                    if (tooCloseToNewTie(F, tc.gx, tc.gy)) {
                        continue;
                    }
                    type = Goal.NET_NEW;
                    tie = rules.chamberCost(Math.max(tc.netDn, dnOf(ft)));
                }
                c += costS(tie);
                if (c >= bestCost) {
                    continue;
                }
                if (!env.newNetClear(ux, uy, tc.gx, tc.gy, null, ctx)) {
                    continue;
                }
                Goal gl = new Goal();
                gl.type = type;
                gl.cost = c;
                gl.u = u;
                gl.gx = tc.gx;
                gl.gy = tc.gy;
                gl.tie = tc;
                best = gl;
                bestCost = c;
            }

            // --- (б) ответвление от новой сети
            if (st.allowBranch) {
                double R = Math.min(opt.tieSearchRadius * 2, (bestCost - d) / perM);
                for (TNode c : F.nodes) {
                    if (c.parent == null || c.grid < 0) {
                        continue;
                    }
                    TNode p = c.parent;
                    double segD = Geo.pointSegDist(ux, uy, c.x, c.y, p.x, p.y);
                    if (segD > R) {
                        continue;
                    }
                    double tt = Geo.projT(ux, uy, c.x, c.y, p.x, p.y);
                    double gx = c.x + tt * (p.x - c.x);
                    double gy = c.y + tt * (p.y - c.y);
                    TNode snap = null;
                    if (Geo.dist(gx, gy, c.x, c.y) < 1.0) {
                        snap = c;
                    } else if (Geo.dist(gx, gy, p.x, p.y) < 1.0) {
                        snap = p;
                    }
                    double chamberRub;
                    double delta;
                    TNode next;
                    if (snap == null) {
                        double s = Geo.dist(c.x, c.y, gx, gy);
                        if (c.up.insideSpecial(s, 0.5)) {
                            continue;
                        }
                        chamberRub = rules.chamberCost(dnOf(c.flow + ft));
                        double frac = c.up.len > 0 ? Geo.dist(gx, gy, p.x, p.y) / c.up.len : 0;
                        delta = frac * c.up.lenK * (price(c.flow + ft) - price(c.flow));
                        delta += upstreamDelta(p, ft);
                        next = p;
                    } else {
                        gx = snap.x;
                        gy = snap.y;
                        if (snap.kind == TARGET || snap.kind == TIE_EXIST) {
                            continue;
                        }
                        if (snap.kind == VERTEX) {
                            chamberRub = rules.chamberCost(dnOf(snap.flow + ft));
                        } else if (snap.kind == BRANCH) {
                            if (snap.ch.size() + 2 > rules.maxChamberAdjacency) {
                                continue;
                            }
                            chamberRub = 0;
                        } else { // TIE_NEW
                            if (snap.baseAdj + snap.ch.size() + 1 > rules.maxChamberAdjacency) {
                                continue;
                            }
                            chamberRub = 0;
                        }
                        delta = upstreamDelta(snap, ft);
                        next = snap.parent;
                    }
                    double el = Geo.dist(ux, uy, gx, gy);
                    double cst = d + w(el, el, pr) + costS(chamberRub) + costS(delta);
                    if (cst >= bestCost) {
                        continue;
                    }
                    if (dirx * (gx - ux) + diry * (gy - uy) < -1e-9) {
                        continue;
                    }
                    if (next != null && (gx - ux) * (next.x - gx) + (gy - uy) * (next.y - gy) < -1e-9) {
                        continue;
                    }
                    final TNode fsnap = snap;
                    final TNode fc = c;
                    IntPredicate ex = own -> {
                        if (fsnap == null) {
                            return own == fc.id;
                        }
                        if (own == fsnap.id) {
                            return true;
                        }
                        for (TNode k : fsnap.ch) {
                            if (k.id == own) {
                                return true;
                            }
                        }
                        return false;
                    };
                    Edge ed = env.eval(ux, uy, gx, gy, -1, null, ex, true, ctx);
                    if (ed == null) {
                        continue;
                    }
                    cst = d + w(ed.lenK, ed.len, pr) + costS(chamberRub) + costS(delta);
                    if (cst >= bestCost) {
                        continue;
                    }
                    Goal gl = new Goal();
                    gl.type = snap == null ? Goal.TREE_SEG : Goal.TREE_NODE;
                    gl.cost = cst;
                    gl.u = u;
                    gl.gx = gx;
                    gl.gy = gy;
                    gl.seg = c;
                    gl.snap = snap;
                    best = gl;
                    bestCost = cst;
                }
            }

            // --- релаксация
            int[] nb = g.nbr[u];
            for (int k = 0; k < nb.length; k++) {
                int v = nb[k];
                if (doneStamp[v] == stamp) {
                    continue;
                }
                double nd = d + w(g.lenK[u][k], g.len[u][k], pr);
                if (nd >= dist[v] || nd >= bestCost) {
                    continue;
                }
                if (dirx * (g.x[v] - ux) + diry * (g.y[v] - uy) < -1e-9) {
                    continue;
                }
                if (!env.newNetClear(ux, uy, g.x[v], g.y[v], null, ctx)) {
                    continue;
                }
                dist[v] = nd;
                pred[v] = u;
                pq.add(new double[]{nd, v});
            }
        }
        if (best != null) {
            List<Integer> path = new ArrayList<>();
            for (int v = best.u; v >= 0; v = pred[v]) {
                path.add(v);
            }
            Collections.reverse(path);
            best.path = path.stream().mapToInt(Integer::intValue).toArray();
        }
        return best;
    }

    private double upstreamDelta(TNode from, double ft) {
        double delta = 0;
        for (TNode n = from; n != null && n.parent != null; n = n.parent) {
            delta += n.up.lenK * (price(n.flow + ft) - price(n.flow));
        }
        return delta;
    }

    private boolean isForbidden(Strategy st, double x, double y) {
        for (double[] p : st.forbiddenTies) {
            if (Geo.dist(x, y, p[0], p[1]) < opt.altTieExclusion) {
                return true;
            }
        }
        return false;
    }

    private boolean tooCloseToNewTie(Forest F, double x, double y) {
        for (TNode r : F.roots) {
            if (r.kind == TIE_NEW && Geo.dist(x, y, r.x, r.y) < 1.0) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== forest building

    private Forest plan(Strategy st) {
        env.resetNew();
        Forest F = new Forest();
        List<Integer> remaining = new ArrayList<>();
        for (int t = 0; t < data.targets.size(); t++) {
            if (eOpts[t].length > 0) {
                remaining.add(t);
            }
        }
        // Кэш лучших подключений: после шага пересчитываются только точки, чья область поиска
        // задета изменившейся частью сети (результат тот же, что при полном пересчёте).
        Goal[] cache = new Goal[data.targets.size()];
        boolean[] valid = new boolean[data.targets.size()];
        while (!remaining.isEmpty()) {
            Goal best = null;
            int bestT = -1;
            for (int t : remaining) {
                if (!valid[t]) {
                    cache[t] = connectAny(t, F, st);
                    valid[t] = true;
                }
                Goal gl = cache[t];
                if (gl != null && (best == null || gl.cost < best.cost)) {
                    best = gl;
                    bestT = t;
                }
            }
            if (best == null) {
                break;
            }
            commit(F, bestT, best);
            remaining.remove(Integer.valueOf(bestT));
            invalidate(F, bestT, remaining, cache, valid);
        }
        return F;
    }

    /** Запас к радиусу области поиска: разнос новых участков и отступы при проверке ответвлений. */
    private static final double INVALIDATE_MARGIN = 5;

    /**
     * Поиск Дейкстры от входа E с найденной стоимостью c не выходит за радиус c / (мин. вес метра):
     * все слагаемые стоимости неотрицательны, а вес ребра не меньше веса его длины. Изменения сети
     * дальше этого радиуса не влияют на результат поиска. Изменились: новая цепочка и участки
     * от места присоединения до корня (вырос расход).
     */
    private void invalidate(Forest F, int committed, List<Integer> remaining, Goal[] cache, boolean[] valid) {
        List<TNode> changed = new ArrayList<>();
        for (TNode n = F.targetNode[committed]; n != null && n.parent != null; n = n.parent) {
            changed.add(n);
        }
        for (int t : remaining) {
            Goal gl = cache[t];
            if (!valid[t]) {
                continue;
            }
            if (gl == null || gl.entry > 0 || gl.relaxed) {
                valid[t] = false; // неподключаемость и запасные пути — пересчитываются всегда
                continue;
            }
            int e = eOpts[t][0];
            double r = gl.cost / w(1, 1, price(data.targets.get(t).flow)) + INVALIDATE_MARGIN;
            for (TNode n : changed) {
                if (Geo.pointSegDist(g.x[e], g.y[e], n.x, n.y, n.parent.x, n.parent.y) <= r) {
                    valid[t] = false;
                    break;
                }
            }
        }
    }

    /** Подключение точки по стратегии; ограничения стратегии снимаются, если иначе точка не подключается. */
    private Goal connectAny(int t, Forest F, Strategy st) {
        Goal gl = connect(t, F, st, true);
        if (gl == null && !st.forbiddenTies.isEmpty()) {
            gl = connect(t, F, st, false); // запрет альтернативы не должен приводить к неподключению
            if (gl != null) {
                gl.relaxed = true;
            }
        }
        if (gl == null && !st.allowBranch) {
            // раздельная схема не должна приводить к неподключению: разрешаем ответвление
            Strategy relaxed = new Strategy();
            relaxed.name = st.name;
            gl = connect(t, F, relaxed, false);
            if (gl != null) {
                gl.relaxed = true;
            }
        }
        return gl;
    }

    /** Построение леса при фиксированном порядке подключения точек. */
    private Forest planOrder(Strategy st, List<Integer> order) {
        env.resetNew();
        Forest F = new Forest();
        for (int t : order) {
            Goal gl = connectAny(t, F, st);
            if (gl != null) {
                commit(F, t, gl);
            }
        }
        return F;
    }

    /** Жадное построение и локальное улучшение порядка вставки. */
    private Forest planBest(Strategy st) {
        long t0 = System.currentTimeMillis();
        Forest F = plan(st);
        result.stats.merge("greedy_ms", System.currentTimeMillis() - t0, (a, b) -> (Long) a + (Long) b);
        return opt.improveEvals > 0 ? improve(st, F) : F;
    }

    /**
     * Локальный поиск по порядку вставки, отдельно в каждой группе точек (группы независимы,
     * поэтому перестановки между группами ничего не меняют): точка переносится на другую позицию,
     * сеть группы перестраивается в новом порядке, перенос принимается при уменьшении S.
     * Итог сравнивается с жадным решением целиком — хуже жадного результат не становится.
     */
    private Forest improve(Strategy st, Forest greedy) {
        long t0 = System.currentTimeMillis();
        List<Integer> all = new ArrayList<>(greedy.order);
        for (int t = 0; t < data.targets.size(); t++) {
            if (eOpts[t].length > 0 && !all.contains(t)) {
                all.add(t); // неподключённые жадно — в конец, вдруг в другом порядке найдётся маршрут
            }
        }
        Map<Integer, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int t : all) {
            groups.computeIfAbsent(groupOf[t], k -> new ArrayList<>()).add(t);
        }
        double s0 = build(greedy, st, false).score;
        List<String> saved = warnSink;
        warnSink = new ArrayList<>(); // промежуточные перестроения не пишут в диагностику
        int[] stats = new int[3]; // перестроения, принято, остановлено по пределу
        List<Integer> order = new ArrayList<>();
        for (List<Integer> grp : groups.values()) {
            // время делится между группами пропорционально числу точек
            long budget = Math.max(1000, opt.improveMs * grp.size() / Math.max(1, all.size()));
            order.addAll(grp.size() < 2 ? grp : improveGroup(st, grp, budget, stats));
        }
        Forest cand = planOrder(st, order);
        double s1 = build(cand, st, false).score;
        if (s1 > s0 - 1e-9 || cand.relaxed > greedy.relaxed) {
            order = greedy.order; // улучшения нет — остаётся жадное решение
            s1 = s0;
        }
        warnSink = saved;
        st.improveNote = String.format(Locale.ROOT,
                "Локальное улучшение порядка вставки: S %.4f → %.4f (групп точек %d, %d перестроений, принято %d%s, %d мс)",
                s0, s1, groups.size(), stats[0], stats[1],
                stats[2] > 0 ? ", остановлено по пределу" : ", локальный минимум", System.currentTimeMillis() - t0);
        return planOrder(st, order); // финальное построение — с диагностикой и состоянием новой сети
    }

    /** Локальный поиск порядка внутри одной группы. stats: перестроения, принято, остановлено по пределу. */
    private List<Integer> improveGroup(Strategy st, List<Integer> start, long budgetMs, int[] stats) {
        long t0 = System.currentTimeMillis();
        List<Integer> best = new ArrayList<>(start);
        Forest F0 = planOrder(st, best);
        int relaxed0 = F0.relaxed;
        double bestS = build(F0, st, false).score; // точки других групп — постоянный штраф, на сравнение не влияет
        int evals = 0;
        int n = best.size();
        int moves = n * n;
        // переносы (i → j) перебираются по кругу; после улучшения перебор продолжается с того же места,
        // остановка — когда полный круг прошёл без улучшений
        for (int m = 0, idle = 0; idle < moves; m = (m + 1) % moves, idle++) {
            int i = m / n;
            int j = m % n;
            if (j == i || j == i - 1) {
                continue; // перенос i на i-1 совпадает с переносом i-1 на i
            }
            if (evals >= opt.improveEvals || System.currentTimeMillis() - t0 > budgetMs) {
                stats[2]++;
                break;
            }
            List<Integer> o = new ArrayList<>(best);
            o.add(j, o.remove(i));
            evals++;
            Forest F = planOrder(st, o);
            if (F.relaxed > relaxed0) {
                continue; // стратегия варианта не должна размываться снятием её ограничений
            }
            double s = build(F, st, false).score;
            if (s < bestS - 1e-9) {
                best = o;
                bestS = s;
                stats[1]++;
                idle = 0;
            }
        }
        stats[0] += evals;
        return best;
    }

    private void commit(Forest F, int t, Goal gl) {
        InputData.Target T = data.targets.get(t);
        double ft = T.flow;
        List<TNode> chain = new ArrayList<>();
        TNode tn = F.add(TARGET, T.x, T.y);
        tn.target = t;
        tn.outId = T.id;
        tn.entry = gl.entry;
        F.targetNode[t] = tn;
        F.order.add(t);
        if (gl.relaxed) {
            F.relaxed++;
        }
        chain.add(tn);
        for (int v : gl.path) {
            chain.add(F.add(VERTEX, g.x[v], g.y[v]));
        }
        TNode attach;
        switch (gl.type) {
            case Goal.NET_NEW: {
                attach = F.add(TIE_NEW, gl.gx, gl.gy);
                attach.tieExempt = gl.tie.exemptC;
                attach.netDn = gl.tie.netDn;
                attach.baseAdj = gl.tie.baseAdj;
                F.roots.add(attach);
                break;
            }
            case Goal.NET_EXIST: {
                attach = F.add(TIE_EXIST, gl.gx, gl.gy);
                attach.chamber = gl.tie.chamber;
                attach.tieExempt = gl.tie.exemptC;
                attach.baseAdj = gl.tie.baseAdj;
                attach.outId = data.chambers.get(gl.tie.chamber).id;
                attach.netDn = gl.tie.netDn;
                F.chamberUsed[gl.tie.chamber]++;
                F.roots.add(attach);
                break;
            }
            case Goal.TREE_SEG: {
                TNode c = gl.seg;
                TNode p = c.parent;
                attach = F.add(BRANCH, gl.gx, gl.gy);
                p.ch.remove(c);
                p.ch.add(attach);
                attach.parent = p;
                c.parent = attach;
                attach.ch.add(c);
                attach.flow = c.flow;
                env.nDead.set(c.grid);
                setUp(c);
                setUp(attach);
                break;
            }
            default: {
                attach = gl.snap;
                if (attach.kind == VERTEX) {
                    attach.kind = BRANCH;
                }
            }
        }
        // связать цепочку
        for (int i = 0; i < chain.size(); i++) {
            TNode n = chain.get(i);
            TNode p = i + 1 < chain.size() ? chain.get(i + 1) : attach;
            n.parent = p;
            p.ch.add(n);
            n.flow = ft;
        }
        for (TNode n : chain) {
            setUp(n);
        }
        // рост расхода вверх по дереву
        for (TNode n = attach; n != null && n.parent != null; n = n.parent) {
            n.flow += ft;
        }
    }

    /** Пересчитать отрезок узел→родитель и зарегистрировать его в индексе новой сети. */
    private void setUp(TNode n) {
        TNode p = n.parent;
        int exPoly = n.kind == TARGET ? tRestr[n.target] : -1;
        Set<Integer> exC = p.tieExempt;
        Edge e = env.eval(n.x, n.y, p.x, p.y, exPoly, exC, null, false, ctx);
        if (e == null) {
            double l = Geo.dist(n.x, n.y, p.x, p.y);
            e = new Edge(l, l, Edge.NONE);
            warnSink.add(String.format(Locale.ROOT,
                    "Отрезок (%.1f, %.1f)–(%.1f, %.1f) не прошёл повторную проверку", n.x, n.y, p.x, p.y));
        }
        n.up = e;
        n.grid = env.nGrid.add(n.x, n.y, p.x, p.y, n.id);
    }

    // ================================================================== output

    private static final class Chain {
        List<TNode> nodes = new ArrayList<>();
        Chain parent;
        double flow;
        double length;
        int dn;
        final List<Chain> children = new ArrayList<>();
    }

    private static boolean significant(TNode n) {
        return n.kind != VERTEX;
    }

    /** @param full false — только стоимость и S (для локального поиска), без проверки правил и сигнатуры */
    private PlanResult.Variant build(Forest F, Strategy st, boolean full) {
        PlanResult.Variant v = new PlanResult.Variant();
        v.id = "tmp";
        v.strategy = st.name;
        // цепочки
        Map<TNode, Chain> startOf = new HashMap<>();
        List<Chain> chains = new ArrayList<>();
        for (TNode n : F.nodes) {
            if (n.parent != null && significant(n)) {
                Chain c = new Chain();
                c.nodes.add(n);
                c.flow = n.flow;
                TNode cur = n;
                while (cur.parent != null) {
                    c.length += cur.up.len;
                    cur = cur.parent;
                    c.nodes.add(cur);
                    if (significant(cur)) {
                        break;
                    }
                }
                startOf.put(n, c);
                chains.add(c);
            }
        }
        for (Chain c : chains) {
            TNode top = c.nodes.get(c.nodes.size() - 1);
            c.parent = startOf.get(top);
            if (c.parent != null) {
                c.parent.children.add(c);
            }
            c.dn = dnOf(c.flow);
            if (rules.pipeForFlow(c.flow) == null) {
                v.checks.add("Расход " + round(c.flow, 2) + " т/ч превышает пропускную способность максимального ДУ");
            }
        }
        assignDiameters(chains, v);

        // выходные объекты
        int[] seq = {0, 0, 0};
        Map<TNode, Object> ids = new HashMap<>();
        for (TNode n : F.nodes) {
            if (n.kind == TARGET || n.kind == TIE_EXIST) {
                ids.put(n, n.outId);
            } else if (n.kind == BRANCH || n.kind == TIE_NEW) {
                String id = "tmp_ch_" + (++seq[1]);
                ids.put(n, id);
            }
        }
        Map<TNode, Integer> chamberDn = new HashMap<>();
        for (Chain c : chains) {
            TNode a = c.nodes.get(0);
            TNode b = c.nodes.get(c.nodes.size() - 1);
            chamberDn.merge(a, c.dn, Math::max);
            chamberDn.merge(b, c.dn, Math::max);
            emitChain(c, ids, seq, v);
        }
        Set<TNode> rootsUsed = new HashSet<>();
        for (TNode n : F.nodes) {
            if (n.kind == BRANCH || n.kind == TIE_NEW) {
                if (n.kind == TIE_NEW && n.ch.isEmpty()) {
                    continue;
                }
                int dn = chamberDn.getOrDefault(n, 0);
                if (n.kind == TIE_NEW) {
                    dn = Math.max(dn, n.netDn);
                }
                PlanResult.Node cn = new PlanResult.Node();
                cn.id = (String) ids.get(n);
                cn.kind = n.kind == TIE_NEW ? "tie_in" : "branch";
                cn.x = n.x;
                cn.y = n.y;
                cn.dn = dn;
                cn.cost = rules.chamberCost(dn);
                v.chambers.add(cn);
                v.chamberCost += cn.cost;
            }
            if (n.kind == TIE_EXIST) {
                v.tieInCount += n.ch.size();
            }
            if (n.kind == TIE_NEW || n.kind == TIE_EXIST) {
                rootsUsed.add(n);
            }
        }
        v.parts = rootsUsed.size();
        v.tieInCost = v.tieInCount * rules.existingChamberTieInCost;
        double lineCost = 0;
        for (PlanResult.Line l : v.lines) {
            lineCost += l.cost;
            v.length += l.length;
        }
        for (int t = 0; t < data.targets.size(); t++) {
            if (F.targetNode[t] == null) {
                InputData.Target T = data.targets.get(t);
                v.unconnected.add(T.id);
                v.penalty += rules.penalty(T.flow);
                String why = tFail[t] != null ? tFail[t] : "допустимый маршрут до существующей сети не найден";
                v.checks.add("Точка id=" + T.id + " не подключена: " + why);
            }
        }
        for (int t = 0; t < data.targets.size(); t++) {
            TNode tn = F.targetNode[t];
            if (tn != null && eNotes[t][tn.entry] != null) {
                v.checks.add("Точка id=" + data.targets.get(t).id + ": " + eNotes[t][tn.entry]);
            }
        }
        v.constructionCost = lineCost + v.chamberCost + v.tieInCost;
        v.calculatedCost = v.constructionCost + v.penalty;
        v.score = rules.score(v.calculatedCost, v.length);
        if (!full) {
            return v;
        }
        if (st.improveNote != null) {
            v.checks.add(st.improveNote);
        }
        validate(F, chains, v);
        v.signature = signature(F);
        return v;
    }

    /** ДУ: минимальный по расходу, с учётом предельной длины и невозрастания к месту присоединения. */
    private void assignDiameters(List<Chain> chains, PlanResult.Variant v) {
        for (int iter = 0; iter < 500; iter++) {
            boolean changed = false;
            // монотонность: ДУ не уменьшается к месту присоединения
            for (Chain c : chains) {
                for (Chain p = c.parent, k = c; p != null; k = p, p = p.parent) {
                    if (p.dn < k.dn) {
                        p.dn = k.dn;
                        changed = true;
                    }
                }
            }
            // предельная длина по каждому пути от точки подключения
            for (Chain leaf : chains) {
                if (leaf.nodes.get(0).kind != TARGET) {
                    continue;
                }
                List<Chain> run = new ArrayList<>();
                double runLen = 0;
                for (Chain c = leaf; c != null; c = c.parent) {
                    if (!run.isEmpty() && run.get(0).dn != c.dn) {
                        if (bumpIfTooLong(run, runLen)) {
                            changed = true;
                        }
                        run.clear();
                        runLen = 0;
                    }
                    run.add(c);
                    runLen += c.length;
                }
                if (bumpIfTooLong(run, runLen)) {
                    changed = true;
                }
                if (changed) {
                    break;
                }
            }
            if (!changed) {
                return;
            }
        }
        v.checks.add("Подбор ДУ по предельной длине не сошёлся");
    }

    private boolean bumpIfTooLong(List<Chain> run, double runLen) {
        if (run.isEmpty()) {
            return false;
        }
        RuleBook.Pipe p = rules.pipeByDn(run.get(0).dn);
        if (p == null || runLen <= p.maxLength + 1e-6) {
            return false;
        }
        RuleBook.Pipe nx = rules.nextPipe(p);
        if (nx == null) {
            return false;
        }
        for (Chain c : run) {
            c.dn = nx.dn;
        }
        return true;
    }

    private void emitChain(Chain c, Map<TNode, Object> ids, int[] seq, PlanResult.Variant v) {
        // полилиния и спецучастки в координате вдоль цепочки
        int m = c.nodes.size();
        double[] xs = new double[m];
        double[] ys = new double[m];
        double[] cum = new double[m];
        List<double[]> sp = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            TNode n = c.nodes.get(i);
            xs[i] = n.x;
            ys[i] = n.y;
            if (i > 0) {
                TNode prev = c.nodes.get(i - 1);
                cum[i] = cum[i - 1] + prev.up.len;
                double[] s = prev.up.special;
                for (int k = 0; k < s.length; k += 3) {
                    sp.add(new double[]{cum[i - 1] + s[k], cum[i - 1] + s[k + 1], s[k + 2]});
                }
            }
        }
        double total = cum[m - 1];
        // кусочное разбиение
        List<double[]> pieces = new ArrayList<>(); // a, b, K
        double cur = 0;
        for (double[] s : sp) {
            if (s[0] > cur + 0.01) {
                pieces.add(new double[]{cur, s[0], 1});
            } else if (!pieces.isEmpty() && s[0] > cur) {
                // микрозазор — присоединить к предыдущему куску
                pieces.get(pieces.size() - 1)[1] = s[0];
            }
            pieces.add(new double[]{Math.max(cur, s[0]), s[1], s[2]});
            cur = s[1];
        }
        if (total > cur + 0.01) {
            pieces.add(new double[]{cur, total, 1});
        } else if (!pieces.isEmpty()) {
            pieces.get(pieces.size() - 1)[1] = total;
        }
        RuleBook.Pipe pipe = rules.pipeByDn(c.dn);
        Object prevId = ids.get(c.nodes.get(0));
        for (int i = 0; i < pieces.size(); i++) {
            double[] pc = pieces.get(i);
            Object endId;
            if (i == pieces.size() - 1) {
                endId = ids.get(c.nodes.get(m - 1));
            } else {
                double[] pt = pointAt(xs, ys, cum, pc[1]);
                PlanResult.Node tn = new PlanResult.Node();
                tn.id = "tmp_tn_" + (++seq[2]);
                tn.kind = "technical_node";
                tn.x = pt[0];
                tn.y = pt[1];
                v.technicalNodes.add(tn);
                endId = tn.id;
            }
            PlanResult.Line l = new PlanResult.Line();
            l.id = "tmp_net_" + (++seq[0]);
            l.startNodeId = prevId;
            l.endNodeId = endId;
            l.flow = round(c.flow, 3);
            l.dn = c.dn;
            l.length = round(pc[1] - pc[0], 3);
            l.kSpecial = pc[2];
            l.layingMethod = pc[2] > 1 ? "special" : "base";
            l.cost = round(l.length * pipe.pricePerM * pc[2], 2);
            l.coords = subLine(xs, ys, cum, pc[0], pc[1]);
            v.lines.add(l);
            prevId = endId;
        }
    }

    private static double[] pointAt(double[] xs, double[] ys, double[] cum, double s) {
        for (int i = 1; i < xs.length; i++) {
            if (s <= cum[i] + 1e-9) {
                double seg = cum[i] - cum[i - 1];
                double t = seg > 0 ? (s - cum[i - 1]) / seg : 0;
                return new double[]{xs[i - 1] + t * (xs[i] - xs[i - 1]), ys[i - 1] + t * (ys[i] - ys[i - 1])};
            }
        }
        return new double[]{xs[xs.length - 1], ys[ys.length - 1]};
    }

    private static double[] subLine(double[] xs, double[] ys, double[] cum, double a, double b) {
        List<Double> out = new ArrayList<>();
        double[] p = pointAt(xs, ys, cum, a);
        out.add(p[0]);
        out.add(p[1]);
        for (int i = 1; i < xs.length - 1; i++) {
            if (cum[i] > a + 1e-6 && cum[i] < b - 1e-6) {
                out.add(xs[i]);
                out.add(ys[i]);
            }
        }
        p = pointAt(xs, ys, cum, b);
        out.add(p[0]);
        out.add(p[1]);
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    // ================================================================== validation

    private void validate(Forest F, List<Chain> chains, PlanResult.Variant v) {
        Map<Integer, Env> envs = new HashMap<>();
        Map<TNode, Integer> dnOfNode = new HashMap<>();
        for (Chain c : chains) {
            for (int i = 0; i + 1 < c.nodes.size(); i++) {
                dnOfNode.put(c.nodes.get(i), c.dn);
            }
        }
        int bad = 0;
        int turns = 0;
        for (TNode n : F.nodes) {
            if (n.parent == null) {
                continue;
            }
            int dn = dnOfNode.getOrDefault(n, designDn);
            Env e = dn == designDn ? env : envs.computeIfAbsent(dn, k -> new Env(data, rules, k));
            int exPoly = n.kind == TARGET ? findPoly(e, n) : -1;
            Edge ed = e.eval(n.x, n.y, n.parent.x, n.parent.y, exPoly, crossExempt(e, n.parent), null, false, ctx);
            if (ed == null) {
                bad++;
            }
            TNode p = n.parent;
            if (p.parent != null && (p.kind == VERTEX || p.kind == BRANCH)) {
                double tr = Geo.turnDeg(n.x, n.y, p.x, p.y, p.parent.x, p.parent.y);
                if (tr > rules.maxTurnDeg + 1e-6) {
                    turns++;
                }
            }
        }
        // Предельная длина: по каждому пути «точка → место присоединения» серии участков одного ДУ;
        // для отчёта — самая длинная серия каждого ДУ и путь, на котором она получена.
        Map<Integer, double[]> maxRun = new java.util.TreeMap<>(); // dn → {длина, индекс точки}
        int over = 0;
        for (Chain leaf : chains) {
            if (leaf.nodes.get(0).kind != TARGET) {
                continue;
            }
            double run = 0;
            int dn = -1;
            boolean reported = false;
            for (Chain c = leaf; c != null; c = c.parent) {
                if (c.dn != dn) {
                    run = 0;
                    dn = c.dn;
                }
                run += c.length;
                double[] m = maxRun.computeIfAbsent(dn, k -> new double[]{0, -1});
                if (run > m[0]) {
                    m[0] = run;
                    m[1] = leaf.nodes.get(0).target;
                }
                RuleBook.Pipe p = rules.pipeByDn(dn);
                if (p != null && run > p.maxLength + 1e-6 && !reported) {
                    v.checks.add("Превышена предельная длина ДУ" + dn + " на пути от точки " + leaf.nodes.get(0).outId);
                    reported = true;
                    over++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, double[]> e : maxRun.entrySet()) {
            RuleBook.Pipe p = rules.pipeByDn(e.getKey());
            double lim = p != null ? p.maxLength : Double.NaN;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("diameter", e.getKey());
            row.put("max_series_length", round(e.getValue()[0], 1));
            row.put("max_length", lim);
            row.put("from_oks_id", data.targets.get((int) e.getValue()[1]).id);
            v.lengthCheck.add(row);
            sb.append(sb.length() == 0 ? "" : "; ").append(String.format(Locale.ROOT, "ДУ%d — %.0f из %.0f м",
                    e.getKey(), e.getValue()[0], lim));
        }
        v.checks.add((over == 0 ? "Предельная длина: нарушений нет (" : "Предельная длина: нарушений " + over + " (")
                + "наибольшая серия участков одного ДУ на пути до места присоединения: " + sb + ")");
        v.checks.add(bad == 0 ? "Отступы и пересечения с фактическими ДУ: нарушений нет"
                : "Отступы с фактическими ДУ: нарушений " + bad + " (трасса строилась для расчётного ДУ" + designDn + ")");
        v.checks.add(turns == 0 ? "Углы поворота ≤ 90°: нарушений нет" : "Повороты > 90°: " + turns);
    }

    private int findPoly(Env e, TNode n) {
        return tRestr[n.target];
    }

    private Set<Integer> crossExempt(Env e, TNode p) {
        if (p.kind == TIE_NEW || p.kind == TIE_EXIST) {
            return e.crossablesTouching(p.x, p.y, 0.05, ctx);
        }
        return null;
    }

    private static String signature(Forest F) {
        TreeSet<String> parts = new TreeSet<>();
        for (TNode r : F.roots) {
            TreeSet<Integer> ts = new TreeSet<>();
            collectTargets(r, ts);
            parts.add(Math.round(r.x / 5) + ":" + Math.round(r.y / 5) + "=" + ts);
        }
        return String.join("|", parts);
    }

    private static void collectTargets(TNode n, Set<Integer> out) {
        if (n.target >= 0) {
            out.add(n.target);
        }
        for (TNode c : n.ch) {
            collectTargets(c, out);
        }
    }

    private static void renameVariant(PlanResult.Variant v, String vid) {
        v.id = vid;
        Map<Object, Object> map = new HashMap<>();
        for (PlanResult.Node n : v.chambers) {
            String nid = vid + "_" + n.id.substring(4);
            map.put(n.id, nid);
            n.id = nid;
        }
        for (PlanResult.Node n : v.technicalNodes) {
            String nid = vid + "_" + n.id.substring(4);
            map.put(n.id, nid);
            n.id = nid;
        }
        for (PlanResult.Line l : v.lines) {
            l.id = vid + "_" + l.id.substring(4);
            if (l.startNodeId instanceof String && map.containsKey(l.startNodeId)) {
                l.startNodeId = map.get(l.startNodeId);
            }
            if (l.endNodeId instanceof String && map.containsKey(l.endNodeId)) {
                l.endNodeId = map.get(l.endNodeId);
            }
        }
    }

    static double round(double v, int d) {
        double m = Math.pow(10, d);
        return Math.round(v * m) / m;
    }
}
