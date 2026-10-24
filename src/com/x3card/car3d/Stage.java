package com.x3card.car3d;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.Half;
import com.x3card.car3d.gl.M4;
import com.x3card.car3d.gl.RT;
import com.x3card.car3d.gl.Shapes;
import com.x3card.car3d.gl.Val;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Programs;
import com.x3card.car3d.gl.Renderer;
import com.x3card.car3d.gl.Tex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 画布的外壳（照网页版 demo3d/main.js，只有车况页一页）：按需渲染——画面不变一帧都不画，页面要动时 update() 返回要的帧率，动完就停；
 * 卡片看不见时全停。手势：按在车上拖 = 转车，没怎么动 = 点。给页面用的小工具：环境图、释放一棵树、画布贴图。
 * 全部在 GL 线程里跑（GLSurfaceView 的渲染线程）；外面来的事（车况、设置、手势）都排进这个线程
 */
public final class Stage implements GLSurfaceView.Renderer {
    /** 页面（同网页版 D3.add 的页面模块） */
    public interface Page {
        void load(Stage s);
        void unload();
        void resize(float w, float h, boolean portrait);
        /** 返回要的帧率：0 不用接着画 */
        int update(float dt);
        Obj3D scene();
        Camera camera();
        void after();
        boolean grab(float x, float y);
        void drag(float dx, float dy, float x, float y);
        void release(float vx, float x, float y, boolean cancel);
        void tap(float x, float y);
        void wake(boolean on);
        /** 这一帧除了图层 1（灯的点亮层、路面光束）别的都没变 */
        boolean still();
        /** 连着动的帧：不用多重采样（画进离屏缓冲再 FXAA） */
        boolean fast();
        /** 画这一帧之前（地面反射在这里画） */
        void pre(Renderer r);
    }

    final AssetManager assets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SceneView view;
    public Renderer renderer;
    private Page page, pending;
    private boolean ready, visible = true, started;
    private float W = 1, H = 1;
    private long lastT, lastDraw;
    private boolean raf, timerOn;
    int fpsCap = 60;
    /** 画质的像素比上限（设置里改）、抗锯齿（建画布时定） */
    float ratio = 1;
    final boolean aa;
    final Bg bg = new Bg();
    /** 画质（主线程交过来，换页面时按它建后期效果） */
    volatile Settings.Qual fx;
    private Post post;

    public Stage(AssetManager a, boolean aa) { assets = a; this.aa = aa; }
    void attach(SceneView v) { view = v; }
    public float width() { return W; }
    /** 画布的像素（离屏缓冲按它） */
    public int bufW() { return bw; }
    public int bufH() { return bh; }
    public float height() { return H; }
    /** 1rem（同网页版：高度 / 10.52） */
    public float rem() { return H / 10.52f; }

    // ================= 按需渲染 =================
    /** 要求画一帧；fps 不到 55 时按这个帧率晚一点再画；帧率设成 30 时最多 30 帧（同 main.js 的 invalidate） */
    public void invalidate(int fps) {
        if (fps == 0) dirty = true;
        if (!started || !visible || !ready || raf) return;
        fps = Math.min(fps == 0 ? 60 : fps, fpsCap);
        if (fps < 55) {
            if (timerOn) return;
            long wait = Math.max(0, 1000 / fps - (SystemClock.uptimeMillis() - lastDraw));
            timerOn = true;
            main.postDelayed(() -> { timerOn = false; if (view != null) view.queueEvent(this::kick); }, wait);
            return;
        }
        kick();
    }
    public void invalidate() { invalidate(0); }
    private void kick() { if (raf || !started || !visible || !ready) return; raf = true; view.requestRender(); }
    private boolean dirty = true;

