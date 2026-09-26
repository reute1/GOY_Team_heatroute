package ru.lct.heatroute.core;

import org.junit.jupiter.api.Test;
import ru.lct.heatroute.core.route.PlanResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Прогон ядра на конкурсном наборе: все точки подключены, правила соблюдены. */
class EngineTest {

    @Test
    void competitionDataset() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get("data/dataset.geojson"))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PlanResult r = new HeatRouteEngine().process(in, out);
            assertFalse(r.variants.isEmpty());
            PlanResult.Variant best = r.variants.get(0);
            assertEquals(1, best.rank);
            assertTrue(best.unconnected.isEmpty(), "все точки должны быть подключены");
            for (String c : best.checks) {
                assertFalse(c.contains("Превышена") || c.matches(".*нарушений [1-9].*") || c.contains("Повороты >"), c);
            }
            assertTrue(out.size() > 0);
        }
    }
}
