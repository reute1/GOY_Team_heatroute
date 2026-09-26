package ru.lct.heatroute;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Сервис моделирования трасс подключения к тепловым сетям",
        version = "0.1.0",
        description = "Загрузка GeoJSON → автоматическое построение вариантов подключения перспективных ОКС → выгрузка GeoJSON"))
public class HeatRouteApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeatRouteApplication.class, args);
    }
}
