package com.x3card.car3d.ach;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.nio.ByteBuffer;

/**
 * 一枚勋章的小图（车况页弹出的提示用，不开 GL）：正面用 Icons.make 的法线和高度图，按等级的金属色、分类的珐琅色在处理器上打光；
 * 外圈凸起的边、背后的花边 / 星芒（银、金、传奇）照勋章墙的形状算法线；底下一圈柔和的影子。光从左上来，同勋章墙的主光方向。
 * back：背面（素的金属面，翻面时转到背面那一下用）
 */
final class Medal2D {
    private Medal2D() { }

    private static final float LX = -0.42f, LY = 0.58f, LZ = 0.70f; // 光的方向（y 朝上）

    static Bitmap make(Ach a, int size, boolean back) {
        ByteBuffer tex = back ? null : Icons.make(a.icon, a.tier, 128);
        int ts = 128;
        float ext = a.tier == Ach.LEGEND ? 1.34f : a.tier == Ach.GOLD ? 1.27f : a.tier == Ach.SILVER ? 1.08f : 1.0f;
        float c0 = size / 2f, R = c0 / (ext * 1.1f);
        float[] base = Palette.TIER[a.tier], en = Palette.ENAMEL[a.cat];
        float rough = base[3];
        float hx = LX, hy = LY, hz = LZ + 1, hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        hx /= hl; hy /= hl; hz /= hl;
        int[] px = new int[size * size];
        float aa = 1 / R; // 一个像素在勋章半径里有多大（边缘抗锯齿）
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - c0) / R, dy = -(y + 0.5f - c0) / R; // y 朝上
                float rr = (float) Math.sqrt(dx * dx + dy * dy);
                float ux = rr > 1e-5f ? dx / rr : 0, uy = rr > 1e-5f ? dy / rr : 0; // 往外的方向
                float nx, ny, nz, metal = 1, cover, g = 0.5f;
                boolean plate = false;
                if (rr <= 1) {
                    cover = Math.min(1, (1 - rr) / aa + 0.5f);
                    if (rr < 0.8f) {
                        if (tex != null) {
                            float u = 0.5f + dx / 1.68f, v = 0.5f - dy / 1.68f;
                            int ix = Math.max(0, Math.min(ts - 1, (int) (u * ts))), iy = Math.max(0, Math.min(ts - 1, (int) (v * ts)));
                            int o = (iy * ts + ix) * 4;
                            nx = (tex.get(o) & 255) / 127.5f - 1; ny = (tex.get(o + 1) & 255) / 127.5f - 1; nz = (tex.get(o + 2) & 255) / 127.5f - 1;
                            float h = (tex.get(o + 3) & 255) / 255f;
                            metal = smooth(0.40f, 0.56f, h);
                            // 珐琅底下的刻花（同勋章墙：波纹放射线；小图上太密时淡掉，不然是一片噪点）
                            float an = (float) Math.atan2(dy, dx) * 60 + (float) Math.sin(rr / 0.84f * 24) * 2.4f;
                            float fade = Math.max(0, Math.min(1, 1.6f - 60 / Math.max(1e-3f, rr * R)));
                            g = 0.5f + 0.5f * (float) Math.sin(an) * fade;
                        } else { nx = 0; ny = 0; nz = 1; }
                    } else if (rr < 0.82f) { nx = -ux * 0.75f; ny = -uy * 0.75f; nz = 0.66f; } // 边的内壁
                    else if (rr < 0.95f) { float t = (rr - 0.885f) / 0.065f; nx = ux * t * 0.35f; ny = uy * t * 0.35f; nz = 1; } // 边顶（圆一点）
                    else { nx = ux * 0.7f; ny = uy * 0.7f; nz = 0.72f; } // 外倒角
                } else {
                    // 背后的花边 / 星芒
                    float edge = 1, sx = 0, sy = 0;
                    double th = Math.atan2(dy, dx);
                    if (a.tier == Ach.SILVER) {
                        edge = 0.97f + 0.11f * (0.5f + 0.5f * (float) Math.cos(10 * th));
                        float k = (rr - 0.88f) * 3.2f;
                        sx = ux * k; sy = uy * k;
                    } else if (a.tier >= Ach.GOLD) {
                        int rays = a.tier == Ach.GOLD ? 12 : 24;
                        double step = 2 * Math.PI / rays, rel = th - Math.PI / 2;
                        int ri = (int) Math.round(rel / step);
                        double off = rel - ri * step;
                        int idx = ((ri % rays) + rays) % rays;
                        float tip = a.tier == Ach.GOLD ? 1.27f : (idx % 2 == 0 ? 1.34f : 1.13f), wd = a.tier == Ach.GOLD ? 0.26f : (idx % 2 == 0 ? 0.34f : 0.26f);
                        double hw = step * wd;
                        float f = (float) Math.max(0, 1 - Math.abs(off) / hw);
                        edge = 0.97f + (tip - 0.97f) * f;
                        // 两个刻面：从中间的棱往两边斜下去
                        float tx = -uy, ty = ux, side = off > 0 ? 1 : -1;
                        sx = ux * 0.25f - tx * side * 0.55f; sy = uy * 0.25f - ty * side * 0.55f;
                    } else edge = 0;
                    if (rr > edge + aa) { px[y * size + x] = 0; continue; }
                    cover = Math.min(1, (edge - rr) / aa + 0.5f);
                    nx = sx; ny = sy; nz = 1;
                    plate = true;
                }
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= nl; ny /= nl; nz /= nl;
                float diff = Math.max(0, nx * LX + ny * LY + nz * LZ);
                float nh = Math.max(0, nx * hx + ny * hy + nz * hz);
                // 反射方向朝上的多亮（同勋章墙的摄影棚：上亮下暗，加一块左上的柔光箱）
                float ry = 2 * nz * ny, rz = 2 * nz * nz - 1, rx = 2 * nz * nx;
                float sky = 0.08f + 0.5f * smooth(-0.4f, 0.8f, ry) + 1.6f * smooth(0.55f, 0.95f, (rx * LX + ry * LY + rz * LZ));
                float rm = Math.max(0.06f, metal > 0.5f ? rough * 0.8f : 0.12f), shin = 2 / (rm * rm);
                float spec = (float) Math.pow(nh, shin) * (shin + 2) / 8 * 0.06f;
                float[] col = new float[3];
                for (int k = 0; k < 3; k++) {
                    float m = base[k] * (0.16f + 0.38f * diff + 0.55f * sky) + spec * (base[k] * 0.7f + 0.3f);
                    float e = en[k] * (0.7f + 0.3f * g) * (0.28f + 0.72f * diff);
                    float c = e + (m - e) * metal;
                    if (plate) c *= 0.92f;
                    col[k] = c;
                }
                // 珐琅上的透明釉：平的一层高光
                if (metal < 1) { float cs = (float) Math.pow(Math.max(0, hz), 160) * 0.8f * (1 - metal); for (int k = 0; k < 3; k++) col[k] += cs; }
                int a8 = Math.round(cover * 255);
                int r8 = enc(col[0]), g8 = enc(col[1]), b8 = enc(col[2]);
                px[y * size + x] = (a8 << 24) | (r8 * a8 / 255 << 16) | (g8 * a8 / 255 << 8) | (b8 * a8 / 255);
            }
        }
        Bitmap fg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        fg.setPremultiplied(true);
        fg.setPixels(px, 0, size, 0, 0, size, size);
        // 影子：同一个形状模糊一下、往下挪一点
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bp = new Paint();
        bp.setMaskFilter(new BlurMaskFilter(Math.max(1, size * 0.035f), BlurMaskFilter.Blur.NORMAL));
        int[] off = new int[2];
        Bitmap sh = fg.extractAlpha(bp, off);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(0x59000000);
        c.drawBitmap(sh, off[0], off[1] + size * 0.03f, sp);
        sh.recycle();
        c.drawBitmap(fg, 0, 0, null);
        fg.recycle();
        return out;
    }

    private static float smooth(float a, float b, float x) { float t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

    /** 线性 → sRGB（先轻轻压一下高光，不然金属亮处一片白） */
    private static int enc(float c) {
        c = c / (1 + c * 0.35f) * 1.2f;
        return Math.max(0, Math.min(255, Math.round((float) Math.pow(Math.max(0, c), 1 / 2.2) * 255)));
    }
}
