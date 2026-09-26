package ru.lct.heatroute.core.route;

/** Результат проверки прямого отрезка трассы. */
final class Edge {
    /** Горизонтальная длина, м. */
    final double len;
    /** Длина, взвешенная коэффициентами спецпроходов: Σ L_i · Kспец_i. */
    final double lenK;
    /**
     * Участки спецпрохода вдоль отрезка: тройки (s0, s1, K) в метрах от начала, по возрастанию,
     * непересекающиеся. Пусто — обычная прокладка на всём отрезке.
     */
    final double[] special;

    Edge(double len, double lenK, double[] special) {
        this.len = len;
        this.lenK = lenK;
        this.special = special;
    }

    boolean insideSpecial(double s, double tol) {
        for (int i = 0; i < special.length; i += 3) {
            if (s > special[i] - tol && s < special[i + 1] + tol) {
                return true;
            }
        }
        return false;
    }

    static final double[] NONE = new double[0];
}
