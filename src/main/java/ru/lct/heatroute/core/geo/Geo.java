package ru.lct.heatroute.core.geo;

/** Плоская вычислительная геометрия в метрах (UTM). */
public final class Geo {
    public static final double EPS = 1e-9;

    private Geo() {
    }

    public static double dist(double ax, double ay, double bx, double by) {
        return Math.hypot(bx - ax, by - ay);
    }

    public static double dist(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    public static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    /** Параметр t∈[0,1] проекции точки P на отрезок AB. */
    public static double projT(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        if (l2 < EPS) {
            return 0;
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / l2;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    public static double pointSegDist(double px, double py, double ax, double ay, double bx, double by) {
        double t = projT(px, py, ax, ay, bx, by);
        return Math.hypot(ax + t * (bx - ax) - px, ay + t * (by - ay) - py);
    }

    /**
     * Параметр t на AB точки пересечения с CD (собственное или касание), либо NaN.
     * Коллинеарное перекрытие возвращает параметр ближайшей к A общей точки.
     */
    public static double segIntersectT(double ax, double ay, double bx, double by,
                                       double cx, double cy, double dx, double dy) {
        double rx = bx - ax;
        double ry = by - ay;
        double sx = dx - cx;
        double sy = dy - cy;
        double den = cross(rx, ry, sx, sy);
        double qpx = cx - ax;
        double qpy = cy - ay;
        if (Math.abs(den) < 1e-12) {
            if (Math.abs(cross(qpx, qpy, rx, ry)) > 1e-9) {
                return Double.NaN;
            }
            double rr = rx * rx + ry * ry;
            if (rr < EPS) {
                return Double.NaN;
            }
            double t0 = (qpx * rx + qpy * ry) / rr;
            double t1 = t0 + (sx * rx + sy * ry) / rr;
            double lo = Math.max(0, Math.min(t0, t1));
            double hi = Math.min(1, Math.max(t0, t1));
            return lo <= hi ? lo : Double.NaN;
        }
        double t = cross(qpx, qpy, sx, sy) / den;
        double u = cross(qpx, qpy, rx, ry) / den;
        if (t < -1e-12 || t > 1 + 1e-12 || u < -1e-12 || u > 1 + 1e-12) {
            return Double.NaN;
        }
        return Math.max(0, Math.min(1, t));
    }

    public static boolean segIntersects(double ax, double ay, double bx, double by,
                                        double cx, double cy, double dx, double dy) {
        return !Double.isNaN(segIntersectT(ax, ay, bx, by, cx, cy, dx, dy));
    }

    public static double segSegDist(double ax, double ay, double bx, double by,
                                    double cx, double cy, double dx, double dy) {
        if (segIntersects(ax, ay, bx, by, cx, cy, dx, dy)) {
            return 0;
        }
        double d1 = pointSegDist(ax, ay, cx, cy, dx, dy);
        double d2 = pointSegDist(bx, by, cx, cy, dx, dy);
        double d3 = pointSegDist(cx, cy, ax, ay, bx, by);
        double d4 = pointSegDist(dx, dy, ax, ay, bx, by);
        return Math.min(Math.min(d1, d2), Math.min(d3, d4));
    }

    /** Точка внутри кольца (x0,y0,x1,y1,...), кольцо может быть не замкнуто явно. */
    public static boolean pointInRing(double px, double py, double[] ring) {
        boolean in = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[2 * i];
            double yi = ring[2 * i + 1];
            double xj = ring[2 * j];
            double yj = ring[2 * j + 1];
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /** Удвоенная знаковая площадь кольца (>0 — против часовой стрелки). */
    public static double signedArea2(double[] ring) {
        double s = 0;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            s += ring[2 * j] * ring[2 * i + 1] - ring[2 * i] * ring[2 * j + 1];
        }
        return s;
    }

    public static double[] reverseRing(double[] ring) {
        int n = ring.length / 2;
        double[] r = new double[ring.length];
        for (int i = 0; i < n; i++) {
            r[2 * i] = ring[2 * (n - 1 - i)];
            r[2 * i + 1] = ring[2 * (n - 1 - i) + 1];
        }
        return r;
    }

    /** Угол (градусы, 0..90) между направлениями двух отрезков без учёта ориентации. */
    public static double lineAngleDeg(double ux, double uy, double vx, double vy) {
        double lu = Math.hypot(ux, uy);
        double lv = Math.hypot(vx, vy);
        if (lu < EPS || lv < EPS) {
            return 90;
        }
        double c = Math.abs((ux * vx + uy * vy) / (lu * lv));
        return Math.toDegrees(Math.acos(Math.min(1, c)));
    }

    /** Угол поворота (0 — прямо, 180 — разворот) при движении a→b→c. */
    public static double turnDeg(double ax, double ay, double bx, double by, double cx, double cy) {
        double ux = bx - ax;
        double uy = by - ay;
        double vx = cx - bx;
        double vy = cy - by;
        double lu = Math.hypot(ux, uy);
        double lv = Math.hypot(vx, vy);
        if (lu < EPS || lv < EPS) {
            return 0;
        }
        double c = (ux * vx + uy * vy) / (lu * lv);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }
}
