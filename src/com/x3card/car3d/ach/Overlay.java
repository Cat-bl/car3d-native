package com.x3card.car3d.ach;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 成就墙上面一层（Canvas，样式同车况页的 Hud：同一套浅色 / 深色配色、系统字 + Outfit 数字、圆键）：
 * 顶上标题；右上角关闭键和统计键（在齿轮、全屏键的位置）；总览卡片里的进度环、各等级几枚、最近拿到的那枚的名字（卡片、那枚勋章在 GL 里）；
 * 点开一枚时旁边的说明；解锁时的字；统计页。长廊里的分类标题、名字在 GL 里（滚动时跟着勋章走）。
 * 手势：键在这里点；长廊里上下拖是换分类、左右拖是翻那一行，点一下是看那一枚（点聚光灯下那枚也行）；
 * 看一枚时拖着转它、点空白处关上；庆祝时点哪里都是继续
 */
final class Overlay extends View {
    interface Host {
        /** 滚动手势：type 见 Wall.V_START 这些；y 是按下的位置（左右滚时认是哪一行），v 是手指挪了多少或者松手时的速度 */
        void scroll(int type, float y, float v);
        void tapGrid(float x, float y);
        void rotate(float dx, float dy);
        void closeFocus();
        void celebNext();
        void openStats();
        void closeStats();
        void closeWall();
    }

