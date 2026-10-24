package com.x3card.car3d.gl;

/** 几种简单的几何体（同 three.js 的 PlaneGeometry、BoxGeometry：顶点顺序、贴图坐标都一样） */
public final class Shapes {
    private Shapes() {}
    /** 竖着的平面（xy 平面，朝 +z），w × h，一格 */
    public static Geo plane(float w, float h) {
        Geo g = new Geo();
        float x = w / 2, y = h / 2;
        g.set("position", Attr.floats(new float[] { -x, y, 0, x, y, 0, -x, -y, 0, x, -y, 0 }, 3));
        g.set("normal", Attr.floats(new float[] { 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1 }, 3));
        g.set("uv", Attr.floats(new float[] { 0, 1, 1, 1, 0, 0, 1, 0 }, 2));
        g.setIndex(Attr.index(new int[] { 0, 2, 1, 2, 3, 1 }, 4));
        return g;
    }
    /** 盖满画布的三角形（后期、贴回画面用；顶点直接是裁剪坐标） */
    public static Geo screen() {
        Geo g = new Geo();
        g.set("position", Attr.floats(new float[] { -1, -1, 0, 3, -1, 0, -1, 3, 0 }, 3));
        g.set("uv", Attr.floats(new float[] { 0, 0, 2, 0, 0, 2 }, 2));
        return g;
    }
}
