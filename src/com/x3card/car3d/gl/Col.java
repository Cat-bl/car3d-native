package com.x3card.car3d.gl;

/** 颜色，存线性值（同 three.js r149 的 Color，ColorManagement.legacyMode = false：十六进制、样式字符串按 sRGB 写，换算成线性存） */
public final class Col {
    public float r = 1, g = 1, b = 1;

    public Col() {}
    public Col(float r, float g, float b) { this.r = r; this.g = g; this.b = b; }
    /** 十六进制 sRGB（0xrrggbb） */
    public Col(int hex) { setHex(hex); }
    public Col(String css) { setStyle(css); }

    public static float srgbToLinear(float c) { return c < 0.04045f ? c * 0.0773993808f : (float) Math.pow(c * 0.9478672986 + 0.0521327014, 2.4); }
    public static float linearToSrgb(float c) { return c < 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 0.41666) - 0.055f; }

    public Col set(Col c) { return copy(c); }
    public Col copy(Col c) { r = c.r; g = c.g; b = c.b; return this; }
    public Col clone() { return new Col(r, g, b); }
    /** 线性值直接写 */
    public Col setRGB(float r, float g, float b) { this.r = r; this.g = g; this.b = b; return this; }
    /** sRGB 的 0～1 值写进来（setRGB(..., SRGBColorSpace)） */
    public Col setSRGB(float r, float g, float b) { this.r = srgbToLinear(r); this.g = srgbToLinear(g); this.b = srgbToLinear(b); return this; }
    public Col setScalar(float s) { r = g = b = s; return this; }
    public Col setHex(int hex) {
        return setSRGB(((hex >> 16) & 255) / 255f, ((hex >> 8) & 255) / 255f, (hex & 255) / 255f);
    }
    /** '#rrggbb' / '#rgb'（sRGB） */
    public Col setStyle(String s) {
        if (s == null) return this;
        s = s.trim();
        if (s.startsWith("#")) {
            String h = s.substring(1);
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            try { return setHex(Integer.parseInt(h.substring(0, 6), 16)); } catch (Exception e) { return this; }
        }
        return this;
    }
    public Col fromArray(float[] a, int o) { r = a[o]; g = a[o + 1]; b = a[o + 2]; return this; }
    public Col multiply(Col c) { r *= c.r; g *= c.g; b *= c.b; return this; }
    public Col multiplyScalar(float s) { r *= s; g *= s; b *= s; return this; }
    public Col lerp(Col c, float a) { r += (c.r - r) * a; g += (c.g - g) * a; b += (c.b - b) * a; return this; }
    public Col convertSRGBToLinear() { r = srgbToLinear(r); g = srgbToLinear(g); b = srgbToLinear(b); return this; }
    public Col convertLinearToSRGB() { r = linearToSrgb(r); g = linearToSrgb(g); b = linearToSrgb(b); return this; }
    /** sRGB 的十六进制（同 getHex） */
    public int getHex() {
        int R = Math.round(clamp(linearToSrgb(r)) * 255), G = Math.round(clamp(linearToSrgb(g)) * 255), B = Math.round(clamp(linearToSrgb(b)) * 255);
        return (R << 16) | (G << 8) | B;
    }
    public String getHexString() { String s = Integer.toHexString(getHex()); while (s.length() < 6) s = "0" + s; return s; }
    /** 安卓 ARGB 颜色（不透明） */
    public int argb() { return 0xff000000 | getHex(); }
    /** HSL（sRGB 下，同 getHSL：h 0～1） */
    public float[] getHSL() {
        float R = clamp(linearToSrgb(r)), G = clamp(linearToSrgb(g)), B = clamp(linearToSrgb(b));
        float max = Math.max(R, Math.max(G, B)), min = Math.min(R, Math.min(G, B)), h = 0, s = 0, l = (min + max) / 2;
        if (min != max) {
            float d = max - min;
            s = l <= 0.5f ? d / (max + min) : d / (2 - max - min);
            if (max == R) h = (G - B) / d + (G < B ? 6 : 0);
            else if (max == G) h = (B - R) / d + 2;
            else h = (R - G) / d + 4;
            h /= 6;
        }
        return new float[] { h, s, l };
    }
    public Col setHSL(float h, float s, float l) {
        h = ((h % 1) + 1) % 1; s = clamp(s); l = clamp(l);
        if (s == 0) return setSRGB(l, l, l);
        float p = l <= 0.5f ? l * (1 + s) : l + s - l * s, q = 2 * l - p;
        return setSRGB(hue(q, p, h + 1 / 3f), hue(q, p, h), hue(q, p, h - 1 / 3f));
    }
    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6f) return p + (q - p) * 6 * t;
        if (t < 1 / 2f) return q;
        if (t < 2 / 3f) return p + (q - p) * 6 * (2 / 3f - t);
        return p;
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    public boolean equals(Col c) { return c.r == r && c.g == g && c.b == b; }
}
