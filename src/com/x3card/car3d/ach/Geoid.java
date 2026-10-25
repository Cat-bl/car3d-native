package com.x3card.car3d.ach;

import android.content.Context;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 大地水准面差距（EGM96，1° 网格，_tools/gen/geoid.py 生成的 geoid.bin）：GPS 给的是 WGS84 椭球高，海拔 = 椭球高 − 差距。
 * 网格纬度 90 → -90（181 行）、经度 -180 → 179（360 列），int16 单位 0.1 米，双线性插值。读不到时差距当 0（显示的海拔差几十米）
 */
final class Geoid {
    private static short[] g;

    static void load(Context c) {
        if (g != null) return;
        try (InputStream in = c.getAssets().open("geoid.bin")) {
            byte[] b = new byte[181 * 360 * 2];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            if (n != b.length) return;
            short[] s = new short[181 * 360];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
            g = s;
        } catch (Exception ignored) { /* 没有就按 0 */ }
    }

    static boolean ok() { return g != null; }

    /** 差距（米） */
    static double n(double lat, double lon) {
        short[] s = g;
        if (s == null || Double.isNaN(lat) || Double.isNaN(lon)) return 0;
        double r = (90 - Math.max(-90, Math.min(90, lat)));
        double c = ((lon + 180) % 360 + 360) % 360;
        int r0 = Math.min(179, (int) Math.floor(r)), c0 = (int) Math.floor(c) % 360, c1 = (c0 + 1) % 360;
        double fr = r - r0, fc = c - Math.floor(c);
        double a = s[r0 * 360 + c0], b = s[r0 * 360 + c1], d = s[(r0 + 1) * 360 + c0], e = s[(r0 + 1) * 360 + c1];
        return ((a * (1 - fc) + b * fc) * (1 - fr) + (d * (1 - fc) + e * fc) * fr) / 10.0;
    }
}
