package ru.lct.heatroute.core.geo;

/**
 * Прямое и обратное преобразование WGS 84 (EPSG:4326) ↔ UTM (по умолчанию зона 37N, EPSG:32637).
 * Ряды Крюгера (Karney 2011), точность — доли миллиметра в пределах зоны.
 */
public final class Utm {
    private static final double A_AXIS = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double K0 = 0.9996;
    private static final double E0 = 500000.0;

    private final double lon0;
    private final double n;
    private final double aa;
    private final double[] alpha;
    private final double[] beta;
    private final double[] delta;
    private final double c2n;

    public Utm(int zone) {
        this.lon0 = Math.toRadians(-183 + 6 * zone);
        n = F / (2 - F);
        double n2 = n * n;
        double n3 = n2 * n;
        aa = A_AXIS / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64);
        alpha = new double[]{n / 2 - 2 * n2 / 3 + 5 * n3 / 16, 13 * n2 / 48 - 3 * n3 / 5, 61 * n3 / 240};
        beta = new double[]{n / 2 - 2 * n2 / 3 + 37 * n3 / 96, n2 / 48 + n3 / 15, 17 * n3 / 480};
        delta = new double[]{2 * n - 2 * n2 / 3 - 2 * n3, 7 * n2 / 3 - 8 * n3 / 5, 56 * n3 / 15};
        c2n = 2 * Math.sqrt(n) / (1 + n);
    }

    public static Utm zone37() {
        return new Utm(37);
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }

    /** lon, lat (градусы) → {x (E), y (N)} в метрах. */
    public double[] forward(double lonDeg, double latDeg) {
        double phi = Math.toRadians(latDeg);
        double dl = Math.toRadians(lonDeg) - lon0;
        double s = Math.sin(phi);
        double t = Math.sinh(atanh(s) - c2n * atanh(c2n * s));
        double xi = Math.atan2(t, Math.cos(dl));
        double eta = atanh(Math.sin(dl) / Math.sqrt(1 + t * t));
        double x = eta;
        double y = xi;
        for (int j = 1; j <= 3; j++) {
            x += alpha[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
            y += alpha[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
        }
        return new double[]{E0 + K0 * aa * x, K0 * aa * y};
    }

    /** x, y (метры) → {lon, lat} в градусах. */
    public double[] inverse(double x, double y) {
        double xi = y / (K0 * aa);
        double eta = (x - E0) / (K0 * aa);
        double xp = xi;
        double ep = eta;
        for (int j = 1; j <= 3; j++) {
            xp -= beta[j - 1] * Math.sin(2 * j * xi) * Math.cosh(2 * j * eta);
            ep -= beta[j - 1] * Math.cos(2 * j * xi) * Math.sinh(2 * j * eta);
        }
        double chi = Math.asin(Math.sin(xp) / Math.cosh(ep));
        double phi = chi;
        for (int j = 1; j <= 3; j++) {
            phi += delta[j - 1] * Math.sin(2 * j * chi);
        }
        double lam = lon0 + Math.atan2(Math.sinh(ep), Math.cos(xp));
        return new double[]{Math.toDegrees(lam), Math.toDegrees(phi)};
    }
}
