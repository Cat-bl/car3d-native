package com.x3card.car3d.gl;

/** 射线（同 three.js 的 Ray / Raycaster.setFromCamera 的透视相机那部分） */
public final class Ray {
    public final V3 origin = new V3(), direction = new V3(0, 0, -1);

    /** 屏幕坐标（-1～1）从相机打出去的射线 */
    public Ray setFromCamera(float x, float y, Camera c) {
        origin.setFromMatrixPosition(c.matrixWorld);
        direction.set(x, y, 0.5f).unproject(c).sub(origin).normalize();
        return this;
    }
    /** 和外框相交的点（同 intersectBox），不相交返回 null */
    public V3 intersectBox(Box3 b, V3 t) {
        float tmin, tmax, tymin, tymax, tzmin, tzmax;
        float ix = 1 / direction.x, iy = 1 / direction.y, iz = 1 / direction.z;
        V3 o = origin;
        if (ix >= 0) { tmin = (b.min.x - o.x) * ix; tmax = (b.max.x - o.x) * ix; } else { tmin = (b.max.x - o.x) * ix; tmax = (b.min.x - o.x) * ix; }
        if (iy >= 0) { tymin = (b.min.y - o.y) * iy; tymax = (b.max.y - o.y) * iy; } else { tymin = (b.max.y - o.y) * iy; tymax = (b.min.y - o.y) * iy; }
        if (tmin > tymax || tymin > tmax) return null;
        if (tymin > tmin || Float.isNaN(tmin)) tmin = tymin;
        if (tymax < tmax || Float.isNaN(tmax)) tmax = tymax;
        if (iz >= 0) { tzmin = (b.min.z - o.z) * iz; tzmax = (b.max.z - o.z) * iz; } else { tzmin = (b.max.z - o.z) * iz; tzmax = (b.min.z - o.z) * iz; }
        if (tmin > tzmax || tzmin > tmax) return null;
        if (tzmin > tmin || Float.isNaN(tmin)) tmin = tzmin;
        if (tzmax < tmax || Float.isNaN(tmax)) tmax = tzmax;
        if (tmax < 0) return null;
        return t.copy(direction).multiplyScalar(tmin >= 0 ? tmin : tmax).add(origin);
    }
    public boolean intersectsBox(Box3 b) { return intersectBox(b, new V3()) != null; }
    /** 和平面（法线 n、过点 p）相交的点，不相交返回 null */
    public V3 intersectPlane(V3 n, V3 p, V3 t) {
        float den = n.dot(direction);
        if (den == 0) return null;
        float s = -(origin.dot(n) - p.dot(n)) / den;
        if (s < 0) return null;
        return t.copy(direction).multiplyScalar(s).add(origin);
    }
    public Ray set(V3 o, V3 d) { origin.copy(o); direction.copy(d).normalize(); return this; }
    /** 打到网格上（同 Mesh.raycast：按材质的正反面剔除、拿最近的三角形）：返回 { 距离, 世界坐标的点, 世界坐标的面法线 }，打不到 null */
    public static final class Hit { public float dist; public final V3 point = new V3(), normal = new V3(); public Mesh object; }
    public Hit intersectMesh(Mesh m) { return intersectMesh(m, null, 0); }
    /** 只看 tris 里的前 nt 个三角形（三角形第一个顶点在索引里的位置，见 trisIn）；tris 为空看全部 */
    public Hit intersectMesh(Mesh m, int[] tris, int nt) {
        Geo g = m.geometry;
        Attr pos = g == null ? null : g.attr("position");
        if (pos == null || m.material == null) return null;
        M4 inv = m.matrixWorld.clone().invert();
        V3 o = origin.clone().applyMatrix4(inv), d = direction.clone().transformDirection(inv);
        // 先看外接球
        V3 c = g.sphereCenter();
        float r = g.sphereRadius(), bx = o.x - c.x, by = o.y - c.y, bz = o.z - c.z, b = bx * d.x + by * d.y + bz * d.z, cc = bx * bx + by * by + bz * bz - r * r;
        if (cc > 0 && b > 0) return null;
        if (b * b - cc < 0) return null;
        Attr ix = g.index;
        int n = ix != null ? ix.count : pos.count;
        int side = m.material.side;
        V3 a = new V3(), e1 = new V3(), e2 = new V3(), v1 = new V3(), v2 = new V3();
        float best = Float.POSITIVE_INFINITY;
        float nx = 0, ny = 0, nz = 0;
        int cnt = tris != null ? nt : n / 3;
        for (int q = 0; q < cnt; q++) {
            int t = tris != null ? tris[q] : q * 3;
            pos.v3(ix != null ? ix.idx(t) : t, a); pos.v3(ix != null ? ix.idx(t + 1) : t + 1, v1); pos.v3(ix != null ? ix.idx(t + 2) : t + 2, v2);
            e1.subVectors(v1, a); e2.subVectors(v2, a);
            // 法线 e1 × e2；朝着射线的是背面
            float fx = e1.y * e2.z - e1.z * e2.y, fy = e1.z * e2.x - e1.x * e2.z, fz = e1.x * e2.y - e1.y * e2.x, DdN = d.x * fx + d.y * fy + d.z * fz, sg;
            if (DdN > 0) { if (side == Mat.FRONT) continue; sg = 1; } else if (DdN < 0) { if (side == Mat.BACK) continue; sg = -1; DdN = -DdN; } else continue;
            float qx = o.x - a.x, qy = o.y - a.y, qz = o.z - a.z;
            // 同 three 的 Ray.intersectTriangle
            float cx = qy * e2.z - qz * e2.y, cy = qz * e2.x - qx * e2.z, cz = qx * e2.y - qy * e2.x;
            float DdQxE2 = sg * (d.x * cx + d.y * cy + d.z * cz);
            if (DdQxE2 < 0) continue;
            float kx = e1.y * qz - e1.z * qy, ky = e1.z * qx - e1.x * qz, kz = e1.x * qy - e1.y * qx;
            float DdE1xQ = sg * (d.x * kx + d.y * ky + d.z * kz);
            if (DdE1xQ < 0 || DdQxE2 + DdE1xQ > DdN) continue;
            float QdN = -sg * (qx * fx + qy * fy + qz * fz);
            if (QdN < 0) continue;
            float tt = QdN / DdN;
            V3 pw = new V3(o.x + d.x * tt, o.y + d.y * tt, o.z + d.z * tt).applyMatrix4(m.matrixWorld);
            float dist = pw.distanceTo(origin);
            if (dist < best) { best = dist; nx = fx; ny = fy; nz = fz; }
        }
        if (best == Float.POSITIVE_INFINITY) return null;
        Hit h = new Hit();
        h.dist = best; h.object = m;
        h.point.copy(direction).multiplyScalar(best).add(origin);
        h.normal.set(nx, ny, nz).applyMatrix3(new M3().getNormalMatrix(m.matrixWorld)).normalize();
        return h;
    }
    /** 外框（世界坐标）碰得到的三角形：每个三角形第一个顶点在索引里的位置。同一块地方要打很多条射线时先挑一遍，每条射线只看这些 */
    public static int[] trisIn(Mesh m, Box3 world) {
        Geo g = m.geometry;
        Attr pos = g == null ? null : g.attr("position");
        if (pos == null) return new int[0];
        Box3 lb = world.clone().applyMatrix4(m.matrixWorld.clone().invert());
        Attr ix = g.index;
        int n = ix != null ? ix.count : pos.count, c = 0;
        int[] out = new int[64];
        for (int t = 0; t + 2 < n; t += 3) {
            float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
            for (int k = 0; k < 3; k++) {
                int v = ix != null ? ix.idx(t + k) : t + k;
                float x = pos.get(v, 0), y = pos.get(v, 1), z = pos.get(v, 2);
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; if (z < z0) z0 = z; if (z > z1) z1 = z;
            }
            if (x1 < lb.min.x || x0 > lb.max.x || y1 < lb.min.y || y0 > lb.max.y || z1 < lb.min.z || z0 > lb.max.z) continue;
            if (c == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            out[c++] = t;
        }
        return java.util.Arrays.copyOf(out, c);
    }
}
