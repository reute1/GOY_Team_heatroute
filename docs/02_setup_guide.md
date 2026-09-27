# Как развернуть проект у себя

Пошаговое руководство: от распаковки архива до работающего сервиса с картой. Что и как устроено внутри — в [01_project_overview.md](01_project_overview.md).

Есть два способа:

- **А. Через Docker** — рекомендуемый. Нужен только Docker, всё остальное (Java, Maven, PostgreSQL) поднимается внутри контейнеров. Так же сервис будет разворачиваться у жюри.
- **Б. Без Docker, для разработки** — нужны JDK и Maven, PostgreSQL всё равно удобнее поднять в Docker.

Плюс отдельно: запуск только ядра расчёта из командной строки (без сервиса и базы) и Python-инструменты для проверки результата.

---

## 0. Что в архиве

```
heatroute/          проект (с историей git в .git)
  docs/             эти документы
  data/             конкурсный набор и копия с дорогой, газопроводом и кабелем
  results/          готовые результаты и автономная карта results/viewer.html
  ...
tz_materials/       исходные документы кейса (описание, техприложение, разъяснения)
```

Посмотреть результаты можно вообще без установки: откройте `heatroute/results/viewer.html` в браузере.

Распакуйте архив в папку **без пробелов и кириллицы в пути**, если можно (например `C:\dev\heatroute` или `~/dev/heatroute`). С кириллицей всё работает, но некоторые инструменты выводят путь «кракозябрами», это сбивает с толку.

---

## А. Запуск через Docker (рекомендуется)

### А1. Установить Docker

**Windows 10/11**
1. Установите **Docker Desktop**: <https://www.docker.com/products/docker-desktop/> или из PowerShell:
   ```powershell
   winget install --id Docker.DockerDesktop -e
   ```
   Установщик попросит права администратора (окно «Разрешить изменения?» — «Да»).
2. Docker Desktop работает поверх WSL 2. Если WSL не установлен, установщик предложит его поставить; иногда нужна перезагрузка.
3. **Запустите Docker Desktop** (из меню «Пуск») и дождитесь, пока внизу слева он покажет, что движок запущен (Engine running). Без запущенного Docker Desktop команды `docker` не работают — это самая частая причина ошибок.
4. Проверка в PowerShell:
   ```powershell
   docker version
   docker compose version
   ```

**Ubuntu 22.04** (так же, как на сервере жюри)
```bash
sudo apt-get update
sudo apt-get install -y docker.io
sudo systemctl enable --now docker
sudo usermod -aG docker $USER        # затем перелогиньтесь, чтобы работать без sudo
# docker-compose 1.29.2 — версия из ТЗ:
sudo curl -L "https://github.com/docker/compose/releases/download/1.29.2/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
docker-compose version
```

> Команды `docker compose ...` (новый Compose v2, есть в Docker Desktop) и `docker-compose ...` (старый 1.29.2 из ТЗ) взаимозаменяемы. Ниже используется `docker compose`; на сервере с 1.29.2 пишите через дефис. Мы проверяли запуск на Docker Desktop с Compose v2; на docker-compose 1.29.2 формат файла совместим, но вживую не проверялся — это стоит сделать до сдачи.

### А2. Собрать и запустить

Из папки `heatroute` (где лежит `docker-compose.yml`):

```bash
docker compose up -d --build
```

Первый раз это займёт несколько минут: скачиваются образы (PostgreSQL, Maven, Java) и зависимости Spring, затем собирается проект и **прогоняется тест** на конкурсном наборе (~1–1,5 мин). Следующие сборки быстрее — зависимости кэшируются.

Проверка:

```bash
docker compose ps
```

Оба сервиса должны быть в состоянии `Up`, база — `(healthy)`:

```
heatroute-app-1   Up ...   0.0.0.0:8080->8080/tcp
heatroute-db-1    Up ... (healthy)
```

Откройте:

- **Интерфейс с картой:** <http://localhost:8080/>
- **Swagger (документация API):** <http://localhost:8080/swagger-ui.html>

Логи приложения, если что-то пошло не так:

```bash
docker compose logs app --tail 100
```

### А3. Первый расчёт через интерфейс

1. <http://localhost:8080/> → блок «Расчёт на сервере» → «Выберите файл» → `heatroute/data/dataset.geojson`.
2. «Загрузить и рассчитать». Статус обновляется сам; конкурсный набор считается **около 1,5 минут** в Docker.
3. После расчёта слева появятся три варианта (по возрастанию S), справа — карта. Клик по варианту показывает его сеть. Клик по трубе или камере — все её атрибуты (расход, ДУ, длина, стоимость, способ прокладки).
4. В карточке варианта: стоимость, длина, камеры, таблица «Предельная длина» и список проверок.
5. «Скачать результат GeoJSON» — готовый файл по формату ТЗ.

Чтобы увидеть спецпроходы (дорога, газопровод, кабель), загрузите `heatroute/data/dataset_with_crossings.geojson`. В конкурсном наборе таких объектов нет.

Прошлые расчёты сохраняются в базе и доступны в списке «предыдущие задачи».

### А4. Работа через API

