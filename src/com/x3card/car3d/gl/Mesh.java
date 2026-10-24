package com.x3card.car3d.gl;

/** 网格：几何体 + 材质（同 three.js 的 Mesh；GLTFLoader 不出多材质的网格，这里只有一个材质） */
public class Mesh extends Obj3D {
    public Geo geometry;
    public Mat material;
    /** 蒙皮网格：不当能动的部件，照绑定姿势画 */
    public boolean skinned;
    /** 画这个网格之前（地面反射在这里画倒影） */
    public Runnable onBeforeRender;

    public Mesh(Geo g, Mat m) { geometry = g; material = m; }
}