    // 浅色 / 深色（同车况页 Hud）：ink、mute、line、chip
    private static final int[] LIGHT = { 0xff14171c, 0x8014171c, 0x1f14171c, 0xb3ffffff };
    private static final int[] DARK = { 0xffeef1f6, 0x80eef1f6, 0x21ffffff, 0xb81c1f25 };

    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), close = new RectF(), statsKey = new RectF(), panel = new RectF();
    private final VelocityTracker vt = VelocityTracker.obtain();
    private final int slop;
    private final Typeface ui500, ui600, ui700, num300, num500;
    private final long born = SystemClock.uptimeMillis();
    private float rem = 10;
    private boolean narrow, portrait;
    private int ink, mute, line, chip;

    // 外面交进来的
    /** 深色的程度（0 浅、1 深，跟着车况页过渡） */
    float darkK;
    /** 强调色（同设置面板：跟着车漆） */
    int acc = 0xffff4b55;
    /** 总览：这台车能拿的拿到了几枚、一共几枚，拿到的隐藏勋章几枚，各等级拿到几枚，最近拿到的那枚和时间 */
    int got, base, hidden;
    int[] tiers = new int[4];
    Ach latest;
    long latestT;
    Ach focus, celeb;
    /** 合成的庆祝：同时解锁的这几枚（等级高的在前）；单独庆祝时是 null */
    List<Ach> celebGroup;
    Stats stats;
    int celebLeft;
    boolean statsShown, focusShown, celebShown;
    /** 看一枚时下面写"拖动可以转着看"（只在第一次看的时候写） */
    boolean dragHint;

    Overlay(Context c, Host host) {
        super(c);
        this.host = host;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        ui500 = Typeface.create(Typeface.DEFAULT, 500, false);
        ui600 = Typeface.create(Typeface.DEFAULT, 600, false);
        ui700 = Typeface.create(Typeface.DEFAULT, 700, false);
        num300 = outfit(c, 300);
        num500 = outfit(c, 500);
    }

    static Typeface outfit(Context c, int w) {
        try { return new Typeface.Builder(c.getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + w).build(); }
        catch (Exception e) { return Typeface.create(Typeface.DEFAULT, w, false); }
    }

    static int mix(int a, int b, float k) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16) | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    private void palette() {
        ink = mix(LIGHT[0], DARK[0], darkK); mute = mix(LIGHT[1], DARK[1], darkK); line = mix(LIGHT[2], DARK[2], darkK); chip = mix(LIGHT[3], DARK[3], darkK);
    }

    /** 强调色：浅色背景上往深里压一点（强调色是按深色卡片调的） */
    private int accent() { return mix(mix(acc, LIGHT[0], 0.3f), acc, darkK); }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align al, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(al);
        c.drawText(s, x, y, p);
    }

    private float tw(String s, float size, Typeface tf) { p.setTypeface(tf); p.setTextSize(size); return p.measureText(s); }

    private static float baseline(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    /** 字的中线放在 cy */
    private void textC(Canvas c, String s, float x, float cy, float size, int color, Paint.Align al, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(size);
        text(c, s, x, baseline(cy, p), size, color, al, tf);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        rem = h / 10.52f;
        narrow = (float) w / h < 0.8f;
        portrait = w < h;
        palette();
        keys(w);
        boolean modal = focusShown || celebShown || statsShown;
        if (!modal) { drawTop(c); drawOverview(c, new Hall(w, h)); drawKey(c, statsKey, false); }
        if (focusShown && focus != null) drawFocus(c, w, h);
        if (celebShown && celeb != null) drawCeleb(c, w, h);
        if (statsShown && stats != null) drawStats(c, w, h);
        if (!celebShown) drawKey(c, close, true);
    }

    // ================= 右上角的键（同车况页齿轮、全屏键的位置和样子） =================
    private void keys(int w) {
        float k = rem * 0.56f, right = rem * (portrait ? 0.22f : 0.26f), top = rem * (portrait ? 0.43f : 0.45f);
        close.set(w - right - k, top, w - right, top + k);
        // 统计键在关闭键左边（竖屏也是：下面是总览那一条）
        if (portrait) statsKey.set(close.left - rem * 0.14f - k, top, close.left - rem * 0.14f, top + k);
        else statsKey.set(w - rem * 0.96f - k, top, w - rem * 0.96f, top + k);
    }

    private int pressedKey; // 0 没有、1 关闭、2 统计

    private void drawKey(Canvas c, RectF b, boolean isClose) {
        int bg = mix(0x1214171c, 0x1affffff, darkK), fg = mix(0xff14171c, 0xffffffff, darkK);
        boolean down = pressedKey == (isClose ? 1 : 2);
        sp.setStyle(Paint.Style.FILL);
        sp.setColor(down ? mix(bg, 0x38ffffff, 0.5f) : bg);
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2, sp);
        sp.setColor(fg);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeCap(Paint.Cap.ROUND);
        sp.setStrokeJoin(Paint.Join.ROUND);
        float s = b.width() / 24 * 0.72f, ox = b.centerX() - 12 * s, oy = b.centerY() - 12 * s;
        sp.setStrokeWidth(2.2f * s);
        if (isClose) {
            c.drawLine(ox + 6.5f * s, oy + 6.5f * s, ox + 17.5f * s, oy + 17.5f * s, sp);
            c.drawLine(ox + 17.5f * s, oy + 6.5f * s, ox + 6.5f * s, oy + 17.5f * s, sp);
        } else {
            Path q = new Path();
            q.moveTo(ox + 5 * s, oy + 20 * s); q.lineTo(ox + 5 * s, oy + 11 * s);
            q.moveTo(ox + 12 * s, oy + 20 * s); q.lineTo(ox + 12 * s, oy + 5 * s);
            q.moveTo(ox + 19 * s, oy + 20 * s); q.lineTo(ox + 19 * s, oy + 14 * s);
            c.drawPath(q, sp);
        }
        sp.setStyle(Paint.Style.FILL);
    }

    // ================= 顶上 =================
    private void drawTop(Canvas c) {
        float x = rem * (portrait ? 0.3f : 0.4f), cy = rem * (portrait ? 0.71f : 0.73f);
        p.setLetterSpacing(0.04f);
        textC(c, "成就墙", x, cy, rem * 0.36f, ink, Paint.Align.LEFT, ui700);
        p.setLetterSpacing(0);
    }

    // ================= 总览 =================
    /** 进度环从 0 转到现在的进度（打开后 0.25 秒开始，0.9 秒转完） */
    private float ringK() {
        float k = (SystemClock.uptimeMillis() - born - 250) / 900f;
        if (k < 1) postInvalidateOnAnimation();
        k = Math.max(0, Math.min(1, k));
        return 1 - (1 - k) * (1 - k) * (1 - k);
    }

    private void drawOverview(Canvas c, Hall g) {
        float k = ringK();
        // 进度环：底下一圈细线，上面强调色的弧（圆头）
        float sw = g.ringR * (g.strip ? 0.15f : 0.11f), rr = g.ringR - sw / 2;
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeWidth(sw);
        sp.setStrokeCap(Paint.Cap.ROUND);
        sp.setColor(line);
        c.drawCircle(g.ringX, g.ringY, rr, sp);
        float frac = base > 0 ? Math.min(1f, got / (float) base) : 0;
        if (frac * k > 0.001f) {
            sp.setColor(accent());
            r.set(g.ringX - rr, g.ringY - rr, g.ringX + rr, g.ringY + rr);
            c.drawArc(r, -90, 360 * frac * k, false, sp);
        }
        sp.setStyle(Paint.Style.FILL);
        String n = Integer.toString(Math.round(got * k));
        if (g.strip) {
            textC(c, n, g.ringX, g.ringY - g.ringR * 0.1f, g.ringR * 0.62f, ink, Paint.Align.CENTER, num300);
            textC(c, "/ " + base, g.ringX, g.ringY + g.ringR * 0.42f, g.ringR * 0.24f, mute, Paint.Align.CENTER, num300);
        } else {
            textC(c, "已解锁", g.ringX, g.ringY - g.ringR * 0.44f, rem * 0.17f, mute, Paint.Align.CENTER, ui500);
            textC(c, n, g.ringX, g.ringY + g.ringR * 0.02f, g.ringR * 0.6f, ink, Paint.Align.CENTER, num300);
            textC(c, "/ " + base, g.ringX, g.ringY + g.ringR * 0.47f, g.ringR * 0.2f, mute, Paint.Align.CENTER, num300);
        }
        // 各等级拿到几枚：等级色的小圆点 + 名字 + 数
        float fs = rem * 0.18f, ns = rem * 0.24f, dot = rem * 0.055f;
        float[] ew = new float[4];
        float maxW = 0, sum = 0;
        for (int t = 0; t < 4; t++) {
            ew[t] = dot * 2 + rem * 0.07f + tw(Ach.TIERS[t], fs, ui500) + rem * 0.07f + tw(Integer.toString(tiers[t]), ns, num500);
            maxW = Math.max(maxW, ew[t]);
            sum += ew[t];
        }
        if (g.strip) {
            float x0 = g.ringX + g.ringR + rem * 0.3f, avail = g.spotX - g.spotR * 1.45f - x0, colW = maxW + rem * 0.24f;
            float sc = Math.min(1, avail / (colW * 2));
            for (int t = 0; t < 4; t++) tier(c, t, x0 + (t % 2) * colW * sc, g.ringY + (t < 2 ? -0.24f : 0.24f) * rem, sc);
        } else {
            float cw = g.ovR - g.ovL - rem * 0.4f, gap = rem * 0.2f, y = g.ringY + g.ringR + rem * 0.52f;
            if (sum + gap * 3 <= cw) {
                float x = (g.ovL + g.ovR) / 2 - (sum + gap * 3) / 2;
                for (int t = 0; t < 4; t++) { tier(c, t, x, y, 1); x += ew[t] + gap; }
                y += rem * 0.5f;
            } else {
                float colW = maxW + gap, x0 = (g.ovL + g.ovR) / 2 - (colW * 2 - gap) / 2;
                for (int t = 0; t < 4; t++) tier(c, t, x0 + (t % 2) * colW, y + (t / 2) * rem * 0.42f, 1);
                y += rem * 0.92f;
            }
            if (hidden > 0) textC(c, "另有 " + hidden + " 枚隐藏勋章", (g.ovL + g.ovR) / 2, y, rem * 0.17f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
            // 最近拿到的那枚：上面一行小字，下面名字和日子（勋章、聚光灯在 GL 里）
            float cx = (g.ovL + g.ovR) / 2, top = g.spotY - g.spotR * 1.34f - rem * 0.3f, bottom = g.spotY + g.spotR * 1.34f + rem * 0.36f;
            p.setLetterSpacing(0.1f);
            textC(c, latest != null ? "最近拿到" : "还没有勋章", cx, top, rem * 0.17f, mute, Paint.Align.CENTER, ui500);
            p.setLetterSpacing(0);
            if (latest != null) {
                p.setTypeface(ui600); p.setTextSize(rem * 0.24f);
                textC(c, ellipsize(latest.name, cw, rem * 0.24f, ui600), cx, bottom, rem * 0.24f, ink, Paint.Align.CENTER, ui600);
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(latestT);
                textC(c, (cal.get(Calendar.MONTH) + 1) + " 月 " + cal.get(Calendar.DAY_OF_MONTH) + " 日 · " + Ach.TIERS[latest.tier], cx, bottom + rem * 0.36f, rem * 0.17f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
            } else textC(c, "照常开车、用车，慢慢就有了", cx, bottom, rem * 0.18f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
        }
    }

    /** 一个等级的数：左边 x、中线 cy、整体缩放 sc */
    private void tier(Canvas c, int t, float x, float cy, float sc) {
        float dot = rem * 0.055f * sc, fs = rem * 0.18f * sc, ns = rem * 0.24f * sc;
        sp.setColor(Palette.TIER_SRGB[t]);
        c.drawCircle(x + dot, cy, dot, sp);
        x += dot * 2 + rem * 0.07f * sc;
        textC(c, Ach.TIERS[t], x, cy, fs, mute, Paint.Align.LEFT, ui500);
        x += tw(Ach.TIERS[t], fs, ui500) + rem * 0.07f * sc;
        textC(c, Integer.toString(tiers[t]), x, cy, ns, ink, Paint.Align.LEFT, num500);
    }

    private String ellipsize(String s, float w, float size, Typeface tf) {
        if (tw(s, size, tf) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) { String t = s.substring(0, n) + "…"; if (tw(t, size, tf) <= w) return t; }
        return s;
    }

    // ================= 点开一枚 =================
    /** 放大的勋章最下沿（连星芒，传奇的星芒伸到 1.34 倍）：位置、大小同 Wall 里看一枚时的算法 */
    private float focusBottom(int w, int h) {
        float fr = narrow ? Math.min(w * 0.3f, h * 0.17f) : Math.min(h * 0.3f, w * 0.2f);
        return (narrow ? h * 0.32f : h * 0.5f) + fr * 1.36f;
    }

    /** "拖动可以转着看"的位置：勋章下面 */
    private float hintY(int w, int h) { return Math.min(h - rem * 0.25f, focusBottom(w, h) + rem * 0.34f); }

    /** 点开一枚：说明放在勋章右边，左对齐；竖屏放在勋章下面，和勋章一样居中 */
    private void drawFocus(Canvas c, int w, int h) {
        Ach a = focus;
        Stats s = stats;
        boolean got = s != null && s.unlocked.containsKey(a.id);
        if (narrow) panel.set(rem * 0.35f, hintY(w, h) + rem * 0.2f, w - rem * 0.35f, h - rem * 0.35f);
        else panel.set(w * 0.52f, h * 0.2f, w - rem * 0.5f, h * 0.8f);
        float x = narrow ? panel.centerX() : panel.left, y = panel.top + rem * 0.2f;
        Paint.Align al = narrow ? Paint.Align.CENTER : Paint.Align.LEFT;
        if (dragHint) text(c, "拖动可以转着看", narrow ? w / 2f : w * 0.3f, hintY(w, h), rem * 0.19f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
        if (!got) { drawHidden(c, a, x, y, al); return; }
        // 等级 · 分类
        pill(c, Ach.TIERS[a.tier] + " · " + Ach.CATS[a.cat], x, y, Palette.TIER_SRGB[a.tier], 0xff14171c);
        y += rem * 0.42f + rem * 0.62f;
        text(c, a.name, x, y, rem * (narrow ? 0.42f : 0.5f), ink, al, ui700);
        y += rem * 0.5f;
        for (String l : narrow ? wrapEven(a.desc, panel.width(), rem * 0.25f) : wrap(a.desc, panel.width(), rem * 0.25f)) { text(c, l, x, y, rem * 0.25f, mute, al, Typeface.DEFAULT); y += rem * 0.36f; }
        y += rem * 0.2f;
        long t = s.unlocked.get(a.id);
        text(c, new SimpleDateFormat("yyyy 年 M 月 d 日 解锁", Locale.CHINA).format(new Date(t)), x, y + rem * 0.1f, rem * 0.24f, accent(), al, ui600);
    }

    /** 小标签：x 是左边（左对齐时）或中间（竖屏居中时） */
    private void pill(Canvas c, String tag, float x, float y, int bg, int fg) {
        float th = rem * 0.42f, tww = tw(tag, rem * 0.2f, ui600) + rem * 0.36f;
        float l = narrow ? x - tww / 2 : x;
        r.set(l, y, l + tww, y + th);
        sp.setColor(bg);
        c.drawRoundRect(r, th / 2, th / 2, sp);
        p.setTypeface(ui600); p.setTextSize(rem * 0.2f);
        text(c, tag, r.centerX(), baseline(r.centerY(), p), rem * 0.2f, fg, Paint.Align.CENTER, ui600);
    }

    /** 没解锁的：名字、条件、等级都不说，只说是哪一类，再给一句不直说的线索 */
    private void drawHidden(Canvas c, Ach a, float x, float y, Paint.Align al) {
        pill(c, "未解锁 · " + Ach.CATS[a.cat], x, y, line, mute);
        y += rem * 0.42f + rem * (narrow ? 0.56f : 0.62f);
        text(c, "？？？", x, y, rem * (narrow ? 0.42f : 0.5f), ink, al, ui700);
        y += rem * (narrow ? 0.5f : 0.58f);
        String hint = Hints.of(a.id);
        if (hint != null) {
            p.setLetterSpacing(0.1f);
            text(c, "线索", x, y, rem * 0.19f, accent(), al, ui600);
            p.setLetterSpacing(0);
            y += rem * 0.42f;
            for (String l : narrow ? wrapEven(hint, panel.width(), rem * 0.26f) : wrap(hint, panel.width(), rem * 0.26f)) {
                text(c, l, x, y, rem * 0.26f, ink, al, Typeface.DEFAULT);
                y += rem * 0.38f;
            }
            y += rem * 0.1f;
        }
        String msg = "条件先保密，照常开车、用车，说不定哪天就碰上了。";
        for (String l : narrow ? wrapEven(msg, panel.width(), rem * 0.2f) : wrap(msg, panel.width(), rem * 0.2f)) {
            text(c, l, x, y, rem * 0.2f, mute, al, Typeface.DEFAULT);
            y += rem * 0.3f;
        }
    }

    // ================= 庆祝 =================
    private void drawCeleb(Canvas c, int w, int h) {
        Ach a = celeb;
        List<Ach> g = celebGroup;
        float cx = w / 2f, y = narrow ? h * 0.62f : h * 0.74f;
        p.setLetterSpacing(0.08f);
        text(c, g != null ? "一下子解锁" : "解锁成就", cx, y, rem * 0.22f, accent(), Paint.Align.CENTER, ui600);
        p.setLetterSpacing(0);
        y += rem * 0.62f;
        text(c, g != null ? "拿到 " + g.size() + " 枚勋章" : a.name, cx, y, rem * 0.52f, ink, Paint.Align.CENTER, ui700);
        y += rem * 0.46f;
        String desc = a.desc;
        if (g != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, g.size()); i++) sb.append(i > 0 ? "、" : "").append(g.get(i).name);
            desc = g.size() > 3 ? sb + " 等，都放进墙里了" : sb + "，都放进墙里了";
        }
        for (String l : wrapEven(desc, w * 0.86f, rem * 0.25f)) { text(c, l, cx, y, rem * 0.25f, mute, Paint.Align.CENTER, Typeface.DEFAULT); y += rem * 0.36f; }
        String more = celebLeft > 0 ? "点一下看下一枚（还有 " + celebLeft + " 枚）" : "点一下继续";
        text(c, more, cx, h - rem * 0.35f, rem * 0.19f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
    }

    // ================= 统计页：一格一个数（同车况页底部数据：上面小字、下面 Outfit 细体数字） =================
    private void drawStats(Canvas c, int w, int h) {
        Stats s = stats;
        sp.setColor(mix(0xf5f0f2f5, 0xf50b0c0f, darkK));
        c.drawRect(0, 0, w, h, sp);
        float x0 = rem * (portrait ? 0.3f : 0.4f), cy = rem * (portrait ? 0.71f : 0.73f);
        p.setLetterSpacing(0.04f);
        p.setTypeface(ui700); p.setTextSize(rem * 0.36f);
        text(c, "统计", x0, baseline(cy, p), rem * 0.36f, ink, Paint.Align.LEFT, ui700);
        p.setLetterSpacing(0);
        List<String[]> t = new ArrayList<>();
        t.add(new String[] { num(s.odo), "公里", "总里程（车）" });
        if (!Double.isNaN(s.evOdo) && s.evOdo > 0) t.add(new String[] { num(s.evOdo), "公里", "纯电里程" });
        t.add(new String[] { Integer.toString(s.trips), "趟", "记录的行程" });
        t.add(new String[] { num(s.totalKm), "公里", "记录的里程" });
        t.add(new String[] { String.format(Locale.US, "%.1f", s.hours), "小时", "开车时间" });
        t.add(new String[] { num(s.nightKm), "公里", "夜里开的" });
        t.add(new String[] { num(s.maxTripKm), "公里", "最长的一趟" });
        t.add(new String[] { num(s.maxDayKm), "公里", "一天最多" });
        t.add(new String[] { s.streak + "", "天", "连续开车（最长 " + s.bestStreak + " 天）" });
        t.add(new String[] { s.dayCount + "", "天", "开车的日子" });
        t.add(new String[] { s.charges + "", "次", "充电（快充 " + s.dcCharges + " 次）" });
        t.add(new String[] { Double.isNaN(s.maxAlt) ? "—" : num(s.maxAlt), "米", "到过的最高海拔" });
        t.add(new String[] { Double.isNaN(s.minTemp) ? "—" : String.format(Locale.US, "%.0f / %.0f", s.minTemp, s.maxTemp), "°C", "开车时最冷 / 最热" });
        t.add(new String[] { s.cells.size() + "", "格", "到过的地方（10 公里一格）" });
        t.add(new String[] { num(s.farKm), "公里", "离第一次记录的地方最远" });
        t.add(new String[] { s.songs.size() + "", "首", "开车时听过的歌" });
        t.add(new String[] { s.beltBest + "", "趟", "连续系好安全带" });
        int cols = narrow ? 2 : w > rem * 14 ? 4 : 3;
        float top = rem * 1.25f, gx = rem * 0.2f, cw = (w - x0 * 2 - gx * (cols - 1)) / cols, chh = rem * 1.12f;
        for (int i = 0; i < t.size(); i++) {
            float cx = x0 + (i % cols) * (cw + gx), cyy = top + (i / cols) * (chh + gx * 0.8f);
            if (cyy + chh > h - rem * 0.5f) break;
            r.set(cx, cyy, cx + cw, cyy + chh);
            sp.setColor(chip);
            c.drawRoundRect(r, rem * 0.2f, rem * 0.2f, sp);
            String[] e = t.get(i);
            p.setLetterSpacing(0.04f);
            text(c, fit(e[2], cw - rem * 0.4f, rem * 0.19f), cx + rem * 0.2f, cyy + rem * 0.36f, rem * 0.19f, mute, Paint.Align.LEFT, Typeface.DEFAULT);
            p.setLetterSpacing(0);
            float ns = rem * 0.44f, vb = cyy + rem * 0.88f;
            text(c, e[0], cx + rem * 0.2f, vb, ns, ink, Paint.Align.LEFT, num300);
            float vw = p.measureText(e[0]);
            text(c, e[1], cx + rem * 0.2f + vw + rem * 0.06f, vb, rem * 0.2f, mute, Paint.Align.LEFT, Typeface.DEFAULT);
        }
        String since = s.firstSeen > 0 ? new SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINA).format(new Date(s.firstSeen)) + "起记录。" : "";
        text(c, since + "行程、充电、海拔这些只在 3D 车况开着时记录", w / 2f, h - rem * 0.25f, rem * 0.18f, mute, Paint.Align.CENTER, Typeface.DEFAULT);
    }

    private String fit(String s, float w, float size) {
        if (tw(s, size, Typeface.DEFAULT) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) { String t = s.substring(0, n) + "…"; if (tw(t, size, Typeface.DEFAULT) <= w) return t; }
        return s;
    }

    private static String num(double v) { return Double.isNaN(v) ? "—" : v >= 100 ? String.format(Locale.US, "%,d", Math.round(v)) : String.format(Locale.US, "%.1f", v); }

    private static final String NO_HEAD = "，。、；：！？）」』”’%";

    /** 按宽度折行；标点不放行首（挤到上一行末尾） */
    private List<String> wrap(String s, float w, float size) {
        List<String> o = new ArrayList<>();
        p.setTextSize(size);
        p.setTypeface(Typeface.DEFAULT);
        int i = 0;
        while (i < s.length()) {
            int n = p.breakText(s, i, s.length(), true, w, null);
            if (n <= 0) break;
            while (i + n < s.length() && NO_HEAD.indexOf(s.charAt(i + n)) >= 0) n++;
            o.add(s.substring(i, i + n));
            i += n;
        }
        return o;
    }

    /** 居中的字折成几行一样长（不在最后一行剩一两个字）：行数不变，把每行的宽度收到刚好够 */
    private List<String> wrapEven(String s, float w, float size) {
        List<String> o = wrap(s, w, size);
        if (o.size() < 2) return o;
        float total = tw(s, size, Typeface.DEFAULT);
        for (float t = total / o.size(); t < w; t += size * 0.5f) {
            List<String> b = wrap(s, t, size);
            if (b.size() == o.size()) return b;
        }
        return o;
    }

    // ================= 手势 =================
    private float dx0, dy0, lx, ly;
    private boolean moved;
    /** 这一下是：0 还没定、1 上下滚、2 左右滚（某一行）、3 不管（总览卡片上横着拖之类） */
    private int axis;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { vt.clear(); dx0 = x; dy0 = y; lx = x; ly = y; moved = false; axis = 0; }
        vt.addMovement(e);
        if (celebShown) { if (a == MotionEvent.ACTION_UP) host.celebNext(); return true; }
        // 右上角的键：按下时变色，抬起时还在键上才算
        boolean modal = focusShown || statsShown;
        if (a == MotionEvent.ACTION_DOWN) {
            int k = close.contains(x, y) ? 1 : !modal && statsKey.contains(x, y) ? 2 : 0;
            if (k != 0) { pressedKey = k; invalidate(); return true; }
        }
        if (pressedKey != 0) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                int k = pressedKey;
                pressedKey = 0;
                invalidate();
                if (a == MotionEvent.ACTION_UP && (k == 1 ? close : statsKey).contains(x, y)) {
                    if (k == 2) host.openStats();
                    else if (statsShown) host.closeStats();
                    else if (focusShown) host.closeFocus();
                    else host.closeWall();
                }
            }
            return true;
        }
        if (statsShown) return true;
        if (focusShown) {
            if (a == MotionEvent.ACTION_MOVE) {
                if (!moved && Math.hypot(x - dx0, y - dy0) > slop) moved = true;
                if (moved) host.rotate(x - lx, y - ly);
            } else if (a == MotionEvent.ACTION_UP && !moved) {
                if (!panel.contains(x, y)) host.closeFocus();
            }
            lx = x; ly = y;
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            if (axis == 0 && Math.hypot(x - dx0, y - dy0) > slop) {
                Hall g = new Hall(getWidth(), getHeight());
                boolean inG = g.inGallery(dx0, dy0);
                if (Math.abs(x - dx0) > Math.abs(y - dy0)) axis = inG ? 2 : 3;
                else axis = inG || g.strip ? 1 : 3;
                if (axis == 1) host.scroll(Wall.V_START, dy0, 0);
                else if (axis == 2) host.scroll(Wall.H_START, dy0, 0);
            }
            if (axis == 1) host.scroll(Wall.V_MOVE, dy0, y - dy0);
            else if (axis == 2) host.scroll(Wall.H_MOVE, dy0, x - dx0);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            vt.computeCurrentVelocity(1000);
            if (axis == 1) host.scroll(Wall.V_END, dy0, a == MotionEvent.ACTION_UP ? vt.getYVelocity() : 0);
            else if (axis == 2) host.scroll(Wall.H_END, dy0, a == MotionEvent.ACTION_UP ? vt.getXVelocity() : 0);
            else if (axis == 0 && a == MotionEvent.ACTION_UP) host.tapGrid(x, y);
            axis = 0;
        }
        return true;
    }
}
