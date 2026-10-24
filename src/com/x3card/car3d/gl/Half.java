package com.x3card.car3d.gl;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 半精度浮点和 RGBE 环境图（_tools/gen/env-pmrem.mjs 存的 three r149 PMREM cubeUV 图）→ 半精度浮点贴图 */
public final class Half {
    private Half() {}

    /** float → half（就近舍入，同 three 的 DataUtils.toHalfFloat 的结果范围） */
    public static short toHalf(float f) {
        int b = Float.floatToRawIntBits(f), s = (b >>> 16) & 0x8000, e = ((b >>> 23) & 0xff) - 127 + 15, m = b & 0x7fffff;
        if (((b >>> 23) & 0xff) == 0xff) return (short) (s | 0x7c00 | (m != 0 ? 0x200 : 0));
        if (e >= 0x1f) return (short) (s | 0x7c00);
        if (e <= 0) {
            if (e < -10) return (short) s;
            m |= 0x800000;
            int sh = 14 - e, h = m >> sh, rem = m & ((1 << sh) - 1), half = 1 << (sh - 1);
            if (rem > half || (rem == half && (h & 1) != 0)) h++;
            return (short) (s | h);
        }
        int h = (e << 10) | (m >> 13), rem = m & 0x1fff;
        if (rem > 0x1000 || (rem == 0x1000 && (h & 1) != 0)) h++;
        return (short) (s | h);
    }

    /** 读 RGBE 文件（'RGBE' + 宽 + 高 + 像素，行序同 GL）→ 半精度浮点 RGBA 贴图（线性过滤、不生成多级，同 PMREM 的离屏缓冲） */
    public static Tex rgbe(InputStream in) throws Exception {
        byte[] all = readAll(in);
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 12 || all[0] != 'R' || all[1] != 'G' || all[2] != 'B' || all[3] != 'E') throw new IllegalArgumentException("不是 RGBE 环境图");
        int w = b.getInt(4), h = b.getInt(8);
        ByteBuffer out = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder());
        short[] px = new short[w * h * 4];
        short[][] tab = new short[256][];
        short one = toHalf(1f);
        for (int i = 0; i < w * h; i++) {
            int o = 12 + i * 4, ex = all[o + 3] & 255;
            short[] t = tab[ex];
            if (t == null) { // 这个指数下 0～255 的尾数对应的半精度（同 toHalf(尾数 × 2^(指数-128) / 255)）
                t = tab[ex] = new short[256];
                float k = ex == 0 ? 0 : (float) Math.scalb(1.0, ex - 128) / 255f;
                for (int m = 0; m < 256; m++) t[m] = toHalf(m * k);
            }
            px[i * 4] = t[all[o] & 255]; px[i * 4 + 1] = t[all[o + 1] & 255]; px[i * 4 + 2] = t[all[o + 2] & 255]; px[i * 4 + 3] = one;
        }
        out.asShortBuffer().put(px);
        out.position(0);
        Tex t = new Tex(out, w, h);
        t.type = android.opengl.GLES30.GL_HALF_FLOAT;
        t.generateMipmaps = false;
        t.minFilter = t.magFilter = android.opengl.GLES30.GL_LINEAR;
        t.ud.put("cubeUV", true);
        return t;
    }
    public static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
