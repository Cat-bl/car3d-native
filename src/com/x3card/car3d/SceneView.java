package com.x3card.car3d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * 画 3D 场景的画布：OpenGL ES 3.0、24 位深度，RGBA8（背景在 GL 里先画，同网页版透明画布叠在 CSS 背景上）。
 * 像素比按画质（同网页版 renderer.setPixelRatio(min(devicePixelRatio, 画质的上限))）：画布按 setFixedSize 缩小画、系统拉伸到窗口大小。
 * 抗锯齿（设置里开）是画布的 4 倍多重采样，只能在建画布时定：改了设置就换一块新画布；显卡不支持就不开。
 * 停着不动不出帧（Stage 要画才 requestRender）。手势排进 GL 线程交给 Stage
 */
public class SceneView extends GLSurfaceView {
    final Stage stage;
    final float dens;
    private float ratio = 1;

    public SceneView(Context c, Stage stage, boolean aa) {
        super(c);
        this.stage = stage;
        dens = c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new Chooser(aa));
        setPreserveEGLContextOnPause(true);
        stage.attach(this);
        setRenderer(stage);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** 像素比上限（画质）：画布按 min(密度, 上限) / 密度 缩小 */
    void setRatio(float r) {
        ratio = r;
        if (getWidth() > 0) sizeTo(getWidth(), getHeight());
    }
    private void sizeTo(int w, int h) {
        float k = Math.min(dens, ratio) / dens;
        final int fw = Math.max(1, Math.round(w * k)), fh = Math.max(1, Math.round(h * k));
        // 先直接设：第一次布局时画布一建出来就是这个大小。不然建好后再改尺寸，主线程要等 GL 线程画完一帧（GLSurfaceView 的 onWindowResize），那时 GL 线程正在建车
        getHolder().setFixedSize(fw, fh);
        post(() -> getHolder().setFixedSize(fw, fh)); // 在布局过程中直接设有时不生效：下一轮再设一次
    }

    /** RGBA8 + 24 位深度；要抗锯齿时先找 4 倍多重采样的，找不到退回不开 */
    private static final class Chooser implements EGLConfigChooser {
        private final boolean aa;
        Chooser(boolean aa) { this.aa = aa; }

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay d) {
            EGLConfig c = aa ? find(egl, d, 4) : null;
            if (c == null) c = find(egl, d, 0);
            if (c == null) throw new IllegalArgumentException("没有能用的画布格式");
            return c;
        }

        private static EGLConfig find(EGL10 egl, EGLDisplay d, int samples) {
            int[] attr = {
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL10.EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0, EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE };
            int[] n = new int[1];
            if (!egl.eglChooseConfig(d, attr, null, 0, n) || n[0] <= 0) return null;
            EGLConfig[] list = new EGLConfig[n[0]];
            if (!egl.eglChooseConfig(d, attr, list, n[0], n)) return null;
            for (EGLConfig c : list) {
                if (get(egl, d, c, EGL10.EGL_RED_SIZE) == 8 && get(egl, d, c, EGL10.EGL_ALPHA_SIZE) == 8 && get(egl, d, c, EGL10.EGL_DEPTH_SIZE) == 24
                    && get(egl, d, c, EGL10.EGL_SAMPLES) == samples) return c;
            }
            return list[0];
        }

        private static int get(EGL10 egl, EGLDisplay d, EGLConfig c, int a) {
            int[] v = new int[1];
            return egl.eglGetConfigAttrib(d, c, a, v) ? v[0] : 0;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        sizeTo(w, h);
        final float cw = w / dens, ch = h / dens;
        queueEvent(() -> stage.resize(cw, ch));
    }

    /** 触摸：坐标换成 CSS 像素（网页版的单位），排进 GL 线程。多指（缩放、平移）只在调整车模时用，这里没有 */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: a = 0; break;
            case MotionEvent.ACTION_MOVE: a = 1; break;
            case MotionEvent.ACTION_UP: a = 2; break;
            case MotionEvent.ACTION_CANCEL: a = 3; break;
            case MotionEvent.ACTION_POINTER_DOWN: a = 3; break; // 第二根手指落下：这一下作废（同网页版：转车那一笔作废）
            default: return true;
        }
        final int act = a;
        final float x = e.getX() / dens, y = e.getY() / dens;
        final long t = e.getEventTime();
        queueEvent(() -> stage.touch(act, x, y, t));
        return true;
    }
}
