package ru.lct.heatroute.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Справочные параметры и правила из технического приложения ЛЦТ-2026.
 * Все значения сосредоточены здесь — алгоритм не содержит «зашитых» чисел кейса.
 */
public final class RuleBook {

    /** Строка таблицы 1. */
    public static final class Pipe {
        public final int dn;
        public final double capacity;
        public final double maxLength;
        public final double pricePerM;
        public final double width;
        public final double height;

        public Pipe(int dn, double capacity, double maxLength, double pricePerM, double width, double height) {
            this.dn = dn;
            this.capacity = capacity;
            this.maxLength = maxLength;
            this.pricePerM = pricePerM;
            this.width = width;
            this.height = height;
        }
    }

    public enum Kind { PROHIBITED, SPECIAL, IGNORE }

    public enum MarginMode { AROUND_POINT, BEYOND_POLYGON }

    /** Строка таблицы 2. */
    public static final class Restriction {
        public final String type;
        public final Kind kind;
        /** Минимальное горизонтальное расстояние (для oks — зависит от ДУ, см. {@link #clearance(int)}). */
        public final double clearance;
        public final double minAngleDeg;
        public final MarginMode marginMode;
        public final double margin;
        public final double kSpecial;
        /** Собственный расчётный габарит объекта (ширина), м; 0 — нет. */
        public final double ownWidth;

        public Restriction(String type, Kind kind, double clearance, double minAngleDeg,
                           MarginMode marginMode, double margin, double kSpecial, double ownWidth) {
            this.type = type;
            this.kind = kind;
            this.clearance = clearance;
            this.minAngleDeg = minAngleDeg;
            this.marginMode = marginMode;
            this.margin = margin;
            this.kSpecial = kSpecial;
            this.ownWidth = ownWidth;
        }

        public double clearance(int dn) {
            if ("oks".equals(type)) {
                if (dn >= 900) {
                    return 9.0;
                }
                if (dn >= 500) {
                    return 7.0;
                }
                return 5.0;
            }
            return clearance;
        }
    }

    public final List<Pipe> pipes;
    public final Map<String, Restriction> restrictions;
    public final Restriction existingNetwork;
    public final Restriction unknownRestriction;

    public double existingChamberTieInCost = 5_000_000;
    public double tieSnapRadius = 10.0;
    public int maxChamberAdjacency = 4;
    public double maxTurnDeg = 90.0;
    public double penaltyBase = 100_000_000;
    public double penaltyPerTph = 500_000;
    public double scoreCostNorm = 25_000_000;
    public double scoreLengthNorm = 100;
    public double scoreCostWeight = 0.7;
    public double scoreLengthWeight = 0.3;

    private RuleBook(List<Pipe> pipes, Map<String, Restriction> restrictions, Restriction existingNetwork,
                     Restriction unknown) {
        this.pipes = Collections.unmodifiableList(pipes);
        this.restrictions = restrictions;
        this.existingNetwork = existingNetwork;
        this.unknownRestriction = unknown;
    }

