package com.x3card.car3d.gl;

import android.opengl.GLES30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 几何体（同 three.js 的 BufferGeometry）：属性按名字（position / normal / uv / uv2 / color / tangent）、索引、分组、外框；
    VAO 按着色器程序缓存（各程序的属性位置不一样） */
public final class Geo {
    public final Map<String, Attr> attributes = new LinkedHashMap<>();
    public Attr index;
    /** 分组：start、count、materialIndex（多材质才用） */
    public final List<int[]> groups = new ArrayList<>();
    private Box3 bbox;
    private V3 sphereC;
    private float sphereR = -1;
    public final Map<String, Object> ud = new HashMap<>();
    private final Map<Prog, Integer> vaos = new HashMap<>();
    private int vaoVersion;

    public Attr attr(String name) { return attributes.get(name); }
    /** 换属性、索引：VAO 作废（外框不重算，同 three：setAttribute 不动 boundingBox） */
    public Geo set(String name, Attr a) { attributes.put(name, a); changed(); return this; }
    public Geo remove(String name) { attributes.remove(name); changed(); return this; }
    public Geo setIndex(Attr a) { index = a; changed(); return this; }
    public void changed() { vaoVersion++; freeVaos(); }
    /** 位置改过：外框、包围球重算（同 computeBoundingBox / computeBoundingSphere） */
    public void computeBounds() { bbox = null; sphereR = -1; }
    public void setSphere(V3 c, float r) { sphereC = c; sphereR = r; }

    public Box3 boundingBox() {
        if (bbox != null) return bbox;
        bbox = new Box3();
        Attr p = attributes.get("position");
        if (p != null) for (int i = 0; i < p.count; i++) bbox.expandByPoint(p.get(i, 0), p.get(i, 1), p.get(i, 2));
        return bbox;
    }
    /** 包围球（视锥剔除用，同 computeBoundingSphere） */
    public V3 sphereCenter() { sphere(); return sphereC; }
    public float sphereRadius() { sphere(); return sphereR; }
    private void sphere() {
        if (sphereR >= 0) return;
        Box3 b = boundingBox();
        sphereC = b.getCenter(new V3());
        Attr p = attributes.get("position");
        float m = 0;
        if (p != null) for (int i = 0; i < p.count; i++) {
            float dx = p.get(i, 0) - sphereC.x, dy = p.get(i, 1) - sphereC.y, dz = p.get(i, 2) - sphereC.z;
            m = Math.max(m, dx * dx + dy * dy + dz * dz);
        }
        sphereR = (float) Math.sqrt(m);
    }
    public void setBoundingBox(Box3 b) { bbox = b; }

    /** 按法线算顶点法线（没带法线的网格，同 computeVertexNormals：有索引的按三角形面积加权累加） */
    public void computeVertexNormals() {
        Attr p = attributes.get("position");
        if (p == null) return;
        float[] n = new float[p.count * 3];
        V3 a = new V3(), b = new V3(), c = new V3(), cb = new V3(), ab = new V3();
        int tri = index != null ? index.count : p.count;
        for (int i = 0; i + 2 < tri; i += 3) {
            int ia = index != null ? index.idx(i) : i, ib = index != null ? index.idx(i + 1) : i + 1, ic = index != null ? index.idx(i + 2) : i + 2;
            p.v3(ia, a); p.v3(ib, b); p.v3(ic, c);
            cb.subVectors(c, b); ab.subVectors(a, b); cb.cross(ab);
            if (index != null) {
                for (int v : new int[] { ia, ib, ic }) { n[v * 3] += cb.x; n[v * 3 + 1] += cb.y; n[v * 3 + 2] += cb.z; }
            } else {
                for (int v : new int[] { ia, ib, ic }) { n[v * 3] = cb.x; n[v * 3 + 1] = cb.y; n[v * 3 + 2] = cb.z; }
            }
        }
        for (int i = 0; i < p.count; i++) {
            float x = n[i * 3], y = n[i * 3 + 1], z = n[i * 3 + 2], l = (float) Math.sqrt(x * x + y * y + z * z);
            if (l > 0) { n[i * 3] = x / l; n[i * 3 + 1] = y / l; n[i * 3 + 2] = z / l; }
        }
        set("normal", Attr.floats(n, 3));
    }

    /** 绑好这个程序要的属性（VAO 缓存） */
    void bind(Prog prog) {
        Integer v = vaos.get(prog);
        if (v != null) { GLES30.glBindVertexArray(v); refresh(); return; }
        int[] a = new int[1];
        GLES30.glGenVertexArrays(1, a, 0);
        GLES30.glBindVertexArray(a[0]);
        for (Map.Entry<String, Integer> e : prog.attribs.entrySet()) {
            Attr at = attributes.get(e.getKey());
            int loc = e.getValue();
            if (at == null) { GLES30.glDisableVertexAttribArray(loc); continue; }
            at.buf.bind(GLES30.GL_ARRAY_BUFFER);
            GLES30.glEnableVertexAttribArray(loc);
            GLES30.glVertexAttribPointer(loc, at.itemSize, at.type, at.normalized, at.stride, at.offset); // 着色器里都是浮点属性：整数的按 normalized 换算（同 WebGL）
        }
        if (index != null) index.buf.bind(GLES30.GL_ELEMENT_ARRAY_BUFFER);
        vaos.put(prog, a[0]);
    }
    /** 数据改过（比如理顺了绕向的索引）：重新上传 */
    private void refresh() {
        if (index != null && index.buf.uploaded != index.buf.version) index.buf.bind(GLES30.GL_ELEMENT_ARRAY_BUFFER);
        for (Attr at : attributes.values()) if (at.buf.uploaded != at.buf.version) { at.buf.bind(GLES30.GL_ARRAY_BUFFER); }
    }
    void forget(Prog p) { Integer v = vaos.remove(p); if (v != null) GLES30.glDeleteVertexArrays(1, new int[] { v }, 0); }
    private void freeVaos() {
        for (int v : vaos.values()) GLES30.glDeleteVertexArrays(1, new int[] { v }, 0);
        vaos.clear();
    }
    /** 释放显存（数据还在，再画时重新上传，同 three 的 dispose） */
    public void dispose() {
        freeVaos();
        for (Attr a : attributes.values()) a.buf.free();
        if (index != null) index.buf.free();
    }
}
