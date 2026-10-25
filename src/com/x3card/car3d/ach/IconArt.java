package com.x3card.car3d.ach;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * 后来加的勋章图案（写法同 Icons：100×100 的格子、圆心 (50, 48)、白 = 凸起的金属、黑 = 凹下去的珐琅，下面 78 以下留给等级记号）。
 * 带参数的写成"名字:参数"：txt:520（一串字）、cal:3（日历上的数）、sign:100（路牌）、plug:10 / fuel:20（充电枪 / 油枪上的数）、
 * thermo:-10（温度计）、cake:3（几根蜡烛）、light:g / gg / r（红绿灯）、plate:R（方牌上一个字母）。
 * 线条至少 2 个单位粗：勋章在墙上只有几十个像素，再细就糊没了
 */
final class IconArt {
    private IconArt() { }

    private static final int W = 0xffffffff, K = 0xff000000;

    /** 画好了返回真；不认识的名字返回假（Icons 画一个圆顶上） */
    static boolean draw(Canvas c, Paint p, String icon) {
        int ci = icon.indexOf(':');
        String n = ci < 0 ? icon : icon.substring(0, ci), arg = ci < 0 ? "" : icon.substring(ci + 1);
        p.setColor(W);
        boolean ok = mile(c, p, n, arg) || trip(c, p, n, arg) || drive(c, p, n, arg) || energy(c, p, n, arg) || explore(c, p, n, arg) || sights(c, p, n) || music(c, p, n) || egg(c, p, n) || redo(c, p, n, arg) || pt(c, p, n, arg);
        p.setColor(W);
        p.setPathEffect(null);
        fill(p);
        return ok;
    }

