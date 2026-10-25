package com.x3card.car3d.ach;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

/** 成就墙的 OpenGL ES 3.0 小工具（同琉光原生版的 G，只留成就墙用到的）：着色器、uniform 和贴图绑定的缓存、贴图。只在 GL 线程里用 */
final class G {
    private G() { }

    // ================= 着色器 =================
    static final String HEAD = "#version 300 es\nprecision highp float;\n";
    static final String HEAD_F = "#version 300 es\nprecision mediump float;\n";

    static int program(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, HEAD + vs));
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, HEAD_F + fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("car3d", "着色器链接失败：" + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e("car3d", "着色器编译失败：" + GLES30.glGetShaderInfoLog(s) + "\n" + src);
        return s;
    }

    // ================= GL 状态缓存：值没变就不再交给驱动（每帧几十次绘制、几百个 uniform 大多和上一帧一样） =================
    // 当前程序、当前贴图单元、每个单元绑着的贴图只在一个画布（GL 上下文）里有效。全屏页和小窗口各有一块画布、各在自己的 GL 线程里画，
    // 所以按线程各记一份；建画布时 resetCache()。贴图都经 bindTex 绑；删贴图时 GL 会把它从单元上解开，forget() 跟着清掉
    private static final class St { int prog = -1, unit = -1; final int[] bound = new int[32]; St() { java.util.Arrays.fill(bound, -1); } }
    private static final ThreadLocal<St> ST = new ThreadLocal<St>() { @Override protected St initialValue() { return new St(); } };
    static void resetCache() { ST.set(new St()); }
    static void activeUnit(int u) { St s = ST.get(); if (s.unit != u) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u); s.unit = u; } }
    static void bindTex(int u, int tex) { activeUnit(u); St s = ST.get(); if (s.bound[u] != tex) { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex); s.bound[u] = tex; } }
    private static void forget(int tex) { int[] b = ST.get().bound; for (int i = 0; i < b.length; i++) if (b[i] == tex) b[i] = 0; }

    /** 一个着色器程序和它的 uniform（位置按名字取，取过就记住；值和上次交给它的一样就不再交） */
    static final class Prog {
        final int id;
        private static final class U { final int loc; int n; float a, b, c, d; float[] m; int i = Integer.MIN_VALUE; U(int l) { loc = l; } }
        private final java.util.HashMap<String, U> us = new java.util.HashMap<>();
        Prog(String vs, String fs) { id = program(vs, fs); }
        private U uu(String n) { U x = us.get(n); if (x == null) { x = new U(GLES30.glGetUniformLocation(id, n)); us.put(n, x); } return x; }
        int u(String n) { return uu(n).loc; }
        void use() { St s = ST.get(); if (s.prog != id) { GLES30.glUseProgram(id); s.prog = id; } }
        void f(String n, float v) { U x = uu(n); if (x.n == 1 && x.a == v) return; x.n = 1; x.a = v; GLES30.glUniform1f(x.loc, v); }
        void f2(String n, float a, float b) { U x = uu(n); if (x.n == 2 && x.a == a && x.b == b) return; x.n = 2; x.a = a; x.b = b; GLES30.glUniform2f(x.loc, a, b); }
        void f3(String n, float a, float b, float c) { U x = uu(n); if (x.n == 3 && x.a == a && x.b == b && x.c == c) return; x.n = 3; x.a = a; x.b = b; x.c = c; GLES30.glUniform3f(x.loc, a, b, c); }
        void f4(String n, float a, float b, float c, float d) { U x = uu(n); if (x.n == 4 && x.a == a && x.b == b && x.c == c && x.d == d) return; x.n = 4; x.a = a; x.b = b; x.c = c; x.d = d; GLES30.glUniform4f(x.loc, a, b, c, d); }
        void i1(String n, int v) { U x = uu(n); if (x.i == v) return; x.i = v; GLES30.glUniform1i(x.loc, v); }
        void m4(String n, float[] m) {
            U x = uu(n);
            if (x.m != null && java.util.Arrays.equals(x.m, m)) return;
            if (x.m == null) x.m = new float[16];
            System.arraycopy(m, 0, x.m, 0, 16);
            GLES30.glUniformMatrix4fv(x.loc, 1, false, m, 0);
        }
        void tex(String n, int unit, int tex) { bindTex(unit, tex); U x = uu(n); if (x.i != unit) { x.i = unit; GLES30.glUniform1i(x.loc, unit); } }
    }

    // ================= 贴图 =================
    static int texture(Bitmap b, boolean mip) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        bindTex(0, t[0]);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mip ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        return t[0];
    }

    static void free(int tex) { if (tex != 0) { GLES30.glDeleteTextures(1, new int[] { tex }, 0); forget(tex); } }
}
