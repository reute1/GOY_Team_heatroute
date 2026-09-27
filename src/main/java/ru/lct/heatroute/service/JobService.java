package ru.lct.heatroute.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.core.HeatRouteEngine;
import ru.lct.heatroute.core.json.Json;
import ru.lct.heatroute.core.model.RuleBook;
import ru.lct.heatroute.core.route.PlanResult;
import ru.lct.heatroute.core.route.Planner;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Асинхронные задачи расчёта. Входной файл сохраняется на диск потоково (до 3 ГБ),
 * статус и сводка хранятся в PostgreSQL, результат — файлом на диске.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JdbcTemplate jdbc;
    private final Path storage;
    private final ExecutorService pool;
    private final Planner.Options options = new Planner.Options();

    public JobService(JdbcTemplate jdbc,
                      @Value("${app.storage-dir:/data/jobs}") String storageDir,
                      @Value("${app.workers:2}") int workers,
                      @Value("${app.max-edge:700}") double maxEdge,
                      @Value("${app.tie-search-radius:600}") double tieRadius) {
        this.jdbc = jdbc;
        this.storage = Paths.get(storageDir);
        this.pool = Executors.newFixedThreadPool(Math.max(1, workers));
        options.maxEdge = maxEdge;
        options.tieSearchRadius = tieRadius;
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(storage);
        int n = jdbc.update("UPDATE jobs SET status='FAILED', error='Сервис был перезапущен во время расчёта', finished_at=now() "
                + "WHERE status IN ('QUEUED','RUNNING')");
        if (n > 0) {
            log.warn("Помечено прерванных задач: {}", n);
        }
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    public Map<String, Object> submit(MultipartFile file) throws IOException {
        String id = UUID.randomUUID().toString();
        Path dir = storage.resolve(id);
        Files.createDirectories(dir);
        Path input = dir.resolve("input.geojson");
        file.transferTo(input);
        jdbc.update("INSERT INTO jobs(id, status, file_name, file_size, created_at) VALUES (?,?,?,?,?)",
                id, "QUEUED", file.getOriginalFilename(), Files.size(input), Timestamp.from(Instant.now()));
        pool.submit(() -> run(id));
        return get(id);
    }

    private void run(String id) {
        Path dir = storage.resolve(id);
        jdbc.update("UPDATE jobs SET status='RUNNING', started_at=? WHERE id=?", Timestamp.from(Instant.now()), id);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(dir.resolve("result.geojson")), 1 << 16)) {
            PlanResult r = new HeatRouteEngine(RuleBook.defaults(), options).process(dir.resolve("input.geojson"), out);
            jdbc.update("UPDATE jobs SET status='DONE', finished_at=?, summary=? WHERE id=?",
                    Timestamp.from(Instant.now()), summaryJson(r), id);
        } catch (Throwable e) {
            log.error("Задача {} завершилась с ошибкой", id, e);
            jdbc.update("UPDATE jobs SET status='FAILED', finished_at=?, error=? WHERE id=?",
                    Timestamp.from(Instant.now()), String.valueOf(e.getMessage()), id);
        }
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status, file_name, file_size, created_at, started_at, finished_at, error FROM jobs WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String summary(String id) {
        List<String> s = jdbc.queryForList("SELECT summary FROM jobs WHERE id=?", String.class, id);
        return s.isEmpty() ? null : s.get(0);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, status, file_name, file_size, created_at, finished_at "
                + "FROM jobs ORDER BY created_at DESC LIMIT 100");
    }

    public Path resultPath(String id) {
        return storage.resolve(id).resolve("result.geojson");
    }

    public Path inputPath(String id) {
        return storage.resolve(id).resolve("input.geojson");
    }

    /** Сводка в JSON: варианты, проверки, диагностика входных данных, статистика расчёта. */
    static String summaryJson(PlanResult r) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stats", r.stats);
        m.put("warnings", r.warnings);
        List<Object> vs = new ArrayList<>();
        for (PlanResult.Variant v : r.variants) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("variant_id", v.id);
            x.put("rank", v.rank);
            x.put("strategy", v.strategy);
            x.put("score", v.score);
            x.put("calculated_cost", v.calculatedCost);
            x.put("construction_cost", v.constructionCost);
            x.put("chamber_construction_cost", v.chamberCost);
            x.put("existing_chamber_tie_in_count", v.tieInCount);
            x.put("unconnected_penalty", v.penalty);
            x.put("new_network_length", v.length);
            x.put("new_chambers", v.chambers.size());
            x.put("technical_nodes", v.technicalNodes.size());
            x.put("network_parts", v.parts);
            x.put("unconnected_oks_ids", v.unconnected);
            x.put("checks", v.checks);
            vs.add(x);
        }
        m.put("variants", vs);
        StringWriter w = new StringWriter();
        Json.write(w, m);
        return w.toString();
    }
}
