package com.x3card.car3d.gl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 材质（同 three.js r149 的 MeshStandardMaterial / MeshPhysicalMaterial / MeshBasicMaterial / ShadowMaterial / MeshDepthMaterial / ShaderMaterial，
    字段名、默认值照它；一个类装下所有种类，type 区分）。改了会换着色器的东西（贴图有没有、透明度裁切、清漆、薄膜干涉……）要 needsUpdate() */
public final class Mat {
    public static final int STANDARD = 1, PHYSICAL = 2, BASIC = 3, SHADOW = 4, DEPTH = 5, SHADER = 6, NORMALS = 7;
    public static final int FRONT = 0, BACK = 1, DOUBLE = 2;
    public static final int NO_BLENDING = 0, NORMAL = 1, ADDITIVE = 2, CUSTOM = 5;
    private static int ids;

    public final int id = ids++;
    public final int type;
    public String name = "";
    public int version;
    // 通用
    public boolean transparent, depthTest = true, depthWrite = true, colorWrite = true, visible = true, toneMapped = true, vertexColors, flatShading, premultipliedAlpha, dithering;
    public float opacity = 1, alphaTest;
    public int side = FRONT, blending = NORMAL, depthFunc = 0x0203; // GL_LEQUAL
    public boolean polygonOffset;
    public float polygonOffsetFactor, polygonOffsetUnits;
    // 自定义混合（CUSTOM）
    public int blendSrc = 0x0302, blendDst = 0x0303, blendSrcAlpha = -1, blendDstAlpha = -1, blendEquation = 0x8006;
    public final Map<String, String> defines = new LinkedHashMap<>();
    // 颜色、贴图
    public final Col color = new Col(1, 1, 1), emissive = new Col(0, 0, 0);
    public float emissiveIntensity = 1, roughness = 1, metalness = 0, envMapIntensity = 1, aoMapIntensity = 1, lightMapIntensity = 1, bumpScale = 1;
    public Tex map, aoMap, emissiveMap, normalMap, bumpMap, roughnessMap, metalnessMap, alphaMap, lightMap;
    public float normalScaleX = 1, normalScaleY = 1;
    public boolean objectSpaceNormal;
    // 物理材质
    public float clearcoat, clearcoatRoughness, ior = 1.5f, specularIntensity = 1, sheen, sheenRoughness = 1, transmission, thickness, attenuationDistance = Float.POSITIVE_INFINITY;
    public float iridescence, iridescenceIOR = 1.3f, iridescenceThickMin = 100, iridescenceThickMax = 400;
    public final Col specularColor = new Col(1, 1, 1), sheenColor = new Col(0, 0, 0), attenuationColor = new Col(1, 1, 1);
    public Tex clearcoatMap, clearcoatRoughnessMap, clearcoatNormalMap, specularIntensityMap, specularColorMap, sheenColorMap, sheenRoughnessMap, transmissionMap, thicknessMap, iridescenceMap, iridescenceThicknessMap;
    public float clearcoatNormalScaleX = 1, clearcoatNormalScaleY = 1;
    // 深度材质（阴影贴图用）：RGBA 打包深度
    public boolean depthPacking;
    // 自定义着色器
    public String vertexShader, fragmentShader;
    public final Map<String, Val> uniforms = new LinkedHashMap<>();
    /** 编译前改着色器原文（同 onBeforeCompile；拿到的是还没展开 #include 的原文） */
    public interface Hook { String[] on(String vs, String fs, Map<String, Val> uniforms); }
    public Hook onBeforeCompile;
    /** 着色器缓存的附加键（同 customProgramCacheKey） */
    public String cacheKey = "";
    public final Map<String, Object> ud = new HashMap<>();

    public Mat(int type) {
        this.type = type;
        if (type == STANDARD || type == PHYSICAL) defines.put("STANDARD", "");
        if (type == PHYSICAL) defines.put("PHYSICAL", "");
        if (type == SHADOW) { color.setScalar(0); transparent = true; }
    }
    public static Mat standard() { return new Mat(STANDARD); }
    public static Mat physical() { return new Mat(PHYSICAL); }
    public static Mat basic() { return new Mat(BASIC); }
    public static Mat shader(String vs, String fs) { Mat m = new Mat(SHADER); m.vertexShader = vs; m.fragmentShader = fs; return m; }

