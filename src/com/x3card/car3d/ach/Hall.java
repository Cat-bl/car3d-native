package com.x3card.car3d.ach;

/**
 * 成就墙的排版（主线程点击判断、GL 画都按它，同一组输入算出来的一样）：
 * - 宽的窗口：左边一块总览（进度环、各等级几枚、最近拿到的那枚在聚光灯下转），右边是分类长廊。
 * - 窄的窗口（竖屏）：总览缩成顶上一条，长廊在下面占满宽度。
 * - 长廊一个分类一行：顶上"里程 12 / 18"和一根进度条，下面勋章立在玻璃台面上（台面里有倒影），名字写在台面下面；
 *   上下滑换分类（整行整行地停），左右滑翻这一行。
 * 单位都是窗口像素，y 朝下
 */
final class Hall {
    final float w, h, rem;
    /** 总览缩成顶上一条 */
    final boolean strip;
    /** 总览卡片 */
    final float ovL, ovT, ovR, ovB;
    /** 进度环的中心、半径 */
    final float ringX, ringY, ringR;
    /** 聚光灯下那枚的中心、半径 */
    final float spotX, spotY, spotR;
    /** 长廊的范围（左、右、上），一行多高、一枚占多宽、勋章半径、行首留白 */
    final float gx0, gx1, gy0, rowH, cellW, medalR, padL;

    Hall(float w, float h) {
        this.w = w;
        this.h = h;
        rem = h / 10.52f;
        strip = w / h < 0.95f;
        if (strip) {
            ovL = rem * 0.3f; ovT = rem * 1.12f; ovR = w - rem * 0.3f; ovB = ovT + rem * 1.62f;
            ringR = rem * 0.58f; ringX = ovL + rem * 0.24f + ringR; ringY = (ovT + ovB) / 2;
            spotR = rem * 0.42f; spotX = ovR - rem * 0.3f - spotR * 1.34f; spotY = ringY;
            gx0 = 0; gy0 = ovB + rem * 0.1f;
            cellW = rem * 1.6f; medalR = rem * 0.54f;
            padL = rem * 0.3f;
        } else {
            float ow = Math.max(rem * 3.3f, Math.min(rem * 4.4f, w * 0.28f));
            ovL = rem * 0.35f; ovT = rem * 1.15f; ovR = ovL + ow; ovB = h - rem * 0.35f;
            float cx = (ovL + ovR) / 2;
            ringR = Math.min(ow * 0.29f, rem * 1.02f); ringX = cx; ringY = ovT + rem * 1.45f;
            spotR = Math.min(ow * 0.22f, rem * 0.86f); spotX = cx; spotY = ovT + rem * 5.5f;
            gx0 = ovR + rem * 0.12f; gy0 = rem * 1.15f;
            cellW = rem * 1.74f; medalR = rem * 0.6f;
            padL = rem * 0.28f;
        }
        gx1 = w;
        rowH = rem * 1.6f + medalR * 2.39f;
    }

    // ================= 一行里的位置（sy：上下滚到哪；rx：这一行左右滚到哪） =================
    float rowTop(int r, float sy) { return gy0 + r * rowH - sy; }
    /** 标题那一行的中线 */
    float headY(int r, float sy) { return rowTop(r, sy) + rem * 0.3f; }
    float cy(int r, float sy) { return rowTop(r, sy) + rem * 0.62f + medalR * 1.36f; }
    /** 玻璃台面的上沿（勋章立在上面） */
    float ledgeY(int r, float sy) { return cy(r, sy) + medalR * 1.03f; }
    /** 名字那一行的中线 */
    float labelY(int r, float sy) { return ledgeY(r, sy) + rem * 0.5f; }
    float cx(int k, float rx) { return gx0 + padL + cellW * (k + 0.5f) - rx; }

    /** 上下最多滚多少（n 行） */
    float maxY(int n) { return Math.max(0, n * rowH + rem * 0.15f - (h - gy0)); }
    /** 一行（n 枚）左右最多滚多少 */
    float maxX(int n) { return Math.max(0, padL * 2 + n * cellW - (gx1 - gx0)); }

    /** 上下滚动停在哪：离 to 最近的一行的行首（最后一个停的地方是滚到底） */
    float snapY(float to, int n) {
        float m = maxY(n);
        if (m <= 0) return 0;
        float best = m, bd = Math.abs(to - m);
        for (int r = 0; r * rowH < m; r++) { float d = Math.abs(to - r * rowH); if (d < bd) { bd = d; best = r * rowH; } }
        return best;
    }

    boolean inGallery(float x, float y) { return x >= gx0 && y >= gy0; }
    boolean inSpot(float x, float y) { float dx = x - spotX, dy = y - spotY; return dx * dx + dy * dy < spotR * spotR * 2.2f; }

    /** 手指在第几行（没在长廊里是 −1） */
    int rowAt(float y, float sy, int rows) {
        if (y < gy0) return -1;
        int r = (int) Math.floor((y - gy0 + sy) / rowH);
        return r >= 0 && r < rows ? r : -1;
    }

    /** 点在哪一行的第几枚：{ 行, 第几枚 }，没点到勋章是 null */
    int[] hit(float x, float y, float sy, float[] rx, int[] counts) {
        if (!inGallery(x, y)) return null;
        int r = rowAt(y, sy, counts.length);
        if (r < 0) return null;
        if (y < cy(r, sy) - medalR * 1.3f || y > labelY(r, sy) + rem * 0.2f) return null;
        float rr = r < rx.length ? rx[r] : 0;
        int k = (int) Math.floor((x - gx0 - padL + rr) / cellW);
        return k >= 0 && k < counts[r] ? new int[] { r, k } : null;
    }
}
