package com.x3card.car3d;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 车况页上的字和图标（照 style.css 的 .pg-car 那部分画在一块画布上）：顶栏（挡位、车速、系统图标、电源）、车况图标两列（竖屏一排）、
 * 下面那行字（展开的列表、"全部车况 ›"）、底部数据、操作提示、正在载入。深浅色 0.6 秒过渡（同 CSS 的 transition）。
 * 尺寸按 CSS 像素 × 密度（1rem = 高度 / 10.52）。只有可点的地方接手势，别处交给下面的 3D 画布
 */
@SuppressLint("ViewConstructor")
final class Hud extends View {
    interface Tap { void status(); void pick(int i); void all(); void close(); void debug(); }

    // 浅色 / 深色（同 CSS 变量）：ink、mute、line、chip、ok、warn、bad、info
    private static final int[] LIGHT = { 0xff14171c, 0x8014171c, 0x1f14171c, 0xb3ffffff, 0xff0e9f57, 0xffd97706, 0xffdc2f2f, 0xff2575e6 };
    private static final int[] DARK = { 0xffeef1f6, 0x80eef1f6, 0x21ffffff, 0xb81c1f25, 0xff3fd98a, 0xffffb547, 0xffff6b6b, 0xff5aa2ff };
    private int ink, mute, line, chip, ok, warn, bad, info;
    private float darkK;
    private ValueAnimator themeAnim;

    private final float dens;
    private final Tap tap;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface num300, num500, ui500, ui600, ui700;
    private float rem = 10;
    private int W = 1, H = 1;
    private boolean portrait;

    // 状态
    private String gear = "", speed = "0", power = "—", loading = null;
    private boolean speedOn, live, hint = true, fsOn;
    private List<String[]> stats = new ArrayList<>();
    private List<CarPage.St> status = new ArrayList<>();
    private boolean stOpen;
    private List<CarStat.Item> tl = new ArrayList<>(), tr = new ArrayList<>();
    private List<CarStat.Sys> sys = new ArrayList<>();
    private float hintA = 1;
    // 可点的地方
    private final RectF pillR = new RectF(), allR = new RectF();
    private final List<RectF> listR = new ArrayList<>();
    private boolean pillOn;
    /** 下面那行字的上沿（胎压标签最多到这里）；变了告诉外面 */
    float msgTop;
    Runnable onMsgTop;

