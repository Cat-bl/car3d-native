package com.x3card.car3d.gl;

import android.opengl.GLES30;
import android.opengl.GLES32;
import android.util.Log;

/** 环境图换成立方体贴图（车机上反光取样是转车时最贵的一块）：three 的 cubeUV 图集每取一次要先算落在哪个面、面上的坐标，再取两次贴图；
    这里在显卡上用同一个取样函数（three 原文的 bilinearCubeUV）把图集烘成
    ① 一张带多级的立方体贴图：清楚的那几级（面宽 256、128、64、32、16，对应 cubeUV 的 mip 8～4），粗糙度低的反光一次 textureLod（硬件三线性过滤，同 three 在相邻两级之间按小数部分混合）；
    ② 一个面宽 16 的立方体贴图数组：第 0 层是 mip 4，第 1～6 层是更模糊的 6 级（cubeUV 的 mip 3～-2），粗糙的取相邻两层再混合（同 three）。
    每个纹素的值就是 three 在这个方向上取到的值，纹素之间按硬件的立方体贴图过滤（面和面接缝处不再靠图集里的一圈边）。
    烘完图集就不用了。要 GL_EXT_texture_cube_map_array（立方体贴图数组）和能往半精度浮点贴图里画（EXT_color_buffer_half_float） */
public final class EnvCube {
    private EnvCube() {}
    static final int LAYERS = 7, SMALL = 16;
    private static Boolean ok;

    public static boolean supported() {
        if (ok == null) {
            String e = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            ok = e != null && e.contains("GL_EXT_texture_cube_map_array") && (e.contains("GL_EXT_color_buffer_half_float") || e.contains("GL_EXT_color_buffer_float"));
        }
        return ok;
    }

