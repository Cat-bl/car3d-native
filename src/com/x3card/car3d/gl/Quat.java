package com.x3card.car3d.gl;

/** 四元数（同 three.js 的 Quaternion） */
public final class Quat {
    public float x, y, z, w = 1;

    public Quat() {}
    public Quat(float x, float y, float z, float w) { this.x = x; this.y = y; this.z = z; this.w = w; }
    public Quat set(float x, float y, float z, float w) { this.x = x; this.y = y; this.z = z; this.w = w; return this; }
    public Quat copy(Quat q) { x = q.x; y = q.y; z = q.z; w = q.w; return this; }
    public Quat clone() { return new Quat(x, y, z, w); }
    public Quat identity() { return set(0, 0, 0, 1); }

    /** 欧拉角（弧度）+ 顺序（"XYZ"、"YXZ"……），同 setFromEuler */
    public Quat setFromEuler(float ex, float ey, float ez, String order) {
        double c1 = Math.cos(ex / 2), c2 = Math.cos(ey / 2), c3 = Math.cos(ez / 2), s1 = Math.sin(ex / 2), s2 = Math.sin(ey / 2), s3 = Math.sin(ez / 2);
        double X, Y, Z, W;
        switch (order) {
            case "YXZ": X = s1 * c2 * c3 + c1 * s2 * s3; Y = c1 * s2 * c3 - s1 * c2 * s3; Z = c1 * c2 * s3 - s1 * s2 * c3; W = c1 * c2 * c3 + s1 * s2 * s3; break;
            case "ZXY": X = s1 * c2 * c3 - c1 * s2 * s3; Y = c1 * s2 * c3 + s1 * c2 * s3; Z = c1 * c2 * s3 + s1 * s2 * c3; W = c1 * c2 * c3 - s1 * s2 * s3; break;
            case "ZYX": X = s1 * c2 * c3 - c1 * s2 * s3; Y = c1 * s2 * c3 + s1 * c2 * s3; Z = c1 * c2 * s3 - s1 * s2 * c3; W = c1 * c2 * c3 + s1 * s2 * s3; break;
            case "YZX": X = s1 * c2 * c3 + c1 * s2 * s3; Y = c1 * s2 * c3 + s1 * c2 * s3; Z = c1 * c2 * s3 - s1 * s2 * c3; W = c1 * c2 * c3 - s1 * s2 * s3; break;
            case "XZY": X = s1 * c2 * c3 - c1 * s2 * s3; Y = c1 * s2 * c3 - s1 * c2 * s3; Z = c1 * c2 * s3 + s1 * s2 * c3; W = c1 * c2 * c3 + s1 * s2 * s3; break;
            default: X = s1 * c2 * c3 + c1 * s2 * s3; Y = c1 * s2 * c3 - s1 * c2 * s3; Z = c1 * c2 * s3 + s1 * s2 * c3; W = c1 * c2 * c3 - s1 * s2 * s3; // XYZ
        }
        return set((float) X, (float) Y, (float) Z, (float) W);
    }
    public Quat setFromAxisAngle(V3 a, float angle) {
        float h = angle / 2, s = (float) Math.sin(h);
        return set(a.x * s, a.y * s, a.z * s, (float) Math.cos(h));
    }
    /** 从旋转矩阵（左上 3×3 不带缩放）取，同 setFromRotationMatrix */
    public Quat setFromRotationMatrix(M4 m) {
        float[] te = m.e;
        float m11 = te[0], m12 = te[4], m13 = te[8], m21 = te[1], m22 = te[5], m23 = te[9], m31 = te[2], m32 = te[6], m33 = te[10], tr = m11 + m22 + m33;
        if (tr > 0) { float s = 0.5f / (float) Math.sqrt(tr + 1.0); w = 0.25f / s; x = (m32 - m23) * s; y = (m13 - m31) * s; z = (m21 - m12) * s; }
        else if (m11 > m22 && m11 > m33) { float s = 2.0f * (float) Math.sqrt(1.0 + m11 - m22 - m33); w = (m32 - m23) / s; x = 0.25f * s; y = (m12 + m21) / s; z = (m13 + m31) / s; }
        else if (m22 > m33) { float s = 2.0f * (float) Math.sqrt(1.0 + m22 - m11 - m33); w = (m13 - m31) / s; x = (m12 + m21) / s; y = 0.25f * s; z = (m23 + m32) / s; }
        else { float s = 2.0f * (float) Math.sqrt(1.0 + m33 - m11 - m22); w = (m21 - m12) / s; x = (m13 + m31) / s; y = (m23 + m32) / s; z = 0.25f * s; }
        return this;
    }
    public Quat multiply(Quat q) { return multiplyQuaternions(this, q); }
    public Quat premultiply(Quat q) { return multiplyQuaternions(q, this); }
    public Quat multiplyQuaternions(Quat a, Quat b) {
        float qax = a.x, qay = a.y, qaz = a.z, qaw = a.w, qbx = b.x, qby = b.y, qbz = b.z, qbw = b.w;
        x = qax * qbw + qaw * qbx + qay * qbz - qaz * qby;
        y = qay * qbw + qaw * qby + qaz * qbx - qax * qbz;
        z = qaz * qbw + qaw * qbz + qax * qby - qay * qbx;
        w = qaw * qbw - qax * qbx - qay * qby - qaz * qbz;
        return this;
    }
    public Quat invert() { x = -x; y = -y; z = -z; return this; }
    public float length() { return (float) Math.sqrt(x * x + y * y + z * z + w * w); }
    public Quat normalize() { float l = length(); if (l == 0) return identity(); l = 1 / l; x *= l; y *= l; z *= l; w *= l; return this; }
    public float dot(Quat q) { return x * q.x + y * q.y + z * q.z + w * q.w; }
    /** 球面插值（同 slerp） */
    public Quat slerp(Quat qb, float t) {
        if (t == 0) return this;
        if (t == 1) return copy(qb);
        float X = x, Y = y, Z = z, W = w;
        float cosHalf = W * qb.w + X * qb.x + Y * qb.y + Z * qb.z;
        if (cosHalf < 0) { w = -qb.w; x = -qb.x; y = -qb.y; z = -qb.z; cosHalf = -cosHalf; } else copy(qb);
        if (cosHalf >= 1.0) { w = W; x = X; y = Y; z = Z; return this; }
        float sqrSin = 1.0f - cosHalf * cosHalf;
        if (sqrSin <= 1.1920929E-7f) {
            float s = 1 - t;
            w = s * W + t * w; x = s * X + t * x; y = s * Y + t * y; z = s * Z + t * z;
            return normalize();
        }
        float sinHalf = (float) Math.sqrt(sqrSin), half = (float) Math.atan2(sinHalf, cosHalf);
        float ra = (float) Math.sin((1 - t) * half) / sinHalf, rb = (float) Math.sin(t * half) / sinHalf;
        w = W * ra + w * rb; x = X * ra + x * rb; y = Y * ra + y * rb; z = Z * ra + z * rb;
        return this;
    }
}
