package com.x3card.car3d;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Box3;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Gltf;
import com.x3card.car3d.gl.Half;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 车模（照网页版 demo3d/carmodel.js 里展示要用的那部分）：现在用哪个车模（默认 W70，或者导入的车模包——网页版"导出车模包"的 GLB，配置在 asset.extras.d3car）、
 * 配置校验（外来数据，只留认识且合法的字段）、解析完给网格和材质编号（按遍历顺序，配置按编号引用）、W70 的内置配置、金属度靠不住的车模、双面材质理顺绕向。
 * 自动识别、导入原始 GLB、拆零件、简化、导出在网页版里做
 */
final class CarModel {
    private CarModel() {}
    static final double R2D = 180 / Math.PI;
    static final List<String> BODY_ROLES = Arrays.asList("paint", "rim", "caliper", "glass");
    static final List<String> CUSTOM_LIGHTS = Arrays.asList("cus1", "cus2", "cus3", "cus4", "cus5", "cus6", "cus7", "cus8");
    static final List<String> LIGHT_ROLES = new ArrayList<>(Arrays.asList("head", "led", "side", "tail", "brake", "reverse", "turnL", "turnR", "fogF", "fogR", "ambient"));
    static { LIGHT_ROLES.addAll(CUSTOM_LIGHTS); }
    static final List<String> MAT_ROLES = new ArrayList<>(BODY_ROLES);
    static { MAT_ROLES.addAll(LIGHT_ROLES); }
    static final List<String> STYLES = Arrays.asList("normal", "rear", "butterfly", "scissor", "gull"), EFFECTS = Arrays.asList("steady", "breath", "blink", "flow", "strobe");
    static final List<String> WHEN = Arrays.asList("power", "led", "side", "head", "brake", "reverse", "hazard");
    static final List<String> MOVERS = Arrays.asList("doorFL", "doorFR", "doorRL", "doorRR", "hood", "trunk", "sunroof", "sunshade", "chargePort"), WINDOWS = Arrays.asList("winFL", "winFR", "winRL", "winRR");
    static final List<String> MAPS = Arrays.asList("aoMap", "normalMap", "map", "roughnessMap", "metalnessMap");

    // ================= 内置车模 W70 的配置（坐标按模型原来的位置，coords: 'model'） =================
    static JSONObject w70() {
        try {
            return new JSONObject("{v:1,name:'2016 Exotic Rides W70',credit:'Ddiaz Design · CC-BY-NC-SA-4.0（不可商用）',scale:100,rotY:0,coords:'model',box:[1.02,1.26,2.3]," +
                "mats:{paint:['CarPaint'],rim:['Rims'],caliper:['calipers_AO'],glass:['glass_1'],head:['glass_light'],tail:['red_glass'],brake:['red_glass'],led:['emiss']}," +
                "lens:true,borrow:true,paintAo:0.45,paintKeep:['aoMap']," +
                "parts:{doorFL:{meshes:['detach_door_L_'],at:[0.6,0.98,0.72],axis:[-0.32,0.5,-0.8],angle:" + (1.15 * R2D) + "}," +
                "doorFR:{meshes:['detach_door_R_'],at:[-0.6,0.98,0.72],axis:[0.32,0.5,-0.8],angle:" + (1.15 * R2D) + "}," +
                "hood:{meshes:['detach_hood_'],at:[0,0.74,1.11],axis:[1,0,0],angle:" + (0.95 * R2D) + "}," +
                "trunk:{meshes:['detach_trunk_'],at:[0,1.04,-0.3],axis:[1,0,0],angle:" + (0.6 * R2D) + "}," +
                "wheels:['wheel(FL|FR|BL|BR)_(tire|rims)_']}," +
                "winSplit:'glass_detachable_glass',winDrop:0.24," +
                "wheelPos:{frontLeft:[0.8,0,1.27],frontRight:[-0.79,0,1.27],rearLeft:[0.83,0,-1.27],rearRight:[-0.835,0,-1.27]}," +
                "wheelR:0.345,labelOut:0.42,turnPos:[[0.74,0.6,1.92],[0.76,0.7,-1.84]]}");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static boolean isW70(Obj3D model) {
        boolean[] f = new boolean[2];
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            if (o.name.contains("detach_door_L_")) f[1] = true;
            if ("CarPaint".equals(((Mesh) o).material.name)) f[0] = true;
        });
        return f[0] && f[1];
    }