| Метод | Путь | Что делает |
|---|---|---|
| POST | `/api/jobs` (multipart, поле `file`) | загрузить GeoJSON (до 3 ГБ) и запустить расчёт, вернёт `id` |
| GET | `/api/jobs/{id}` | статус: QUEUED / RUNNING / DONE / FAILED |
| GET | `/api/jobs/{id}/summary` | сводка: варианты, проверки, диагностика данных, статистика |
| GET | `/api/jobs/{id}/result` | результат GeoJSON |
| GET | `/api/jobs/{id}/input` | исходный файл |
| GET | `/api/jobs` | последние 100 задач |

Linux / macOS / Git Bash:

```bash
curl -F "file=@data/dataset.geojson" http://localhost:8080/api/jobs
# → {"id":"190d2666-...","status":"RUNNING",...}
curl http://localhost:8080/api/jobs/<id>                 # повторять, пока не DONE
curl -o result.geojson http://localhost:8080/api/jobs/<id>/result
```

Windows PowerShell (именно `curl.exe`, а не `curl` — в PowerShell 5 `curl` это другая команда):

```powershell
curl.exe -F "file=@data/dataset.geojson" http://localhost:8080/api/jobs
curl.exe http://localhost:8080/api/jobs/<id>
curl.exe -o result.geojson http://localhost:8080/api/jobs/<id>/result
```

### А5. Остановить, обновить, почистить

```bash
docker compose stop                 # остановить (данные сохраняются)
docker compose start                # запустить снова
docker compose up -d --build        # пересобрать после изменений в коде
docker compose down                 # удалить контейнеры (данные в томах сохраняются)
docker compose down -v              # удалить контейнеры И данные: базу задач и файлы расчётов
```

После перезагрузки компьютера на Windows сначала запустите Docker Desktop. Контейнеры сами поднимутся (`restart: unless-stopped`).

### А6. Память

В `docker-compose.yml` для приложения задано `JAVA_OPTS: -Xmx12g` — под сервер жюри с 16 ГБ. Это верхний предел, а не резерв: на ноутбуке с 8 ГБ конкурсный набор тоже считается (ему хватает 1 ГБ). Для совсем слабой машины поменяйте на `-Xmx4g`.

В Docker Desktop память виртуальной машины ограничена настройками WSL; если большие файлы падают с `OutOfMemoryError`, увеличьте лимит в `%UserProfile%\.wslconfig`.

---

## Б. Разработка без Docker

### Б1. Что поставить

- **JDK 11 или новее.** Проект собирается под Java 11 (`maven.compiler.release=11`), так что подойдёт и JDK 17. Мы работали на Eclipse Temurin 17:
  - Windows: `winget install --id EclipseAdoptium.Temurin.17.JDK -e`
  - Ubuntu: `sudo apt-get install -y openjdk-17-jdk`
- **Maven 3.6+.**
  - Windows: скачать zip с <https://maven.apache.org/download.cgi>, распаковать, добавить папку `bin` в `PATH` (в winget Maven нет).
  - Ubuntu: `sudo apt-get install -y maven`
- **Git** (для работы с историей): Windows — `winget install --id Git.Git -e`.
- **Python 3.8+** — для инструментов проверки (раздел Г).

Проверка: `java -version`, `javac -version`, `mvn -version` — везде Java 11+.

### Б2. Сборка и тесты

```bash
mvn package                  # сборка + тест на конкурсном наборе (~1–1,5 мин)
mvn package -DskipTests      # быстрая сборка без теста
```

Результат — `target/heatroute.jar`.

### Б3. База данных для локального запуска

Сервису нужен PostgreSQL. Проще всего поднять его в Docker на порту **5433**:

```bash
docker run -d --name heatroute-devdb -p 5433:5432 \
  -e POSTGRES_DB=heatroute -e POSTGRES_USER=heatroute -e POSTGRES_PASSWORD=heatroute \
  postgres:16
```

