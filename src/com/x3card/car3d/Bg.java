package com.x3card.car3d;

import android.opengl.GLES30;

import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Renderer;
import com.x3card.car3d.gl.Tex;
import com.x3card.car3d.gl.Val;

/**
 * 车况页的背景（照 style.css 的 .pg-car .bg）：影棚 / 暖调 / 冷调三套径向渐变（椭圆 75%×46%、中心在 50% 60%，在 sRGB 里插值，同 CSS），
 * 深色是盖在上面的一层（椭圆 72%×44%），切换时按透明度淡入淡出；图片背景铺满（cover）再盖一层黑色压暗。
 * 每帧在场景之前画（网页版是透明画布叠在 CSS 背景上：先画背景再按同样的混合画场景，结果一样）
 */
final class Bg {
    /** 浅色三站、深色三站（sRGB 0～1） */
    private static final int[][] LIGHT = { { 0xffffff, 0xf0f2f5, 0xdde1e7 }, { 0xfffaf3, 0xf3ebe0, 0xe2d6c6 }, { 0xf6fbff, 0xe4edf7, 0xcbd9ea } };
    private static final int[][] DARK = { { 0x2b3038, 0x16191e, 0x0b0c0f }, { 0x3b3027, 0x1c1612, 0x0d0a08 }, { 0x1f2d42, 0x101824, 0x060a10 } };
    /** 0 影棚、1 暖调、2 冷调、3 图片 */
    int kind;
    /** 深色那层的不透明度（0.6 秒渐变由车况页算好给过来）、图片的压暗 */
    float darkK, dim = 0.25f;
    Tex image;
    private Mat mat;
    private Mesh quad;
    private Obj3D sc;
    private final Camera cam = Camera.ortho(-1, 1, 1, -1, 0, 1);

    private static float[] rgb(int c) { return new float[] { ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f }; }

    void draw(Renderer r, int bw, int bh, float W, float H) {
        if (mat == null) {
            mat = Mat.shader("void main() { gl_Position = vec4(position.xy, 0.0, 1.0); }",
                "uniform vec3 uL0, uL1, uL2, uD0, uD1, uD2;\nuniform float uK, uDim, uImg;\nuniform vec2 uRes, uImgS;\nuniform sampler2D tImg;\n" +
                "vec3 grad(vec2 p, vec2 e, float s, vec3 a, vec3 b, vec3 c) { float t = length(vec2((p.x - 0.5) / e.x, (p.y - 0.6) / e.y)); return t < s ? mix(a, b, t / s) : mix(b, c, clamp((t - s) / (1.0 - s), 0.0, 1.0)); }\n" +
                "void main() {\n  vec2 p = vec2(gl_FragCoord.x / uRes.x, 1.0 - gl_FragCoord.y / uRes.y);\n  vec3 c;\n" +
                "  if (uImg > 0.5) { vec2 q = (p - 0.5) * uImgS + 0.5; c = mix(texture2D(tImg, vec2(q.x, q.y)).rgb, vec3(0.0), uDim); }\n" +
                "  else c = mix(grad(p, vec2(0.75, 0.46), 0.46, uL0, uL1, uL2), grad(p, vec2(0.72, 0.44), 0.52, uD0, uD1, uD2), uK);\n" +
                "  gl_FragColor = vec4(c, 1.0);\n}");
            mat.blending = Mat.NO_BLENDING; mat.depthTest = false; mat.depthWrite = false; mat.toneMapped = false;
            for (String k : new String[] { "uL0", "uL1", "uL2", "uD0", "uD1", "uD2", "uRes", "uImgS" }) mat.uniforms.put(k, new Val(null));
            mat.uniforms.put("uK", new Val(0f)); mat.uniforms.put("uDim", new Val(0f)); mat.uniforms.put("uImg", new Val(0f)); mat.uniforms.put("tImg", new Val(null));
            Geo g = new Geo();
            g.set("position", Attr.floats(new float[] { -1, -1, 0, 3, -1, 0, -1, 3, 0 }, 3));
            quad = new Mesh(g, mat);
            quad.frustumCulled = false;
            sc = new Obj3D();
            sc.add(quad);
        }
        int k = Math.min(2, kind);
        String[] n = { "uL0", "uL1", "uL2" }, d = { "uD0", "uD1", "uD2" };
        for (int i = 0; i < 3; i++) { mat.uniforms.get(n[i]).v = rgb(LIGHT[k][i]); mat.uniforms.get(d[i]).v = rgb(DARK[k][i]); }
        mat.uniforms.get("uK").v = darkK;
        mat.uniforms.get("uRes").v = new float[] { bw, bh };
        boolean img = kind == 3 && image != null;
        mat.uniforms.get("uImg").v = img ? 1f : 0f;
        mat.uniforms.get("uDim").v = kind == 3 ? dim : 0f;
        mat.uniforms.get("tImg").v = image;
        if (img) { // cover：短的那边撑满，长的那边裁掉
            float ia = image.width / (float) Math.max(1, image.height), va = W / Math.max(1f, H);
            mat.uniforms.get("uImgS").v = ia > va ? new float[] { va / ia, 1 } : new float[] { 1, ia / va };
        } else mat.uniforms.get("uImgS").v = new float[] { 1, 1 };
        boolean ac = r.autoClear;
        r.autoClear = false;
        r.render(sc, cam);
        r.autoClear = ac;
        if (kind == 3 && image == null) { GLES30.glClearColor(0x1a / 255f, 0x1c / 255f, 0x20 / 255f, 1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); }
    }
}