    // ================= 小工具 =================
    private static Paint fill(Paint p) { p.setStyle(Paint.Style.FILL); return p; }
    private static Paint st(Paint p, float w) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); return p; }
    private static void k(Paint p) { p.setColor(K); }
    private static void w(Paint p) { p.setColor(W); }
    private static RectF r(float l, float t, float rr, float b) { return new RectF(l, t, rr, b); }

    private static Path poly(float... xy) {
        Path a = new Path();
        a.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) a.lineTo(xy[i], xy[i + 1]);
        a.close();
        return a;
    }

    private static void fp(Canvas c, Paint p, float... xy) { c.drawPath(poly(xy), fill(p)); }

    /** 一串字，放不下 maxW 宽时缩小 */
    private static void text(Canvas c, Paint p, String s, float cx, float cy, float size, float maxW) {
        fill(p);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        float tw = p.measureText(s);
        if (tw > maxW) p.setTextSize(size * maxW / tw);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, p);
    }

    private static void star(Canvas c, Paint p, float x, float y, float rr) { fill(p); Icons.star(c, p, x, y, rr); }

    /** 闪电（中心 cx, cy，高约 52s） */
    private static void bolt(Canvas c, Paint p, float cx, float cy, float s) {
        fp(c, p, cx + 6 * s, cy - 28 * s, cx - 16 * s, cy + 4 * s, cx - 2 * s, cy + 4 * s, cx - 8 * s, cy + 30 * s, cx + 16 * s, cy - 4 * s, cx + 2 * s, cy - 4 * s);
    }

    /** 水滴（尖朝上，中心 cx, cy，高约 44s） */
    private static Path drop(float cx, float cy, float s) {
        Path a = new Path();
        a.moveTo(cx, cy - 22 * s);
        a.cubicTo(cx - 10 * s, cy - 8 * s, cx - 14 * s, cy, cx - 14 * s, cy + 7 * s);
        a.cubicTo(cx - 14 * s, cy + 16 * s, cx - 7 * s, cy + 22 * s, cx, cy + 22 * s);
        a.cubicTo(cx + 7 * s, cy + 22 * s, cx + 14 * s, cy + 16 * s, cx + 14 * s, cy + 7 * s);
        a.cubicTo(cx + 14 * s, cy, cx + 10 * s, cy - 8 * s, cx, cy - 22 * s);
        a.close();
        return a;
    }

    private static Path heart(float cx, float cy, float s) {
        Path a = new Path();
        a.moveTo(cx, cy + 18 * s);
        a.cubicTo(cx - 30 * s, cy - 4 * s, cx - 22 * s, cy - 28 * s, cx, cy - 16 * s);
        a.cubicTo(cx + 22 * s, cy - 28 * s, cx + 30 * s, cy - 4 * s, cx, cy + 18 * s);
        a.close();
        return a;
    }

    /** 月牙（圆 r 挖掉右上偏一点的圆） */
    private static Path crescent(float cx, float cy, float rr) {
        Path a = new Path();
        a.addCircle(cx, cy, rr, Path.Direction.CW);
        Path b = new Path();
        b.addCircle(cx + rr * 0.45f, cy - rr * 0.3f, rr * 0.88f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        return a;
    }

    private static Path cloudPath(float cx, float cy, float s) {
        Path a = new Path();
        a.addCircle(cx - 11 * s, cy + 3 * s, 8 * s, Path.Direction.CW);
        a.addCircle(cx, cy - 4 * s, 11 * s, Path.Direction.CW);
        a.addCircle(cx + 12 * s, cy + 3 * s, 8 * s, Path.Direction.CW);
        a.addRoundRect(r(cx - 19 * s, cy + 2 * s, cx + 20 * s, cy + 11 * s), 5 * s, 5 * s, Path.Direction.CW);
        Path u = new Path();
        u.op(a, Path.Op.UNION);
        return u;
    }

    /** 侧面的小轿车（中心大约在 cx, cy，宽约 56s） */
    private static void car(Canvas c, Paint p, float cx, float cy, float s) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        Path a = new Path();
        a.moveTo(-28, 10); a.lineTo(-28, 2); a.quadTo(-26, -2, -20, -3); a.lineTo(-12, -12); a.quadTo(-10, -14, -6, -14);
        a.lineTo(10, -14); a.quadTo(14, -14, 16, -11); a.lineTo(22, -3); a.quadTo(28, -2, 28, 4); a.lineTo(28, 10); a.close();
        c.drawPath(a, fill(p));
        k(p);
        fp(c, p, -10, -4, -6, -10, -1, -10, -1, -4);
        fp(c, p, 2, -10, 10, -10, 15, -4, 2, -4);
        c.drawCircle(-16, 10, 8, p);
        c.drawCircle(16, 10, 8, p);
        w(p);
        c.drawCircle(-16, 10, 5, p);
        c.drawCircle(16, 10, 5, p);
        c.restore();
    }

    /** 波浪线（一行，y、从 x0 到 x1、波长 len） */
    private static void waveLine(Canvas c, Paint p, float y, float x0, float x1, float len, float amp, float width) {
        Path a = new Path();
        a.moveTo(x0, y);
        boolean up = true;
        for (float x = x0; x < x1 - 0.1f; x += len / 2, up = !up) a.quadTo(x + len / 4, y + (up ? -amp : amp), Math.min(x1, x + len / 2), y);
        c.drawPath(a, st(p, width));
    }

    private static void shieldPath(Canvas c, Paint p) {
        Path a = new Path();
        a.moveTo(50, 22); a.lineTo(72, 30); a.lineTo(72, 46);
        a.cubicTo(72, 62, 60, 72, 50, 76); a.cubicTo(40, 72, 28, 62, 28, 46); a.lineTo(28, 30); a.close();
        c.drawPath(a, fill(p));
    }

    private static void calendar(Canvas c, Paint p, String num) {
        c.drawRoundRect(r(28, 30, 72, 72), 5, 5, st(p, 3.5f));
        fill(p);
        c.drawRect(28, 30, 72, 40, p);
        c.drawRect(36, 24, 40, 34, p);
        c.drawRect(60, 24, 64, 34, p);
        if (!num.isEmpty()) text(c, p, num, 50, 57, 22, 34);
    }

    private static void globe(Canvas c, Paint p, float cx, float cy, float rr, float lw) {
        st(p, lw);
        c.drawCircle(cx, cy, rr, p);
        c.drawOval(r(cx - rr * 0.45f, cy - rr, cx + rr * 0.45f, cy + rr), p);
        c.drawLine(cx - rr, cy, cx + rr, cy, p);
    }

    private static void mountain(Canvas c, Paint p, float l, float peakX, float peakY, float rr, float base) {
        fp(c, p, l, base, peakX, peakY, rr, base);
    }

    /** 雪线：在山尖下面挖一圈锯齿 */
    private static void snowCap(Canvas c, Paint p, float x, float y, float s) {
        k(p);
        c.drawPath(poly(x - 7 * s, y + 15 * s, x, y, x + 7 * s, y + 15 * s, x + 3 * s, y + 12 * s, x, y + 17 * s, x - 3 * s, y + 12 * s), st(p, 1.8f));
        w(p);
    }

    private static void pine(Canvas c, Paint p, float cx, float top, float h) {
        float wd = h * 0.55f;
        fp(c, p, cx, top, cx + wd * 0.5f, top + h * 0.38f, cx - wd * 0.5f, top + h * 0.38f);
        fp(c, p, cx, top + h * 0.2f, cx + wd * 0.7f, top + h * 0.66f, cx - wd * 0.7f, top + h * 0.66f);
        fp(c, p, cx, top + h * 0.42f, cx + wd * 0.9f, top + h * 0.92f, cx - wd * 0.9f, top + h * 0.92f);
        c.drawRect(cx - h * 0.06f, top + h * 0.9f, cx + h * 0.06f, top + h, p);
    }

    private static void seat(Canvas c, Paint p) {
        fill(p);
        c.drawRoundRect(r(28, 20, 38, 28), 3, 3, p);
        fp(c, p, 30, 30, 38, 29, 42, 58, 33, 60);
        c.drawRoundRect(r(33, 56, 60, 64), 3, 3, p);
        c.drawRect(44, 64, 48, 72, p);
        c.drawRect(36, 71, 58, 74, p);
    }

    private static void pagoda(Canvas c, Paint p, float cx, float top, float bottom, float wd, int floors) {
        float h = (bottom - top) / (floors + 0.6f);
        c.drawLine(cx, top - h * 0.6f, cx, top, st(p, 2));
        fill(p);
        for (int i = 0; i < floors; i++) {
            float y = top + i * h, ww = wd * (0.55f + 0.45f * i / Math.max(1, floors - 1));
            fp(c, p, cx - ww * 0.7f, y + h * 0.45f, cx + ww * 0.7f, y + h * 0.45f, cx + ww * 0.45f, y, cx - ww * 0.45f, y);
            c.drawRect(cx - ww * 0.32f, y + h * 0.45f, cx + ww * 0.32f, y + h, p);
        }
        c.drawRect(cx - wd * 0.45f, bottom - h * 0.4f, cx + wd * 0.45f, bottom, p);
    }

    /** 圆弧箭头：圆心 (cx, cy)、半径 rr，从 a0 度转 sweep 度，箭头在末端 */
    private static void arcArrow(Canvas c, Paint p, float cx, float cy, float rr, float a0, float sweep, float lw) {
        c.drawArc(r(cx - rr, cy - rr, cx + rr, cy + rr), a0, sweep, false, st(p, lw));
        double e = Math.toRadians(a0 + sweep), dir = e + Math.PI / 2 * Math.signum(sweep);
        float ex = cx + (float) Math.cos(e) * rr, ey = cy + (float) Math.sin(e) * rr;
        float hx = (float) Math.cos(dir) * lw * 2.2f, hy = (float) Math.sin(dir) * lw * 2.2f, nx = (float) Math.cos(e) * lw * 1.6f, ny = (float) Math.sin(e) * lw * 1.6f;
        fp(c, p, ex + hx, ey + hy, ex - nx, ey - ny, ex + nx, ey + ny);
    }

    // ================= 里程 =================
    private static boolean mile(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "txt": text(c, p, arg, 50, 48, 30, 56); return true;
            case "key": // 车钥匙
                c.drawRoundRect(r(26, 28, 54, 54), 9, 9, fill(p));
                k(p); c.drawCircle(38, 41, 5, p); w(p);
                c.drawRect(52, 37, 78, 45, p);
                c.drawRect(64, 45, 68, 52, p);
                c.drawRect(71, 45, 75, 50, p);
                return true;
            case "milestone": { // 路边的里程碑
                Path a = new Path();
                a.addRoundRect(r(34, 24, 66, 72), new float[] { 16, 16, 16, 16, 0, 0, 0, 0 }, Path.Direction.CW);
                c.drawPath(a, fill(p));
                c.drawRect(26, 72, 74, 76, p);
                k(p);
                text(c, p, "K", 50, 40, 16, 20);
                c.drawRect(40, 52, 60, 55, p);
                c.drawRect(43, 59, 57, 62, p);
                w(p);
                return true;
            }
            case "cloudmoon": // 云和月
                c.drawPath(crescent(58, 34, 13), fill(p));
                c.drawPath(cloudPath(42, 58, 1.1f), p);
                return true;
            case "march": { // 红星 + 飘带
                star(c, p, 50, 36, 17);
                fp(c, p, 22, 55, 78, 55, 73, 63, 78, 71, 22, 71, 27, 63);
                k(p); fill(p); c.drawRect(32, 59.5f, 68, 61.5f, p); c.drawRect(32, 64.5f, 68, 66.5f, p); w(p);
                return true;
            }
            case "wave": // 一排排浪花
                st(p, 4);
                for (int row = 0; row < 3; row++) {
                    float y = 36 + row * 12, x0 = 22 + (row % 2) * 6;
                    for (float x = x0; x < 72; x += 12) c.drawArc(r(x, y - 6, x + 12, y + 6), 180, 180, false, p);
                }
                return true;
            case "wall": // 长城：墙 + 城楼
                fill(p);
                c.drawRect(22, 52, 78, 70, p);
                for (int i = 0; i < 6; i++) c.drawRect(22 + i * 10.4f, 46, 27 + i * 10.4f, 52, p);
                c.drawRect(38, 32, 62, 52, p);
                for (int i = 0; i < 4; i++) c.drawRect(38 + i * 6.7f, 27, 42 + i * 6.7f, 32, p);
                k(p);
                c.drawOval(r(45, 40, 55, 50), p);
                c.drawRect(45, 45, 55, 52, p);
                c.drawRect(22, 60, 78, 62, p);
                w(p);
                return true;
            case "rocket": {
                c.save();
                c.rotate(30, 50, 48);
                Path a = new Path();
                a.moveTo(50, 18); a.cubicTo(62, 28, 62, 50, 58, 62); a.lineTo(42, 62); a.cubicTo(38, 50, 38, 28, 50, 18); a.close();
                c.drawPath(a, fill(p));
                fp(c, p, 42, 50, 32, 66, 42, 62);
                fp(c, p, 58, 50, 68, 66, 58, 62);
                fp(c, p, 44, 64, 50, 78, 56, 64);
                k(p); c.drawCircle(50, 36, 5, p); w(p);
                c.restore();
                return true;
            }
            default: return false;
        }
    }

    // ================= 旅途 =================
    private static boolean trip(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "car": car(c, p, 50, 48, 1); return true;
            case "cup": { // 奖杯
                Path a = new Path();
                a.moveTo(34, 24); a.lineTo(66, 24); a.lineTo(64, 40); a.quadTo(60, 54, 50, 56); a.quadTo(40, 54, 36, 40); a.close();
                c.drawPath(a, fill(p));
                st(p, 4);
                c.drawArc(r(25, 27, 41, 45), 90, 180, false, p);
                c.drawArc(r(59, 27, 75, 45), 270, 180, false, p);
                fill(p);
                c.drawRect(47, 56, 53, 64, p);
                c.drawRoundRect(r(38, 64, 62, 72), 2, 2, p);
                k(p); star(c, p, 50, 37, 6.5f); w(p);
                return true;
            }
            case "sign": // 路牌上一个数
                fill(p);
                c.drawRect(47, 56, 53, 78, p);
                c.drawRoundRect(r(24, 24, 76, 58), 6, 6, p);
                k(p);
                c.drawRoundRect(r(28, 28, 72, 54), 4, 4, st(p, 2));
                text(c, p, arg, 50, 41, 18, 36);
                w(p);
                return true;
            case "sunrise": // 地平线上升起的太阳
                fill(p);
                c.drawRect(22, 62, 78, 66, p);
                c.drawArc(r(36, 46, 64, 74), 180, 180, true, p);
                st(p, 3.5f);
                for (int i = 0; i <= 6; i++) {
                    double t = Math.toRadians(180 + i * 30);
                    c.drawLine(50 + (float) Math.cos(t) * 19, 60 + (float) Math.sin(t) * 19, 50 + (float) Math.cos(t) * 26, 60 + (float) Math.sin(t) * 26, p);
                }
                return true;
            case "nightroad": // 夜里的路
                fp(c, p, 30, 78, 46, 46, 54, 46, 70, 78);
                k(p);
                for (int i = 0; i < 3; i++) { float y0 = 50 + i * 9, w0 = 0.8f + i * 0.5f; c.drawRect(50 - w0, y0, 50 + w0, y0 + 5 + i, p); }
                w(p);
                c.drawPath(crescent(64, 30, 10), fill(p));
                star(c, p, 34, 30, 4.5f);
                star(c, p, 44, 22, 3);
                return true;
            case "cal": calendar(c, p, arg); return true;
            case "hourglass": {
                fill(p);
                c.drawRect(32, 22, 68, 27, p);
                c.drawRect(32, 69, 68, 74, p);
                st(p, 3.5f);
                Path a = new Path();
                a.moveTo(36, 27); a.lineTo(50, 48); a.lineTo(36, 69); a.moveTo(64, 27); a.lineTo(50, 48); a.lineTo(64, 69);
                c.drawPath(a, p);
                fp(c, p, 41, 33, 59, 33, 50, 45);
                fp(c, p, 42, 69, 58, 69, 50, 59);
                return true;
            }
            case "clock2": // 秒表
                c.drawCircle(50, 52, 22, st(p, 4));
                fill(p);
                c.drawRect(46, 22, 54, 28, p);
                c.drawRect(48, 27, 52, 31, p);
                st(p, 3.5f);
                c.drawLine(66, 34, 70, 30, p);
                c.drawLine(50, 52, 50, 38, p);
                c.drawLine(50, 52, 59, 58, p);
                for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6; c.drawPoint(50 + (float) Math.cos(t) * 16, 52 + (float) Math.sin(t) * 16, p); }
                return true;
            case "shield": // 盾牌 + 十字
                shieldPath(c, p);
                k(p); c.drawRect(47, 34, 53, 62, p); c.drawRect(37, 44, 63, 50, p); w(p);
                return true;
            case "coffee": { // 一杯热咖啡
                Path a = new Path();
                a.addRoundRect(r(30, 40, 62, 66), new float[] { 0, 0, 0, 0, 10, 10, 10, 10 }, Path.Direction.CW);
                c.drawPath(a, fill(p));
                c.drawArc(r(56, 44, 72, 60), 270, 180, false, st(p, 4));
                c.drawRoundRect(r(24, 66, 70, 71), 2, 2, fill(p));
                st(p, 3);
                for (int i = 0; i < 3; i++) { Path s = new Path(); float x = 38 + i * 8; s.moveTo(x, 36); s.quadTo(x - 4, 31, x, 26); s.quadTo(x + 4, 22, x, 18); c.drawPath(s, p); }
                return true;
            }
            case "bench": // 长椅
                fill(p);
                c.drawRect(24, 38, 76, 42, p);
                c.drawRect(24, 45, 76, 49, p);
                c.drawRect(22, 53, 78, 58, p);
                c.drawRect(28, 42, 32, 72, p);
                c.drawRect(68, 42, 72, 72, p);
                c.drawRect(24, 62, 76, 64, p);
                return true;
            case "tent": // 帐篷
                fp(c, p, 22, 72, 50, 28, 78, 72);
                c.drawRect(18, 72, 82, 75, p);
                k(p); fp(c, p, 50, 42, 42, 72, 58, 72); w(p);
                c.drawLine(50, 28, 50, 18, st(p, 2.5f));
                fp(c, p, 50, 18, 60, 21, 50, 24);
                return true;
            case "briefcase": // 公文包
                c.drawRoundRect(r(26, 36, 74, 70), 5, 5, fill(p));
                c.drawRoundRect(r(40, 26, 60, 40), 4, 4, st(p, 4));
                k(p); fill(p); c.drawRect(26, 49, 74, 52, p); w(p);
                c.drawRoundRect(r(45, 46, 55, 56), 2, 2, p);
                return true;
            case "house":
                fp(c, p, 22, 50, 50, 24, 78, 50);
                c.drawRect(32, 48, 68, 72, p);
                c.drawRect(60, 28, 66, 42, p);
                k(p); c.drawRect(45, 56, 55, 72, p); c.drawRect(35, 54, 42, 61, p); w(p);
                return true;
            case "gift": // 礼物盒
                fill(p);
                c.drawRect(28, 44, 72, 72, p);
                c.drawRect(25, 36, 75, 45, p);
                k(p); c.drawRect(47, 36, 53, 72, p); w(p);
                st(p, 3.5f);
                c.drawOval(r(36, 24, 50, 36), p);
                c.drawOval(r(50, 24, 64, 36), p);
                return true;
            case "cake": { // 蛋糕和蜡烛
                int nc = "3".equals(arg) ? 3 : 1;
                fill(p);
                c.drawRoundRect(r(26, 56, 74, 72), 3, 3, p);
                c.drawRoundRect(r(32, 44, 68, 57), 3, 3, p);
                k(p); waveLine(c, p, 56.5f, 26, 74, 8, 2, 2); w(p); fill(p);
                for (int i = 0; i < nc; i++) {
                    float x = nc == 1 ? 50 : 40 + i * 10;
                    c.drawRect(x - 2, 32, x + 2, 44, p);
                    c.drawPath(drop(x, 26, 0.22f), p);
                }
                return true;
            }
            default: return false;
        }
    }

    // ================= 驾驶 =================
    private static boolean drive(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "silk": { // 飘带
                Path a = new Path();
                a.moveTo(22, 40); a.cubicTo(40, 22, 56, 58, 78, 38); a.lineTo(78, 50); a.cubicTo(56, 70, 40, 34, 22, 52); a.close();
                c.drawPath(a, fill(p));
                k(p);
                Path s = new Path();
                s.moveTo(26, 46); s.cubicTo(42, 30, 56, 64, 74, 44);
                c.drawPath(s, st(p, 1.8f));
                w(p);
                return true;
            }
            case "glass": // 一杯水，水面平平的
                fp(c, p, 33, 24, 67, 24, 62, 74, 38, 74);
                k(p);
                fp(c, p, 37, 27.5f, 63, 27.5f, 61.5f, 44, 38.5f, 44);
                w(p);
                return true;
            case "level": // 水平仪
                c.drawRoundRect(r(20, 40, 80, 58), 6, 6, fill(p));
                k(p); c.drawRoundRect(r(37, 43, 63, 55), 6, 6, p); w(p);
                c.drawCircle(50, 49, 4.2f, p);
                k(p); fill(p); c.drawRect(25, 47, 31, 51, p); c.drawRect(69, 47, 75, 51, p); w(p);
                return true;
            case "light": { // 红绿灯
                if ("gg".equals(arg)) { light(c, p, 36, 2); light(c, p, 64, 2); }
                else light(c, p, 50, "r".equals(arg) ? 0 : 2);
                return true;
            }
            case "snail": { // 蜗牛
                fill(p);
                c.drawRoundRect(r(24, 60, 76, 68), 4, 4, p);
                c.drawCircle(54, 48, 16, p);
                k(p);
                st(p, 2.4f);
                c.drawArc(r(44, 38, 64, 58), 30, 280, false, p);
                c.drawArc(r(49, 43, 59, 53), 210, 250, false, p);
                w(p);
                fill(p);
                c.drawCircle(28, 58, 6, p);
                st(p, 2.5f);
                c.drawLine(26, 54, 22, 42, p); c.drawLine(31, 53, 32, 41, p);
                fill(p);
                c.drawCircle(22, 41, 2.5f, p); c.drawCircle(32, 40, 2.5f, p);
                return true;
            }
            case "lotus": lotus(c, p, 50, 60, 1); waveLine(c, p, 70, 26, 74, 12, 2.5f, 3); return true;
            case "highway": // 高速路 + 限速牌
                fp(c, p, 24, 78, 46, 46, 54, 46, 76, 78);
                k(p);
                for (int i = 0; i < 3; i++) { float y0 = 50 + i * 9, w0 = 0.7f + i * 0.45f; c.drawRect(50 - w0, y0, 50 + w0, y0 + 5 + i, p); }
                w(p);
                c.drawCircle(50, 30, 11, st(p, 3));
                text(c, p, "120", 50, 30, 8.5f, 14);
                return true;
            case "speedo": // 定速巡航：表盘 + 绕一圈的箭头
                arcArrow(c, p, 50, 50, 23, 150, 240, 4);
                st(p, 4);
                c.drawLine(50, 50, 62, 38, p);
                c.drawCircle(50, 50, 4, fill(p));
                return true;
            case "ribbon": // 挂着绶带的奖章
                fp(c, p, 36, 20, 46, 20, 54, 46, 44, 48);
                fp(c, p, 64, 20, 54, 20, 46, 46, 56, 48);
                c.drawCircle(50, 58, 15, fill(p));
                k(p); c.drawCircle(50, 58, 11, st(p, 2)); star(c, p, 50, 58, 7); w(p);
                return true;
            case "plate": // 方牌上一个字母
                c.drawRoundRect(r(30, 28, 70, 68), 8, 8, fill(p));
                k(p); text(c, p, arg, 50, 48, 30, 30); w(p);
                return true;
            case "turn": fp(c, p, 24, 48, 44, 28, 44, 39, 70, 39, 70, 57, 44, 57, 44, 68); return true;
            case "turn2":
                fp(c, p, 20, 48, 36, 32, 36, 41, 46, 41, 46, 55, 36, 55, 36, 64);
                fp(c, p, 80, 48, 64, 32, 64, 41, 54, 41, 54, 55, 64, 55, 64, 64);
                return true;
            case "blinker":
                fp(c, p, 34, 48, 52, 30, 52, 40, 74, 40, 74, 56, 52, 56, 52, 66);
                st(p, 3);
                c.drawLine(28, 48, 20, 48, p); c.drawLine(29, 40, 23, 34, p); c.drawLine(29, 56, 23, 62, p);
                return true;
            case "tri3": { // 三种驾驶模式
                String[] t = { "E", "S", "C" };
                float[][] at = { { 50, 30 }, { 35, 58 }, { 65, 58 } };
                for (int i = 0; i < 3; i++) {
                    w(p); c.drawCircle(at[i][0], at[i][1], 11, fill(p));
                    k(p); text(c, p, t[i], at[i][0], at[i][1], 13, 14);
                }
                w(p);
                return true;
            }
            case "recycle": // 能量回收：三段转着的箭头
                for (int i = 0; i < 3; i++) arcArrow(c, p, 50, 48, 21, i * 120 + 20, 85, 4.5f);
                return true;
            case "hazard": // 双闪的三角
                fp(c, p, 50, 22, 77, 70, 23, 70);
                k(p); fp(c, p, 50, 34, 66.5f, 63, 33.5f, 63); w(p);
                fp(c, p, 50, 43, 58.5f, 58, 41.5f, 58);
                return true;
            case "fog": { // 前雾灯
                Path a = new Path();
                a.moveTo(58, 30); a.cubicTo(80, 30, 80, 66, 58, 66); a.close();
                c.drawPath(a, fill(p));
                st(p, 3.5f);
                c.drawLine(52, 36, 28, 36, p); c.drawLine(52, 48, 28, 48, p); c.drawLine(52, 60, 28, 60, p);
                Path s = new Path();
                s.moveTo(40, 26); s.quadTo(34, 32, 40, 38); s.quadTo(46, 44, 40, 50); s.quadTo(34, 56, 40, 62); s.quadTo(46, 68, 40, 72);
                c.drawPath(s, st(p, 2.5f));
                return true;
            }
            case "slope2":
                fp(c, p, 22, 72, 78, 72, 78, 48);
                k(p); text(c, p, "%", 66, 64, 11, 12); w(p);
                arcArrow(c, p, 50, 70, 26, 200, 40, 3);
                return true;
            case "serpent": { // 盘山公路
                Path a = new Path();
                a.moveTo(16, 76); a.quadTo(50, 8, 84, 76);
                c.drawPath(a, st(p, 3));
                Path road = new Path();
                road.moveTo(30, 74); road.lineTo(64, 66); road.lineTo(38, 56); road.lineTo(60, 46); road.lineTo(46, 38); road.lineTo(52, 32);
                c.drawPath(road, st(p, 5));
                return true;
            }
            case "downhill":
                mountain(c, p, 22, 40, 30, 80, 74);
                k(p);
                st(p, 3.5f);
                c.drawLine(42, 38, 62, 62, p);
                fp(c, p, 66, 67, 56, 63, 63, 56);
                w(p);
                return true;
            case "everest":
                mountain(c, p, 20, 50, 26, 80, 74);
                snowCap(c, p, 50, 26, 1.1f);
                c.drawLine(50, 27, 50, 12, st(p, 2.5f));
                fp(c, p, 50, 12, 62, 15.5f, 50, 19);
                return true;
            case "everest2":
                mountain(c, p, 18, 38, 36, 58, 74);
                mountain(c, p, 42, 62, 28, 82, 74);
                snowCap(c, p, 62, 28, 0.9f);
                st(p, 2.5f);
                c.drawLine(38, 37, 38, 24, p); c.drawLine(62, 29, 62, 14, p);
                fp(c, p, 38, 24, 47, 27, 38, 30);
                fp(c, p, 62, 14, 72, 17, 62, 20);
                return true;
            default: return false;
        }
    }

    /** 红绿灯：lit 0 红（上）、2 绿（下） */
    private static void light(Canvas c, Paint p, float cx, int lit) {
        float hw = 11, top = 22, bot = 74;
        w(p);
        c.drawRoundRect(r(cx - hw, top, cx + hw, bot), 8, 8, fill(p));
        for (int i = 0; i < 3; i++) {
            float y = top + 10 + i * 16;
            k(p); c.drawCircle(cx, y, 6.5f, fill(p));
            if (i == lit) {
                w(p);
                c.drawCircle(cx, y, 5.2f, p);
                st(p, 2.4f);
                c.drawLine(cx - hw - 3, y, cx - hw - 8, y, p); c.drawLine(cx + hw + 3, y, cx + hw + 8, y, p);
                c.drawLine(cx - hw - 2, y - 7, cx - hw - 6, y - 10, p); c.drawLine(cx + hw + 2, y - 7, cx + hw + 6, y - 10, p);
                fill(p);
            }
        }
        w(p);
    }

    /** 莲花：一层层花瓣（每瓣描一道细缝） */
    private static void lotus(Canvas c, Paint p, float cx, float by, float s) {
        float[] ang = { -70, 70, -38, 38, 0 };
        for (float a : ang) {
            c.save();
            c.rotate(a, cx, by);
            RectF o = r(cx - 7 * s, by - 32 * s, cx + 7 * s, by);
            w(p); c.drawOval(o, fill(p));
            k(p); c.drawOval(o, st(p, 1.6f));
            c.restore();
        }
        w(p);
        fill(p);
    }

    // ================= 能源 =================
    private static boolean energy(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "evcar": car(c, p, 50, 58, 0.9f); bolt(c, p, 50, 30, 0.42f); return true;
            case "plug": { // 充电插头上写数（plug:10）
                fill(p);
                c.drawRoundRect(r(33, 34, 67, 60), 4, 4, p);
                c.drawRect(40, 22, 44, 35, p);
                c.drawRect(56, 22, 60, 35, p);
                c.drawRect(47, 59, 53, 67, p);
                Path a = new Path();
                a.moveTo(50, 67); a.quadTo(50, 77, 40, 77);
                c.drawPath(a, st(p, 4));
                k(p); text(c, p, arg, 50, 47, 13, 28); w(p);
                return true;
            }
            case "boltc": // 满电的电池，挖一个闪电
                c.drawRoundRect(r(24, 34, 70, 62), 4, 4, fill(p));
                c.drawRect(70, 42, 76, 54, p);
                k(p); bolt(c, p, 47, 48, 0.42f); w(p);
                return true;
            case "earthleaf": {
                globe(c, p, 46, 50, 20, 3.5f);
                c.save();
                c.translate(63, 62);
                c.rotate(-30);
                Path a = new Path();
                a.moveTo(0, 12); a.cubicTo(-12, 2, -10, -10, 0, -14); a.cubicTo(10, -10, 12, 2, 0, 12); a.close();
                w(p); c.drawPath(a, fill(p));
                k(p); c.drawPath(a, st(p, 2)); c.drawLine(0, 10, 0, -10, p);
                c.restore();
                w(p);
                return true;
            }
            case "dual": case "dual2":
                c.drawPath(drop(37, 50, 0.95f), fill(p));
                bolt(c, p, 63, 48, 0.75f);
                if (n.equals("dual2")) c.drawCircle(50, 48, 31, st(p, 3));
                return true;
            case "half":
                c.drawCircle(50, 48, 24, st(p, 4));
                c.drawArc(r(26, 24, 74, 72), 90, 180, true, fill(p));
                text(c, p, "50", 61, 48, 12, 16);
                return true;
            case "pylon": // 高压电塔
                st(p, 3);
                c.drawLine(36, 76, 46, 26, p); c.drawLine(64, 76, 54, 26, p);
                c.drawLine(28, 34, 72, 34, p); c.drawLine(32, 46, 68, 46, p);
                c.drawLine(40, 58, 61, 70, p); c.drawLine(60, 58, 39, 70, p);
                c.drawLine(43, 40, 58, 52, p); c.drawLine(57, 40, 42, 52, p);
                fp(c, p, 45, 27, 50, 18, 55, 27);
                return true;
            case "boltfast":
                bolt(c, p, 40, 48, 0.8f);
                bolt(c, p, 62, 48, 0.8f);
                return true;
            case "bolt3":
                bolt(c, p, 30, 50, 0.6f);
                bolt(c, p, 50, 44, 0.75f);
                bolt(c, p, 70, 50, 0.6f);
                return true;
            case "drop": // 细水长流
                c.drawPath(drop(50, 46, 1.25f), fill(p));
                k(p); c.drawArc(r(36, 42, 56, 66), 110, 70, false, st(p, 2.5f)); w(p);
                return true;
            case "moonplug":
                c.drawPath(crescent(36, 32, 12), fill(p));
                c.drawRoundRect(r(44, 44, 70, 62), 4, 4, p);
                c.drawRect(49, 34, 53, 45, p); c.drawRect(61, 34, 65, 45, p);
                c.drawRect(54, 61, 60, 68, p);
                return true;
            case "batup":
                c.drawRoundRect(r(24, 34, 70, 62), 4, 4, fill(p));
                c.drawRect(70, 42, 76, 54, p);
                k(p); fp(c, p, 47, 37, 58, 48, 51, 48, 51, 58, 43, 58, 43, 48, 36, 48); w(p);
                return true;
            case "battery2":
                for (int i = 0; i < 2; i++) {
                    float x = 30 + i * 22;
                    c.drawRoundRect(r(x, 30, x + 18, 72), 3, 3, st(p, 3));
                    fill(p);
                    c.drawRect(x + 5, 25, x + 13, 30, p);
                    for (int j = 0; j < 4; j++) c.drawRect(x + 4, 35 + j * 9, x + 14, 41 + j * 9, p);
                }
                return true;
            case "infinity": {
                Path a = new Path();
                a.moveTo(50, 48); a.cubicTo(40, 32, 22, 36, 22, 48); a.cubicTo(22, 60, 40, 64, 50, 48);
                a.cubicTo(60, 32, 78, 36, 78, 48); a.cubicTo(78, 60, 60, 64, 50, 48);
                c.drawPath(a, st(p, 6));
                return true;
            }
            case "fuel": { // 加油机（fuel:20 在窗口里写数）
                c.drawRoundRect(r(28, 26, 56, 74), 3, 3, fill(p));
                c.drawRect(24, 72, 60, 76, p);
                k(p); c.drawRect(33, 31, 51, 44, p);
                if (!arg.isEmpty()) { w(p); text(c, p, arg, 42, 37.5f, 10, 16); }
                w(p);
                Path h = new Path();
                h.moveTo(56, 40); h.lineTo(66, 40); h.lineTo(68, 64); h.quadTo(70, 70, 64, 70);
                c.drawPath(h, st(p, 3.5f));
                c.drawRect(62, 32, 70, 44, fill(p));
                return true;
            }
            case "socket": // 墙上的插座
                c.drawRoundRect(r(28, 26, 72, 70), 8, 8, fill(p));
                k(p);
                c.save(); c.rotate(-30, 42, 42); c.drawRect(40, 36, 44, 48, p); c.restore();
                c.save(); c.rotate(30, 58, 42); c.drawRect(56, 36, 60, 48, p); c.restore();
                c.drawRect(48, 54, 52, 64, p);
                w(p);
                return true;
            case "phone":
                c.drawRoundRect(r(36, 22, 64, 74), 6, 6, fill(p));
                k(p); c.drawRoundRect(r(39, 28, 61, 64), 2, 2, p); w(p);
                bolt(c, p, 50, 46, 0.4f);
                c.drawRect(46, 67, 54, 69, p);
                return true;
            case "shieldbolt":
                shieldPath(c, p);
                k(p); bolt(c, p, 50, 48, 0.62f); w(p);
                return true;
            default: return false;
        }
    }

    // ================= 探索 =================
    private static boolean explore(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "basin": { // 盆地，往下的箭头
                Path a = new Path();
                a.moveTo(20, 34); a.lineTo(36, 60); a.quadTo(50, 70, 64, 60); a.lineTo(80, 34); a.lineTo(80, 74); a.lineTo(20, 74); a.close();
                c.drawPath(a, fill(p));
                c.drawRect(47.5f, 24, 52.5f, 42, p);
                fp(c, p, 42, 40, 58, 40, 50, 52);
                return true;
            }
            case "thermo": { // 温度计 + 度数
                c.drawRoundRect(r(28, 22, 40, 62), 6, 6, fill(p));
                c.drawCircle(34, 66, 9, p);
                k(p); c.drawRoundRect(r(31.5f, 26, 36.5f, 60), 2.5f, 2.5f, p); w(p);
                boolean cold = arg.startsWith("-");
                c.drawRect(32, cold ? 52 : 34, 36, 62, p);
                c.drawCircle(34, 66, 5, p);
                text(c, p, (cold ? "−" + arg.substring(1) : arg) + "°", 60, 46, 17, 32);
                return true;
            }
            case "flame": {
                Path a = new Path();
                a.moveTo(50, 20); a.cubicTo(66, 36, 72, 50, 68, 62); a.cubicTo(64, 72, 57, 77, 50, 77); a.cubicTo(41, 77, 32, 70, 32, 60);
                a.cubicTo(32, 50, 38, 44, 42, 36); a.cubicTo(44, 44, 47, 47, 50, 48); a.cubicTo(52, 40, 52, 30, 50, 20); a.close();
                c.drawPath(a, fill(p));
                k(p);
                Path b = new Path();
                b.moveTo(50, 50); b.cubicTo(58, 57, 60, 63, 58, 67); b.cubicTo(56, 71, 53, 73, 50, 73); b.cubicTo(46, 73, 42, 70, 42, 65); b.cubicTo(42, 60, 46, 56, 50, 50); b.close();
                c.drawPath(b, fill(p));
                w(p);
                return true;
            }
            case "yinyang": {
                Path wh = new Path(), rt = new Path(), half = new Path();
                half.addCircle(50, 48, 25, Path.Direction.CW);
                rt.addRect(50, 23, 75, 73, Path.Direction.CW);
                half.op(rt, Path.Op.INTERSECT);
                wh.addCircle(50, 35.5f, 12.5f, Path.Direction.CW);
                wh.op(half, Path.Op.UNION);
                Path cut = new Path();
                cut.addCircle(50, 60.5f, 12.5f, Path.Direction.CW);
                wh.op(cut, Path.Op.DIFFERENCE);
                c.drawPath(wh, fill(p));
                c.drawCircle(50, 48, 25, st(p, 3));
                fill(p);
                c.drawCircle(50, 60.5f, 4, p);
                k(p); c.drawCircle(50, 35.5f, 4, p); w(p);
                return true;
            }
            case "foot": // 两只脚印
                c.save(); c.scale(1.35f, 1.35f, 50, 48);
                foot(c, p, 41, 58, -12);
                foot(c, p, 59, 39, 10);
                c.restore();
                return true;
            case "map": {
                fp(c, p, 24, 30, 40, 26, 40, 70, 24, 74);
                fp(c, p, 42, 26, 58, 30, 58, 74, 42, 70);
                fp(c, p, 60, 30, 76, 26, 76, 70, 60, 74);
                k(p);
                p.setPathEffect(new DashPathEffect(new float[] { 4, 3 }, 0));
                Path a = new Path();
                a.moveTo(28, 64); a.cubicTo(40, 40, 56, 66, 70, 36);
                c.drawPath(a, st(p, 2.4f));
                p.setPathEffect(null);
                w(p);
                return true;
            }
            case "globepin":
                globe(c, p, 46, 52, 22, 3.2f);
                k(p); c.drawCircle(64, 32, 13, fill(p)); w(p);
                Icons.pin(c, p, 64, 44, 22);
                return true;
            case "suitcase":
                c.drawRoundRect(r(28, 34, 72, 72), 6, 6, fill(p));
                c.drawRoundRect(r(40, 24, 60, 36), 4, 4, st(p, 4));
                k(p); fill(p); c.drawRect(38, 34, 42, 72, p); c.drawRect(58, 34, 62, 72, p); w(p);
                c.drawCircle(34, 75, 3, p); c.drawCircle(66, 75, 3, p);
                return true;
            case "sail": // 帆船
                fp(c, p, 24, 62, 76, 62, 66, 72, 34, 72);
                c.drawLine(50, 22, 50, 62, st(p, 2.5f));
                fp(c, p, 53, 24, 53, 58, 74, 58);
                fp(c, p, 47, 30, 47, 58, 30, 58);
                return true;
            case "tropic": // 太阳 + 虚线
                c.drawCircle(50, 32, 9, fill(p));
                st(p, 3);
                for (int i = 0; i < 8; i++) { double t = i * Math.PI / 4; c.drawLine(50 + (float) Math.cos(t) * 13, 32 + (float) Math.sin(t) * 13, 50 + (float) Math.cos(t) * 18, 32 + (float) Math.sin(t) * 18, p); }
                p.setPathEffect(new DashPathEffect(new float[] { 6, 4 }, 0));
                c.drawLine(22, 56, 78, 56, st(p, 3.5f));
                p.setPathEffect(null);
                c.drawArc(r(22, 46, 78, 102), 210, 120, false, st(p, 3));
                return true;
            case "divide": // 秦岭：山脊上下写"北""南"
                fp(c, p, 18, 54, 30, 43, 40, 49, 52, 38, 64, 47, 82, 40, 82, 56, 18, 56);
                text(c, p, "北", 50, 26, 17, 19);
                text(c, p, "南", 50, 69, 17, 19);
                return true;
            case "diagonal": // 地图上一条斜线，右下密左上疏
                c.drawRoundRect(r(24, 22, 76, 74), 4, 4, st(p, 3));
                p.setPathEffect(new DashPathEffect(new float[] { 5, 3.5f }, 0));
                c.drawLine(68, 28, 32, 68, st(p, 3.5f));
                p.setPathEffect(null);
                fill(p);
                float[][] dots = { { 58, 56 }, { 64, 48 }, { 66, 62 }, { 54, 64 }, { 70, 54 }, { 60, 68 }, { 36, 34 }, { 44, 30 } };
                for (float[] d : dots) c.drawCircle(d[0], d[1], 2.6f, p);
                return true;
            case "pine": pine(c, p, 50, 22, 54); return true;
            case "palm": { // 椰子树
                Path t = new Path();
                t.moveTo(52, 76); t.quadTo(46, 56, 54, 36);
                c.drawPath(t, st(p, 5));
                float[][] leaf = { { 30, 42, 40, 28 }, { 36, 24, 44, 22 }, { 58, 20, 54, 22 }, { 74, 26, 66, 22 }, { 78, 42, 70, 30 } };
                st(p, 4.5f);
                for (float[] l : leaf) { Path a = new Path(); a.moveTo(54, 36); a.quadTo(l[2], l[3], l[0], l[1]); c.drawPath(a, p); }
                fill(p);
                c.drawCircle(51, 41, 3, p); c.drawCircle(57, 40, 3, p);
                return true;
            }
            case "sunsea": // 海上日出
                c.drawCircle(50, 46, 13, fill(p));
                st(p, 3);
                for (int i = 0; i <= 4; i++) { double t = Math.toRadians(180 + 22.5 + i * 33.75); c.drawLine(50 + (float) Math.cos(t) * 17, 46 + (float) Math.sin(t) * 17, 50 + (float) Math.cos(t) * 24, 46 + (float) Math.sin(t) * 24, p); }
                k(p); fill(p); c.drawRect(20, 54, 80, 76, p); w(p);
                waveLine(c, p, 60, 24, 76, 13, 2.5f, 3.5f);
                waveLine(c, p, 69, 30, 70, 13, 2.5f, 3);
                return true;
            case "lr": fp(c, p, 20, 48, 36, 34, 36, 42, 64, 42, 64, 34, 80, 48, 64, 62, 64, 54, 36, 54, 36, 62); return true;
            case "building":
                c.drawRect(38, 22, 62, 74, fill(p));
                k(p);
                for (int i = 0; i < 5; i++) for (int j = 0; j < 3; j++) c.drawRect(41 + j * 7, 27 + i * 8, 45 + j * 7, 32 + i * 8, p);
                c.drawRect(47, 67, 53, 74, p);
                w(p);
                return true;
            case "buildings":
                fill(p);
                c.drawRect(26, 38, 46, 74, p);
                c.drawRect(52, 24, 74, 74, p);
                k(p);
                for (int i = 0; i < 4; i++) for (int j = 0; j < 2; j++) c.drawRect(30 + j * 9, 43 + i * 8, 34 + j * 9, 48 + i * 8, p);
                for (int i = 0; i < 6; i++) for (int j = 0; j < 3; j++) c.drawRect(55.5f + j * 6.5f, 29 + i * 7.5f, 59 + j * 6.5f, 33.5f + i * 7.5f, p);
                w(p);
                return true;
            case "skyline":
                fill(p);
                c.drawRect(20, 50, 30, 74, p);
                c.drawRect(32, 38, 42, 74, p);
                c.drawRect(44, 46, 54, 74, p);
                c.drawRect(56, 32, 66, 74, p);
                c.drawRect(68, 52, 80, 74, p);
                c.drawRect(60, 20, 62, 32, p);
                k(p);
                for (int i = 0; i < 4; i++) { c.drawRect(35, 43 + i * 7, 39, 47 + i * 7, p); c.drawRect(59, 37 + i * 8, 63, 41 + i * 8, p); }
                w(p);
                return true;
            case "laurel": // 月桂环抱着一颗星
                for (int side = -1; side <= 1; side += 2) {
                    for (int i = 0; i < 6; i++) {
                        double a = Math.toRadians(100 + i * 24);
                        float cx = 50 + side * (float) Math.cos(a) * 24, cy = 50 + (float) Math.sin(a) * 24;
                        c.save();
                        c.translate(cx, cy);
                        c.rotate((float) Math.toDegrees(a) * side + (side > 0 ? 0 : 180) + 90);
                        c.drawOval(r(-2.8f, -6, 2.8f, 6), fill(p));
                        c.restore();
                    }
                }
                star(c, p, 50, 46, 12);
                return true;
            default: return false;
        }
    }

    private static void foot(Canvas c, Paint p, float cx, float cy, float rot) {
        c.save();
        c.rotate(rot, cx, cy);
        fill(p);
        c.drawOval(r(cx - 6.5f, cy - 6, cx + 6.5f, cy + 13), p);
        c.drawCircle(cx - 5, cy - 10, 2.6f, p);
        c.drawCircle(cx - 1, cy - 12, 2.8f, p);
        c.drawCircle(cx + 3.5f, cy - 11.5f, 2.6f, p);
        c.drawCircle(cx + 7, cy - 9, 2.2f, p);
        c.restore();
    }

    // ================= 地标 =================
    private static boolean sights(Canvas c, Paint p, String n) {
        switch (n) {
            case "gate": // 天安门
                fill(p);
                c.drawRect(22, 58, 78, 74, p);
                c.drawRect(30, 44, 70, 58, p);
                fp(c, p, 22, 45, 78, 45, 70, 37, 30, 37);
                fp(c, p, 27, 36, 73, 36, 65, 28, 35, 28);
                k(p);
                for (int i = 0; i < 3; i++) { float x = 34 + i * 16; c.drawOval(r(x - 4, 62, x + 4, 70), p); c.drawRect(x - 4, 66, x + 4, 74, p); }
                st(p, 1.8f);
                for (int i = 0; i < 5; i++) c.drawLine(36 + i * 7, 47, 36 + i * 7, 57, p);
                w(p);
                return true;
            case "pearl": // 东方明珠
                c.drawLine(50, 14, 50, 28, st(p, 2.2f));
                st(p, 3);
                c.drawLine(44, 62, 36, 78, p); c.drawLine(56, 62, 64, 78, p); c.drawLine(50, 64, 50, 78, p);
                fill(p);
                c.drawCircle(50, 30, 5, p);
                c.drawRect(47.5f, 30, 52.5f, 50, p);
                c.drawCircle(50, 41, 3.2f, p);
                c.drawCircle(50, 57, 10, p);
                k(p); c.drawRect(40, 56, 60, 58, p); w(p);
                return true;
            case "archbridge": { // 断桥
                Path a = new Path();
                a.moveTo(18, 58); a.quadTo(50, 30, 82, 58); a.lineTo(82, 66); a.lineTo(66, 66); a.quadTo(50, 48, 34, 66); a.lineTo(18, 66); a.close();
                c.drawPath(a, fill(p));
                st(p, 2.5f);
                c.drawLine(28, 72, 38, 72, p); c.drawLine(44, 72, 56, 72, p); c.drawLine(62, 72, 72, 72, p);
                c.drawLine(36, 77, 46, 77, p); c.drawLine(54, 77, 64, 77, p);
                return true;
            }
            case "pillars": // 张家界：石柱
                fill(p);
                float[][] pl = { { 26, 36, 32 }, { 40, 52, 22 }, { 56, 64, 34 }, { 68, 76, 42 } };
                for (float[] q : pl) {
                    c.drawRoundRect(r(q[0], q[2], q[1], 78), 4, 4, p);
                    fp(c, p, (q[0] + q[1]) / 2, q[2] - 7, q[1] - 1, q[2] + 1, q[0] + 1, q[2] + 1);
                }
                k(p); st(p, 1.6f);
                c.drawLine(31, 46, 30, 64, p); c.drawLine(46, 34, 47, 56, p); c.drawLine(60, 48, 59, 66, p);
                w(p);
                return true;
            case "karst": // 桂林：圆圆的山 + 水
                fill(p);
                c.drawOval(r(20, 36, 40, 80), p);
                c.drawOval(r(36, 26, 58, 80), p);
                c.drawOval(r(54, 40, 76, 80), p);
                k(p); c.drawRect(18, 62, 82, 82, p); w(p);
                waveLine(c, p, 66, 24, 76, 12, 2, 2.5f);
                waveLine(c, p, 73, 30, 70, 12, 2, 2.5f);
                return true;
            case "palace": // 布达拉宫
                fill(p);
                fp(c, p, 18, 76, 26, 62, 74, 62, 82, 76);
                c.drawRect(24, 44, 76, 64, p);
                c.drawRect(38, 30, 62, 46, p);
                c.drawRect(44, 24, 56, 31, p);
                k(p);
                c.drawRect(42, 33, 58, 44, p);
                for (int i = 0; i < 6; i++) { c.drawRect(27 + i * 8.4f, 48, 30 + i * 8.4f, 52, p); c.drawRect(27 + i * 8.4f, 56, 30 + i * 8.4f, 60, p); }
                w(p);
                for (int i = 0; i < 3; i++) c.drawRect(44.5f + i * 5, 36, 46.5f + i * 5, 40, p);
                return true;
            case "lake": // 青海湖：远山 + 湖面
                fp(c, p, 18, 52, 34, 30, 48, 44, 62, 26, 82, 52);
                waveLine(c, p, 60, 22, 78, 12, 2.4f, 3);
                waveLine(c, p, 67, 28, 72, 12, 2.4f, 3);
                waveLine(c, p, 74, 34, 66, 12, 2.4f, 3);
                return true;
            case "cave": // 莫高窟：崖壁上的龛
                fp(c, p, 20, 72, 20, 32, 30, 28, 42, 31, 54, 26, 66, 30, 80, 28, 80, 72);
                k(p);
                c.drawOval(r(42, 40, 58, 52), p); c.drawRect(42, 46, 58, 70, p);
                for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                    float x = j == 0 ? 26 : 64, y = 38 + i * 16;
                    c.drawOval(r(x, y, x + 10, y + 7), p); c.drawRect(x, y + 3.5f, x + 10, y + 12, p);
                }
                w(p);
                c.drawCircle(50, 52, 3.2f, p);
                fp(c, p, 44, 70, 47, 56, 53, 56, 56, 70);
                return true;
            case "rocks": { // 天涯海角
                Path a = new Path();
                a.moveTo(20, 66); a.quadTo(20, 40, 34, 38); a.quadTo(46, 38, 46, 66); a.close();
                c.drawPath(a, fill(p));
                Path b = new Path();
                b.moveTo(48, 66); b.quadTo(46, 30, 62, 26); b.quadTo(80, 28, 78, 66); b.close();
                c.drawPath(b, p);
                k(p); st(p, 1.8f); c.drawLine(56, 38, 70, 38, p); c.drawLine(56, 46, 70, 46, p); w(p);
                waveLine(c, p, 71, 20, 80, 12, 2.4f, 3);
                return true;
            }
            case "snowpeak": // 稻城：三座神山
                mountain(c, p, 16, 32, 38, 50, 74);
                mountain(c, p, 50, 68, 38, 84, 74);
                mountain(c, p, 30, 50, 24, 70, 74);
                snowCap(c, p, 50, 24, 1);
                snowCap(c, p, 32, 38, 0.7f);
                snowCap(c, p, 68, 38, 0.7f);
                return true;
            case "lakeforest": // 喀纳斯：松树 + 湖
                pine(c, p, 36, 22, 40);
                pine(c, p, 58, 30, 32);
                waveLine(c, p, 68, 22, 78, 12, 2.4f, 3);
                waveLine(c, p, 75, 28, 72, 12, 2.4f, 3);
                return true;
            case "everestflag": // 珠峰大本营：雪山 + 帐篷
                mountain(c, p, 22, 52, 22, 82, 66);
                snowCap(c, p, 52, 22, 1);
                k(p); fp(c, p, 18, 76, 30, 56, 42, 76); w(p);
                fp(c, p, 20, 74, 30, 58, 40, 74);
                k(p); fp(c, p, 30, 64, 27, 74, 33, 74); w(p);
                return true;
            case "pagodas": // 崇圣寺三塔
                pagoda(c, p, 50, 26, 76, 16, 6);
                pagoda(c, p, 28, 46, 76, 11, 4);
                pagoda(c, p, 72, 46, 76, 11, 4);
                return true;
            case "temple": // 少林寺：大殿
                fill(p);
                fp(c, p, 20, 42, 80, 42, 70, 30, 30, 30);
                fp(c, p, 20, 42, 16, 38, 24, 40);
                fp(c, p, 80, 42, 84, 38, 76, 40);
                c.drawRect(30, 42, 70, 64, p);
                c.drawRect(26, 64, 74, 68, p);
                c.drawRect(22, 68, 78, 72, p);
                k(p);
                c.drawRect(44, 48, 56, 64, p);
                c.drawRect(33, 48, 38, 56, p);
                c.drawRect(62, 48, 67, 56, p);
                w(p);
                return true;
            case "pools": // 九寨沟：一层层的海子
                st(p, 3.5f);
                c.drawArc(r(26, 30, 74, 48), 0, 180, false, p);
                c.drawArc(r(20, 44, 70, 62), 0, 180, false, p);
                c.drawArc(r(30, 58, 80, 76), 0, 180, false, p);
                pine(c, p, 30, 22, 20);
                pine(c, p, 72, 36, 18);
                return true;
            case "cablebridge": // 斜拉桥
                fill(p);
                c.drawRect(16, 56, 84, 61, p);
                st(p, 4);
                c.drawLine(34, 60, 34, 24, p); c.drawLine(66, 60, 66, 24, p);
                st(p, 2);
                for (int i = 0; i < 3; i++) {
                    float d = 6 + i * 6;
                    c.drawLine(34, 26 + i * 3, 34 - d - 4, 56, p); c.drawLine(34, 26 + i * 3, 34 + d + 2, 56, p);
                    c.drawLine(66, 26 + i * 3, 66 - d - 2, 56, p); c.drawLine(66, 26 + i * 3, 66 + d + 4, 56, p);
                }
                waveLine(c, p, 70, 22, 78, 12, 2.2f, 2.6f);
                return true;
            case "lotus2": // 澳门：莲花 + 大桥 + 海水，上面一弯星星
                lotus(c, p, 50, 58, 0.85f);
                st(p, 2.6f);
                c.drawLine(26, 64, 74, 64, p);
                waveLine(c, p, 70, 30, 70, 10, 2, 2.4f);
                for (int i = 0; i < 5; i++) { double t = Math.toRadians(210 + i * 30); star(c, p, 50 + (float) Math.cos(t) * 26, 52 + (float) Math.sin(t) * 26, 3.4f); }
                return true;
            default: return false;
        }
    }

    // ================= 音乐 =================
    private static boolean music(Canvas c, Paint p, String n) {
        switch (n) {
            case "note1": {
                fill(p);
                c.save(); c.rotate(-20, 42, 64); c.drawOval(r(32, 58, 50, 70), p); c.restore();
                c.drawRect(45, 24, 50, 64, p);
                Path a = new Path();
                a.moveTo(50, 24); a.cubicTo(60, 30, 68, 36, 62, 52); a.cubicTo(63, 42, 57, 38, 50, 36); a.close();
                c.drawPath(a, p);
                return true;
            }
            case "headphones":
                c.drawArc(r(24, 24, 76, 76), 180, 180, false, st(p, 5));
                fill(p);
                c.drawRoundRect(r(20, 48, 34, 72), 5, 5, p);
                c.drawRoundRect(r(66, 48, 80, 72), 5, 5, p);
                return true;
            case "vinyl":
                c.drawCircle(48, 50, 25, fill(p));
                k(p);
                st(p, 1.6f);
                c.drawCircle(48, 50, 20, p); c.drawCircle(48, 50, 15.5f, p);
                fill(p); c.drawCircle(48, 50, 2.2f, p);
                w(p);
                st(p, 3);
                c.drawLine(78, 20, 66, 42, p);
                c.drawCircle(78, 20, 3, fill(p));
                return true;
            case "mic":
                c.drawRoundRect(r(40, 22, 60, 52), 10, 10, fill(p));
                k(p); st(p, 1.8f);
                c.drawLine(42, 32, 58, 32, p); c.drawLine(41, 38, 59, 38, p); c.drawLine(42, 44, 58, 44, p);
                w(p);
                c.drawArc(r(33, 34, 67, 64), 0, 180, false, st(p, 3.5f));
                c.drawLine(50, 64, 50, 72, p);
                c.drawRect(40, 72, 60, 76, fill(p));
                return true;
            case "heartnote":
                c.drawPath(heart(50, 50, 1.15f), fill(p));
                k(p);
                c.drawOval(r(41, 53, 50, 60), p);
                c.drawRect(47.5f, 36, 50.5f, 57, p);
                fp(c, p, 50.5f, 36, 58, 40, 58, 44, 50.5f, 41);
                w(p);
                return true;
            case "speaker":
                c.drawRoundRect(r(32, 22, 68, 76), 5, 5, fill(p));
                k(p); c.drawCircle(50, 58, 11, p); c.drawCircle(50, 35, 5.5f, p); w(p);
                c.drawCircle(50, 58, 5, p); c.drawCircle(50, 35, 2.2f, p);
                return true;
            case "apps":
                fill(p);
                c.drawRoundRect(r(28, 26, 47, 45), 5, 5, p);
                c.drawRoundRect(r(53, 26, 72, 45), 5, 5, p);
                c.drawRoundRect(r(28, 51, 47, 70), 5, 5, p);
                c.drawRoundRect(r(53, 51, 72, 70), 5, 5, p);
                k(p);
                fp(c, p, 34, 30, 43, 35.5f, 34, 41);
                c.drawCircle(62.5f, 35.5f, 4, p);
                c.drawRect(33, 59, 42, 62, p);
                c.drawRect(58, 56, 67, 65, p);
                w(p);
                return true;
            default: return false;
        }
    }

    // ================= 彩蛋 =================
    private static boolean egg(Canvas c, Paint p, String n) {
        switch (n) {
            case "chart":
                fill(p);
                c.drawRect(28, 56, 36, 72, p); c.drawRect(40, 44, 48, 72, p); c.drawRect(52, 34, 60, 72, p); c.drawRect(64, 24, 72, 72, p);
                c.drawRect(24, 72, 76, 75, p);
                return true;
            case "spinner": // 一枚勋章 + 两段转着的箭头
                c.drawCircle(50, 48, 12, fill(p));
                k(p); star(c, p, 50, 48, 6.5f); w(p);
                arcArrow(c, p, 50, 48, 23, 200, 120, 3.5f);
                arcArrow(c, p, 50, 48, 23, 20, 120, 3.5f);
                return true;
            case "case": // 陈列盒里四枚小勋章
                c.drawRoundRect(r(24, 26, 76, 74), 4, 4, st(p, 3.5f));
                c.drawLine(26, 50, 74, 50, st(p, 2.5f));
                fill(p);
                c.drawCircle(38, 39, 6.5f, p); c.drawCircle(62, 39, 6.5f, p); c.drawCircle(38, 62, 6.5f, p); c.drawCircle(62, 62, 6.5f, p);
                return true;
            case "cabinet": // 三层的陈列柜
                c.drawRect(28, 20, 72, 76, st(p, 3.5f));
                st(p, 2.5f);
                c.drawLine(30, 39, 70, 39, p); c.drawLine(30, 58, 70, 58, p);
                fill(p);
                c.drawCircle(40, 32, 5, p); c.drawCircle(58, 32, 5, p);
                fp(c, p, 36, 44, 46, 44, 44, 52, 38, 52); c.drawRect(40, 52, 42, 56, p);
                c.drawCircle(60, 50, 5, p);
                star(c, p, 42, 68, 5); c.drawCircle(60, 68, 5, p);
                return true;
            case "hall": // 殿堂
                fp(c, p, 24, 38, 50, 22, 76, 38);
                fill(p);
                c.drawRect(26, 38, 74, 42, p);
                for (int i = 0; i < 5; i++) c.drawRect(29 + i * 9.4f, 43, 34 + i * 9.4f, 66, p);
                c.drawRect(24, 66, 76, 70, p);
                c.drawRect(22, 70, 78, 74, p);
                k(p); c.drawCircle(50, 32, 2.6f, p); w(p);
                return true;
            case "crown":
                fp(c, p, 26, 64, 24, 34, 38, 48, 50, 26, 62, 48, 76, 34, 74, 64);
                c.drawRect(26, 64, 74, 71, p);
                c.drawCircle(24, 32, 3, p); c.drawCircle(50, 24, 3.5f, p); c.drawCircle(76, 32, 3, p);
                k(p); c.drawCircle(38, 67.5f, 2, p); c.drawCircle(50, 67.5f, 2, p); c.drawCircle(62, 67.5f, 2, p); w(p);
                return true;
            case "dumpling": { // 饺子
                Path a = new Path();
                a.moveTo(20, 60); a.quadTo(50, 20, 80, 60); a.quadTo(50, 70, 20, 60); a.close();
                c.drawPath(a, fill(p));
                k(p); st(p, 2);
                for (int i = 0; i < 6; i++) { float x = 32 + i * 7.2f, y = 47 - (float) Math.sin(Math.PI * (x - 20) / 60) * 6; c.drawLine(x, y, x + 2, y + 6, p); }
                w(p);
                return true;
            }
            case "tangyuan": { // 一碗汤圆
                fill(p);
                c.drawCircle(38, 48, 7, p); c.drawCircle(52, 45, 7.5f, p); c.drawCircle(64, 49, 6, p);
                c.drawRect(20, 52, 80, 57, p);
                c.drawArc(r(24, 36, 76, 82), 0, 180, true, p);
                c.drawRect(42, 72, 58, 76, p);
                st(p, 2.5f);
                Path s = new Path(); s.moveTo(46, 34); s.quadTo(42, 28, 46, 22); c.drawPath(s, p);
                Path s2 = new Path(); s2.moveTo(56, 34); s2.quadTo(52, 28, 56, 22); c.drawPath(s2, p);
                return true;
            }
            case "willow": { // 柳枝
                Path a = new Path();
                a.moveTo(20, 26); a.quadTo(50, 14, 80, 26);
                c.drawPath(a, st(p, 3));
                p.setPathEffect(new DashPathEffect(new float[] { 4.5f, 3 }, 0));
                st(p, 3.2f);
                for (int i = 0; i < 6; i++) {
                    float x = 28 + i * 9, y0 = 26 - (float) Math.sin(Math.PI * (x - 20) / 60) * 9;
                    Path s = new Path(); s.moveTo(x, y0 + 2); s.quadTo(x - 3, y0 + 22, x + 1, y0 + 40 + (i % 2) * 8);
                    c.drawPath(s, p);
                }
                p.setPathEffect(null);
                return true;
            }
            case "kite": { // 风筝
                fp(c, p, 50, 20, 68, 42, 50, 64, 32, 42);
                k(p); st(p, 2); c.drawLine(50, 22, 50, 62, p); c.drawLine(34, 42, 66, 42, p); w(p);
                Path t = new Path();
                t.moveTo(50, 64); t.quadTo(44, 70, 52, 74); t.quadTo(60, 78, 56, 82);
                c.drawPath(t, st(p, 2.5f));
                fp(c, p, 46, 70, 50, 67, 50, 73);
                return true;
            }
            case "balloon": { // 三个气球
                float[][] b = { { 36, 36 }, { 56, 28 }, { 62, 50 } };
                st(p, 2);
                for (float[] q : b) c.drawLine(q[0], q[1] + 11, 50, 76, p);
                for (float[] q : b) {
                    w(p); c.drawOval(r(q[0] - 9, q[1] - 11, q[0] + 9, q[1] + 11), fill(p));
                    fp(c, p, q[0], q[1] + 10, q[0] + 3, q[1] + 14, q[0] - 3, q[1] + 14);
                    k(p); c.drawArc(r(q[0] - 6, q[1] - 8, q[0] + 6, q[1] + 8), 200, 50, false, st(p, 2));
                }
                w(p);
                return true;
            }
            case "zongzi": // 粽子
                fp(c, p, 50, 22, 76, 70, 24, 70);
                k(p); st(p, 2.4f);
                c.drawLine(31, 58, 69, 58, p);
                c.drawLine(42, 37, 60, 70, p);
                w(p);
                return true;
            case "qixi": // 七夕：两颗星 + 鹊桥
                star(c, p, 24, 58, 7);
                star(c, p, 76, 58, 7);
                st(p, 2.4f);
                for (int i = 0; i < 6; i++) {
                    double t = Math.toRadians(205 + i * 26);
                    float x = 50 + (float) Math.cos(t) * 26, y = 60 + (float) Math.sin(t) * 26;
                    c.drawLine(x - 4, y - 2, x, y + 1, p); c.drawLine(x, y + 1, x + 4, y - 2, p);
                }
                c.drawPath(heart(50, 26, 0.3f), fill(p));
                return true;
            case "mooncake": // 月饼
                fill(p);
                for (int i = 0; i < 16; i++) { double t = i * Math.PI / 8; c.drawCircle(50 + (float) Math.cos(t) * 23, 48 + (float) Math.sin(t) * 23, 4.5f, p); }
                c.drawCircle(50, 48, 23, p);
                k(p);
                c.drawCircle(50, 48, 17, st(p, 2));
                text(c, p, "月", 50, 48, 17, 22);
                w(p);
                return true;
            case "tree": // 圣诞树
                pine(c, p, 50, 26, 50);
                star(c, p, 50, 22, 6);
                k(p); fill(p);
                c.drawCircle(44, 44, 2.2f, p); c.drawCircle(56, 52, 2.2f, p); c.drawCircle(42, 62, 2.2f, p); c.drawCircle(60, 66, 2.2f, p);
                w(p);
                return true;
            case "starmoon":
                c.drawPath(crescent(40, 48, 18), fill(p));
                star(c, p, 66, 32, 6);
                star(c, p, 70, 56, 4.5f);
                star(c, p, 58, 68, 3.5f);
                return true;
            case "lamp": { // 路灯
                Path a = new Path();
                a.moveTo(40, 76); a.lineTo(40, 34); a.quadTo(40, 26, 50, 26); a.lineTo(56, 26);
                c.drawPath(a, st(p, 4));
                fp(c, p, 52, 26, 70, 26, 66, 33, 56, 33);
                st(p, 2.4f);
                c.drawLine(58, 40, 55, 50, p); c.drawLine(61, 40, 61, 51, p); c.drawLine(64, 40, 67, 50, p);
                c.drawRect(32, 76, 48, 78, fill(p));
                star(c, p, 26, 28, 3.5f);
                return true;
            }
            case "guitar":
                c.save();
                c.rotate(35, 50, 50);
                fill(p);
                c.drawCircle(50, 62, 13, p);
                c.drawCircle(50, 46, 10, p);
                c.drawRect(48, 16, 52, 40, p);
                c.drawRoundRect(r(45.5f, 11, 54.5f, 20), 2, 2, p);
                k(p); c.drawCircle(50, 54, 4.5f, p); c.drawRect(45, 66, 55, 68, p); w(p);
                c.restore();
                return true;
            case "umbrella": { // 雨伞
                Path a = new Path();
                a.moveTo(22, 50); a.quadTo(22, 24, 50, 22); a.quadTo(78, 24, 78, 50);
                for (int i = 0; i < 4; i++) { float x0 = 78 - i * 14; a.quadTo(x0 - 7, 44, x0 - 14, 50); }
                a.close();
                c.drawPath(a, fill(p));
                Path h = new Path();
                h.moveTo(50, 48); h.lineTo(50, 68); h.quadTo(50, 74, 44, 74); h.quadTo(40, 74, 40, 70);
                c.drawPath(h, st(p, 3.5f));
                return true;
            }
            case "candle":
                fill(p);
                c.drawRoundRect(r(42, 38, 58, 74), 3, 3, p);
                c.drawRect(34, 74, 66, 78, p);
                c.drawPath(drop(50, 26, 0.4f), p);
                k(p); c.drawRect(49, 34, 51, 39, p); w(p);
                return true;
            case "starwindow": // 天窗里的星星
                c.drawRoundRect(r(22, 28, 78, 68), 8, 8, st(p, 4));
                star(c, p, 38, 42, 5.5f);
                star(c, p, 60, 38, 4);
                star(c, p, 64, 56, 6.5f);
                fill(p);
                c.drawCircle(44, 58, 1.8f, p); c.drawCircle(52, 48, 1.6f, p);
                return true;
            case "cloud": c.drawPath(cloudPath(50, 46, 1.45f), fill(p)); return true;
            case "wind": { // 吹风
                st(p, 4);
                Path a = new Path(); a.moveTo(22, 38); a.lineTo(56, 38); a.cubicTo(66, 38, 68, 26, 60, 25); a.cubicTo(55, 25, 53, 30, 56, 32); c.drawPath(a, p);
                Path b = new Path(); b.moveTo(22, 50); b.lineTo(68, 50); b.cubicTo(78, 50, 80, 62, 72, 64); b.cubicTo(66, 65, 64, 60, 67, 58); c.drawPath(b, p);
                Path d = new Path(); d.moveTo(28, 62); d.lineTo(50, 62); d.cubicTo(58, 62, 58, 72, 52, 72); c.drawPath(d, p);
                return true;
            }
            case "acsnow": // 空调出风口 + 雪花
                c.drawRoundRect(r(24, 26, 76, 52), 6, 6, st(p, 3.5f));
                st(p, 2.8f);
                c.drawLine(30, 33, 70, 33, p); c.drawLine(30, 39, 70, 39, p); c.drawLine(30, 45, 70, 45, p);
                st(p, 2.6f);
                for (int i = 0; i < 3; i++) { double t = i * Math.PI / 3; float dx = (float) Math.cos(t) * 9, dy = (float) Math.sin(t) * 9; c.drawLine(50 - dx, 66 - dy, 50 + dx, 66 + dy, p); }
                return true;
            case "heater": // 暖风：往上冒的热气
                st(p, 4);
                for (int i = 0; i < 3; i++) {
                    float x = 36 + i * 14;
                    Path a = new Path();
                    a.moveTo(x, 62); a.quadTo(x - 6, 54, x, 46); a.quadTo(x + 6, 38, x, 30); a.quadTo(x - 4, 26, x, 22);
                    c.drawPath(a, p);
                }
                c.drawRect(26, 66, 74, 72, fill(p));
                return true;
            case "seatheat":
                seat(c, p);
                st(p, 3);
                for (int i = 0; i < 3; i++) { float x = 56 + i * 8; Path a = new Path(); a.moveTo(x, 52); a.quadTo(x - 4, 44, x, 38); a.quadTo(x + 4, 32, x, 26); c.drawPath(a, p); }
                return true;
            case "seatwind":
                seat(c, p);
                st(p, 3);
                c.drawLine(52, 30, 76, 30, p); c.drawLine(56, 40, 78, 40, p); c.drawLine(52, 50, 74, 50, p);
                return true;
            case "defrost": { // 前挡除雾
                Path a = new Path();
                a.moveTo(22, 66); a.quadTo(50, 26, 78, 66); a.close();
                c.drawPath(a, st(p, 3.5f));
                for (int i = 0; i < 3; i++) {
                    float x = 38 + i * 12;
                    Path s = new Path(); s.moveTo(x, 66); s.quadTo(x - 4, 60, x, 54); s.quadTo(x + 4, 49, x, 44);
                    c.drawPath(s, st(p, 3.8f));
                    fp(c, p, x - 5, 46, x, 37, x + 5, 46);
                }
                return true;
            }
            case "filter": // 滤网
                c.drawRoundRect(r(24, 30, 76, 66), 4, 4, st(p, 3.5f));
                c.drawPath(poly(28, 62, 34, 34, 40, 62, 46, 34, 52, 62, 58, 34, 64, 62, 70, 34, 72, 50), st(p, 2.8f));
                return true;
            case "rainbow":
                st(p, 4.5f);
                c.drawArc(r(22, 32, 78, 88), 180, 180, false, p);
                c.drawArc(r(30, 40, 70, 80), 180, 180, false, p);
                c.drawArc(r(38, 48, 62, 72), 180, 180, false, p);
                c.drawPath(cloudPath(25, 62, 0.45f), fill(p));
                c.drawPath(cloudPath(75, 62, 0.45f), fill(p));
                return true;
            case "notelight": // 音符 + 光
                fill(p);
                c.save(); c.rotate(-20, 40, 62); c.drawOval(r(31, 56, 49, 68), p); c.restore();
                c.drawRect(44, 28, 49, 62, p);
                fp(c, p, 49, 28, 62, 34, 62, 40, 49, 35);
                st(p, 3);
                c.drawLine(66, 24, 72, 18, p); c.drawLine(70, 34, 78, 32, p); c.drawLine(58, 20, 58, 13, p);
                return true;
            case "spectrum": { // 一排跳动的柱子
                float[] hh = { 18, 30, 40, 26, 44, 32, 20 };
                fill(p);
                for (int i = 0; i < hh.length; i++) c.drawRoundRect(r(23 + i * 8, 72 - hh[i], 29 + i * 8, 72), 3, 3, p);
                return true;
            }
            case "soundwave": // 喇叭 + 声波
                fp(c, p, 24, 40, 34, 40, 46, 28, 46, 68, 34, 56, 24, 56);
                st(p, 3.5f);
                c.drawArc(r(42, 36, 58, 60), -50, 100, false, p);
                c.drawArc(r(42, 28, 68, 68), -50, 100, false, p);
                c.drawArc(r(42, 20, 78, 76), -50, 100, false, p);
                return true;
            case "trunk": // 尾门打开的车尾
                c.drawRoundRect(r(26, 46, 74, 68), 6, 6, fill(p));
                c.drawRect(30, 68, 38, 74, p); c.drawRect(62, 68, 70, 74, p);
                k(p); c.drawRect(29, 52, 37, 57, p); c.drawRect(63, 52, 71, 57, p); c.drawRect(42, 58, 58, 62, p); w(p);
                fp(c, p, 32, 40, 68, 40, 62, 22, 38, 22);
                k(p); fp(c, p, 36, 37, 64, 37, 60, 25, 40, 25); w(p);
                return true;
            case "hood": // 车头机舱盖掀起来
                c.drawRoundRect(r(24, 48, 76, 68), 6, 6, fill(p));
                c.drawRect(28, 68, 36, 74, p); c.drawRect(64, 68, 72, 74, p);
                k(p); c.drawOval(r(28, 54, 38, 60), p); c.drawOval(r(62, 54, 72, 60), p); c.drawRect(42, 56, 58, 62, p); w(p);
                fp(c, p, 30, 42, 70, 42, 62, 22, 38, 22);
                c.drawLine(36, 46, 39, 30, st(p, 2));
                return true;
            case "caropen": // 俯视的车，四个门和尾门都开着
                fill(p);
                c.drawRoundRect(r(38, 24, 62, 72), 10, 10, p);
                k(p);
                c.drawRoundRect(r(41, 32, 59, 40), 3, 3, p);
                c.drawRoundRect(r(42, 58, 58, 64), 3, 3, p);
                w(p);
                float[][] d = { { 38, 42, -1 }, { 38, 54, -1 }, { 62, 42, 1 }, { 62, 54, 1 } };
                for (float[] q : d) {
                    c.save();
                    c.rotate(q[2] * 40, q[0], q[1]);
                    if (q[2] < 0) c.drawRoundRect(r(q[0] - 14, q[1], q[0], q[1] + 4), 2, 2, p);
                    else c.drawRoundRect(r(q[0], q[1], q[0] + 14, q[1] + 4), 2, 2, p);
                    c.restore();
                }
                fp(c, p, 41, 74, 59, 74, 62, 80, 38, 80);
                return true;
            default: return false;
        }
    }

    // ================= 第二版：重画的一批 =================
    /** 折线（不闭合：对勾、之字路） */
    private static void line(Canvas c, Paint p, float lw, float... xy) {
        Path a = new Path();
        a.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) a.lineTo(xy[i], xy[i + 1]);
        c.drawPath(a, st(p, lw));
    }

    /** 先用黑笔描一圈再填白：叠在别的白东西前面时中间留一道缝 */
    private static void carve(Canvas c, Paint p, Path a, float gap) {
        k(p);
        c.drawPath(a, st(p, gap * 2));
        w(p);
        c.drawPath(a, fill(p));
    }

    /** 图钉（尖在 x, y，高 s），同 Icons.pin，返回轮廓 */
    private static Path pinPath(float x, float y, float s) { return pinPath(x, y, s, 0.42f); }

    private static Path pinPath(float x, float y, float s, float holeK) {
        Path a = new Path();
        float rr = s * 0.42f, cy = y - s + rr;
        a.addCircle(x, cy, rr, Path.Direction.CW);
        Path t = new Path();
        t.moveTo(x - rr * 0.86f, cy + rr * 0.5f); t.lineTo(x, y); t.lineTo(x + rr * 0.86f, cy + rr * 0.5f); t.close();
        a.op(t, Path.Op.UNION);
        Path hole = new Path();
        hole.addCircle(x, cy, rr * holeK, Path.Direction.CW);
        a.op(hole, Path.Op.DIFFERENCE);
        return a;
    }

    /** 叶子：从 (x, y) 长出去，朝 deg 度（0 朝上，顺时针），长 len */
    private static void leaf(Canvas c, Paint p, float x, float y, float deg, float len) {
        c.save();
        c.translate(x, y);
        c.rotate(deg);
        Path a = new Path();
        a.moveTo(0, 0);
        a.cubicTo(-len * 0.5f, -len * 0.3f, -len * 0.35f, -len * 0.85f, 0, -len);
        a.cubicTo(len * 0.35f, -len * 0.85f, len * 0.5f, -len * 0.3f, 0, 0);
        a.close();
        c.drawPath(a, fill(p));
        k(p);
        c.drawLine(0, -len * 0.15f, 0, -len * 0.75f, st(p, 2));
        w(p);
        c.restore();
    }

    /** 系着安全带的人（正面） */
    private static void beltMan(Canvas c, Paint p) {
        c.drawCircle(50, 26, 7.5f, fill(p));
        Path a = new Path();
        a.moveTo(32, 74); a.lineTo(32, 49); a.quadTo(32, 37, 44, 37); a.lineTo(56, 37); a.quadTo(68, 37, 68, 49); a.lineTo(68, 74); a.close();
        c.drawPath(a, p);
        k(p);
        c.drawLine(37, 40, 61, 64, st(p, 8));
        w(p);
        c.drawLine(37, 40, 61, 64, st(p, 3.2f));
        k(p);
        c.drawRoundRect(r(54, 58, 67, 70), 2, 2, fill(p));
        w(p);
        c.drawRoundRect(r(56.5f, 60.5f, 64.5f, 67.5f), 1.5f, 1.5f, p);
    }

    private static boolean redo(Canvas c, Paint p, String n, String arg) {
        switch (n) {
            case "hand": { // 招手
                c.save();
                c.rotate(-14, 50, 62);
                c.drawRoundRect(r(33.5f, 44, 64.5f, 74), 11, 11, fill(p));
                st(p, 5.5f);
                c.drawLine(37, 47, 37, 29, p);
                c.drawLine(45, 47, 45, 22, p);
                c.drawLine(53, 47, 53, 23, p);
                c.drawLine(61, 48, 61, 30, p);
                st(p, 6);
                c.drawLine(36, 64, 25, 52, p);
                c.restore();
                st(p, 3);
                c.drawArc(r(26, 23, 74, 71), -62, 34, false, p);
                c.drawArc(r(20, 17, 80, 77), -60, 30, false, p);
                return true;
            }
            case "road2": { // 弯弯的路通到远山
                fp(c, p, 18, 54, 32, 34, 41, 44, 54, 26, 69, 44, 82, 54);
                Path a = new Path();
                a.moveTo(22, 76); a.cubicTo(30, 66, 62, 64, 51, 50); a.lineTo(54, 50); a.cubicTo(72, 60, 58, 68, 78, 76); a.close();
                carve(c, p, a, 2.5f);
                k(p);
                p.setPathEffect(new DashPathEffect(new float[] { 4, 3.5f }, 0));
                Path m = new Path();
                m.moveTo(50, 76); m.cubicTo(46, 68, 66, 62, 52.5f, 51);
                c.drawPath(m, st(p, 2.2f));
                p.setPathEffect(null);
                w(p);
                return true;
            }
            case "river": { // 两岸的山，中间一条弯弯的江
                fp(c, p, 18, 50, 30, 30, 42, 50);
                fp(c, p, 56, 46, 70, 26, 84, 46);
                Path a = new Path();
                a.moveTo(47, 24); a.quadTo(36, 34, 44, 42); a.quadTo(54, 50, 40, 60); a.quadTo(28, 68, 28, 76);
                a.lineTo(54, 76); a.quadTo(50, 66, 60, 58); a.quadTo(72, 48, 57, 40); a.quadTo(46, 32, 53, 24); a.close();
                carve(c, p, a, 2.5f);
                k(p);
                waveLine(c, p, 67, 36, 50, 7, 1.6f, 2.2f);
                w(p);
                return true;
            }
            case "bird": { // 枝头的小鸟，后面初升的太阳
                c.drawCircle(29, 29, 7, fill(p));
                c.drawOval(r(34, 40, 64, 64), p);
                c.drawCircle(61, 38, 9, p);
                fp(c, p, 38, 50, 20, 58, 24, 64, 42, 60);
                fp(c, p, 69, 35, 78, 38, 69, 41);
                k(p);
                c.drawCircle(63, 36, 2, p);
                Path wg = new Path();
                wg.moveTo(40, 50); wg.quadTo(52, 46, 58, 57);
                c.drawPath(wg, st(p, 2.5f));
                w(p);
                st(p, 2.5f);
                c.drawLine(46, 63, 45, 69, p);
                c.drawLine(53, 63, 54, 69, p);
                c.drawLine(22, 71, 78, 69, st(p, 4));
                return true;
            }
            case "rooster": { // 公鸡
                fill(p);
                c.drawCircle(60, 31, 7, p);
                c.drawCircle(55.5f, 24, 3.2f, p);
                c.drawCircle(60, 22.5f, 3.6f, p);
                c.drawCircle(64.5f, 24, 3.2f, p);
                Path bd = new Path();
                bd.moveTo(55, 34); bd.cubicTo(52, 44, 46, 48, 40, 50); bd.cubicTo(32, 53, 33, 65, 44, 66);
                bd.lineTo(57, 66); bd.cubicTo(68, 64, 70, 50, 65, 37); bd.close();
                c.drawPath(bd, p);
                fp(c, p, 66, 29, 73, 31.5f, 66, 34);
                c.drawCircle(64.5f, 38.5f, 2.6f, p);
                st(p, 4.5f);
                Path t = new Path();
                t.moveTo(40, 53); t.quadTo(24, 47, 28, 28);
                t.moveTo(39, 57); t.quadTo(20, 55, 20, 38);
                t.moveTo(41, 61); t.quadTo(24, 65, 18, 52);
                c.drawPath(t, p);
                st(p, 3);
                c.drawLine(48, 66, 46, 73, p);
                c.drawLine(56, 66, 58, 73, p);
                st(p, 2.5f);
                c.drawLine(42, 74, 50, 74, p);
                c.drawLine(54, 74, 62, 74, p);
                k(p);
                c.drawCircle(61.5f, 30, 1.6f, fill(p));
                Path wg = new Path();
                wg.moveTo(45, 55); wg.quadTo(54, 52, 59, 60);
                c.drawPath(wg, st(p, 2.5f));
                w(p);
                return true;
            }
            case "belt": beltMan(c, p); return true;
            case "belt2": { // 系着安全带的人，右上角一个打了勾的圆牌
                c.save();
                c.translate(-6, 3);
                c.scale(0.88f, 0.88f, 50, 48);
                beltMan(c, p);
                c.restore();
                Path b = new Path();
                b.addCircle(68, 28, 11, Path.Direction.CW);
                carve(c, p, b, 2.5f);
                k(p);
                line(c, p, 3, 62.5f, 28, 66.5f, 32, 73.5f, 24);
                w(p);
                return true;
            }
            case "calcheck":
                calendar(c, p, "");
                line(c, p, 4.5f, 38, 56, 46, 64, 62, 48);
                return true;
            case "shieldok":
                shieldPath(c, p);
                k(p);
                line(c, p, 5, 39, 48, 47, 57, 62, 38);
                w(p);
                return true;
            case "slope": // 车爬陡坡
                fp(c, p, 20, 76, 80, 76, 80, 38);
                c.save();
                c.rotate(-32.3f, 50, 57);
                car(c, p, 50, 57 - 2.5f - 15 * 0.62f, 0.62f);
                c.restore();
                return true;
            case "hill": { // 一座圆山，山顶插旗，山上一条小路
                Path a = new Path();
                a.moveTo(18, 74); a.quadTo(50, -2, 82, 74); a.close();
                c.drawPath(a, fill(p));
                c.drawLine(50, 37, 50, 20, st(p, 2.5f));
                fp(c, p, 51, 19, 63, 23.5f, 51, 28);
                k(p);
                Path t = new Path();
                t.moveTo(42, 74); t.quadTo(60, 64, 46, 56); t.quadTo(36, 50, 50, 41);
                c.drawPath(t, st(p, 2.5f));
                w(p);
                return true;
            }
            case "hill2": { // 前后两座山，高的那座插旗
                Path b = new Path();
                b.moveTo(38, 72); b.quadTo(61, 2, 84, 72); b.close();
                c.drawPath(b, fill(p));
                c.drawLine(61, 38, 61, 21, st(p, 2.5f));
                fp(c, p, 62, 20, 73, 24.5f, 62, 29);
                Path f = new Path();
                f.moveTo(16, 72); f.quadTo(38, 24, 60, 72); f.close();
                carve(c, p, f, 2.5f);
                return true;
            }
            case "pin": // 大图钉扎在地上
                c.drawOval(r(32, 64, 68, 76), st(p, 3));
                carve(c, p, pinPath(50, 70, 52), 2.5f);
                return true;
            case "pins": { // 一路插过去的三个图钉，中间虚线连着
                p.setPathEffect(new DashPathEffect(new float[] { 4, 3.5f }, 0));
                Path a = new Path();
                a.moveTo(27, 74); a.quadTo(38, 70, 47, 62); a.quadTo(56, 54, 68, 52);
                c.drawPath(a, st(p, 2.5f));
                p.setPathEffect(null);
                carve(c, p, pinPath(68, 52, 32, 0.3f), 2);
                carve(c, p, pinPath(47, 62, 30, 0.3f), 2);
                carve(c, p, pinPath(27, 74, 26, 0.3f), 2);
                return true;
            }
            case "horizon": // 千山万水：远山、太阳、近处的水
                c.drawCircle(64, 30, 7, fill(p));
                carve(c, p, poly(18, 56, 32, 34, 42, 46, 54, 28, 70, 50, 82, 56), 2.5f);
                waveLine(c, p, 64, 22, 78, 12, 2.5f, 3.5f);
                waveLine(c, p, 72, 28, 72, 12, 2.5f, 3.5f);
                return true;
            case "camel": { // 骆驼走在沙丘上
                c.drawCircle(28, 27, 5.5f, fill(p));
                Path a = new Path();
                a.moveTo(28, 48); a.quadTo(29, 41, 35, 40); a.quadTo(39, 29, 44, 39); a.quadTo(48, 31, 53, 40); a.lineTo(58, 42);
                a.quadTo(62, 40, 63, 34); a.lineTo(65, 26); a.quadTo(67, 22, 71, 22.5f); a.lineTo(76, 25); a.lineTo(75.5f, 29); a.lineTo(70, 29.5f);
                a.quadTo(68, 37, 66, 46); a.quadTo(64, 52, 58, 52); a.lineTo(34, 52); a.quadTo(28, 52, 28, 48); a.close();
                c.drawPath(a, p);
                st(p, 3.5f);
                c.drawLine(34, 51, 32, 68, p);
                c.drawLine(40, 51, 40, 68, p);
                c.drawLine(54, 51, 56, 68, p);
                c.drawLine(60, 50, 62, 68, p);
                c.drawLine(28.5f, 46, 25, 56, st(p, 2.2f));
                Path d = new Path();
                d.moveTo(18, 74); d.quadTo(34, 66, 50, 72); d.quadTo(66, 78, 82, 70);
                c.drawPath(d, st(p, 3.5f));
                k(p);
                c.drawCircle(71, 25.5f, 1.4f, fill(p));
                w(p);
                return true;
            }
            case "polaris": { // 北极星：一颗大的四角星，旁边两颗小星
                Path a = new Path();
                a.moveTo(50, 16); a.quadTo(55, 39, 78, 44); a.quadTo(55, 49, 50, 74); a.quadTo(45, 49, 22, 44); a.quadTo(45, 39, 50, 16); a.close();
                c.drawPath(a, fill(p));
                star(c, p, 29, 25, 5.5f);
                star(c, p, 72, 66, 4.5f);
                return true;
            }
            case "peak": { // 泰山：太阳从山后出来，层层的山头，山上一条弯弯的石阶
                c.drawCircle(68, 29, 7.5f, fill(p));
                carve(c, p, poly(16, 74, 27, 57, 33, 59, 42, 42, 50, 25, 55, 34, 59, 32, 67, 49, 73, 51, 84, 74), 2.5f);
                k(p);
                fill(p);
                float[] xs = { 47, 50, 53, 51, 48, 50 };
                for (int i = 0; i < 6; i++) {
                    float y = 69 - i * 5.2f, hw = 4.2f - i * 0.4f;
                    c.drawRect(xs[i] - hw, y, xs[i] + hw, y + 2.2f, p);
                }
                w(p);
                return true;
            }
            case "pinerock": { // 黄山迎客松：崖上一棵松，一根长枝伸出来，下面云海
                Path rock = new Path();
                rock.moveTo(18, 76); rock.lineTo(18, 54); rock.quadTo(22, 46, 30, 48); rock.quadTo(36, 42, 42, 50); rock.lineTo(48, 58); rock.lineTo(50, 76); rock.close();
                c.drawPath(rock, fill(p));
                Path tr = new Path();
                tr.moveTo(34, 50); tr.cubicTo(36, 40, 42, 36, 48, 30);
                c.drawPath(tr, st(p, 4.5f));
                Path br = new Path();
                br.moveTo(42, 38); br.quadTo(56, 38, 70, 44);
                c.drawPath(br, st(p, 3.5f));
                fill(p);
                c.drawRoundRect(r(40, 22, 64, 30), 4, 4, p);
                c.drawRoundRect(r(56, 39, 82, 47), 4, 4, p);
                c.drawRoundRect(r(26, 31, 44, 37), 3, 3, p);
                c.drawPath(cloudPath(66, 64, 0.62f), p);
                return true;
            }
            case "crater": // 长白山：平顶的火山，山顶一池水，山腰的雪线
                fp(c, p, 18, 74, 38, 38, 62, 38, 82, 74);
                c.drawOval(r(36, 31, 64, 45), p);
                k(p);
                c.drawOval(r(40.5f, 34.5f, 59.5f, 41.5f), fill(p));
                line(c, p, 2.5f, 30, 56, 35, 51, 40, 57, 45, 51, 50, 57, 55, 51, 60, 57, 65, 51, 70, 56);
                w(p);
                return true;
            case "warrior": { // 兵马俑：侧边的发髻、一排排的甲片
                fill(p);
                c.drawCircle(57, 21, 4.5f, p);
                c.drawOval(r(41, 22, 59, 44), p);
                c.drawRect(46, 42, 54, 48, p);
                Path ar = new Path();
                ar.moveTo(26, 75); ar.lineTo(30, 54); ar.quadTo(33, 48, 43, 48); ar.lineTo(57, 48); ar.quadTo(67, 48, 70, 54); ar.lineTo(74, 75); ar.close();
                c.drawPath(ar, p);
                k(p);
                st(p, 2);
                c.drawLine(45, 32, 48.5f, 32, p);
                c.drawLine(51.5f, 32, 55, 32, p);
                Path ms = new Path();
                ms.moveTo(45, 38.5f); ms.quadTo(50, 36, 55, 38.5f);
                c.drawPath(ms, p);
                line(c, p, 2, 43, 48, 50, 55, 57, 48);
                st(p, 2);
                c.drawLine(31, 60, 69, 60, p);
                c.drawLine(30, 67, 70, 67, p);
                for (int i = 0; i < 4; i++) c.drawLine(38 + i * 8, 60, 38 + i * 8, 67, p);
                for (int i = 0; i < 5; i++) c.drawLine(34 + i * 8, 67, 34 + i * 8, 74, p);
                w(p);
                return true;
            }
            case "waterfall": { // 黄果树：宽宽的一面瀑布，底下的水
                Path cl = new Path();
                cl.moveTo(18, 33); cl.lineTo(18, 26); cl.quadTo(30, 19, 42, 24); cl.quadTo(55, 18, 68, 23); cl.quadTo(76, 21, 82, 26); cl.lineTo(82, 33); cl.close();
                c.drawPath(cl, fill(p));
                for (int i = 0; i < 8; i++) {
                    float x = 24 + i * 7;
                    c.drawRoundRect(r(x, 31, x + 4.5f, 54 + (i % 3) * 2.5f), 2.2f, 2.2f, p);
                }
                waveLine(c, p, 64, 22, 78, 10, 2, 3.5f);
                waveLine(c, p, 72, 28, 72, 10, 2, 3.5f);
                return true;
            }
            case "buddha": { // 乐山大佛：崖壁上的龛里坐着大佛
                Path arch = new Path();
                arch.moveTo(22, 76); arch.lineTo(22, 36); arch.quadTo(22, 14, 50, 14); arch.quadTo(78, 14, 78, 36); arch.lineTo(78, 76);
                c.drawPath(arch, st(p, 3.5f));
                fill(p);
                c.drawCircle(50, 31, 8.5f, p);
                c.drawCircle(50, 22.5f, 3.5f, p);
                c.drawRoundRect(r(39.5f, 27, 43.5f, 38), 2, 2, p);
                c.drawRoundRect(r(56.5f, 27, 60.5f, 38), 2, 2, p);
                c.drawRect(46, 38, 54, 45, p);
                Path bd = new Path();
                bd.moveTo(31, 73); bd.lineTo(31, 58); bd.quadTo(31, 45, 45, 44); bd.lineTo(55, 44); bd.quadTo(69, 45, 69, 58); bd.lineTo(69, 73); bd.close();
                c.drawPath(bd, p);
                k(p);
                st(p, 2);
                c.drawLine(45.5f, 31, 48, 31, p);
                c.drawLine(52, 31, 54.5f, 31, p);
                line(c, p, 2.2f, 44, 45, 50, 54, 56, 45);
                Path lap = new Path();
                lap.moveTo(35, 63); lap.quadTo(50, 70, 65, 63);
                c.drawPath(lap, st(p, 2.2f));
                w(p);
                return true;
            }
            case "falls2": // 壶口：黄河在峡谷里收成一股往下冲
                waveLine(c, p, 20, 26, 74, 10, 2, 3);
                waveLine(c, p, 26, 22, 78, 10, 2, 3);
                fp(c, p, 18, 32, 38, 32, 44, 74, 18, 74);
                fp(c, p, 62, 32, 82, 32, 82, 74, 56, 74);
                st(p, 2.5f);
                c.drawLine(41.5f, 34, 46, 62, p);
                c.drawLine(47, 34, 48.5f, 64, p);
                c.drawLine(53, 34, 51.5f, 64, p);
                c.drawLine(58.5f, 34, 54, 62, p);
                fill(p);
                c.drawCircle(46.5f, 69, 2, p);
                c.drawCircle(53.5f, 69, 2, p);
                c.drawCircle(50, 73.5f, 1.8f, p);
                return true;
            case "rose": { // 一枝玫瑰
                c.drawLine(50, 46, 50, 76, st(p, 3.5f));
                leaf(c, p, 50, 66, -55, 17);
                leaf(c, p, 50, 58, 55, 15);
                Path bowl = new Path();
                bowl.moveTo(35, 31); bowl.quadTo(35, 48, 50, 49); bowl.quadTo(65, 48, 65, 31); bowl.close();
                c.drawPath(bowl, fill(p));
                c.drawCircle(50, 30, 11.5f, p);
                k(p);
                st(p, 2.2f);
                c.drawArc(r(45, 25, 55, 35), 200, 250, false, p);
                c.drawArc(r(40.5f, 20.5f, 59.5f, 39.5f), 10, 150, false, p);
                Path s = new Path();
                s.moveTo(35.5f, 37); s.quadTo(50, 45, 64.5f, 37);
                c.drawPath(s, p);
                w(p);
                return true;
            }
            case "sun2": { // 烈日 + 满格的温度计
                c.drawCircle(36, 44, 11, fill(p));
                st(p, 3.2f);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(36 + cs * 15, 44 + sn * 15, 36 + cs * 21, 44 + sn * 21, p);
                }
                c.drawRoundRect(r(63, 24, 73, 62), 5, 5, st(p, 3));
                c.drawCircle(68, 66, 7.5f, fill(p));
                c.drawRect(66.5f, 30, 69.5f, 64, p);
                return true;
            }
            case "dam": // 三峡大坝：一道长坝，一排闸口在泄洪
                waveLine(c, p, 26, 24, 76, 10, 2, 3);
                fp(c, p, 20, 33, 80, 33, 83, 58, 17, 58);
                k(p);
                fill(p);
                c.drawRect(20, 36.5f, 80, 38.5f, p);
                for (int i = 0; i < 6; i++) { float x = 27 + i * 8.6f; c.drawRoundRect(r(x, 42, x + 4.5f, 52), 1.5f, 1.5f, p); }
                w(p);
                for (int i = 0; i < 6; i++) { float x = 27 + i * 8.6f; c.drawRect(x + 0.5f, 57, x + 4, 65, p); }
                waveLine(c, p, 70, 22, 78, 10, 2, 3);
                return true;
            case "feather": { // 一根羽毛
                c.save();
                c.rotate(38, 50, 48);
                Path a = new Path();
                a.moveTo(50, 16); a.cubicTo(63, 28, 63, 54, 52, 70); a.lineTo(48, 70); a.cubicTo(37, 54, 37, 28, 50, 16); a.close();
                c.drawPath(a, fill(p));
                c.drawLine(50, 68, 50, 80, st(p, 3));
                k(p);
                st(p, 2);
                c.drawLine(50, 22, 50, 68, p);
                c.drawLine(61.5f, 36, 53, 42, p);
                c.drawLine(38.5f, 50, 47, 55, p);
                w(p);
                c.restore();
                return true;
            }
            default: return false;
        }
    }

    // ================= 纯电、纯油车的勋章 =================
    /** 右上角一个圆牌写次数（tank:10 这类），先在下面挖一圈缝 */
    private static void badge(Canvas c, Paint p, String arg) {
        if (arg.isEmpty()) return;
        Path a = new Path();
        a.addCircle(70, 27, 9.5f, Path.Direction.CW);
        carve(c, p, a, 2.5f);
        k(p);
        text(c, p, arg, 70, 27, 11, 14);
        w(p);
    }

    /** 油桶（中心 cx, cy，宽 30 高约 45）：solid 实心（cross 再压一个 X），否则只描边、里面的油高 lvl（0~1） */
    private static void can(Canvas c, Paint p, float cx, float cy, boolean solid, boolean cross, float lvl) {
        c.save();
        c.translate(cx, cy);
        fill(p);
        c.drawRoundRect(r(-15, -19, 3, -8), 2.5f, 2.5f, p);
        c.save();
        c.rotate(35, 10, -12);
        c.drawRoundRect(r(7, -22, 13, -9), 1.5f, 1.5f, p);
        c.restore();
        if (solid) c.drawRoundRect(r(-15, -10, 15, 24), 3, 3, p);
        else {
            k(p);
            c.drawRoundRect(r(-15, -10, 15, 24), 3, 3, p);
            w(p);
            c.drawRoundRect(r(-13.25f, -8.25f, 13.25f, 22.25f), 2, 2, st(p, 3.5f));
            if (lvl > 0) c.drawRect(-9.5f, 18.5f - 23 * lvl, 9.5f, 18.5f, fill(p));
        }
        k(p);
        c.drawRoundRect(r(-11, -16, -1, -12), 1.5f, 1.5f, fill(p));
        if (solid && cross) {
            st(p, 2.4f);
            c.drawLine(-9, -4, 9, 18, p);
            c.drawLine(9, -4, -9, 18, p);
        }
        w(p);
        c.restore();
    }

    /** 油表：圆心 (cx, cy)、半径 rr 的上半圈，E 在左、F 在右，指针朝 deg 度（180 = E、270 = 一半、360 = F），下面一滴油 */
    private static void gauge(Canvas c, Paint p, float cx, float cy, float rr, float deg) {
        c.drawArc(r(cx - rr, cy - rr, cx + rr, cy + rr), 180, 180, false, st(p, 4));
        st(p, 3);
        for (int i = 0; i <= 4; i++) {
            double a = Math.toRadians(180 + i * 45);
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            c.drawLine(cx + cs * (rr - 9), cy + sn * (rr - 9), cx + cs * (rr - 4.5f), cy + sn * (rr - 4.5f), p);
        }
        double a = Math.toRadians(deg);
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * (rr - 7), cy + (float) Math.sin(a) * (rr - 7), st(p, 4));
        c.drawCircle(cx, cy, 5, fill(p));
        text(c, p, "E", cx - rr + 3, cy + 9, 11, 10);
        text(c, p, "F", cx + rr - 3, cy + 9, 11, 10);
        c.drawPath(drop(cx, cy + 13, 0.25f), fill(p));
    }

    private static boolean pt(Canvas c, Paint p, String n, String arg) {
        boolean a = arg.isEmpty();
        switch (n) {
            case "batdeep": // 电池只剩底下一格，旁边往下的箭头（batdeep:10 在空的地方写次数）
                c.drawRoundRect(r(27, 27, 49, 73), 4, 4, st(p, 3.5f));
                fill(p);
                c.drawRect(33, 21, 43, 27, p);
                c.drawRect(32, 62, 44, 68, p);
                if (!a) text(c, p, arg, 38, 45, 12, 14);
                c.drawLine(66, 28, 66, 56, st(p, 5));
                fp(c, p, 57, 54, 75, 54, 66, 67);
                return true;
            case "batgulp": { // 一口充进一大截：电池里写 +50
                float y = a ? 34 : 40;
                c.drawRoundRect(r(22, y, 66, y + 28), 4, 4, st(p, 3.5f));
                c.drawRect(66, y + 8, 72, y + 20, fill(p));
                text(c, p, "+50", 44, y + 14, 15, 34);
                badge(c, p, arg);
                return true;
            }
            case "recycle1": // 绕一圈的箭头，中间一道闪电
                arcArrow(c, p, 50, 48, 22, -60, 290, 4.5f);
                bolt(c, p, 50, 48, 0.5f);
                return true;
            case "batfull": { // 满格的电池上写 100
                float x = a ? 50 : 43;
                c.drawRoundRect(r(x - 14, 27, x + 14, 73), 4, 4, fill(p));
                c.drawRect(x - 5, 21, x + 5, 27, p);
                k(p);
                text(c, p, "100", x, 51, 13, 22);
                w(p);
                badge(c, p, arg);
                return true;
            }
            case "batshield": // 盾牌里一节电池，留着两格电
                shieldPath(c, p);
                k(p);
                fill(p);
                c.drawRoundRect(r(37, 38, 61, 58), 3, 3, p);
                c.drawRect(61, 43, 65, 53, p);
                w(p);
                c.drawRect(40.5f, 41.5f, 46.5f, 54.5f, p);
                c.drawRect(49.5f, 41.5f, 55.5f, 54.5f, p);
                return true;
            case "tank": // 加满的油桶
                can(c, p, a ? 50 : 44, a ? 48 : 52, true, true, 0);
                badge(c, p, arg);
                return true;
            case "tankgulp": { // 油桶上写 +50
                float x = a ? 50 : 44, y = a ? 48 : 52;
                can(c, p, x, y, true, false, 0);
                k(p);
                text(c, p, "+50", x, y + 7, 13, 25);
                w(p);
                badge(c, p, arg);
                return true;
            }
            case "tankdeep": // 油桶里只剩个底，旁边往下的箭头（tankdeep:10 在空的地方写次数）
                can(c, p, 40, 49, false, false, 0.14f);
                if (!a) text(c, p, arg, 40, 47, 12, 16);
                c.drawLine(68, 28, 68, 56, st(p, 5));
                fp(c, p, 59, 54, 77, 54, 68, 67);
                return true;
            case "tankroad": // 一滴油、开了多少公里、底下一条路
                c.drawPath(drop(50, 24, 0.42f), fill(p));
                text(c, p, arg, 50, 47, 20, 46);
                c.drawRoundRect(r(20, 60, 80, 70), 2, 2, fill(p));
                k(p);
                for (int i = 0; i < 4; i++) c.drawRect(26 + i * 14, 64, 34 + i * 14, 66, p);
                w(p);
                return true;
            case "fuellow": gauge(c, p, 50, 56, 27, 196); return true;
            case "fuelhalf": gauge(c, p, 50, 56, 27, 270); return true;
            case "fuelgauge": gauge(c, p, 50, 56, 27, 344); return true;
            case "tankup": // 油桶快满了，旁边往上的箭头
                can(c, p, 40, 49, false, false, 0.85f);
                c.drawLine(68, 66, 68, 38, st(p, 5));
                fp(c, p, 59, 40, 77, 40, 68, 27);
                return true;
            case "tankup2": // 油桶满了，旁边两层往上的箭头
                can(c, p, 40, 49, false, false, 1);
                c.drawLine(68, 66, 68, 48, st(p, 5));
                fp(c, p, 59, 49, 77, 49, 68, 37);
                fp(c, p, 59, 34, 77, 34, 68, 22);
                return true;
            case "tankshield": // 盾牌里一滴油
                shieldPath(c, p);
                k(p);
                c.drawPath(drop(50, 49, 0.72f), fill(p));
                w(p);
                return true;
            case "tankshield2": // 两层的盾牌，里面一滴油
                c.save();
                c.scale(1.12f, 1.1f, 50, 46);
                Path s = new Path();
                s.moveTo(50, 22); s.lineTo(72, 30); s.lineTo(72, 46);
                s.cubicTo(72, 62, 60, 72, 50, 76); s.cubicTo(40, 72, 28, 62, 28, 46); s.lineTo(28, 30); s.close();
                c.drawPath(s, st(p, 2.8f));
                c.restore();
                c.save();
                c.scale(0.86f, 0.86f, 50, 48);
                shieldPath(c, p);
                c.restore();
                k(p);
                c.drawPath(drop(50, 49, 0.6f), fill(p));
                w(p);
                return true;
            case "tankcar": // 车上面一滴油，底下一条路
                car(c, p, 50, 56, 0.9f);
                c.drawPath(drop(50, 26, 0.5f), fill(p));
                p.setPathEffect(new DashPathEffect(new float[] { 6, 4 }, 0));
                c.drawLine(22, 76, 78, 76, st(p, 3));
                p.setPathEffect(null);
                return true;
            default: return false;
        }
    }
}
