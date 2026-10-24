package com.x3card.car3d;

import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/** SVG 的路径数据（d 属性）→ 安卓 Path：M L H V C S Q T A Z 和小写的相对写法（车况图标、系统图标用，同网页版的 svg） */
final class SvgPath {
    private SvgPath() {}
    private static final Map<String, Path> CACHE = new HashMap<>();

    static synchronized Path of(String d) {
        Path p = CACHE.get(d);
        if (p == null) { p = parse(d); CACHE.put(d, p); }
        return p;
    }

    private static Path parse(String d) {
        Path p = new Path();
        int[] at = { 0 };
        float cx = 0, cy = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 0, prev = 0;
        while (true) {
            skip(d, at);
            if (at[0] >= d.length()) break;
            char ch = d.charAt(at[0]);
            if (Character.isLetter(ch)) { cmd = ch; at[0]++; }
            else if (cmd == 0) break;
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    p.moveTo(x, y); cx = sx = x; cy = sy = y;
                    cmd = rel ? 'l' : 'L'; // 后面跟着的坐标当 L
                    lcx = cx; lcy = cy;
                    prev = 'M';
                    continue;
                }
                case 'L': { float x = num(d, at) + ox, y = num(d, at) + oy; p.lineTo(x, y); cx = x; cy = y; break; }
                case 'H': { float x = num(d, at) + ox; p.lineTo(x, cy); cx = x; break; }
                case 'V': { float y = num(d, at) + oy; p.lineTo(cx, y); cy = y; break; }
                case 'C': {
                    float x1 = num(d, at) + ox, y1 = num(d, at) + oy, x2 = num(d, at) + ox, y2 = num(d, at) + oy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y; prev = 'C';
                    continue;
                }
                case 'S': {
                    float x1 = prev == 'C' ? 2 * cx - lcx : cx, y1 = prev == 'C' ? 2 * cy - lcy : cy;
                    float x2 = num(d, at) + ox, y2 = num(d, at) + oy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y; prev = 'C';
                    continue;
                }
                case 'Q': {
                    float x1 = num(d, at) + ox, y1 = num(d, at) + oy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y; prev = 'Q';
                    continue;
                }
                case 'T': {
                    float x1 = prev == 'Q' ? 2 * cx - lcx : cx, y1 = prev == 'Q' ? 2 * cy - lcy : cy, x = num(d, at) + ox, y = num(d, at) + oy;
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y; prev = 'Q';
                    continue;
                }
                case 'A': {
                    float rx = num(d, at), ry = num(d, at), rot = num(d, at);
                    int large = flag(d, at), sweep = flag(d, at);
                    float x = num(d, at) + ox, y = num(d, at) + oy;
                    arc(p, cx, cy, x, y, rx, ry, rot, large != 0, sweep != 0);
                    cx = x; cy = y;
                    break;
                }
                case 'Z': { p.close(); cx = sx; cy = sy; prev = 'Z'; continue; }
                default: at[0]++;
            }
            prev = Character.toUpperCase(cmd);
        }
        return p;
    }
    private static void skip(String d, int[] at) { while (at[0] < d.length() && (d.charAt(at[0]) == ' ' || d.charAt(at[0]) == ',' || d.charAt(at[0]) == '\n' || d.charAt(at[0]) == '\t')) at[0]++; }
    /** 读一个数（SVG 的写法：.5、-.3、1e-3，两个数可以直接连着写，比如 1.2.5 是 1.2 和 .5） */
    private static float num(String d, int[] at) {
        skip(d, at);
        int s = at[0], i = s;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false, exp = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') { i++; continue; }
            if (c == '.' && !dot && !exp) { dot = true; i++; continue; }
            if ((c == 'e' || c == 'E') && !exp) { exp = true; i++; if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++; continue; }
            break;
        }
        at[0] = i;
        try { return Float.parseFloat(d.substring(s, i)); } catch (Exception e) { return 0; }
    }
    /** 圆弧的两个标志位（0 / 1，可以和后面的数连着写） */
    private static int flag(String d, int[] at) { skip(d, at); if (at[0] < d.length()) { char c = d.charAt(at[0]); at[0]++; return c == '1' ? 1 : 0; } return 0; }
    /** SVG 圆弧（端点参数）→ 中心参数，用 arcTo 画（同 SVG 规范附录 F.6） */
    private static void arc(Path p, float x1, float y1, float x2, float y2, float rx, float ry, float rotDeg, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0 || (x1 == x2 && y1 == y2)) { p.lineTo(x2, y2); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0, x1p = cos * dx + sin * dy, y1p = -sin * dx + cos * dy;
        double lam = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lam > 1) { double s = Math.sqrt(lam); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p, den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double co = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = co * rx * y1p / ry, cyp = -co * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double t1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx), t2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx), dt = t2 - t1;
        if (sweep && dt < 0) dt += 2 * Math.PI;
        else if (!sweep && dt > 0) dt -= 2 * Math.PI;
        if (rotDeg == 0) {
            p.arcTo(new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry)), (float) Math.toDegrees(t1), (float) Math.toDegrees(dt), false);
            return;
        }
        int n = (int) Math.ceil(Math.abs(dt) / (Math.PI / 16)); // 斜的椭圆：分成小段画
        for (int i = 1; i <= n; i++) {
            double t = t1 + dt * i / n, ex = rx * Math.cos(t), ey = ry * Math.sin(t);
            p.lineTo((float) (cos * ex - sin * ey + cx), (float) (sin * ex + cos * ey + cy));
        }
    }
}
