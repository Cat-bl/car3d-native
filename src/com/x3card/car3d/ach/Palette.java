package com.x3card.car3d.ach;

/** 勋章的颜色：等级的金属（线性色 + 粗糙度）、分类的珐琅（线性色 + 界面用的 sRGB 色） */
final class Palette {
    private Palette() { }

    /** 铜、银、金、传奇（铂金，着色器里再加一点随角度变的彩虹色） */
    static final float[][] TIER = {
        { 0.80f, 0.47f, 0.27f, 0.30f },
        { 0.90f, 0.91f, 0.93f, 0.20f },
        { 1.00f, 0.76f, 0.33f, 0.17f },
        { 0.86f, 0.88f, 0.93f, 0.13f },
    };

    /** 里程蓝、旅途青、驾驶品红（橙色和铜色挨着分不清）、能源绿、探索紫、彩蛋红（顺序同 Ach 的分类）：前三个线性色（珐琅），后三个 sRGB（界面） */
    static final float[][] ENAMEL = {
        lin(0x2f6fe0), lin(0x12a58f), lin(0xc4489c), lin(0x3cbf52), lin(0x7b5ce0), lin(0xe0475e),
    };

    static final int[] SRGB = { 0xff2f6fe0, 0xff12a58f, 0xffc4489c, 0xff3cbf52, 0xff7b5ce0, 0xffe0475e };
    static final int[] TIER_SRGB = { 0xffcd8a5a, 0xffc8ccd4, 0xffe8b84c, 0xffb7c4ff };

    private static float[] lin(int rgb) {
        float r = ((rgb >> 16) & 0xff) / 255f, g = ((rgb >> 8) & 0xff) / 255f, b = (rgb & 0xff) / 255f;
        return new float[] { (float) Math.pow(r, 2.2), (float) Math.pow(g, 2.2), (float) Math.pow(b, 2.2), r, g, b };
    }
}
