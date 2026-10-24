package com.x3card.car3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Col;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Half;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Tex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 车牌（照网页版 car3d.js 的车牌部分，按国标 GA 36-2018 的图 1～8、图 19 画；应急牌照国办 2018 年的通知）：版式、颜色两张表，
 * 牌面用安卓画布画（字形用国标字样 plate-font.json，国标里没有的"应""急"用系统字体近似），一块 3 毫米厚的圆角薄板 + 正面贴牌面。
 * 位置、角度、跟着会动的部件走在 CarPage 里
 */
final class Plates {
    private Plates() {}
    static final int YEL = 0xffffc20d, INK = 0xff231f20, RED = 0xffe2232a, NIGHT = 0xff17181a;
    /** 版式：外廓 W×H（毫米）、字格 [左, 宽, 上, 高]（单行牌只有前两个：字高 90、上 25）、间隔符、铆钉孔 [左, 中心高]、新能源标志、每格的字形 */
    static final class Lay {
        final float W, H; final float[][] cells, slots; final float[] sep; final boolean logo; final String fonts;
        /** sep：{ after, x, y, r } 圆点或 { after, x, y, 0, w, h } 短横 */
        Lay(float W, float H, float[][] cells, float[] sep, float[][] slots, boolean logo, String fonts) { this.W = W; this.H = H; this.cells = cells; this.sep = sep; this.slots = slots; this.logo = logo; this.fonts = fonts; }
    }
    private static final float[][] C440 = { { 15, 45 }, { 72, 45 }, { 151, 45 }, { 208, 45 }, { 265, 45 }, { 322, 45 }, { 379, 45 } };
    private static final float[][] C480 = { { 15.5f, 45 }, { 69.5f, 43 }, { 161.5f, 43 }, { 213.5f, 43 }, { 265.5f, 43 }, { 317.5f, 43 }, { 369.5f, 43 }, { 421.5f, 43 } };
    private static final float[][] CPOL = { { 15, 45 }, { 94, 45 }, { 151, 45 }, { 208, 45 }, { 265, 45 }, { 322, 45 }, { 379, 45 } };
    private static final float[][] S440 = { { 100, 12.5f }, { 325, 12.5f }, { 100, 127.5f }, { 325, 127.5f } }, S480 = { { 120, 12.5f }, { 345, 12.5f }, { 120, 127.5f }, { 345, 127.5f } };
    static final Map<String, Lay> LAYOUT = new HashMap<>();
    static {
        LAYOUT.put("l440", new Lay(440, 140, C440, new float[] { 2, 134, 70, 5 }, S440, false, null));
        LAYOUT.put("l480", new Lay(480, 140, C480, null, S480, true, null));
        LAYOUT.put("l480b", new Lay(480, 140, C480, null, new float[][] { { 105, 12.5f }, { 360, 12.5f }, { 105, 127.5f }, { 360, 127.5f } }, true, null));
        LAYOUT.put("row2", new Lay(440, 220, new float[][] { { 110, 80, 15, 60 }, { 250, 80, 15, 60 }, { 27.5f, 65, 90, 110 }, { 107.5f, 65, 90, 110 }, { 187.5f, 65, 90, 110 }, { 267.5f, 65, 90, 110 }, { 347.5f, 65, 90, 110 } },
            new float[] { 1, 220, 57, 5 }, new float[][] { { 85, 12.5f }, { 340, 12.5f }, { 100, 207.5f }, { 325, 207.5f } }, false, "ttbbbbb"));
        LAYOUT.put("low", new Lay(300, 165, new float[][] { { 70, 55, 10, 45 }, { 175, 55, 10, 45 }, { 13.5f, 45, 65, 90 }, { 70.5f, 45, 65, 90 }, { 127.5f, 45, 65, 90 }, { 184.5f, 45, 65, 90 }, { 241.5f, 45, 65, 90 } },
            new float[] { 1, 150, 32.5f, 5 }, new float[][] { { 15, 12.5f }, { 270, 12.5f } }, false, "ttnnnnn"));
        LAYOUT.put("police", new Lay(440, 140, CPOL, new float[] { 1, 77, 70, 7 }, S440, false, null));
        LAYOUT.put("policeR", new Lay(440, 140, CPOL, new float[] { 1, 77, 70, 0, 16, 10 }, S440, false, null));
        LAYOUT.put("embassy", new Lay(440, 140, new float[][] { { 15.5f, 45 }, { 72.5f, 45 }, { 129.5f, 45 }, { 208.5f, 45 }, { 265.5f, 45 }, { 322.5f, 45 }, { 379.5f, 45 } }, new float[] { 3, 191.5f, 70, 5 }, S440, false, null));
        LAYOUT.put("consulate", new Lay(440, 140, new float[][] { { 15.5f, 45 }, { 72.5f, 45 }, { 129.5f, 45 }, { 186.5f, 45 }, { 265.5f, 45 }, { 322.5f, 45 }, { 379.5f, 45 } }, new float[] { 4, 248.5f, 70, 5 }, S440, false, null));
        LAYOUT.put("emergency", new Lay(480, 140, new float[][] { { 15.5f, 45 }, { 91, 43 }, { 146.2f, 43 }, { 201.4f, 43 }, { 256.6f, 43 }, { 311.8f, 43 }, { 366.5f, 43.5f }, { 421, 43.5f } }, new float[] { 1, 76, 70, 5.5f }, S480, false, null));
    }
    /** 牌种：版式（layR 后牌不一样的）、底色（两个是竖向渐变）、右边一块（大型新能源）、字色、边框线离边几毫米到几毫米、铆钉孔颜色 / 描边、红字的位置（redF 只有前牌红）、固定的字 */
    static final class Style {
        String lay, layR; int[] bg; int panel, fg, slot, slotLine; float[] frame; int[] red = {}, redF = {}; final Map<Integer, String> fix = new HashMap<>();
        Style(String lay, String layR, int[] bg, int fg, float f0, float f1, int slot, int slotLine) { this.lay = lay; this.layR = layR; this.bg = bg; this.fg = fg; frame = new float[] { f0, f1 }; this.slot = slot; this.slotLine = slotLine; }
        Style fix(int i, String s) { fix.put(i, s); return this; }
    }
    static final Map<String, Style> PLATE = new HashMap<>();
    static {
        PLATE.put("blue", new Style("l440", null, new int[] { 0xff213f99 }, 0xffffffff, 1.5f, 3, 0xffe6ebf5, 0));
        PLATE.put("green", new Style("l480", null, new int[] { 0xffffffff, 0xff7dc685 }, INK, 2, 4, 0xfff7faf7, 0xff8a948c));
        PLATE.put("yellow", new Style("l440", "row2", new int[] { YEL }, INK, 1.5f, 3, 0xfffff3c4, 0));
        Style nev = new Style("l480", "l480b", new int[] { 0xffeba749 }, INK, 2, 4, 0xffffffff, 0); nev.panel = 0xff7dc685; PLATE.put("bignev", nev);
        Style pol = new Style("police", "policeR", new int[] { 0xffffffff }, INK, 1.5f, 3, 0xffffffff, 0xff6b6b6b).fix(6, "警"); pol.red = new int[] { 6 }; PLATE.put("police", pol);
        Style em = new Style("emergency", null, new int[] { 0xffffffff }, INK, 2, 4, 0xffffffff, 0xff6b6b6b).fix(6, "应").fix(7, "急"); em.red = new int[] { 6, 7 }; em.redF = new int[] { 1 }; PLATE.put("emergency", em);
        PLATE.put("coach", new Style("l440", null, new int[] { YEL }, INK, 1.5f, 3, 0xfffff3c4, 0).fix(6, "学"));
        PLATE.put("trailer", new Style("row2", null, new int[] { YEL }, INK, 1.5f, 3, 0xfffff3c4, 0).fix(6, "挂"));
        PLATE.put("lowspeed", new Style("low", null, new int[] { YEL }, INK, 1.5f, 3, 0xfffff3c4, 0));
        PLATE.put("embassy", new Style("embassy", null, new int[] { NIGHT }, 0xffffffff, 1.5f, 3, 0xffdcdcdc, 0).fix(6, "使"));
        PLATE.put("consulate", new Style("consulate", null, new int[] { NIGHT }, 0xffffffff, 1.5f, 3, 0xffdcdcdc, 0).fix(6, "领"));
        PLATE.put("hkmo", new Style("l440", null, new int[] { NIGHT }, 0xffffffff, 1.5f, 3, 0xffdcdcdc, 0).fix(0, "粤").fix(1, "Z"));
    }
    /** 这块牌子（前 / 后）的样式和版式；自定义：版式同蓝牌，颜色自己定 */
    static Style style(JSONObject st) {
        Style s = PLATE.get(st.optString("style"));
        if (s != null) return s;
        return new Style("l440", null, new int[] { Color.parseColor(st.optString("bg", "#e9e9e9")) }, Color.parseColor(st.optString("fg", "#15171a")), 1.5f, 3, 0xfff2f2f2, 0);
    }
    static Lay lay(Style s, boolean rear) { return LAYOUT.get(rear && s.layR != null ? s.layR : s.lay); }

