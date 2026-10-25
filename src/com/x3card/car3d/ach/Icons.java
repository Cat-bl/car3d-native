package com.x3card.car3d.ach;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 勋章正面的浮雕：每个成就一个图案（矢量画在 100×100 的格子里，正面的圆心 (50, 50)，图案本身以 (50, 48) 为中心画）。
 * 版面：外面一圈凸起的金属环带（中间一道槽，槽里一圈小珠子；传奇在环带上刻月桂叶），里面是珐琅地子，图案凸起在当中，
 * 下面是等级的星星（铜 1 颗、银 2 颗、金 3 颗、传奇 4 颗）。没解锁的勋章是一个问号（Wall.HIDDEN），不画等级。
 * 白 = 凸起的金属，黑 = 凹下去的珐琅；先按两倍大画，用距离变换算出边上的倒角（金属的边是圆角斜面、顶上是平的，
 * 珐琅比边低一点再平下去），缩回原大小，再算法线。
 * 输出 RGBA 字节：RGB 是切线空间的法线、A 是高度（着色器按高度分金属 / 珐琅、算缝里的阴影）。
 * 这里是最早的一批图案，后来加的在 IconArt
 */
final class Icons {
    private Icons() { }

    /** 环带的内外半径（格子单位）；里面是珐琅地子 */
    static final float BAND_IN = 41.6f, BAND_OUT = 47.4f;

