package com.x3card.car3d;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置（照网页版 demo3d/settings.js，去掉唱片机那几项）：存在 SharedPreferences（d3 / settings，JSON），按白名单校验，坏了回到默认。
 * 车漆 / 轮毂 / 卡钳的选项、车漆质感、画质、底部数据的可选项同网页版。主线程改、GL 线程读：读写都加锁。
 * 改了通知订阅的人（主线程上），按钮类操作（act:名字）也走这里
 */
final class Settings {
    interface Listener { void on(String k); }

    /** 车漆 / 轮毂 / 卡钳的一个选项 */
    static final class Opt {
        final String id, name, color, hex, acc; final float metal, rough; final boolean model;
        Opt(String id, String name, String color, String hex, float metal, float rough, String acc, boolean model) { this.id = id; this.name = name; this.color = color; this.hex = hex; this.metal = metal; this.rough = rough; this.acc = acc; this.model = model; }
    }
    static final Map<String, List<Opt>> OPTS = new LinkedHashMap<>();
    static {
        OPTS.put("paint", Arrays.asList(
            new Opt("model", "原车颜色", null, null, Float.NaN, Float.NaN, null, true),
            new Opt("red", "赤焰红", "#9a0c13", null, 0.3f, 0.34f, "#ff4b55", false),
            new Opt("white", "珍珠白", "#e6e4df", null, 0.12f, 0.3f, "#9fd0ff", false),
            new Opt("black", "午夜黑", "#0f1013", null, 0.5f, 0.28f, "#b9c2d0", false),
            new Opt("blue", "电光蓝", "#1a44c4", null, 0.45f, 0.3f, "#5b8cff", false),
            new Opt("green", "极光绿", "#13705a", null, 0.45f, 0.3f, "#3fd9a6", false),
            new Opt("orange", "落日橙", "#e0571a", null, 0.25f, 0.32f, "#ff8a3d", false),
            new Opt("custom", "自定义", null, "paintHex", 0.3f, 0.32f, null, false)));
        OPTS.put("rim", Arrays.asList(
            new Opt("model", "原车颜色", null, null, Float.NaN, Float.NaN, null, true),
            new Opt("silver", "亮银", "#d9dce1", null, 1, 0.22f, null, false),
            new Opt("black", "亮黑", "#1a1b1e", null, 0.7f, 0.25f, null, false),
            new Opt("gold", "香槟金", "#c9a96a", null, 1, 0.28f, null, false),
            new Opt("gun", "枪灰", "#5a5f67", null, 0.9f, 0.3f, null, false),
            new Opt("custom", "自定义", null, "rimHex", 0.9f, 0.25f, null, false)));
        OPTS.put("caliper", Arrays.asList(
            new Opt("model", "原车颜色", null, null, Float.NaN, Float.NaN, null, true),
            new Opt("red", "红", "#c8141b", null, Float.NaN, Float.NaN, null, false),
            new Opt("yellow", "黄", "#e8b20e", null, Float.NaN, Float.NaN, null, false),
            new Opt("blue", "蓝", "#1f5fd6", null, Float.NaN, Float.NaN, null, false),
            new Opt("black", "黑", "#1a1a1c", null, Float.NaN, Float.NaN, null, false),
            new Opt("custom", "自定义", null, "calHex", Float.NaN, Float.NaN, null, false)));
    }
    static final List<String> FINISHES = Arrays.asList("preset", "gloss", "metal", "pearl", "satin", "matte", "frost", "candy", "chrome", "flip");
    static final String[] FINISH_NAMES = { "原厂", "亮面", "金属", "珠光", "缎面", "哑光", "磨砂金属", "糖果", "电镀", "变色龙" };
    /** 车漆质感：metal、rough、coat、ccr、iri、thick、flake（NaN = 没写） */
    static final Map<String, float[]> FINISH = new LinkedHashMap<>();
    static {
        // { metal, rough, coat, ccr, iri, thickLo, thickHi, flake }
        FINISH.put("gloss", new float[] { 0.15f, 0.28f, 1, Float.NaN, 0, Float.NaN, Float.NaN, 0 });
        FINISH.put("metal", new float[] { 0.62f, 0.3f, 1, Float.NaN, 0, Float.NaN, Float.NaN, 0.08f });
        FINISH.put("pearl", new float[] { 0.22f, 0.24f, 1, Float.NaN, 0.8f, 120, 380, 0.05f });
        FINISH.put("satin", new float[] { 0.35f, 0.46f, 0.4f, 0.35f, 0, Float.NaN, Float.NaN, 0.04f });
        FINISH.put("matte", new float[] { 0.08f, 0.72f, 0, Float.NaN, 0, Float.NaN, Float.NaN, 0 });
        FINISH.put("frost", new float[] { 0.78f, 0.52f, 0, Float.NaN, 0, Float.NaN, Float.NaN, 0.06f });
        FINISH.put("candy", new float[] { 0.5f, 0.14f, 1, Float.NaN, 0, Float.NaN, Float.NaN, 0.06f });
        FINISH.put("chrome", new float[] { 1, 0.06f, 1, Float.NaN, 0, Float.NaN, Float.NaN, 0 });
        FINISH.put("flip", new float[] { 0.55f, 0.24f, 1, Float.NaN, 1, 250, 900, 0.06f });
    }
    /** 画质：ratio 像素比上限、shadow 阴影贴图大小、flake 车漆闪点、lowp 中精度光照、reflect 地面反射、bloom 辉光、ao 环境光遮蔽、dof 景深 */
    static final class Qual {
        final float ratio; final int shadow; final boolean flake, lowp, reflect, bloom, ao, dof; final String note;
        Qual(float r, int s, boolean f, boolean l, boolean re, boolean b, boolean a, boolean d, String n) { ratio = r; shadow = s; flake = f; lowp = l; reflect = re; bloom = b; ao = a; dof = d; note = n; }
    }
    static final Map<String, Qual> QUAL = new LinkedHashMap<>();
    static {
        QUAL.put("eco", new Qual(1, 0, false, true, false, false, false, false, "像素比 1，不画真实阴影、车漆闪点，最省显卡"));
        QUAL.put("std", new Qual(1.5f, 1024, true, true, false, false, false, false, "真实影棚反射、车身阴影、金属漆闪点和清漆纹理"));
        QUAL.put("high", new Qual(1.5f, 2048, true, false, true, true, false, false, "在标准上加地面反射、灯光辉光，转车时更费显卡"));
        QUAL.put("ultra", new Qual(2, 2048, true, false, true, true, true, true, "再加环境光遮蔽、景深、像素比 2，最费显卡，车机上转车可能会卡"));
    }
    static final List<String> THEMES = Arrays.asList("auto", "light", "dark"), BGS = Arrays.asList("studio", "warm", "cool", "image"), FPS = Arrays.asList("30", "60");
    /** 滑块：最小、最大、步长、默认 */
    static final Map<String, float[]> NUM = new LinkedHashMap<>();
    static {
        NUM.put("bgDim", new float[] { 0, 0.7f, 0.05f, 0.25f });
        NUM.put("viewAz", new float[] { -3.15f, 3.15f, 0.01f, -0.72f });
        NUM.put("viewEl", new float[] { 0.04f, 0.62f, 0.01f, 0.2f });
        NUM.put("viewZoom", new float[] { 0.7f, 1.4f, 0.05f, 1 });
        NUM.put("viewFov", new float[] { 18, 50, 1, 27 });
        // 挂 D 挡后的视角：默认是车顶视角（车后正中、俯视 62°）
        NUM.put("dAz", new float[] { -3.15f, 3.15f, 0.01f, (float) Math.PI });
        NUM.put("dEl", new float[] { 0.04f, 1.3f, 0.01f, 1.08f });
        NUM.put("dZoom", new float[] { 0.7f, 1.4f, 0.05f, 1 });
    }
    static final List<String> VIEW = Arrays.asList("viewAz", "viewEl", "viewZoom", "viewFov"), DVIEW = Arrays.asList("dAz", "dEl", "dZoom");
    static final List<String> BOOL = Arrays.asList("labels", "intro", "aa", "fsBar");
    static final Map<String, String> HEX = new LinkedHashMap<>();
    static { HEX.put("paintHex", "#3a7bd5"); HEX.put("rimHex", "#b8bcc4"); HEX.put("calHex", "#2bb673"); }

