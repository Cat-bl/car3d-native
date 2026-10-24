package com.x3card.car3d.gl;

import android.opengl.GLES30;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 渲染器（照 three.js r149 的 WebGLRenderer：投影收集 → 排序 → 阴影贴图 → 不透明、透明两张表依次画；材质参数照 WebGLMaterials 上传，
    灯照 WebGLLights（legacy 模式灯的强度 × π），阴影照 WebGLShadowMap（PCF 柔和阴影、深度打包成 RGBA、单面材质画背面）。
    透明的双面材质分两遍画（先背面再正面），同 r149。只在 GL 线程里用 */
public final class Renderer {
    public static Renderer current;
    public int toneMapping = Programs.ACES;
    public float toneMappingExposure = 1;
    public int outputEncoding = Programs.SRGB;
    public boolean physicallyCorrectLights;
    public boolean shadowsEnabled, shadowNeedsUpdate;
    public boolean autoClear = true;
    public float clearR, clearG, clearB, clearA;
    /** 所有网格都换成这个材质画（同 scene.overrideMaterial：环境光遮蔽画法线、景深画深度用） */
    public Mat overrideMaterial;
    public int width = 1, height = 1;
    public final Map<String, Object> info = new HashMap<>();
    public int calls, triangles;

    private RT target;
    private final Map<String, Prog> programs = new HashMap<>();
    /** 同一个程序按"参数 + 没展开的原文"再记一份 */
    private final Map<String, Prog> byParams = new HashMap<>();
    private final Map<Mat, MState> mstate = new IdentityHashMap<>();
    private int unit, empty;
    private final Map<String, Integer> stamps = new HashMap<>();
    private int stamp;

    static final class MState { final Map<Long, Prog> progs = new HashMap<>(); }
    static final class Item {
        Mesh o; Mat m; float z; int side;
        Item(Mesh o, Mat m, float z) { this.o = o; this.m = m; this.z = z; }
    }

    public Renderer() { current = this; }

