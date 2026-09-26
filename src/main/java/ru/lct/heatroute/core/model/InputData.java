package ru.lct.heatroute.core.model;

import java.util.ArrayList;
import java.util.List;

/** Входные данные в метрической системе координат (UTM). */
public final class InputData {

    /** Геометрия: полигоны (массив колец, первое — внешнее) и линии; координаты x0,y0,x1,y1... */
    public static final class Shape {
        public final List<double[][]> polygons = new ArrayList<>();
        public final List<double[]> lines = new ArrayList<>();

        public boolean isEmpty() {
            return polygons.isEmpty() && lines.isEmpty();
        }
    }

    public static final class Source {
        public Object id;
        public double x;
        public double y;
    }

    public static final class NetLine {
        public Object id;
        public int dn;
        public double[] coords;
    }

    public static final class Chamber {
        public Object id;
        public double x;
        public double y;
        /** Число уже примыкающих линейных участков существующей сети. */
        public int adjacency;
    }

    public static final class Target {
        public Object id;
        public double flow;
        public double x;
        public double y;
        public int index;
    }

    public static final class Restriction {
        public Object id;
        public String type;
        public Shape shape;
        public RuleBook.Restriction rule;
        public int index;
    }

    public final List<Source> sources = new ArrayList<>();
    public final List<NetLine> network = new ArrayList<>();
    public final List<Chamber> chambers = new ArrayList<>();
    public final List<Target> targets = new ArrayList<>();
    public final List<Restriction> restrictions = new ArrayList<>();
    /** Диагностика входных данных (пропуски атрибутов, некорректная геометрия и т.п.). */
    public final List<String> warnings = new ArrayList<>();
    public long featureCount;
}
