package ru.lct.heatroute.core.io;

import ru.lct.heatroute.core.geo.Utm;
import ru.lct.heatroute.core.json.Json;
import ru.lct.heatroute.core.route.PlanResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Запись результата в GeoJSON (WGS 84) по разделу 7 технического приложения. Пишется потоково. */
public final class OutputWriter {
    private final Utm utm;

    public OutputWriter(Utm utm) {
        this.utm = utm;
    }

    public void write(PlanResult r, OutputStream os) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 1 << 16);
        w.write("{\"type\":\"FeatureCollection\",\"name\":\"heat_connection_result\",\"features\":[\n");
        boolean first = true;
        for (PlanResult.Variant v : r.variants) {
            for (PlanResult.Line l : v.lines) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", l.id);
                p.put("object_type", "heat_network");
                p.put("variant_id", v.id);
                p.put("start_node_id", l.startNodeId);
                p.put("end_node_id", l.endNodeId);
                p.put("flow_tph", r3(l.flow));
                p.put("diameter", l.dn);
                p.put("length", r3(l.length));
                p.put("laying_method", l.layingMethod);
                p.put("depth_start", null);
                p.put("depth_end", null);
                p.put("cost", r2(l.cost));
                p.put("k_special", l.kSpecial);
                first = feature(w, first, p, line(l.coords));
            }
            for (PlanResult.Node n : v.chambers) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", n.id);
                p.put("object_type", "heat_chamber");
                p.put("variant_id", v.id);
                p.put("diameter", n.dn);
                p.put("cost", r2(n.cost));
                p.put("chamber_role", n.kind);
                first = feature(w, first, p, point(n.x, n.y));
            }
            for (PlanResult.Node n : v.technicalNodes) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", n.id);
                p.put("object_type", "technical_node");
                p.put("variant_id", v.id);
                first = feature(w, first, p, point(n.x, n.y));
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", v.id + "_summary");
            s.put("object_type", "variant_summary");
            s.put("variant_id", v.id);
            s.put("rank", v.rank);
            s.put("construction_cost", r2(v.constructionCost));
            s.put("chamber_construction_cost", r2(v.chamberCost));
            s.put("existing_chamber_tie_in_count", v.tieInCount);
            s.put("existing_chamber_tie_in_cost", r2(v.tieInCost));
            s.put("unconnected_penalty", r2(v.penalty));
            s.put("calculated_cost", r2(v.calculatedCost));
            s.put("new_network_length", r3(v.length));
            s.put("score", Math.round(v.score * 1e6) / 1e6);
            s.put("unconnected_oks_ids", new ArrayList<>(v.unconnected));
            s.put("strategy", v.strategy);
            s.put("network_parts", v.parts);
            s.put("checks", v.checks);
            s.put("dn_length_check", v.lengthCheck);
            first = feature(w, first, s, null);
        }
        w.write("\n]}\n");
        w.flush();
    }

    private boolean feature(Writer w, boolean first, Map<String, Object> props, Object geom) throws IOException {
        if (!first) {
            w.write(",\n");
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        f.put("geometry", geom);
        Json.write(w, f);
        return false;
    }

    private Map<String, Object> point(double x, double y) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", "Point");
        g.put("coordinates", utm.inverse(x, y));
        return g;
    }

    private Map<String, Object> line(double[] c) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i + 1 < c.length; i += 2) {
            pts.add(utm.inverse(c[i], c[i + 1]));
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", "LineString");
        g.put("coordinates", pts);
        return g;
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
