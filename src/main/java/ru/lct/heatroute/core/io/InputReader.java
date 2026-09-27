package ru.lct.heatroute.core.io;

import ru.lct.heatroute.core.geo.Geo;
import ru.lct.heatroute.core.geo.Utm;
import ru.lct.heatroute.core.json.Json;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;
import ru.lct.heatroute.core.model.SearchArea;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Потоковое чтение входного GeoJSON: объекты разбираются по одному и сразу
 * переводятся в UTM, файл целиком в памяти не хранится.
 */
public final class InputReader {

    private final Utm utm;
    private final RuleBook rules;

    public InputReader(Utm utm, RuleBook rules) {
        this.utm = utm;
        this.rules = rules;
    }

    /** Однопроходное чтение потока: все объекты сохраняются. */
    public InputData read(InputStream in) throws IOException {
        InputData data = new InputData();
        pass(in, data, null, 0);
        postProcess(data);
        return data;
    }

    /**
     * Двухпроходное чтение файла (для больших наборов). Проход 1: точки подключения, сеть, камеры —
     * по ним строится область поиска. Проход 2: только ограничения, касающиеся области, расширенной
     * на {@code extra} м; сеть и камеры дальше области тоже отбрасываются. В памяти остаётся лишь
     * окрестность подключаемых объектов, а не весь город.
     */
    public InputData read(Path file, double extra) throws IOException {
        InputData data = new InputData();
        try (InputStream in = Files.newInputStream(file)) {
            pass(in, data, false, 0);
        }
        SearchArea area = SearchArea.build(data.targets, data.network);
        data.area = area;
        long before = data.network.size() + data.chambers.size();
        data.network.removeIf(nl -> {
            double[] b = bbox(nl.coords);
            return !area.touches(b[0], b[1], b[2], b[3], extra + 1);
        });
        data.chambers.removeIf(c -> !area.touches(c.x, c.y, c.x, c.y, extra));
        data.skippedOutsideArea = before - data.network.size() - data.chambers.size();
        try (InputStream in = Files.newInputStream(file)) {
            pass(in, data, true, extra);
        }
        for (int i = 0; i < data.restrictions.size(); i++) {
            data.restrictions.get(i).index = i;
        }
        if (data.skippedOutsideArea > 0) {
            data.warnings.add("Вне области поиска трасс (+" + Math.round(extra) + " м) отброшено объектов: "
                    + data.skippedOutsideArea + " — на расчёт они повлиять не могут");
        }
        postProcess(data);
        return data;
    }

