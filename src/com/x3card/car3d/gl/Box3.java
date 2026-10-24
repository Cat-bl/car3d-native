package com.x3card.car3d.gl;

/** 轴对齐的外框（同 three.js 的 Box3） */
public final class Box3 {
    public final V3 min = new V3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
    public final V3 max = new V3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

    public Box3() {}
    public Box3(V3 a, V3 b) { min.copy(a); max.copy(b); }
    public Box3 set(V3 a, V3 b) { min.copy(a); max.copy(b); return this; }
    public Box3 copy(Box3 b) { min.copy(b.min); max.copy(b.max); return this; }
    public Box3 clone() { return new Box3().copy(this); }
    public Box3 makeEmpty() {
        min.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        max.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        return this;
    }
    public boolean isEmpty() { return max.x < min.x || max.y < min.y || max.z < min.z; }
    public V3 getCenter(V3 t) { return isEmpty() ? t.set(0, 0, 0) : t.addVectors(min, max).multiplyScalar(0.5f); }
    public V3 getSize(V3 t) { return isEmpty() ? t.set(0, 0, 0) : t.subVectors(max, min); }
    public Box3 expandByPoint(V3 p) { min.min(p); max.max(p); return this; }
    public Box3 expandByPoint(float x, float y, float z) {
        if (x < min.x) min.x = x; if (y < min.y) min.y = y; if (z < min.z) min.z = z;
        if (x > max.x) max.x = x; if (y > max.y) max.y = y; if (z > max.z) max.z = z;
        return this;
    }
    public Box3 expandByScalar(float s) { min.x -= s; min.y -= s; min.z -= s; max.x += s; max.y += s; max.z += s; return this; }
    public Box3 union(Box3 b) { min.min(b.min); max.max(b.max); return this; }
    public Box3 intersect(Box3 b) { min.max(b.min); max.min(b.max); if (isEmpty()) makeEmpty(); return this; }
    public boolean intersectsBox(Box3 b) { return !(b.max.x < min.x || b.min.x > max.x || b.max.y < min.y || b.min.y > max.y || b.max.z < min.z || b.min.z > max.z); }
    public boolean containsPoint(V3 p) { return !(p.x < min.x || p.x > max.x || p.y < min.y || p.y > max.y || p.z < min.z || p.z > max.z); }
    /** 八个角都变换后重新取外框（同 applyMatrix4） */
    public Box3 applyMatrix4(M4 m) {
        if (isEmpty()) return this;
        float[] xs = { min.x, max.x }, ys = { min.y, max.y }, zs = { min.z, max.z };
        V3 p = new V3();
        makeEmpty();
        for (float x : xs) for (float y : ys) for (float z : zs) expandByPoint(p.set(x, y, z).applyMatrix4(m));
        return this;
    }
    /** 物体（连同子物体）的世界外框，按几何体的外框算（同 expandByObject，precise = false） */
    public Box3 expandByObject(Obj3D o) {
        o.updateWorldMatrix(false, false);
        if (o instanceof Mesh) {
            Geo g = ((Mesh) o).geometry;
            if (g != null) {
                Box3 b = g.boundingBox();
                if (!b.isEmpty()) union(b.clone().applyMatrix4(o.matrixWorld));
            }
        }
        for (int i = 0; i < o.children.size(); i++) expandByObject(o.children.get(i));
        return this;
    }
    public Box3 setFromObject(Obj3D o) { makeEmpty(); return expandByObject(o); }
}
