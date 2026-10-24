package com.x3card.car3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 贴纸的贴图（照网页版 carwrap.js 的画布部分逐段搬）：拉花（腰线、斜切条、侧裙条、赛道条纹、宽条、手绘线、像素渐隐、速度线、格子旗、火焰、迷彩）、
 * 文字（卡片带的 8 种字体 + 系统字体；用导入的字体写的字用车模包里画好的图）、图片（裁剪、去背景、调色、边缘渐隐、白边）。
 * 拉花画完统一加工：配色（单色、交替、渐变）、虚线、两端渐隐、描边。画布按米画，最长边不超过 2048
 */
final class WrapArt {
    private WrapArt() {}
    static final float DEG = (float) (Math.PI / 180);

    // ================= 拉花模板（参数：键、名字、最小、最大、步长、默认、单位） =================
    static final class Tpl { final String frame; final boolean lines; final Object[][] params; Tpl(String f, boolean l, Object[][] p) { frame = f; lines = l; params = p; } }
    static final Map<String, Tpl> TPL = new LinkedHashMap<>();
    private static Object[] q(String k, String n, double a, double b, double s, double d, String u) { return new Object[] { k, n, a, b, s, d, u }; }
    static {
        TPL.put("belt", new Tpl("side", true, new Object[][] { q("n", "几道线", 1, 6, 1, 3, "n"), q("lw", "线宽", 3, 60, 1, 16, "mm"), q("gap", "线的间距", 2, 60, 1, 12, "mm"), q("lwr", "外粗里细", 0.2, 1, 0.05, 1, "pct"),
            q("y", "高度", 0.25, 0.92, 0.005, 0.6, "hgt"), q("rise", "车头那端高低", -0.25, 0.25, 0.005, -0.05, "dh"), q("s0", "起点", 0, 0.5, 0.005, 0.05, "pos"),
            q("s1", "拐点", 0.3, 0.98, 0.005, 0.72, "pos"), q("drop", "往下拐的角度", 0, 80, 1, 52, "deg"), q("dlen", "往下拐多少", 0, 0.6, 0.005, 0.34, "hgt"),
            q("round", "拐角圆滑", 0, 1, 0.05, 0.6, "pct"), q("tip", "起头渐细", 0, 0.6, 0.02, 0.2, "pct") }));
        TPL.put("slash", new Tpl("side", true, new Object[][] { q("s", "前后位置", 0, 1, 0.005, 0.78, "pos"), q("y", "高度", 0.12, 0.88, 0.005, 0.42, "hgt"), q("ang", "倾斜", -60, 60, 1, 28, "deg"),
            q("len", "长度", 0.1, 1, 0.01, 0.55, "hgt"), q("wd", "宽度", 1, 40, 0.5, 7, "cm"), q("n", "几道", 1, 5, 1, 2, "n"), q("gap", "间距", 1, 30, 0.5, 3, "cm"), q("tip", "尾巴渐细", 0, 1, 0.05, 0.6, "pct") }));
        TPL.put("rocker", new Tpl("side", false, new Object[][] { q("y", "离地高度", 0.04, 0.5, 0.005, 0.16, "hgt"), q("wd", "粗细", 0.5, 30, 0.5, 2.5, "cm"), q("s0", "起点", 0, 0.5, 0.005, 0.2, "pos"),
            q("s1", "终点", 0.5, 1, 0.005, 0.8, "pos"), q("pt", "两头尖", 0, 0.45, 0.01, 0, "pct"), q("fine", "下面加一道细线", 0, 1, 1, 1, "sw"), q("fw", "细线宽", 2, 20, 1, 5, "mm"), q("fg", "细线间距", 0.5, 10, 0.5, 1.5, "cm") }));
        TPL.put("race2", new Tpl("top", true, new Object[][] { q("wd", "每道宽度", 5, 60, 0.5, 24, "cm"), q("gap", "中间间距", 2, 40, 0.5, 8, "cm"), q("edge", "描边", 0, 30, 1, 0, "mm"),
            q("mid", "中间加一道细线", 0, 1, 1, 0, "sw"), q("mw", "细线宽", 3, 40, 1, 10, "mm"), q("s0", "从车头", 0, 0.5, 0.005, 0, "pos"), q("s1", "到车尾", 0.5, 1, 0.005, 1, "pos"), q("off", "左右偏移", -50, 50, 0.5, 0, "cm") }));
        TPL.put("band", new Tpl("top", false, new Object[][] { q("wd", "宽度", 5, 150, 1, 40, "cm"), q("off", "左右偏移", -80, 80, 0.5, 0, "cm"), q("edge", "描边", 0, 30, 1, 6, "mm"),
            q("s0", "从车头", 0, 0.5, 0.005, 0, "pos"), q("s1", "到车尾", 0.5, 1, 0.005, 1, "pos") }));
        TPL.put("path", new Tpl("any", true, new Object[][] { q("n", "几道线", 1, 6, 1, 2, "n"), q("lw", "线宽", 3, 150, 1, 20, "mm"), q("gap", "线的间距", 2, 80, 1, 10, "mm"), q("lwr", "外粗里细", 0.2, 1, 0.05, 1, "pct"),
            q("tip", "起头渐细", 0, 0.6, 0.02, 0.15, "pct"), q("tip2", "收尾渐细", 0, 0.6, 0.02, 0.15, "pct") }));
        TPL.put("pixel", new Tpl("any", false, new Object[][] { q("s0", "从", 0, 1, 0.005, 0.05, "pos"), q("s1", "到", 0, 1, 0.005, 0.7, "pos"), q("y", "位置", 0, 1, 0.005, 0.45, "frac"), q("hgt", "宽窄", 0.05, 1, 0.01, 0.35, "frac"),
            q("cell", "方块大小", 1, 20, 0.5, 5, "cm"), q("dir", "后面密前面疏", 0, 1, 1, 0, "sw"), q("dens", "密度", 0.1, 1, 0.05, 1, "pct"), q("mix", "混色多少", 0, 1, 0.05, 0, "pct"), q("seed", "换个花样", 1, 99, 1, 1, "seed") }));
        TPL.put("speed", new Tpl("any", true, new Object[][] { q("s0", "从", 0, 1, 0.005, 0.1, "pos"), q("s1", "到", 0, 1, 0.005, 0.9, "pos"), q("y", "位置", 0, 1, 0.005, 0.45, "frac"), q("hgt", "宽窄", 0.05, 1, 0.01, 0.3, "frac"),
            q("n", "几道", 3, 40, 1, 12, "n"), q("lw", "线宽", 3, 60, 1, 12, "mm"), q("var", "长短变化", 0, 1, 0.05, 0.6, "pct"), q("tip", "尾巴渐细", 0, 1, 0.05, 0.5, "pct"), q("seed", "换个花样", 1, 99, 1, 1, "seed") }));
        TPL.put("checker", new Tpl("any", false, new Object[][] { q("s0", "从", 0, 1, 0.005, 0.6, "pos"), q("s1", "到", 0, 1, 0.005, 1, "pos"), q("y", "位置", 0, 1, 0.005, 0.5, "frac"), q("hgt", "宽窄", 0.05, 1, 0.01, 0.4, "frac"),
            q("cell", "格子大小", 2, 30, 0.5, 8, "cm"), q("frag", "往前碎开", 0, 1, 1, 1, "sw"), q("seed", "换个花样", 1, 99, 1, 1, "seed") }));
        TPL.put("flame", new Tpl("any", false, new Object[][] { q("s0", "从车头", 0, 0.8, 0.005, 0.02, "pos"), q("len", "烧多长", 0.1, 1, 0.01, 0.55, "pct"), q("y", "位置", 0, 1, 0.005, 0.42, "frac"), q("hgt", "宽窄", 0.05, 1, 0.01, 0.45, "frac"),
            q("n", "几条火舌", 3, 14, 1, 7, "n"), q("curl", "卷曲", 0, 1, 0.05, 0.5, "pct"), q("seed", "换个花样", 1, 99, 1, 1, "seed") }));
        TPL.put("camo", new Tpl("any", false, new Object[][] { q("s0", "从", 0, 1, 0.005, 0, "pos"), q("s1", "到", 0, 1, 0.005, 1, "pos"), q("y", "位置", 0, 1, 0.005, 0.5, "frac"), q("hgt", "宽窄", 0.05, 1, 0.01, 1, "frac"),
            q("style", "数码迷彩（方块）", 0, 1, 1, 0, "sw"), q("scale", "花纹大小", 5, 80, 1, 30, "cm"), q("seed", "换个花样", 1, 99, 1, 1, "seed") }));
    }
    static final List<String> TPL_ORDER = Arrays.asList("belt", "slash", "rocker", "race2", "band", "path", "pixel", "speed", "checker", "flame", "camo");
    private static final String C1 = "#16181b";
    private static final Map<String, String> C1T = new HashMap<>(), C2 = new HashMap<>(), C3 = new HashMap<>();
    static {
        C1T.put("flame", "#d62c1a"); C1T.put("camo", "#4b5320");
        C2.put("rocker", "#16181b"); C2.put("race2", "#ffffff"); C2.put("band", "#ffffff"); C2.put("pixel", "#c8102e"); C2.put("checker", "#ffffff"); C2.put("flame", "#f39c12"); C2.put("camo", "#7c7a52");
        C3.put("flame", "#ffe066"); C3.put("camo", "#2f3326");
    }
    /** 这张拉花的参数（缺的用默认、超出范围的夹回去） */
    static Map<String, Float> params(JSONObject w) {
        Tpl t = TPL.get(w.optString("tpl"));
        JSONObject src = w.optJSONObject("p");
        Map<String, Float> p = new HashMap<>();
        for (Object[] q : t.params) {
            String k = (String) q[0];
            double v = src != null && src.opt(k) instanceof Number ? src.optDouble(k) : Double.NaN;
            p.put(k, (float) (!Double.isNaN(v) && !Double.isInfinite(v) ? Math.max((double) q[2], Math.min((double) q[3], v)) : (double) q[5]));
        }
        return p;
    }
    /** 颜色：[主色, 第二色, 第三色, 交替色, 渐变的另一头, 描边] */
    static int[] colors(JSONObject w) {
        String t = w.optString("tpl");
        String[] c = { w.optString("c1", C1T.containsKey(t) ? C1T.get(t) : C1), w.optString("c2", C2.containsKey(t) ? C2.get(t) : "#ffffff"), w.optString("c3", C3.containsKey(t) ? C3.get(t) : "#ffffff"),
            w.optString("ac", "#ffffff"), w.optString("gc", "#ffffff"), w.optString("oc", "#ffffff") };
        int[] o = new int[6];
        for (int i = 0; i < 6; i++) { try { o[i] = Color.parseColor(c[i]); } catch (Exception e) { o[i] = 0xffffffff; } }
        return o;
    }
    static boolean topOf(JSONObject w) { String f = TPL.get(w.optString("tpl")).frame; return "top".equals(f) || ("any".equals(f) && "top".equals(w.optString("pf"))); }

