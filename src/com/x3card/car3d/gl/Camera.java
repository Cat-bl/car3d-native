package com.x3card.car3d.gl;

/** 相机（同 three.js 的 PerspectiveCamera / OrthographicCamera：persp 为真是透视的） */
public final class Camera extends Obj3D {
    public final M4 matrixWorldInverse = new M4(), projectionMatrix = new M4(), projectionMatrixInverse = new M4();
    public final boolean persp;
    public float fov = 50, aspect = 1, near = 0.1f, far = 2000, zoom = 1;
    public float left = -1, right = 1, top = 1, bottom = -1;
    /** 镜头的上方（lookAt 用，同 object.up） */
    public final V3 up = new V3(0, 1, 0);
    /** 画面偏移（同 setViewOffset：整个画面 fullW×fullH 里取 (offX, offY) 起的 w×h 那块） */
    private boolean view;
    private float fullW, fullH, offX, offY, vw, vh;
    public void setViewOffset(float fw, float fh, float x, float y, float w, float h) {
        view = true; fullW = fw; fullH = fh; offX = x; offY = y; vw = w; vh = h;
        updateProjectionMatrix();
    }

    private Camera(boolean persp) { this.persp = persp; }
    public static Camera perspective(float fov, float aspect, float near, float far) {
        Camera c = new Camera(true);
        c.fov = fov; c.aspect = aspect; c.near = near; c.far = far;
        c.updateProjectionMatrix();
        return c;
    }
    public static Camera ortho(float left, float right, float top, float bottom, float near, float far) {
        Camera c = new Camera(false);
        c.left = left; c.right = right; c.top = top; c.bottom = bottom; c.near = near; c.far = far;
        c.updateProjectionMatrix();
        return c;
    }
    public void updateProjectionMatrix() {
        if (persp) {
            float t = near * (float) Math.tan(Math.toRadians(0.5 * fov)) / zoom, h = 2 * t, w = aspect * h, l = -0.5f * w;
            if (view) { l += offX * w / fullW; t -= offY * h / fullH; w *= vw / fullW; h *= vh / fullH; }
            projectionMatrix.makePerspective(l, l + w, t, t - h, near, far);
        } else {
            float dx = (right - left) / (2 * zoom), dy = (top - bottom) / (2 * zoom), cx = (right + left) / 2, cy = (top + bottom) / 2;
            projectionMatrix.makeOrthographic(cx - dx, cx + dx, cy + dy, cy - dy, near, far);
        }
        projectionMatrixInverse.copy(projectionMatrix).invert();
    }
    @Override public void updateMatrixWorld(boolean force) { super.updateMatrixWorld(force); matrixWorldInverse.copy(matrixWorld).invert(); }
    @Override public void updateWorldMatrix(boolean up, boolean down) { super.updateWorldMatrix(up, down); matrixWorldInverse.copy(matrixWorld).invert(); }
    /** 镜头看向某一点（同相机的 lookAt：上方是 up） */
    public void lookAt(V3 target) {
        updateWorldMatrix(true, false);
        V3 p = new V3().setFromMatrixPosition(matrixWorld);
        M4 m = new M4().lookAt(p, target, up);
        quaternion.setFromRotationMatrix(m);
        if (parent != null) {
            parent.updateWorldMatrix(true, false);
            Quat q = new Quat();
            new M4().copy(parent.matrixWorld).decompose(new V3(), q, new V3());
            quaternion.premultiply(q.invert());
        }
    }
    public V3 getWorldDirection(V3 t) {
        updateWorldMatrix(true, false);
        float[] e = matrixWorld.e;
        return t.set(-e[8], -e[9], -e[10]).normalize();
    }
}
