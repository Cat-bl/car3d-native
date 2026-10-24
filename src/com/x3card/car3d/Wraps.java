package com.x3card.car3d;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** 车身贴纸、拉花（照网页版 demo3d/carwrap.js）。这里先是配置校验（同 carmodel.js 的 wrapClean），贴到车上的部分在后面一段搬 */
final class Wraps {
    private Wraps() {}
    static final List<String> TPL = Arrays.asList("belt", "slash", "rocker", "race2", "band", "path", "pixel", "speed", "checker", "flame", "camo"), ON = Arrays.asList("body", "glass", "both");
    static final List<String> FIN = Arrays.asList("gloss", "satin", "matte", "metal", "holo");

    private static Object imgRef(Object v) {
        if (v instanceof Number) { double d = ((Number) v).doubleValue(); return d >= 0 && d % 1 == 0 && d < 1e5 ? (Object) (int) d : null; }
        return v instanceof String && ((String) v).matches("^h:[0-9a-f]{8,40}$") ? v : null;
    }
    private static JSONArray unit3(Object a) {
        JSONArray v = CarModel.vec(a);
        if (v == null) return null;
        double x = v.optDouble(0), y = v.optDouble(1), z = v.optDouble(2), l = Math.sqrt(x * x + y * y + z * z);
        if (l <= 1e-3) return null;
        try { return new JSONArray().put(x / l).put(y / l).put(z / l); } catch (Exception e) { return null; }
    }
    private static JSONArray nums(Object a, double[][] lim) {
        if (!(a instanceof JSONArray) || ((JSONArray) a).length() != lim.length) return null;
        JSONArray l = (JSONArray) a, o = new JSONArray();
        for (int i = 0; i < lim.length; i++) { Double v = CarModel.num(l.opt(i), lim[i][0], lim[i][1]); if (v == null) return null; o.put(v); }
        return o;
    }
    /** 贴纸配置校验（同 wrapClean）：不合法返回 null */
    static JSONObject clean(Object xo) {
        if (!(xo instanceof JSONObject)) return null;
        JSONObject x = (JSONObject) xo, y = new JSONObject();
        String kind = x.optString("kind");
        if (!Arrays.asList("stripe", "img", "text").contains(kind)) return null;
        try {
            String id = x.opt("id") instanceof String && x.optString("id").matches("^[a-z0-9]{1,16}$") ? x.optString("id") : Long.toString((long) (Math.random() * 1e12), 36);
            y.put("id", id); y.put("kind", kind);
            if (Boolean.TRUE.equals(x.opt("hide"))) y.put("hide", true);
            Double al = CarModel.num(x.opt("alpha"), 0.05, 1); if (al != null) y.put("alpha", al);
            if (ON.contains(x.opt("on"))) y.put("on", x.optString("on"));
            for (String k : new String[] { "c1", "c2", "c3", "ac", "gc", "oc" }) if (CarModel.hex(x.opt(k))) y.put(k, x.optString(k).toLowerCase());
            if (FIN.contains(x.opt("fin"))) y.put("fin", x.optString("fin"));
            Double r = CarModel.num(x.opt("rough"), 0, 1); if (r != null) y.put("rough", r);
            Double m = CarModel.num(x.opt("metal"), 0, 1); if (m != null) y.put("metal", m);
            Double out = CarModel.num(x.opt("out"), 0, 40); if (out != null && out > 0) y.put("out", out);
            if ("a".equals(x.opt("grad")) || "c".equals(x.opt("grad"))) y.put("grad", x.optString("grad"));
            JSONArray dash = nums(x.opt("dash"), new double[][] { { 0.5, 300 }, { 0.5, 300 } }); if (dash != null) y.put("dash", dash);
            JSONArray fd = nums(x.opt("fd"), new double[][] { { 0, 0.5 }, { 0, 0.5 } }); if (fd != null) y.put("fd", fd);
            if ("stripe".equals(kind)) {
                if (!TPL.contains(x.opt("tpl"))) return null;
                y.put("tpl", x.optString("tpl"));
                Object sd = x.opt("side");
                if ("L".equals(sd) || "R".equals(sd) || "both".equals(sd)) y.put("side", sd);
                if ("top".equals(x.opt("pf")) || "side".equals(x.opt("pf"))) y.put("pf", x.optString("pf"));
                if (Boolean.TRUE.equals(x.opt("alt"))) y.put("alt", true);
                JSONObject p = new JSONObject(), xp = x.optJSONObject("p");
                if (xp != null) {
                    int n = 0;
                    for (Iterator<String> it = xp.keys(); it.hasNext() && n < 32; n++) {
                        String k = it.next();
                        if (k.matches("^[a-zA-Z][a-zA-Z0-9]{0,15}$") && CarModel.num(xp.opt(k), -1e4, 1e4) != null) p.put(k, xp.getDouble(k));
                    }
                }
                y.put("p", p);
                JSONArray path = x.optJSONArray("path");
                if (path != null) {
                    JSONArray pts = new JSONArray();
                    for (int i = 0; i < Math.min(200, path.length()); i++) {
                        JSONArray q = nums(path.opt(i), new double[][] { { -0.5, 1.5 }, { -0.5, 1.5 } });
                        if (q != null) pts.put(new JSONArray().put(Math.round(q.getDouble(0) * 1e4) / 1e4).put(Math.round(q.getDouble(1) * 1e4) / 1e4));
                    }
                    if (pts.length() >= 2) y.put("path", pts);
                }
                return y;
            }
            JSONArray at = CarModel.vec(x.opt("at")), dir = unit3(x.opt("dir"));
            if (at == null || dir == null) return null;
            y.put("at", at); y.put("dir", dir);
            if (unit3(x.opt("up")) != null) y.put("up", unit3(x.opt("up")));
            Double rot = CarModel.num(x.opt("rot"), -180, 180); if (rot != null) y.put("rot", rot);
            Double w = CarModel.num(x.opt("w"), 0.02, 12); y.put("w", w != null ? w : 0.6);
            Double sx = CarModel.num(x.opt("sx"), 0.2, 5); if (sx != null && sx != 1) y.put("sx", sx);
            if (Boolean.TRUE.equals(x.opt("flip"))) y.put("flip", true);
            if (Boolean.TRUE.equals(x.opt("fv"))) y.put("fv", true);
            if ("mirror".equals(x.opt("both")) || "same".equals(x.opt("both"))) y.put("both", x.optString("both"));
            if ("v".equals(x.opt("wm")) || "h".equals(x.opt("wm"))) y.put("wm", x.optString("wm"));
            if ("img".equals(kind)) {
                Object ref = imgRef(x.opt("img"));
                if (ref == null) return null;
                y.put("img", ref);
                JSONArray crop = nums(x.opt("crop"), new double[][] { { 0, 0.45 }, { 0, 0.45 }, { 0, 0.45 }, { 0, 0.45 } }); if (crop != null) y.put("crop", crop);
                JSONObject key = x.optJSONObject("key");
                if (key != null) {
                    JSONObject k = new JSONObject();
                    k.put("c", "auto".equals(key.opt("c")) || !CarModel.hex(key.opt("c")) ? "auto" : key.optString("c").toLowerCase());
                    Double tol = CarModel.num(key.opt("tol"), 0, 1); if (tol != null) k.put("tol", tol);
                    Double soft = CarModel.num(key.opt("soft"), 0, 1); if (soft != null) k.put("soft", soft);
                    if (Boolean.FALSE.equals(key.opt("edge"))) k.put("edge", false);
                    y.put("key", k);
                }
                JSONArray adj = nums(x.opt("adj"), new double[][] { { 0, 3 }, { 0, 3 }, { 0, 4 }, { -180, 180 } }); if (adj != null) y.put("adj", adj);
                Double ef = CarModel.num(x.opt("ef"), 0, 0.5); if (ef != null && ef > 0) y.put("ef", ef);
                return y;
            }
            String text = x.opt("text") instanceof String ? x.optString("text").replaceAll("[\\u0000-\\u0009\\u000b-\\u001f\\u007f]", "") : "";
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, lines.length); i++) { if (i > 0) sb.append('\n'); sb.append(lines[i]); }
            y.put("text", sb.length() > 120 ? sb.substring(0, 120) : sb.toString());
            if (x.opt("font") instanceof String && x.optString("font").matches("^(sys|sysb|b:[a-z0-9]{1,16}|u:[0-9a-f]{8,40})$")) y.put("font", x.optString("font"));
            Object st = x.opt("stroke");
            if (st instanceof Number && Arrays.asList(0, 1, 2).contains(((Number) st).intValue()) && ((Number) st).doubleValue() % 1 == 0) y.put("stroke", ((Number) st).intValue());
            if (Boolean.TRUE.equals(x.opt("vert"))) y.put("vert", true);
            Double ls = CarModel.num(x.opt("ls"), -0.3, 1); if (ls != null && ls != 0) y.put("ls", ls);
            if (Boolean.TRUE.equals(x.opt("it"))) y.put("it", true);
            JSONObject sh0 = x.optJSONObject("sh");
            if (sh0 != null) {
                JSONObject sh = new JSONObject();
                sh.put("c", CarModel.hex(sh0.opt("c")) ? sh0.optString("c").toLowerCase() : "#000000");
                for (String q : new String[] { "x", "y", "b" }) { Double v = CarModel.num(sh0.opt(q), -1, 1); if (v != null) sh.put(q, v); }
                y.put("sh", sh);
            }
            if (imgRef(x.opt("raster")) instanceof Integer) y.put("raster", imgRef(x.opt("raster")));
            return y;
        } catch (Exception e) { return null; }
    }
}
