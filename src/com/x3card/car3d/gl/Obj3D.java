package com.x3card.car3d.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 场景树里的一个节点（同 three.js 的 Object3D：位置、四元数、缩放，自己的矩阵和世界矩阵，图层、显示、画的顺序） */
public class Obj3D {
    private static int ids;
    public final int id = ids++;
    public String name = "";
    public Obj3D parent;
    public final List<Obj3D> children = new ArrayList<>();
    public final V3 position = new V3();
    public final Quat quaternion = new Quat();
    public final V3 scale = new V3(1, 1, 1);
    public final M4 matrix = new M4(), matrixWorld = new M4();
    public boolean matrixAutoUpdate = true, matrixWorldNeedsUpdate;
    public boolean visible = true, castShadow, receiveShadow, frustumCulled = true;
    /** 图层（同 Layers.mask：默认只在图层 0） */
    public int layers = 1;
    public int renderOrder;
    /** 各种标记（同 userData） */
    public final Map<String, Object> ud = new HashMap<>();

    /** 欧拉角（弧度）设旋转（同 rotation.set(x, y, z, order)） */
    public Obj3D rotate(float x, float y, float z, String order) { quaternion.setFromEuler(x, y, z, order); return this; }
    public Obj3D rotate(float x, float y, float z) { return rotate(x, y, z, "XYZ"); }

    public Obj3D add(Obj3D o) {
        if (o == this) return this;
        if (o.parent != null) o.parent.remove(o);
        o.parent = this;
        children.add(o);
        return this;
    }
    public Obj3D remove(Obj3D o) {
        if (children.remove(o)) o.parent = null;
        return this;
    }
    public void removeFromParent() { if (parent != null) parent.remove(this); }
    /** 挂到这下面、世界位置不变（同 attach） */
    public Obj3D attach(Obj3D o) {
        updateWorldMatrix(true, false);
        M4 m = new M4().copy(matrixWorld).invert();
        if (o.parent != null) {
            o.parent.updateWorldMatrix(true, false);
            m.multiply(o.parent.matrixWorld);
        }
        o.applyMatrix4(m);
        add(o);
        o.updateWorldMatrix(false, true);
        return this;
    }
    public void applyMatrix4(M4 m) {
        if (matrixAutoUpdate) updateMatrix();
        matrix.premultiply(m);
        matrix.decompose(position, quaternion, scale);
    }

    public interface Visit { void on(Obj3D o); }
    /** 先自己、再按顺序深度优先遍历子节点（同 traverse） */
    public void traverse(Visit v) {
        v.on(this);
        for (int i = 0; i < children.size(); i++) children.get(i).traverse(v);
    }
    public void traverseVisible(Visit v) {
        if (!visible) return;
        v.on(this);
        for (int i = 0; i < children.size(); i++) children.get(i).traverseVisible(v);
    }

    public void updateMatrix() { matrix.compose(position, quaternion, scale); matrixWorldNeedsUpdate = true; }
    public void updateMatrixWorld(boolean force) {
        if (matrixAutoUpdate) updateMatrix();
        if (matrixWorldNeedsUpdate || force) {
            if (parent == null) matrixWorld.copy(matrix); else matrixWorld.multiplyMatrices(parent.matrixWorld, matrix);
            matrixWorldNeedsUpdate = false;
            force = true;
        }
        for (int i = 0; i < children.size(); i++) children.get(i).updateMatrixWorld(force);
    }
    public void updateWorldMatrix(boolean updateParents, boolean updateChildren) {
        if (updateParents && parent != null) parent.updateWorldMatrix(true, false);
        if (matrixAutoUpdate) updateMatrix();
        if (parent == null) matrixWorld.copy(matrix); else matrixWorld.multiplyMatrices(parent.matrixWorld, matrix);
        if (updateChildren) for (int i = 0; i < children.size(); i++) children.get(i).updateWorldMatrix(false, true);
    }
    public V3 getWorldPosition(V3 t) { updateWorldMatrix(true, false); return t.setFromMatrixPosition(matrixWorld); }

    public boolean onLayer(int mask) { return (layers & mask) != 0; }
    public Object get(String k) { return ud.get(k); }
    public boolean has(String k) { return ud.containsKey(k); }
    public Obj3D put(String k, Object v) { ud.put(k, v); return this; }
}
