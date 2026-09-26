package ru.lct.heatroute.core.io;

import ru.lct.heatroute.core.geo.Geo;
import ru.lct.heatroute.core.geo.Utm;
import ru.lct.heatroute.core.json.Json;
import ru.lct.heatroute.core.model.InputData;
import ru.lct.heatroute.core.model.RuleBook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    public InputData read(InputStream in) throws IOException {
        InputData data = new InputData();
        Json.Parser p = new Json.Parser(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16));
        Map<String, Object> top = p.streamFeatures(f -> {
            data.featureCount++;
            try {
                accept(data, f);
            } catch (RuntimeException e) {
                data.warnings.add("Объект #" + data.featureCount + " пропущен: " + e.getMessage());
            }
        });
        Object crs = top.get("crs");
        if (crs != null && !String.valueOf(crs).contains("4326") && !String.valueOf(crs).contains("CRS84")) {
            data.warnings.add("CRS входного файла не WGS 84: " + crs + " — координаты интерпретируются как lon/lat");
        }
        postProcess(data);
        return data;
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
        for (InputData.Chamber c : d.chambers) {
            int adj = 0;
            double best = Double.MAX_VALUE;
            for (InputData.NetLine nl : d.network) {
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
