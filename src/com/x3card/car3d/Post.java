package com.x3card.car3d;

import android.opengl.GLES30;

import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Programs;
import com.x3card.car3d.gl.RT;
import com.x3card.car3d.gl.Renderer;
import com.x3card.car3d.gl.Tex;
import com.x3card.car3d.gl.Val;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * 画质高 / 极致的后期效果（照网页版 main.js 的 makePost + three r149 的 EffectComposer、RenderPass、SSAOPass、UnrealBloomPass、ShaderPass）：
 * 先把场景画进离屏缓冲（极致：环境光遮蔽——画一遍正常的、一遍法线和深度，算遮蔽、模糊，乘回去），再叠灯光辉光，最后一道转 sRGB 贴到画布上。
 * 网页版的画布是透明的、叠在 CSS 背景上：这里先画背景，最后一道按预乘透明度叠上去，结果一样。
 * 网页版极致画质还加了景深（BokehPass），但 three r149 的 BokehPass 不交换读写缓冲，后面的辉光、最后一道读的还是景深之前的画面，景深从来没显示出来——这里不画
 */
final class Post {
    private final Renderer r;
    private final boolean ao, bloom;
    private final int type, samples;
    private int W = 0, H = 0;
    private RT read, write;
    // 辉光
    private RT bright;
    private final RT[] hz = new RT[5], vt = new RT[5];
    private Mat high, comp, add;
    private final Mat[] blurs = new Mat[5];
    // 环境光遮蔽
    private RT beauty, normal, ssao, blur;
    private Mat ssaoM, ssaoBlur, normalM, copy, mul;
    private Tex noise;
    private float[] kernel;
    // 最后一道
    private Mat fin;
    private final Mesh quad;
    private final Obj3D qs = new Obj3D();
    private final Camera qcam = Camera.ortho(-1, 1, 1, -1, 0, 1);
    private static final String FINAL_FS = "uniform sampler2D tDiffuse;\nvarying vec2 vUv;\nvoid main() {\n  vec4 c = texture2D(tDiffuse, vUv);\n" +
        "  vec3 p = max(c.rgb, 0.0);\n  float a = clamp(max(c.a, max(p.r, max(p.g, p.b))), 0.0, 1.0);\n  if (a <= 0.0) { gl_FragColor = vec4(0.0); return; }\n" +
        "  vec3 l = min(p / a, 1.0);\n  vec3 s = mix(pow(l, vec3(0.41666)) * 1.055 - vec3(0.055), l * 12.92, vec3(lessThanEqual(l, vec3(0.0031308))));\n  gl_FragColor = vec4(s * a, a);\n}";
    private static final String QUAD_VS = "varying vec2 vUv;\nvoid main() { vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }";

