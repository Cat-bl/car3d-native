package com.x3card.car3d.gl;

/** 4×4 矩阵，按列存（同 three.js 的 Matrix4.elements，直接交给 GL） */
public final class M4 {
    public final float[] e = new float[16];

    public M4() { identity(); }
    public M4 identity() { for (int i = 0; i < 16; i++) e[i] = (i % 5 == 0) ? 1 : 0; return this; }
    public M4 copy(M4 m) { System.arraycopy(m.e, 0, e, 0, 16); return this; }
    public M4 clone() { return new M4().copy(this); }
    public M4 fromArray(float[] a, int o) { System.arraycopy(a, o, e, 0, 16); return this; }
    /** 按行写的 16 个数（同 Matrix4.set） */
    public M4 set(float n11, float n12, float n13, float n14, float n21, float n22, float n23, float n24, float n31, float n32, float n33, float n34, float n41, float n42, float n43, float n44) {
        e[0] = n11; e[4] = n12; e[8] = n13; e[12] = n14;
        e[1] = n21; e[5] = n22; e[9] = n23; e[13] = n24;
        e[2] = n31; e[6] = n32; e[10] = n33; e[14] = n34;
        e[3] = n41; e[7] = n42; e[11] = n43; e[15] = n44;
        return this;
    }
    public M4 multiply(M4 m) { return multiplyMatrices(this, m); }
    public M4 premultiply(M4 m) { return multiplyMatrices(m, this); }
    public M4 multiplyMatrices(M4 a, M4 b) {
        float[] ae = a.e, be = b.e;
        float a11 = ae[0], a12 = ae[4], a13 = ae[8], a14 = ae[12], a21 = ae[1], a22 = ae[5], a23 = ae[9], a24 = ae[13];
        float a31 = ae[2], a32 = ae[6], a33 = ae[10], a34 = ae[14], a41 = ae[3], a42 = ae[7], a43 = ae[11], a44 = ae[15];
        float b11 = be[0], b12 = be[4], b13 = be[8], b14 = be[12], b21 = be[1], b22 = be[5], b23 = be[9], b24 = be[13];
        float b31 = be[2], b32 = be[6], b33 = be[10], b34 = be[14], b41 = be[3], b42 = be[7], b43 = be[11], b44 = be[15];
        e[0] = a11 * b11 + a12 * b21 + a13 * b31 + a14 * b41; e[4] = a11 * b12 + a12 * b22 + a13 * b32 + a14 * b42;
        e[8] = a11 * b13 + a12 * b23 + a13 * b33 + a14 * b43; e[12] = a11 * b14 + a12 * b24 + a13 * b34 + a14 * b44;
        e[1] = a21 * b11 + a22 * b21 + a23 * b31 + a24 * b41; e[5] = a21 * b12 + a22 * b22 + a23 * b32 + a24 * b42;
        e[9] = a21 * b13 + a22 * b23 + a23 * b33 + a24 * b43; e[13] = a21 * b14 + a22 * b24 + a23 * b34 + a24 * b44;
        e[2] = a31 * b11 + a32 * b21 + a33 * b31 + a34 * b41; e[6] = a31 * b12 + a32 * b22 + a33 * b32 + a34 * b42;
        e[10] = a31 * b13 + a32 * b23 + a33 * b33 + a34 * b43; e[14] = a31 * b14 + a32 * b24 + a33 * b34 + a34 * b44;
        e[3] = a41 * b11 + a42 * b21 + a43 * b31 + a44 * b41; e[7] = a41 * b12 + a42 * b22 + a43 * b32 + a44 * b42;
        e[11] = a41 * b13 + a42 * b23 + a43 * b33 + a44 * b43; e[15] = a41 * b14 + a42 * b24 + a43 * b34 + a44 * b44;
        return this;
    }
    public float determinant() {
        float n11 = e[0], n12 = e[4], n13 = e[8], n14 = e[12], n21 = e[1], n22 = e[5], n23 = e[9], n24 = e[13];
        float n31 = e[2], n32 = e[6], n33 = e[10], n34 = e[14], n41 = e[3], n42 = e[7], n43 = e[11], n44 = e[15];
        return n41 * (+n14 * n23 * n32 - n13 * n24 * n32 - n14 * n22 * n33 + n12 * n24 * n33 + n13 * n22 * n34 - n12 * n23 * n34)
            + n42 * (+n11 * n23 * n34 - n11 * n24 * n33 + n14 * n21 * n33 - n13 * n21 * n34 + n13 * n24 * n31 - n14 * n23 * n31)
            + n43 * (+n11 * n24 * n32 - n11 * n22 * n34 - n14 * n21 * n32 + n12 * n21 * n34 + n14 * n22 * n31 - n12 * n24 * n31)
            + n44 * (-n13 * n22 * n31 - n11 * n23 * n32 + n11 * n22 * n33 + n13 * n21 * n32 - n12 * n21 * n33 + n12 * n23 * n31);
    }
    public M4 invert() {
        float n11 = e[0], n21 = e[1], n31 = e[2], n41 = e[3], n12 = e[4], n22 = e[5], n32 = e[6], n42 = e[7];
        float n13 = e[8], n23 = e[9], n33 = e[10], n43 = e[11], n14 = e[12], n24 = e[13], n34 = e[14], n44 = e[15];
        float t11 = n23 * n34 * n42 - n24 * n33 * n42 + n24 * n32 * n43 - n22 * n34 * n43 - n23 * n32 * n44 + n22 * n33 * n44;
        float t12 = n14 * n33 * n42 - n13 * n34 * n42 - n14 * n32 * n43 + n12 * n34 * n43 + n13 * n32 * n44 - n12 * n33 * n44;
        float t13 = n13 * n24 * n42 - n14 * n23 * n42 + n14 * n22 * n43 - n12 * n24 * n43 - n13 * n22 * n44 + n12 * n23 * n44;
        float t14 = n14 * n23 * n32 - n13 * n24 * n32 - n14 * n22 * n33 + n12 * n24 * n33 + n13 * n22 * n34 - n12 * n23 * n34;
        float det = n11 * t11 + n21 * t12 + n31 * t13 + n41 * t14;
        if (det == 0) { for (int i = 0; i < 16; i++) e[i] = 0; return this; }
        float d = 1 / det;
        e[0] = t11 * d;
        e[1] = (n24 * n33 * n41 - n23 * n34 * n41 - n24 * n31 * n43 + n21 * n34 * n43 + n23 * n31 * n44 - n21 * n33 * n44) * d;
        e[2] = (n22 * n34 * n41 - n24 * n32 * n41 + n24 * n31 * n42 - n21 * n34 * n42 - n22 * n31 * n44 + n21 * n32 * n44) * d;
        e[3] = (n23 * n32 * n41 - n22 * n33 * n41 - n23 * n31 * n42 + n21 * n33 * n42 + n22 * n31 * n43 - n21 * n32 * n43) * d;
        e[4] = t12 * d;
        e[5] = (n13 * n34 * n41 - n14 * n33 * n41 + n14 * n31 * n43 - n11 * n34 * n43 - n13 * n31 * n44 + n11 * n33 * n44) * d;
        e[6] = (n14 * n32 * n41 - n12 * n34 * n41 - n14 * n31 * n42 + n11 * n34 * n42 + n12 * n31 * n44 - n11 * n32 * n44) * d;
        e[7] = (n12 * n33 * n41 - n13 * n32 * n41 + n13 * n31 * n42 - n11 * n33 * n42 - n12 * n31 * n43 + n11 * n32 * n43) * d;
        e[8] = t13 * d;
        e[9] = (n14 * n23 * n41 - n13 * n24 * n41 - n14 * n21 * n43 + n11 * n24 * n43 + n13 * n21 * n44 - n11 * n23 * n44) * d;
        e[10] = (n12 * n24 * n41 - n14 * n22 * n41 + n14 * n21 * n42 - n11 * n24 * n42 - n12 * n21 * n44 + n11 * n22 * n44) * d;
        e[11] = (n13 * n22 * n41 - n12 * n23 * n41 - n13 * n21 * n42 + n11 * n23 * n42 + n12 * n21 * n43 - n11 * n22 * n43) * d;
        e[12] = t14 * d;
        e[13] = (n13 * n24 * n31 - n14 * n23 * n31 + n14 * n21 * n33 - n11 * n24 * n33 - n13 * n21 * n34 + n11 * n23 * n34) * d;
        e[14] = (n14 * n22 * n31 - n12 * n24 * n31 - n14 * n21 * n32 + n11 * n24 * n32 + n12 * n21 * n34 - n11 * n22 * n34) * d;
        e[15] = (n12 * n23 * n31 - n13 * n22 * n31 + n13 * n21 * n32 - n11 * n23 * n32 - n12 * n21 * n33 + n11 * n22 * n33) * d;
        return this;
    }
    public M4 compose(V3 p, Quat q, V3 s) {
        float x = q.x, y = q.y, z = q.z, w = q.w, x2 = x + x, y2 = y + y, z2 = z + z;
        float xx = x * x2, xy = x * y2, xz = x * z2, yy = y * y2, yz = y * z2, zz = z * z2, wx = w * x2, wy = w * y2, wz = w * z2;
        float sx = s.x, sy = s.y, sz = s.z;
        e[0] = (1 - (yy + zz)) * sx; e[1] = (xy + wz) * sx; e[2] = (xz - wy) * sx; e[3] = 0;
        e[4] = (xy - wz) * sy; e[5] = (1 - (xx + zz)) * sy; e[6] = (yz + wx) * sy; e[7] = 0;
        e[8] = (xz + wy) * sz; e[9] = (yz - wx) * sz; e[10] = (1 - (xx + yy)) * sz; e[11] = 0;
        e[12] = p.x; e[13] = p.y; e[14] = p.z; e[15] = 1;
        return this;
    }
    public M4 decompose(V3 p, Quat q, V3 s) {
        float sx = (float) Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        float sy = (float) Math.sqrt(e[4] * e[4] + e[5] * e[5] + e[6] * e[6]);
        float sz = (float) Math.sqrt(e[8] * e[8] + e[9] * e[9] + e[10] * e[10]);
        if (determinant() < 0) sx = -sx;
        p.x = e[12]; p.y = e[13]; p.z = e[14];
        M4 m = new M4().copy(this);
        float ix = 1 / sx, iy = 1 / sy, iz = 1 / sz;
        m.e[0] *= ix; m.e[1] *= ix; m.e[2] *= ix;
        m.e[4] *= iy; m.e[5] *= iy; m.e[6] *= iy;
        m.e[8] *= iz; m.e[9] *= iz; m.e[10] *= iz;
        q.setFromRotationMatrix(m);
        s.x = sx; s.y = sy; s.z = sz;
        return this;
    }
    public M4 makeRotationFromQuaternion(Quat q) { return compose(new V3(), q, new V3(1, 1, 1)); }
    public M4 makeTranslation(float x, float y, float z) { return set(1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1); }
    public M4 makeScale(float x, float y, float z) { return set(x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1); }
    public M4 setPosition(float x, float y, float z) { e[12] = x; e[13] = y; e[14] = z; return this; }
    public M4 scale(V3 v) {
        e[0] *= v.x; e[4] *= v.y; e[8] *= v.z; e[1] *= v.x; e[5] *= v.y; e[9] *= v.z;
        e[2] *= v.x; e[6] *= v.y; e[10] *= v.z; e[3] *= v.x; e[7] *= v.y; e[11] *= v.z;
        return this;
    }
    public float getMaxScaleOnAxis() {
        float sx = e[0] * e[0] + e[1] * e[1] + e[2] * e[2], sy = e[4] * e[4] + e[5] * e[5] + e[6] * e[6], sz = e[8] * e[8] + e[9] * e[9] + e[10] * e[10];
        return (float) Math.sqrt(Math.max(sx, Math.max(sy, sz)));
    }
    /** 物体看向 target（up 朝上），同 Matrix4.lookAt（只写旋转部分） */
    public M4 lookAt(V3 eye, V3 target, V3 up) {
        V3 z = new V3().subVectors(eye, target);
        if (z.lengthSq() == 0) z.z = 1;
        z.normalize();
        V3 x = new V3().crossVectors(up, z);
        if (x.lengthSq() == 0) {
            if (Math.abs(up.z) == 1) z.x += 0.0001f; else z.z += 0.0001f;
            z.normalize();
            x.crossVectors(up, z);
        }
        x.normalize();
        V3 y = new V3().crossVectors(z, x);
        e[0] = x.x; e[4] = y.x; e[8] = z.x;
        e[1] = x.y; e[5] = y.y; e[9] = z.y;
        e[2] = x.z; e[6] = y.z; e[10] = z.z;
        return this;
    }
    public M4 makePerspective(float left, float right, float top, float bottom, float near, float far) {
        float x = 2 * near / (right - left), y = 2 * near / (top - bottom);
        float a = (right + left) / (right - left), b = (top + bottom) / (top - bottom), c = -(far + near) / (far - near), d = -2 * far * near / (far - near);
        e[0] = x; e[4] = 0; e[8] = a; e[12] = 0;
        e[1] = 0; e[5] = y; e[9] = b; e[13] = 0;
        e[2] = 0; e[6] = 0; e[10] = c; e[14] = d;
        e[3] = 0; e[7] = 0; e[11] = -1; e[15] = 0;
        return this;
    }
    public M4 makeOrthographic(float left, float right, float top, float bottom, float near, float far) {
        float w = 1f / (right - left), h = 1f / (top - bottom), p = 1f / (far - near);
        float x = (right + left) * w, y = (top + bottom) * h, z = (far + near) * p;
        e[0] = 2 * w; e[4] = 0; e[8] = 0; e[12] = -x;
        e[1] = 0; e[5] = 2 * h; e[9] = 0; e[13] = -y;
        e[2] = 0; e[6] = 0; e[10] = -2 * p; e[14] = -z;
        e[3] = 0; e[7] = 0; e[11] = 0; e[15] = 1;
        return this;
    }
}
