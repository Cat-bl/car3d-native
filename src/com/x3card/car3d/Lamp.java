package com.x3card.car3d;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车灯的点亮层和路面光束（照网页版 demo3d/carlamp.js 逐行搬）：点亮层的贴图按灯罩的形状烘，四个通道：
 * R 原灯罩贴图的明暗（按这一块的 10% / 90% 分位拉开，没贴图或是平的按最亮）、G 离边缘多深、B 一个像素折成多深、A 盖住了（往外扩 3 像素）；
 * 亮度、热白中心、保留灯罩纹理、边缘柔化、颜色在着色器里按这几个数算。路面光束：车头 / 车尾地上一块四边形，透明度贴图按形状、光斑、衰减、扩散、雾感画
 */
final class Lamp {
    private Lamp() {}
    static final int PAD = 4, DILATE = 3, CELL_MAX = 256, ATLAS_MAX = 1024, DENS = 640, BEAM_PX = 256;

    // ================= 参数：范围、默认值 =================
    static final Map<String, float[]> GLOW = new LinkedHashMap<>();
    static final Map<String, Float> CORE = new HashMap<>(), INT = new HashMap<>();
    static final Map<String, float[]> BEAM_LIM = new LinkedHashMap<>();
    /** 路面光束：role、存在哪个键、方向、名字、默认开不开、长、宽、强度、扩散 */
    static final class BeamDef {
        final String role, key, name; final int dir; final boolean enabled; final float length, width, intensity, spread;
        BeamDef(String role, String key, int dir, String name, boolean en, float l, float w, float i, float s) { this.role = role; this.key = key; this.dir = dir; this.name = name; enabled = en; length = l; width = w; intensity = i; spread = s; }
    }
    static final Map<String, BeamDef> BEAMS = new LinkedHashMap<>();
    static final List<String> SHAPES = Arrays.asList("cone", "pool", "bar"), LOBES = Arrays.asList("auto", "single", "double");
    static {
        GLOW.put("intensity", new float[] { 0.3f, 1.5f, 1 }); GLOW.put("core", new float[] { 0, 1, 0.45f });
        GLOW.put("detail", new float[] { 0, 1, 0.6f }); GLOW.put("softness", new float[] { 0, 1, 0.3f });
        CORE.put("tail", 0.12f); CORE.put("brake", 0f); CORE.put("fogR", 0.12f); CORE.put("turnL", 0.2f); CORE.put("turnR", 0.2f); CORE.put("ambient", 0.15f);
        for (int i = 1; i <= 8; i++) CORE.put("cus" + i, 0.15f);
        INT.put("brake", 1.5f);
        BEAM_LIM.put("length", new float[] { 0.3f, 5 }); BEAM_LIM.put("width", new float[] { 0.4f, 3.5f }); BEAM_LIM.put("intensity", new float[] { 0.05f, 1 });
        BEAM_LIM.put("spread", new float[] { 0, 1 }); BEAM_LIM.put("offset", new float[] { -0.3f, 0.8f }); BEAM_LIM.put("side", new float[] { -0.5f, 0.5f });
        BEAM_LIM.put("height", new float[] { 0, 0.1f }); BEAM_LIM.put("lobeSpacing", new float[] { 0.1f, 1.2f }); BEAM_LIM.put("lobeWidth", new float[] { 0, 1 });
        BEAM_LIM.put("falloff", new float[] { 0, 1 }); BEAM_LIM.put("haze", new float[] { 0, 1 });
        BEAMS.put("low", new BeamDef("head", "beam", 1, "近光", true, 2.2f, 1.75f, 0.6f, 0.45f));
        BEAMS.put("high", new BeamDef("head", "beamHigh", 1, "远光", true, 2.8f, 1.9f, 0.75f, 0.6f));
        BEAMS.put("fogF", new BeamDef("fogF", "beam", 1, "前雾灯", true, 1.2f, 1.9f, 0.4f, 0.85f));
        BEAMS.put("tail", new BeamDef("tail", "beam", -1, "尾灯", false, 0.7f, 1.4f, 0.45f, 0.7f));
        BEAMS.put("reverse", new BeamDef("reverse", "beam", -1, "倒车灯", false, 1, 1.3f, 0.5f, 0.6f));
    }
    static boolean hex(Object c) { return c instanceof String && ((String) c).matches("(?i)^#[0-9a-f]{6}$"); }
    static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static boolean inLim(Object v, float[] lim) {
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d) && d >= lim[0] && d <= lim[1];
    }
    static float smooth(float a, float b, float x) { float t = clamp01((x - a) / Math.max(1e-9f, b - a)); return t * t * (3 - 2 * t); }

    /** 发光质感（补上默认值）：intensity、core、detail、softness */
    static float[] glow(JSONObject g, String r) {
        float[] o = new float[4];
        int i = 0;
        for (Map.Entry<String, float[]> e : GLOW.entrySet()) {
            String k = e.getKey();
            Object v = g == null ? null : g.opt(k);
            o[i++] = inLim(v, e.getValue()) ? ((Number) v).floatValue() : "core".equals(k) && CORE.containsKey(r) ? CORE.get(r) : "intensity".equals(k) && INT.containsKey(r) ? INT.get(r) : e.getValue()[2];
        }
        return o;
    }
    /** 路面光束的参数（补上默认值） */
    static final class Beam {
        int dir; boolean enabled; String shape, lobeMode, color;
        float lobeSpacing, lobeWidth, length, width, offset, side, height, intensity, spread, falloff, haze;
    }
    static Beam beam(String slot, JSONObject b) {
        BeamDef d = BEAMS.get(slot);
        Beam o = new Beam();
        o.dir = d.dir;
        if (b == null) b = new JSONObject();
        Object en = b.opt("enabled");
        o.enabled = en instanceof Boolean ? (Boolean) en : d.enabled;
        o.shape = SHAPES.contains(b.opt("shape")) ? b.optString("shape") : "cone";
        o.lobeMode = LOBES.contains(b.opt("lobeMode")) ? b.optString("lobeMode") : "auto";
        o.lobeSpacing = pick(b, "lobeSpacing", 0.6f); o.lobeWidth = pick(b, "lobeWidth", 0.4f); o.length = pick(b, "length", d.length); o.width = pick(b, "width", d.width);
        o.offset = pick(b, "offset", 0.06f); o.side = pick(b, "side", 0); o.height = pick(b, "height", 0.02f); o.intensity = pick(b, "intensity", d.intensity);
        o.spread = pick(b, "spread", d.spread); o.falloff = pick(b, "falloff", 0.45f); o.haze = pick(b, "haze", 0.35f);
        o.color = hex(b.opt("color")) ? b.optString("color").toLowerCase() : null;
        return o;
    }
    private static float pick(JSONObject b, String k, float def) { Object v = b.opt(k); return inLim(v, BEAM_LIM.get(k)) ? ((Number) v).floatValue() : def; }
    /** 存配置用：只留认识、合法的字段 */
    static JSONObject cleanGlow(Object x) {
        if (!(x instanceof JSONObject)) return null;
        JSONObject g = (JSONObject) x, o = new JSONObject();
        try { for (Map.Entry<String, float[]> e : GLOW.entrySet()) if (inLim(g.opt(e.getKey()), e.getValue())) o.put(e.getKey(), g.opt(e.getKey())); } catch (Exception ignored) {}
        return o.length() > 0 ? o : null;
    }
    static JSONObject cleanBeam(Object x) {
        if (!(x instanceof JSONObject)) return null;
        JSONObject b = (JSONObject) x, o = new JSONObject();
        try {
            if (b.opt("enabled") instanceof Boolean) o.put("enabled", b.opt("enabled"));
            if (SHAPES.contains(b.opt("shape"))) o.put("shape", b.opt("shape"));
            if (LOBES.contains(b.opt("lobeMode"))) o.put("lobeMode", b.opt("lobeMode"));
            for (Map.Entry<String, float[]> e : BEAM_LIM.entrySet()) if (inLim(b.opt(e.getKey()), e.getValue())) o.put(e.getKey(), b.opt(e.getKey()));
            if (hex(b.opt("color"))) o.put("color", b.optString("color").toLowerCase());
        } catch (Exception ignored) {}
        return o.length() > 0 ? o : null;
    }

    // ================= 点亮层的贴图 =================
    /** 一块灯罩：p 投影到灯罩平面的二维坐标（米），i 三角形顶点索引，uv 原贴图坐标（或 null），img 原灯罩贴图像素（或 null），base 没贴图时灯罩的颜色 */
    static final class Piece { float[] p; int[] i; float[] uv; Img img; float[] base; }
    static final class Img { final int w, h; final byte[] d; Img(int w, int h, byte[] d) { this.w = w; this.h = h; this.d = d; } }
    static final class Baked { int w, h; byte[] data; List<float[]> uvs = new ArrayList<>(); }

    static Baked bake(List<Piece> pieces) {
        List<float[]> b = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        for (Piece pc : pieces) {
            float[] bb = bounds(pc.p);
            float ex = Math.max(Math.max(bb[2], bb[3]), 1e-6f), s = Math.min(DENS, (CELL_MAX - 2 * PAD) / ex);
            sizes.add(new int[] { Math.max(16, (int) Math.ceil(bb[2] * s) + 2 * PAD), Math.max(16, (int) Math.ceil(bb[3] * s) + 2 * PAD) });
            b.add(bb);
        }
        int[][] lay = pack(sizes);
        int W = lay[0][0], H = lay[0][1];
        Baked out = new Baked();
        out.w = W; out.h = H; out.data = new byte[W * H * 4];
        for (int i = 0; i < pieces.size(); i++) out.uvs.add(paint(out.data, W, H, lay[i + 1], pieces.get(i), b.get(i)));
        return out;
    }
    /** x0, y0, w, h */
    static float[] bounds(float[] p) {
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1;
        for (int i = 0; i < p.length; i += 2) {
            if (p[i] < x0) x0 = p[i]; if (p[i] > x1) x1 = p[i];
            if (p[i + 1] < y0) y0 = p[i + 1]; if (p[i + 1] > y1) y1 = p[i + 1];
        }
        if (Float.isInfinite(x0)) x0 = y0 = x1 = y1 = 0;
        return new float[] { x0, y0, x1 - x0, y1 - y0 };
    }
    static int pow2(double v) { return (int) Math.pow(2, Math.ceil(Math.log(Math.max(1, v)) / Math.log(2))); }
    /** 格子按高度排成一行行装进一张图；装不下整体缩小再装。返回 [ [w, h], cell0 [x, y, w, h], cell1 … ] */
    static int[][] pack(List<int[]> sizes) {
        double scale = 1;
        for (int t = 0; t < 8; t++) {
            int n = sizes.size();
            int[][] sc = new int[n][];
            double area = 0;
            int big = 1;
            for (int i = 0; i < n; i++) {
                sc[i] = new int[] { Math.max(16, (int) Math.round(sizes.get(i)[0] * scale)), Math.max(16, (int) Math.round(sizes.get(i)[1] * scale)) };
                area += sc[i][0] * (double) sc[i][1]; big = Math.max(big, Math.max(sc[i][0], sc[i][1]));
            }
            int width = Math.min(ATLAS_MAX, Math.max(pow2(Math.ceil(Math.sqrt(area * 1.15))), pow2(big)));
            for (; width <= ATLAS_MAX; width *= 2) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < n; i++) order.add(i);
                final int[][] s2 = sc;
                Collections.sort(order, (a, c) -> s2[c][1] != s2[a][1] ? s2[c][1] - s2[a][1] : s2[c][0] - s2[a][0]);
                int[][] cells = new int[n + 1][];
                int x = 0, y = 0, rowH = 0;
                for (int i : order) {
                    if (x + sc[i][0] > width) { x = 0; y += rowH; rowH = 0; }
                    cells[i + 1] = new int[] { x, y, sc[i][0], sc[i][1] };
                    x += sc[i][0]; rowH = Math.max(rowH, sc[i][1]);
                }
                int height = pow2(y + rowH);
                if (height <= ATLAS_MAX) { cells[0] = new int[] { width, height }; return cells; }
            }
            scale *= 0.7;
        }
        throw new IllegalStateException("灯光贴图装不下");
    }
    /** 画一块：光栅化（像素中心到三条边都不少于 -0.7 像素就算盖住），顺带插值出原 UV；原贴图的明暗按分位拉开；离边缘的距离用 3-4 倒角距离变换算 */
    static float[] paint(byte[] data, int W, int H, int[] cell, Piece pc, float[] b) {
        int iw = cell[2] - 2 * PAD, ih = cell[3] - 2 * PAD;
        float s = Math.min(iw / Math.max(b[2], 1e-6f), ih / Math.max(b[3], 1e-6f));
        if (Float.isNaN(s) || Float.isInfinite(s) || s <= 0) s = 1;
        float ox = PAD + (iw - b[2] * s) / 2, oy = PAD + (ih - b[3] * s) / 2;
        int n = pc.p.length / 2;
        float[] pts = new float[n * 2], out = new float[n * 2];
        for (int v = 0; v < n; v++) {
            pts[v * 2] = ox + (pc.p[v * 2] - b[0]) * s;
            pts[v * 2 + 1] = oy + (pc.p[v * 2 + 1] - b[1]) * s;
            out[v * 2] = (cell[0] + pts[v * 2]) / W;
            out[v * 2 + 1] = (cell[1] + pts[v * 2 + 1]) / H;
        }
        int cw = cell[2], ch = cell[3], N = cw * ch;
        byte[] mask = new byte[N];
        float[] su = pc.uv != null && pc.img != null ? new float[N * 2] : null;
        int[] idx = pc.i;
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t], c1 = idx[t + 1], c2 = idx[t + 2];
            float ax = pts[a * 2], ay = pts[a * 2 + 1], bx = pts[c1 * 2], by = pts[c1 * 2 + 1], cx = pts[c2 * 2], cy = pts[c2 * 2 + 1];
            float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Float.isNaN(area) || Float.isInfinite(area)) continue;
            float sg = area >= 0 ? 1 : -1;
            float la = hyp(bx - ax, by - ay), lb = hyp(cx - bx, cy - by), lc = hyp(ax - cx, ay - cy);
            if (la == 0) la = 1; if (lb == 0) lb = 1; if (lc == 0) lc = 1;
            int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))) - 1), x1 = Math.min(cw - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))) + 1);
            int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))) - 1), y1 = Math.min(ch - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))) + 1);
            for (int y = y0; y <= y1; y++) {
                float py = y + 0.5f;
                for (int x = x0; x <= x1; x++) {
                    float px = x + 0.5f;
                    float eAB = ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) * sg, eBC = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * sg, eCA = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * sg;
                    if (eAB / la < -0.7f || eBC / lb < -0.7f || eCA / lc < -0.7f) continue;
                    int at = y * cw + x;
                    mask[at] = 1;
                    if (su != null && Math.abs(area) > 1e-9f) {
                        float A = Math.abs(area), wA = Math.max(0, eBC) / A, wB = Math.max(0, eCA) / A, wC = Math.max(0, eAB) / A, sum = wA + wB + wC;
                        if (sum == 0) sum = 1;
                        su[at * 2] = (pc.uv[a * 2] * wA + pc.uv[c1 * 2] * wB + pc.uv[c2 * 2] * wC) / sum;
                        su[at * 2 + 1] = (pc.uv[a * 2 + 1] * wA + pc.uv[c1 * 2 + 1] * wB + pc.uv[c2 * 2 + 1] * wC) / sum;
                    }
                }
            }
        }
        float[] inside = chamfer(mask, cw, ch, 1), outside = chamfer(mask, cw, ch, 0);
        float[] lum = new float[N];
        float[] samples = new float[N];
        int ns = 0;
        float dMax = 1;
        for (int i = 0; i < N; i++) {
            if (mask[i] == 0) continue;
            float[] rgb = su != null ? sample(pc.img, su[i * 2], su[i * 2 + 1]) : pc.base;
            lum[i] = rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f;
            samples[ns++] = lum[i];
            if (inside[i] > dMax) dMax = inside[i];
        }
        if (ns == 0) return out;
        Arrays.sort(samples, 0, ns);
        float lo = samples[(int) Math.floor(ns * 0.1)], hi = samples[Math.min(ns - 1, (int) Math.floor(ns * 0.9))];
        boolean flat = hi - lo < 0.04f;
        int one = Math.min(255, Math.round(255 / dMax));
        for (int yy = 0; yy < ch; yy++) {
            for (int xx = 0; xx < cw; xx++) {
                int i = yy * cw + xx;
                boolean cov = mask[i] == 1;
                if (!cov && outside[i] > DILATE) continue;
                float L = cov ? lum[i] : near(mask, lum, cw, ch, xx, yy);
                int o = ((cell[1] + yy) * W + cell[0] + xx) * 4;
                data[o] = (byte) (flat ? 255 : Math.round(clamp01((L - lo) / (hi - lo)) * 255));
                data[o + 1] = (byte) (cov ? Math.round(clamp01(inside[i] / dMax) * 255) : 0);
                data[o + 2] = (byte) one;
                data[o + 3] = (byte) 255;
            }
        }
        return out;
    }
    private static float hyp(float x, float y) { return (float) Math.sqrt(x * x + y * y); }
    static float near(byte[] mask, float[] lum, int w, int h, int x, int y) {
        for (int r = 1; r <= DILATE + 1; r++)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    int X = x + dx, Y = y + dy;
                    if (X < 0 || Y < 0 || X >= w || Y >= h) continue;
                    if (mask[Y * w + X] != 0) return lum[Y * w + X];
                }
        return 0.5f;
    }
    /** 3-4 倒角距离变换：target 像素到最近的非 target 像素有多远（像素） */
    static float[] chamfer(byte[] mask, int w, int h, int target) {
        float[] d = new float[w * h];
        float INF = 1e9f;
        for (int i = 0; i < d.length; i++) d[i] = mask[i] == target ? INF : 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int at = y * w + x; float v = d[at];
            if (v == 0) continue;
            if (x > 0) v = Math.min(v, d[at - 1] + 3);
            if (y > 0) {
                v = Math.min(v, d[at - w] + 3);
                if (x > 0) v = Math.min(v, d[at - w - 1] + 4);
                if (x < w - 1) v = Math.min(v, d[at - w + 1] + 4);
            }
            d[at] = v;
        }
        for (int y = h - 1; y >= 0; y--) for (int x = w - 1; x >= 0; x--) {
            int at = y * w + x; float v = d[at];
            if (v == 0) continue;
            if (x < w - 1) v = Math.min(v, d[at + 1] + 3);
            if (y < h - 1) {
                v = Math.min(v, d[at + w] + 3);
                if (x < w - 1) v = Math.min(v, d[at + w + 1] + 4);
                if (x > 0) v = Math.min(v, d[at + w - 1] + 4);
            }
            d[at] = v;
        }
        for (int i = 0; i < d.length; i++) d[i] = d[i] >= INF ? 0 : d[i] / 3;
        return d;
    }
    /** 原贴图双线性取样（平铺；glTF 的 UV 左上角是 0,0） */
    static float[] sample(Img img, float u, float v) {
        float fx = (float) ((u - Math.floor(u)) * img.w - 0.5), fy = (float) ((v - Math.floor(v)) * img.h - 0.5);
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int xa = ((x0 % img.w) + img.w) % img.w, xb = (xa + 1) % img.w, ya = ((y0 % img.h) + img.h) % img.h, yb = (ya + 1) % img.h;
        float[] o = new float[3];
        for (int c = 0; c < 3; c++) {
            float top = (img.d[(ya * img.w + xa) * 4 + c] & 255) * (1 - tx) + (img.d[(ya * img.w + xb) * 4 + c] & 255) * tx;
            float bot = (img.d[(yb * img.w + xa) * 4 + c] & 255) * (1 - tx) + (img.d[(yb * img.w + xb) * 4 + c] & 255) * tx;
            o[c] = (top * (1 - ty) + bot * ty) / 255;
        }
        return o;
    }

    // ================= 路面光束 =================
    /** 光斑在贴图上的横向位置（u）：按灯罩的横向坐标聚成 1～2 团，或者固定单 / 双光斑 */
    static float[] lobes(float[] xs, int nxs, float c, float hw, float cw, Beam bm) {
        if ("single".equals(bm.lobeMode)) return new float[] { 0.5f };
        if ("double".equals(bm.lobeMode)) { float half = clamp01(bm.lobeSpacing * cw / 2 / Math.max(1e-6f, 2 * hw)); return new float[] { clamp01(0.5f - half), clamp01(0.5f + half) }; }
        if (nxs == 0) return new float[] { uOf(c, c, hw) };
        float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < nxs; i++) { if (xs[i] < mn) mn = xs[i]; if (xs[i] > mx) mx = xs[i]; }
        if (mx - mn > cw * 0.3f) {
            float mid = (mn + mx) / 2, ls = 0, rs = 0;
            int ln = 0, rn = 0;
            for (int i = 0; i < nxs; i++) { if (xs[i] > mid) { ls += xs[i]; ln++; } else { rs += xs[i]; rn++; } }
            return new float[] { uOf(ls / Math.max(1, ln), c, hw), uOf(rs / Math.max(1, rn), c, hw) };
        }
        float sum = 0;
        for (int i = 0; i < nxs; i++) sum += xs[i];
        return new float[] { uOf(sum / nxs, c, hw) };
    }
    private static float uOf(float x, float c, float hw) { return clamp01((c + hw - x) / Math.max(1e-6f, 2 * hw)); }
    /** 光束的透明度贴图（RGBA）：第 0 行是远端（v = 0），最后一行贴着车 */
    static byte[] beamTex(Beam bm, float[] lb) {
        int N = BEAM_PX;
        byte[] d = new byte[N * N * 4];
        float sp = clamp01(bm.spread), fall = clamp01(bm.falloff), hazeW = 0.5f * clamp01(bm.haze), lw = clamp01(bm.lobeWidth);
        String shape = bm.shape;
        boolean bar = "bar".equals(shape), pool = "pool".equals(shape);
        float sigma0 = 0.04f + 0.14f * lw, growth = bar ? 0.3f * sp : 0.6f + 3.4f * sp, nearFall = bar ? 0.3f + 0.9f * fall : 0.08f + 0.32f * fall, farFall = 0.3f + 0.5f * fall;
        float hazeS0 = 0.1f + 0.15f * sp, poolU = 0.08f + 0.3f * lw, poolT = 0.2f + 0.45f * fall, poolC = 0.22f;
        if (lb == null || lb.length == 0) lb = new float[] { 0.5f };
        for (int y = 0; y < N; y++) {
            float t = 1 - y / (float) (N - 1), sigma = sigma0 * (1 + growth * t), hs = hazeS0 * (1 + growth * t);
            float along = (float) Math.min(1, 0.85 * Math.exp(-t / nearFall) + 0.25 * Math.exp(-t / farFall));
            float env = (pool ? 1 : along) * (1 - smooth(0.7f, 1, t)) * smooth(0, 0.1f, t);
            for (int x = 0; x < N; x++) {
                float u = x / (float) (N - 1), lat = 1 - smooth(0.5f, 1, Math.abs(u * 2 - 1)), lobe = 0;
                for (float l : lb) {
                    if (pool) { float dt = (t - poolC) / poolT, du = (u - l) / poolU; lobe += (float) Math.exp(-0.5 * (du * du + dt * dt)); }
                    else { float dd = (u - l) / sigma; lobe += (float) Math.exp(-0.5 * dd * dd); }
                }
                lobe = Math.min(1, lobe);
                float hd = (u - 0.5f) / hs, haze = (float) Math.exp(-0.5 * hd * hd) * hazeW * (pool ? along : 1);
                float a = env * lat * Math.min(1, lobe + haze * (1 - lobe));
                int o = (y * N + x) * 4;
                d[o] = d[o + 1] = d[o + 2] = (byte) 255;
                d[o + 3] = (byte) Math.round(clamp01(a) * 255);
            }
        }
        return d;
    }
}
