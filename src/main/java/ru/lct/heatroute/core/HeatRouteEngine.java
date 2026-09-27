package ru.lct.heatroute.core;

import ru.lct.heatroute.core.geo.Utm;
import ru.lct.heatroute.core.io.InputReader;
import ru.lct.heatroute.core.io.OutputWriter;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;
import ru.lct.heatroute.core.route.PlanResult;
import ru.lct.heatroute.core.route.Planner;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Точка входа ядра: GeoJSON на входе → GeoJSON с вариантами на выходе. Без зависимостей от Spring. */
public final class HeatRouteEngine {
    private final RuleBook rules;
    private final Planner.Options options;
    private final Utm utm = Utm.zone37();

    public HeatRouteEngine() {
        this(RuleBook.defaults(), new Planner.Options());
    }

    public HeatRouteEngine(RuleBook rules, Planner.Options options) {
        this.rules = rules;
        this.options = options;
    }

    /** Расчёт по потоку (однопроходное чтение, все объекты в памяти). */
    public PlanResult process(InputStream in, OutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        InputData data = new InputReader(utm, rules).read(in);
        return plan(data, System.currentTimeMillis() - t0, out);
    }

    /**
     * Расчёт по файлу: двухпроходное чтение, в памяти только окрестность подключаемых объектов —
     * для больших входных файлов (до 3 ГБ).
     */
    public PlanResult process(Path in, OutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        InputData data = new InputReader(utm, rules).read(in, reach());
        return plan(data, System.currentTimeMillis() - t0, out);
    }

    /**
     * Насколько далеко от области поиска объекты ещё могут повлиять на расчёт: ребро графа между
     * вершинами области отходит от вершины не дальше maxEdge/2, отрезок до места врезки —
     * не дальше радиуса поиска врезки; плюс запас на отступы и габариты.
     */
    private double reach() {
        return Math.max(options.tieSearchRadius, options.maxEdge / 2) + 50;
    }

    private PlanResult plan(InputData data, long tRead, OutputStream out) throws IOException {
        PlanResult r = new Planner(data, rules, options).run();
        r.warnings.addAll(0, data.warnings);
        r.stats.put("input_features", data.featureCount);
        r.stats.put("skipped_outside_area", data.skippedOutsideArea);
        r.stats.put("read_ms", tRead);
        new OutputWriter(utm).write(r, out);
        return r;
    }

    /** CLI: java -cp heatroute.jar ru.lct.heatroute.core.HeatRouteEngine input.geojson output.geojson */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Использование: HeatRouteEngine <input.geojson> <output.geojson>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        PlanResult r;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            r = new HeatRouteEngine().process(in, os);
        }
        System.out.println("Статистика: " + r.stats);
        for (String w : r.warnings) {
            System.out.println("! " + w);
        }
        for (PlanResult.Variant v : r.variants) {
            System.out.printf(java.util.Locale.ROOT,
                    "%s rank=%d S=%.4f cost=%.0f len=%.1f parts=%d chambers=%d ties=%d unconnected=%s  [%s]%n",
                    v.id, v.rank, v.score, v.calculatedCost, v.length, v.parts, v.chambers.size(), v.tieInCount,
                    v.unconnected, v.strategy);
            for (String c : v.checks) {
                System.out.println("    - " + c);
            }
        }
    }
}