    public static RuleBook defaults() {
        List<Pipe> p = new ArrayList<>();
        p.add(new Pipe(50, 3.5, 181, 74023, 0.400, 0.125));
        p.add(new Pipe(65, 8.3, 245, 78631, 0.430, 0.140));
        p.add(new Pipe(80, 13.2, 327, 83530, 0.470, 0.160));
        p.add(new Pipe(100, 22.3, 419, 89748, 0.510, 0.180));
        p.add(new Pipe(125, 40.2, 554, 97275, 0.600, 0.225));
        p.add(new Pipe(150, 65.1, 696, 105507, 0.650, 0.250));
        p.add(new Pipe(200, 152.3, 1042, 120275, 0.880, 0.315));
        p.add(new Pipe(250, 274.9, 1379, 135323, 1.050, 0.400));
        p.add(new Pipe(300, 437.4, 1718, 150022, 1.150, 0.450));
        p.add(new Pipe(400, 943.1, 2477, 190299, 1.370, 0.560));
        p.add(new Pipe(500, 1663.4, 3245, 224137, 1.670, 0.710));
        p.add(new Pipe(600, 2627.7, 4037, 264790, 1.850, 0.800));
        p.add(new Pipe(700, 3735.1, 4775, 324298, 2.050, 0.900));
        p.add(new Pipe(800, 5296.8, 5644, 325996, 2.250, 1.000));
        p.add(new Pipe(900, 7165.0, 6518, 327693, 2.450, 1.100));
        p.add(new Pipe(1000, 9391.8, 7419, 418777, 2.650, 1.200));
        p.add(new Pipe(1200, 15012.8, 9288, 428074, 3.100, 1.425));
        p.add(new Pipe(1400, 22501.9, 11276, 683417, 3.450, 1.600));

        Map<String, Restriction> r = new HashMap<>();
        r.put("oks", new Restriction("oks", Kind.PROHIBITED, 5.0, 0, null, 0, 1, 0));
        for (String t : new String[]{"park", "social_area", "prohibited_site", "water", "railway"}) {
            r.put(t, new Restriction(t, Kind.PROHIBITED, 1.0, 0, null, 0, 1, 0));
        }
        r.put("road", new Restriction("road", Kind.SPECIAL, 1.5, 45, MarginMode.BEYOND_POLYGON, 3.0, 1.60, 0));
        r.put("tram_tracks", new Restriction("tram_tracks", Kind.SPECIAL, 1.5, 45, MarginMode.BEYOND_POLYGON, 3.0, 1.75, 0));
        r.put("gas_pipeline", new Restriction("gas_pipeline", Kind.SPECIAL, 2.0, 0, MarginMode.AROUND_POINT, 2.0, 1.25, 0.40));
        r.put("power_cable", new Restriction("power_cable", Kind.SPECIAL, 2.0, 0, MarginMode.AROUND_POINT, 2.0, 1.15, 0.20));
        Restriction net = new Restriction("heat_network", Kind.SPECIAL, 1.0, 0, MarginMode.AROUND_POINT, 2.0, 1.05, -1);
        Restriction unknown = new Restriction("unknown", Kind.PROHIBITED, 1.0, 0, null, 0, 1, 0);
        return new RuleBook(p, r, net, unknown);
    }

    public Restriction ruleFor(String type) {
        if (type == null) {
            return unknownRestriction;
        }
        Restriction r = restrictions.get(type.trim().toLowerCase(Locale.ROOT));
        return r != null ? r : unknownRestriction;
    }

    /** Минимальный ДУ по пропускной способности; null, если расход превышает таблицу. */
    public Pipe pipeForFlow(double flow) {
        for (Pipe p : pipes) {
            if (p.capacity >= flow - 1e-9) {
                return p;
            }
        }
        return null;
    }

    public Pipe pipeByDn(int dn) {
        for (Pipe p : pipes) {
            if (p.dn == dn) {
                return p;
            }
        }
        return null;
    }

    public Pipe nextPipe(Pipe p) {
        int i = pipes.indexOf(p);
        return i >= 0 && i + 1 < pipes.size() ? pipes.get(i + 1) : null;
    }

    /** Ширина пары для произвольного ДУ существующей сети (ближайший не меньший из таблицы). */
    public double widthForDn(int dn) {
        for (Pipe p : pipes) {
            if (p.dn >= dn) {
                return p.width;
            }
        }
        return pipes.get(pipes.size() - 1).width;
    }

    public double chamberCost(int maxDn) {
        if (maxDn <= 200) {
            return 3_000_000;
        }
        if (maxDn <= 500) {
            return 5_000_000;
        }
        if (maxDn <= 1000) {
            return 8_000_000;
        }
        return 12_000_000;
    }

    public double penalty(double flow) {
        return penaltyBase + penaltyPerTph * flow;
    }

    public double score(double cost, double length) {
        return scoreCostWeight * (cost / scoreCostNorm) + scoreLengthWeight * (length / scoreLengthNorm);
    }
}
