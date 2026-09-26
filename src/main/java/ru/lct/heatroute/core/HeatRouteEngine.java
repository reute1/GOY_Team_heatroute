package ru.lct.heatroute.core;

import ru.lct.heatroute.core.geo.Utm;
import ru.lct.heatroute.core.io.InputReader;
import ru.lct.heatroute.core.io.OutputWriter;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;
import ru.lct.heatroute.core.route.PlanResult;
import ru.lct.heatroute.core.route.Planner;

import java.io.BufferedInputStream;
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

    public PlanResult process(InputStream in, OutputStream out) throws IOException {
        long t0 = System.currentTimeMillis();
        InputData data = new InputReader(utm, rules).read(in);
        long tRead = System.currentTimeMillis() - t0;
        PlanResult r = new Planner(data, rules, options).run();
        r.warnings.addAll(0, data.warnings);
        r.stats.put("input_features", data.featureCount);
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
        try (InputStream is = new BufferedInputStream(Files.newInputStream(in));
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            r = new HeatRouteEngine().process(is, os);
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