    /** 离屏缓冲的格式（同 rtSpec）：能渲染到半精度浮点就用；多重采样（开着抗锯齿时）最多 4 倍，颜色、深度格式都支持的倍数 */
    static int[] spec(boolean aa) {
        String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
        boolean half = ext != null && ext.contains("GL_EXT_color_buffer_float");
        int n = 0;
        if (aa) {
            int[] fmts = { half ? GLES30.GL_RGBA16F : GLES30.GL_RGBA8, GLES30.GL_DEPTH_COMPONENT16, GLES30.GL_DEPTH_COMPONENT24 };
            for (int k : new int[] { 4, 2 }) {
                boolean all = true;
                for (int f : fmts) {
                    int[] c = new int[1];
                    GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, f, GLES30.GL_NUM_SAMPLE_COUNTS, 1, c, 0);
                    int[] s = new int[Math.max(1, c[0])];
                    if (c[0] > 0) GLES30.glGetInternalformativ(GLES30.GL_RENDERBUFFER, f, GLES30.GL_SAMPLES, c[0], s, 0);
                    boolean has = false;
                    for (int i = 0; i < c[0]; i++) if (s[i] == k) has = true;
                    if (!has) all = false;
                }
                if (all) { n = k; break; }
            }
        }
        return new int[] { half ? GLES30.GL_HALF_FLOAT : GLES30.GL_UNSIGNED_BYTE, n };
    }

    Post(Renderer r, boolean ao, boolean bloom, boolean aa) {
        this.r = r; this.ao = ao; this.bloom = bloom;
        int[] sp = spec(aa);
        type = sp[0]; samples = sp[1];
        Geo g = new Geo(); // 同 FullScreenQuad：一个盖满画布的大三角形
        g.set("position", Attr.floats(new float[] { -1, 3, 0, -1, -1, 0, 3, -1, 0 }, 3));
        g.set("uv", Attr.floats(new float[] { 0, 2, 0, 0, 2, 0 }, 2));
        quad = new Mesh(g, null);
        quad.frustumCulled = false;
        qs.add(quad);
        fin = shader(QUAD_VS, FINAL_FS);
        fin.put("tDiffuse", null);
        fin.blending = Mat.CUSTOM; fin.blendSrc = GLES30.GL_ONE; fin.blendDst = GLES30.GL_ONE_MINUS_SRC_ALPHA; fin.blendSrcAlpha = GLES30.GL_ONE; fin.blendDstAlpha = GLES30.GL_ONE_MINUS_SRC_ALPHA;
        fin.depthTest = false; fin.depthWrite = false;
        if (bloom) {
            String[] hp = Programs.POST.get("highPass");
            high = shader(hp[0], hp[1]);
            high.put("tDiffuse", null); high.put("luminosityThreshold", 0.86f); high.put("smoothWidth", 0.01f); high.put("defaultColor", new float[] { 0, 0, 0 }); high.put("defaultOpacity", 0f);
            int[] ks = { 3, 5, 7, 9, 11 };
            String[] bl = Programs.POST.get("blur");
            for (int i = 0; i < 5; i++) {
                blurs[i] = shader(bl[0], bl[1]);
                blurs[i].defines.put("KERNEL_RADIUS", String.valueOf(ks[i])); blurs[i].defines.put("SIGMA", String.valueOf(ks[i]));
                blurs[i].put("colorTexture", null); blurs[i].put("texSize", new float[] { 0.5f, 0.5f }); blurs[i].put("direction", new float[] { 0.5f, 0.5f });
            }
            String[] cp = Programs.POST.get("composite");
            comp = shader(cp[0], cp[1]);
            comp.defines.put("NUM_MIPS", "5");
            for (int i = 1; i <= 5; i++) comp.put("blurTexture" + i, null);
            comp.put("bloomStrength", 0.32f); comp.put("bloomRadius", 0.5f); comp.put("bloomFactors", new float[] { 1, 0.8f, 0.6f, 0.4f, 0.2f });
            comp.put("bloomTintColors", new float[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 });
            String[] co = Programs.POST.get("copy");
            add = shader(co[0], co[1]); // 只加颜色、透明度不动（同网页版改过的 UnrealBloomPass）
            add.put("tDiffuse", null); add.put("opacity", 1f);
            add.blending = Mat.CUSTOM; add.blendSrc = GLES30.GL_ONE; add.blendDst = GLES30.GL_ONE; add.blendSrcAlpha = GLES30.GL_ZERO; add.blendDstAlpha = GLES30.GL_ONE;
            add.transparent = true; add.depthTest = false; add.depthWrite = false;
        }
        if (ao) {
            String[] so = Programs.POST.get("ssao"), sb = Programs.POST.get("ssaoBlur"), co = Programs.POST.get("copy");
            ssaoM = shader(so[0], so[1]);
            ssaoM.defines.put("PERSPECTIVE_CAMERA", "1"); ssaoM.defines.put("KERNEL_SIZE", "32");
            for (String k : new String[] { "tDiffuse", "tNormal", "tDepth", "tNoise", "kernel", "cameraNear", "cameraFar", "resolution", "cameraProjectionMatrix", "cameraInverseProjectionMatrix", "kernelRadius", "minDistance", "maxDistance" }) ssaoM.put(k, null);
            ssaoM.put("kernelRadius", 0.9f); ssaoM.put("minDistance", 0.0002f); ssaoM.put("maxDistance", 0.015f);
            ssaoBlur = shader(sb[0], sb[1]);
            ssaoBlur.put("tDiffuse", null); ssaoBlur.put("resolution", null);
            normalM = new Mat(Mat.NORMALS);
            normalM.blending = Mat.NO_BLENDING;
            copy = shader(co[0], co[1]);
            copy.put("tDiffuse", null); copy.put("opacity", 1f);
            copy.transparent = true; copy.depthTest = false; copy.depthWrite = false;
            mul = shader(co[0], co[1]); // 遮蔽乘回去（同 SSAOPass 的 copyMaterial：目标颜色 × 遮蔽）
            mul.put("tDiffuse", null); mul.put("opacity", 1f);
            mul.blending = Mat.CUSTOM; mul.blendSrc = GLES30.GL_DST_COLOR; mul.blendDst = GLES30.GL_ZERO; mul.blendSrcAlpha = GLES30.GL_DST_ALPHA; mul.blendDstAlpha = GLES30.GL_ZERO;
            mul.transparent = true; mul.depthTest = false; mul.depthWrite = false;
            // 采样核（32 个、越往外越长）、4×4 的随机旋转（同 generateSampleKernel / generateRandomKernelRotations）
            Random rnd = new Random();
            kernel = new float[32 * 3];
            for (int i = 0; i < 32; i++) {
                float x = rnd.nextFloat() * 2 - 1, y = rnd.nextFloat() * 2 - 1, z = rnd.nextFloat(), l = (float) Math.sqrt(x * x + y * y + z * z);
                if (l == 0) l = 1;
                float sc = i / 32f; sc = 0.1f + 0.9f * sc * sc;
                kernel[i * 3] = x / l * sc; kernel[i * 3 + 1] = y / l * sc; kernel[i * 3 + 2] = z / l * sc;
            }
            ByteBuffer nb = ByteBuffer.allocateDirect(16 * 4 * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 16; i++) { nb.putFloat(simplex(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1)); nb.putFloat(0); nb.putFloat(0); nb.putFloat(1); }
            nb.position(0);
            noise = new Tex(nb, 4, 4);
            noise.type = GLES30.GL_FLOAT; noise.generateMipmaps = false; noise.minFilter = noise.magFilter = GLES30.GL_NEAREST; noise.wrapS = noise.wrapT = GLES30.GL_REPEAT;
        }
    }
    private static Mat shader(String vs, String fs) { Mat m = Mat.shader(vs, fs); m.blending = Mat.NO_BLENDING; m.toneMapped = false; return m; }

    /** 尺寸（画布的像素）变了：缓冲跟着变 */
    private void size(int w, int h) {
        if (w == W && h == H) return;
        W = w; H = h;
        dropRT();
        read = new RT(w, h, type, true, false, samples, GLES30.GL_LINEAR);
        write = new RT(w, h, type, true, false, samples, GLES30.GL_LINEAR);
        if (bloom) {
            int rx = Math.round(w / 2f), ry = Math.round(h / 2f);
            bright = rt8(rx, ry, false);
            for (int i = 0; i < 5; i++) {
                hz[i] = rt8(rx, ry, false); vt[i] = rt8(rx, ry, false);
                blurs[i].put("texSize", new float[] { rx, ry });
                rx = Math.round(rx / 2f); ry = Math.round(ry / 2f);
            }
            for (int i = 0; i < 5; i++) comp.put("blurTexture" + (i + 1), vt[i].texture);
        }
        if (ao) {
            beauty = rt8(w, h, true);
            normal = new RT(w, h, GLES30.GL_UNSIGNED_BYTE, true, true, 0, GLES30.GL_NEAREST);
            ssao = rt8(w, h, false); blur = rt8(w, h, false);
            ssaoM.put("tDiffuse", beauty.texture); ssaoM.put("tNormal", normal.texture); ssaoM.put("tDepth", normal.depthTexture); ssaoM.put("tNoise", noise); ssaoM.put("kernel", kernel);
            ssaoM.put("resolution", new float[] { w, h });
            ssaoBlur.put("tDiffuse", ssao.texture); ssaoBlur.put("resolution", new float[] { w, h });
        }
    }
    private static RT rt8(int w, int h, boolean depth) { return new RT(w, h, GLES30.GL_UNSIGNED_BYTE, depth, false, 0, GLES30.GL_LINEAR); }

    /** 画一帧：场景（+ 环境光遮蔽）→ 辉光 → 最后一道叠到画布上（画布上已经画好了背景） */
    void render(Obj3D scene, Camera cam, int w, int h) {
        size(w, h);
        float cr = r.clearR, cg = r.clearG, cb = r.clearB, ca = r.clearA;
        boolean ac = r.autoClear;
        r.autoClear = false;
        r.clearR = r.clearG = r.clearB = r.clearA = 0;
        if (ao) {
            r.setRenderTarget(beauty); r.clear(true, true); r.render(scene, cam);
            r.overrideMaterial = normalM;
            r.setRenderTarget(normal);
            r.clearR = 0x77 / 255f; r.clearG = 0x77 / 255f; r.clearB = 1; r.clearA = 1;
            r.clear(true, true);
            r.render(scene, cam);
            r.overrideMaterial = null;
            r.clearR = r.clearG = r.clearB = r.clearA = 0;
            ssaoM.put("cameraNear", cam.near); ssaoM.put("cameraFar", cam.far);
            ssaoM.put("cameraProjectionMatrix", cam.projectionMatrix); ssaoM.put("cameraInverseProjectionMatrix", cam.projectionMatrixInverse);
            pass(ssaoM, ssao);
            pass(ssaoBlur, blur);
            copy.put("tDiffuse", beauty.texture); copy.blending = Mat.NO_BLENDING;
            pass(copy, write);
            mul.put("tDiffuse", blur.texture);
            pass(mul, write);
            RT t = read; read = write; write = t;
        } else {
            r.setRenderTarget(read); r.clear(true, true); r.render(scene, cam);
        }
        if (bloom) {
            high.put("tDiffuse", read.texture);
            r.setRenderTarget(bright); r.clear(true, false); quad(high);
            RT in = bright;
            for (int i = 0; i < 5; i++) {
                blurs[i].put("colorTexture", in.texture); blurs[i].put("direction", new float[] { 1, 0 });
                r.setRenderTarget(hz[i]); r.clear(true, false); quad(blurs[i]);
                blurs[i].put("colorTexture", hz[i].texture); blurs[i].put("direction", new float[] { 0, 1 });
                r.setRenderTarget(vt[i]); r.clear(true, false); quad(blurs[i]);
                in = vt[i];
            }
            r.setRenderTarget(hz[0]); r.clear(true, false); quad(comp);
            add.put("tDiffuse", hz[0].texture);
            r.setRenderTarget(read); quad(add);
        }
        r.setRenderTarget(null);
        fin.put("tDiffuse", read.texture);
        quad(fin);
        r.clearR = cr; r.clearG = cg; r.clearB = cb; r.clearA = ca; r.autoClear = ac;
    }
    private void pass(Mat m, RT to) { r.setRenderTarget(to); quad(m); }
    private void quad(Mat m) { quad.material = m; r.render(qs, qcam); }

    private void dropRT() {
        for (RT t : new RT[] { read, write, bright, beauty, normal, ssao, blur }) if (t != null) { t.dispose(); if (t.depthTexture != null) t.depthTexture.dispose(); }
        for (int i = 0; i < 5; i++) { if (hz[i] != null) hz[i].dispose(); if (vt[i] != null) vt[i].dispose(); hz[i] = vt[i] = null; }
        read = write = bright = beauty = normal = ssao = blur = null;
    }
    void dispose() {
        dropRT();
        W = H = 0;
        for (Mat m : new Mat[] { fin, high, comp, add, ssaoM, ssaoBlur, normalM, copy, mul }) if (m != null) m.dispose();
        for (Mat m : blurs) if (m != null) m.dispose();
        if (noise != null) noise.dispose();
        quad.geometry.dispose();
    }

    // ================= 三维单纯形噪声（z = 0 的切片，同 SimplexNoise.noise3d 的 Stefan Gustavson 写法）：只给环境光遮蔽的随机旋转用 =================
    private static final int[][] GRAD3 = { { 1, 1, 0 }, { -1, 1, 0 }, { 1, -1, 0 }, { -1, -1, 0 }, { 1, 0, 1 }, { -1, 0, 1 }, { 1, 0, -1 }, { -1, 0, -1 }, { 0, 1, 1 }, { 0, -1, 1 }, { 0, 1, -1 }, { 0, -1, -1 } };
    private static int[] perm;
    private static float simplex(float xin, float yin) {
        if (perm == null) { Random r = new Random(); int[] p = new int[256]; for (int i = 0; i < 256; i++) p[i] = (int) Math.floor(r.nextDouble() * 256); perm = new int[512]; for (int i = 0; i < 512; i++) perm[i] = p[i & 255]; }
        // z 固定为 0 的三维噪声（同 noise3d(x, y, 0)）
        double F3 = 1.0 / 3.0, G3 = 1.0 / 6.0, zin = 0;
        double s = (xin + yin + zin) * F3;
        int i = (int) Math.floor(xin + s), j = (int) Math.floor(yin + s), k = (int) Math.floor(zin + s);
        double t = (i + j + k) * G3, x0 = xin - (i - t), y0 = yin - (j - t), z0 = zin - (k - t);
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) { if (y0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; } else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; } else { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; } }
        else { if (y0 < z0) { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; } else if (x0 < z0) { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; } else { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; } }
        double x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3, x2 = x0 - i2 + 2 * G3, y2 = y0 - j2 + 2 * G3, z2 = z0 - k2 + 2 * G3, x3 = x0 - 1 + 3 * G3, y3 = y0 - 1 + 3 * G3, z3 = z0 - 1 + 3 * G3;
        int ii = i & 255, jj = j & 255, kk = k & 255;
        double n = corner(perm[ii + perm[jj + perm[kk]]] % 12, x0, y0, z0) + corner(perm[ii + i1 + perm[jj + j1 + perm[kk + k1]]] % 12, x1, y1, z1)
            + corner(perm[ii + i2 + perm[jj + j2 + perm[kk + k2]]] % 12, x2, y2, z2) + corner(perm[ii + 1 + perm[jj + 1 + perm[kk + 1]]] % 12, x3, y3, z3);
        return (float) (32.0 * n);
    }
    private static double corner(int g, double x, double y, double z) {
        double t = 0.6 - x * x - y * y - z * z;
        if (t < 0) return 0;
        t *= t;
        return t * t * (GRAD3[g][0] * x + GRAD3[g][1] * y + GRAD3[g][2] * z);
    }
}
