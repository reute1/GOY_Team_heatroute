package ru.lct.heatroute.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Результат расчёта: варианты подключения в метрической системе координат. */
public final class PlanResult {

    public static final class Line {
        public String id;
        public Object startNodeId;
        public Object endNodeId;
        public double flow;
        public int dn;
        public double length;
        public String layingMethod;
        public double kSpecial = 1;
        public double cost;
        public double[] coords;
    }

    public static final class Node {
        public String id;
        public String kind;
        public double x;
        public double y;
        public int dn;
        public double cost;
    }

    public static final class Variant {
        public String id;
        public String strategy;
        public int rank;
        public final List<Line> lines = new ArrayList<>();
        public final List<Node> chambers = new ArrayList<>();
        public final List<Node> technicalNodes = new ArrayList<>();
        public final List<Object> unconnected = new ArrayList<>();
        public double constructionCost;
        public double chamberCost;
        public int tieInCount;
        public double tieInCost;
        public double penalty;
        public double calculatedCost;
        public double length;
        public double score;
        /** Сколько отдельных частей новой сети (мест присоединения). */
        public int parts;
        public final List<String> checks = new ArrayList<>();
        /** Проверка предельной длины: по каждому ДУ наибольшая серия участков и предел. */
        public final List<Map<String, Object>> lengthCheck = new ArrayList<>();
        public String signature;
    }

    public final List<Variant> variants = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final Map<String, Object> stats = new LinkedHashMap<>();
}
