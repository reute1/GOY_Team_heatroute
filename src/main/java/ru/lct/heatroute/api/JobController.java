package ru.lct.heatroute.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.lct.heatroute.service.JobService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Расчёт трасс", description = "Загрузка конкурсного набора, запуск расчёта и выгрузка результата")
public class JobController {
    private static final MediaType GEOJSON = MediaType.parseMediaType("application/geo+json");

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Загрузить GeoJSON и запустить расчёт",
            description = "Файл до 3 ГБ сохраняется на диск потоково; расчёт выполняется асинхронно. Возвращает id задачи.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submit(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(jobs.submit(file));
    }

    @Operation(summary = "Список последних задач")
    @GetMapping
    public List<Map<String, Object>> list() {
        return jobs.list();
    }

    @Operation(summary = "Статус задачи")
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        Map<String, Object> j = jobs.get(id);
        return j == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(j);
    }

    @Operation(summary = "Сводка по вариантам, проверки правил и диагностика данных")
    @GetMapping(value = "/{id}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> summary(@PathVariable String id) {
        String s = jobs.summary(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(s);
    }

    @Operation(summary = "Выгрузить результат (GeoJSON, WGS 84)")
    @GetMapping("/{id}/result")
    public ResponseEntity<Resource> result(@PathVariable String id) {
        return file(jobs.resultPath(id), "result_" + id + ".geojson");
    }

    @Operation(summary = "Исходный загруженный файл (для отображения на карте)")
    @GetMapping("/{id}/input")
    public ResponseEntity<Resource> input(@PathVariable String id) {
        return file(jobs.inputPath(id), "input_" + id + ".geojson");
    }

    private static ResponseEntity<Resource> file(Path p, String name) {
        if (!Files.exists(p)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(GEOJSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new FileSystemResource(p));
    }
}
