package ru.lct.heatroute.core.route;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Равномерная сетка для быстрого поиска отрезков рядом с другим отрезком.
 * Поддерживает добавление отрезков «на лету» (новая сеть растёт в процессе расчёта).
 */
final class SegGrid {
    final double cell;
    double[] ax = new double[1024];
    double[] ay = new double[1024];
    double[] bx = new double[1024];
    double[] by = new double[1024];
    int[] owner = new int[1024];
    int size;
    private final Map<Long, int[]> cells = new HashMap<>();

    SegGrid(double cell) {
        this.cell = cell;
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    int add(double x1, double y1, double x2, double y2, int own) {
        if (size == ax.length) {
            int n = size * 2;
            ax = Arrays.copyOf(ax, n);
            ay = Arrays.copyOf(ay, n);
            bx = Arrays.copyOf(bx, n);
            by = Arrays.copyOf(by, n);
            owner = Arrays.copyOf(owner, n);
        }
        int id = size++;
        ax[id] = x1;
        ay[id] = y1;
        bx[id] = x2;
        by[id] = y2;
        owner[id] = own;
        // Растеризация отрезка по ячейкам (с запасом в соседние ячейки по bbox шага).
        double len = Math.hypot(x2 - x1, y2 - y1);
        int steps = Math.max(1, (int) Math.ceil(len / (cell * 0.5)));
        long last = Long.MIN_VALUE;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int cx = (int) Math.floor((x1 + t * (x2 - x1)) / cell);
            int cy = (int) Math.floor((y1 + t * (y2 - y1)) / cell);
            long k = key(cx, cy);
            if (k != last) {
                put(k, id);
                last = k;
            }
        }
        return id;
    }

    private void put(long k, int id) {
        int[] a = cells.get(k);
        if (a == null) {
            a = new int[]{1, id, 0, 0};
            cells.put(k, a);
            return;
        }
        int n = a[0];
        if (n > 0 && a[n] == id) {
            return;
        }
        if (n + 1 >= a.length) {
            a = Arrays.copyOf(a, a.length * 2);
            cells.put(k, a);
        }
        a[n + 1] = id;
        a[0] = n + 1;
    }

    /** Контекст запроса (на поток): отметки для исключения повторов. */
    static final class Ctx {
        int[] stamp = new int[1024];
        int cur;

        void begin(int size) {
            if (stamp.length < size) {
                stamp = Arrays.copyOf(stamp, Math.max(size, stamp.length * 2));
            }
            cur++;
            if (cur == Integer.MAX_VALUE) {
                Arrays.fill(stamp, 0);
                cur = 1;
            }
        }
    }

    interface Visitor {
        /** @return false — прекратить обход. */
        boolean visit(int id);
    }

    /**
     * Обходит отрезки в коридоре радиуса r вокруг AB (r ≤ cell даёт точный охват по соседним ячейкам).
     * @return false, если обход был прерван посетителем.
     */
    boolean query(double x1, double y1, double x2, double y2, double r, Ctx ctx, Visitor v) {
        ctx.begin(size);
        int ring = Math.max(1, (int) Math.ceil(r / cell));
        double len = Math.hypot(x2 - x1, y2 - y1);
        int steps = Math.max(1, (int) Math.ceil(len / (cell * 0.5)));
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int cx = (int) Math.floor((x1 + t * (x2 - x1)) / cell);
            int cy = (int) Math.floor((y1 + t * (y2 - y1)) / cell);
            if (cx == lastX && cy == lastY) {
                continue;
            }
            lastX = cx;
            lastY = cy;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    int[] a = cells.get(key(cx + dx, cy + dy));
                    if (a == null) {
                        continue;
                    }
                    int n = a[0];
                    for (int i = 1; i <= n; i++) {
                        int id = a[i];
                        if (ctx.stamp[id] == ctx.cur) {
                            continue;
                        }
                        ctx.stamp[id] = ctx.cur;
                        if (!v.visit(id)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
