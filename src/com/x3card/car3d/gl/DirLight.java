package com.x3card.car3d.gl;

/** 平行光（同 three.js 的 DirectionalLight + DirectionalLightShadow：照向 target（不在场景里，就是原点），能投影子） */
public final class DirLight extends Obj3D {
    public final Col color;
    public float intensity;
    public final Obj3D target = new Obj3D();
    // 阴影
    public final Camera shadowCamera = Camera.ortho(-5, 5, 5, -5, 0.5f, 500);
    public int mapSize = 512;
    public float bias, normalBias, radius = 1;
    public final M4 shadowMatrix = new M4();
    public RT shadowMap;

    public DirLight(int hex, float intensity) {
        color = new Col(hex);
        this.intensity = intensity;
        position.set(0, 1, 0);
    }
    /** 阴影相机摆到光的位置看向 target，算出阴影矩阵（同 LightShadow.updateMatrices） */
    public void updateShadow() {
        V3 p = new V3().setFromMatrixPosition(matrixWorld);
        shadowCamera.position.copy(p);
        target.updateMatrixWorld(false);
        shadowCamera.lookAt(new V3().setFromMatrixPosition(target.matrixWorld));
        shadowCamera.updateMatrixWorld(false);
        M4 ps = new M4().multiplyMatrices(shadowCamera.projectionMatrix, shadowCamera.matrixWorldInverse);
        shadowMatrix.set(0.5f, 0, 0, 0.5f, 0, 0.5f, 0, 0.5f, 0, 0, 0.5f, 0.5f, 0, 0, 0, 1);
        shadowMatrix.multiply(ps);
    }
    public void disposeShadow() { if (shadowMap != null) { shadowMap.dispose(); shadowMap = null; } }
}
