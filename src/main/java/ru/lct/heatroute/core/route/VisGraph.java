package ru.lct.heatroute.core.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Граф видимости: вершины у выпуклых углов препятствий (со смещением на отступ),
 * рёбра — прямые отрезки, допустимые по правилам {@link Env#eval}.
 */
final class VisGraph {
    final double[] x;
    final double[] y;
    final int n;
    int[][] nbr;
    double[][] len;
    double[][] lenK;
    long edgeCount;

    VisGraph(List<double[]> pts) {
        n = pts.size();
        x = new double[n];
        y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = pts.get(i)[0];
            y[i] = pts.get(i)[1];
        }
    }

    void build(Env env, double maxEdge) {
        @SuppressWarnings("unchecked")
        final List<double[]>[] found = new List[n];
        // Кандидаты в соседи — только из ячеек сетки со стороной maxEdge вокруг вершины (а не все пары).
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < n; i++) {
            cells.computeIfAbsent(cell(x[i], y[i], maxEdge, 0, 0), k -> new ArrayList<>()).add(i);
        }
        ThreadLocal<SegGrid.Ctx> tl = ThreadLocal.withInitial(SegGrid.Ctx::new);
        IntStream.range(0, n).parallel().forEach(i -> {
            SegGrid.Ctx ctx = tl.get();
            List<double[]> l = new ArrayList<>();
            List<Integer> cand = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<Integer> c = cells.get(cell(x[i], y[i], maxEdge, dx, dy));
                    if (c != null) {
                        for (int j : c) {
                            if (j > i) {
                                cand.add(j);
                            }
                        }
                    }
                }
            }
            Collections.sort(cand); // порядок рёбер как при полном переборе — расчёт детерминирован
            for (int j : cand) {
                double dx = x[j] - x[i];
                double dy = y[j] - y[i];
                double d2 = dx * dx + dy * dy;
                if (d2 > maxEdge * maxEdge || d2 < 1e-6) {
                    continue;
                }
                Edge e = env.eval(x[i], y[i], x[j], y[j], -1, null, null, false, ctx);
                if (e != null) {
                    l.add(new double[]{j, e.len, e.lenK});
                }
            }
            found[i] = l;
        });
        cells.clear();
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) {
            for (double[] e : found[i]) {
                deg[i]++;
                deg[(int) e[0]]++;
            }
        }
        nbr = new int[n][];
        len = new double[n][];
        lenK = new double[n][];
        for (int i = 0; i < n; i++) {
            nbr[i] = new int[deg[i]];
            len[i] = new double[deg[i]];
            lenK[i] = new double[deg[i]];
        }
        int[] fill = new int[n];
        for (int i = 0; i < n; i++) {
            for (double[] e : found[i]) {
                int j = (int) e[0];
                nbr[i][fill[i]] = j;
                len[i][fill[i]] = e[1];
                lenK[i][fill[i]++] = e[2];
                nbr[j][fill[j]] = i;
                len[j][fill[j]] = e[1];
                lenK[j][fill[j]++] = e[2];
                edgeCount++;
            }
        }
    }

    private static long cell(double px, double py, double size, int dx, int dy) {
        long cx = (long) Math.floor(px / size) + dx;
        long cy = (long) Math.floor(py / size) + dy;
        return (cx << 32) ^ (cy & 0xffffffffL);
    }
}