    // ================= 配置校验 =================
    static boolean hex(Object c) { return c instanceof String && ((String) c).matches("(?i)^#[0-9a-f]{6}$"); }
    static boolean okRe(String x) { try { Pattern.compile(x); return true; } catch (Exception e) { return false; } }
    static JSONArray refs(Object a) {
        JSONArray o = new JSONArray();
        if (!(a instanceof JSONArray)) return o;
        JSONArray l = (JSONArray) a;
        for (int i = 0; i < l.length(); i++) {
            Object x = l.opt(i);
            if (x instanceof Number) { double d = ((Number) x).doubleValue(); if (d >= 0 && d % 1 == 0) o.put((int) d); }
            else if (x instanceof String && ((String) x).length() < 200 && okRe((String) x)) o.put(x);
        }
        return o;
    }
    static Double num(Object v, double lo, double hi) {
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d) && d >= lo && d <= hi ? d : null;
    }
    static JSONArray vec(Object a) {
        if (!(a instanceof JSONArray) || ((JSONArray) a).length() != 3) return null;
        JSONArray l = (JSONArray) a, o = new JSONArray();
        for (int i = 0; i < 3; i++) { Object x = l.opt(i); if (!(x instanceof Number)) return null; double d = ((Number) x).doubleValue(); if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) >= 1000) return null; try { o.put(d); } catch (Exception e) { return null; } }
        return o;
    }
    static int quarter(Object v) { if (!(v instanceof Number)) return 0; double d = ((Number) v).doubleValue(); return d % 90 == 0 ? (int) (((d % 360) + 360) % 360) : 0; }
    /** 车模包里的配置是外来数据：只留认识且合法的字段（同 sanitize）；连大小都没有的配置不完整，返回 null */
    static JSONObject sanitize(Object x) {
        if (!(x instanceof JSONObject)) return null;
        JSONObject c = (JSONObject) x, o = new JSONObject();
        try {
            o.put("v", 1); o.put("rotX", quarter(c.opt("rotX"))); o.put("rotY", quarter(c.opt("rotY"))); o.put("rotZ", quarter(c.opt("rotZ")));
            o.put("hide", refs(c.opt("hide"))); o.put("mats", new JSONObject()); o.put("parts", new JSONObject());
            Object[][] nums = { { "length", 0.5, 30.0 }, { "scale", 1e-6, 1e6 }, { "exposure", 0.3, 3.0 }, { "env", 0.0, 5.0 }, { "paintAo", 0.0, 1.0 }, { "winDrop", 0.0, 2.0 }, { "wheelR", 0.05, 2.0 }, { "labelOut", 0.0, 3.0 } };
            for (Object[] k : nums) { Double v = num(c.opt((String) k[0]), (Double) k[1], (Double) k[2]); if (v != null) o.put((String) k[0], v); }
            for (String k : new String[] { "lens", "keepGlass", "keepPaintMap", "borrow" }) if (Boolean.TRUE.equals(c.opt(k))) o.put(k, true);
            if ("model".equals(c.opt("coords"))) o.put("coords", "model");
            if (vec(c.opt("box")) != null) o.put("box", vec(c.opt("box")));
            if (c.opt("winSplit") instanceof String && okRe(c.optString("winSplit"))) o.put("winSplit", c.optString("winSplit"));
            if (c.opt("paintKeep") instanceof JSONArray) { JSONArray pk = c.getJSONArray("paintKeep"), q = new JSONArray(); for (int i = 0; i < pk.length(); i++) if (MAPS.contains(pk.opt(i))) q.put(pk.opt(i)); o.put("paintKeep", q); }
            if (c.opt("name") instanceof String) o.put("name", cut(c.optString("name"), 60));
            if (c.opt("credit") instanceof String) o.put("credit", cut(c.optString("credit"), 120));
            JSONObject m = c.optJSONObject("mats"), p = c.optJSONObject("parts"), pk = c.optJSONObject("pick"), li = c.optJSONObject("lights");
            if (m == null) m = new JSONObject();
            if (p == null) p = new JSONObject();
            if (pk == null) pk = new JSONObject();
            if (li == null) li = new JSONObject();
            for (String k : MAT_ROLES) if (m.has(k) && m.opt(k) != null && m.opt(k) != JSONObject.NULL) o.getJSONObject("mats").put(k, refs(m.opt(k)));
            for (String k : MAT_ROLES) { JSONArray l = refs(pk.opt(k)); if (l.length() > 0) { if (!o.has("pick")) o.put("pick", new JSONObject()); o.getJSONObject("pick").put(k, l); } }
            for (String k : LIGHT_ROLES) {
                Object xo = li.opt(k);
                if (!(xo instanceof JSONObject)) continue;
                JSONObject xl = (JSONObject) xo, y = new JSONObject();
                if (hex(xl.opt("color"))) y.put("color", xl.optString("color").toLowerCase());
                if (EFFECTS.contains(xl.opt("effect"))) y.put("effect", xl.optString("effect"));
                if (CUSTOM_LIGHTS.contains(k)) { if (WHEN.contains(xl.opt("when"))) y.put("when", xl.optString("when")); if (Boolean.TRUE.equals(xl.opt("late"))) y.put("late", true); }
                JSONObject gl = Lamp.cleanGlow(xl.opt("glow"));
                Double kk = num(xl.opt("k"), 0.2, 3);
                if (gl == null && kk != null) { gl = new JSONObject(); gl.put("intensity", Math.max(0.3, Math.min(1.5, kk))); }
                if (gl != null) y.put("glow", gl);
                for (String q : new String[] { "beam", "beamHigh" }) { JSONObject bb = Lamp.cleanBeam(xl.opt(q)); if (bb != null) y.put(q, bb); }
                if (y.length() > 0 || CUSTOM_LIGHTS.contains(k)) { if (!o.has("lights")) o.put("lights", new JSONObject()); o.getJSONObject("lights").put(k, y); }
            }
            List<String> mw = new ArrayList<>(MOVERS); mw.addAll(WINDOWS);
            for (String k : mw) {
                Object xo = p.opt(k);
                if (!(xo instanceof JSONObject)) continue;
                JSONObject xp = (JSONObject) xo, y = new JSONObject();
                y.put("meshes", refs(xp.opt("meshes")));
                if (STYLES.contains(xp.opt("style"))) y.put("style", xp.optString("style"));
                Double an = num(xp.opt("angle"), 5, 150); if (an != null) y.put("angle", an);
                if (Boolean.TRUE.equals(xp.opt("flip"))) y.put("flip", true);
                if (Boolean.TRUE.equals(xp.opt("other"))) y.put("other", true);
                if ("slide".equals(xp.opt("mode")) || "tilt".equals(xp.opt("mode"))) y.put("mode", xp.optString("mode"));
                Double tr = num(xp.opt("travel"), 0.1, 1.5); if (tr != null) y.put("travel", tr);
                if (vec(xp.opt("at")) != null && vec(xp.opt("axis")) != null) { y.put("at", vec(xp.opt("at"))); y.put("axis", vec(xp.opt("axis"))); }
                JSONArray pv = vec(xp.opt("piv"));
                if (pv != null && Math.abs(pv.getDouble(0)) <= 1.5 && Math.abs(pv.getDouble(1)) <= 1.5 && Math.abs(pv.getDouble(2)) <= 1.5) y.put("piv", pv);
                o.getJSONObject("parts").put(k, y);
            }
            if (p.has("wheels")) o.getJSONObject("parts").put("wheels", refs(p.opt("wheels")));
            JSONObject wp0 = c.optJSONObject("wheelPos");
            if (wp0 != null) {
                JSONObject wp = new JSONObject();
                for (String k : new String[] { "frontLeft", "frontRight", "rearLeft", "rearRight" }) if (vec(wp0.opt(k)) != null) wp.put(k, vec(wp0.opt(k)));
                if (wp.length() == 4) o.put("wheelPos", wp);
            }
            JSONArray tp = c.optJSONArray("turnPos");
            if (tp != null && tp.length() == 2 && vec(tp.opt(0)) != null && vec(tp.opt(1)) != null) o.put("turnPos", new JSONArray().put(vec(tp.opt(0))).put(vec(tp.opt(1))));
            JSONObject pl = c.optJSONObject("plates");
            if (pl != null) {
                JSONObject ps = new JSONObject();
                if (Arrays.asList("blue", "green", "yellow", "bignev", "police", "emergency", "coach", "trailer", "lowspeed", "embassy", "consulate", "hkmo", "custom").contains(pl.opt("style"))) ps.put("style", pl.optString("style"));
                if (pl.opt("text") instanceof String) ps.put("text", cut(pl.optString("text").toUpperCase().replaceAll("[^A-Z0-9\\u4e00-\\u9fa5]", ""), 8));
                if (hex(pl.opt("bg"))) ps.put("bg", pl.optString("bg").toLowerCase());
                if (hex(pl.opt("fg"))) ps.put("fg", pl.optString("fg").toLowerCase());
                for (String k : new String[] { "front", "rear" }) {
                    JSONObject xp = pl.optJSONObject(k);
                    if (xp == null) continue;
                    JSONObject y = new JSONObject();
                    if (Boolean.TRUE.equals(xp.opt("on"))) y.put("on", true);
                    if (vec(xp.opt("at")) != null) y.put("at", vec(xp.opt("at")));
                    Double w = num(xp.opt("w"), 0.2, 0.8); if (w != null) y.put("w", w);
                    Double yaw = num(xp.opt("yaw"), -60, 60); if (yaw != null) y.put("yaw", yaw);
                    Double pitch = num(xp.opt("pitch"), -60, 60); if (pitch != null) y.put("pitch", pitch);
                    boolean v2 = Integer.valueOf(2).equals(pl.opt("v")) || (pl.opt("v") instanceof Number && ((Number) pl.opt("v")).intValue() == 2);
                    if (!v2 && "front".equals(k) && y.has("yaw") && y.getDouble("yaw") != 0) y.put("yaw", -y.getDouble("yaw"));
                    if (!v2 && "rear".equals(k) && y.has("pitch") && y.getDouble("pitch") != 0) y.put("pitch", -y.getDouble("pitch"));
                    ps.put(k, y);
                }
                ps.put("v", 2);
                if (ps.has("front") || ps.has("rear") || ps.has("style") || ps.has("text")) o.put("plates", ps);
            }
            JSONArray wr = c.optJSONArray("wraps");
            if (wr != null) {
                JSONArray ws = new JSONArray();
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < Math.min(MAX_WRAPS, wr.length()); i++) { JSONObject y = Wraps.clean(wr.opt(i)); if (y != null && ids.add(y.optString("id"))) ws.put(y); }
                if (ws.length() > 0) o.put("wraps", ws);
            }
        } catch (Exception e) { Log.w("car3d", "车模配置读不出来", e); return null; }
        return o.has("length") || o.has("scale") ? o : null;
    }
    static final int MAX_WRAPS = 40;
    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    // ================= 现在用的车模 =================
    /** 一个车模：GLB（直接内存）、配置、名字、署名；builtin 是默认车模 W70，broken 是导入的读不出来、先用了默认的 */
    static final class Rec { ByteBuffer buf; JSONObject cfg; String name = "", credit = ""; boolean builtin, broken; int fmt = FMT; }
    /** 车模包的格式版本（同网页版 carmodel.js 的 PACK_FMT，导出时写在 asset.extras.d3fmt，以前导出的没写当 1）：网页版的车模包里加了这里还显示不了的配置时那边加 1，
     *  这里搬完再跟上；导入比这个新的包照样显示能显示的，提示更新应用 */
    static final int FMT = 1;
    /** 网页版（调整车模、导出为 GLB）的网址 */
    static final String WEB = "cat-bl.github.io/car3d-web";

    static File file(Context c) { return new File(c.getFilesDir(), "model.glb"); }
    static Rec def(Context c) throws Exception {
        Rec r = new Rec();
        r.builtin = true;
        r.buf = read(c.getAssets().open("w70.glb"));
        JSONObject w = w70();
        r.name = w.optString("name"); r.credit = w.optString("credit");
        return r;
    }
    /** 现在用的车模：导入过车模包就用它（读不出来、配置不对退回默认） */
    static Rec current(Context c) {
        File f = file(c);
        if (f.exists()) {
            try {
                Rec r = new Rec();
                try (FileInputStream in = new FileInputStream(f); FileChannel ch = in.getChannel()) {
                    r.buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
                }
                JSONObject x = extras(r.buf), cfg = x == null ? null : sanitize(x.opt("d3car"));
                if (cfg == null) throw new IllegalStateException("车模包里没有配置");
                r.fmt = x.optInt("d3fmt", 1);
                r.cfg = cfg;
                r.name = cfg.optString("name", "导入的车模"); r.credit = cfg.optString("credit", "");
                return r;
            } catch (Exception e) { Log.w("car3d", "导入的车模读不出来，用默认车模", e); }
            try { Rec r = def(c); r.broken = true; return r; } catch (Exception e) { throw new RuntimeException(e); }
        }
        try { return def(c); } catch (Exception e) { throw new RuntimeException(e); }
    }
    /** GLB 里的车模配置（asset.extras.d3car），校验过；没有返回 null */
    static JSONObject configOf(ByteBuffer glb) throws Exception {
        JSONObject x = extras(glb);
        return x == null ? null : sanitize(x.opt("d3car"));
    }
    private static JSONObject extras(ByteBuffer glb) throws Exception {
        JSONObject a = Gltf.read(glb).json.optJSONObject("asset");
        return a == null ? null : a.optJSONObject("extras");
    }
    /** 导入车模包（网页版"导出为 GLB"导出的 GLB）：要带配置，存进应用自己的目录。返回车模名字；不是车模包抛错（错误信息给人看） */
    /** 导入的进度：步骤、0～1 */
    interface Step { void at(String step, float f); }
    static String importPack(Context c, Uri uri, Step st) throws Exception {
        long size = -1;
        try (android.database.Cursor q = c.getContentResolver().query(uri, new String[] { android.provider.OpenableColumns.SIZE }, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) size = q.getLong(0);
        } catch (Exception ignored) { }
        if (size > 200L * 1024 * 1024) throw new IllegalArgumentException("文件太大了");
        byte[] b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(size > 0 ? (int) size : 1 << 20);
            byte[] buf = new byte[1 << 18];
            int n, pct = -1;
            long got = 0;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                got += n;
                if (got > 200L * 1024 * 1024) throw new IllegalArgumentException("文件太大了");
                int k = size > 0 ? (int) (got * 100 / size) : -1;
                if (k != pct && k >= 0) { pct = k; st.at("读取文件", 0.01f + 0.11f * Math.min(1, got / (float) size)); }
            }
            b = o.toByteArray();
        }
        st.at("检查车模包", 0.13f);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        if (b.length < 20 || bb.getInt(0) != 0x46546C67) throw new IllegalArgumentException("不是车模包：要网页版「调整车模」里导出的 .glb");
        JSONObject cfg;
        try { cfg = configOf(bb); }
        catch (Exception e) { throw new IllegalArgumentException("车模包读不出来：文件可能没拷完整，或者坏了"); } // 截断的文件读到一半就越界
        if (cfg == null) throw new IllegalArgumentException("这个 .glb 里没有车模配置：先在网页版（" + WEB + "）里导入、调整好，再「导出为 GLB」");
        st.at("保存", 0.14f);
        File tmp = new File(c.getFilesDir(), "model.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            int chunk = 1 << 20, pct = -1;
            for (int off = 0; off < b.length; off += chunk) {
                o.write(b, off, Math.min(chunk, b.length - off));
                int k = (int) ((off + (long) chunk) * 20 / b.length);
                if (k != pct) { pct = k; st.at("保存", 0.14f + 0.02f * Math.min(1, (off + chunk) / (float) b.length)); }
            }
        }
        File f = file(c);
        GeoCache.clear(c);
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new IllegalStateException("存不下来"); }
        return cfg.optString("name", "导入的车模");
    }
    static void reset(Context c) { file(c).delete(); GeoCache.clear(c); }
    static ByteBuffer read(InputStream in) throws Exception {
        byte[] b = Half.readAll(in);
        ByteBuffer bb = ByteBuffer.allocateDirect(b.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(b).position(0);
        return bb;
    }

    // ================= 编号、按配置找网格 / 材质 =================
    /** 解析完马上编号：网格、材质按遍历顺序（同一个 GLB 每次都一样）；模型自带的灯和相机拿掉 */
    static final class D3 { final List<Mesh> meshes = new ArrayList<>(); final List<Mat> mats = new ArrayList<>(); }
    static D3 ids(Obj3D model) {
        D3 d = new D3();
        List<Obj3D> drop = new ArrayList<>();
        model.traverse(o -> {
            if (o.has("isLight") || o.has("isCamera")) { drop.add(o); return; }
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            m.put("mid", d.meshes.size());
            d.meshes.add(m);
            Mat mt = m.material;
            if (!mt.ud.containsKey("matId")) {
                mt.ud.put("matId", d.mats.size());
                d.mats.add(mt);
                if (!mt.ud.containsKey("gltf")) mt.ud.put("gltfDefault", true);
            }
        });
        for (Obj3D o : drop) if (o.parent != null) o.parent.remove(o);
        model.put("d3", d);
        return d;
    }
    /** 配置里的引用 → 网格：数字是编号，字符串是名字的正则（内置配置用） */
    static List<Mesh> meshRefs(D3 d, Object refs) {
        List<Mesh> out = new ArrayList<>();
        if (!(refs instanceof JSONArray)) return out;
        JSONArray l = (JSONArray) refs;
        for (int i = 0; i < l.length(); i++) {
            Object r = l.opt(i);
            if (r instanceof Number) { int k = ((Number) r).intValue(); if (k >= 0 && k < d.meshes.size() && !out.contains(d.meshes.get(k))) out.add(d.meshes.get(k)); }
            else if (r instanceof String) { Pattern re = Pattern.compile((String) r); for (Mesh m : d.meshes) if (re.matcher(m.name).find() && !out.contains(m)) out.add(m); }
        }
        return out;
    }
    static List<Mat> matRefs(D3 d, Object refs) {
        List<Mat> out = new ArrayList<>();
        if (!(refs instanceof JSONArray)) return out;
        JSONArray l = (JSONArray) refs;
        for (int i = 0; i < l.length(); i++) {
            Object r = l.opt(i);
            if (r instanceof Number) { int k = ((Number) r).intValue(); if (k >= 0 && k < d.mats.size() && !out.contains(d.mats.get(k))) out.add(d.mats.get(k)); }
            else if (r instanceof String) for (Mat m : d.mats) if (r.equals(m.name) && !out.contains(m)) out.add(m);
        }
        return out;
    }
    /** 网格的世界外框（按几何体外框） */
    static Box3 box(Mesh o) {
        return o.geometry.boundingBox().clone().applyMatrix4(o.matrixWorld);
    }

    // ================= 名字按词认（金属度靠不住的车模要用） =================
    static String norm(String s) {
        if (s == null) s = "";
        return s.replaceAll("([a-z\\u00df-\\u00ff])([A-Z\\u00c0-\\u00de])", "$1 $2").replaceAll("([A-Z\\u00c0-\\u00de])([A-Z\\u00c0-\\u00de][a-z\\u00df-\\u00ff])", "$1 $2")
            .replaceAll("(\\d+)", " $1 ").replaceAll("([\\u3400-\\u9fff]+)", " $1 ").toLowerCase().replaceAll("[^a-z0-9\\u00df-\\u00ff\\u3400-\\u9fff]+", " ").trim();
    }
    static final List<String> HUES = Arrays.asList("red", "blue", "green", "grey", "gray", "yellow", "orange", "white", "black", "silver", "gold", "brown", "purple", "pink", "beige", "cyan", "violet", "bronze", "color", "colour");
    static List<String> words(String s) {
        List<String> t = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (!w.isEmpty()) t.add(w);
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            if (w.equals("light") && i + 1 < t.size() && HUES.contains(t.get(i + 1))) t.set(i, "pale");
            else if (w.startsWith("light") && HUES.contains(w.substring(5))) t.set(i, w.substring(5));
        }
        return t;
    }
    static final class WL { final List<String> w = new ArrayList<>(), zh = new ArrayList<>(); }
    static WL wl(String[] a, String... zh) { WL o = new WL(); o.w.addAll(Arrays.asList(a)); o.zh.addAll(Arrays.asList(zh)); return o; }
    static WL join(WL... l) { WL o = new WL(); for (WL x : l) { o.w.addAll(x.w); o.zh.addAll(x.zh); } return o; }
    static boolean hasW(List<String> t, WL d) {
        if (!d.zh.isEmpty()) { StringBuilder z = new StringBuilder(); for (String s : t) z.append(s); for (String c : d.zh) if (z.indexOf(c) >= 0) return true; }
        for (int i = 0; i < t.size(); i++) {
            String x = t.get(i);
            if (d.w.contains(x) || (i + 1 < t.size() && d.w.contains(x + t.get(i + 1)))) return true;
            for (String w : d.w) if (w.length() >= 5 && x.length() > w.length() && (x.startsWith(w) || x.endsWith(w))) return true;
        }
        return false;
    }
    static final WL N_PAINT = wl(new String[] { "paint", "paints", "painted", "paintwork", "carpaint", "bodypaint", "lack", "autolack", "lackierung", "karosserie", "carrosserie", "carroceria", "carrozzeria",
        "peinture", "pintura", "vernice", "bodycolor", "bodycolour", "bodycol", "carcolor", "carcolour", "bodymain", "mainbody", "bodyshell", "bodywork" }, "车漆", "漆面", "车身漆", "车身颜色", "车身色");
    static final WL N_BODY = wl(new String[] { "body", "carbody", "exterior", "ext", "shell", "outer" }, "车身", "车壳", "外壳", "外观", "钣金");
    static final WL N_GLASS = wl(new String[] { "glass", "glas", "glasses", "window", "windows", "windshield", "windscreen", "scheibe", "vidro", "vetro", "verre", "vitre", "vitres", "vidrio" }, "玻璃", "车窗", "挡风", "风挡");
    static final WL N_RIM = wl(new String[] { "rim", "rims", "alloy", "alloys", "felge", "felgen", "jante", "jantes", "cerchio", "cerchi", "hubcap", "hubcaps" }, "轮毂", "轮圈", "钢圈", "轮辋", "铝圈");
    static final WL N_CALIPER = wl(new String[] { "caliper", "calipers", "calliper", "callipers", "bremssattel", "etrier", "pinza" }, "卡钳", "制动钳", "刹车钳");
    static final WL N_TIRE = wl(new String[] { "tire", "tires", "tyre", "tyres", "reifen", "pneu", "pneus", "gomme", "neumatico", "rubber" }, "轮胎", "车胎", "胎");
    static final WL N_LAMP = wl(new String[] { "light", "lights", "lamp", "lamps", "headlight", "headlights", "headlamp", "headlamps", "taillight", "taillights", "taillamp", "taillamps", "rearlight", "brakelight", "stoplight",
        "foglight", "foglamp", "indicator", "indicators", "blinker", "blinkers", "signal", "drl", "led", "leds", "lens", "lenses", "reflector", "scheinwerfer", "rueckleuchte", "rückleuchte",
        "heckleuchte", "phare", "phares", "feu", "feux", "faro", "fari", "luce", "luci" }, "灯", "透镜");
    static final WL NOT_PAINT = join(wl(new String[] { "interior", "inner", "inside", "int", "seat", "seats", "dash", "dashboard", "console", "trim", "trims", "chrome", "chromed", "chromium", "plastic", "plastics", "plast",
        "carbon", "grill", "grille", "logo", "logos", "badge", "badges", "emblem", "decal", "decals", "sticker", "stickers", "label", "text", "plate", "license", "licence", "under", "underbody",
        "bottom", "chassis", "exhaust", "wheel", "wheels", "brake", "disc", "disk", "rotor", "seal", "gasket", "wiper", "wipers", "leather", "fabric", "cloth", "carpet", "wood", "steering",
        "pedal", "belt", "fiberglass", "fibreglass" },
        "内", "座", "饰", "镀铬", "电镀", "橡胶", "塑料", "碳", "格栅", "中网", "标", "牌", "底盘", "车底", "排气", "把手", "雨刮", "密封", "皮", "方向盘", "仪表", "钢琴"),
        N_GLASS, N_LAMP, N_TIRE, N_RIM, N_CALIPER);
    static final WL NOT_BODY = wl(new String[] { "black", "blk", "matt", "matte", "dark" }, "黑", "哑光", "亚光");
    static final WL METAL = wl(new String[] { "chrome", "chromed", "chromium", "metal", "alu", "aluminium", "aluminum", "steel", "iron", "silver", "gold", "bronze", "copper", "brass", "titanium", "nickel",
        "rim", "rims", "alloy", "alloys", "felge", "felgen", "jante", "exhaust" }, "镀铬", "电镀", "铬", "金属", "铝", "钢", "银", "铜", "钛", "轮毂", "排气");
    static final WL NONMETAL = join(wl(new String[] { "interior", "inner", "inside", "seat", "seats", "leather", "fabric", "cloth", "carpet", "plastic", "plastics", "rubber", "matt", "matte", "dash", "dashboard", "wood" },
        "内饰", "座", "塑料", "橡胶", "皮", "布", "地毯", "哑光", "亚光"), N_GLASS, N_TIRE);
    /** 名字像车漆吗：2 明说是漆，1 只说是车身外面，0 不是 */
    static int paintName(String s) {
        List<String> t = words(s);
        if (hasW(t, NOT_PAINT)) return 0;
        if (hasW(t, N_PAINT)) return 2;
        return hasW(t, N_BODY) && !hasW(t, NOT_BODY) ? 1 : 0;
    }
    /** 金属度靠不住的车模（同 metalFix）：返回改了几个 */
    static int metalFix(D3 d, List<Mat> keep) {
        List<Mat> list = new ArrayList<>(), full = new ArrayList<>(), bad = new ArrayList<>();
        for (Mat m : d.mats) if (m != null && m.isStandard() && !m.ud.containsKey("gltfDefault")) list.add(m);
        for (Mat m : list) if (m.metalness >= 0.99f && m.metalnessMap == null) full.add(m);
        for (Mat m : full) if (non(m)) bad.add(m);
        if (!(bad.size() >= 2 || (list.size() >= 4 && full.size() >= 0.7 * list.size()))) return 0;
        int n = 0;
        for (Mat m : full) {
            if (keep.contains(m) || (!non(m) && hasW(words(m.name), METAL) && paintName(m.name) == 0)) continue;
            m.metalness = 0;
            n++;
        }
        for (Mat m : list) if (m.isPhysical() && m.metalness < 0.99f) { float k = Math.max(m.specularColor.r, Math.max(m.specularColor.g, m.specularColor.b)); if (k > 1) m.specularColor.multiplyScalar(1 / k); }
        return n;
    }
    private static boolean non(Mat m) { return m.opacity < 0.95f || m.transmission > 0 || hasW(words(m.name), NONMETAL); }
    /** 双面材质的网格：三角形的绕向理顺到和它自己的法线一致（同 wind）。返回翻了几个三角形 */
    static int wind(Geo g) {
        Attr p = g.attr("position"), n = g.attr("normal");
        if (p == null || n == null || g.ud.containsKey("wound")) return 0;
        g.ud.put("wound", 1);
        Attr ix = g.index;
        int cnt = ix != null ? ix.count : p.count;
        int[] flips = new int[64];
        int nf = 0;
        int[] v = new int[3];
        for (int i = 0; i + 2 < cnt; i += 3) {
            for (int k = 0; k < 3; k++) v[k] = ix != null ? ix.idx(i + k) : i + k;
            float ax = p.getX(v[0]), ay = p.getY(v[0]), az = p.getZ(v[0]);
            float ux = p.getX(v[1]) - ax, uy = p.getY(v[1]) - ay, uz = p.getZ(v[1]) - az, wx = p.getX(v[2]) - ax, wy = p.getY(v[2]) - ay, wz = p.getZ(v[2]) - az;
            float nx = n.getX(v[0]) + n.getX(v[1]) + n.getX(v[2]), ny = n.getY(v[0]) + n.getY(v[1]) + n.getY(v[2]), nz = n.getZ(v[0]) + n.getZ(v[1]) + n.getZ(v[2]);
            if ((uy * wz - uz * wy) * nx + (uz * wx - ux * wz) * ny + (ux * wy - uy * wx) * nz < 0) {
                if (nf == flips.length) flips = java.util.Arrays.copyOf(flips, nf * 2);
                flips[nf++] = i;
            }
        }
        if (nf == 0) return 0;
        if (ix == null) {
            g.ud.put("woundCopy", 1);
            int[] a = new int[cnt];
            for (int j = 0; j < cnt; j++) a[j] = j;
            g.setIndex(Attr.index(a, cnt));
            ix = g.index;
        } else if (ix.buf.data.isReadOnly() || shared(ix)) { // 索引在车模文件里（只读映射）或者别的图元也用：复制一份再改
            g.ud.put("woundCopy", 1);
            int[] a = new int[cnt];
            int mx = 0;
            for (int j = 0; j < cnt; j++) { a[j] = ix.idx(j); mx = Math.max(mx, a[j]); }
            g.setIndex(Attr.index(a, mx));
            ix = g.index;
        }
        for (int f = 0; f < nf; f++) { int i = flips[f], s = ix.idx(i + 1); ix.setIdx(i + 1, ix.idx(i + 2)); ix.setIdx(i + 2, s); }
        ix.buf.version++;
        return nf;
    }
    private static boolean shared(Attr a) { return a.offset != 0 || a.buf.data.capacity() != a.count * Attr.size(a.type); }
}
