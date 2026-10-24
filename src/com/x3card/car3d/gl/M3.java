package com.x3card.car3d.gl;

/** 3×3 矩阵，按列存（法线矩阵、贴图坐标变换用，同 three.js 的 Matrix3） */
public final class M3 {
    public final float[] e = new float[9];

    public M3() { identity(); }
    public M3 identity() { for (int i = 0; i < 9; i++) e[i] = (i % 4 == 0) ? 1 : 0; return this; }
    public M3 copy(M3 m) { System.arraycopy(m.e, 0, e, 0, 9); return this; }
    /** 按行写（同 Matrix3.set） */
    public M3 set(float n11, float n12, float n13, float n21, float n22, float n23, float n31, float n32, float n33) {
        e[0] = n11; e[1] = n21; e[2] = n31; e[3] = n12; e[4] = n22; e[5] = n32; e[6] = n13; e[7] = n23; e[8] = n33;
        return this;
    }
    /** 法线矩阵：左上 3×3 的逆转置（同 getNormalMatrix） */
    public M3 getNormalMatrix(M4 m) {
        float[] a = m.e;
        float n11 = a[0], n21 = a[1], n31 = a[2], n12 = a[4], n22 = a[5], n32 = a[6], n13 = a[8], n23 = a[9], n33 = a[10];
        float t11 = n33 * n22 - n32 * n23, t12 = n32 * n13 - n33 * n12, t13 = n23 * n12 - n22 * n13;
        float det = n11 * t11 + n21 * t12 + n31 * t13;
        if (det == 0) { for (int i = 0; i < 9; i++) e[i] = 0; return this; }
        float d = 1 / det;
        // 先求逆
        float i0 = t11 * d, i1 = (n31 * n23 - n33 * n21) * d, i2 = (n32 * n21 - n31 * n22) * d;
        float i3 = t12 * d, i4 = (n33 * n11 - n31 * n13) * d, i5 = (n31 * n12 - n32 * n11) * d;
        float i6 = t13 * d, i7 = (n21 * n13 - n23 * n11) * d, i8 = (n22 * n11 - n21 * n12) * d;
        // 再转置
        e[0] = i0; e[1] = i3; e[2] = i6; e[3] = i1; e[4] = i4; e[5] = i7; e[6] = i2; e[7] = i5; e[8] = i8;
        return this;
    }
    /** 贴图坐标变换（同 setUvTransform） */
    public M3 setUvTransform(float tx, float ty, float sx, float sy, float rotation, float cx, float cy) {
        float c = (float) Math.cos(rotation), s = (float) Math.sin(rotation);
        return set(sx * c, sx * s, -sx * (c * cx + s * cy) + cx + tx,
            -sy * s, sy * c, -sy * (-s * cx + c * cy) + cy + ty,
            0, 0, 1);
    }
}
