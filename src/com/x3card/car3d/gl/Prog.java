package com.x3card.car3d.gl;

import android.opengl.GLES30;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** 编译好的着色器程序：属性位置、uniform 位置，uniform 的值记着、没变就不再交给 GL（同 three 的 WebGLUniforms 缓存） */
public final class Prog {
    public final int id;
    public final String key;
    public int usedTimes = 1;
    /** 计数（打开时打日志用）：现编译的几个、花了多久，从存好的二进制载入的几个 */
    public static int compiled, loaded;
    public static long compileMs;
    final Map<String, Integer> attribs = new HashMap<>();
    private final Map<String, U> uni = new HashMap<>();

    static final class U {
        final int loc, type, size;
        float[] cache;
        int icache = Integer.MIN_VALUE;
        U(int loc, int type, int size) { this.loc = loc; this.type = type; this.size = size; }
    }

    Prog(String vs, String fs, String key) {
        this.key = key;
        File bf = binFile(vs, fs);
        int pid = bf != null ? loadBin(bf) : 0;
        if (pid != 0) loaded++;
        else {
            long t0 = android.os.SystemClock.uptimeMillis();
            int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
            pid = GLES30.glCreateProgram();
            GLES30.glAttachShader(pid, v);
            GLES30.glAttachShader(pid, f);
            if (bf != null) GLES30.glProgramParameteri(pid, GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES30.GL_TRUE);
            GLES30.glLinkProgram(pid);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(pid) + "\n" + GLES30.glGetShaderInfoLog(v) + "\n" + GLES30.glGetShaderInfoLog(f);
                Log.w("car3d", "着色器链接失败：" + log);
                dumpErr(vs, GLES30.glGetShaderInfoLog(v));
                dumpErr(fs, GLES30.glGetShaderInfoLog(f));
            } else if (bf != null) saveBin(pid, bf);
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            compiled++; compileMs += android.os.SystemClock.uptimeMillis() - t0;
        }
        id = pid;
        int[] n = new int[1], len = new int[1], size = new int[1], type = new int[1];
        byte[] name = new byte[256];
        GLES30.glGetProgramiv(id, GLES30.GL_ACTIVE_ATTRIBUTES, n, 0);
        for (int i = 0; i < n[0]; i++) {
            GLES30.glGetActiveAttrib(id, i, 256, len, 0, size, 0, type, 0, name, 0);
            String s = new String(name, 0, len[0]);
            attribs.put(s, GLES30.glGetAttribLocation(id, s));
        }
        GLES30.glGetProgramiv(id, GLES30.GL_ACTIVE_UNIFORMS, n, 0);
        for (int i = 0; i < n[0]; i++) {
            GLES30.glGetActiveUniform(id, i, 256, len, 0, size, 0, type, 0, name, 0);
            String s = new String(name, 0, len[0]);
            int loc = GLES30.glGetUniformLocation(id, s);
            if (s.endsWith("[0]") && size[0] > 1) { // 数组：每个元素各记一个名字（directionalLights[0].color 这种结构体数组 GL 本来就一个个列）
                String b = s.substring(0, s.length() - 3);
                for (int k = 0; k < size[0]; k++) uni.put(b + "[" + k + "]", new U(GLES30.glGetUniformLocation(id, b + "[" + k + "]"), type[0], 1));
                uni.put(b, new U(loc, type[0], size[0]));
            } else {
                uni.put(s, new U(loc, type[0], size[0]));
                if (s.endsWith("[0]")) uni.put(s.substring(0, s.length() - 3), new U(loc, type[0], size[0]));
            }
        }
    }
    // ---------------- 编译结果存盘（glGetProgramBinary）：下次打开直接载入，不用再编译 ----------------
    // 车机的显卡驱动编译一个车漆这样的大着色器要几十到上百毫秒，大车模一次要编二三十个。文件名是 驱动 + 两段源码 的摘要：
    // 驱动升级了名字就变了；载入失败（驱动不认这份二进制）照常编译、再存一份。目录超过 24MB 删最久没用的
    private static File binDir;
    private static String driver;
    private static boolean binOk;
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "shader-cache"); t.setDaemon(true); return t; });
    /** 存在哪（应用的缓存目录；不给就不存） */
    public static void cacheDir(File dir) { binDir = dir; }
    /** 画布建好后在 GL 线程里调一次：驱动支不支持取出二进制、驱动是哪个 */
    public static void initCache() {
        int[] n = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_NUM_PROGRAM_BINARY_FORMATS, n, 0);
        binOk = binDir != null && n[0] > 0;
        driver = GLES30.glGetString(GLES30.GL_VENDOR) + "|" + GLES30.glGetString(GLES30.GL_RENDERER) + "|" + GLES30.glGetString(GLES30.GL_VERSION);
        if (binOk) binDir.mkdirs();
    }
    private static File binFile(String vs, String fs) {
        if (!binOk) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(driver.getBytes("UTF-8")); md.update((byte) 0);
            md.update(vs.getBytes("UTF-8")); md.update((byte) 0);
            md.update(fs.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return new File(binDir, sb + ".bin");
        } catch (Exception e) { return null; }
    }
    /** 载入存好的二进制，成功返回程序号，不成功返回 0（文件不对就删掉） */
    private static int loadBin(File f) {
        if (!f.isFile() || f.length() < 8) return 0;
        byte[] a;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            a = new byte[(int) f.length()];
            int o = 0;
            for (int n; o < a.length && (n = in.read(a, o, a.length - o)) > 0; ) o += n;
            if (o != a.length) return 0;
        } catch (Exception e) { return 0; }
        int fmt = java.nio.ByteBuffer.wrap(a).getInt(0);
        java.nio.ByteBuffer bin = java.nio.ByteBuffer.allocateDirect(a.length - 4);
        bin.put(a, 4, a.length - 4).position(0);
        int pid = GLES30.glCreateProgram();
        GLES30.glProgramBinary(pid, fmt, bin, a.length - 4);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, ok, 0);
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) ok[0] = 0; // 驱动不认这个格式时报 INVALID_ENUM
        if (ok[0] == 0) { GLES30.glDeleteProgram(pid); f.delete(); return 0; }
        f.setLastModified(System.currentTimeMillis());
        return pid;
    }
    private static void saveBin(int pid, final File f) {
        int[] len = new int[1], fmt = new int[1];
        GLES30.glGetProgramiv(pid, GLES30.GL_PROGRAM_BINARY_LENGTH, len, 0);
        if (len[0] <= 0) return;
        java.nio.ByteBuffer bin = java.nio.ByteBuffer.allocateDirect(len[0]);
        GLES30.glGetProgramBinary(pid, len[0], len, 0, fmt, 0, bin);
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) len[0] = 0;
        if (len[0] <= 0) return;
        final byte[] a = new byte[4 + len[0]];
        java.nio.ByteBuffer.wrap(a).putInt(fmt[0]);
        bin.position(0);
        bin.get(a, 4, len[0]);
        IO.execute(() -> { // 写文件放到后台
            File tmp = new File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(a); }
            catch (Exception e) { tmp.delete(); return; }
            if (!tmp.renameTo(f)) tmp.delete();
            File[] all = f.getParentFile().listFiles();
            if (all == null) return;
            long total = 0;
            for (File x : all) total += x.length();
            if (total <= 24L << 20) return;
            java.util.Arrays.sort(all, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
            for (File x : all) { if (total <= 16L << 20) break; total -= x.length(); x.delete(); }
        });
    }

    private static void dumpErr(String src, String log) {
        if (log == null || log.isEmpty()) return;
        String[] lines = src.split("\n");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("0:(\\d+)").matcher(log);
        if (m.find()) {
            int at = Integer.parseInt(m.group(1));
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(1, at - 4); i <= Math.min(lines.length, at + 3); i++) sb.append(i == at ? "> " : "  ").append(i).append(": ").append(lines[i - 1]).append('\n');
            Log.w("car3d", log + "\n" + sb);
        }
    }
    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        return s;
    }
    public boolean has(String n) { return uni.containsKey(n); }
    public void dispose() { GLES30.glDeleteProgram(id); }

    private U u(String n) { return uni.get(n); }
    public void f(String n, float v) {
        U u = u(n); if (u == null) return;
        if (u.cache != null && u.cache[0] == v) return;
        if (u.cache == null) u.cache = new float[1];
        u.cache[0] = v;
        GLES30.glUniform1f(u.loc, v);
    }
    public void i(String n, int v) {
        U u = u(n); if (u == null || u.icache == v) return;
        u.icache = v;
        GLES30.glUniform1i(u.loc, v);
    }
    public void b(String n, boolean v) { i(n, v ? 1 : 0); }
    public void v2(String n, float x, float y) {
        U u = u(n); if (u == null) return;
        if (u.cache != null && u.cache[0] == x && u.cache[1] == y) return;
        if (u.cache == null) u.cache = new float[2];
        u.cache[0] = x; u.cache[1] = y;
        GLES30.glUniform2f(u.loc, x, y);
    }
    public void v3(String n, float x, float y, float z) {
        U u = u(n); if (u == null) return;
        if (u.cache != null && u.cache[0] == x && u.cache[1] == y && u.cache[2] == z) return;
        if (u.cache == null) u.cache = new float[3];
        u.cache[0] = x; u.cache[1] = y; u.cache[2] = z;
        GLES30.glUniform3f(u.loc, x, y, z);
    }
    public void v3(String n, V3 v) { v3(n, v.x, v.y, v.z); }
    public void col(String n, Col c) { v3(n, c.r, c.g, c.b); }
    public void v4(String n, float x, float y, float z, float w) {
        U u = u(n); if (u == null) return;
        if (u.cache != null && u.cache[0] == x && u.cache[1] == y && u.cache[2] == z && u.cache[3] == w) return;
        if (u.cache == null) u.cache = new float[4];
        u.cache[0] = x; u.cache[1] = y; u.cache[2] = z; u.cache[3] = w;
        GLES30.glUniform4f(u.loc, x, y, z, w);
    }
    public void m3(String n, M3 m) {
        U u = u(n); if (u == null) return;
        if (same(u, m.e)) return;
        GLES30.glUniformMatrix3fv(u.loc, 1, false, m.e, 0);
    }
    public void m4(String n, M4 m) {
        U u = u(n); if (u == null) return;
        if (same(u, m.e)) return;
        GLES30.glUniformMatrix4fv(u.loc, 1, false, m.e, 0);
    }
    /** float 数组（vec3 数组、mat4 数组这类：按 type 交） */
    public void fv(String n, float[] a) {
        U u = u(n); if (u == null) return;
        if (same(u, a)) return;
        switch (u.type) {
            case GLES30.GL_FLOAT: GLES30.glUniform1fv(u.loc, a.length, a, 0); break;
            case GLES30.GL_FLOAT_VEC2: GLES30.glUniform2fv(u.loc, a.length / 2, a, 0); break;
            case GLES30.GL_FLOAT_VEC3: GLES30.glUniform3fv(u.loc, a.length / 3, a, 0); break;
            case GLES30.GL_FLOAT_VEC4: GLES30.glUniform4fv(u.loc, a.length / 4, a, 0); break;
            case GLES30.GL_FLOAT_MAT4: GLES30.glUniformMatrix4fv(u.loc, a.length / 16, false, a, 0); break;
            case GLES30.GL_FLOAT_MAT3: GLES30.glUniformMatrix3fv(u.loc, a.length / 9, false, a, 0); break;
        }
    }
    private static boolean same(U u, float[] a) {
        if (u.cache != null && u.cache.length == a.length) {
            boolean eq = true;
            for (int k = 0; k < a.length; k++) if (u.cache[k] != a[k]) { eq = false; break; }
            if (eq) return true;
        } else u.cache = new float[a.length];
        System.arraycopy(a, 0, u.cache, 0, a.length);
        return false;
    }
    /** 贴图：分一个贴图单元、绑上（单元号记在 uniform 里） */
    public void tex(String n, Tex t, Renderer r) {
        U u = u(n); if (u == null) return;
        int unit = r.nextUnit();
        if (u.icache != unit) { u.icache = unit; GLES30.glUniform1i(u.loc, unit); }
        if (t != null) t.bind(unit); else { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, r.emptyTex()); }
    }
    /** 自定义着色器的 uniform：按值的类型交 */
    public void any(String n, Object v, Renderer r) {
        if (v instanceof Float) f(n, (Float) v);
        else if (v instanceof Integer) { U u = u(n); if (u != null && (u.type == GLES30.GL_FLOAT)) f(n, (Integer) v); else i(n, (Integer) v); }
        else if (v instanceof Boolean) b(n, (Boolean) v);
        else if (v instanceof V3) v3(n, (V3) v);
        else if (v instanceof Col) col(n, (Col) v);
        else if (v instanceof float[]) {
            float[] a = (float[]) v;
            U u = u(n); if (u == null) return;
            if (u.size == 1 && a.length == 2 && u.type == GLES30.GL_FLOAT_VEC2) v2(n, a[0], a[1]);
            else if (u.size == 1 && a.length == 3 && u.type == GLES30.GL_FLOAT_VEC3) v3(n, a[0], a[1], a[2]);
            else if (u.size == 1 && a.length == 4 && u.type == GLES30.GL_FLOAT_VEC4) v4(n, a[0], a[1], a[2], a[3]);
            else fv(n, a);
        }
        else if (v instanceof M4) m4(n, (M4) v);
        else if (v instanceof M3) m3(n, (M3) v);
        else if (v instanceof Tex || (v == null && u(n) != null && isSampler(u(n).type))) tex(n, (Tex) v, r);
        else if (v instanceof RT) tex(n, ((RT) v).texture, r);
    }
    private static boolean isSampler(int t) { return t == GLES30.GL_SAMPLER_2D || t == GLES30.GL_SAMPLER_CUBE || t == GLES30.GL_SAMPLER_2D_SHADOW; }
}
