package com.x3card.car3d.gl;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 着色器原文（three r149 的 ShaderChunk / ShaderLib，_tools/gen/three-shaders.mjs 导出的 three-shaders.json）和拼程序的规则（照 WebGLProgram.js：
    前缀的宏、#include 展开、灯的数量替换、循环展开、GLSL 3 的换名）。网页版对着色器的两处改动在 load 里照原样做：
    main.js 的中精度光照（材质带 D3_LOWP 时）、car3d.js 的 Khronos PBR Neutral（挂在 CustomToneMapping 上） */
public final class Programs {
    public static final int NO_TONE = 0, LINEAR_TONE = 1, ACES = 4, CUSTOM_TONE = 5;
    public static final int LINEAR = 3000, SRGB = 3001;
    static final Map<String, String> CHUNK = new HashMap<>();
    static final Map<String, String[]> LIB = new HashMap<>();
    /** 后期效果的着色器（复制、辉光、环境光遮蔽、景深），网页版 three-post.js 里的原文 */
    public static final Map<String, String[]> POST = new HashMap<>();

    public static void load(String json) throws Exception {
        JSONObject o = new JSONObject(json), c = o.getJSONObject("chunks"), l = o.getJSONObject("lib");
        for (Iterator<String> it = c.keys(); it.hasNext(); ) { String k = it.next(); CHUNK.put(k, c.getString(k)); }
        for (Iterator<String> it = l.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = l.getJSONObject(k); LIB.put(k, new String[] { s.getString("vs"), s.getString("fs") }); }
        JSONObject po = o.optJSONObject("post");
        if (po != null) for (Iterator<String> it = po.keys(); it.hasNext(); ) { String k = it.next(); JSONObject s = po.getJSONObject(k); POST.put(k, new String[] { s.getString("vs"), s.getString("fs") }); }
        // ---- main.js：光照按中精度算（材质带 D3_LOWP 时） ----
        String LO = "#ifdef D3_MP\nprecision mediump float;\n#endif\n", HI = "#ifdef D3_MP\nprecision highp float;\n#endif\n";
        String[] keep = { "uv_pars_fragment", "uv2_pars_fragment", "iridescence_fragment", "cube_uv_reflection_fragment", "normal_pars_fragment", "transmission_pars_fragment",
            "shadowmap_pars_fragment", "bumpmap_pars_fragment", "normalmap_pars_fragment", "logdepthbuf_pars_fragment", "clipping_planes_pars_fragment" };
        String b = CHUNK.get("bsdfs");
        b = first(b, "float D_GGX( const in float alpha, const in float dotNH ) {\n\tfloat a2 = pow2( alpha );",
            "float D_GGX( const in float alpha, const in float dotNH ) {\n#ifdef D3_MP\n\tfloat a2 = max( pow2( alpha ), 6.2e-5 );\n#else\n\tfloat a2 = pow2( alpha );\n#endif");
        b = b.replace("return F * ( V * D );", "#ifdef D3_MP\n\treturn F * min( V * D, 8192.0 );\n#else\n\treturn F * ( V * D );\n#endif");
        CHUNK.put("bsdfs", b);
        String[] ph = LIB.get("physical");
        String f = first(ph[1], "varying vec3 vViewPosition;", "varying highp vec3 vViewPosition;");
        f = first(f, "#include <common>", "#include <common>\n#ifdef D3_MP\n#undef EPSILON\n#define EPSILON 1e-4\n#endif");
        for (String n : keep) f = f.replace("#include <" + n + ">", HI + "#include <" + n + ">\n" + LO);
        ph[1] = "#if defined( D3_LOWP ) && ! defined( USE_IRIDESCENCE )\n#define D3_MP\n#endif\n" + LO + f;
        // ---- car3d.js：Khronos PBR Neutral（three r162 以后叫 NeutralToneMapping） ----
        String tm = CHUNK.get("tonemapping_pars_fragment");
        if (tm.contains("vec3 CustomToneMapping( vec3 color ) { return color; }")) CHUNK.put("tonemapping_pars_fragment", first(tm, "vec3 CustomToneMapping( vec3 color ) { return color; }",
            "vec3 CustomToneMapping( vec3 color ) {\n" +
            "  const float StartCompression = 0.8 - 0.04; const float Desaturation = 0.15;\n" +
            "  color *= toneMappingExposure;\n" +
            "  float x = min( color.r, min( color.g, color.b ) );\n" +
            "  float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;\n" +
            "  color -= offset;\n" +
            "  float peak = max( color.r, max( color.g, color.b ) );\n" +
            "  if ( peak < StartCompression ) return color;\n" +
            "  float d = 1. - StartCompression;\n" +
            "  float newPeak = 1. - d * d / ( peak + d - StartCompression );\n" +
            "  color *= newPeak / peak;\n" +
            "  float g = 1. - 1. / ( Desaturation * ( peak - newPeak ) + 1. );\n" +
            "  return mix( color, vec3( newPeak ), g );\n" +
            "}"));
    }
    /** 只换第一处（同 JS 的 string.replace(string, string)） */
    public static String first(String s, String a, String b) { int i = s.indexOf(a); return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length()); }
    public static String chunk(String n) { return CHUNK.get(n); }

    /** 拼程序要的参数（同 WebGLPrograms.getParameters 里用得到的那些） */
    public static final class P {
        public String shaderID, shaderName;
        public Map<String, String> defines;
        public int outputEncoding = SRGB, toneMapping, envHeight, numDir, numDirShadow;
        public boolean map, envMap, aoMap, lightMap, emissiveMap, bumpMap, normalMap, objectSpaceNormal, clearcoat, clearcoatMap, clearcoatRoughnessMap, clearcoatNormalMap,
            iridescence, iridescenceMap, iridescenceThicknessMap, roughnessMap, metalnessMap, specularIntensityMap, specularColorMap, opaque, alphaMap, alphaTest,
            sheen, sheenColorMap, sheenRoughnessMap, transmission, transmissionMap, thicknessMap, vertexTangents, vertexColors, vertexAlphas, vertexUvs, uvsVertexOnly,
            flatShading, shadowMapEnabled, physicallyCorrectLights, premultipliedAlpha, doubleSided, flipSided, depthPacking, dithering, skinning, custom;
    }

    public static P params(Mat m, int side, Geo g, boolean skinned, Tex env, int outputEncoding, int toneMapping, int numDir, int numDirShadow, boolean shadows, boolean pcl) {
        P p = new P();
        boolean std = m.isStandard();
        switch (m.type) {
            case Mat.STANDARD: p.shaderID = "physical"; p.shaderName = "MeshStandardMaterial"; break;
            case Mat.PHYSICAL: p.shaderID = "physical"; p.shaderName = "MeshPhysicalMaterial"; break;
            case Mat.BASIC: p.shaderID = "basic"; p.shaderName = "MeshBasicMaterial"; break;
            case Mat.SHADOW: p.shaderID = "shadow"; p.shaderName = "ShadowMaterial"; break;
            case Mat.DEPTH: p.shaderID = "depth"; p.shaderName = "MeshDepthMaterial"; break;
            case Mat.NORMALS: p.shaderID = "normal"; p.shaderName = "MeshNormalMaterial"; break;
            default: p.custom = true; p.shaderName = "ShaderMaterial";
        }
        p.defines = m.defines;
        p.outputEncoding = outputEncoding;
        Tex em = std ? env : null;
        p.envMap = em != null;
        p.envHeight = em != null ? em.height : 0;
        p.map = m.map != null; p.lightMap = m.lightMap != null; p.aoMap = m.aoMap != null; p.emissiveMap = m.emissiveMap != null;
        p.bumpMap = m.bumpMap != null; p.normalMap = m.normalMap != null; p.objectSpaceNormal = m.objectSpaceNormal;
        boolean cc = m.clearcoat > 0, ir = m.iridescence > 0;
        p.clearcoat = cc; p.clearcoatMap = cc && m.clearcoatMap != null; p.clearcoatRoughnessMap = cc && m.clearcoatRoughnessMap != null; p.clearcoatNormalMap = cc && m.clearcoatNormalMap != null;
        p.iridescence = ir; p.iridescenceMap = ir && m.iridescenceMap != null; p.iridescenceThicknessMap = ir && m.iridescenceThicknessMap != null;
        p.roughnessMap = m.roughnessMap != null; p.metalnessMap = m.metalnessMap != null;
        p.specularIntensityMap = m.specularIntensityMap != null; p.specularColorMap = m.specularColorMap != null;
        p.opaque = !m.transparent && m.blending == Mat.NORMAL;
        p.alphaMap = m.alphaMap != null; p.alphaTest = m.alphaTest > 0;
        p.sheen = m.sheen > 0; p.sheenColorMap = m.sheenColorMap != null; p.sheenRoughnessMap = m.sheenRoughnessMap != null;
        p.transmission = m.transmission > 0; p.transmissionMap = m.transmissionMap != null; p.thicknessMap = m.thicknessMap != null;
        p.vertexTangents = m.normalMap != null && g.attr("tangent") != null;
        p.vertexColors = m.vertexColors;
        Attr col = g.attr("color");
        p.vertexAlphas = m.vertexColors && col != null && col.itemSize == 4;
        p.vertexUvs = m.map != null || m.bumpMap != null || m.normalMap != null || m.alphaMap != null || m.emissiveMap != null || m.roughnessMap != null || m.metalnessMap != null
            || m.clearcoatMap != null || m.clearcoatRoughnessMap != null || m.clearcoatNormalMap != null || m.iridescenceMap != null || m.iridescenceThicknessMap != null
            || m.transmissionMap != null || m.thicknessMap != null || m.specularIntensityMap != null || m.specularColorMap != null || m.sheenColorMap != null || m.sheenRoughnessMap != null;
        p.uvsVertexOnly = false;
        p.flatShading = m.flatShading;
        p.skinning = false; // 蒙皮网格照绑定姿势画（车模不用骨骼动画）
        p.numDir = numDir; p.numDirShadow = numDirShadow;
        p.shadowMapEnabled = shadows && numDirShadow > 0;
        p.toneMapping = m.toneMapped ? toneMapping : NO_TONE;
        p.physicallyCorrectLights = pcl;
        p.premultipliedAlpha = m.premultipliedAlpha;
        p.doubleSided = side == Mat.DOUBLE; p.flipSided = side == Mat.BACK;
        p.depthPacking = m.type == Mat.DEPTH && m.depthPacking;
        p.dithering = m.dithering;
        return p;
    }

    /** 决定拼出来的源码的全部参数，拼成一个短键（同 WebGLPrograms.getProgramCacheKey） */
    public static String key(P p) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(p.shaderID).append(',').append(p.shaderName).append(',').append(defines(p.defines)).append(',')
            .append(p.outputEncoding).append(',').append(p.toneMapping).append(',').append(p.envHeight).append(',').append(p.numDir).append(',').append(p.numDirShadow).append(',');
        boolean[] b = { p.map, p.envMap, p.aoMap, p.lightMap, p.emissiveMap, p.bumpMap, p.normalMap, p.objectSpaceNormal, p.clearcoat, p.clearcoatMap, p.clearcoatRoughnessMap,
            p.clearcoatNormalMap, p.iridescence, p.iridescenceMap, p.iridescenceThicknessMap, p.roughnessMap, p.metalnessMap, p.specularIntensityMap, p.specularColorMap, p.opaque,
            p.alphaMap, p.alphaTest, p.sheen, p.sheenColorMap, p.sheenRoughnessMap, p.transmission, p.transmissionMap, p.thicknessMap, p.vertexTangents, p.vertexColors,
            p.vertexAlphas, p.vertexUvs, p.uvsVertexOnly, p.flatShading, p.shadowMapEnabled, p.physicallyCorrectLights, p.premultipliedAlpha, p.doubleSided, p.flipSided,
            p.depthPacking, p.dithering, p.skinning, p.custom };
        for (boolean x : b) sb.append(x ? '1' : '0');
        return sb.toString();
    }

    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#include +<([\\w\\d./]+)>");
    private static final Pattern UNROLL = Pattern.compile("#pragma unroll_loop_start\\s+for\\s*\\(\\s*int\\s+i\\s*=\\s*(\\d+)\\s*;\\s*i\\s*<\\s*(\\d+)\\s*;\\s*i\\s*\\+\\+\\s*\\)\\s*\\{([\\s\\S]+?)\\}\\s+#pragma unroll_loop_end");
    private static final Pattern IDX = Pattern.compile("\\[\\s*i\\s*\\]");

    static String includes(String s) {
        Matcher mm = INCLUDE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (mm.find()) {
            String c = CHUNK.get(mm.group(1));
            if (c == null) throw new RuntimeException("Can not resolve #include <" + mm.group(1) + ">");
            mm.appendReplacement(sb, Matcher.quoteReplacement(includes(c)));
        }
        mm.appendTail(sb);
        return sb.toString();
    }
    static String unroll(String s) {
        Matcher mm = UNROLL.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (mm.find()) {
            int a = Integer.parseInt(mm.group(1)), b = Integer.parseInt(mm.group(2));
            StringBuilder o = new StringBuilder();
            for (int i = a; i < b; i++) o.append(IDX.matcher(mm.group(3)).replaceAll("[ " + i + " ]").replace("UNROLLED_LOOP_INDEX", String.valueOf(i)));
            mm.appendReplacement(sb, Matcher.quoteReplacement(o.toString()));
        }
        mm.appendTail(sb);
        return sb.toString();
    }
    static String lightNums(String s, P p) {
        return s.replace("NUM_DIR_LIGHT_SHADOWS", String.valueOf(p.numDirShadow)).replace("NUM_DIR_LIGHTS", String.valueOf(p.numDir))
            .replace("NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS", "0").replace("NUM_SPOT_LIGHT_SHADOWS", "0").replace("NUM_SPOT_LIGHT_MAPS", "0").replace("NUM_SPOT_LIGHT_COORDS", "0").replace("NUM_SPOT_LIGHTS", "0")
            .replace("NUM_RECT_AREA_LIGHTS", "0").replace("NUM_POINT_LIGHT_SHADOWS", "0").replace("NUM_POINT_LIGHTS", "0").replace("NUM_HEMI_LIGHTS", "0")
            .replace("UNION_CLIPPING_PLANES", "0").replace("NUM_CLIPPING_PLANES", "0");
    }
    private static void add(List<String> l, boolean on, String s) { if (on && !s.isEmpty()) l.add(s); }

    /** 顶点、片元着色器的完整原文（同 WebGLProgram 在 WebGL2 上拼出来的）；vs / fs 是材质的原文（内置材质取 ShaderLib，可能被 onBeforeCompile 改过） */
    public static String[] source(P p, String vs, String fs) {
        String defs = defines(p.defines);
        String prec = "precision highp float;\nprecision highp int;\n#define HIGH_PRECISION";
        boolean cubeUV = p.envMap && p.envHeight > 0;
        double maxMip = cubeUV ? Math.log(p.envHeight) / Math.log(2) - 2 : 0;
        List<String> v = new ArrayList<>(), f = new ArrayList<>();
        v.add(prec); v.add("#define SHADER_NAME " + p.shaderName); add(v, true, defs);
        v.add("#define VERTEX_TEXTURES");
        add(v, p.map, "#define USE_MAP"); add(v, p.envMap, "#define USE_ENVMAP"); add(v, p.envMap, "#define ENVMAP_MODE_REFLECTION");
        add(v, p.lightMap, "#define USE_LIGHTMAP"); add(v, p.aoMap, "#define USE_AOMAP"); add(v, p.emissiveMap, "#define USE_EMISSIVEMAP");
        add(v, p.bumpMap, "#define USE_BUMPMAP"); add(v, p.normalMap, "#define USE_NORMALMAP");
        add(v, p.normalMap && p.objectSpaceNormal, "#define OBJECTSPACE_NORMALMAP"); add(v, p.normalMap && !p.objectSpaceNormal, "#define TANGENTSPACE_NORMALMAP");
        add(v, p.clearcoatMap, "#define USE_CLEARCOATMAP"); add(v, p.clearcoatRoughnessMap, "#define USE_CLEARCOAT_ROUGHNESSMAP"); add(v, p.clearcoatNormalMap, "#define USE_CLEARCOAT_NORMALMAP");
        add(v, p.iridescenceMap, "#define USE_IRIDESCENCEMAP"); add(v, p.iridescenceThicknessMap, "#define USE_IRIDESCENCE_THICKNESSMAP");
        add(v, p.specularIntensityMap, "#define USE_SPECULARINTENSITYMAP"); add(v, p.specularColorMap, "#define USE_SPECULARCOLORMAP");
        add(v, p.roughnessMap, "#define USE_ROUGHNESSMAP"); add(v, p.metalnessMap, "#define USE_METALNESSMAP"); add(v, p.alphaMap, "#define USE_ALPHAMAP");
        add(v, p.transmission, "#define USE_TRANSMISSION"); add(v, p.transmissionMap, "#define USE_TRANSMISSIONMAP"); add(v, p.thicknessMap, "#define USE_THICKNESSMAP");
        add(v, p.sheenColorMap, "#define USE_SHEENCOLORMAP"); add(v, p.sheenRoughnessMap, "#define USE_SHEENROUGHNESSMAP");
        add(v, p.vertexTangents, "#define USE_TANGENT"); add(v, p.vertexColors, "#define USE_COLOR"); add(v, p.vertexAlphas, "#define USE_COLOR_ALPHA");
        add(v, p.vertexUvs, "#define USE_UV"); add(v, p.uvsVertexOnly, "#define UVS_VERTEX_ONLY"); add(v, p.flatShading, "#define FLAT_SHADED");
        add(v, p.skinning, "#define USE_SKINNING");
        add(v, p.doubleSided, "#define DOUBLE_SIDED"); add(v, p.flipSided, "#define FLIP_SIDED");
        add(v, p.shadowMapEnabled, "#define USE_SHADOWMAP"); add(v, p.shadowMapEnabled, "#define SHADOWMAP_TYPE_PCF_SOFT");
        v.add("uniform mat4 modelMatrix;\nuniform mat4 modelViewMatrix;\nuniform mat4 projectionMatrix;\nuniform mat4 viewMatrix;\nuniform mat3 normalMatrix;\nuniform vec3 cameraPosition;\nuniform bool isOrthographic;");
        v.add("#ifdef USE_INSTANCING\n\tattribute mat4 instanceMatrix;\n#endif\n#ifdef USE_INSTANCING_COLOR\n\tattribute vec3 instanceColor;\n#endif\nattribute vec3 position;\nattribute vec3 normal;\nattribute vec2 uv;\n" +
            "#ifdef USE_TANGENT\n\tattribute vec4 tangent;\n#endif\n#if defined( USE_COLOR_ALPHA )\n\tattribute vec4 color;\n#elif defined( USE_COLOR )\n\tattribute vec3 color;\n#endif\n" +
            "#if ( defined( USE_MORPHTARGETS ) && ! defined( MORPHTARGETS_TEXTURE ) )\n\tattribute vec3 morphTarget0;\n\tattribute vec3 morphTarget1;\n\tattribute vec3 morphTarget2;\n\tattribute vec3 morphTarget3;\n" +
            "\t#ifdef USE_MORPHNORMALS\n\t\tattribute vec3 morphNormal0;\n\t\tattribute vec3 morphNormal1;\n\t\tattribute vec3 morphNormal2;\n\t\tattribute vec3 morphNormal3;\n\t#else\n" +
            "\t\tattribute vec3 morphTarget4;\n\t\tattribute vec3 morphTarget5;\n\t\tattribute vec3 morphTarget6;\n\t\tattribute vec3 morphTarget7;\n\t#endif\n#endif\n" +
            "#ifdef USE_SKINNING\n\tattribute vec4 skinIndex;\n\tattribute vec4 skinWeight;\n#endif");
        v.add("\n");
        f.add(prec); f.add("#define SHADER_NAME " + p.shaderName); add(f, true, defs);
        add(f, p.map, "#define USE_MAP"); add(f, p.envMap, "#define USE_ENVMAP");
        add(f, p.envMap, "#define " + (cubeUV ? "ENVMAP_TYPE_CUBE_UV" : "ENVMAP_TYPE_CUBE")); add(f, p.envMap, "#define ENVMAP_MODE_REFLECTION"); add(f, p.envMap, "#define ENVMAP_BLENDING_NONE");
        if (cubeUV) {
            f.add("#define CUBEUV_TEXEL_WIDTH " + js(1.0 / (3 * Math.max(Math.pow(2, maxMip), 7 * 16))));
            f.add("#define CUBEUV_TEXEL_HEIGHT " + js(1.0 / p.envHeight));
            f.add("#define CUBEUV_MAX_MIP " + js(maxMip) + ".0");
        }
        add(f, p.lightMap, "#define USE_LIGHTMAP"); add(f, p.aoMap, "#define USE_AOMAP"); add(f, p.emissiveMap, "#define USE_EMISSIVEMAP");
        add(f, p.bumpMap, "#define USE_BUMPMAP"); add(f, p.normalMap, "#define USE_NORMALMAP");
        add(f, p.normalMap && p.objectSpaceNormal, "#define OBJECTSPACE_NORMALMAP"); add(f, p.normalMap && !p.objectSpaceNormal, "#define TANGENTSPACE_NORMALMAP");
        add(f, p.clearcoat, "#define USE_CLEARCOAT"); add(f, p.clearcoatMap, "#define USE_CLEARCOATMAP"); add(f, p.clearcoatRoughnessMap, "#define USE_CLEARCOAT_ROUGHNESSMAP"); add(f, p.clearcoatNormalMap, "#define USE_CLEARCOAT_NORMALMAP");
        add(f, p.iridescence, "#define USE_IRIDESCENCE"); add(f, p.iridescenceMap, "#define USE_IRIDESCENCEMAP"); add(f, p.iridescenceThicknessMap, "#define USE_IRIDESCENCE_THICKNESSMAP");
        add(f, p.specularIntensityMap, "#define USE_SPECULARINTENSITYMAP"); add(f, p.specularColorMap, "#define USE_SPECULARCOLORMAP");
        add(f, p.roughnessMap, "#define USE_ROUGHNESSMAP"); add(f, p.metalnessMap, "#define USE_METALNESSMAP");
        add(f, p.alphaMap, "#define USE_ALPHAMAP"); add(f, p.alphaTest, "#define USE_ALPHATEST");
        add(f, p.sheen, "#define USE_SHEEN"); add(f, p.sheenColorMap, "#define USE_SHEENCOLORMAP"); add(f, p.sheenRoughnessMap, "#define USE_SHEENROUGHNESSMAP");
        add(f, p.transmission, "#define USE_TRANSMISSION"); add(f, p.transmissionMap, "#define USE_TRANSMISSIONMAP"); add(f, p.thicknessMap, "#define USE_THICKNESSMAP");
        add(f, p.vertexTangents, "#define USE_TANGENT"); add(f, p.vertexColors, "#define USE_COLOR"); add(f, p.vertexAlphas, "#define USE_COLOR_ALPHA");
        add(f, p.vertexUvs, "#define USE_UV"); add(f, p.uvsVertexOnly, "#define UVS_VERTEX_ONLY");
        add(f, p.flatShading, "#define FLAT_SHADED"); add(f, p.doubleSided, "#define DOUBLE_SIDED"); add(f, p.flipSided, "#define FLIP_SIDED");
        add(f, p.shadowMapEnabled, "#define USE_SHADOWMAP"); add(f, p.shadowMapEnabled, "#define SHADOWMAP_TYPE_PCF_SOFT");
        add(f, p.premultipliedAlpha, "#define PREMULTIPLIED_ALPHA"); add(f, p.physicallyCorrectLights, "#define PHYSICALLY_CORRECT_LIGHTS");
        f.add("uniform mat4 viewMatrix;\nuniform vec3 cameraPosition;\nuniform bool isOrthographic;");
        if (p.toneMapping != NO_TONE) {
            f.add("#define TONE_MAPPING");
            f.add(CHUNK.get("tonemapping_pars_fragment"));
            String n = p.toneMapping == LINEAR_TONE ? "Linear" : p.toneMapping == 2 ? "Reinhard" : p.toneMapping == 3 ? "OptimizedCineon" : p.toneMapping == ACES ? "ACESFilmic" : p.toneMapping == CUSTOM_TONE ? "Custom" : "Linear";
            f.add("vec3 toneMapping( vec3 color ) { return " + n + "ToneMapping( color ); }");
        }
        add(f, p.dithering, "#define DITHERING"); add(f, p.opaque, "#define OPAQUE");
        f.add(CHUNK.get("encodings_pars_fragment"));
        f.add("vec4 linearToOutputTexel( vec4 value ) { return LinearTo" + (p.outputEncoding == SRGB ? "sRGB" : "Linear") + "( value ); }");
        add(f, p.depthPacking, "#define DEPTH_PACKING 3201");
        f.add("\n");
        String pv = join(v), pf = join(f);
        String vb = expand(vs, p), fb = expand(fs, p);
        pv = "precision mediump sampler2DArray;\n#define attribute in\n#define varying out\n#define texture2D texture\n" + pv;
        pf = "#define varying in\nlayout(location = 0) out highp vec4 pc_fragColor;\n#define gl_FragColor pc_fragColor\n#define gl_FragDepthEXT gl_FragDepth\n#define texture2D texture\n#define textureCube texture\n" +
            "#define texture2DProj textureProj\n#define texture2DLodEXT textureLod\n#define texture2DProjLodEXT textureProjLod\n#define textureCubeLodEXT textureLod\n#define texture2DGradEXT textureGrad\n" +
            "#define texture2DProjGradEXT textureProjGrad\n#define textureCubeGradEXT textureGrad\n" + pf;
        return new String[] { "#version 300 es\n" + pv + vb, "#version 300 es\n" + pf + fb };
    }
    /** 展开 #include、填灯的数量、展开循环（几十段正则替换，拼一次十几毫秒）：同样的原文、同样的灯数结果一样，记住最近用过的 */
    private static final Map<String, String> EXPANDED = new java.util.LinkedHashMap<String, String>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 48; }
    };
    private static String expand(String src, P p) {
        String k = p.numDir + "," + p.numDirShadow + "" + src;
        synchronized (EXPANDED) { String r = EXPANDED.get(k); if (r != null) return r; }
        String r = unroll(lightNums(includes(src), p));
        synchronized (EXPANDED) { EXPANDED.put(k, r); }
        return r;
    }
    private static String join(List<String> l) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append('\n'); sb.append(l.get(i)); } return sb.toString(); }
    private static String defines(Map<String, String> d) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : d.entrySet()) { if ("false".equals(e.getValue())) continue; if (sb.length() > 0) sb.append('\n'); sb.append("#define ").append(e.getKey()).append(' ').append(e.getValue()); }
        return sb.toString();
    }
    /** 数字写成 JS 的样子（宏里的 CUBEUV_TEXEL_WIDTH 这类） */
    static String js(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = Double.toString(d);
        if (s.contains("E")) { java.math.BigDecimal b = new java.math.BigDecimal(d); s = b.round(new java.math.MathContext(17)).stripTrailingZeros().toPlainString(); }
        return s;
    }
}