    // ================= GLSurfaceView.Renderer =================
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig c) {
        try {
            if (Programs.chunk("bsdfs") == null) Programs.load(new String(Half.readAll(assets.open("three-shaders.json")), "UTF-8"));
        } catch (Exception e) { Log.w("car3d", "着色器读不出来", e); }
        com.x3card.car3d.gl.Prog.initCache();
        renderer = new Renderer();
        renderer.toneMapping = Programs.ACES;
        renderer.outputEncoding = Programs.SRGB;
        renderer.clearR = renderer.clearG = renderer.clearB = renderer.clearA = 0;
        started = true;
        // 建车放到这一帧画完以后：画布第一次定尺寸时主线程要等第一帧画完（GLSurfaceView 的 onWindowResize），在这里建车主线程就跟着卡住，大车模会弹"应用无响应"
        if (pending != null) main.post(() -> { if (view != null) view.queueEvent(this::activatePending); });
    }
    private int bw = 1, bh = 1;
    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        bw = w; bh = h;
        renderer.setSize(w, h);
        if (page != null && ready) page.resize(W, H, W < H); // 像素比变了（换画质）：按画布像素建的缓冲跟着变
        invalidate();
    }
    @Override public void onDrawFrame(GL10 gl) {
        raf = false;
        if (page == null || !ready) { GLES30.glClearColor(0, 0, 0, 0); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return; }
        frame(SystemClock.uptimeMillis());
    }
    private void frame(long t) {
        float dt = lastT != 0 ? Math.min(0.1f, (t - lastT) / 1000f) : 1 / 60f; // 停了一阵之后的第一帧按 1/60 秒算
        lastT = t;
        int need = page.update(dt);
        boolean act = g != null && "rot".equals(g.mode); // 手指正在转车：视角是手势直接改的，页面自己不觉得在动，也算连着动
        renderer.setRenderTarget(null);
        renderer.resetState();
        page.pre(renderer);
        renderer.setRenderTarget(null);
        if (post != null) { // 画质高 / 极致：后期效果（背景先画在画布上，最后一道叠上去）
            renderer.clear(true, true);
            bg.draw(renderer, bw, bh, W, H);
            post.render(page.scene(), page.camera(), bw, bh);
        } else if (!act && page.still() && !(renderer.shadowsEnabled && renderer.shadowNeedsUpdate)) {
            if (stillOk && !dirty && stillSame()) stillDraw();
            else if (need > 0) stillSave(); // 后面还要接着画（灯在动）：这一帧照常画，顺便存下来
            else { stillOk = false; plain(); }
        } else {
            stillOk = false;
            if ((need > 0 || act) && page.fast() && aa) fastDraw();
            else plain(); // 停下来的那一帧照常按多重采样画
        }
        dirty = false;
        if (need == 0 && !act) { stillDrop(); fastDrop(); } // 不动了：存的画面、离屏缓冲用不上了，显存还回去（贴图用的材质留着）
        drawn++;
        stashFree(false);
        page.after();
        lastDraw = SystemClock.uptimeMillis();
        if (need > 0 || act) invalidate(need > 0 ? need : 60); else lastT = 0;
    }
    /** 照常画一帧：背景、场景 */
    private void plain() {
        renderer.clear(true, true);
        bg.draw(renderer, bw, bh, W, H);
        renderer.autoClear = false;
        renderer.render(page.scene(), page.camera());
        renderer.autoClear = true;
    }

    // ================= 只有灯在变：静止的部分画一次存下来（同 main.js 的 still） =================
    // 车停着、只有灯在动（氛围灯呼吸、转向灯、双闪、音乐律动）时：第一帧照常画，顺便把画面（背景 + 图层 0）拷成贴图、深度另画一遍存进深度贴图；
    // 之后每帧把颜色连同深度贴回去，再只画图层 1（灯的点亮层、路面光束）。别的一变（外面叫的 invalidate）就作废、照常整帧画。
    // 只画图层 1 时灯光也要算进来（灯光加上图层 1）：不然灯的数量变了，下一帧整帧画时每个材质都要重新取着色器程序
    private static final int OV = 2;
    private RT stillC, stillD;
    private boolean stillOk;
    private Obj3D stillScene;
    private final M4 stillM = new M4(), m4 = new M4();
    private Mat stillQ, fastQ;
    private Obj3D stillSc, fastSc;
    private final Camera qcam = Camera.ortho(-1, 1, 1, -1, 0, 1);
    private final Map<Mat, Mat> dm = new IdentityHashMap<>();
    private RT fastRT;

    /** 盖满画布的三角形（贴回存下的画面、FXAA）。材质一直留着：释放了会把着色器程序一起删掉，下次又要编译 */
    private Obj3D quad(Mat m) {
        m.blending = Mat.NO_BLENDING; m.toneMapped = false;
        Mesh q = new Mesh(Shapes.screen(), m);
        q.frustumCulled = false;
        Obj3D sc = new Obj3D();
        sc.add(q);
        return sc;
    }
    private void stillBuf() {
        if (stillC != null && stillC.width == bw && stillC.height == bh) return;
        stillDrop();
        stillC = new RT(bw, bh, GLES30.GL_UNSIGNED_BYTE, false, false, 0, GLES30.GL_NEAREST);
        stillD = new RT(bw, bh, GLES30.GL_UNSIGNED_BYTE, true, true, 0, GLES30.GL_NEAREST);
        if (stillQ == null) {
            stillQ = Mat.shader("void main() { gl_Position = vec4(position.xy, 0.0, 1.0); }",
                "uniform sampler2D tC;\nuniform highp sampler2D tD;\nuniform vec2 uS;\nvoid main() {\n  vec2 uv = gl_FragCoord.xy / uS;\n  gl_FragColor = texture2D(tC, uv);\n  gl_FragDepth = texture2D(tD, uv).r;\n}");
            stillQ.depthFunc = GLES30.GL_ALWAYS; // 深度原样写回去（深度测试开着才写得进去）
            stillQ.uniforms.put("tC", new Val(null)); stillQ.uniforms.put("tD", new Val(null)); stillQ.uniforms.put("uS", new Val(null));
            stillSc = quad(stillQ);
        }
        stillQ.uniforms.get("tC").v = stillC.texture; stillQ.uniforms.get("tD").v = stillD.depthTexture; stillQ.uniforms.get("uS").v = new float[] { bw, bh };
    }
    private void stillDrop() {
        stillOk = false;
        if (stillC == null) return;
        stillC.dispose(); stillD.dispose();
        stillC = stillD = null;
    }
    /** 镜头、场景、画布大小都没变 */
    private boolean stillSame() {
        Camera c = page.camera();
        if (stillC == null || stillScene != page.scene() || stillC.width != bw || stillC.height != bh) return false;
        float[] a = stillM.e, b = m4.multiplyMatrices(c.projectionMatrix, c.matrixWorldInverse).e;
        for (int i = 0; i < 16; i++) if (Math.abs(a[i] - b[i]) > 1e-7f) return false;
        return true;
    }
    /** 写深度用的材质：只写深度，正反面、深度测试、偏移、透明度裁切照原来的材质 */
    private Mat depthMat(Mat m) {
        Mat d = dm.get(m);
        if (d == null) { d = Mat.basic(); d.colorWrite = false; dm.put(m, d); }
        float at = m.alphaTest;
        Tex mp = at > 0 ? m.map : null, am = at > 0 ? m.alphaMap : null;
        if (d.side != m.side || (d.alphaTest > 0) != (at > 0) || (d.map != null) != (mp != null) || (d.alphaMap != null) != (am != null)) d.needsUpdate();
        d.side = m.side; d.visible = m.visible && m.depthWrite;
        d.depthTest = m.depthTest; d.depthFunc = m.depthFunc;
        d.polygonOffset = m.polygonOffset; d.polygonOffsetFactor = m.polygonOffsetFactor; d.polygonOffsetUnits = m.polygonOffsetUnits;
        d.alphaTest = at; d.map = mp; d.alphaMap = am; d.opacity = at > 0 ? m.opacity : 1;
        return d;
    }
    /** 照常画一帧（只画图层 0），拷下颜色、另画深度，再把图层 1 叠上去 */
    private void stillSave() {
        Obj3D sc = page.scene();
        Camera cam = page.camera();
        int mask = cam.layers;
        stillBuf();
        sc.traverse(o -> { if (o instanceof com.x3card.car3d.gl.DirLight) o.layers |= OV; });
        renderer.clear(true, true);
        bg.draw(renderer, bw, bh, W, H);
        cam.layers = 1;
        renderer.autoClear = false;
        renderer.render(sc, cam);
        stillC.copyFromScreen();
        final List<Object[]> swap = new ArrayList<>();
        final Map<Mat, Boolean> used = new IdentityHashMap<>();
        sc.traverse(o -> {
            if (!(o instanceof Mesh) || (o.layers & 1) == 0) return;
            Mesh m = (Mesh) o;
            if (m.material == null) return;
            used.put(m.material, true);
            swap.add(new Object[] { m, m.material });
            m.material = depthMat(m.material);
        });
        renderer.setRenderTarget(stillD);
        renderer.clear(false, true);
        renderer.render(sc, cam);
        renderer.setRenderTarget(null);
        for (Object[] x : swap) ((Mesh) x[0]).material = (Mat) x[1];
        for (java.util.Iterator<Map.Entry<Mat, Mat>> it = dm.entrySet().iterator(); it.hasNext(); ) { Map.Entry<Mat, Mat> e = it.next(); if (!used.containsKey(e.getKey())) { e.getValue().dispose(); it.remove(); } }
        cam.layers = OV; // 点亮层叠上去（画布上还是刚才画的颜色和深度）
        renderer.render(sc, cam);
        renderer.autoClear = true;
        cam.layers = mask;
        stillOk = true; stillScene = sc;
        stillM.multiplyMatrices(cam.projectionMatrix, cam.matrixWorldInverse);
    }
    private void stillDraw() {
        Camera cam = page.camera();
        int mask = cam.layers;
        renderer.autoClear = false;
        renderer.render(stillSc, qcam); // 存下的颜色、深度贴回去
        cam.layers = OV;
        renderer.render(page.scene(), cam);
        renderer.autoClear = true;
        cam.layers = mask;
    }
    /** 拆页时：画深度用的材质也释放（原材质都没了） */
    private void stillFree() {
        stillDrop();
        for (Mat d : dm.values()) d.dispose();
        dm.clear(); stillScene = null;
    }

    // ================= 连着动的帧：不用多重采样（同 main.js 的 fast） =================
    // 车机（Adreno 610）是分块画的：画布开着 4 倍多重采样时块数是 4 倍，每块都要把场景里的每次绘制重放一遍。所以开着抗锯齿时，
    // 转车、镜头转过去这类连着动的帧画进一张不开多重采样的离屏缓冲，再过一遍 FXAA 贴到画布上；停下来那一帧照常按多重采样直接画在画布上。
    // 离屏缓冲标成 XR 的：着色器按 sRGB 输出（和画到画布上同一套程序），存成普通 8 位不做转换；深度用 24 位的深度贴图
    private static final String FXAA = "uniform sampler2D tC;\nuniform vec2 uR;\n" +
        "float L(vec4 c) { return dot(c.rgb, vec3(0.299, 0.587, 0.114)) * 0.7 + c.a * 0.3; }\n" +
        "void main() {\n  vec2 uv = gl_FragCoord.xy * uR;\n  vec4 cM = texture2D(tC, uv);\n" +
        "  float lM = L(cM), lNW = L(texture2D(tC, uv + vec2(-1.0, 1.0) * uR)), lNE = L(texture2D(tC, uv + uR)),\n" +
        "    lSW = L(texture2D(tC, uv - uR)), lSE = L(texture2D(tC, uv + vec2(1.0, -1.0) * uR));\n" +
        "  float mn = min(lM, min(min(lNW, lNE), min(lSW, lSE))), mx = max(lM, max(max(lNW, lNE), max(lSW, lSE)));\n" +
        "  if (mx - mn < max(0.04, mx * 0.12)) { gl_FragColor = cM; return; }\n" +
        "  vec2 d = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));\n" +
        "  float red = max((lNW + lNE + lSW + lSE) * 0.03125, 1.0 / 128.0);\n" +
        "  d = clamp(d / (min(abs(d.x), abs(d.y)) + red), vec2(-8.0), vec2(8.0)) * uR;\n" +
        "  vec4 a = 0.5 * (texture2D(tC, uv - d / 6.0) + texture2D(tC, uv + d / 6.0));\n" +
        "  vec4 b = a * 0.5 + 0.25 * (texture2D(tC, uv - d * 0.5) + texture2D(tC, uv + d * 0.5));\n" +
        "  float lb = L(b);\n  gl_FragColor = lb < mn || lb > mx ? a : b;\n}";
    private void fastDraw() {
        if (fastRT == null || fastRT.width != bw || fastRT.height != bh) {
            fastDrop();
            fastRT = new RT(bw, bh, GLES30.GL_UNSIGNED_BYTE, true, true, 0, GLES30.GL_LINEAR);
            fastRT.xr = true;
            if (fastQ == null) {
                fastQ = Mat.shader("void main() { gl_Position = vec4(position.xy, 0.0, 1.0); }", FXAA);
                fastQ.depthTest = false; fastQ.depthWrite = false;
                fastQ.uniforms.put("tC", new Val(null)); fastQ.uniforms.put("uR", new Val(null));
                fastSc = quad(fastQ);
            }
            fastQ.uniforms.get("tC").v = fastRT.texture; fastQ.uniforms.get("uR").v = new float[] { 1f / bw, 1f / bh };
        }
        renderer.setRenderTarget(fastRT);
        renderer.clear(true, true);
        bg.draw(renderer, bw, bh, W, H);
        renderer.autoClear = false;
        renderer.render(page.scene(), page.camera());
        renderer.setRenderTarget(null);
        renderer.render(fastSc, qcam);
        renderer.autoClear = true;
    }
    private void fastDrop() {
        if (fastRT == null) return;
        fastRT.dispose();
        fastRT = null;
    }

    // ================= 页面 =================
    public void setPage(Page p) {
        if (!started || pending != null) { pending = p; return; } // 还没轮到建的那个直接换成新的
        activate(p);
    }
    private void activatePending() {
        if (pending == null) return;
        Page p = pending; pending = null;
        activate(p);
    }
    private void activate(Page p) {
        if (page != null) { stillFree(); fastDrop(); page.unload(); }
        if (post != null) { post.dispose(); post = null; }
        page = p; ready = false;
        p.load(this);
        Settings.Qual f = fx;
        if (f != null && (f.bloom || f.ao)) post = new Post(renderer, f.ao, f.bloom, aa);
        ready = true;
        p.resize(W, H, W < H);
        invalidate();
    }
    void resize(float w, float h) {
        W = w; H = h;
        if (page != null && ready) page.resize(W, H, W < H);
        invalidate();
    }
    void setVisible(boolean v) {
        if (v == visible) return;
        visible = v;
        if (page != null && ready) page.wake(v);
        if (v) invalidate(); else lastT = 0;
    }

    // ================= 手势（同 main.js：按在车上拖 = 转车；没怎么动 = 点） =================
    private static final class G { float x0, y0, x, y, vx; long t0, lt; String mode = ""; boolean rot; }
    private G g;
    void touch(int act, float x, float y, long t) {
        if (page == null || !ready) return;
        if (act == 0) {
            g = new G();
            g.x0 = g.x = x; g.y0 = g.y = y; g.t0 = g.lt = t;
            g.rot = page.grab(x, y);
            return;
        }
        if (g == null) return;
        if (act == 1) {
            float dx = x - g.x, dy = y - g.y;
            g.vx = dx / Math.max(1, t - g.lt); g.lt = t;
            g.x = x; g.y = y;
            if (g.mode.isEmpty()) {
                if (Math.abs(x - g.x0) < 8 && Math.abs(y - g.y0) < 8) return;
                g.mode = g.rot ? "rot" : "none";
            }
            if ("rot".equals(g.mode)) { page.drag(dx, dy, x, y); invalidate(); }
            return;
        }
        G s = g;
        g = null;
        if (act == 3) { if ("rot".equals(s.mode)) page.release(0, s.x, s.y, true); return; }
        if ("rot".equals(s.mode)) { page.release(s.vx, x, y, false); invalidate(); return; }
        if (s.mode.isEmpty() && t - s.t0 < 500) page.tap(x, y);
    }

    // ================= 给页面用的小工具 =================
    private final Map<String, Tex> envs = new HashMap<>();
    /** 环境图：'studio' 影棚（env-hdr.js 经 PMREM）、'neutral' model-viewer 的中性环境；两张都是 env-pmrem.mjs 预先算好的。一直留着（和网页版的 hdr 一样） */
    public Tex env(String kind) {
        Tex t = envs.get(kind);
        if (t != null) return t;
        try { t = Half.rgbe(assets.open("env-" + kind + ".rgbe")); t.keep = true; }
        catch (Exception e) { Log.w("car3d", "环境图读不出来：" + kind, e); return null; }
        envs.put(kind, t);
        return t;
    }

    /** 拆下来的材质先不释放（同 main.js 的 stash：它编译好的着色器挂在上面，同样的新材质直接接着用，不用再编译）；再画出一帧后才真正释放 */
    private final List<Object[]> stash = new ArrayList<>();
    private int drawn;
    void stashFree(boolean all) {
        for (int i = stash.size() - 1; i >= 0; i--) {
            Object[] x = stash.get(i);
            if (all || (int) x[1] < drawn) { ((Mat) x[0]).dispose(); stash.remove(i); }
        }
    }
    /** 释放一棵树里的几何体、材质、贴图（同 main.js 的 dispose） */
    public void dispose(Obj3D root) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        root.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            if (m.geometry != null && seen.put(m.geometry, true) == null) m.geometry.dispose();
            Mat mt = m.material;
            if (mt != null && seen.put(mt, true) == null) {
                for (Tex t : texOf(mt)) if (t != null && seen.put(t, true) == null) t.dispose();
                stash.add(new Object[] { mt, drawn });
            }
        });
    }
    static Tex[] texOf(Mat m) {
        return new Tex[] { m.map, m.aoMap, m.emissiveMap, m.normalMap, m.bumpMap, m.roughnessMap, m.metalnessMap, m.alphaMap, m.lightMap, m.clearcoatMap, m.clearcoatRoughnessMap,
            m.clearcoatNormalMap, m.specularIntensityMap, m.specularColorMap, m.sheenColorMap, m.sheenRoughnessMap, m.transmissionMap, m.thicknessMap, m.iridescenceMap, m.iridescenceThicknessMap };
    }
    public interface Draw { void on(Canvas c, int w, int h); }
    /** 画一张画布贴图（同 main.js 的 canvasTex：sRGB、上下翻转上传） */
    public Tex canvasTex(int w, int h, Draw d, boolean color) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.on(new Canvas(bm), w, h);
        Tex t = Tex.canvas(bm, color);
        bm.recycle();
        return t;
    }
    /** 在主线程上晚一点做，做的时候排回 GL 线程（同网页版的 setTimeout） */
    public Runnable later(long ms, Runnable r) {
        Runnable w = () -> { if (view != null) view.queueEvent(r); };
        main.postDelayed(w, ms);
        return w;
    }
    public void cancel(Runnable w) { if (w != null) main.removeCallbacks(w); }
    /** 交给主线程（界面） */
    public void ui(Runnable r) { main.post(r); }
}