(В PowerShell перенос строки — обратная кавычка `` ` `` вместо `\`, или напишите всё в одну строку.)

> **Почему 5433, а не 5432.** Если на компьютере уже установлен свой PostgreSQL, он занимает порт 5432, и приложение подключится к нему вместо контейнера — с ошибкой вида `password authentication failed for user "heatroute"`. Мы на это наткнулись. Порт 5433 обходит конфликт.

Таблица `jobs` создаётся автоматически при старте приложения (`schema.sql`).

### Б4. Запуск

Linux / macOS:
```bash
export DB_URL=jdbc:postgresql://localhost:5433/heatroute
java -jar target/heatroute.jar
```

Windows PowerShell:
```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5433/heatroute"
java -jar target/heatroute.jar
```

Сервис поднимется на <http://localhost:8080>. Если там уже работает Docker-версия — остановите её (`docker compose stop`) или запустите на другом порту: `java -jar target/heatroute.jar --server.port=8081`.

Файлы задач пишутся в `./jobs` (папка в `.gitignore`). Все настройки — в `src/main/resources/application.yml` и переменных окружения, см. раздел 13 описания проекта.

Остановить базу: `docker stop heatroute-devdb`, удалить: `docker rm heatroute-devdb`.

---

## В. Только ядро расчёта (без сервиса и базы)

Удобно для экспериментов с алгоритмом: вход — файл, выход — файл, в консоль — статистика, проверки и диагностика.

После `mvn package` (или `mvn compile`):

```bash
java -Xmx4g -cp target/classes ru.lct.heatroute.core.HeatRouteEngine data/dataset.geojson result.geojson
```

Без Maven, одним `javac` (ядро не зависит ни от каких библиотек):

Linux / Git Bash:
```bash
javac --release 11 -encoding UTF-8 -d out $(find src/main/java/ru/lct/heatroute/core -name '*.java')
java -Xmx4g -cp out ru.lct.heatroute.core.HeatRouteEngine data/dataset.geojson result.geojson
```

Windows PowerShell:
```powershell
javac --release 11 -encoding UTF-8 -d out (Get-ChildItem src/main/java/ru/lct/heatroute/core -Recurse -Filter *.java | ForEach-Object FullName)
java -Xmx4g -cp out ru.lct.heatroute.core.HeatRouteEngine data/dataset.geojson result.geojson
```

Конкурсный набор считается около минуты. На Windows русский текст в консоли может выводиться «кракозябрами» — это кодировка консоли, сам файл результата в UTF-8 и корректен. Помогает `java "-Dsun.stdout.encoding=UTF-8" ...` в PowerShell после `[Console]::OutputEncoding = [Text.Encoding]::UTF8`.

---

## Г. Python-инструменты

Нужен Python 3.8+. `validate.py`, `make_big.py`, `build_viewer.py` работают без сторонних библиотек; для `plot.py` нужен matplotlib (`pip install matplotlib`). На Windows вместо `python3` пишите `python`.

```bash
# Проверить результат по правилам ТЗ (независимо от кода сервиса)
python3 tools/validate.py data/dataset.geojson result.geojson
# → v1: rank=1 S=12.4229 линий=27 камер=13 тех.узлов=0 — OK  (и т.д.)

# Картинка одного варианта
python3 tools/plot.py data/dataset.geojson result.geojson v1 v1.png

# Автономная HTML-карта (открывается без сервера); можно передать несколько наборов тройками
python3 tools/build_viewer.py viewer.html data/dataset.geojson result.geojson "Конкурсный набор"

# Стресс-набор: конкурсный, размноженный сеткой 4×4 (шаг 2200 м)
python3 tools/make_big.py data/dataset.geojson 4 big.geojson
# «Город»: 20×20 копий с шагом 5 км, здания только в одной — результат должен совпасть с конкурсным
python3 tools/make_big.py data/dataset.geojson 20 city.geojson 5000 --city
```

---

## Д. Работа с git

Архив содержит репозиторий с историей (папка `.git`). Посмотреть: `git log --oneline`.

- В `.gitattributes` закреплены переводы строк LF для всех текстовых файлов: сервис разворачивается на Ubuntu, а файлы с CRLF ломают shell-скрипты в контейнерах. Не отключайте это. Предупреждения git на Windows вида «CRLF will be replaced by LF» — нормальны.
- В `.gitignore`: `target/`, `jobs/`, `__pycache__/`, файлы IDE.
- Если заводите общий удалённый репозиторий (GitHub/GitLab): `git remote add origin <url>` и `git push -u origin master`.

---

## Е. Если что-то не работает

| Симптом | Причина и решение |
|---|---|
| `failed to connect to the docker API ... dockerDesktopLinuxEngine` | Не запущен Docker Desktop. Запустите его и дождитесь «Engine running». |
| `docker compose up` падает на шаге `mvn package` | Упал тест или нет доступа к Maven Central. Посмотрите вывод сборки; при проблемах с сетью повторите позже. |
| Порт 8080 занят (`port is already allocated`) | Там уже что-то работает — возможно, вторая копия сервиса. Остановите её или поменяйте порт в `docker-compose.yml`: `"8090:8080"`. |
| `password authentication failed for user "heatroute"` при локальном запуске | Приложение подключилось к вашему собственному PostgreSQL на 5432. Поднимите базу на 5433 и задайте `DB_URL` (раздел Б3). |
| Python-скрипт падает с `UnicodeDecodeError: 'charmap'` | Старая версия скриптов без явной UTF-8. В актуальной версии исправлено — обновитесь. |
| Расчёт «висит» в RUNNING | Конкурсный набор в Docker считается ~1,5 мин, большие наборы — дольше: у каждого варианта до 60 с на улучшение. Смотрите `docker compose logs app`. |
| Задача FAILED «Сервис был перезапущен во время расчёта» | Контейнер перезапускался посреди расчёта. Загрузите файл заново. |
| На карте не видно исходных данных для большого файла | Интерфейс не загружает исходный файл больше 80 МБ, показывает только результат. Это ограничение браузера, не ошибка. |
| `OutOfMemoryError` на большом файле | Увеличьте `-Xmx` в `JAVA_OPTS` и лимит памяти WSL/Docker (раздел А6). |
| Русский текст в консоли нечитаем | Кодировка консоли Windows; на файлы не влияет (раздел В). |
