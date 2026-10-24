package com.x3card.car3d.gl;

import android.graphics.Bitmap;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** 贴图（同 three.js 的 Texture / DataTexture / CanvasTexture）：像素放在直接内存里（RGBA，透明度不预乘，同 WebGL 默认的上传方式），
    第一次用到时上传，上传完像素就扔掉（keep 为真时留着）。sRGB 的用 SRGB8_ALPHA8（硬件解码，同 three r149 在 WebGL2 上的做法） */
public final class Tex {
    public String name = "";
    public ByteBuffer pixels;
    public int width, height, type = GLES30.GL_UNSIGNED_BYTE;
    public boolean srgb, premultiplyAlpha, generateMipmaps = true, keep;
    public int wrapS = GLES30.GL_CLAMP_TO_EDGE, wrapT = GLES30.GL_CLAMP_TO_EDGE;
    public int minFilter = GLES30.GL_LINEAR_MIPMAP_LINEAR, magFilter = GLES30.GL_LINEAR;
    // 贴图坐标变换（offset / repeat / rotation / center，同 texture.matrix）
    public float offX, offY, repX = 1, repY = 1, rotation, cx, cy;
    public final M3 matrix = new M3();
    /** 原来的图片文件（GLB 里的 JPEG / PNG：要读像素时再解，比如算平均色、灯罩纹理） */
    public ByteBuffer src;
    public String mime = "";
    public final Map<String, Object> ud = new HashMap<>();
    public int id, version = 1, uploaded;
    /** 离屏缓冲的贴图：显存归 RT 管 */
    public boolean target;
    /** 各向异性过滤（同 texture.anisotropy；显卡不支持就不设） */
    public int anisotropy = 1;
    private static int maxAniso = -1;

    public Tex() {}
    public Tex(ByteBuffer rgba, int w, int h) { pixels = rgba; width = w; height = h; }

    /** 位图 → 贴图（不预乘的 RGBA）。安卓位图在内存里一般是预乘过的：getPixels 给的是不预乘的颜色 */
    public static Tex fromBitmap(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int c : px) { b.put((byte) (c >> 16)); b.put((byte) (c >> 8)); b.put((byte) c); b.put((byte) (c >>> 24)); }
        b.position(0);
        return new Tex(b, w, h);
    }
    /** 画布画出来的贴图（同 CanvasTexture：上传时上下翻转，sRGB） */
    public static Tex canvas(Bitmap bm, boolean color) {
        Bitmap f = flip(bm);
        Tex t = fromBitmap(f);
        if (f != bm) f.recycle();
        t.srgb = color;
        return t;
    }
    static Bitmap flip(Bitmap bm) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(1, -1);
        return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, false);
    }
    /** 数据贴图（同 DataTexture：不翻转、不生成多级、线性过滤） */
    public static Tex data(byte[] rgba, int w, int h) {
        ByteBuffer b = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        b.put(rgba).position(0);
        Tex t = new Tex(b, w, h);
        t.generateMipmaps = false; t.minFilter = t.magFilter = GLES30.GL_LINEAR;
        return t;
    }

    public void updateMatrix() { matrix.setUvTransform(offX, offY, repX, repY, rotation, cx, cy); }
    public void needsUpdate() { version++; }

    /** 绑到第 unit 个贴图单元（要上传就先上传） */
    public void bind(int unit) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        if (id == 0) { int[] a = new int[1]; GLES30.glGenTextures(1, a, 0); id = a[0]; }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
        if (!target && uploaded != version) {
            if (pixels == null && src != null && pending != null) take();
            if (pixels == null && src != null) decode();
            if (pixels != null) upload();
        }
    }
    /** 后台正在解的（同一张原图的几份贴图共用一个） */
    private java.util.concurrent.Future<Tex> pending;
    /** 后台先把这些贴图解好（同一张原图只解一次）：载入车模时建好车就交出去，画第一帧时 bind 只等结果 */
    public static void prefetch(Iterable<Tex> ts) {
        java.util.Map<ByteBuffer, java.util.concurrent.Future<Tex>> by = new java.util.IdentityHashMap<>();
        for (Tex t : ts) {
            if (t == null || t.target || t.src == null || t.pixels != null || t.pending != null || t.uploaded == t.version) continue;
            java.util.concurrent.Future<Tex> f = by.get(t.src);
            if (f == null) {
                final Tex d = new Tex();
                d.src = t.src;
                f = Work.submit(() -> { d.decode(); return d; });
                by.put(t.src, f);
            }
            t.pending = f;
        }
    }
    private void take() {
        try { Tex d = pending.get(); if (d.pixels != null) { pixels = d.pixels; width = d.width; height = d.height; } }
        catch (Exception e) { /* 后台没解出来：下面照常再解一次 */ }
        pending = null;
    }
    /** 把原来的图片文件解成 RGBA（不预乘，同 ImageBitmapLoader 的 premultiplyAlpha: 'none'） */
    public boolean decode() {
        if (src == null) return false;
        byte[] b = new byte[src.remaining()];
        src.duplicate().get(b);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPremultiplied = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (bm == null) return false;
        if (bm.getConfig() != Bitmap.Config.ARGB_8888) { Bitmap c = bm.copy(Bitmap.Config.ARGB_8888, false); bm.recycle(); bm = c; }
        width = bm.getWidth(); height = bm.getHeight();
        pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        bm.copyPixelsToBuffer(pixels);
        pixels.position(0);
        bm.recycle();
        return true;
    }
    private void upload() {
        boolean mips = generateMipmaps && mipFilter(minFilter);
        int internal = type == GLES30.GL_HALF_FLOAT ? GLES30.GL_RGBA16F : type == GLES30.GL_FLOAT ? GLES30.GL_RGBA32F : srgb ? GLES30.GL_SRGB8_ALPHA8 : GLES30.GL_RGBA8;
        ByteBuffer px = pixels;
        if (premultiplyAlpha && type == GLES30.GL_UNSIGNED_BYTE) {
            px = ByteBuffer.allocateDirect(pixels.capacity()).order(ByteOrder.nativeOrder());
            for (int i = 0; i < width * height; i++) {
                int a = pixels.get(i * 4 + 3) & 255;
                for (int k = 0; k < 3; k++) px.put(i * 4 + k, (byte) Math.round((pixels.get(i * 4 + k) & 255) * a / 255f));
                px.put(i * 4 + 3, (byte) a);
            }
        }
        px.position(0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, GLES30.GL_RGBA, type, px);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mips || !mipFilter(minFilter) ? minFilter : GLES30.GL_LINEAR);
        if (anisotropy > 1) {
            if (maxAniso < 0) {
                String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
                float[] m = { 1 };
                if (ext != null && ext.contains("GL_EXT_texture_filter_anisotropic")) GLES30.glGetFloatv(0x84FF, m, 0);
                maxAniso = Math.max(1, (int) m[0]);
            }
            if (maxAniso > 1) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, 0x84FE, Math.min(anisotropy, maxAniso));
        }
        if (mips) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        uploaded = version;
        if (!keep) pixels = null;
    }
    static boolean mipFilter(int f) { return f != GLES30.GL_LINEAR && f != GLES30.GL_NEAREST; }
    public void dispose() {
        if (id != 0 && !target) GLES30.glDeleteTextures(1, new int[] { id }, 0);
        if (!target) id = 0;
        uploaded = 0;
    }
}
