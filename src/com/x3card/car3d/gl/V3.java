package com.x3card.car3d.gl;

/** 三维向量（同 three.js 的 Vector3，方法名、行为照它） */
public final class V3 {
    public float x, y, z;

    public V3() {}
    public V3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    public V3(double x, double y, double z) { this.x = (float) x; this.y = (float) y; this.z = (float) z; }

    public V3 set(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }
    public V3 set(double x, double y, double z) { this.x = (float) x; this.y = (float) y; this.z = (float) z; return this; }
    public V3 setScalar(float s) { x = y = z = s; return this; }
    public V3 setX(float v) { x = v; return this; }
    public V3 setY(float v) { y = v; return this; }
    public V3 setZ(float v) { z = v; return this; }
    public V3 copy(V3 v) { x = v.x; y = v.y; z = v.z; return this; }
    public V3 clone() { return new V3(x, y, z); }
    public V3 fromArray(float[] a, int o) { x = a[o]; y = a[o + 1]; z = a[o + 2]; return this; }
    public V3 fromArray(double[] a) { x = (float) a[0]; y = (float) a[1]; z = (float) a[2]; return this; }
    public float get(int i) { return i == 0 ? x : i == 1 ? y : z; }
    public V3 add(V3 v) { x += v.x; y += v.y; z += v.z; return this; }
    public V3 addScaled(V3 v, float s) { x += v.x * s; y += v.y * s; z += v.z * s; return this; }
    public V3 addVectors(V3 a, V3 b) { x = a.x + b.x; y = a.y + b.y; z = a.z + b.z; return this; }
    public V3 sub(V3 v) { x -= v.x; y -= v.y; z -= v.z; return this; }
    public V3 subVectors(V3 a, V3 b) { x = a.x - b.x; y = a.y - b.y; z = a.z - b.z; return this; }
    public V3 multiply(V3 v) { x *= v.x; y *= v.y; z *= v.z; return this; }
    public V3 multiplyScalar(float s) { x *= s; y *= s; z *= s; return this; }
    public V3 divideScalar(float s) { return multiplyScalar(1f / s); }
    public V3 min(V3 v) { x = Math.min(x, v.x); y = Math.min(y, v.y); z = Math.min(z, v.z); return this; }
    public V3 max(V3 v) { x = Math.max(x, v.x); y = Math.max(y, v.y); z = Math.max(z, v.z); return this; }
    public V3 negate() { x = -x; y = -y; z = -z; return this; }
    public float dot(V3 v) { return x * v.x + y * v.y + z * v.z; }
    public float lengthSq() { return x * x + y * y + z * z; }
    public float length() { return (float) Math.sqrt(x * x + y * y + z * z); }
    public V3 normalize() { float l = length(); return divideScalar(l == 0 ? 1 : l); }
    public V3 setLength(float l) { return normalize().multiplyScalar(l); }
    public V3 lerp(V3 v, float a) { x += (v.x - x) * a; y += (v.y - y) * a; z += (v.z - z) * a; return this; }
    public V3 cross(V3 v) { return crossVectors(this, v); }
    public V3 crossVectors(V3 a, V3 b) {
        float ax = a.x, ay = a.y, az = a.z, bx = b.x, by = b.y, bz = b.z;
        x = ay * bz - az * by; y = az * bx - ax * bz; z = ax * by - ay * bx;
        return this;
    }
    public float distanceTo(V3 v) { return (float) Math.sqrt(distanceToSquared(v)); }
    public float distanceToSquared(V3 v) { float dx = x - v.x, dy = y - v.y, dz = z - v.z; return dx * dx + dy * dy + dz * dz; }
    public boolean equals(V3 v) { return v.x == x && v.y == y && v.z == z; }

    public V3 applyMatrix3(M3 m) {
        float[] e = m.e; float X = x, Y = y, Z = z;
        x = e[0] * X + e[3] * Y + e[6] * Z; y = e[1] * X + e[4] * Y + e[7] * Z; z = e[2] * X + e[5] * Y + e[8] * Z;
        return this;
    }
    public V3 applyMatrix4(M4 m) {
        float[] e = m.e; float X = x, Y = y, Z = z;
        float w = 1f / (e[3] * X + e[7] * Y + e[11] * Z + e[15]);
        x = (e[0] * X + e[4] * Y + e[8] * Z + e[12]) * w;
        y = (e[1] * X + e[5] * Y + e[9] * Z + e[13]) * w;
        z = (e[2] * X + e[6] * Y + e[10] * Z + e[14]) * w;
        return this;
    }
    /** 方向：只乘旋转缩放部分再归一（同 transformDirection） */
    public V3 transformDirection(M4 m) {
        float[] e = m.e; float X = x, Y = y, Z = z;
        x = e[0] * X + e[4] * Y + e[8] * Z; y = e[1] * X + e[5] * Y + e[9] * Z; z = e[2] * X + e[6] * Y + e[10] * Z;
        return normalize();
    }
    public V3 applyQuaternion(Quat q) {
        float X = x, Y = y, Z = z, qx = q.x, qy = q.y, qz = q.z, qw = q.w;
        float ix = qw * X + qy * Z - qz * Y, iy = qw * Y + qz * X - qx * Z, iz = qw * Z + qx * Y - qy * X, iw = -qx * X - qy * Y - qz * Z;
        x = ix * qw + iw * -qx + iy * -qz - iz * -qy;
        y = iy * qw + iw * -qy + iz * -qx - ix * -qz;
        z = iz * qw + iw * -qz + ix * -qy - iy * -qx;
        return this;
    }
    public V3 applyAxisAngle(V3 axis, float angle) { return applyQuaternion(new Quat().setFromAxisAngle(axis, angle)); }
    public V3 project(Camera c) { return applyMatrix4(c.matrixWorldInverse).applyMatrix4(c.projectionMatrix); }
    public V3 unproject(Camera c) { return applyMatrix4(c.projectionMatrixInverse).applyMatrix4(c.matrixWorld); }
    public V3 setFromMatrixPosition(M4 m) { x = m.e[12]; y = m.e[13]; z = m.e[14]; return this; }
    public V3 setFromMatrixColumn(M4 m, int i) { return fromArray(m.e, i * 4); }
    public V3 setFromSpherical(float radius, float phi, float theta) {
        float s = (float) Math.sin(phi) * radius;
        x = s * (float) Math.sin(theta); y = (float) Math.cos(phi) * radius; z = s * (float) Math.cos(theta);
        return this;
    }
    @Override public String toString() { return "(" + x + "," + y + "," + z + ")"; }
}