    /** 设一个自定义着色器的 uniform（没有就加上） */
    public Mat put(String k, Object v) { Val x = uniforms.get(k); if (x == null) uniforms.put(k, new Val(v)); else x.v = v; return this; }
    public boolean isStandard() { return type == STANDARD || type == PHYSICAL; }
    public boolean isPhysical() { return type == PHYSICAL; }
    public void needsUpdate() { version++; }
    /** reflectivity 和 ior 互相换算（同 MeshPhysicalMaterial） */
    public float reflectivity() { return clamp(2.5f * (ior - 1) / (ior + 1), 0, 1); }
    public void setReflectivity(float r) { ior = (1 + 0.4f * r) / (1 - 0.4f * r); }
    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    /** 复制一份（同 clone：贴图共用） */
    public Mat clone() {
        Mat m = new Mat(type);
        m.name = name; m.transparent = transparent; m.depthTest = depthTest; m.depthWrite = depthWrite; m.colorWrite = colorWrite; m.visible = visible;
        m.toneMapped = toneMapped; m.vertexColors = vertexColors; m.flatShading = flatShading; m.premultipliedAlpha = premultipliedAlpha; m.dithering = dithering;
        m.opacity = opacity; m.alphaTest = alphaTest; m.side = side; m.blending = blending; m.depthFunc = depthFunc;
        m.polygonOffset = polygonOffset; m.polygonOffsetFactor = polygonOffsetFactor; m.polygonOffsetUnits = polygonOffsetUnits;
        m.blendSrc = blendSrc; m.blendDst = blendDst; m.blendSrcAlpha = blendSrcAlpha; m.blendDstAlpha = blendDstAlpha; m.blendEquation = blendEquation;
        m.defines.putAll(defines);
        m.color.copy(color); m.emissive.copy(emissive);
        m.emissiveIntensity = emissiveIntensity; m.roughness = roughness; m.metalness = metalness; m.envMapIntensity = envMapIntensity; m.aoMapIntensity = aoMapIntensity;
        m.lightMapIntensity = lightMapIntensity; m.bumpScale = bumpScale;
        m.map = map; m.aoMap = aoMap; m.emissiveMap = emissiveMap; m.normalMap = normalMap; m.bumpMap = bumpMap; m.roughnessMap = roughnessMap; m.metalnessMap = metalnessMap;
        m.alphaMap = alphaMap; m.lightMap = lightMap;
        m.normalScaleX = normalScaleX; m.normalScaleY = normalScaleY; m.objectSpaceNormal = objectSpaceNormal;
        m.clearcoat = clearcoat; m.clearcoatRoughness = clearcoatRoughness; m.ior = ior; m.specularIntensity = specularIntensity; m.sheen = sheen; m.sheenRoughness = sheenRoughness;
        m.transmission = transmission; m.thickness = thickness; m.attenuationDistance = attenuationDistance;
        m.iridescence = iridescence; m.iridescenceIOR = iridescenceIOR; m.iridescenceThickMin = iridescenceThickMin; m.iridescenceThickMax = iridescenceThickMax;
        m.specularColor.copy(specularColor); m.sheenColor.copy(sheenColor); m.attenuationColor.copy(attenuationColor);
        m.clearcoatMap = clearcoatMap; m.clearcoatRoughnessMap = clearcoatRoughnessMap; m.clearcoatNormalMap = clearcoatNormalMap;
        m.specularIntensityMap = specularIntensityMap; m.specularColorMap = specularColorMap; m.sheenColorMap = sheenColorMap; m.sheenRoughnessMap = sheenRoughnessMap;
        m.transmissionMap = transmissionMap; m.thicknessMap = thicknessMap; m.iridescenceMap = iridescenceMap; m.iridescenceThicknessMap = iridescenceThicknessMap;
        m.clearcoatNormalScaleX = clearcoatNormalScaleX; m.clearcoatNormalScaleY = clearcoatNormalScaleY;
        m.depthPacking = depthPacking; m.vertexShader = vertexShader; m.fragmentShader = fragmentShader;
        m.uniforms.putAll(uniforms);
        m.onBeforeCompile = onBeforeCompile; m.cacheKey = cacheKey;
        return m;
    }
    /** 释放：着色器程序由渲染器按用的次数收（同 dispose 事件） */
    public void dispose() { if (Renderer.current != null) Renderer.current.release(this); }
}
