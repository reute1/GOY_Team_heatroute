package ru.lct.heatroute.core.json;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Минимальный потоковый JSON-парсер/писатель без внешних зависимостей.
 * Числа хранятся как {@link Num} с исходным литералом, чтобы идентификаторы
 * возвращались в выходной файл в том же виде, что и во входном.
 */
public final class Json {

    private Json() {
    }

    /** Числовое значение с сохранением исходного литерала. */
    public static final class Num {
        public final String raw;

        public Num(String raw) {
            this.raw = raw;
        }

        public double doubleValue() {
            return Double.parseDouble(raw);
        }

        @Override
        public String toString() {
            return raw;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Num && ((Num) o).raw.equals(raw);
        }

        @Override
        public int hashCode() {
            return raw.hashCode();
        }
    }

    // ------------------------------------------------------------------ parser

    public static final class Parser {
        private final Reader in;
        private final char[] buf = new char[1 << 16];
        private int pos;
        private int len;
        private long offset;
        private final StringBuilder sb = new StringBuilder();

        public Parser(Reader in) {
            this.in = in;
        }

        private int peekChar() throws IOException {
            if (pos >= len) {
                offset += len;
                len = in.read(buf, 0, buf.length);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return -1;
                }
            }
            return buf[pos];
        }

        private int nextChar() throws IOException {
            int c = peekChar();
            if (c >= 0) {
                pos++;
            }
            return c;
        }

        private int skipWs() throws IOException {
            while (true) {
                int c = peekChar();
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') {
                    pos++;
                } else {
                    return c;
                }
            }
        }

        private IOException err(String msg) {
            return new IOException("JSON: " + msg + " (позиция " + (offset + pos) + ")");
        }

        private void expect(char ch) throws IOException {
            int c = skipWs();
            if (c != ch) {
                throw err("ожидался '" + ch + "', найден '" + (c < 0 ? "EOF" : String.valueOf((char) c)) + "'");
            }
            pos++;
        }

        /** Читает произвольное значение целиком. */
        public Object readValue() throws IOException {
            int c = skipWs();
            switch (c) {
                case '{': {
                    pos++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (skipWs() == '}') {
                        pos++;
                        return m;
                    }
                    while (true) {
                        String k = readString();
                        expect(':');
                        m.put(k, readValue());
                        int d = skipWs();
                        pos++;
                        if (d == '}') {
                            return m;
                        }
                        if (d != ',') {
                            throw err("ожидалась ',' или '}'");
                        }
                    }
                }
                case '[': {
                    pos++;
                    List<Object> l = new ArrayList<>();
                    if (skipWs() == ']') {
                        pos++;
                        return l;
                    }
                    while (true) {
                        l.add(readValue());
                        int d = skipWs();
                        pos++;
                        if (d == ']') {
                            return l;
                        }
                        if (d != ',') {
                            throw err("ожидалась ',' или ']'");
                        }
                    }
                }
                case '"':
                    return readString();
                case 't':
                    lit("true");
                    return Boolean.TRUE;
                case 'f':
                    lit("false");
                    return Boolean.FALSE;
                case 'n':
                    lit("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw err("неожиданный символ " + (c < 0 ? "EOF" : String.valueOf((char) c)));
            }
        }

        private void lit(String s) throws IOException {
            for (int i = 0; i < s.length(); i++) {
                if (nextChar() != s.charAt(i)) {
                    throw err("ожидалось " + s);
                }
            }
        }

        private Num readNumber() throws IOException {
            sb.setLength(0);
            while (true) {
                int c = peekChar();
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    sb.append((char) c);
                    pos++;
                } else {
                    break;
                }
            }
            return new Num(sb.toString());
        }

        private String readString() throws IOException {
            int c = skipWs();
            if (c != '"') {
                throw err("ожидалась строка");
            }
            pos++;
            sb.setLength(0);
            while (true) {
                c = nextChar();
                if (c < 0) {
                    throw err("незакрытая строка");
                }
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    int e = nextChar();
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': {
                            char[] h = new char[4];
                            for (int i = 0; i < 4; i++) {
                                h[i] = (char) nextChar();
                            }
                            sb.append((char) Integer.parseInt(new String(h), 16));
                            break;
                        }
                        default: sb.append((char) e);
                    }
                } else {
                    sb.append((char) c);
                }
            }
        }

        /**
         * Потоковый разбор FeatureCollection: каждый элемент массива "features"
         * передаётся в consumer и сразу освобождается. Остальные ключи верхнего уровня
         * возвращаются в виде Map (crs, name и т.п.).
         */
        public Map<String, Object> streamFeatures(Consumer<Map<String, Object>> consumer) throws IOException {
            Map<String, Object> top = new LinkedHashMap<>();
            expect('{');
            if (skipWs() == '}') {
                pos++;
                return top;
            }
            while (true) {
                String k = readString();
                expect(':');
                if ("features".equals(k)) {
                    expect('[');
                    if (skipWs() == ']') {
                        pos++;
                    } else {
                        while (true) {
                            Object v = readValue();
                            if (v instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> f = (Map<String, Object>) v;
                                consumer.accept(f);
                            }
                            int d = skipWs();
                            pos++;
                            if (d == ']') {
                                break;
                            }
                            if (d != ',') {
                                throw err("ожидалась ',' в массиве features");
                            }
                        }
                    }
                } else {
                    top.put(k, readValue());
                }
                int d = skipWs();
                pos++;
                if (d == '}') {
                    return top;
                }
                if (d != ',') {
                    throw err("ожидалась ',' или '}' на верхнем уровне");
                }
            }
        }
    }

    // ------------------------------------------------------------------ writer

    public static void write(Writer w, Object v) throws IOException {
        if (v == null) {
            w.write("null");
        } else if (v instanceof String) {
            writeString(w, (String) v);
        } else if (v instanceof Num) {
            w.write(((Num) v).raw);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                w.write("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                w.write(Long.toString((long) d));
            } else {
                w.write(Double.toString(d));
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            w.write(v.toString());
        } else if (v instanceof Map) {
            w.write('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    w.write(',');
                }
                first = false;
                writeString(w, String.valueOf(e.getKey()));
                w.write(':');
                write(w, e.getValue());
            }
            w.write('}');
        } else if (v instanceof Iterable) {
            w.write('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    w.write(',');
                }
                first = false;
                write(w, o);
            }
            w.write(']');
        } else if (v instanceof double[]) {
            double[] a = (double[]) v;
            w.write('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    w.write(',');
                }
                w.write(String.format(java.util.Locale.ROOT, "%.9f", a[i]));
            }
            w.write(']');
        } else {
            writeString(w, v.toString());
        }
    }

    public static void writeString(Writer w, String s) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': w.write("\\\""); break;
                case '\\': w.write("\\\\"); break;
                case '\n': w.write("\\n"); break;
                case '\r': w.write("\\r"); break;
                case '\t': w.write("\\t"); break;
                default:
                    if (c < 0x20) {
                        w.write(String.format("\\u%04x", (int) c));
                    } else {
                        w.write(c);
                    }
            }
        }
        w.write('"');
    }
}
