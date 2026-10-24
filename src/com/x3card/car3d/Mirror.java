package com.x3card.car3d;

import android.opengl.GLES30;

import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.M4;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Programs;
import com.x3card.car3d.gl.RT;
import com.x3card.car3d.gl.Renderer;
import com.x3card.car3d.gl.Shapes;
import com.x3card.car3d.gl.V3;

/**
 * 地面反射（画质高、极致；照网页版 car3d.js 的 mirror() + three r149 的 Reflector）：车下面一块镜面，按离车中心的距离渐隐，像展厅抛光的地面。
 * 反射按画面的实际分辨率画（最长边不超过 2048），能多重采样就抗锯齿；取样是 4 个斜角各差半个像素的平滑核；画反射时关掉了色调映射，在这里补上。
 * 一帧只画一次，在画场景之前（pre）画好
 */
final class Mirror {
    final Mesh mesh;
    private final Camera vcam = Camera.perspective(50, 1, 0.1f, 2000);
    private final RT rt;
    private final M4 texMat = new M4();
    private final float R;
    private static final String VS = "uniform mat4 textureMatrix;\nvarying vec4 vUv;\nvarying vec2 vP;\nvoid main() { vUv = textureMatrix * vec4(position, 1.0); vP = position.xy; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }";
    private static final String FS = "uniform sampler2D tDiffuse;\nuniform float uK;\nuniform float uR;\nuniform vec2 uPx;\nvarying vec4 vUv;\nvarying vec2 vP;\nvoid main() {\n" +
        "  vec2 uv = vUv.xy / vUv.w, o = uPx;\n" +
        "  vec4 c = (texture2D(tDiffuse, uv + o) + texture2D(tDiffuse, uv - o) + texture2D(tDiffuse, uv + vec2(o.x, -o.y)) + texture2D(tDiffuse, uv + vec2(-o.x, o.y))) * 0.25;\n" +
        "  gl_FragColor = vec4(c.rgb / max(c.a, 1e-4), c.a * uK * (1.0 - smoothstep(0.25, 0.8, length(vP) / uR)));\n  #include <tonemapping_fragment>\n  #include <encodings_fragment>\n}";

    Mirror(float halfL, float halfW, int w, int h, boolean aa) {
        R = Math.max(halfL, halfW) + 1.2f;
        int[] sp = Post.spec(aa), sz = size(w, h);
        rt = new RT(sz[0], sz[1], sp[0], true, false, sp[1], GLES30.GL_LINEAR);
        Mat m = Mat.shader(VS, FS);
        m.put("tDiffuse", rt.texture); m.put("textureMatrix", texMat); m.put("uK", 0.2f); m.put("uR", R); m.put("uPx", new float[] { 0.5f / sz[0], 0.5f / sz[1] });
        m.transparent = true; m.depthWrite = false; // 输出不预乘的颜色
        mesh = new Mesh(Shapes.plane(R * 2, R * 2), m);
        mesh.rotate((float) (-Math.PI / 2), 0, 0);
        mesh.renderOrder = -3;
        vcam.layers |= 2; // 倒影里也有亮着的灯
    }
    /** 反射的离屏缓冲按画面实际的像素算，最长边不超过 2048 */
    private static int[] size(int w, int h) { float k = Math.min(1, 2048f / Math.max(w, Math.max(h, 1))); return new int[] { Math.max(64, Math.round(w * k)), Math.max(64, Math.round(h * k)) }; }
    /** 拖中缝、横竖屏切换：跟着改大小 */
    void resize(int w, int h) {
        int[] sz = size(w, h);
        if (rt.width == sz[0] && rt.height == sz[1]) return;
        rt.setSize(sz[0], sz[1]);
        mesh.material.put("uPx", new float[] { 0.5f / sz[0], 0.5f / sz[1] });
    }
    void setK(float k) { mesh.material.put("uK", k); }

