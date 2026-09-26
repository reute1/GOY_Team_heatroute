package ru.lct.heatroute.core.route;

import java.util.ArrayList;
import java.util.List;
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
        final int[][][] tmpN = new int[n][][];
        @SuppressWarnings("unchecked")
        final List<double[]>[] found = new List[n];
        ThreadLocal<SegGrid.Ctx> tl = ThreadLocal.withInitial(SegGrid.Ctx::new);
        IntStream.range(0, n).parallel().forEach(i -> {
            SegGrid.Ctx ctx = tl.get();
            List<double[]> l = new ArrayList<>();
            for (int j = i + 1; j < n; j++) {
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
}
