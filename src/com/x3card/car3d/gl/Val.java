package com.x3card.car3d.gl;

/** 自定义着色器的一个 uniform 值（同 three.js 的 { value: x }：几个材质可以共用同一个，改一处都跟着变）。
    值可以是 Float、Integer、Boolean、V3、Col、float[]（vec2 / vec4 / 数组）、M3、M4、Tex、RT */
public final class Val {
    public Object v;
    public Val(Object v) { this.v = v; }
    public float f() { return v instanceof Number ? ((Number) v).floatValue() : 0; }
    public float[] a() { return (float[]) v; }
}