    /** 生成一张 size×size 的法线 + 高度图（RGBA，行从上往下）；tier −1 不画等级记号 */
    static ByteBuffer make(String icon, int tier, int size) {
        int n = size * 2;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(n / 100f, n / 100f);
        c.drawColor(0xff000000); // 黑底 = 凹下去的珐琅；白的凸起，图案里挖空的地方用黑笔画
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff);
        band(c, p, tier);
        // 图案缩一点放进地子里（原来的图案画到半径 34 左右）
        c.save();
        c.scale(0.9f, 0.9f, 50, 47);
        p.setColor(0xffffffff);
        p.setStyle(Paint.Style.FILL);
        draw(c, p, icon);
        c.restore();
        p.setColor(0xffffffff);
        if (tier >= 0) marks(c, p, tier);
        int[] px = new int[n * n];
        b.getPixels(px, 0, n, 0, 0, n, n);
        b.recycle();
        float[] h = relief(px, n, n * 0.018f, n * 0.012f);
        px = null;
        // 缩回原大小（2×2 平均，顺便抗锯齿）
        float[] hs = new float[size * size];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                hs[y * size + x] = (h[(2 * y) * n + 2 * x] + h[(2 * y) * n + 2 * x + 1] + h[(2 * y + 1) * n + 2 * x] + h[(2 * y + 1) * n + 2 * x + 1]) * 0.25f;
        ByteBuffer out = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        float k = size / 28f; // 坡度换法线的强度（倒角宽度按比例变，所以各种大小一样陡）
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float hl = hs[y * size + Math.max(0, x - 1)], hr = hs[y * size + Math.min(size - 1, x + 1)];
                float hu = hs[Math.max(0, y - 1) * size + x], hd = hs[Math.min(size - 1, y + 1) * size + x];
                float nx = -(hr - hl) * k, ny = (hd - hu) * k, nz = 1;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                out.put((byte) Math.round((nx / l * 0.5f + 0.5f) * 255));
                out.put((byte) Math.round((ny / l * 0.5f + 0.5f) * 255));
                out.put((byte) Math.round((nz / l * 0.5f + 0.5f) * 255));
                out.put((byte) Math.round(Math.max(0, Math.min(1, hs[y * size + x])) * 255));
            }
        }
        out.position(0);
        return out;
    }

    /**
     * 高度：金属（白）离边越远越高，宽 bev 的圆角斜面后是平顶（1.0）；珐琅（黑）在边上是 0.5，宽 bev2 内降到 0.3 再平着。
     * 距离用精确的欧氏距离变换（Felzenszwalb）
     */
    private static float[] relief(int[] px, int n, float bev, float bev2) {
        boolean[] white = new boolean[n * n];
        for (int i = 0; i < white.length; i++) white[i] = ((px[i] >> 16) & 0xff) > 127;
        float[] dIn = edt(white, n, false), dOut = edt(white, n, true);
        float[] h = new float[n * n];
        for (int i = 0; i < h.length; i++) {
            if (white[i]) {
                float t = Math.min(1, ((float) Math.sqrt(dIn[i]) - 0.5f) / bev);
                h[i] = 0.5f + 0.5f * (float) Math.sin(Math.max(0, t) * Math.PI / 2);
            } else {
                float t = Math.min(1, ((float) Math.sqrt(dOut[i]) - 0.5f) / bev2);
                h[i] = 0.5f - 0.2f * Math.max(0, t) * (2 - Math.max(0, t));
            }
        }
        return h;
    }

    /** 平方欧氏距离：到最近的"另一种"像素（toWhite 为真时算黑像素到最近白像素，否则白到黑） */
    private static float[] edt(boolean[] white, int n, boolean toWhite) {
        final float INF = 1e12f;
        float[] g = new float[n * n];
        for (int i = 0; i < g.length; i++) g[i] = white[i] == toWhite ? 0 : INF;
        float[] f = new float[n], d = new float[n], z = new float[n + 1];
        int[] v = new int[n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) f[y] = g[y * n + x];
            dt1(f, d, v, z, n);
            for (int y = 0; y < n; y++) g[y * n + x] = d[y];
        }
        for (int y = 0; y < n; y++) {
            System.arraycopy(g, y * n, f, 0, n);
            dt1(f, d, v, z, n);
            System.arraycopy(d, 0, g, y * n, n);
        }
        return g;
    }

    /** 一维平方距离变换（下包络抛物线） */
    private static void dt1(float[] f, float[] d, int[] v, float[] z, int n) {
        int k = 0;
        v[0] = 0;
        z[0] = -Float.MAX_VALUE;
        z[1] = Float.MAX_VALUE;
        for (int q = 1; q < n; q++) {
            float s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]);
            while (s <= z[k]) { k--; s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2f * q - 2f * v[k]); }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Float.MAX_VALUE;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            float dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    /** 外圈的环带：凸起的金属，中间一道槽、槽里一圈小珠子；传奇的环带上刻一圈月桂叶 */
    private static void band(Canvas c, Paint p, int tier) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xffffffff);
        c.drawCircle(50, 50, BAND_OUT, p);
        p.setColor(0xff000000);
        c.drawCircle(50, 50, BAND_IN, p);
        float mid = (BAND_IN + BAND_OUT) / 2;
        if (tier == Ach.LEGEND) {
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < 11; i++) {
                    double a = Math.toRadians(98 + i * 15.5);
                    float cx = 50 + side * (float) Math.cos(a) * mid, cy = 50 + (float) Math.sin(a) * mid;
                    c.save();
                    c.translate(cx, cy);
                    c.rotate((float) Math.toDegrees(a) * side + (side > 0 ? 0 : 180) + 90 + side * 28);
                    c.drawOval(new RectF(-1.1f, -2.5f, 1.1f, 2.5f), p);
                    c.restore();
                }
            }
            star(c, p, 50, 50 - mid, 2.2f);
            p.setColor(0xffffffff);
            return;
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        c.drawCircle(50, 50, mid, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xffffffff);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            c.drawCircle(50 + (float) Math.cos(a) * mid, 50 + (float) Math.sin(a) * mid, 0.85f, p);
        }
    }

    /** 等级记号：地子下面的小星星（铜 1、银 2、金 3、传奇 4） */
    private static void marks(Canvas c, Paint p, int tier) {
        p.setStyle(Paint.Style.FILL);
        int k = tier + 1;
        for (int i = 0; i < k; i++) star(c, p, 50 + (i - (k - 1) / 2f) * 6.4f, 81.5f, 2.6f);
    }

    static void star(Canvas c, Paint p, float cx, float cy, float r) {
        Path s = new Path();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
            if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
        }
        s.close();
        c.drawPath(s, p);
    }

    static void text(Canvas c, Paint p, String s, float cx, float cy, float size) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2, p);
    }

    static Paint stroke(Paint p, float w) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); return p; }
    static Paint fill(Paint p) { p.setStyle(Paint.Style.FILL); return p; }

    /** 各个图案（都在半径约 30 的范围里，下面留给等级记号） */
    private static void draw(Canvas c, Paint p, String icon) {
        Path a = new Path();
        switch (icon) {
            case "road": // 一条往远处去的路
                a.moveTo(30, 76); a.lineTo(46, 22); a.lineTo(54, 22); a.lineTo(70, 76); a.close();
                fill(p); c.drawPath(a, p);
                p.setColor(0xff000000);
                for (int i = 0; i < 4; i++) { float y0 = 30 + i * 12, w0 = 0.8f + i * 0.5f; c.drawRect(50 - w0, y0, 50 + w0, y0 + 6 + i, p); }
                p.setColor(0xffffffff);
                break;
            case "10k": text(c, p, "1万", 50, 48, 30); break;
            case "100k": text(c, p, "10万", 50, 48, 26); break;
            case "wheel": // 方向盘
                c.drawCircle(50, 48, 24, stroke(p, 6));
                c.drawCircle(50, 48, 7, fill(p));
                stroke(p, 5);
                c.drawLine(28, 46, 43, 48, p); c.drawLine(72, 46, 57, 48, p); c.drawLine(50, 55, 50, 70, p);
                break;
            case "globe":
                c.drawCircle(50, 48, 25, stroke(p, 3.5f));
                c.drawOval(new RectF(39, 23, 61, 73), p);
                c.drawLine(50, 23, 50, 73, p);
                c.drawLine(26, 48, 74, 48, p);
                c.drawArc(new RectF(28, 30, 72, 44), 0, 180, false, p);
                c.drawArc(new RectF(28, 52, 72, 66), 180, 180, false, p);
                break;
            case "moon":
                a.addCircle(46, 48, 24, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(58, 42, 21, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                c.drawPath(a, fill(p));
                star(c, p, 66, 62, 5); star(c, p, 70, 28, 3.5f);
                break;
            case "flag": case "flag2": case "flag3": { // 方格旗（几面旗）
                int n = icon.equals("flag") ? 1 : icon.equals("flag2") ? 2 : 3;
                for (int k = 0; k < n; k++) {
                    float ox = (k - (n - 1) / 2f) * 12, oy = Math.abs(k - (n - 1) / 2f) * 6;
                    c.drawLine(36 + ox, 26 + oy, 36 + ox, 74, stroke(p, 3));
                    fill(p);
                    for (int i = 0; i < 4; i++) for (int j = 0; j < 3; j++) if ((i + j) % 2 == 0) c.drawRect(37.5f + ox + i * 7, 27 + oy + j * 6.5f, 44.5f + ox + i * 7, 33.5f + oy + j * 6.5f, p);
                    c.drawRect(37.5f + ox, 27 + oy, 65.5f + ox, 46.5f + oy, stroke(p, 1.2f));
                }
                break;
            }
            case "route":
                stroke(p, 4);
                p.setPathEffect(new android.graphics.DashPathEffect(new float[] { 5, 4 }, 0));
                a.moveTo(30, 68); a.cubicTo(30, 44, 70, 60, 68, 32);
                c.drawPath(a, p);
                p.setPathEffect(null);
                pin(c, p, 68, 32, 9);
                c.drawCircle(30, 68, 5, fill(p));
                break;
            case "sun":
                c.drawCircle(50, 48, 13, fill(p));
                stroke(p, 4);
                for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6; c.drawLine(50 + (float) Math.cos(t) * 19, 48 + (float) Math.sin(t) * 19, 50 + (float) Math.cos(t) * 27, 48 + (float) Math.sin(t) * 27, p); }
                break;
            case "bolt": case "bolt2":
                a.moveTo(56, 20); a.lineTo(34, 52); a.lineTo(48, 52); a.lineTo(42, 78); a.lineTo(66, 44); a.lineTo(52, 44); a.close();
                fill(p); c.drawPath(a, p);
                if (icon.equals("bolt2")) c.drawCircle(50, 49, 29, stroke(p, 3));
                break;
            case "owl":
                fill(p);
                a.moveTo(28, 30); a.lineTo(36, 40); a.lineTo(64, 40); a.lineTo(72, 30); a.lineTo(72, 64); a.quadTo(50, 82, 28, 64); a.close();
                c.drawPath(a, p);
                p.setColor(0xff000000);
                c.drawCircle(40, 52, 8, p); c.drawCircle(60, 52, 8, p);
                p.setColor(0xffffffff);
                c.drawCircle(40, 52, 3.5f, p); c.drawCircle(60, 52, 3.5f, p);
                a.reset(); a.moveTo(46, 60); a.lineTo(54, 60); a.lineTo(50, 67); a.close();
                c.drawPath(a, p);
                break;
            case "cal7": case "cal30":
                c.drawRoundRect(new RectF(28, 30, 72, 72), 5, 5, stroke(p, 3.5f));
                fill(p);
                c.drawRect(28, 30, 72, 40, p);
                c.drawRect(36, 24, 40, 34, p); c.drawRect(60, 24, 64, 34, p);
                text(c, p, icon.equals("cal7") ? "7" : "30", 50, 57, icon.equals("cal7") ? 22 : 19);
                break;
            case "clock":
                c.drawCircle(50, 48, 25, stroke(p, 4));
                c.drawLine(50, 48, 50, 32, p); c.drawLine(50, 48, 62, 54, p);
                for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6; c.drawPoint(50 + (float) Math.cos(t) * 19, 48 + (float) Math.sin(t) * 19, p); }
                break;
            case "leaf": case "leaf2": case "spark":
                a.moveTo(30, 70); a.cubicTo(26, 40, 46, 24, 72, 24); a.cubicTo(74, 50, 58, 72, 30, 70); a.close();
                fill(p); c.drawPath(a, p);
                p.setColor(0xff000000);
                c.drawLine(32, 68, 62, 36, stroke(p, 2.4f));
                p.setColor(0xffffffff);
                if (icon.equals("leaf2")) { fill(p); star(c, p, 72, 66, 6); }
                if (icon.equals("spark")) { fill(p); Path z = new Path(); z.moveTo(70, 52); z.lineTo(62, 66); z.lineTo(68, 66); z.lineTo(64, 78); z.lineTo(74, 62); z.lineTo(68, 62); z.close(); c.drawPath(z, p); }
                break;
            case "plug": case "plug2":
                fill(p);
                c.drawRoundRect(new RectF(36, 36, 64, 58), 4, 4, p);
                c.drawRect(41, 24, 45, 37, p); c.drawRect(55, 24, 59, 37, p);
                c.drawRect(47, 57, 53, 66, p);
                a.moveTo(50, 66); a.quadTo(50, 76, 40, 76);
                c.drawPath(a, stroke(p, 4));
                if (icon.equals("plug2")) { p.setColor(0xff000000); text(c, p, "50", 50, 47, 12); p.setColor(0xffffffff); }
                break;
            case "battery":
                c.drawRoundRect(new RectF(26, 34, 70, 62), 4, 4, stroke(p, 4));
                fill(p);
                c.drawRect(70, 42, 76, 54, p);
                for (int i = 0; i < 4; i++) c.drawRect(31 + i * 9.5f, 39, 38 + i * 9.5f, 57, p);
                break;
            case "heart":
                a.moveTo(50, 72); a.cubicTo(20, 50, 28, 26, 50, 38); a.cubicTo(72, 26, 80, 50, 50, 72); a.close();
                fill(p); c.drawPath(a, p);
                p.setColor(0xff000000);
                stroke(p, 4);
                c.drawLine(50, 42, 50, 58, p); c.drawLine(42, 50, 58, 50, p);
                p.setColor(0xffffffff);
                break;
            case "mount": case "mount2": case "mount3": {
                int n = icon.equals("mount") ? 1 : icon.equals("mount2") ? 2 : 3;
                fill(p);
                if (n >= 2) { a.reset(); a.moveTo(22, 70); a.lineTo(38, 40); a.lineTo(52, 70); a.close(); c.drawPath(a, p); }
                if (n >= 3) { a.reset(); a.moveTo(48, 70); a.lineTo(64, 42); a.lineTo(80, 70); a.close(); c.drawPath(a, p); }
                a.reset(); a.moveTo(30, 70); a.lineTo(50, 26); a.lineTo(70, 70); a.close(); c.drawPath(a, p);
                p.setColor(0xff000000);
                a.reset(); a.moveTo(43, 41); a.lineTo(50, 26); a.lineTo(57, 41); a.lineTo(53, 38); a.lineTo(50, 43); a.lineTo(47, 38); a.close();
                c.drawPath(a, stroke(p, 1.5f));
                p.setColor(0xffffffff);
                break;
            }
            case "snow":
                stroke(p, 3.5f);
                for (int i = 0; i < 6; i++) {
                    double t = i * Math.PI / 3;
                    float ex = 50 + (float) Math.cos(t) * 26, ey = 48 + (float) Math.sin(t) * 26;
                    c.drawLine(50, 48, ex, ey, p);
                    for (int s2 = -1; s2 <= 1; s2 += 2) {
                        double t2 = t + s2 * 0.6;
                        float mx = 50 + (float) Math.cos(t) * 16, my = 48 + (float) Math.sin(t) * 16;
                        c.drawLine(mx, my, mx + (float) Math.cos(t2) * 7, my + (float) Math.sin(t2) * 7, p);
                    }
                }
                break;
            case "compass":
                c.drawCircle(50, 48, 25, stroke(p, 3));
                fill(p);
                a.moveTo(50, 26); a.lineTo(56, 48); a.lineTo(50, 70); a.lineTo(44, 48); a.close();
                c.drawPath(a, p);
                p.setColor(0xff000000);
                c.drawCircle(50, 48, 3, p);
                p.setColor(0xffffffff);
                break;
            case "arrows":
                fill(p);
                a.moveTo(40, 22); a.lineTo(52, 38); a.lineTo(44, 38); a.lineTo(44, 74); a.lineTo(36, 74); a.lineTo(36, 38); a.lineTo(28, 38); a.close();
                a.moveTo(60, 74); a.lineTo(72, 58); a.lineTo(64, 58); a.lineTo(64, 22); a.lineTo(56, 22); a.lineTo(56, 58); a.lineTo(48, 58); a.close();
                c.drawPath(a, p);
                break;
            case "note":
                fill(p);
                c.drawOval(new RectF(28, 58, 44, 70), p); c.drawOval(new RectF(54, 52, 70, 64), p);
                c.drawRect(40, 28, 44, 64, p); c.drawRect(66, 22, 70, 58, p);
                a.moveTo(40, 28); a.lineTo(70, 22); a.lineTo(70, 30); a.lineTo(40, 36); a.close();
                c.drawPath(a, p);
                break;
            case "mirror": text(c, p, "12321", 50, 48, 15); c.drawLine(50, 30, 50, 66, stroke(p, 1.5f)); break;
            case "dice":
                c.drawRoundRect(new RectF(28, 26, 72, 70), 8, 8, fill(p));
                p.setColor(0xff000000);
                for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) c.drawCircle(39 + j * 22, 36 + i * 12, 3.6f, p);
                p.setColor(0xffffffff);
                break;
            case "steps":
                fill(p);
                for (int i = 0; i < 4; i++) c.drawRect(28 + i * 11, 66 - i * 10, 37 + i * 11, 70, p);
                break;
            case "gauge":
                c.drawArc(new RectF(24, 26, 76, 78), 160, 220, false, stroke(p, 4));
                for (int i = 0; i <= 8; i++) { double t = Math.toRadians(160 + i * 27.5); c.drawLine(50 + (float) Math.cos(t) * 20, 52 + (float) Math.sin(t) * 20, 50 + (float) Math.cos(t) * 24, 52 + (float) Math.sin(t) * 24, stroke(p, 2.5f)); }
                stroke(p, 4);
                c.drawLine(50, 52, 64, 36, p);
                c.drawCircle(50, 52, 4, fill(p));
                break;
            case "firework":
                stroke(p, 3);
                for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6; c.drawLine(50 + (float) Math.cos(t) * 9, 46 + (float) Math.sin(t) * 9, 50 + (float) Math.cos(t) * 24, 46 + (float) Math.sin(t) * 24, p); }
                fill(p);
                for (int i = 0; i < 12; i++) { double t = i * Math.PI / 6 + Math.PI / 12; c.drawCircle(50 + (float) Math.cos(t) * 28, 46 + (float) Math.sin(t) * 28, 1.8f, p); }
                c.drawCircle(50, 46, 4, p);
                break;
            case "lantern":
                fill(p);
                c.drawOval(new RectF(30, 32, 70, 68), p);
                c.drawRect(42, 26, 58, 32, p); c.drawRect(42, 68, 58, 73, p);
                c.drawLine(50, 18, 50, 26, stroke(p, 2));
                p.setColor(0xff000000);
                stroke(p, 1.6f);
                c.drawOval(new RectF(40, 32, 60, 68), p);
                c.drawLine(50, 32, 50, 68, p);
                p.setColor(0xffffffff);
                fill(p);
                for (int i = 0; i < 3; i++) c.drawRect(46 + i * 3, 73, 47 + i * 3, 80, p);
                break;
            case "star": star(c, fill(p), 50, 48, 26); break;
            case Wall.HIDDEN: text(c, p, "?", 50, 47, 50); break;
            default: if (!IconArt.draw(c, p, icon)) c.drawCircle(50, 48, 20, fill(p)); break;
        }
        fill(p);
    }

    /** 地图定位针：尖在 (x, y)，高 s */
    static void pin(Canvas c, Paint p, float x, float y, float s) {
        fill(p);
        Path a = new Path();
        float r = s * 0.42f, cy = y - s + r;
        a.addCircle(x, cy, r, Path.Direction.CW);
        Path t = new Path();
        t.moveTo(x - r * 0.86f, cy + r * 0.5f); t.lineTo(x, y); t.lineTo(x + r * 0.86f, cy + r * 0.5f); t.close();
        a.op(t, Path.Op.UNION);
        Path hole = new Path();
        hole.addCircle(x, cy, r * 0.42f, Path.Direction.CW);
        a.op(hole, Path.Op.DIFFERENCE);
        c.drawPath(a, p);
    }
}