    private final Map<String, Object> s = new LinkedHashMap<>();
    private final List<Listener> fns = new ArrayList<>();
    private final SharedPreferences prefs;
    /** 车模自己的车漆 { color, css, metal, rough, coat }（没标出车身时 null）；轮毂、卡钳显示什么颜色 */
    private String[] modelPaint; // color, css
    private float mpMetal, mpRough, mpCoat;
    private final Map<String, String> modelPart = new LinkedHashMap<>();
    final Map<String, Float> hueKeep = new LinkedHashMap<>();

    Settings(Context c) {
        prefs = c.getSharedPreferences("d3", Context.MODE_PRIVATE);
        load();
    }
    private static Map<String, Object> defs() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("paint", "red"); d.put("rim", "silver"); d.put("caliper", "red"); d.put("finish", "preset");
        for (Map.Entry<String, String> e : HEX.entrySet()) d.put(e.getKey(), e.getValue());
        d.put("theme", "auto"); d.put("bg", "studio"); d.put("bgDim", 0.25f);
        d.put("viewAz", -0.72f); d.put("viewEl", 0.2f); d.put("viewZoom", 1f); d.put("viewFov", 27f);
        d.put("dAz", (float) Math.PI); d.put("dEl", 1.08f); d.put("dZoom", 1f);
        d.put("stats", Arrays.asList("bat", "fuel", "range", "temp"));
        d.put("labels", true); d.put("intro", true); d.put("quality", "eco"); d.put("fps", "60"); d.put("aa", false); d.put("fsBar", false);
        return d;
    }
    private static boolean ok(String k, Object v) {
        if (BOOL.contains(k)) return v instanceof Boolean;
        if (NUM.containsKey(k)) { if (!(v instanceof Number)) return false; float f = ((Number) v).floatValue(); return f >= NUM.get(k)[0] && f <= NUM.get(k)[1]; }
        if (HEX.containsKey(k)) return v instanceof String && ((String) v).matches("^#[0-9a-f]{6}$");
        if ("stats".equals(k)) {
            if (!(v instanceof List)) return false;
            List<?> l = (List<?>) v;
            if (l.isEmpty() || l.size() > 4) return false;
            for (int i = 0; i < l.size(); i++) if (l.indexOf(l.get(i)) != i || CarStat.byId(String.valueOf(l.get(i))) == null) return false;
            return true;
        }
        if (OPTS.containsKey(k)) { for (Opt o : OPTS.get(k)) if (o.id.equals(v)) return true; return false; }
        if ("finish".equals(k)) return FINISHES.contains(v);
        if ("theme".equals(k)) return THEMES.contains(v);
        if ("bg".equals(k)) return BGS.contains(v);
        if ("quality".equals(k)) return QUAL.containsKey(v);
        if ("fps".equals(k)) return FPS.contains(v);
        return false;
    }
    synchronized void load() {
        JSONObject raw = null;
        try { raw = new JSONObject(prefs.getString("settings", "{}")); } catch (Exception ignored) {}
        if (raw == null) raw = new JSONObject();
        s.clear();
        for (Map.Entry<String, Object> e : defs().entrySet()) {
            Object v = raw.opt(e.getKey());
            if (v instanceof JSONArray) { List<String> l = new ArrayList<>(); for (int i = 0; i < ((JSONArray) v).length(); i++) l.add(String.valueOf(((JSONArray) v).opt(i))); v = l; }
            if (v instanceof Number && NUM.containsKey(e.getKey())) v = ((Number) v).floatValue();
            s.put(e.getKey(), v != null && ok(e.getKey(), v) ? v : e.getValue());
        }
    }
    private void save() {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, Object> e : s.entrySet()) o.put(e.getKey(), e.getValue() instanceof List ? new JSONArray((List<?>) e.getValue()) : e.getValue()); } catch (Exception ignored) {}
        prefs.edit().putString("settings", o.toString()).apply();
    }
    synchronized Object get(String k) { return s.get(k); }
    String str(String k) { return String.valueOf(get(k)); }
    boolean bool(String k) { return Boolean.TRUE.equals(get(k)); }
    float num(String k) { Object v = get(k); return v instanceof Number ? ((Number) v).floatValue() : 0; }
    @SuppressWarnings("unchecked") synchronized List<String> stats() { return new ArrayList<>((List<String>) s.get("stats")); }

    /** 改一项（不合法、没变就不动）；通知订阅的人 */
    void set(String k, Object v) {
        synchronized (this) {
            if (NUM.containsKey(k) && v instanceof Number) { float st = NUM.get(k)[2]; v = Math.round(Math.round(((Number) v).floatValue() / st) * st * 100) / 100f; }
            if (!ok(k, v) || String.valueOf(s.get(k)).equals(String.valueOf(v))) return;
            s.put(k, v);
            save();
        }
        emit(k);
    }
    void on(Listener l) { synchronized (fns) { fns.add(l); } }
    void emit(String k) { List<Listener> l; synchronized (fns) { l = new ArrayList<>(fns); } for (Listener f : l) f.on(k); }
    synchronized Qual fx() { return QUAL.get(str0("quality")); }
    private String str0(String k) { return String.valueOf(s.get(k)); }

    /** 车漆 / 轮毂 / 卡钳选中的那一项（同 pick） */
    static final class Pick { String id, name, color, acc; float metal = Float.NaN, rough = Float.NaN, coat = 1, ccr = 0.03f, iri, flake; float[] thick = { 250, 900 }; boolean model; }
    synchronized Pick pick(String k) {
        Opt o = find(k, str0(k));
        Pick r = new Pick();
        r.id = o.id; r.name = o.name; r.color = o.hex != null ? str0(o.hex) : o.color; r.metal = o.metal; r.rough = o.rough; r.acc = o.acc; r.coat = 1;
        if (o.model && !"paint".equals(k)) { r.model = true; String c = modelPart.get(k); r.color = c != null ? c : "#9a9da3"; }
        if (o.model && "paint".equals(k)) {
            String[] mp = modelPaint != null ? modelPaint : new String[] { "#9a0c13", "#9a0c13" };
            float mm = modelPaint != null ? mpMetal : 0.3f;
            r.model = true; r.color = mp[0]; r.acc = accOf(mp[1]);
            r.metal = mm >= 0.5f ? 0.6f : 0.3f; r.rough = 0.34f; r.coat = 1;
        }
        if (o.hex != null && "paint".equals(k)) r.acc = accOf(r.color);
        if ("paint".equals(k)) {
            float[] f = FINISH.get(str0("finish"));
            if (f != null) { r.metal = f[0]; r.rough = f[1]; r.coat = f[2]; }
            r.ccr = f != null && !Float.isNaN(f[3]) ? f[3] : 0.03f;
            r.iri = f != null ? f[4] : 0;
            r.thick = f != null && !Float.isNaN(f[5]) ? new float[] { f[5], f[6] } : new float[] { 250, 900 };
            r.flake = f != null ? f[7] : r.metal >= 0.4f ? 0.06f : 0;
        }
        return r;
    }
    private static Opt find(String k, String id) { for (Opt o : OPTS.get(k)) if (o.id.equals(id)) return o; return OPTS.get(k).get(0); }
    /** 车模自己的车漆（null：没标出车身） */
    void modelPaint(String color, String css, float metal, float rough, float coat) {
        synchronized (this) { modelPaint = color == null ? null : new String[] { color, css }; mpMetal = metal; mpRough = rough; mpCoat = coat; }
        emit("model");
    }
    void modelPart(String k, String css) { synchronized (this) { if (css == null) modelPart.remove(k); else modelPart.put(k, css); } emit("model"); }
    synchronized String modelPaintCss() { return modelPaint == null ? null : modelPaint[1]; }
    synchronized String modelPartCss(String k) { return modelPart.get(k); }

    // ================= 颜色换算（同 settings.js） =================
    static int[] hex2hsl(String h) {
        float r = Integer.parseInt(h.substring(1, 3), 16) / 255f, g = Integer.parseInt(h.substring(3, 5), 16) / 255f, b = Integer.parseInt(h.substring(5, 7), 16) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), l = (mx + mn) / 2, d = mx - mn, hh = 0, ss = 0;
        if (d != 0) {
            ss = d / (1 - Math.abs(2 * l - 1));
            hh = mx == r ? ((g - b) / d) % 6 : mx == g ? (b - r) / d + 2 : (r - g) / d + 4;
            hh = (hh * 60 + 360) % 360;
        }
        return new int[] { Math.round(hh), Math.round(ss * 100), Math.round(l * 100) };
    }
    static String hsl2hex(float h, float sat, float l) {
        sat /= 100; l /= 100;
        float c = (1 - Math.abs(2 * l - 1)) * sat, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = l - c / 2;
        float[] rgb = h < 60 ? new float[] { c, x, 0 } : h < 120 ? new float[] { x, c, 0 } : h < 180 ? new float[] { 0, c, x } : h < 240 ? new float[] { 0, x, c } : h < 300 ? new float[] { x, 0, c } : new float[] { c, 0, x };
        StringBuilder sb = new StringBuilder("#");
        for (float v : rgb) { int n = Math.round((v + m) * 255); sb.append(n < 16 ? "0" : "").append(Integer.toHexString(n)); }
        return sb.toString();
    }
    static String accOf(String h) { int[] a = hex2hsl(h); return a[1] < 12 ? "#b9c2d0" : hsl2hex(a[0], Math.max(60, a[1]), Math.max(58, Math.min(70, a[2] + 20))); }
}