    // ================= 字形 =================
    private static JSONObject font;
    private static final Map<String, Path> paths = new HashMap<>();
    /** 国标字样（第一次用到时读，读不出来就都用系统字体） */
    static synchronized JSONObject font(Context c) {
        if (font == null) {
            try { font = new JSONObject(new String(Half.readAll(c.getAssets().open("plate-font.json")), "UTF-8")); }
            catch (Exception e) { font = new JSONObject(); }
        }
        return font;
    }
    private static Bitmap gly;
    /** 在离屏画布上画一个字，返回墨迹范围（像素，基线在 y = 280） */
    private static Rect glyph(String ch, Typeface tf, int color) {
        if (gly == null) gly = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888);
        gly.eraseColor(0);
        Canvas x = new Canvas(gly);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf); p.setTextSize(240); p.setColor(color);
        x.drawText(ch, 30, 280, p);
        int N = 360, l = N, r = -1, t = N, b = -1;
        int[] px = new int[N * N];
        gly.getPixels(px, 0, N, 0, 0, N, N);
        for (int y = 0; y < N; y++) for (int i = 0; i < N; i++) if ((px[y * N + i] >>> 24) > 60) { if (i < l) l = i; if (i > r) r = i; if (y < t) t = y; if (y > b) b = y; }
        return r < 0 ? null : new Rect(l, t, r + 1, b + 1);
    }
    private static void rrect(Path p, float X, float Y, float W2, float H2, float R) { p.reset(); p.addRoundRect(new RectF(X, Y, X + W2, Y + H2), R, R, Path.Direction.CW); }
    /** 新能源标志（效果图 B.4、B.5）：浅绿外圈，左边一道道彩色横条，右边一个白色插头 */
    private static void nevLogo(Canvas x, float cx, float cy, float R) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xff84d2b4); x.drawCircle(cx, cy, R, p);
        float r = R * 0.86f;
        x.save();
        Path clip = new Path(); clip.addCircle(cx, cy, r, Path.Direction.CW); x.clipPath(clip);
        int[] cols = { 0xff40acd4, 0xffda3477, 0xffec7a2a, 0xffc1ce39, 0xff4faa47 };
        float[][] bands = { { -1, -0.42f }, { -0.42f, -0.16f }, { -0.16f, 0.2f }, { 0.2f, 0.46f }, { 0.46f, 1 } };
        for (int i = 0; i < 5; i++) { p.setColor(cols[i]); x.drawRect(cx - r, cy + bands[i][0] * r, cx + r, cy + bands[i][1] * r, p); }
        p.setColor(0xffffffff);
        for (float y0 : new float[] { -0.09f, 0.03f, 0.14f }) x.drawRect(cx - r, cy + y0 * r, cx - r + 1.4f * r, cy + y0 * r + 0.045f * r, p);
        float pxx = cx + 0.18f * r;
        Path plug = new Path();
        plug.moveTo(pxx, cy - 0.66f * r);
        plug.arcTo(new RectF(pxx - 0.66f * r, cy - 0.66f * r, pxx + 0.66f * r, cy + 0.66f * r), -90, 180);
        plug.lineTo(pxx, cy + 0.36f * r); plug.lineTo(cx - 0.52f * r, cy + 0.36f * r); plug.lineTo(cx - 0.52f * r, cy - 0.36f * r); plug.lineTo(pxx, cy - 0.36f * r); plug.close();
        p.setColor(0xffffffff); x.drawPath(plug, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.09f * r); p.setColor(0xff3f6b5c); x.drawPath(plug, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xffec7a2a);
        for (float y0 : new float[] { -0.16f, -0.03f, 0.09f }) x.drawRect(cx - r, cy + y0 * r, cx - r + 0.7f * r + 0.18f * r, cy + y0 * r + 0.075f * r, p);
        x.restore();
    }
    private static boolean has(int[] a, int v) { for (int x : a) if (x == v) return true; return false; }
    static final float PK = 2.6f; // 牌面画布每毫米几个像素

    /** 画牌面（同 plateCanvas） */
    static Bitmap canvas(Context ctx, JSONObject st, boolean rear) {
        Style s = style(st);
        Lay L = lay(s, rear);
        float k = PK;
        Bitmap bm = Bitmap.createBitmap(Math.round(L.W * k), Math.round(L.H * k), Bitmap.Config.ARGB_8888);
        Canvas x = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (s.bg.length > 1) p.setShader(new LinearGradient(0, 0, 0, bm.getHeight(), new int[] { s.bg[0], s.bg[1], s.bg[1] }, new float[] { 0, 0.85f, 1 }, Shader.TileMode.CLAMP));
        else p.setColor(s.bg[0]);
        x.drawRect(0, 0, bm.getWidth(), bm.getHeight(), p);
        p.setShader(null);
        Path path = new Path();
        if (s.panel != 0) { rrect(path, 136.5f * k, 10 * k, 336 * k, 120 * k, 4 * k); p.setColor(s.panel); x.drawPath(path, p); }
        for (float[] sl : L.slots) {
            rrect(path, sl[0] * k, (sl[1] - 4) * k, 15 * k, 8 * k, 4 * k);
            p.setColor(s.slot); x.drawPath(path, p);
            if (s.slotLine != 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.6f * k); p.setColor(s.slotLine); x.drawPath(path, p); p.setStyle(Paint.Style.FILL); }
        }
        float f0 = s.frame[0], f1 = s.frame[1], fm = (f0 + f1) / 2;
        rrect(path, fm * k, fm * k, (L.W - 2 * fm) * k, (L.H - 2 * fm) * k, (10 - fm) * k);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth((f1 - f0) * k); p.setColor(s.fg); x.drawPath(path, p); p.setStyle(Paint.Style.FILL);
        // 号
        String text = st.optString("text", "").toUpperCase().replaceAll("[^A-Z0-9\\u4e00-\\u9fa5]", "");
        int nc = Math.min(text.codePointCount(0, text.length()), L.cells.length);
        JSONObject F = font(ctx);
        Typeface CN = Typeface.create(Typeface.DEFAULT, Typeface.BOLD), AN = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        Rect ref = null, zero = null;
        for (int i = 0; i < L.cells.length; i++) {
            String ch = i < nc ? text.substring(text.offsetByCodePoints(0, i), text.offsetByCodePoints(0, i + 1)) : s.fix.get(i);
            if (ch == null) continue;
            float[] cell = L.cells[i];
            float cw = cell[1] * k, cx = (cell[0] + cell[1] / 2) * k, top = (cell.length > 2 ? cell[2] : 25) * k, chh = (cell.length > 3 ? cell[3] : 90) * k;
            boolean cn = ch.matches("[\\u4e00-\\u9fa5]");
            int col = has(s.red, i) || (!rear && has(s.redF, i)) ? RED : s.fg;
            String fs = L.fonts != null ? String.valueOf(L.fonts.charAt(i)) : "n";
            JSONObject set = F.optJSONObject(fs);
            JSONArray e = set != null ? set.optJSONArray(ch) : null;
            String key = fs;
            if (e == null && "b".equals(fs) && F.optJSONObject("t") != null) { e = F.optJSONObject("t").optJSONArray(ch); key = "t"; } // 双行牌下排的"挂"在上排那套里
            if (e != null) {
                float gw = cn ? cw : Math.min((float) e.optDouble(0) * chh, cw);
                Path gp;
                synchronized (paths) { gp = paths.get(key + ch); if (gp == null) { gp = SvgPath.of(e.optString(1)); paths.put(key + ch, gp); } }
                Matrix m = new Matrix(); m.setScale(gw / 400, chh / 400); m.postTranslate(cx - gw / 2, top);
                Path q = new Path(); gp.transform(m, q);
                p.setColor(col); x.drawPath(q, p);
                continue;
            }
            if (!cn && ref == null) { ref = glyph("H", AN, col); zero = glyph("0", AN, col); }
            Rect b = glyph(ch, cn ? CN : AN, col);
            if (b == null) continue;
            int bw = b.width(), bh = b.height();
            if (cn) { x.drawBitmap(gly, b, new RectF(cell[0] * k, top, cell[0] * k + cw, top + chh), p); continue; } // 汉字：整个撑满字格
            if (ref == null || zero == null) continue;
            float sy = chh / (float) ref.height(), sxr = cw / (float) zero.width(), dw = Math.min(bw * sxr, cw);
            x.drawBitmap(gly, b, new RectF(cx - dw / 2, top + (b.top - ref.top) * sy, cx + dw / 2, top + (b.top - ref.top) * sy + bh * sy), p);
        }
        // 间隔符（两边都有字了才画）；新能源牌第 2、3 字之间是标志
        float[] sp = L.sep;
        if (sp != null && nc > sp[0]) {
            p.setColor(s.fg);
            if (sp[3] > 0) x.drawCircle(sp[1] * k, sp[2] * k, sp[3] * k, p);
            else x.drawRect((sp[1] - sp[4] / 2) * k, (sp[2] - sp[5] / 2) * k, (sp[1] + sp[4] / 2) * k, (sp[2] + sp[5] / 2) * k, p);
        }
        if (L.logo) nevLogo(x, 137 * k, 70 * k, 13.5f * k);
        return bm;
    }

    // ================= 板子 =================
    /** 圆角矩形的轮廓（米，中心在原点，逆时针）：每个角 10 段（同 three 的 Shape 按 curveSegments 5 取圆弧） */
    private static float[] outline(float w, float h, float r) {
        int seg = 10;
        float[] o = new float[4 * (seg + 1) * 2];
        float[][] cs = { { w / 2 - r, -h / 2 + r, -90 }, { w / 2 - r, h / 2 - r, 0 }, { -w / 2 + r, h / 2 - r, 90 }, { -w / 2 + r, -h / 2 + r, 180 } };
        int n = 0;
        for (float[] c : cs) for (int i = 0; i <= seg; i++) { double a = Math.toRadians(c[2] + 90.0 * i / seg); o[n++] = c[0] + r * (float) Math.cos(a); o[n++] = c[1] + r * (float) Math.sin(a); }
        return o;
    }
    /** 一块车牌：3 毫米厚的圆角板子（R10，按宽度缩放）+ 正面贴牌面（同 mkPlate）；宽度没调过按国标外廓 */
    static Obj3D mesh(Context ctx, JSONObject st, boolean rear) {
        Style s = style(st);
        Lay L = lay(s, rear);
        JSONObject p = st.optJSONObject(rear ? "rear" : "front");
        float w = p != null && p.has("w") ? (float) p.optDouble("w") : L.W / 1000, h = w * L.H / L.W, r = w * 10 / L.W, D = 0.003f;
        float[] o = outline(w, h, r);
        int n = o.length / 2;
        // 板子：侧面 + 背面（平面着色），正面盖着牌面
        float[] pos = new float[(n * 6 + (n - 2) * 3) * 3], nor = new float[pos.length];
        int q = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float x0 = o[i * 2], y0 = o[i * 2 + 1], x1 = o[j * 2], y1 = o[j * 2 + 1], nx = y1 - y0, ny = -(x1 - x0), nl = (float) Math.sqrt(nx * nx + ny * ny);
            if (nl > 0) { nx /= nl; ny /= nl; }
            float[][] v = { { x0, y0, 0 }, { x0, y0, -D }, { x1, y1, -D }, { x0, y0, 0 }, { x1, y1, -D }, { x1, y1, 0 } };
            for (float[] a : v) { pos[q] = a[0]; pos[q + 1] = a[1]; pos[q + 2] = a[2]; nor[q] = nx; nor[q + 1] = ny; nor[q + 2] = 0; q += 3; }
        }
        for (int i = 1; i < n - 1; i++) {
            int[] tri = { 0, i + 1, i }; // 背面朝 -z
            for (int t : tri) { pos[q] = o[t * 2]; pos[q + 1] = o[t * 2 + 1]; pos[q + 2] = -D; nor[q] = 0; nor[q + 1] = 0; nor[q + 2] = -1; q += 3; }
        }
        Geo bg = new Geo();
        bg.set("position", Attr.floats(pos, 3)); bg.set("normal", Attr.floats(nor, 3));
        bg.computeBounds();
        Mat bm = Mat.standard();
        Col edge = new Col(); edge.setHex(s.bg[s.bg.length - 1] & 0xffffff); edge.multiplyScalar(0.75f);
        bm.color.copy(edge); bm.roughness = 0.55f; bm.metalness = 0.3f;
        // 牌面：扇形三角形，贴图坐标按牌子大小换到 0～1
        float[] fp = new float[(n + 1) * 3], fn = new float[(n + 1) * 3], fu = new float[(n + 1) * 2];
        fn[2] = 1; fu[0] = 0.5f; fu[1] = 0.5f;
        for (int i = 0; i < n; i++) { fp[(i + 1) * 3] = o[i * 2]; fp[(i + 1) * 3 + 1] = o[i * 2 + 1]; fn[(i + 1) * 3 + 2] = 1; fu[(i + 1) * 2] = o[i * 2] / w + 0.5f; fu[(i + 1) * 2 + 1] = o[i * 2 + 1] / h + 0.5f; }
        int[] fi = new int[n * 3];
        for (int i = 0; i < n; i++) { fi[i * 3] = 0; fi[i * 3 + 1] = i + 1; fi[i * 3 + 2] = (i + 1) % n + 1; }
        Geo fg = new Geo();
        fg.set("position", Attr.floats(fp, 3)); fg.set("normal", Attr.floats(fn, 3)); fg.set("uv", Attr.floats(fu, 2));
        fg.setIndex(Attr.index(fi, n + 1));
        fg.computeBounds();
        Bitmap face = canvas(ctx, st, rear);
        Tex tex = Tex.canvas(face, true);
        face.recycle();
        tex.anisotropy = 4; // 车牌常常斜着看
        Mat fm = Mat.standard();
        fm.map = tex; fm.roughness = 0.45f; fm.metalness = 0; fm.polygonOffset = true; fm.polygonOffsetFactor = -1; fm.polygonOffsetUnits = -1;
        Obj3D grp = new Obj3D();
        grp.add(new Mesh(bg, bm));
        grp.add(new Mesh(fg, fm));
        grp.visible = p != null && p.optBoolean("on");
        grp.put("w", w);
        return grp;
    }
}