    /**
     * @param restrictions null — все объекты; false — всё, кроме ограничений; true — только ограничения,
     *                     касающиеся области поиска, расширенной на extra
     */
    private void pass(InputStream in, InputData data, Boolean restrictions, double extra) throws IOException {
        long[] seq = {0};
        Json.Parser p = new Json.Parser(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16));
        Map<String, Object> top = p.streamFeatures(f -> {
            seq[0]++;
            if (restrictions != null && restrictions != isRestriction(f)) {
                return;
            }
            if (!Boolean.TRUE.equals(restrictions)) {
                data.featureCount++;
            }
            int n = data.restrictions.size();
            int w = data.warnings.size();
            try {
                accept(data, f);
            } catch (RuntimeException e) {
                data.warnings.add("Объект #" + seq[0] + " пропущен: " + e.getMessage());
            }
            if (Boolean.TRUE.equals(restrictions)) {
                data.featureCount++;
                if (data.restrictions.size() > n) {
                    double[] b = bbox(data.restrictions.get(n).shape);
                    if (!data.area.touches(b[0], b[1], b[2], b[3], extra)) {
                        data.restrictions.remove(n);
                        data.skippedOutsideArea++;
                        while (data.warnings.size() > w) {
                            data.warnings.remove(data.warnings.size() - 1); // диагностика только по нужным объектам
                        }
                    }
                }
            }
        });
        Object crs = top.get("crs");
        if (restrictions != Boolean.TRUE && crs != null
                && !String.valueOf(crs).contains("4326") && !String.valueOf(crs).contains("CRS84")) {
            data.warnings.add("CRS входного файла не WGS 84: " + crs + " — координаты интерпретируются как lon/lat");
        }
    }

    @SuppressWarnings("unchecked")
    private void accept(InputData d, Map<String, Object> f) {
        Map<String, Object> props = (Map<String, Object>) f.get("properties");
        Map<String, Object> geom = (Map<String, Object>) f.get("geometry");
        if (props == null) {
            d.warnings.add("Объект #" + d.featureCount + " без properties — пропущен");
            return;
        }
        Object id = props.get("id");
        String type = str(props.get("object_type"));
        if (type == null) {
            d.warnings.add("Объект id=" + id + " без object_type — пропущен");
            return;
        }
        if (id == null) {
            d.warnings.add("Объект типа " + type + " без id — пропущен");
            return;
        }
        if (geom == null) {
            d.warnings.add("Объект id=" + id + " без геометрии — пропущен");
            return;
        }
        String gt = str(geom.get("type"));
        Object coords = geom.get("coordinates");
        switch (type.toLowerCase(Locale.ROOT)) {
            case "source": {
                double[] pt = point(coords);
                InputData.Source s = new InputData.Source();
                s.id = id;
                s.x = pt[0];
                s.y = pt[1];
                d.sources.add(s);
                break;
            }
            case "heat_network": {
                Number dn = num(props.get("diameter"));
                if (dn == null) {
                    d.warnings.add("heat_network id=" + id + " без diameter — пропущен");
                    return;
                }
                for (double[] line : lines(gt, coords)) {
                    InputData.NetLine nl = new InputData.NetLine();
                    nl.id = id;
                    nl.dn = dn.intValue();
                    nl.coords = line;
                    d.network.add(nl);
                }
                break;
            }
            case "heat_chamber": {
                double[] pt = point(coords);
                InputData.Chamber c = new InputData.Chamber();
                c.id = id;
                c.x = pt[0];
                c.y = pt[1];
                d.chambers.add(c);
                break;
            }
            case "oks_connection_point": {
                Number fl = num(props.get("flow_tph"));
                if (fl == null || fl.doubleValue() < 0) {
                    d.warnings.add("oks_connection_point id=" + id + " без корректного flow_tph — пропущен");
                    return;
                }
                double[] pt = point(coords);
                InputData.Target t = new InputData.Target();
                t.id = id;
                t.flow = fl.doubleValue();
                t.x = pt[0];
                t.y = pt[1];
                t.index = d.targets.size();
                d.targets.add(t);
                break;
            }
            case "restriction": {
                String rt = str(props.get("restriction_type"));
                InputData.Restriction r = new InputData.Restriction();
                r.id = id;
                r.type = rt == null ? "unknown" : rt.trim().toLowerCase(Locale.ROOT);
                r.rule = rules.ruleFor(rt);
                if (rt == null || !rules.restrictions.containsKey(r.type)) {
                    d.warnings.add("restriction id=" + id + ": тип '" + rt + "' не описан в таблице 2 — учтён как запрет с отступом "
                            + r.rule.clearance + " м");
                }
                r.shape = shape(gt, coords);
                if (r.shape.isEmpty()) {
                    d.warnings.add("restriction id=" + id + ": пустая геометрия — пропущено");
                    return;
                }
                r.index = d.restrictions.size();
                d.restrictions.add(r);
                break;
            }
            default:
                d.warnings.add("Неизвестный object_type '" + type + "' (id=" + id + ") — пропущен");
        }
    }

    private void postProcess(InputData d) {
        if (d.sources.isEmpty()) {
            d.warnings.add("Во входных данных нет источника (source)");
        }
        if (d.network.isEmpty()) {
            d.warnings.add("Во входных данных нет существующей тепловой сети (heat_network)");
        }
        // Число примыканий существующих линий к каждой камере.
        List<double[]> boxes = new ArrayList<>();
        for (InputData.NetLine nl : d.network) {
            boxes.add(bbox(nl.coords));
        }
        for (InputData.Chamber c : d.chambers) {
            int adj = 0;
            double best = Double.MAX_VALUE;
            for (int li = 0; li < d.network.size(); li++) {
                double[] bb = boxes.get(li);
                double far = Math.max(Math.max(bb[0] - c.x, c.x - bb[2]), Math.max(bb[1] - c.y, c.y - bb[3]));
                if (far > 0.5 && far >= best) {
                    continue; // линия заведомо дальше 0,5 м и не ближе уже найденной
                }
                InputData.NetLine nl = d.network.get(li);
                double[] a = nl.coords;
                int n = a.length / 2;
                if (Geo.dist(c.x, c.y, a[0], a[1]) < 0.5) {
                    adj++;
                    best = 0;
                    continue;
                }
                if (Geo.dist(c.x, c.y, a[2 * n - 2], a[2 * n - 1]) < 0.5) {
                    adj++;
                    best = 0;
                    continue;
                }
                for (int i = 0; i + 1 < n; i++) {
                    double dd = Geo.pointSegDist(c.x, c.y, a[2 * i], a[2 * i + 1], a[2 * i + 2], a[2 * i + 3]);
                    best = Math.min(best, dd);
                    if (dd < 0.5) {
                        adj += 2; // линия проходит через камеру — два примыкания
                        break;
                    }
                }
            }
            c.adjacency = adj;
            if (best > 0.5) {
                c.adjacency = -1; // камера не лежит на существующей сети — для врезки не используется
                d.warnings.add("heat_chamber id=" + c.id + " не лежит на существующей сети (" +
                        String.format(Locale.ROOT, "%.1f", best) + " м) — не используется для врезки");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static boolean isRestriction(Map<String, Object> f) {
        Object props = f.get("properties");
        if (!(props instanceof Map)) {
            return false;
        }
        Object t = ((Map<String, Object>) props).get("object_type");
        return t != null && "restriction".equalsIgnoreCase(t.toString().trim());
    }

    /** Габаритный прямоугольник {minX, minY, maxX, maxY}. */
    private static double[] bbox(double[] c) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i + 1 < c.length; i += 2) {
            b[0] = Math.min(b[0], c[i]);
            b[1] = Math.min(b[1], c[i + 1]);
            b[2] = Math.max(b[2], c[i]);
            b[3] = Math.max(b[3], c[i + 1]);
        }
        return b;
    }

    private static double[] bbox(InputData.Shape s) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        List<double[]> parts = new ArrayList<>(s.lines);
        for (double[][] poly : s.polygons) {
            parts.add(poly[0]);
        }
        for (double[] c : parts) {
            double[] p = bbox(c);
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[2]);
            b[3] = Math.max(b[3], p[3]);
        }
        return b;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Number num(Object o) {
        if (o instanceof Json.Num) {
            return ((Json.Num) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).replace(',', '.').trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private double[] point(Object coords) {
        List<?> c = (List<?>) coords;
        return utm.forward(((Json.Num) c.get(0)).doubleValue(), ((Json.Num) c.get(1)).doubleValue());
    }

    private double[] lineCoords(Object coords, boolean ring) {
        List<?> l = (List<?>) coords;
        double[] out = new double[l.size() * 2];
        int k = 0;
        for (Object o : l) {
            double[] p = point(o);
            if (k >= 2 && Geo.dist(out[k - 2], out[k - 1], p[0], p[1]) < 1e-3) {
                continue;
            }
            out[k++] = p[0];
            out[k++] = p[1];
        }
        if (ring && k >= 4 && Geo.dist(out[0], out[1], out[k - 2], out[k - 1]) < 1e-3) {
            k -= 2;
        }
        double[] r = new double[k];
        System.arraycopy(out, 0, r, 0, k);
        return r;
    }

    private List<double[]> lines(String gt, Object coords) {
        List<double[]> res = new ArrayList<>();
        if ("LineString".equals(gt)) {
            double[] l = lineCoords(coords, false);
            if (l.length >= 4) {
                res.add(l);
            }
        } else if ("MultiLineString".equals(gt)) {
            for (Object o : (List<?>) coords) {
                double[] l = lineCoords(o, false);
                if (l.length >= 4) {
                    res.add(l);
                }
            }
        } else {
            throw new IllegalArgumentException("ожидалась линейная геометрия, получено " + gt);
        }
        return res;
    }

    private double[][] polygon(Object coords) {
        List<?> rings = (List<?>) coords;
        List<double[]> out = new ArrayList<>();
        for (Object r : rings) {
            double[] ring = lineCoords(r, true);
            if (ring.length >= 6) {
                out.add(ring);
            }
        }
        return out.isEmpty() ? null : out.toArray(new double[0][]);
    }

    private InputData.Shape shape(String gt, Object coords) {
        InputData.Shape s = new InputData.Shape();
        if (gt == null) {
            return s;
        }
        switch (gt) {
            case "Polygon": {
                double[][] p = polygon(coords);
                if (p != null) {
                    s.polygons.add(p);
                }
                break;
            }
            case "MultiPolygon":
                for (Object o : (List<?>) coords) {
                    double[][] p = polygon(o);
                    if (p != null) {
                        s.polygons.add(p);
                    }
                }
                break;
            case "LineString":
            case "MultiLineString":
                s.lines.addAll(lines(gt, coords));
                break;
            case "Point": {
                double[] p = point(coords);
                s.lines.add(new double[]{p[0], p[1], p[0] + 0.01, p[1]});
                break;
            }
            default:
                throw new IllegalArgumentException("неподдерживаемая геометрия " + gt);
        }
        return s;
    }
}