    // ================= 画布上的小工具 =================
    static final int MAXPX = 2048;
    /** 一条中线（像素点）+ 每个点的半宽 → 填一条带子 */
    private static void ribbon(Canvas x, Paint p, float[][] pts, float[] half) {
        int n = pts.length;
        if (n < 2) return;
        float[][] L = new float[n][], R = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
            float dx = b[0] - a[0], dy = b[1] - a[1], l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l == 0) l = 1;
            float nx = -dy / l, ny = dx / l;
            L[i] = new float[] { pts[i][0] + nx * half[i], pts[i][1] + ny * half[i] }; R[i] = new float[] { pts[i][0] - nx * half[i], pts[i][1] - ny * half[i] };
        }
        Path path = new Path();
        path.moveTo(L[0][0], L[0][1]);
        for (int i = 1; i < n; i++) path.lineTo(L[i][0], L[i][1]);
        for (int i = n - 1; i >= 0; i--) path.lineTo(R[i][0], R[i][1]);
        path.close();
        x.drawPath(path, p);
    }
    /** 一条中线平移 o（像素，往左手边为正）后的点 */
    private static float[][] offset(float[][] pts, float o) {
        int n = pts.length;
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
            float dx = b[0] - a[0], dy = b[1] - a[1], l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l == 0) l = 1;
            out[i] = new float[] { pts[i][0] - dy / l * o, pts[i][1] + dx / l * o };
        }
        return out;
    }
    static final class Rs { float[][] pts; float[] f; }
    /** 折线（像素）按间距 st 重新取点，f 是从头到这个点占全长的比例 */
    private static Rs resample(List<float[]> line, float st) {
        List<float[]> pts = new ArrayList<>();
        List<Float> acc = new ArrayList<>();
        pts.add(line.get(0)); acc.add(0f);
        for (int i = 1; i < line.size(); i++) {
            float[] a = line.get(i - 1), b = line.get(i);
            float d = (float) Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]));
            int n = Math.max(1, (int) Math.ceil(d / st));
            for (int k = 1; k <= n; k++) { pts.add(new float[] { a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n }); acc.add(acc.get(acc.size() - 1) + d / n); }
        }
        float tot = acc.get(acc.size() - 1);
        if (tot == 0) tot = 1;
        Rs r = new Rs();
        r.pts = pts.toArray(new float[0][]);
        r.f = new float[acc.size()];
        for (int i = 0; i < r.f.length; i++) r.f[i] = acc.get(i) / tot;
        return r;
    }
    private static List<float[]> list(float[]... p) { return new ArrayList<>(Arrays.asList(p)); }
    /** 手画的点连成光滑的线（Catmull-Rom，每段 8 个点） */
    private static List<float[]> smoothLine(List<float[]> p) {
        if (p.size() < 3) return new ArrayList<>(p);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < p.size() - 1; i++) {
            float[] a = p.get(Math.max(0, i - 1)), b = p.get(i), c = p.get(i + 1), d = p.get(Math.min(p.size() - 1, i + 2));
            for (int k = 0; k < 8; k++) {
                float t = k / 8f, t2 = t * t, t3 = t2 * t;
                out.add(new float[] { 0.5f * (2 * b[0] + (-a[0] + c[0]) * t + (2 * a[0] - 5 * b[0] + 4 * c[0] - d[0]) * t2 + (-a[0] + 3 * b[0] - 3 * c[0] + d[0]) * t3),
                    0.5f * (2 * b[1] + (-a[1] + c[1]) * t + (2 * a[1] - 5 * b[1] + 4 * c[1] - d[1]) * t2 + (-a[1] + 3 * b[1] - 3 * c[1] + d[1]) * t3) });
            }
        }
        out.add(p.get(p.size() - 1));
        return out;
    }
    /** 带种子的随机数（同 rng：同一个种子每次画出来一样） */
    static final class Rng {
        int a;
        Rng(long seed) { a = (int) ((seed * 2654435761L) & 0xffffffffL); }
        double next() {
            a = a + 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }
    }
    /** 二维值噪声（迷彩用）：格点随机值，平滑插值 */
    static final class Noise {
        final int seed;
        Noise(int s) { seed = s; }
        private double hash(int i, int j) { int h = i * 374761393 + j * 668265263 + seed * (int) 2246822519L; h = (h ^ (h >>> 13)) * 1274126177; return ((h ^ (h >>> 16)) & 0xffffffffL) / 4294967296.0; }
        double at(double x, double y) {
            int i = (int) Math.floor(x), j = (int) Math.floor(y);
            double fx = x - i, fy = y - j, u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
            double a = hash(i, j), b = hash(i + 1, j), c = hash(i, j + 1), d = hash(i + 1, j + 1);
            return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
        }
    }
    /** 画布上有内容（透明度 > 8）的范围 { 左, 上, 右, 下 } */
    static int[] inkBox(Bitmap c) {
        int W = c.getWidth(), H = c.getHeight(), l = W, r = -1, t = H, b = -1;
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            c.getPixels(row, 0, W, 0, y, W, 1);
            for (int x = 0; x < W; x++) if ((row[x] >>> 24) > 8) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; b = y; }
        }
        return r < 0 ? null : new int[] { l, t, r + 1, b + 1 };
    }
    static Bitmap newCanvas(float w, float h) { return Bitmap.createBitmap(Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), Bitmap.Config.ARGB_8888); }

    // ================= 加工（拉花、图片、文字共用） =================
    /** 渐变上色：已有的形状换成从 c0 到 c1 的渐变（axis 'x' 横向、'y' 竖向，按内容的范围） */
    private static void gradFill(Bitmap c, char axis, int c0, int c1, int[] box) {
        if (box == null) box = inkBox(c);
        if (box == null) return;
        Canvas x = new Canvas(c);
        Paint p = new Paint();
        p.setShader(axis == 'x' ? new LinearGradient(box[0], 0, box[2], 0, c0, c1, Shader.TileMode.CLAMP) : new LinearGradient(0, box[1], 0, box[3], c0, c1, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        x.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
    }
    /** 虚线：沿 axis 方向每 len 像素留下、gap 像素切掉 */
    private static void dashCut(Bitmap c, char axis, float len, float gap, int[] box) {
        if (box == null) box = inkBox(c);
        if (box == null || len <= 0 || gap <= 0) return;
        Canvas x = new Canvas(c);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        if (axis == 'x') for (float a = box[0] + len; a < box[2]; a += len + gap) x.drawRect(a, 0, a + gap, c.getHeight(), p);
        else for (float b = box[1] + len; b < box[3]; b += len + gap) x.drawRect(0, b, c.getWidth(), b + gap, p);
    }
    /** 两端渐隐：沿 axis 方向从头 fa、到尾 fb（占内容长度的比例）慢慢淡出 */
    private static void endFade(Bitmap c, char axis, float fa, float fb, int[] box) {
        if (box == null) box = inkBox(c);
        if (box == null || (fa <= 0 && fb <= 0)) return;
        Canvas x = new Canvas(c);
        Paint p = new Paint();
        int[] cols = { 0x00000000, 0xff000000, 0xff000000, 0x00000000 };
        float[] st = { 0, Math.min(0.5f, Math.max(0.0001f, fa)), Math.max(0.5f, Math.min(0.9999f, 1 - fb)), 1 };
        p.setShader(axis == 'x' ? new LinearGradient(box[0], 0, box[2], 0, cols, st, Shader.TileMode.CLAMP) : new LinearGradient(0, box[1], 0, box[3], cols, st, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        x.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
    }
    /** 一维平方距离变换（Felzenszwalb） */
    private static void edt1(float[] f, int n, float[] d, int[] v, float[] z) {
        int k = 0;
        v[0] = 0; z[0] = -1e20f; z[1] = 1e20f;
        for (int q = 1; q < n; q++) {
            float s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
            while (s <= z[k]) { k--; s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]); }
            k++; v[k] = q; z[k] = s; z[k + 1] = 1e20f;
        }
        k = 0;
        for (int q = 0; q < n; q++) { while (z[k + 1] < q) k++; d[q] = (q - v[k]) * (q - v[k]) + f[v[k]]; }
    }
    /** 白边 / 描边：形状外面一圈 wpx 像素宽的颜色（欧氏距离，边是圆的）。pad：画布四周先留出描边的地方（图片、文字）；拉花只算形状附近那一块 */
    static Bitmap outline(Bitmap c, float wpx, int color, boolean pad, int[] box) {
        if (!(wpx > 0.5f)) return c;
        int P = pad ? (int) Math.ceil(wpx + 2) : 0, m = (int) Math.ceil(wpx + 2), rx = 0, ry = 0, rw = c.getWidth(), rh = c.getHeight();
        if (!pad && box != null) { rx = Math.max(0, box[0] - m); ry = Math.max(0, box[1] - m); rw = Math.min(c.getWidth(), box[2] + m) - rx; rh = Math.min(c.getHeight(), box[3] + m) - ry; }
        int W = rw + 2 * P, H = rh + 2 * P;
        int[] src = new int[rw * rh];
        c.getPixels(src, 0, rw, rx, ry, rw, rh);
        float INF = 1e20f;
        float[] g = new float[W * H];
        Arrays.fill(g, INF);
        for (int Y = 0; Y < rh; Y++) for (int X = 0; X < rw; X++) if ((src[Y * rw + X] >>> 24) > 127) g[(Y + P) * W + X + P] = 0;
        int n = Math.max(W, H);
        float[] f = new float[n], d = new float[n], z = new float[n + 1];
        int[] v = new int[n];
        for (int X = 0; X < W; X++) { for (int Y = 0; Y < H; Y++) f[Y] = g[Y * W + X]; edt1(f, H, d, v, z); for (int Y = 0; Y < H; Y++) g[Y * W + X] = d[Y]; }
        for (int Y = 0; Y < H; Y++) { for (int X = 0; X < W; X++) f[X] = g[Y * W + X]; edt1(f, W, d, v, z); for (int X = 0; X < W; X++) g[Y * W + X] = d[X]; }
        int[] od = new int[W * H];
        int rgb = color & 0xffffff;
        for (int i = 0; i < g.length; i++) {
            float a = Math.max(0, Math.min(1, wpx + 0.5f - (float) Math.sqrt(g[i])));
            if (a > 0) od[i] = (Math.round(a * 255) << 24) | rgb;
        }
        Bitmap out = pad ? newCanvas(W, H) : newCanvas(c.getWidth(), c.getHeight());
        out.setPixels(od, 0, W, pad ? 0 : rx, pad ? 0 : ry, W, H);
        new Canvas(out).drawBitmap(c, P, P, null);
        c.recycle();
        return out;
    }
    /** 拉花画好形状后的统一加工：渐变、虚线、两端渐隐、描边（axis：拉花走向，侧面是横的、车顶是竖的） */
    private static Bitmap finishStripe(Bitmap c, JSONObject w, char axis, float k) {
        int[] col = colors(w), box = inkBox(c);
        if (box == null) return c;
        char across = axis == 'x' ? 'y' : 'x';
        if (w.has("grad")) gradFill(c, "a".equals(w.optString("grad")) ? axis : across, col[0], col[4], box);
        JSONArray dash = w.optJSONArray("dash"), fd = w.optJSONArray("fd");
        if (dash != null) dashCut(c, axis, (float) dash.optDouble(0) / 100 * k, (float) dash.optDouble(1) / 100 * k, box);
        if (fd != null) endFade(c, axis, (float) fd.optDouble(0), (float) fd.optDouble(1), box);
        if (w.has("out")) c = outline(c, (float) w.optDouble("out") / 1000 * k, col[5], false, box);
        return c;
    }

    // ================= 拉花的画布（按米画，长宽和车一样） =================
    static final class Dims { final float hw, h, hl; Dims(float hw, float h, float hl) { this.hw = hw; this.h = h; this.hl = hl; } }
    private static float pxPerM(float wm, float hm) { return Math.min(700, MAXPX / Math.max(wm, hm)); }
    /** 拉花的画布：侧面的按车长 × 车高（左边是车头、上边是车顶），车顶的按车宽 × 车长（上边是车头、左边是车的左边） */
    static Bitmap stripeCanvas(JSONObject w, Dims dims) {
        final Map<String, Float> p = params(w);
        final int[] col = colors(w);
        boolean top = topOf(w);
        final float Lc = 2 * dims.hl, Hc = dims.h, Wc = 2 * dims.hw, wm = top ? Wc : Lc, hm = top ? Lc : Hc, k = pxPerM(wm, hm);
        Bitmap c = newCanvas(wm * k, hm * k);
        Canvas x = new Canvas(c);
        Paint pa = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean alt = w.optBoolean("alt");
        String tpl = w.optString("tpl");
        pa.setColor(col[0]);
        if ("belt".equals(tpl)) {
            float mm = k / 1000;
            float[] p0 = { p.get("s0") * Lc, (p.get("y") + p.get("rise")) * Hc }, p1 = { Math.max(p.get("s0") + 0.02f, p.get("s1")) * Lc, p.get("y") * Hc };
            List<float[]> line = list(p0, p1);
            float band = (p.get("n") * p.get("lw") + (p.get("n") - 1) * p.get("gap")) / 2000;
            if (p.get("drop") >= 3 && p.get("dlen") > 0.005f) {
                float dv = p.get("dlen") * Hc;
                float[] p2 = { p1[0] + dv / (float) Math.tan(p.get("drop") * DEG), p1[1] - dv };
                float la = (float) Math.hypot(p1[0] - p0[0], p1[1] - p0[1]), lb = (float) Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
                float r = Math.min(0.48f * Math.min(la, lb), Math.max(p.get("round") * 0.5f * Math.min(la, lb), p.get("round") > 0 ? band * 1.2f : 0));
                float[] a1 = { p1[0] - (p1[0] - p0[0]) / la * r, p1[1] - (p1[1] - p0[1]) / la * r }, b1 = { p1[0] + (p2[0] - p1[0]) / lb * r, p1[1] + (p2[1] - p1[1]) / lb * r };
                line = list(p0, a1);
                for (int qq = 1; qq <= 24; qq++) { float u = qq / 24f, v = 1 - u; line.add(new float[] { v * v * a1[0] + 2 * v * u * p1[0] + u * u * b1[0], v * v * a1[1] + 2 * v * u * p1[1] + u * u * b1[1] }); }
                line.add(p2);
            }
            List<float[]> px = new ArrayList<>();
            for (float[] m : line) px.add(new float[] { m[0] * k, (Hc - m[1]) * k });
            lines(x, pa, resample(px, 3), p, mm, col, alt, p.get("tip"), 0);
        } else if ("path".equals(tpl)) {
            JSONArray path = w.optJSONArray("path");
            List<float[]> pts = new ArrayList<>();
            for (int i = 0; path != null && i < path.length(); i++) { JSONArray q2 = path.optJSONArray(i); pts.add(new float[] { (float) q2.optDouble(0) * wm * k, (float) q2.optDouble(1) * hm * k }); }
            if (pts.size() >= 2) lines(x, pa, resample(smoothLine(pts), 3), p, k / 1000, col, alt, p.get("tip"), p.get("tip2"));
        } else if ("slash".equals(tpl)) {
            float cm = k / 100, dx = (float) Math.sin(p.get("ang") * DEG), dy = (float) Math.cos(p.get("ang") * DEG), half = p.get("len") * Hc / 2;
            int n = Math.round(p.get("n"));
            for (int j = 0; j < n; j++) {
                float cs = p.get("s") * Lc + (j - (n - 1) / 2f) * (p.get("wd") + p.get("gap")) / 100, ch = p.get("y") * Hc;
                Rs sl = resample(list(new float[] { (cs + dx * half) * k, (Hc - (ch + dy * half)) * k }, new float[] { (cs - dx * half) * k, (Hc - (ch - dy * half)) * k }), 3);
                pa.setColor(alt && j % 2 == 1 ? col[3] : col[0]);
                float[] hw = new float[sl.f.length];
                float tip = p.get("tip");
                for (int i = 0; i < hw.length; i++) hw[i] = p.get("wd") * cm / 2 * (tip > 0 && sl.f[i] > 1 - tip ? Math.max(0.04f, (1 - sl.f[i]) / tip) : 1);
                ribbon(x, pa, sl.pts, hw);
            }
        } else if ("rocker".equals(tpl)) {
            float x0 = p.get("s0") * Lc * k, x1 = p.get("s1") * Lc * k, yc = (Hc - p.get("y") * Hc) * k, th = p.get("wd") / 100 * k, pt = p.get("pt");
            Rs rk = resample(list(new float[] { x0, yc }, new float[] { x1, yc }), 3);
            float[] hw = new float[rk.f.length];
            for (int i = 0; i < hw.length; i++) { float f = rk.f[i]; hw[i] = th / 2 * (pt > 0 ? Math.min(1, Math.min(f / pt, (1 - f) / pt)) * 0.96f + 0.04f : 1); }
            ribbon(x, pa, rk.pts, hw);
            if (p.get("fine") > 0.5f) { pa.setColor(col[1]); x.drawRect(x0, yc + th / 2 + p.get("fg") / 100 * k, x1, yc + th / 2 + p.get("fg") / 100 * k + p.get("fw") / 1000 * k, pa); }
        } else if ("race2".equals(tpl) || "band".equals(tpl)) {
            float y0 = p.get("s0") * Lc * k, y1 = p.get("s1") * Lc * k, mid = Wc / 2 * k, wd = p.get("wd") / 100 * k, ed = p.get("edge") / 1000 * k;
            float[] cents = "race2".equals(tpl) ? new float[] { -(p.get("gap") / 2 + p.get("wd") / 2), p.get("gap") / 2 + p.get("wd") / 2 } : new float[] { 0 };
            for (int i2 = 0; i2 < cents.length; i2++) {
                float cx = mid + (cents[i2] + p.get("off")) / 100 * k;
                if (ed > 0) { pa.setColor(col[1]); x.drawRect(cx - wd / 2 - ed, y0, cx + wd / 2 + ed, y1, pa); }
                pa.setColor(alt && i2 % 2 == 1 ? col[3] : col[0]); x.drawRect(cx - wd / 2, y0, cx + wd / 2, y1, pa);
            }
            if ("race2".equals(tpl) && p.get("mid") > 0.5f) { float mw = p.get("mw") / 1000 * k, cx = mid + p.get("off") / 100 * k; pa.setColor(col[1]); x.drawRect(cx - mw / 2, y0, cx + mw / 2, y1, pa); }
        } else pattern(x, pa, w, p, col, k, Lc * k, (top ? Wc : Hc) * k, top);
        return finishStripe(c, w, top ? 'y' : 'x', k);
    }
    /** 几道平行线沿一条中线走（腰线、手绘线）：外粗里细、起头 / 收尾渐细、每道线的颜色 */
    private static void lines(Canvas x, Paint pa, Rs rs, Map<String, Float> p, float mm, int[] col, boolean alt, float tip, float tip2) {
        int n = Math.round(p.get("n"));
        for (int i = 0; i < n; i++) {
            float o = (i - (n - 1) / 2f) * (p.get("lw") + p.get("gap")) * mm, scale = n > 1 ? 1 - (1 - p.get("lwr")) * i / (n - 1) : 1, hw = p.get("lw") * mm / 2 * scale;
            pa.setColor(alt && i % 2 == 1 ? col[3] : col[0]);
            float[] h = new float[rs.f.length];
            for (int j = 0; j < h.length; j++) {
                float f = rs.f[j], a = tip > 0 ? Math.min(1, 0.1f + 0.9f * f / tip) : 1, b = tip2 > 0 ? Math.min(1, 0.1f + 0.9f * (1 - f) / tip2) : 1;
                h[j] = hw * Math.min(a, b);
            }
            ribbon(x, pa, offset(rs.pts, o), h);
        }
    }
    /** 图案：在"沿着 a、横着 b"的坐标里画（侧面 a 是车长方向、b 往下；车顶 a 是车长方向、b 从左往右——换坐标轴画） */
    private static void pattern(Canvas x, Paint pa, JSONObject w, Map<String, Float> p, int[] col, float k, float A, float B, boolean top) {
        if (top) { Matrix m = new Matrix(); m.setValues(new float[] { 0, 1, 0, 1, 0, 0, 0, 0, 1 }); x.setMatrix(m); }
        String tpl = w.optString("tpl");
        boolean alt = w.optBoolean("alt");
        Rng R = new Rng(Math.round(p.get("seed")) * 7919L + TPL_ORDER.indexOf(tpl));
        Float s1o = p.get("s1");
        float s0 = p.get("s0"), a0 = Math.min(s0, s1o != null ? s1o : 1) * A, a1 = (s1o != null ? Math.max(s0, s1o) : Math.min(1, s0 + p.get("len"))) * A;
        float bc = (top ? p.get("y") : 1 - p.get("y")) * B, hb = p.get("hgt") * B / 2, b0 = bc - hb, b1 = bc + hb;
        if ("pixel".equals(tpl) || "checker".equals(tpl)) {
            float cell = p.get("cell") / 100 * k;
            int na = Math.max(1, Math.round((a1 - a0) / cell)), nb = Math.max(1, Math.round((b1 - b0) / cell));
            float ca = (a1 - a0) / na, cb = (b1 - b0) / nb;
            for (int ia = 0; ia < na; ia++) for (int ib = 0; ib < nb; ib++) {
                float f = (ia + 0.5f) / na;
                if ("pixel".equals(tpl)) {
                    if (p.get("dir") > 0.5f) f = 1 - f;
                    if (R.next() > p.get("dens") * Math.pow(1 - f, 1.3)) continue; // 前面密、往后越来越疏
                    pa.setColor(R.next() < p.get("mix") ? col[1] : col[0]);
                    float g = 0.12f * Math.min(ca, cb);
                    x.drawRect(a0 + ia * ca + g / 2, b0 + ib * cb + g / 2, a0 + ia * ca + ca - g / 2, b0 + ib * cb + cb - g / 2, pa);
                } else {
                    if (p.get("frag") > 0.5f && f < 0.6f && R.next() > f / 0.6f) continue; // 前面碎开
                    pa.setColor((ia + ib) % 2 == 1 ? col[1] : col[0]);
                    x.drawRect(a0 + ia * ca, b0 + ib * cb, a0 + ia * ca + ca + 0.5f, b0 + ib * cb + cb + 0.5f, pa);
                }
            }
        } else if ("speed".equals(tpl)) {
            int n = Math.round(p.get("n"));
            for (int i = 0; i < n; i++) {
                float len = (a1 - a0) * (1 - p.get("var") * (float) R.next() * 0.85f), st = a0 + (float) R.next() * (a1 - a0 - len), bb = b0 + (float) R.next() * (b1 - b0), lw = p.get("lw") / 1000 * k * (0.5f + 0.5f * (float) R.next());
                Rs rs = resample(list(new float[] { st, bb }, new float[] { st + len, bb }), 3);
                pa.setColor(alt && i % 2 == 1 ? col[3] : col[0]);
                float[] h = new float[rs.f.length];
                float tip = p.get("tip");
                for (int j = 0; j < h.length; j++) { float f2 = rs.f[j]; h[j] = lw / 2 * (tip > 0 && f2 > 1 - tip ? Math.max(0.03f, (1 - f2) / tip) : 1) * Math.min(1, 0.3f + f2 * 8); }
                ribbon(x, pa, rs.pts, h);
            }
        } else if ("flame".equals(tpl)) { // 车头一整团，往后分成几条胖火舌，S 形摆动、尖上勾起来
            int n = Math.round(p.get("n"));
            float span = a1 - a0, pitch = (b1 - b0) / n, fat = Math.min(b1 - b0, span * 0.5f);
            float[][] tg = new float[n][];
            for (int t = 0; t < n; t++) {
                float side = Math.abs((t + 0.5f) / n - 0.5f) * 2;
                float L = span * (1 - 0.45f * side) * (0.6f + 0.4f * (float) R.next()), ph = (float) R.next() * 6.28f, amp = fat * (0.12f + 0.18f * (float) R.next()) * p.get("curl"), up = R.next() < 0.5 ? -1 : 1;
                tg[t] = new float[] { b0 + (t + 0.5f) * pitch, L, ph, amp, up };
            }
            float[][] layers = { { 0, 1, 1, 0 }, { 1, 0.62f, 0.8f, 0.04f }, { 2, 0.3f, 0.56f, 0.08f } };
            for (float[] layer : layers) {
                pa.setColor(col[(int) layer[0]]);
                for (float[] t : tg) {
                    List<float[]> pts = new ArrayList<>();
                    float st = a0 + span * layer[3], L = Math.max(span * 0.05f, t[1] * layer[2] - span * layer[3]), W0 = Math.max(pitch * 1.6f, fat * 0.42f) * layer[1];
                    for (int s = 0; s <= 60; s++) { float u = (st - a0 + s / 60f * L) / t[1]; pts.add(new float[] { a0 + u * t[1], t[0] + (float) Math.sin(u * 7 + t[2]) * t[3] * (float) Math.pow(u, 0.7) + t[4] * t[3] * 2.2f * (float) Math.pow(u, 4) }); }
                    Rs rs2 = resample(pts, 3);
                    float[] hw2 = new float[rs2.f.length];
                    for (int j = 0; j < hw2.length; j++) { float u = rs2.f[j]; hw2[j] = W0 / 2 * (u < 0.2f ? 1 : (float) Math.pow((1 - u) / 0.8, 1.25)); }
                    ribbon(x, pa, rs2.pts, hw2);
                    x.drawCircle(rs2.pts[0][0], rs2.pts[0][1], hw2[0], pa); // 车头那端是圆的
                }
            }
        } else if ("camo".equals(tpl)) {
            float sc = p.get("scale") / 100 * k;
            Noise nz = new Noise(Math.round(p.get("seed")) * 101 + 7);
            if (p.get("style") > 0.5f) { // 数码迷彩：方块
                float qq = sc / 5;
                int ncA = (int) Math.ceil((a1 - a0) / qq), ncB = (int) Math.ceil((b1 - b0) / qq);
                for (int ia = 0; ia < ncA; ia++) for (int ib = 0; ib < ncB; ib++) {
                    double vv = nz.at(ia * qq / sc, ib * qq / sc) * 0.7 + nz.at(ia * qq / sc * 3.1, ib * qq / sc * 3.1) * 0.3;
                    pa.setColor(vv < 0.4 ? col[0] : vv < 0.62 ? col[1] : col[2]);
                    x.drawRect(a0 + ia * qq, b0 + ib * qq, a0 + ia * qq + qq + 0.5f, b0 + ib * qq + qq + 0.5f, pa);
                }
            } else { // 色块迷彩：噪声分三档；按三分之一的分辨率算再放大
                int DS = 3, W2 = Math.max(1, (int) Math.ceil((a1 - a0) / DS)), H2 = Math.max(1, (int) Math.ceil((b1 - b0) / DS));
                float s2 = sc / DS;
                int[] d = new int[W2 * H2];
                for (int yy = 0; yy < H2; yy++) for (int xx = 0; xx < W2; xx++) {
                    double n2 = nz.at(xx / s2, yy / s2) * 0.65 + nz.at(xx / s2 * 2.3 + 17, yy / s2 * 2.3 + 5) * 0.35;
                    d[yy * W2 + xx] = n2 < 0.42 ? col[0] : n2 < 0.6 ? col[1] : col[2];
                }
                Bitmap tmp = Bitmap.createBitmap(d, W2, H2, Bitmap.Config.ARGB_8888);
                Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG);
                x.drawBitmap(tmp, null, new RectF(a0, b0, a0 + W2 * DS, b0 + H2 * DS), fp);
                tmp.recycle();
            }
        }
        x.setMatrix(new Matrix());
    }

    // ================= 文字 =================
    private static final Map<String, Typeface> FONTS = new HashMap<>();
    /** 字体引用 → Typeface：卡片带的（b:）从 APK 里读；导入的（u:）没有（用车模包里画好的图）；系统字体返回 null */
    static synchronized Typeface font(Context c, String ref) {
        if (ref == null || !ref.startsWith("b:")) return null;
        if (FONTS.containsKey(ref)) return FONTS.get(ref);
        Typeface t = null;
        try { t = Typeface.createFromAsset(c.getAssets(), "wrapfont-" + ref.substring(2) + ".ttf"); } catch (Exception ignored) { }
        FONTS.put(ref, t);
        return t;
    }
    /** 画字的笔（同 fontCss：系统粗体 800、系统 600、别的字体 400；字号 200） */
    private static Paint textPaint(JSONObject w, Typeface fam) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        String f = w.optString("font", "");
        p.setTypeface(fam != null ? fam : Typeface.create(Typeface.DEFAULT, "sysb".equals(f) ? 800 : 600, false));
        p.setTextSize(200);
        return p;
    }
    /** 文字的画布（同 textCanvas）：多行（横排一行一行往下，竖排一列一列从右往左）、字距、斜体、阴影、描边、渐变色，按墨迹裁好；最后两端渐隐、白边 */
    static Bitmap textCanvas(JSONObject w, Typeface fam) {
        String[] all = w.optString("text", "").split("\n", -1);
        List<String> rows = new ArrayList<>();
        boolean any = false;
        for (String s : all) if (!s.trim().isEmpty()) any = true;
        if (!any) return null;
        for (String s : all) rows.add(s.trim());
        int[] col = colors(w);
        boolean vert = w.optBoolean("vert");
        final float fs = 200, sw = new float[] { 0, 0.06f, 0.12f }[Math.max(0, Math.min(2, w.optInt("stroke", 0)))] * fs, ls = (float) w.optDouble("ls", 0) * fs, sk = w.optBoolean("it") ? -0.21f : 0;
        JSONObject sh = w.optJSONObject("sh");
        final Paint probe = textPaint(w, fam);
        float[] lineW = new float[rows.size()], colH = new float[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            String s = rows.get(r);
            float t = 0;
            for (int i = 0; i < s.length(); i++) t += probe.measureText(s, i, i + 1) + (i > 0 ? ls : 0);
            lineW[r] = t;
            colH[r] = s.length() * fs * 1.08f + Math.max(0, s.length() - 1) * ls;
        }
        float LH = fs * 1.3f, CW = fs * 1.25f;
        float shx = sh != null ? Math.abs((float) sh.optDouble("x", 0)) * fs + (float) sh.optDouble("b", 0) * fs : 0, shy = sh != null ? Math.abs((float) sh.optDouble("y", 0)) * fs + (float) sh.optDouble("b", 0) * fs : 0;
        float pad = (float) Math.ceil(fs * 0.8f + sw + Math.max(shx, shy) + Math.abs(sk) * fs);
        float bw = 0, bh = 0;
        if (vert) { bw = rows.size() * CW; for (float h : colH) bh = Math.max(bh, h); }
        else { for (float v : lineW) bw = Math.max(bw, v); bh = rows.size() * LH; }
        final int W = (int) Math.ceil(bw + 2 * pad), H = (int) Math.ceil(bh + 2 * pad);
        final float sc = Math.min(1, 4096f / Math.max(W, H));
        Bitmap tmp = newCanvas(W * sc, H * sc);
        Canvas x = new Canvas(tmp);
        Matrix tf = new Matrix();
        tf.setValues(new float[] { sc, sk * sc, -sk * sc * H * 0.5f, 0, sc, 0, 0, 0, 1 }); // 斜体：按中线错切
        if (sh != null) { // 阴影：字和描边都投影，模糊
            Paint sp = textPaint(w, fam);
            int scol = Color.parseColor(sh.optString("c", "#000000"));
            sp.setColor(scol);
            float blur = (float) sh.optDouble("b", 0) * fs * sc;
            if (blur > 0) sp.setMaskFilter(new BlurMaskFilter(Math.max(0.1f, blur), BlurMaskFilter.Blur.NORMAL));
            x.save();
            x.translate((float) sh.optDouble("x", 0) * fs * sc, (float) sh.optDouble("y", 0) * fs * sc);
            if (sw > 0) { Paint ss = new Paint(sp); ss.setStyle(Paint.Style.STROKE); ss.setStrokeWidth(2 * sw); ss.setStrokeJoin(Paint.Join.ROUND); ss.setStrokeMiter(2); drawText(x, tf, ss, rows, vert, W, pad, fs, ls, LH, CW); }
            drawText(x, tf, sp, rows, vert, W, pad, fs, ls, LH, CW);
            x.restore();
        }
        if (sw > 0) { Paint ss = textPaint(w, fam); ss.setColor(col[1]); ss.setStyle(Paint.Style.STROKE); ss.setStrokeWidth(2 * sw); ss.setStrokeJoin(Paint.Join.ROUND); ss.setStrokeMiter(2); drawText(x, tf, ss, rows, vert, W, pad, fs, ls, LH, CW); }
        if (w.has("grad")) { // 渐变色只上在字上（描边照旧）
            Bitmap fc = newCanvas(tmp.getWidth(), tmp.getHeight());
            Paint fp = textPaint(w, fam);
            fp.setColor(0xffffffff);
            drawText(new Canvas(fc), tf, fp, rows, vert, W, pad, fs, ls, LH, CW);
            gradFill(fc, "a".equals(w.optString("grad")) ? (vert ? 'y' : 'x') : (vert ? 'x' : 'y'), col[0], col[4], null);
            x.drawBitmap(fc, 0, 0, null);
            fc.recycle();
        } else { Paint fp = textPaint(w, fam); fp.setColor(col[0]); drawText(x, tf, fp, rows, vert, W, pad, fs, ls, LH, CW); }
        int[] box = inkBox(tmp);
        if (box == null) { tmp.recycle(); return null; }
        int m = 6, iw = box[2] - box[0] + 2 * m, ih = box[3] - box[1] + 2 * m;
        float k = Math.min(1, (float) MAXPX / Math.max(iw, ih));
        Bitmap c = newCanvas(iw * k, ih * k);
        new Canvas(c).drawBitmap(tmp, new Rect(box[0] - m, box[1] - m, box[0] - m + iw, box[1] - m + ih), new RectF(0, 0, c.getWidth(), c.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
        tmp.recycle();
        JSONArray fd = w.optJSONArray("fd");
        if (fd != null) endFade(c, vert ? 'y' : 'x', (float) fd.optDouble(0), (float) fd.optDouble(1), null);
        if (w.has("out")) c = outline(c, (float) w.optDouble("out") / 100 * Math.max(c.getWidth(), c.getHeight()), col[5], true, null);
        return c;
    }
    /** 一个一个字放（字距、竖排都要按字放） */
    private static void drawText(Canvas x, Matrix tf, Paint p, List<String> rows, boolean vert, float W, float pad, float fs, float ls, float LH, float CW) {
        x.save();
        x.concat(tf);
        for (int r = 0; r < rows.size(); r++) {
            String s = rows.get(r);
            float cx = vert ? W - pad - (r + 0.5f) * CW : pad, cy = vert ? pad : pad + (r + 0.86f) * LH;
            for (int i = 0; i < s.length(); i++) {
                if (vert) { p.setTextAlign(Paint.Align.CENTER); x.drawText(s, i, i + 1, cx, cy + (i + 0.86f) * fs * 1.08f + i * ls, p); }
                else { p.setTextAlign(Paint.Align.LEFT); x.drawText(s, i, i + 1, cx, cy, p); cx += p.measureText(s, i, i + 1) + ls; }
            }
        }
        x.restore();
    }

    // ================= 图片 =================
    /** 车模包里的 glTF 图片 → 位图（可改） */
    static Bitmap decode(ByteBuffer b) {
        if (b == null) return null;
        byte[] a = new byte[b.remaining()];
        b.duplicate().get(a);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inMutable = true;
        return BitmapFactory.decodeByteArray(a, 0, a.length, o);
    }
    /** 去背景（同 keyOut）：颜色离背景色越近越透明；edge 时只去从边缘漫延进来的 */
    private static void keyOut(Bitmap c, JSONObject key) {
        int W = c.getWidth(), H = c.getHeight(), n = W * H;
        int[] d = new int[n];
        c.getPixels(d, 0, W, 0, 0, W, H);
        float[] kc = new float[3];
        String kcs = key.optString("c", "auto");
        if ("auto".equals(kcs)) {
            for (int idx : new int[] { 0, W - 1, (H - 1) * W, (H - 1) * W + W - 1 }) { kc[0] += ((d[idx] >> 16) & 255) / 4f; kc[1] += ((d[idx] >> 8) & 255) / 4f; kc[2] += (d[idx] & 255) / 4f; }
        } else { int cc = Color.parseColor(kcs); kc[0] = (cc >> 16) & 255; kc[1] = (cc >> 8) & 255; kc[2] = cc & 255; }
        float tol = (float) key.optDouble("tol", 0.15) * 441.7f, soft = Math.max(1, (float) key.optDouble("soft", 0.08) * 441.7f);
        float[] fac = new float[n];
        for (int i = 0; i < n; i++) {
            float dr = ((d[i] >> 16) & 255) - kc[0], dg = ((d[i] >> 8) & 255) - kc[1], db = (d[i] & 255) - kc[2], dist = (float) Math.sqrt(dr * dr + dg * dg + db * db);
            fac[i] = dist <= tol ? 0 : dist >= tol + soft ? 1 : (dist - tol) / soft;
        }
        if (key.optBoolean("edge", true)) { // 只去从边缘漫延进来的：中间同色的地方不挖
            byte[] seen = new byte[n];
            int[] q = new int[n];
            int qh = 0, qt = 0;
            for (int i = 0; i < W; i++) { qt = push(i, seen, fac, q, qt); qt = push((H - 1) * W + i, seen, fac, q, qt); }
            for (int i = 0; i < H; i++) { qt = push(i * W, seen, fac, q, qt); qt = push(i * W + W - 1, seen, fac, q, qt); }
            while (qh < qt) {
                int p = q[qh++], px = p % W, py = p / W;
                if (px > 0) qt = push(p - 1, seen, fac, q, qt);
                if (px < W - 1) qt = push(p + 1, seen, fac, q, qt);
                if (py > 0) qt = push(p - W, seen, fac, q, qt);
                if (py < H - 1) qt = push(p + W, seen, fac, q, qt);
            }
            for (int i = 0; i < n; i++) if (seen[i] == 0) fac[i] = 1;
        }
        for (int i = 0; i < n; i++) d[i] = (Math.round((d[i] >>> 24) * fac[i]) << 24) | (d[i] & 0xffffff);
        c.setPixels(d, 0, W, 0, 0, W, H);
    }
    private static int push(int p, byte[] seen, float[] fac, int[] q, int qt) { if (seen[p] == 0 && fac[p] < 1) { seen[p] = 1; q[qt++] = p; } return qt; }
    /** 亮度、对比度、饱和度、色相（同画布的 brightness / contrast / saturate / hue-rotate 滤镜） */
    private static ColorMatrix adjust(float br, float ct, float sa, float hue) {
        ColorMatrix m = new ColorMatrix(new float[] { br, 0, 0, 0, 0, 0, br, 0, 0, 0, 0, 0, br, 0, 0, 0, 0, 0, 1, 0 });
        float o = 255 * (0.5f - 0.5f * ct);
        m.postConcat(new ColorMatrix(new float[] { ct, 0, 0, 0, o, 0, ct, 0, 0, o, 0, 0, ct, 0, o, 0, 0, 0, 1, 0 }));
        m.postConcat(new ColorMatrix(new float[] { 0.213f + 0.787f * sa, 0.715f - 0.715f * sa, 0.072f - 0.072f * sa, 0, 0, 0.213f - 0.213f * sa, 0.715f + 0.285f * sa, 0.072f - 0.072f * sa, 0, 0,
            0.213f - 0.213f * sa, 0.715f - 0.715f * sa, 0.072f + 0.928f * sa, 0, 0, 0, 0, 0, 1, 0 }));
        double a = Math.toRadians(hue);
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        m.postConcat(new ColorMatrix(new float[] {
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0, 0,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0, 0,
            0, 0, 0, 1, 0 }));
        return m;
    }
    /** 图片加工：裁剪 → 去背景 → 调色 → 边缘渐隐 → 白边 */
    static Bitmap processImage(Bitmap raw, JSONObject w) {
        JSONArray cr = w.optJSONArray("crop");
        double c0 = cr != null ? cr.optDouble(0) : 0, c1 = cr != null ? cr.optDouble(1) : 0, c2 = cr != null ? cr.optDouble(2) : 0, c3 = cr != null ? cr.optDouble(3) : 0;
        int sx = (int) Math.round(raw.getWidth() * c0), sy = (int) Math.round(raw.getHeight() * c2);
        int cw = Math.max(1, (int) Math.round(raw.getWidth() * (1 - c0 - c1))), ch = Math.max(1, (int) Math.round(raw.getHeight() * (1 - c2 - c3)));
        Bitmap c = newCanvas(cw, ch);
        new Canvas(c).drawBitmap(raw, new Rect(sx, sy, sx + cw, sy + ch), new Rect(0, 0, cw, ch), null);
        if (w.optJSONObject("key") != null) keyOut(c, w.optJSONObject("key"));
        JSONArray a = w.optJSONArray("adj");
        if (a != null && (a.optDouble(0) != 1 || a.optDouble(1) != 1 || a.optDouble(2) != 1 || a.optDouble(3) != 0)) {
            Bitmap c2b = newCanvas(cw, ch);
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(adjust((float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2), Math.round(a.optDouble(3)))));
            new Canvas(c2b).drawBitmap(c, 0, 0, p);
            c.recycle();
            c = c2b;
        }
        if (w.has("ef")) { float ef = (float) w.optDouble("ef"); int[] box = { 0, 0, c.getWidth(), c.getHeight() }; endFade(c, 'x', ef, ef, box); endFade(c, 'y', ef, ef, box); }
        if (w.has("out")) c = outline(c, (float) w.optDouble("out") / 100 * Math.max(c.getWidth(), c.getHeight()), colors(w)[5], true, null);
        return c;
    }

    // ================= 哪些格子里有内容 =================
    static final class Mask { byte[] bits; int gx, gy; }
    /** 透明度 > 0 的格子（外扩 3 像素：取样时边上的像素也会被带到）；行从下往上数（贴图上传时上下翻转） */
    static Mask maskOf(Bitmap c) {
        int W = c.getWidth(), H = c.getHeight(), gx = Math.max(1, Math.min(64, (int) Math.ceil(W / 24.0))), gy = Math.max(1, Math.min(64, (int) Math.ceil(H / 24.0)));
        byte[] bits = new byte[gx * gy];
        int[] row = new int[W];
        for (int y = 0; y < H; y++) {
            c.getPixels(row, 0, W, 0, y, W, 1);
            int r0 = Math.max(0, Math.min(gy - 1, (int) Math.floor((1 - (y + 4) / (double) H) * gy))), r1 = Math.min(gy - 1, (int) Math.floor((1 - (y - 3) / (double) H) * gy));
            for (int x = 0; x < W; x++) {
                if ((row[x] >>> 24) < 3) continue;
                int a0 = Math.max(0, (int) Math.floor((x - 3) / (double) W * gx)), a1 = Math.min(gx - 1, (int) Math.floor((x + 3) / (double) W * gx));
                for (int ry = r0; ry <= Math.max(r0, r1); ry++) for (int cx = a0; cx <= a1; cx++) bits[ry * gx + cx] = 1;
            }
        }
        Mask m = new Mask();
        m.bits = bits; m.gx = gx; m.gy = gy;
        return m;
    }
}
