package com.x3card.car3d.ach;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * 成就墙的画布：OpenGL ES 3.0、24 位深度；按密度 1.5 画（车机密度 2 时少画四成多的像素），系统拉伸到窗口大小。
 * 盖在车况页的画布上面（setZOrderMediaOverlay：在别的 SurfaceView 上面、窗口里的控件下面，字和键照常画在它上面）。
 * 抗锯齿同车况页的设置（画布的 4 倍多重采样，显卡不支持就不开）。停着不动不出帧：要画才 requestRender。
 * 只在成就墙打开时有；关掉就从窗口里拿掉，GL 上下文和里面的东西跟着释放
 */
final class AchView extends GLSurfaceView {
    private final float dens;

    AchView(Context c, Renderer r, boolean aa) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(new Chooser(aa));
        setZOrderMediaOverlay(true);
        setRenderer(r);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /** 画布比窗口小多少（密度 > 1.5 时按 1.5 画） */
    float scale() { return Math.min(dens, 1.5f) / dens; }

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
        float k = scale();
        final int fw = Math.max(1, Math.round(w * k)), fh = Math.max(1, Math.round(h * k));
        getHolder().setFixedSize(fw, fh);
        post(() -> getHolder().setFixedSize(fw, fh)); // 在布局过程中直接设有时不生效：下一轮再设一次
    }

    /** 帧率（同车况页的设置：30 / 60）。每帧开头调 begin()（30 帧时不够 33 毫秒先睡一会儿），返回这一帧走了多少秒；停下不画时调 idle() */
    static final class Pacer {
        private volatile int fps = 60;
        private long lastT;

        void fps(int f) { fps = f; }

        float begin() {
            long t = SystemClock.uptimeMillis();
            if (lastT != 0 && fps == 30 && t - lastT < 30) { try { Thread.sleep(33 - (t - lastT)); } catch (InterruptedException ignored) { } t = SystemClock.uptimeMillis(); }
            float dt = lastT != 0 ? Math.min(0.05f, (t - lastT) / 1000f) : 1 / 60f;
            lastT = t;
            return dt;
        }

        void idle() { lastT = 0; }
    }
}