    /** 在 GL 线程上烘（图集 atlas 要是 Half.rgbe 读出来的 cubeUV 图）；返回立方体贴图（ud.arr 是那个数组、ud.maxMip 是 8），失败返回 null */
    public static Tex bake(Tex atlas) {
        int maxMip = Math.round((float) (Math.log(atlas.height) / Math.log(2))) - 2, top = 1 << maxMip, levels = maxMip - 4 + 1;
        int[] id = new int[2];
        GLES30.glGenTextures(2, id, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, id[0]);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_CUBE_MAP, levels, GLES30.GL_RGBA16F, top, top);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GLES30.glBindTexture(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, id[1]);
        GLES30.glTexStorage3D(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, 1, GLES30.GL_RGBA16F, SMALL, SMALL, LAYERS * 6);
        GLES30.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES32.GL_TEXTURE_CUBE_MAP_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        int prog = program(atlas.width, atlas.height, maxMip);
        if (prog == 0) { GLES30.glDeleteTextures(2, id, 0); return null; }
        int[] fb = new int[1], va = new int[1];
        GLES30.glGenFramebuffers(1, fb, 0);
        GLES30.glGenVertexArrays(1, va, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0]);
        GLES30.glBindVertexArray(va[0]);
        GLES30.glUseProgram(prog);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST); GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_CULL_FACE); GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glColorMask(true, true, true, true);
        atlas.bind(0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "envMap"), 0);
        int uFace = GLES30.glGetUniformLocation(prog, "uFace"), uSize = GLES30.glGetUniformLocation(prog, "uSize"), uMip = GLES30.glGetUniformLocation(prog, "uMip");
        boolean good = true;
        for (int l = 0; l < levels && good; l++) {
            int size = top >> l;
            GLES30.glViewport(0, 0, size, size);
            GLES30.glUniform1f(uSize, size); GLES30.glUniform1f(uMip, maxMip - l);
            for (int f = 0; f < 6; f++) {
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, id[0], l);
                if (f == 0 && l == 0 && GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) { good = false; break; }
                GLES30.glUniform1i(uFace, f);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }
        }
        GLES30.glViewport(0, 0, SMALL, SMALL);
        GLES30.glUniform1f(uSize, SMALL);
        for (int k = 0; k < LAYERS && good; k++) {
            GLES30.glUniform1f(uMip, 4 - k);
            for (int f = 0; f < 6; f++) {
                GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, id[1], 0, k * 6 + f);
                GLES30.glUniform1i(uFace, f);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glBindVertexArray(0);
        GLES30.glUseProgram(0);
        GLES30.glDeleteFramebuffers(1, fb, 0);
        GLES30.glDeleteVertexArrays(1, va, 0);
        GLES30.glDeleteProgram(prog);
        if (!good) { Log.w("car3d", "环境图烘成立方体贴图失败：帧缓冲不完整"); GLES30.glDeleteTextures(2, id, 0); return null; }
        Tex arr = new Tex(), cube = new Tex();
        arr.id = id[1]; arr.glTarget = GLES32.GL_TEXTURE_CUBE_MAP_ARRAY; arr.width = arr.height = SMALL;
        cube.id = id[0]; cube.glTarget = GLES30.GL_TEXTURE_CUBE_MAP; cube.width = cube.height = top;
        arr.uploaded = arr.version; cube.uploaded = cube.version;
        cube.ud.put("arr", arr); cube.ud.put("maxMip", maxMip);
        return cube;
    }
    public static void dispose(Tex cube) {
        if (cube == null) return;
        Object a = cube.ud.get("arr");
        if (a instanceof Tex) ((Tex) a).dispose();
        cube.dispose();
    }

    /** 盖满一个面的三角形；每个像素按 GL 立方体贴图的面朝向算出方向，交给 three 原文的 bilinearCubeUV */
    private static int program(int w, int h, int maxMip) {
        String vs = "#version 300 es\nvoid main() { vec2 p = vec2( ( gl_VertexID << 1 ) & 2, gl_VertexID & 2 ); gl_Position = vec4( p * 2.0 - 1.0, 0.0, 1.0 ); }";
        String fs = "#version 300 es\nprecision highp float;\nprecision highp int;\n#define ENVMAP_TYPE_CUBE_UV\n" +
            "#define CUBEUV_TEXEL_WIDTH " + Programs.js(1.0 / w) + "\n#define CUBEUV_TEXEL_HEIGHT " + Programs.js(1.0 / h) + "\n#define CUBEUV_MAX_MIP " + maxMip + ".0\n" +
            "#define texture2D texture\n#define texture2DGradEXT textureGrad\nuniform sampler2D envMap;\nuniform int uFace;\nuniform float uSize, uMip;\nout vec4 o;\n" +
            Programs.chunk("cube_uv_reflection_fragment") +
            "\nvoid main() {\n  vec2 st = gl_FragCoord.xy / uSize * 2.0 - 1.0;\n  float sc = st.x, tc = st.y;\n  vec3 d;\n" +
            "  if ( uFace == 0 ) d = vec3( 1.0, - tc, - sc );\n  else if ( uFace == 1 ) d = vec3( - 1.0, - tc, sc );\n  else if ( uFace == 2 ) d = vec3( sc, 1.0, tc );\n" +
            "  else if ( uFace == 3 ) d = vec3( sc, - 1.0, - tc );\n  else if ( uFace == 4 ) d = vec3( sc, - tc, 1.0 );\n  else d = vec3( - sc, - tc, - 1.0 );\n" +
            "  o = vec4( bilinearCubeUV( envMap, d, uMip ), 1.0 );\n}\n";
        int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f);
        int[] st = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0);
        if (st[0] == 0) { Log.w("car3d", "环境图烘焙程序链接失败：" + GLES30.glGetProgramInfoLog(p)); GLES30.glDeleteProgram(p); return 0; }
        return p;
    }
    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] st = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0);
        if (st[0] == 0) { Log.w("car3d", "环境图烘焙着色器编译失败：" + GLES30.glGetShaderInfoLog(s)); GLES30.glDeleteShader(s); return 0; }
        return s;
    }
}
