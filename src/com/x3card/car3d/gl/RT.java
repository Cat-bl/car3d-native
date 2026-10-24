package com.x3card.car3d.gl;

import android.opengl.GLES30;

/** 离屏缓冲（同 three.js 的 WebGLRenderTarget）：颜色贴图（8 位 / 半精度浮点）+ 深度（渲染缓冲，或深度贴图），
    samples > 0 时先画进多重采样的渲染缓冲，画完 resolve() 拷到贴图上（同 three 在 WebGL2 上的做法） */
public final class RT {
    public int width, height;
    public final int samples, colorType;
    public final boolean depth, depthTex;
    public final Tex texture = new Tex();
    public Tex depthTexture;
    /** 颜色存成 SRGB8_ALPHA8（取的时候硬件转线性） */
    public boolean srgb;
    /** 画进来时着色器按 sRGB 输出（同 three 的 isXRRenderTarget + texture.encoding = sRGBEncoding：和画到屏幕上同一套程序） */
    public boolean xr;
    int fbo, msFbo, msColor, msDepth, depthRb;

    public RT(int w, int h, int colorType, boolean depth, boolean depthTex, int samples, int filter) {
        width = Math.max(1, w); height = Math.max(1, h);
        this.colorType = colorType; this.depth = depth; this.depthTex = depthTex; this.samples = samples;
        texture.target = true; texture.generateMipmaps = false;
        texture.minFilter = texture.magFilter = filter;
        texture.type = colorType;
        texture.width = width; texture.height = height;
        if (depthTex) { depthTexture = new Tex(); depthTexture.target = true; depthTexture.minFilter = depthTexture.magFilter = GLES30.GL_NEAREST; }
    }
    public void setSize(int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        if (w == width && h == height) return;
        dispose();
        width = w; height = h; texture.width = w; texture.height = h;
    }
    private int colorFormat() { return colorType == GLES30.GL_HALF_FLOAT ? GLES30.GL_RGBA16F : srgb ? GLES30.GL_SRGB8_ALPHA8 : GLES30.GL_RGBA8; }
    /** 设为当前画的目标（第一次用时建好） */
    public void bind() {
        if (fbo == 0) build();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, samples > 0 ? msFbo : fbo);
        GLES30.glViewport(0, 0, width, height);
    }
    private void build() {
        int[] a = new int[1];
        GLES30.glGenTextures(1, a, 0); texture.id = a[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, colorFormat(), width, height);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, texture.minFilter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, texture.magFilter);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glGenFramebuffers(1, a, 0); fbo = a[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture.id, 0);
        if (depthTex) {
            GLES30.glGenTextures(1, a, 0); depthTexture.id = a[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture.id);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT24, width, height);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, depthTexture.id, 0);
            depthTexture.width = width; depthTexture.height = height;
        } else if (depth && samples == 0) {
            GLES30.glGenRenderbuffers(1, a, 0); depthRb = a[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, width, height);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb);
        }
        if (samples > 0) {
            GLES30.glGenFramebuffers(1, a, 0); msFbo = a[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo);
            GLES30.glGenRenderbuffers(1, a, 0); msColor = a[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msColor);
            GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, colorFormat(), width, height);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, msColor);
            if (depth) {
                GLES30.glGenRenderbuffers(1, a, 0); msDepth = a[0];
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, msDepth);
                GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, depthTex ? GLES30.GL_DEPTH_COMPONENT24 : GLES30.GL_DEPTH_COMPONENT16, width, height);
                GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, msDepth);
            }
        }
        texture.uploaded = texture.version;
    }
    /** 多重采样的画完：拷到贴图上（颜色，有深度贴图时连深度） */
    public void resolve() {
        if (samples == 0 || msFbo == 0) return;
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo);
        int mask = GLES30.GL_COLOR_BUFFER_BIT | (depthTex ? GLES30.GL_DEPTH_BUFFER_BIT : 0);
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, mask, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo);
    }
    /** 读回颜色（RGBA8，行从下往上），同 readRenderTargetPixels */
    public void readPixels(java.nio.ByteBuffer out) {
        if (fbo == 0) build();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        out.position(0);
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4);
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out);
    }
    /** 从画布（默认帧缓冲；开着多重采样时顺便解析）把颜色拷进来，大小要一样 */
    public void copyFromScreen() {
        if (fbo == 0) build();
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fbo);
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }
    public void dispose() {
        int[] a = new int[1];
        if (texture.id != 0) { a[0] = texture.id; GLES30.glDeleteTextures(1, a, 0); texture.id = 0; }
        if (depthTexture != null && depthTexture.id != 0) { a[0] = depthTexture.id; GLES30.glDeleteTextures(1, a, 0); depthTexture.id = 0; }
        for (int rb : new int[] { msColor, msDepth, depthRb }) if (rb != 0) { a[0] = rb; GLES30.glDeleteRenderbuffers(1, a, 0); }
        for (int f : new int[] { fbo, msFbo }) if (f != 0) { a[0] = f; GLES30.glDeleteFramebuffers(1, a, 0); }
        fbo = msFbo = msColor = msDepth = depthRb = 0;
    }
}