    Hud(Context c, Tap tap) {
        super(c);
        this.tap = tap;
        dens = c.getResources().getDisplayMetrics().density;
        num300 = outfit(c, 300); num500 = outfit(c, 500);
        ui500 = Typeface.create(Typeface.DEFAULT, 500, false);
        ui600 = Typeface.create(Typeface.DEFAULT, 600, false);
        ui700 = Typeface.create(Typeface.DEFAULT, 700, false);
        palette(0);
    }
    static Typeface outfit(Context c, int w) {
        try { return new Typeface.Builder(c.getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + w).build(); }
        catch (Exception e) { return Typeface.create(Typeface.DEFAULT, w, false); }
    }
    private static int mix(int a, int b, float k) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16) | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }
    private void palette(float k) {
        darkK = k;
        int[] c = new int[8];
        for (int i = 0; i < 8; i++) c[i] = mix(LIGHT[i], DARK[i], k);
        ink = c[0]; mute = c[1]; line = c[2]; chip = c[3]; ok = c[4]; warn = c[5]; bad = c[6]; info = c[7];
    }
    int ink() { return ink; } int mute() { return mute; } int chip() { return chip; } int line() { return line; } int info() { return info; } int warn() { return warn; } int bad() { return bad; }
    float darkK() { return darkK; }

    // ================= 外面交进来的（主线程） =================
    void setDark(boolean d, boolean anim, Runnable each) {
        float to = d ? 1 : 0;
        if (themeAnim != null) themeAnim.cancel();
        if (!anim) { palette(to); invalidate(); if (each != null) each.run(); return; }
        themeAnim = ValueAnimator.ofFloat(darkK, to);
        themeAnim.setDuration(600);
        themeAnim.addUpdateListener(a -> { palette((Float) a.getAnimatedValue()); invalidate(); if (each != null) each.run(); });
        themeAnim.start();
    }
    void setTop(String g, boolean on, String s, String pw, boolean lv) { gear = g; speedOn = on; speed = s; power = pw; live = lv; invalidate(); }
    void setStats(List<String[]> l) { stats = l; invalidate(); }
    void setStatus(List<CarPage.St> l, boolean open) { status = l; stOpen = open; invalidate(); }
    void setTells(List<CarStat.Item> l, List<CarStat.Item> r) { tl = l; tr = r; invalidate(); }
    void setSys(List<CarStat.Sys> s) { sys = s; invalidate(); }
    void setLoading(String s) { loading = s; invalidate(); }
    void setHint(boolean on) {
        if (on == hint) return;
        hint = on;
        ValueAnimator a = ValueAnimator.ofFloat(hintA, on ? 1 : 0);
        a.setDuration(600);
        a.addUpdateListener(x -> { hintA = (Float) x.getAnimatedValue(); invalidate(); });
        a.start();
    }
    void setFs(boolean on) { fsOn = on; invalidate(); }
    /** 调试宿主：顶栏电源后面一个"调试数据"（横屏才有），点了打开调试面板 */
    void setDemo(boolean on) { demo = on; invalidate(); }
    /** 面板开着（竖屏时车况图标那一排、车速藏起来） */
    void setCovered(boolean on) { if (covered != on) { covered = on; invalidate(); } }
    private boolean covered;
    private boolean demo;
    private final RectF demoR = new RectF();

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        portrait = W < H;
    }
    private float r(float v) { return v * rem; }

    // ================= 画 =================
    @Override protected void onDraw(Canvas c) {
        drawHead(c);
        drawTells(c);
        drawStatus(c);
        drawStats(c);
        if (hintA > 0.01f) { // 操作提示
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.22f)); p.setColor(withA(mute, hintA)); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("按住车身左右拖，转着看", W / 2f, r(portrait ? 2.3f : 1.22f) + r(0.22f), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
        if (loading != null) {
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.22f)); p.setColor(mute); p.setTextAlign(Paint.Align.CENTER);
            c.drawText(loading, W / 2f, H * 0.48f + r(0.22f), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
    private static int withA(int c, float a) { return (Math.round((c >>> 24) * a) << 24) | (c & 0xffffff); }

    private void drawHead(Canvas c) {
        float left = r(portrait ? 0.3f : 0.4f), top = r(portrait ? 0.48f : 0.5f), sz = r(0.46f), cy = top + sz / 2;
        String[] gs = { "P", "R", "N", "D" };
        p.setTypeface(ui600); p.setTextSize(r(0.28f)); p.setTextAlign(Paint.Align.CENTER);
        float x = left;
        for (String g : gs) {
            boolean on = g.equals(gear);
            if (on) { p.setColor(ink); c.drawRoundRect(x, top, x + sz, top + sz, r(0.12f), r(0.12f), p); }
            p.setColor(on ? mix(0xfff4f5f7, 0xff111317, darkK) : mute);
            c.drawText(g, x + sz / 2, baseline(cy, p), p);
            x += sz + r(0.06f);
        }
        p.setTextAlign(Paint.Align.LEFT);
        if (speedOn && !(portrait && covered)) { // 车速（竖屏在车况图标那一排下面：同网页版，顶栏往下 1.22rem，顶栏本身在 top；竖屏开着面板时车挤到上面，会压在车上，同车况图标一起藏）
            float sx = portrait ? left : x - r(0.06f) + r(0.26f), sy = portrait ? top + r(1.22f) + r(0.4f) : cy;
            p.setTypeface(num300); p.setTextSize(r(0.4f)); p.setColor(ink);
            float bl = portrait ? top + r(1.22f) + r(0.4f) * 0.95f : baseline(sy, p);
            c.drawText(speed, sx, bl, p);
            float sw = p.measureText(speed);
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.2f)); p.setColor(mute);
            c.drawText("km/h", sx + sw + r(0.06f), bl, p);
        }
        // 右边：系统图标、电源（右边留给齿轮、全屏键）
        float right = W - r(portrait ? 0.3f : 0.4f) - r(fsOn && !portrait ? 1.32f : 0.62f);
        p.setTypeface(ui500); p.setTextSize(r(0.2f));
        demoR.setEmpty();
        if (demo && !portrait) {
            float dw = p.measureText("调试数据") + r(0.32f), dh = r(0.2f) * 1.45f + r(0.1f);
            demoR.set(right - dw, cy - dh / 2, right, cy + dh / 2);
            sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line);
            c.drawRoundRect(demoR, r(0.3f), r(0.3f), sp);
            sp.setStyle(Paint.Style.FILL);
            p.setColor(warn);
            c.drawText("调试数据", demoR.left + r(0.16f), baseline(cy, p), p);
            right = demoR.left - r(0.1f);
        }
        float pw = p.measureText(power) + (live ? r(0.09f + 0.08f) : 0), ch = r(0.2f) * 1.45f + r(0.1f), cx0 = right - pw - r(0.32f);
        RectF cr = new RectF(cx0, cy - ch / 2, right, cy + ch / 2);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line);
        c.drawRoundRect(cr, r(0.3f), r(0.3f), sp);
        sp.setStyle(Paint.Style.FILL);
        float tx = cx0 + r(0.16f);
        p.setColor(live ? ok : mute);
        if (live) { c.drawCircle(tx + r(0.045f), cy, r(0.045f), p); tx += r(0.17f); }
        c.drawText(power, tx, baseline(cy, p), p);
        // 系统图标：从右往左排在电源前面
        float ix = cx0 - r(0.06f);
        for (int i = sys.size() - 1; i >= 0; i--) {
            if (portrait && i >= 2) continue; // 竖屏只放两个
            CarStat.Sys s = sys.get(i);
            float w = r(0.3f) + (s.label != null ? labelW(s.label) : 0);
            ix -= w;
            drawSys(c, s, ix, cy);
            ix -= r(0.1f);
        }
    }
    private float labelW(String s) { p.setTypeface(ui700); p.setTextSize(r(0.17f)); return r(0.02f) + p.measureText(s); }
    private static float baseline(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    /** 24×24 的图标画在 (x, y-0.15rem) 起的 0.3rem 方块里 */
    private void icon(Canvas c, String d, float x, float cy, float size, int color, boolean fill, float alpha) {
        Path path = SvgPath.of(d);
        Matrix m = new Matrix();
        m.setScale(size / 24, size / 24);
        m.postTranslate(x, cy - size / 2);
        Path q = new Path();
        path.transform(m, q);
        sp.setColor(withA(color, alpha));
        if (fill) { sp.setStyle(Paint.Style.FILL); }
        else { sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1.8f * size / 24); sp.setStrokeCap(Paint.Cap.ROUND); sp.setStrokeJoin(Paint.Join.ROUND); }
        c.drawPath(q, sp);
        sp.setStyle(Paint.Style.FILL);
    }
    private void fan(Canvas c, float x, float cy, float size, int color) {
        Path path = SvgPath.of(CarStat.FAN_D);
        for (int k = 0; k < 4; k++) {
            Matrix m = new Matrix();
            m.setRotate(90 * k, 12, 12);
            m.postScale(size / 24, size / 24);
            m.postTranslate(x, cy - size / 2);
            Path q = new Path();
            path.transform(m, q);
            sp.setColor(color); sp.setStyle(Paint.Style.FILL);
            c.drawPath(q, sp);
        }
        c.drawCircle(x + 12 * size / 24, cy, 1.9f * size / 24, sp);
    }
    private void drawSys(Canvas c, CarStat.Sys s, float x, float cy) {
        float sz = r(0.3f), a = s.dim ? 0.4f : 1;
        switch (s.kind) {
            case "bars": {
                for (int i = 0; i < 4; i++) {
                    float bh = 5 + i * (14 / 3f), bx = 3 + i * 4.6f;
                    sp.setColor(withA(ink, a * (i < s.level ? 1 : 0.25f)));
                    float k = sz / 24, y0 = cy - sz / 2;
                    c.drawRoundRect(x + bx * k, y0 + (20 - bh) * k, x + (bx + 4.6f - 1.6f) * k, y0 + 20 * k, k, k, sp);
                }
                break;
            }
            case "wifi": {
                String[] arcs = { "M12 18.5h.01", "M9.2 15.6a4 4 0 0 1 5.6 0", "M6.4 12.8a8 8 0 0 1 11.2 0", "M3.6 10a12 12 0 0 1 16.8 0" };
                for (int i = 0; i < 4; i++) icon(c, arcs[i], x, cy, sz, ink, false, a * (!s.hollow && i <= s.level ? 1 : 0.25f));
                break;
            }
            default: icon(c, CarStat.ICON.get(s.kind), x, cy, sz, ink, false, a);
        }
        if (s.label != null) { p.setTypeface(ui700); p.setTextSize(r(0.17f)); p.setColor(withA(ink, a)); c.drawText(s.label, x + sz + r(0.02f), baseline(cy, p), p); }
        if (s.warn) { p.setTypeface(ui700); p.setTextSize(r(0.16f)); p.setColor(warn); c.drawText("!", x + sz - r(0.02f), cy - sz / 2 + r(0.12f), p); }
    }

    // ---------------- 车况图标 ----------------
    private float itemW(CarStat.Item it, float fs) {
        if (!it.modes.isEmpty()) {
            p.setTypeface(ui700); p.setTextSize(r(0.19f)); p.setLetterSpacing(0.04f);
            float w = r(0.04f) * 2;
            for (String[] m : it.modes) w += p.measureText(m[0]) + r(0.24f) + r(0.04f);
            p.setLetterSpacing(0);
            return w;
        }
        float w = r(0.1f) + r(0.3f) + (it.text.isEmpty() ? r(0.1f) : r(0.08f) + r(0.16f));
        p.setTypeface(ui500); p.setTextSize(fs);
        w += p.measureText(it.text);
        p.setTextSize(r(0.18f));
        for (String t : it.tags) w += r(0.08f) + p.measureText(t) + r(0.16f);
        return w;
    }
    private int clsColor(String cls, boolean svg) {
        if (cls.contains("bad")) return bad;
        if (cls.contains("warn") || (svg && cls.contains("warm"))) return warn;
        if (cls.contains("ok")) return ok;
        if (cls.contains("blue") || (svg && cls.contains("on"))) return info;
        return ink;
    }
    private float drawItem(Canvas c, CarStat.Item it, float x, float y, float fs) {
        float h = fs * 1.45f + r(0.1f), w = itemW(it, fs), cy = y + h / 2;
        sp.setColor(chip);
        c.drawRoundRect(x, y, x + w, y + h, r(0.3f), r(0.3f), sp);
        if (!it.modes.isEmpty()) {
            float bx = x + r(0.04f);
            p.setTypeface(ui700); p.setTextSize(r(0.19f)); p.setLetterSpacing(0.04f);
            for (String[] m : it.modes) {
                float tw = p.measureText(m[0]), bw = tw + r(0.24f), bh = r(0.19f) * 1.3f + r(0.04f);
                sp.setColor(line);
                c.drawRoundRect(bx + r(0.02f), cy - bh / 2, bx + r(0.02f) + bw, cy + bh / 2, r(0.24f), r(0.24f), sp);
                p.setColor("eco".equals(m[1]) || "ev".equals(m[1]) ? ok : "sport".equals(m[1]) ? bad : "comfort".equals(m[1]) || "hev".equals(m[1]) ? info : ink);
                c.drawText(m[0], bx + r(0.02f) + r(0.12f), baseline(cy, p), p);
                bx += bw + r(0.04f);
            }
            p.setLetterSpacing(0);
            return h;
        }
        float ix = x + r(0.1f), isz = r(0.3f);
        if ("fan".equals(it.icon)) fan(c, ix, cy, isz, clsColor(it.cls, true));
        else if (it.icon != null) icon(c, CarStat.ICON.get(it.icon), ix, cy, isz, clsColor(it.cls, true), false, 1);
        float tx = ix + isz + r(0.08f);
        p.setTypeface(ui500); p.setTextSize(fs); p.setColor(it.cls.contains("on") && !it.cls.contains("blue") ? ink : clsColor(it.cls, false));
        if (!it.text.isEmpty()) c.drawText(it.text, tx, baseline(cy, p), p);
        tx += p.measureText(it.text);
        p.setTextSize(r(0.18f));
        for (String t : it.tags) {
            float tw = p.measureText(t), bh = r(0.18f) * 1.35f;
            sp.setColor(line);
            c.drawRoundRect(tx + r(0.08f), cy - bh / 2, tx + r(0.08f) + tw + r(0.16f), cy + bh / 2, r(0.1f), r(0.1f), sp);
            p.setColor(mute);
            c.drawText(t, tx + r(0.08f) + r(0.08f), baseline(cy, p), p);
            tx += r(0.08f) + tw + r(0.16f);
        }
        return h;
    }
    private void drawTells(Canvas c) {
        if (portrait) { // 一排，放不下的藏掉（告警排前面）
            if (covered) return;
            List<CarStat.Item> all = new ArrayList<>();
            for (CarStat.Item it : tl) if (it.bad()) all.add(it);
            for (CarStat.Item it : tr) if (it.bad()) all.add(it);
            for (CarStat.Item it : tl) if (!it.bad() && it.warn()) all.add(it);
            for (CarStat.Item it : tr) if (!it.bad() && it.warn()) all.add(it);
            for (CarStat.Item it : tl) if (!it.bad() && !it.warn()) all.add(it);
            for (CarStat.Item it : tr) if (!it.bad() && !it.warn()) all.add(it);
            float x = r(0.3f), lim = W - r(0.88f), y = r(1.14f), fs = r(0.19f);
            for (CarStat.Item it : all) {
                float w = itemW(it, fs);
                if (x + w > lim + 1) break;
                drawItem(c, it, x, y, fs);
                x += w + r(0.08f);
            }
            return;
        }
        float top = r(1.3f), bottom = H - r(2.3f), fs = r(0.21f);
        float y = top;
        for (CarStat.Item it : tl) {
            float h = fs * 1.45f + r(0.1f);
            if (y + h > bottom + 1) break;
            drawItem(c, it, r(0.4f), y, fs);
            y += h + r(0.1f);
        }
        y = top;
        for (CarStat.Item it : tr) {
            float h = fs * 1.45f + r(0.1f), w = itemW(it, fs);
            if (y + h > bottom + 1) break;
            drawItem(c, it, W - r(0.4f) - w, y, fs);
            y += h + r(0.1f);
        }
    }

    // ---------------- 下面那行字 ----------------
    private int lvColor(int lv) { return lv == 3 ? bad : lv == 2 ? warn : lv == 1 ? info : ok; }
    private void drawStatus(Canvas c) {
        listR.clear(); pillR.setEmpty(); allR.setEmpty(); pillOn = false;
        float bottom = H - r(portrait ? 2.42f : 1.66f), fs = r(portrait ? 0.24f : 0.26f);
        List<CarPage.St> L = status;
        int topLv = L.isEmpty() ? 0 : L.get(0).lv;
        List<String> shown = new ArrayList<>();
        for (CarPage.St s : L) if (s.lv == topLv && shown.size() < 2) shown.add(s.t);
        int more = L.size() - shown.size();
        String msg = String.join(" · ", shown);
        // "全部车况 ›"
        p.setTypeface(ui500); p.setTextSize(r(0.2f));
        float allW = p.measureText("全部车况 ›") + r(0.32f), allH = r(0.2f) * 1.45f + r(0.1f);
        p.setTextSize(fs); p.setTypeface(ui500);
        float maxText = W * 0.96f - allW - r(0.12f) - r(0.44f) - r(0.24f) - (more > 0 ? r(0.5f) : 0);
        List<String> lines = wrapText(msg, maxText);
        float lh = fs * 1.35f, pillH = Math.max(lh * lines.size(), lh) + r(0.14f);
        float textW = 0;
        for (String s : lines) textW = Math.max(textW, p.measureText(s));
        p.setTextSize(r(0.2f)); p.setTypeface(ui600);
        float badgeW = more > 0 ? p.measureText("+" + more) + r(0.24f) : 0;
        float pillW = r(0.22f) * 2 + r(0.12f) + r(0.12f) + textW + (more > 0 ? r(0.12f) + badgeW : 0);
        float rowW = pillW + r(0.12f) + allW, x0 = (W - rowW) / 2, rowCy = bottom - pillH / 2;
        if (Math.abs(msgTop - (bottom - pillH)) > 0.5f) { msgTop = bottom - pillH; if (onMsgTop != null) post(onMsgTop); }
        boolean clickable = L.size() > 1 || (L.size() == 1 && L.get(0).go());
        if (!msg.isEmpty()) {
            pillR.set(x0, bottom - pillH, x0 + pillW, bottom);
            pillOn = clickable;
            if (clickable) { sp.setColor(chip); c.drawRoundRect(pillR, r(0.3f), r(0.3f), sp); }
            float dx = x0 + r(0.22f) + r(0.06f);
            int col = lvColor(topLv);
            sp.setColor(withA(col, 0.18f)); c.drawCircle(dx, rowCy, r(0.06f) + r(0.05f), sp);
            sp.setColor(col); c.drawCircle(dx, rowCy, r(0.06f), sp);
            p.setTypeface(ui500); p.setTextSize(fs); p.setColor(ink);
            float ty = rowCy - lh * lines.size() / 2 + lh / 2;
            for (String s : lines) { c.drawText(s, dx + r(0.06f) + r(0.12f), baseline(ty, p), p); ty += lh; }
            if (more > 0) {
                float bx = dx + r(0.18f) + textW + r(0.12f), bh = r(0.32f);
                sp.setColor(line); c.drawRoundRect(bx, rowCy - bh / 2, bx + badgeW, rowCy + bh / 2, r(0.16f), r(0.16f), sp);
                p.setTypeface(ui600); p.setTextSize(r(0.2f)); p.setColor(mute);
                c.drawText("+" + more, bx + r(0.12f), baseline(rowCy, p), p);
            }
        }
        allR.set(x0 + pillW + r(0.12f), rowCy - allH / 2, x0 + pillW + r(0.12f) + allW, rowCy + allH / 2);
        sp.setColor(chip); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(Math.max(1, dens)); sp.setColor(line); c.drawRoundRect(allR, r(0.3f), r(0.3f), sp); sp.setStyle(Paint.Style.FILL);
        p.setTypeface(ui500); p.setTextSize(r(0.2f)); p.setColor(mute);
        c.drawText("全部车况 ›", allR.left + r(0.16f), baseline(rowCy, p), p);
        if (stOpen && L.size() > 1) { // 展开的列表：在那行字上面
            float fs2 = r(0.24f), ih = fs2 * 1.35f + r(0.12f), lw = 0;
            p.setTypeface(ui500); p.setTextSize(fs2);
            for (CarPage.St s : L) lw = Math.max(lw, p.measureText(s.t) + (s.go() ? r(0.14f) + p.measureText("›") : 0));
            lw = Math.min(W * 0.92f, lw + r(0.24f) + r(0.24f) + r(0.24f));
            float lh2 = ih * L.size() + r(0.2f), ly = bottom - pillH - r(0.1f) - lh2, lx = (W - lw) / 2;
            sp.setColor(chip); c.drawRoundRect(lx, ly, lx + lw, ly + lh2, r(0.22f), r(0.22f), sp);
            float y = ly + r(0.1f);
            for (CarPage.St s : L) {
                float cy = y + ih / 2;
                sp.setColor(lvColor(s.lv)); c.drawCircle(lx + r(0.24f) + r(0.06f), cy, r(0.06f), sp);
                p.setColor(ink); c.drawText(s.t, lx + r(0.24f) + r(0.24f), baseline(cy, p), p);
                if (s.go()) { p.setColor(mute); c.drawText("›", lx + r(0.48f) + p.measureText(s.t) + r(0.14f), baseline(cy, p), p); }
                listR.add(new RectF(lx, y, lx + lw, y + ih));
                y += ih;
            }
        }
    }
    private List<String> wrapText(String s, float max) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) { out.add(""); return out; }
        int st = 0;
        while (st < s.length()) {
            int n = p.breakText(s, st, s.length(), true, Math.max(1, max), null);
            if (n <= 0) n = 1;
            out.add(s.substring(st, st + n));
            st += n;
        }
        return out;
    }

    // ---------------- 底部数据 ----------------
    private void drawStats(Canvas c) {
        int n = Math.max(1, stats.size());
        float left = r(portrait ? 0.3f : 0.4f), right = W - left, bottom = H - r(portrait ? 0.32f : 0.42f);
        int cols = portrait ? (n == 1 ? 1 : 2) : n, rows = (n + cols - 1) / cols;
        float cw = (right - left) / cols, vfs = r(portrait ? 0.44f : 0.5f);
        float cellH = r(0.2f) * 1.2f + r(0.02f) + vfs * 1.1f + r(0.13f);
        float gap = portrait ? r(0.22f) : 0, top = bottom - rows * cellH - (rows - 1) * gap;
        for (int i = 0; i < stats.size(); i++) {
            String[] s = stats.get(i);
            int col = i % cols, row = i / cols;
            float x = left + col * cw, y = top + row * (cellH + gap);
            boolean firstCol = col == 0;
            if (!firstCol) { sp.setColor(line); c.drawRect(x, y, x + Math.max(1, dens), y + cellH, sp); }
            float tx = x + (firstCol ? 0 : r(0.24f));
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.2f)); p.setColor(mute); p.setLetterSpacing(0.04f);
            c.drawText(s[1], tx, y + r(0.2f), p);
            p.setLetterSpacing(0);
            float vb = y + r(0.2f) * 1.2f + r(0.02f) + vfs * 0.95f;
            if (s[2] == null) { p.setTypeface(num300); p.setTextSize(vfs); p.setColor(ink); c.drawText("—", tx, vb, p); }
            else {
                String[] seg = s[2].split("\u0001");
                float vx = tx;
                for (int k = 0; k < seg.length; k++) {
                    boolean small = k % 2 == 1;
                    p.setTypeface(small ? Typeface.DEFAULT : num300); p.setTextSize(small ? r(0.2f) : vfs); p.setColor(small ? mute : ink);
                    if (small) vx += r(0.04f);
                    c.drawText(seg[k], vx, vb, p);
                    vx += p.measureText(seg[k]) + (small ? r(0.04f) : 0);
                }
                if (s[3] != null && !s[3].isEmpty()) { p.setTypeface(Typeface.DEFAULT); p.setTextSize(r(0.2f)); p.setColor(mute); c.drawText(s[3], vx + r(0.04f), vb, p); }
            }
            if (s[4] != null) { // 电量 / 油量的进度条
                float bw = (cw - (firstCol ? 0 : r(0.24f))) * 0.72f, by = vb + r(0.08f) + vfs * 0.15f, bh = r(0.05f);
                sp.setColor(line); c.drawRoundRect(tx, by, tx + bw, by + bh, r(0.03f), r(0.03f), sp);
                float f = Float.parseFloat(s[4]);
                sp.setColor("low".equals(s[5]) ? bad : "mid".equals(s[5]) ? warn : ok);
                if (f > 0) c.drawRoundRect(tx, by, tx + bw * f, by + bh, r(0.03f), r(0.03f), sp);
            }
        }
    }

    // ================= 手势：只接可点的地方 =================
    private int downOn = -1;
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downOn = -1;
            for (int i = 0; i < listR.size(); i++) if (listR.get(i).contains(x, y)) downOn = 100 + i;
            if (downOn < 0 && pillOn && pillR.contains(x, y)) downOn = 1;
            if (downOn < 0 && allR.contains(x, y)) downOn = 2;
            if (downOn < 0 && !demoR.isEmpty() && demoR.contains(x, y)) downOn = 3;
            if (downOn < 0 && stOpen) { tap.close(); return false; } // 点别处收起列表（同网页版），这一下照常交给 3D
            return downOn >= 0;
        }
        if (downOn < 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int d = downOn;
            downOn = -1;
            if (d == 1) tap.status(); else if (d == 2) tap.all(); else if (d == 3) tap.debug(); else if (d >= 100) tap.pick(d - 100);
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) downOn = -1;
        return true;
    }
}