    // ---------------- 状态（照 WebGLState 记住，没变不交） ----------------
    private int curProg = -1, curBlend = -1, curDepthFunc = -1, curFront = -1;
    private Boolean curCull, curDepthTest, curDepthMask, curColorMask, curOffset, curBlendOn;
    private float curOffF = Float.NaN, curOffU = Float.NaN;
    private boolean curPremul;
    private Mat curMat;
    private Camera curCam;
    public void resetState() {
        curProg = curBlend = curDepthFunc = curFront = -1;
        curCull = curDepthTest = curDepthMask = curColorMask = curOffset = curBlendOn = null;
        curOffF = curOffU = Float.NaN; curMat = null; curCam = null;
        GLES30.glBindVertexArray(0);
    }
    private void enable(int cap, boolean on) { if (on) GLES30.glEnable(cap); else GLES30.glDisable(cap); }
    private void setMaterial(Mat m, int side, boolean cw) {
        boolean cull = side != Mat.DOUBLE;
        if (curCull == null || curCull != cull) { enable(GLES30.GL_CULL_FACE, cull); curCull = cull; GLES30.glCullFace(GLES30.GL_BACK); }
        boolean flip = side == Mat.BACK;
        if (cw) flip = !flip;
        int ff = flip ? GLES30.GL_CW : GLES30.GL_CCW;
        if (curFront != ff) { GLES30.glFrontFace(ff); curFront = ff; }
        if (m.blending == Mat.NORMAL && !m.transparent) blend(Mat.NO_BLENDING, false, m);
        else blend(m.blending, m.premultipliedAlpha, m);
        if (curDepthFunc != m.depthFunc) { GLES30.glDepthFunc(m.depthFunc); curDepthFunc = m.depthFunc; }
        if (curDepthTest == null || curDepthTest != m.depthTest) { enable(GLES30.GL_DEPTH_TEST, m.depthTest); curDepthTest = m.depthTest; }
        depthMask(m.depthWrite);
        if (curColorMask == null || curColorMask != m.colorWrite) { GLES30.glColorMask(m.colorWrite, m.colorWrite, m.colorWrite, m.colorWrite); curColorMask = m.colorWrite; }
        if (curOffset == null || curOffset != m.polygonOffset) { enable(GLES30.GL_POLYGON_OFFSET_FILL, m.polygonOffset); curOffset = m.polygonOffset; }
        if (m.polygonOffset && (curOffF != m.polygonOffsetFactor || curOffU != m.polygonOffsetUnits)) {
            GLES30.glPolygonOffset(m.polygonOffsetFactor, m.polygonOffsetUnits); curOffF = m.polygonOffsetFactor; curOffU = m.polygonOffsetUnits;
        }
    }
    private void depthMask(boolean on) { if (curDepthMask == null || curDepthMask != on) { GLES30.glDepthMask(on); curDepthMask = on; } }
    private void blend(int b, boolean premul, Mat m) {
        if (b == Mat.NO_BLENDING) { if (curBlendOn == null || curBlendOn) { GLES30.glDisable(GLES30.GL_BLEND); curBlendOn = false; } return; }
        if (curBlendOn == null || !curBlendOn) { GLES30.glEnable(GLES30.GL_BLEND); curBlendOn = true; }
        if (b == Mat.CUSTOM) {
            GLES30.glBlendEquation(m.blendEquation);
            GLES30.glBlendFuncSeparate(m.blendSrc, m.blendDst, m.blendSrcAlpha < 0 ? m.blendSrc : m.blendSrcAlpha, m.blendDstAlpha < 0 ? m.blendDst : m.blendDstAlpha);
            curBlend = -1;
            return;
        }
        if (curBlend == b && curPremul == premul) return;
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD);
        if (premul) {
            if (b == Mat.ADDITIVE) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            else GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            if (b == Mat.ADDITIVE) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
            else GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }
        curBlend = b; curPremul = premul;
    }

    // ---------------- 画到哪 ----------------
    public void setSize(int w, int h) { width = Math.max(1, w); height = Math.max(1, h); }
    public RT getRenderTarget() { return target; }
    public void setRenderTarget(RT rt) {
        target = rt;
        if (rt == null) { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, width, height); }
        else rt.bind();
    }
    public void clear(boolean color, boolean depth) {
        int bits = 0;
        if (color) { GLES30.glClearColor(clearR, clearG, clearB, clearA); colorMask(true); bits |= GLES30.GL_COLOR_BUFFER_BIT; }
        if (depth) { depthMask(true); bits |= GLES30.GL_DEPTH_BUFFER_BIT; }
        if (bits != 0) GLES30.glClear(bits);
    }
    private void colorMask(boolean on) { if (curColorMask == null || curColorMask != on) { GLES30.glColorMask(on, on, on, on); curColorMask = on; } }
    int nextUnit() { return unit++; }
    int emptyTex() {
        if (empty == 0) {
            int[] a = new int[1];
            GLES30.glGenTextures(1, a, 0);
            empty = a[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, empty);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(4);
            b.put(new byte[] { -1, -1, -1, -1 }).position(0);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        }
        return empty;
    }

    // ---------------- 灯 ----------------
    private final List<DirLight> lights = new ArrayList<>(), shadowLights = new ArrayList<>();
    private float[] dirColor = new float[0], dirDir = new float[0];

    // ---------------- 画一帧 ----------------
    private final M4 projScreen = new M4();
    private final Frustum frustum = new Frustum();
    private final List<Item> opaque = new ArrayList<>(), transparent = new ArrayList<>();
    private final V3 tv = new V3();
    private Tex env;
    private Camera cam;
    private int outEnc;

    /** 画一个场景（同 renderer.render(scene, camera)）。root 不一定是 Scene（贴回画面的四边形也走这里） */
    /** 画一遍的进度（0～1，按画到第几个网格）：第一次画新车时贴图解码上传、着色器编译都在这一遍里，导入车模时给进度条用 */
    public interface Progress { void at(float f); }
    public Progress progress;
    private int drawn, drawTotal;
    private void tick() {
        if (progress != null && (++drawn & 3) == 0) progress.at(drawn / (float) drawTotal);
    }

    public void render(Obj3D root, Camera camera) {
        root.updateMatrixWorld(false);
        if (camera.parent == null) camera.updateMatrixWorld(false);
        cam = camera;
        env = root instanceof Scene ? ((Scene) root).environment : null;
        outEnc = target == null ? outputEncoding : target.xr ? Programs.SRGB : Programs.LINEAR;
        projScreen.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
        frustum.set(projScreen);
        opaque.clear(); transparent.clear(); lights.clear(); shadowLights.clear();
        project(root, camera);
        Collections.sort(opaque, (a, b) -> a.o.renderOrder != b.o.renderOrder ? a.o.renderOrder - b.o.renderOrder : a.m.id != b.m.id ? a.m.id - b.m.id : a.z != b.z ? Float.compare(a.z, b.z) : a.o.id - b.o.id);
        Collections.sort(transparent, (a, b) -> a.o.renderOrder != b.o.renderOrder ? a.o.renderOrder - b.o.renderOrder : a.z != b.z ? Float.compare(b.z, a.z) : a.o.id - b.o.id);
        if (shadowsEnabled && shadowNeedsUpdate && !shadowLights.isEmpty()) { renderShadows(root, camera); shadowNeedsUpdate = false; }
        setupLights(camera);
        stamp = stampOf();
        if (autoClear) clear(true, true);
        curCam = null; curMat = null;
        calls = triangles = 0;
        drawn = 0; drawTotal = Math.max(1, opaque.size() + transparent.size());
        for (Item it : opaque) { draw(it.o, it.m, it.m.side, camera); tick(); }
        for (Item it : transparent) {
            if (it.m.side == Mat.DOUBLE) { draw(it.o, it.m, Mat.BACK, camera); draw(it.o, it.m, Mat.FRONT, camera); }
            else draw(it.o, it.m, it.m.side, camera);
            tick();
        }
        depthMask(true); colorMask(true);
        if (curDepthTest == null || !curDepthTest) { GLES30.glEnable(GLES30.GL_DEPTH_TEST); curDepthTest = true; }
        if (curOffset == null || curOffset) { GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL); curOffset = false; }
        if (target != null) target.resolve();
        GLES30.glBindVertexArray(0);
    }
    private void project(Obj3D o, Camera camera) {
        if (!o.visible) return;
        boolean vis = (o.layers & camera.layers) != 0;
        if (vis) {
            if (o instanceof DirLight) {
                DirLight l = (DirLight) o;
                lights.add(l);
                if (l.castShadow) shadowLights.add(l);
            } else if (o instanceof Mesh) {
                Mesh m = (Mesh) o;
                if (m.material != null && m.geometry != null && m.material.visible && (!m.frustumCulled || frustum.intersects(m))) {
                    float z = tv.setFromMatrixPosition(m.matrixWorld).applyMatrix4(projScreen).z;
                    Mat mt = overrideMaterial != null ? overrideMaterial : m.material;
                    Item it = new Item(m, mt, z);
                    if (mt.transparent || mt.transmission > 0) transparent.add(it); else opaque.add(it);
                }
            }
        }
        for (int i = 0; i < o.children.size(); i++) project(o.children.get(i), camera);
    }
    /** 一帧里会让着色器变的全局状态（灯的数量、阴影、色调映射、输出编码、环境图）编成一个号 */
    private int stampOf() {
        String k = toneMapping + "," + outEnc + "," + lights.size() + "," + (shadowsEnabled ? shadowLights.size() : 0) + "," + (env != null ? env.height : 0) + "," + physicallyCorrectLights;
        Integer s = stamps.get(k);
        if (s == null) { s = stamps.size() + 1; stamps.put(k, s); }
        return s;
    }
    private void setupLights(Camera camera) {
        int n = lights.size();
        dirColor = new float[n * 3]; dirDir = new float[n * 3];
        float sc = physicallyCorrectLights ? 1 : (float) Math.PI;
        // 投影子的灯排前面（同 shadowCastingAndTexturingLightsFirst）
        List<DirLight> l = new ArrayList<>(lights);
        Collections.sort(l, (a, b) -> (b.castShadow ? 1 : 0) - (a.castShadow ? 1 : 0));
        lights.clear(); lights.addAll(l);
        V3 p = new V3(), t = new V3();
        for (int i = 0; i < n; i++) {
            DirLight d = lights.get(i);
            dirColor[i * 3] = d.color.r * d.intensity * sc; dirColor[i * 3 + 1] = d.color.g * d.intensity * sc; dirColor[i * 3 + 2] = d.color.b * d.intensity * sc;
            d.target.updateMatrixWorld(false);
            p.setFromMatrixPosition(d.matrixWorld); t.setFromMatrixPosition(d.target.matrixWorld);
            p.sub(t).transformDirection(camera.matrixWorldInverse);
            dirDir[i * 3] = p.x; dirDir[i * 3 + 1] = p.y; dirDir[i * 3 + 2] = p.z;
        }
    }

    // ---------------- 阴影贴图 ----------------
    private final Map<Mat, Mat> depthMats = new IdentityHashMap<>();
    private Mat depthBase;
    private void renderShadows(Obj3D root, Camera camera) {
        RT keep = target;
        for (DirLight l : shadowLights) {
            if (l.shadowMap == null) {
                l.shadowMap = new RT(l.mapSize, l.mapSize, GLES30.GL_UNSIGNED_BYTE, true, false, 0, GLES30.GL_NEAREST);
                l.shadowCamera.updateProjectionMatrix();
            }
            setRenderTarget(l.shadowMap);
            float r = clearR, g = clearG, b = clearB, a = clearA;
            clearR = clearG = clearB = clearA = 1;
            blend(Mat.NO_BLENDING, false, null);
            clear(true, true);
            clearR = r; clearG = g; clearB = b; clearA = a;
            l.updateShadow();
            Frustum fr = new Frustum().set(new M4().multiplyMatrices(l.shadowCamera.projectionMatrix, l.shadowCamera.matrixWorldInverse));
            int encKeep = outEnc;
            outEnc = Programs.LINEAR;
            stamp = stampOf();
            curCam = null; curMat = null;
            shadowObj(root, camera, l.shadowCamera, fr);
            outEnc = encKeep;
        }
        setRenderTarget(keep);
    }
    private void shadowObj(Obj3D o, Camera camera, Camera sc, Frustum fr) {
        if (!o.visible) return;
        if ((o.layers & camera.layers) != 0 && o instanceof Mesh) {
            Mesh m = (Mesh) o;
            if (m.castShadow && m.material != null && m.material.visible && m.geometry != null && (!m.frustumCulled || fr.intersects(m))) {
                Mat d = depthMat(m.material);
                draw(m, d, d.side, sc);
            }
        }
        for (int i = 0; i < o.children.size(); i++) shadowObj(o.children.get(i), camera, sc, fr);
    }
    private Mat depthMat(Mat src) {
        boolean own = (src.alphaMap != null && src.alphaTest > 0) || (src.map != null && src.alphaTest > 0);
        Mat d;
        if (own) { d = depthMats.get(src); if (d == null) { d = depth(); depthMats.put(src, d); } }
        else { if (depthBase == null) depthBase = depth(); d = depthBase; }
        int side = src.side == Mat.FRONT ? Mat.BACK : src.side == Mat.BACK ? Mat.FRONT : Mat.DOUBLE;
        if (d.side != side || d.map != src.map || d.alphaMap != src.alphaMap || d.alphaTest != src.alphaTest) d.needsUpdate();
        d.side = side; d.map = src.map; d.alphaMap = src.alphaMap; d.alphaTest = src.alphaTest; d.visible = src.visible;
        return d;
    }
    private static Mat depth() { Mat d = new Mat(Mat.DEPTH); d.depthPacking = true; return d; }

    // ---------------- 画一个网格 ----------------
    private final M4 mvT = new M4();
    private final M3 nmT = new M3();
    private void draw(Mesh o, Mat m, int side, Camera camera) {
        if (o.onBeforeRender != null) o.onBeforeRender.run();
        Geo g = o.geometry;
        Prog prog = program(m, o, side);
        boolean cw = o.matrixWorld.determinant() < 0;
        boolean newProg = curProg != prog.id;
        if (newProg) { GLES30.glUseProgram(prog.id); curProg = prog.id; }
        boolean newCam = curCam != camera;
        if (newProg || newCam) {
            prog.m4("projectionMatrix", camera.projectionMatrix);
            if (newCam) { curCam = camera; curMat = null; }
            prog.v3("cameraPosition", tv.setFromMatrixPosition(camera.matrixWorld));
            prog.b("isOrthographic", !camera.persp);
            prog.m4("viewMatrix", camera.matrixWorldInverse);
        }
        if (newProg || curMat != m) {
            curMat = m;
            unit = 0;
            refresh(prog, m, side);
            lightsTo(prog, m);
        }
        prog.b("receiveShadow", o.receiveShadow);
        mvT.multiplyMatrices(camera.matrixWorldInverse, o.matrixWorld);
        prog.m4("modelViewMatrix", mvT);
        prog.m3("normalMatrix", nmT.getNormalMatrix(mvT));
        prog.m4("modelMatrix", o.matrixWorld);
        setMaterial(m, side, cw);
        g.bind(prog);
        Attr idx = g.index;
        if (idx != null) {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, idx.count, idx.type, idx.offset);
            triangles += idx.count / 3;
        } else {
            Attr p = g.attr("position");
            if (p == null) return;
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, p.count);
            triangles += p.count / 3;
        }
        calls++;
    }
    private Prog program(Mat m, Mesh o, int side) {
        MState s = mstate.get(m);
        if (s == null) { s = new MState(); mstate.put(m, s); }
        Geo g = o.geometry;
        Attr col = g.attr("color");
        // 会换着色器的开关（three 里这几个属性的 setter 跨过 0 时 version++，这里直接算进键里）
        long bits = (g.attr("tangent") != null ? 1 : 0) | (col != null && col.itemSize == 4 ? 2 : 0) | (m.clearcoat > 0 ? 4 : 0) | (m.iridescence > 0 ? 8 : 0)
            | (m.sheen > 0 ? 16 : 0) | (m.transmission > 0 ? 32 : 0) | (m.alphaTest > 0 ? 64 : 0);
        long key = ((long) m.version << 32) | ((long) stamp << 12) | ((long) side << 10) | bits;
        Prog p = s.progs.get(key);
        if (p != null) return p;
        Tex e = m.isStandard() ? env : null;
        Programs.P par = Programs.params(m, side, g, o.skinned, e, outEnc, toneMapping, lights.size(), shadowsEnabled ? shadowLights.size() : 0, shadowsEnabled, physicallyCorrectLights);
        String vs, fs;
        Map<String, Val> u = m.uniforms;
        if (par.custom) { vs = m.vertexShader; fs = m.fragmentShader; }
        else { String[] lib = Programs.LIB.get(par.shaderID); vs = lib[0]; fs = lib[1]; }
        if (m.onBeforeCompile != null) { String[] r = m.onBeforeCompile.on(vs, fs, u); vs = r[0]; fs = r[1]; }
        // 先按参数和没展开的原文查（同 WebGLPrograms.getProgramCacheKey）：拼完整源码要展开几十段 #include，大车模一百多个材质每个都拼一遍要好几秒
        String pk = Programs.key(par) + "\u0001" + vs + "\u0001" + fs;
        p = byParams.get(pk);
        if (p != null) { p.usedTimes++; s.progs.put(key, p); return p; }
        String[] src = Programs.source(par, vs, fs);
        String k = src[0] + "\u0000" + src[1];
        p = programs.get(k);
        if (p == null) { p = new Prog(src[0], src[1], k); programs.put(k, p); }
        else p.usedTimes++;
        byParams.put(pk, p);
        s.progs.put(key, p);
        return p;
    }
    /** 材质不要了：它用过的程序少一个人用，没人用了删掉 */
    public void release(Mat m) {
        MState s = mstate.remove(m);
        Mat d = depthMats.remove(m);
        if (d != null) release(d);
        if (s == null) return;
        for (Prog p : new java.util.HashSet<>(s.progs.values())) {
            int n = 0;
            for (Prog q : s.progs.values()) if (q == p) n++;
            p.usedTimes -= n;
            if (p.usedTimes <= 0) { programs.remove(p.key); byParams.values().remove(p); p.dispose(); if (curProg == p.id) curProg = -1; }
        }
    }
    public int programCount() { return programs.size(); }

    // ---------------- 材质参数（照 WebGLMaterials） ----------------
    private void refresh(Prog p, Mat m, int side) {
        p.f("toneMappingExposure", toneMappingExposure);
        if (m.type == Mat.SHADER) { for (Map.Entry<String, Val> e : m.uniforms.entrySet()) p.any(e.getKey(), e.getValue().v, this); return; }
        if (m.type == Mat.SHADOW) { p.col("color", m.color); p.f("opacity", m.opacity); return; }
        // common
        p.f("opacity", m.opacity);
        p.col("diffuse", m.color);
        if (m.isStandard()) p.v3("emissive", m.emissive.r * m.emissiveIntensity, m.emissive.g * m.emissiveIntensity, m.emissive.b * m.emissiveIntensity);
        if (m.map != null) p.tex("map", m.map, this);
        if (m.alphaMap != null) p.tex("alphaMap", m.alphaMap, this);
        if (m.bumpMap != null) { p.tex("bumpMap", m.bumpMap, this); p.f("bumpScale", side == Mat.BACK ? -m.bumpScale : m.bumpScale); }
        if (m.emissiveMap != null) p.tex("emissiveMap", m.emissiveMap, this);
        if (m.normalMap != null) { p.tex("normalMap", m.normalMap, this); float k = side == Mat.BACK ? -1 : 1; p.v2("normalScale", m.normalScaleX * k, m.normalScaleY * k); }
        if (m.alphaTest > 0) p.f("alphaTest", m.alphaTest);
        Tex e = m.isStandard() ? env : null;
        if (e != null) { p.tex("envMap", e, this); p.f("flipEnvMap", 1); p.f("reflectivity", m.reflectivity()); p.f("ior", m.ior); p.f("refractionRatio", 0.98f); }
        if (m.lightMap != null) { p.tex("lightMap", m.lightMap, this); p.f("lightMapIntensity", m.lightMapIntensity * (physicallyCorrectLights ? 1 : (float) Math.PI)); }
        if (m.aoMap != null) { p.tex("aoMap", m.aoMap, this); p.f("aoMapIntensity", m.aoMapIntensity); }
        Tex uvm = m.map != null ? m.map : m.normalMap != null ? m.normalMap : m.bumpMap != null ? m.bumpMap : m.roughnessMap != null ? m.roughnessMap : m.metalnessMap != null ? m.metalnessMap
            : m.alphaMap != null ? m.alphaMap : m.emissiveMap != null ? m.emissiveMap : m.clearcoatMap != null ? m.clearcoatMap : m.clearcoatNormalMap != null ? m.clearcoatNormalMap
            : m.clearcoatRoughnessMap != null ? m.clearcoatRoughnessMap : m.iridescenceMap != null ? m.iridescenceMap : m.iridescenceThicknessMap != null ? m.iridescenceThicknessMap
            : m.specularIntensityMap != null ? m.specularIntensityMap : m.specularColorMap != null ? m.specularColorMap : m.transmissionMap != null ? m.transmissionMap
            : m.thicknessMap != null ? m.thicknessMap : m.sheenColorMap != null ? m.sheenColorMap : m.sheenRoughnessMap;
        if (uvm != null) { uvm.updateMatrix(); p.m3("uvTransform", uvm.matrix); }
        Tex uv2 = m.aoMap != null ? m.aoMap : m.lightMap;
        if (uv2 != null) { uv2.updateMatrix(); p.m3("uv2Transform", uv2.matrix); }
        if (!m.isStandard()) return;
        // standard
        p.f("roughness", m.roughness); p.f("metalness", m.metalness);
        if (m.roughnessMap != null) p.tex("roughnessMap", m.roughnessMap, this);
        if (m.metalnessMap != null) p.tex("metalnessMap", m.metalnessMap, this);
        if (e != null) p.f("envMapIntensity", m.envMapIntensity);
        if (!m.isPhysical()) return;
        // physical
        p.f("ior", m.ior);
        if (m.sheen > 0) {
            p.v3("sheenColor", m.sheenColor.r * m.sheen, m.sheenColor.g * m.sheen, m.sheenColor.b * m.sheen); p.f("sheenRoughness", m.sheenRoughness);
            if (m.sheenColorMap != null) p.tex("sheenColorMap", m.sheenColorMap, this);
            if (m.sheenRoughnessMap != null) p.tex("sheenRoughnessMap", m.sheenRoughnessMap, this);
        }
        if (m.clearcoat > 0) {
            p.f("clearcoat", m.clearcoat); p.f("clearcoatRoughness", m.clearcoatRoughness);
            if (m.clearcoatMap != null) p.tex("clearcoatMap", m.clearcoatMap, this);
            if (m.clearcoatRoughnessMap != null) p.tex("clearcoatRoughnessMap", m.clearcoatRoughnessMap, this);
            if (m.clearcoatNormalMap != null) {
                float k = side == Mat.BACK ? -1 : 1;
                p.v2("clearcoatNormalScale", m.clearcoatNormalScaleX * k, m.clearcoatNormalScaleY * k);
                p.tex("clearcoatNormalMap", m.clearcoatNormalMap, this);
            }
        }
        if (m.iridescence > 0) {
            p.f("iridescence", m.iridescence); p.f("iridescenceIOR", m.iridescenceIOR);
            p.f("iridescenceThicknessMinimum", m.iridescenceThickMin); p.f("iridescenceThicknessMaximum", m.iridescenceThickMax);
            if (m.iridescenceMap != null) p.tex("iridescenceMap", m.iridescenceMap, this);
            if (m.iridescenceThicknessMap != null) p.tex("iridescenceThicknessMap", m.iridescenceThicknessMap, this);
        }
        p.f("specularIntensity", m.specularIntensity); p.col("specularColor", m.specularColor);
        if (m.specularIntensityMap != null) p.tex("specularIntensityMap", m.specularIntensityMap, this);
        if (m.specularColorMap != null) p.tex("specularColorMap", m.specularColorMap, this);
    }
    private static final float[] ZERO9 = new float[27];
    private void lightsTo(Prog p, Mat m) {
        boolean need = m.isStandard() || m.type == Mat.SHADOW;
        if (!need) return;
        p.v3("ambientLightColor", 0, 0, 0);
        p.fv("lightProbe", ZERO9);
        for (int i = 0; i < lights.size(); i++) {
            p.v3("directionalLights[" + i + "].direction", dirDir[i * 3], dirDir[i * 3 + 1], dirDir[i * 3 + 2]);
            p.v3("directionalLights[" + i + "].color", dirColor[i * 3], dirColor[i * 3 + 1], dirColor[i * 3 + 2]);
        }
        if (!shadowsEnabled) return;
        for (int i = 0; i < shadowLights.size(); i++) {
            DirLight l = shadowLights.get(i);
            String s = "directionalLightShadows[" + i + "].";
            p.f(s + "shadowBias", l.bias); p.f(s + "shadowNormalBias", l.normalBias); p.f(s + "shadowRadius", l.radius); p.v2(s + "shadowMapSize", l.mapSize, l.mapSize);
            p.tex("directionalShadowMap[" + i + "]", l.shadowMap != null ? l.shadowMap.texture : null, this);
            p.m4("directionalShadowMatrix[" + i + "]", l.shadowMatrix);
        }
    }

    /** 视锥（剔除用，同 Frustum.setFromProjectionMatrix / intersectsObject） */
    static final class Frustum {
        final float[][] pl = new float[6][4];
        Frustum set(M4 m) {
            float[] e = m.e;
            float me0 = e[0], me1 = e[1], me2 = e[2], me3 = e[3], me4 = e[4], me5 = e[5], me6 = e[6], me7 = e[7];
            float me8 = e[8], me9 = e[9], me10 = e[10], me11 = e[11], me12 = e[12], me13 = e[13], me14 = e[14], me15 = e[15];
            p(0, me3 - me0, me7 - me4, me11 - me8, me15 - me12);
            p(1, me3 + me0, me7 + me4, me11 + me8, me15 + me12);
            p(2, me3 + me1, me7 + me5, me11 + me9, me15 + me13);
            p(3, me3 - me1, me7 - me5, me11 - me9, me15 - me13);
            p(4, me3 - me2, me7 - me6, me11 - me10, me15 - me14);
            p(5, me3 + me2, me7 + me6, me11 + me10, me15 + me14);
            return this;
        }
        private void p(int i, float x, float y, float z, float w) {
            float l = (float) Math.sqrt(x * x + y * y + z * z);
            if (l == 0) l = 1;
            pl[i][0] = x / l; pl[i][1] = y / l; pl[i][2] = z / l; pl[i][3] = w / l;
        }
        boolean intersects(Mesh m) {
            Geo g = m.geometry;
            if (g.attr("position") == null) return false;
            V3 c = g.sphereCenter().clone().applyMatrix4(m.matrixWorld);
            float r = g.sphereRadius() * m.matrixWorld.getMaxScaleOnAxis();
            for (float[] p : pl) if (p[0] * c.x + p[1] * c.y + p[2] * c.z + p[3] < -r) return false;
            return true;
        }
    }
}
