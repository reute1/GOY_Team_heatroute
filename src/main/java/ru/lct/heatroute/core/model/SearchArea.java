package ru.lct.heatroute.core.model;

import ru.lct.heatroute.core.geo.Geo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Область поиска трасс: круги вокруг точек подключения радиусом {@code DETOUR·d + MARGIN}, где d —
 * расстояние от точки до существующей сети. Маршрут к сети с разумными обходами в круг укладывается,
 * а совместная сеть связанных точек проходит через пересекающиеся круги (связь точек при группировке
 * — расстояние меньше {@code DETOUR·(d1 + d2) + 100}).
 * Вершины графа ставятся только внутри области; объекты дальше области + {@code extra} на расчёт
 * повлиять не могут и при чтении большого файла отбрасываются.
 */
public final class SearchArea {
    public static final double DETOUR = 1.5;
    public static final double MARGIN = 200;
    private static final double CELL = 200;

    /** Центры, радиусы кругов и расстояния точек до существующей сети (по индексу точки). */
    public final double[] cx;
    public final double[] cy;
    public final double[] r;
    public final double[] dNet;
    /** Сети нет — область не ограничена. */
    public final boolean unbounded;

    private double extra = -1;
    private final Set<Long> cells = new HashSet<>();

    private SearchArea(int n, boolean unbounded) {
        cx = new double[n];
        cy = new double[n];
        r = new double[n];
        dNet = new double[n];
        this.unbounded = unbounded;
    }

    public static SearchArea build(List<InputData.Target> targets, List<InputData.NetLine> network) {
        int n = targets.size();
        SearchArea a = new SearchArea(n, network.isEmpty());
        for (int t = 0; t < n; t++) {
            InputData.Target T = targets.get(t);
            double best = Double.POSITIVE_INFINITY;
            for (InputData.NetLine nl : network) {
                double[] c = nl.coords;
                for (int k = 0; k + 3 < c.length; k += 2) {
                    best = Math.min(best, Geo.pointSegDist(T.x, T.y, c[k], c[k + 1], c[k + 2], c[k + 3]));
                }
            }
            a.cx[t] = T.x;
            a.cy[t] = T.y;
            a.dNet[t] = best;
            a.r[t] = DETOUR * best + MARGIN;
        }
        return a;
    }

    public boolean contains(double x, double y) {
        if (unbounded) {
            return true;
        }
        for (int i = 0; i < r.length; i++) {
            if (Geo.dist(x, y, cx[i], cy[i]) <= r[i]) {
                return true;
            }
        }
        return false;
    }

    /** Прямоугольник (с точностью до ячейки 200 м) касается области, расширенной на extra. */
    public boolean touches(double minX, double minY, double maxX, double maxY, double extra) {
        if (unbounded) {
            return true;
        }
        if (extra != this.extra) {
            index(extra);
        }
        for (long ix = cell(minX); ix <= cell(maxX); ix++) {
            for (long iy = cell(minY); iy <= cell(maxY); iy++) {
                if (cells.contains(key(ix, iy))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void index(double ext) {
        extra = ext;
        cells.clear();
        for (int i = 0; i < r.length; i++) {
            double R = r[i] + ext;
            for (long ix = cell(cx[i] - R); ix <= cell(cx[i] + R); ix++) {
                for (long iy = cell(cy[i] - R); iy <= cell(cy[i] + R); iy++) {
                    // расстояние от центра круга до квадрата ячейки
                    double dx = Math.max(0, Math.max(ix * CELL - cx[i], cx[i] - (ix + 1) * CELL));
                    double dy = Math.max(0, Math.max(iy * CELL - cy[i], cy[i] - (iy + 1) * CELL));
                    if (dx * dx + dy * dy <= R * R) {
                        cells.add(key(ix, iy));
                    }
                }
            }
        }
    }

    private static long cell(double v) {
        return (long) Math.floor(v / CELL);
    }

    private static long key(long ix, long iy) {
        return (ix << 32) ^ (iy & 0xffffffffL);
    }
}