    /** 画反射（同 Reflector.onBeforeRender）：镜像的相机、斜裁剪面 */
    void render(Renderer r, Obj3D scene, Camera camera) {
        mesh.updateWorldMatrix(true, false);
        camera.updateWorldMatrix(true, false);
        V3 rp = new V3().setFromMatrixPosition(mesh.matrixWorld), cp = new V3().setFromMatrixPosition(camera.matrixWorld);
        M4 rot = rotation(mesh.matrixWorld);
        V3 normal = new V3(0, 0, 1).applyMatrix4(rot);
        V3 view = new V3().subVectors(rp, cp);
        if (view.dot(normal) > 0) return; // 从背面看
        reflect(view, normal).negate().add(rp);
        M4 crot = rotation(camera.matrixWorld);
        V3 look = new V3(0, 0, -1).applyMatrix4(crot).add(cp);
        V3 target = reflect(new V3().subVectors(rp, look), normal).negate().add(rp);
        vcam.position.copy(view);
        vcam.up.set(0, 1, 0).applyMatrix4(crot);
        reflect(vcam.up, normal);
        vcam.lookAt(target);
        vcam.far = camera.far;
        vcam.updateMatrixWorld(true);
        vcam.projectionMatrix.copy(camera.projectionMatrix);
        texMat.set(0.5f, 0, 0, 0.5f, 0, 0.5f, 0, 0.5f, 0, 0, 0.5f, 0.5f, 0, 0, 0, 1);
        texMat.multiply(vcam.projectionMatrix).multiply(vcam.matrixWorldInverse).multiply(mesh.matrixWorld);
        // 斜裁剪面（Lengyel）：只画镜面上面的东西
        V3 pn = normal.clone(), pp = rp.clone();
        float[] vi = vcam.matrixWorldInverse.e;
        V3 nn = new V3(vi[0] * pn.x + vi[4] * pn.y + vi[8] * pn.z, vi[1] * pn.x + vi[5] * pn.y + vi[9] * pn.z, vi[2] * pn.x + vi[6] * pn.y + vi[10] * pn.z).normalize();
        V3 q0 = pp.clone().applyMatrix4(vcam.matrixWorldInverse);
        float cx = nn.x, cy = nn.y, cz = nn.z, cw = -q0.dot(nn);
        float[] e = vcam.projectionMatrix.e;
        float qx = (Math.signum(cx) + e[8]) / e[0], qy = (Math.signum(cy) + e[9]) / e[5], qz = -1, qw = (1 + e[10]) / e[14];
        float s = 2f / (cx * qx + cy * qy + cz * qz + cw * qw);
        e[2] = cx * s; e[6] = cy * s; e[10] = cz * s + 1 - 0.003f; e[14] = cw * s;
        mesh.visible = false;
        RT keep = r.getRenderTarget();
        int enc = r.outputEncoding, tm = r.toneMapping;
        boolean ac = r.autoClear;
        r.outputEncoding = Programs.LINEAR; r.toneMapping = Programs.NO_TONE;
        r.setRenderTarget(rt);
        r.autoClear = true;
        r.render(scene, vcam);
        r.autoClear = ac;
        r.outputEncoding = enc; r.toneMapping = tm;
        r.setRenderTarget(keep);
        mesh.visible = true;
    }
    private static M4 rotation(M4 m) { // 同 extractRotation
        M4 o = new M4();
        float[] a = m.e, b = o.e;
        float sx = 1 / (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), sy = 1 / (float) Math.sqrt(a[4] * a[4] + a[5] * a[5] + a[6] * a[6]), sz = 1 / (float) Math.sqrt(a[8] * a[8] + a[9] * a[9] + a[10] * a[10]);
        b[0] = a[0] * sx; b[1] = a[1] * sx; b[2] = a[2] * sx; b[4] = a[4] * sy; b[5] = a[5] * sy; b[6] = a[6] * sy; b[8] = a[8] * sz; b[9] = a[9] * sz; b[10] = a[10] * sz;
        b[3] = b[7] = b[11] = b[12] = b[13] = b[14] = 0; b[15] = 1;
        return o;
    }
    private static V3 reflect(V3 v, V3 n) { return v.sub(n.clone().multiplyScalar(2 * v.dot(n))); }
    void dispose() { rt.dispose(); mesh.material.dispose(); mesh.geometry.dispose(); }
}
