package com.x3card.car3d;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.M4;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Shapes;
import com.x3card.car3d.gl.Tex;
import com.x3card.car3d.gl.V3;
import com.x3card.car3d.gl.Val;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电时车旁边的充电桩、插在充电口上的充电枪和线缆、地上围着车的一圈光（装饰，跟着车况：插着枪就显示；充电时光沿线缆流进车里，光环亮的那段 = 电量）。
 * 充电桩、枪、线缆在图层 0（照常受光、投影子）；线缆上流动的光、立柱上的灯条、地上的光环在图层 1：只有它们在动时走"只画图层 1"那条省电的路（同灯的点亮层）。
 * 出现：充电桩从地上长出来 → 线缆从桩上伸向车 → 枪出现、插进充电口 → 光环从 0 扫到电量；拔枪倒着放一遍。坐标都是车的（车头 +z、左边 +x、地面 y = 0，单位米）
 */
final class Charger {
    final Obj3D root = new Obj3D();
    private final Obj3D pile = new Obj3D(), gun = new Obj3D();
    private final Mesh cable, pileShadow;
    private final Tube tube;
    private final V3 port, nrm, hand = new V3(), outlet = new V3();
    /** 充电口朝外的水平方向、充电桩往哪一头挪（离充电口近的那一头） */
    private final V3 nh = new V3(), end = new V3();
    private final List<V3> pts = new ArrayList<>();
    private final List<Mat> glows = new ArrayList<>();
    private final Val uT = new Val(0f), uOn = new Val(0f), uFlow = new Val(0f), uPct = new Val(-1f), uDark = new Val(0f), uOff = new Val(0f);
    private boolean want, charging;
    private float k, flowK, pct = -1, pctNow = -1, lastF = -1, lastG = -1;
    private final long t0 = System.currentTimeMillis();
    /** 枪插好以前在充电口外面多远（插进去的那一段） */
    private static final float PRE = 0.22f;
    /** 充电桩整个放大多少（按真实交流桩 1.6 米做的，站在车后面透视一缩显得单薄；放大到 2.1 米左右，同直流快充桩） */
    private static final float PS = 1.3f;

    // ================= 着色器（图层 1：不做色调映射，sRGB 里算好转线性，同灯的点亮层） =================
    private static final String VS = "varying vec2 vUv;\nuniform float uPush;\nvoid main() {\n  vUv = uv;\n  gl_Position = projectionMatrix * modelViewMatrix * vec4(position + normal * uPush, 1.0);\n}";
    private static final String HEAD = "uniform float uT;\nuniform float uOn;\nuniform float uFlow;\nuniform float uDark;\nvarying vec2 vUv;\n"
        + "vec3 green() { return mix(vec3(0.07, 0.72, 0.5), vec3(0.24, 0.94, 0.66), uDark); }\n"; // 浅色背景上压深一点才看得清
    /** 线缆上的光：一段段往车那边流（vUv.x 是离桩上出线口多少米） */
    private static final String CABLE_FS = HEAD + "void main() {\n"
        + "  float ph = fract(vUv.x / 0.16 - uT * 1.25);\n"
        + "  float dash = smoothstep(0.0, 0.3, ph) * (1.0 - smoothstep(0.42, 0.7, ph));\n"
        + "  float a = (0.18 + 0.82 * dash) * uFlow * uOn;\n"
        + "  if (a < 0.004) discard;\n"
        + "  gl_FragColor = vec4(pow(mix(green(), vec3(1.0), 0.35 * dash), vec3(2.2)), a * (0.8 + 0.2 * uDark));\n"
        + "  #include <encodings_fragment>\n}";
    /** 立柱上的灯条：充电时从下往上流，插着没在充时安静地亮着 */
    private static final String STRIP_FS = HEAD + "void main() {\n"
        + "  float ph = fract(vUv.y * 2.5 - uT);\n"
        + "  float dash = smoothstep(0.0, 0.25, ph) * (1.0 - smoothstep(0.35, 0.8, ph));\n"
        + "  float a = mix(0.55, 0.35 + 0.65 * dash, uFlow) * uOn;\n"
        + "  if (a < 0.004) discard;\n"
        + "  gl_FragColor = vec4(pow(mix(green(), vec3(1.0), 0.3 * dash * uFlow), vec3(2.2)), a);\n"
        + "  #include <encodings_fragment>\n}";
    /** 地上的光环：vUv.x 从车尾正中绕一圈 0～1（见 ellipse），vUv.y 横过环带；亮的一段 = 电量，头上一个发白的光点，充电时一道光从起点扫到头 */
    private static final String RING_FS = HEAD + "uniform float uPct;\nuniform float uPer;\nuniform float uOff;\nvoid main() {\n"
        + "  float u = fract(vUv.x + uOff);\n" // 亮弧从哪儿起（跟着镜头转，见 ringAt）
        + "  float v = abs(vUv.y - 0.5) * 2.0;\n" // 0 中线 → 1 环带边上
        + "  float line = exp(-v * v * 70.0), body = exp(-v * v * 9.0), halo = (1.0 - v) * (1.0 - v);\n" // 发白的细线、亮带、外面一大圈柔光
        + "  float known = step(0.0, uPct), d = (uPct - u) * uPer;\n" // 离亮弧的头多少米（负的是还没充到的）
        + "  float lit = known > 0.5 ? max(smoothstep(-0.06, 0.06, d) * smoothstep(0.0, 0.08, u * uPer), step(0.999, uPct)) : 1.0;\n"
        + "  float head = known * exp(-d * d / 0.06) * step(0.004, uPct);\n"
        + "  float s = fract(uT / 2.5) * (known > 0.5 ? uPct : 1.0), sw = exp(-pow((u - s) * uPer / 0.6, 2.0)) * lit * uFlow;\n"
        + "  float i = lit * (line + 0.8 * body + 0.35 * halo) + (1.0 - lit) * (0.12 * body + 0.05 * halo) + (0.6 * sw + head) * (line + 0.7 * body + 0.4 * halo);\n"
        + "  float hot = clamp(line * (0.55 * lit + head + 0.5 * sw), 0.0, 1.0);\n"
        + "  float a = clamp(i, 0.0, 1.0) * uOn;\n"
        + "  if (a < 0.004) discard;\n"
        + "  gl_FragColor = vec4(pow(mix(green(), vec3(1.0), hot * 0.8), vec3(2.2)), a);\n"
        + "  #include <encodings_fragment>\n}";

    /**
     * P：充电口（车身表面上的点），N：那里朝外的方向；hw / hl / h：车的半宽、半长、高；env：环境反光；shadow：投影子
     */
    Charger(Stage app, V3 P, V3 N, float hw, float hl, float h, float env, boolean shadow) {
        port = P.clone(); nrm = N.clone().normalize();
        root.visible = false;
        // ---- 充电桩摆在哪：从充电口往外 1 米、再往离得近的那一头挪 0.6 米，离车身至少 0.65 米；横梁朝着充电口 ----
        nh.set(nrm.x, 0, nrm.z);
        if (nh.length() < 0.3f) nh.set(port.x > 0 ? 1 : -1, 0, 0);
        nh.normalize();
        if (Math.abs(nh.x) >= Math.abs(nh.z)) end.set(0, 0, port.z >= 0 ? 1 : -1); else end.set(port.x > 0.05f ? 1 : -1, 0, 0);
        V3 t = end;
        V3 base = new V3(port.x, 0, port.z).addScaled(nh, 1.05f).addScaled(t, 0.6f);
        for (int i = 0; i < 40 && Math.abs(base.x) < hw + 0.65f && Math.abs(base.z) < hl + 0.65f; i++) base.addScaled(nh, 0.05f);
        V3 dir = new V3(port.x - base.x, 0, port.z - base.z).normalize();
        pile.position.copy(base);
        pile.rotate(0, (float) Math.atan2(-dir.z, dir.x), 0);
        pile.scale.setScalar(PS);
        root.add(pile);

        Mat teal = std(0x1fa89c, 0.32f, 0.15f, env), dark = std(0x2c3137, 0.5f, 0.35f, env), black = std(0x15171a, 0.6f, 0f, env), body = std(0x30353b, 0.38f, 0.2f, env), metal = std(0xc3c9ce, 0.25f, 0.9f, env);
        Mat led = Mat.basic(); led.color.setHex(0xe9f5f2);
        // ---- 充电桩（自己的坐标：横梁朝 +x 伸向车，y 朝上，底在地上） ----
        part(pile, roundBox(0.40f, 0.06f, 0.30f, 0.02f, 2), dark, 0, 0.03f, 0, shadow);
        part(pile, roundBox(0.26f, 0.46f, 0.20f, 0.04f, 3), dark, 0, 0.29f, 0, shadow);
        part(pile, roundBox(0.17f, 1.02f, 0.13f, 0.045f, 3), teal, 0, 1.0f, 0, shadow);
        part(pile, roundBox(0.92f, 0.12f, 0.19f, 0.045f, 3), teal, 0.24f, 1.56f, 0, shadow);
        Mesh o = part(pile, cylinder(0.035f, 0, 0.06f, 16), black, 0.62f, 1.44f, 0, shadow);
        o.rotate((float) (-Math.PI / 2), 0, 0); // 出线口：圆柱朝下
        Tex logo = app.canvasTex(256, 64, Charger::logo, true);
        Mat lm = Mat.basic(); lm.map = logo; lm.transparent = true; lm.depthWrite = false;
        Mat sm = shaderMat(STRIP_FS);
        for (int s = -1; s <= 1; s += 2) {
            Mesh lp = new Mesh(Shapes.plane(0.32f, 0.08f), lm);
            lp.position.set(0.16f, 1.56f, s * 0.0965f); lp.rotate(0, s > 0 ? 0 : (float) Math.PI, 0); lp.renderOrder = 1;
            pile.add(lp);
            Mesh st = new Mesh(Shapes.plane(0.026f, 0.7f), led);
            st.position.set(0, 1.0f, s * 0.0662f); st.rotate(0, s > 0 ? 0 : (float) Math.PI, 0);
            pile.add(st);
            Mesh sg = new Mesh(Shapes.plane(0.026f, 0.7f), sm);
            sg.position.set(0, 1.0f, s * 0.0668f); sg.rotate(0, s > 0 ? 0 : (float) Math.PI, 0); sg.layers = 2; sg.renderOrder = 3;
            pile.add(sg);
        }
        // 桩底下一块柔和的影子
        Tex sh = app.canvasTex(64, 64, (cv, w, hh) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w / 2f, hh / 2f, w / 2f, new int[] { 0x99000000, 0x44000000, 0x00000000 }, new float[] { 0, 0.5f, 1 }, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, hh, p);
        }, false);
        Mat shm = Mat.basic(); shm.map = sh; shm.color.setHex(0x000000); shm.transparent = true; shm.depthWrite = false;
        pileShadow = new Mesh(Shapes.plane(0.75f, 0.6f), shm);
        pileShadow.rotate((float) (-Math.PI / 2), 0, 0); pileShadow.position.set(0, 0.0025f, 0);
        pile.add(pileShadow);
        pile.updateMatrixWorld(true);
        outlet.set(0.62f, 1.445f, 0).applyMatrix4(pile.matrixWorld);

        // ---- 充电枪（自己的坐标：z 朝外 = 充电口朝外的方向，y 朝上；枪口在原点往里插 2 厘米） ----
        V3 gz = nrm.clone(), gy = new V3(0, 1, 0).addScaled(gz, -gz.y);
        if (gy.length() < 0.2f) gy.set(0, 0, 1).addScaled(gz, -gz.z);
        gy.normalize();
        V3 gx = gy.clone().cross(gz).normalize();
        gun.quaternion.setFromRotationMatrix(new M4().set(gx.x, gy.x, gz.x, 0, gx.y, gy.y, gz.y, 0, gx.z, gy.z, gz.z, 0, 0, 0, 0, 1));
        part(gun, cylinder(0.03f, -0.02f, 0.055f, 18), black, 0, 0, 0, shadow);
        part(gun, cylinder(0.044f, 0.05f, 0.064f, 18), metal, 0, 0, 0, shadow);
        part(gun, roundBox(0.085f, 0.1f, 0.1f, 0.03f, 3), body, 0, 0, 0.11f, shadow);
        Mesh hd = part(gun, roundBox(0.06f, 0.055f, 0.2f, 0.025f, 3), body, 0, -0.02f - 0.8f * 0.13f, 0.11f + 0.6f * 0.13f, shadow);
        hd.rotate((float) Math.toRadians(53), 0, 0); // 握把往下、往外斜（z 轴转到 (0, -0.8, 0.6)）
        root.add(gun);
        hand.set(0, -0.02f - 0.8f * 0.25f, 0.11f + 0.6f * 0.25f); // 握把尾巴（线缆从这里出来），枪自己的坐标

        // ---- 线缆：底下一根黑色橡胶管（图层 0），外面套一层流动的光（图层 1，同一份几何、沿法线往外推一点） ----
        tube = new Tube(56, 10);
        Mat cm = std(0x15171a, 0.55f, 0f, env); cm.side = Mat.DOUBLE; // 伸出来的时候看得到管子里面
        cable = new Mesh(tube.geo, cm);
        cable.frustumCulled = false; cable.castShadow = shadow; cable.receiveShadow = shadow;
        root.add(cable);
        Mat gm = shaderMat(CABLE_FS);
        gm.uniforms.put("uPush", new Val(0.0015f));
        Mesh glow = new Mesh(tube.geo, gm);
        glow.frustumCulled = false; glow.layers = 2; glow.renderOrder = 3;
        root.add(glow);

        // ---- 地上的光环：围着车的椭圆，宽 0.6 米（亮的只有中间一条，两边是柔光） ----
        float rx = hw + 0.5f, rz = hl + 0.38f;
        float[] per = new float[1];
        Mat rm = shaderMat(RING_FS);
        Mesh ring = new Mesh(ellipse(rx, rz, 0.6f, 160, per), rm);
        rm.uniforms.put("uPct", uPct); rm.uniforms.put("uPer", new Val(per[0])); rm.uniforms.put("uOff", uOff);
        ring.layers = 2; ring.renderOrder = 2;
        root.add(ring);

        // 取景用的点：充电桩的几个角、光环最外面一圈
        for (float[] c : new float[][] { { -0.2f, 0, -0.15f }, { -0.2f, 0, 0.15f }, { 0.7f, 1.62f, -0.1f }, { 0.7f, 1.62f, 0.1f }, { -0.22f, 1.62f, -0.1f }, { -0.22f, 1.62f, 0.1f } })
            pts.add(new V3(c[0], c[1], c[2]).applyMatrix4(pile.matrixWorld));
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; pts.add(new V3(Math.sin(a) * (rx + 0.2f), 0, Math.cos(a) * (rz + 0.2f))); }
        place(0);
    }

    private static Mat std(int hex, float rough, float metal, float env) {
        Mat m = Mat.standard();
        m.color.setHex(hex); m.roughness = rough; m.metalness = metal; m.envMapIntensity = env;
        return m;
    }
    private Mat shaderMat(String fs) {
        Mat m = Mat.shader(VS, fs);
        m.uniforms.put("uT", uT); m.uniforms.put("uOn", uOn); m.uniforms.put("uFlow", uFlow); m.uniforms.put("uDark", uDark);
        if (!m.uniforms.containsKey("uPush")) m.uniforms.put("uPush", new Val(0f));
        m.transparent = true; m.depthWrite = false; m.toneMapped = false; m.side = Mat.DOUBLE;
        glows.add(m);
        return m;
    }
    private static Mesh part(Obj3D to, Geo g, Mat m, float x, float y, float z, boolean shadow) {
        Mesh o = new Mesh(g, m);
        o.position.set(x, y, z);
        o.castShadow = o.receiveShadow = shadow;
        to.add(o);
        return o;
    }
    /** 横梁两面的字：闪电 + 充电（白字，底是透明的） */
    private static void logo(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff);
        Path b = new Path();
        float s = h / 64f, x0 = w * 0.2f;
        b.moveTo(x0 + 12 * s, 6 * s); b.lineTo(x0 - 8 * s, 36 * s); b.lineTo(x0 + 4 * s, 36 * s); b.lineTo(x0 - 4 * s, 58 * s); b.lineTo(x0 + 18 * s, 26 * s); b.lineTo(x0 + 6 * s, 26 * s); b.close();
        c.drawPath(b, p);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(40 * s);
        c.drawText("充电", x0 + 28 * s, 46 * s, p);
    }

    /** 车况：插着枪（或者在充电）就显示；电量 0～100，没有是 null */
    void set(boolean show, boolean chg, Double battery) {
        want = show; charging = chg;
        pct = battery != null ? Math.max(0, Math.min(1, battery.floatValue() / 100)) : -1;
        if (pctNow < 0 || pct < 0) pctNow = pct;
    }
    boolean wanted() { return want; }
    /** 镜头从 az（同 viewAz）看过来时：亮弧绕到八成的地方正对着镜头——电量过半时亮弧的头、还没充到的那段都在看得见的这一侧（不正对着，是让开那边轮子的胎压标签） */
    void ringAt(float az) {
        double u = 0.8 - (Math.PI - az) / (2 * Math.PI);
        uOff.v = (float) (u - Math.floor(u));
    }
    /**
     * 看充电桩的水平角度（同 CarPage 的 vAz：镜头在 (sin, cos) 那边）：镜头在充电口那一侧、离充电桩远的那一头斜着看，桩、线缆、枪都不被车挡住。
     * a 是和车侧面的夹角（用默认视角的，默认视角正好是看右后的充电口）
     */
    float viewAz(float a) {
        float s = (float) Math.sin(a), c = (float) Math.cos(a);
        return (float) Math.atan2(nh.x * s - end.x * c, nh.z * s - end.z * c);
    }
    /** 取景要装下的点（光环、充电桩） */
    List<V3> points() { return pts; }

    /**
     * 每帧：0 不用再画；1 只有图层 1 在动（光在流）；2 图层 0 也在动（长出来、插枪、拔枪）
     */
    int tick(float dt, float dark) {
        uDark.v = dark;
        int bl = dark > 0.5f ? Mat.ADDITIVE : Mat.NORMAL; // 深色背景上叠加着画，像发光；浅色背景上叠加就看不见了，照常画
        for (Mat m : glows) m.blending = bl;
        float k0 = k;
        k = want ? Math.min(1, k + dt / 1.8f) : Math.max(0, k - dt / 1.1f);
        float fw = charging && want ? 1 : 0, f0 = flowK;
        flowK = fw > flowK ? Math.min(1, flowK + dt / 0.6f) : Math.max(0, flowK - dt / 0.6f);
        float p0 = pctNow;
        if (pct >= 0 && pctNow >= 0 && Math.abs(pct - pctNow) > 0.001f) pctNow += (pct - pctNow) * (1 - (float) Math.exp(-dt * 3)); else pctNow = pct;
        root.visible = k > 0;
        if (k == 0) { uOn.v = 0f; return 0; }
        boolean geo = k != k0;
        if (geo) place(k);
        float rk = sm((k - 0.72f) / 0.28f);
        uOn.v = rk;
        uFlow.v = flowK;
        uPct.v = pctNow < 0 ? -1f : pctNow * rk;
        boolean live = flowK > 0 && rk > 0;
        if (live) uT.v = ((System.currentTimeMillis() - t0) % 40000) / 1000f; // 40 秒一轮（几种光的周期都能整除）
        return geo ? 2 : live || flowK != f0 || pctNow != p0 ? 1 : 0;
    }

    /** 按出现的进度摆：桩长出来（0～0.35）→ 线缆伸过去（0.25～0.62）→ 枪冒出来（0.55～0.66）→ 插进去（0.66～0.84） */
    private void place(float k) {
        float pk = backOut(cl(k / 0.35f));
        pile.scale.set(PS, PS * Math.max(0.001f, pk), PS);
        pile.visible = k > 0;
        float gs = sm((k - 0.55f) / 0.11f), g = sm((k - 0.66f) / 0.18f);
        gun.visible = gs > 0;
        gun.scale.setScalar(Math.max(0.001f, gs));
        gun.position.copy(port).addScaled(nrm, PRE * (1 - g));
        gun.updateMatrixWorld(true);
        float f = sm((k - 0.25f) / 0.37f);
        cable.visible = f > 0;
        if (f > 0 && (f != lastF || g != lastG)) {
            V3 tail = hand.clone().applyMatrix4(gun.matrixWorld);
            V3 hd = new V3(0, -0.8f, 0.6f).applyQuaternion(gun.quaternion); // 握把往外的方向（线缆从这里接着往下垂）
            tube.curve(outlet, tail, hd, f);
        }
        lastF = f; lastG = g;
    }
    private static float cl(float x) { return Math.max(0, Math.min(1, x)); }
    private static float sm(float x) { x = cl(x); return x * x * (3 - 2 * x); }
    private static float backOut(float x) { float c = 1.4f, y = x - 1; return x <= 0 ? 0 : 1 + (c + 1) * y * y * y + c * y * y; }

    // ================= 几何 =================
    /** 圆角长方体（中心在原点）：三个方向各取一串坐标（中间两个、两头圆角上按角度均匀的几个），六个面各铺一张网格，再把顶点推到圆角上 */
    static Geo roundBox(float w, float h, float d, float r, int seg) {
        float[] half = { w / 2, h / 2, d / 2 };
        r = Math.min(r, Math.min(half[0], Math.min(half[1], half[2])) * 0.999f);
        float[][] L = new float[3][];
        for (int a = 0; a < 3; a++) {
            float in = half[a] - r;
            float[] s = new float[2 * seg + 2];
            for (int i = 0; i <= seg; i++) { float v = in + r * (float) Math.tan(i / (float) seg * Math.PI / 4); s[seg - i] = -v; s[seg + 1 + i] = v; }
            L[a] = s;
        }
        int n1 = 2 * seg + 2, per = n1 * n1;
        float[] pos = new float[6 * per * 3], nor = new float[6 * per * 3];
        int[] idx = new int[6 * (n1 - 1) * (n1 - 1) * 6];
        int vi = 0, ii = 0, face = 0;
        float[] p = new float[3], c = new float[3];
        for (int ax = 0; ax < 3; ax++) for (int sg = -1; sg <= 1; sg += 2) {
            int j = (ax + 1) % 3, k = (ax + 2) % 3, start = face * per;
            for (int a = 0; a < n1; a++) for (int b = 0; b < n1; b++) {
                p[ax] = sg * half[ax]; p[j] = L[j][a]; p[k] = L[k][b];
                float dx = 0, len = 0;
                for (int q = 0; q < 3; q++) { float in = half[q] - r; c[q] = Math.max(-in, Math.min(in, p[q])); }
                for (int q = 0; q < 3; q++) { dx = p[q] - c[q]; len += dx * dx; }
                len = (float) Math.sqrt(len);
                for (int q = 0; q < 3; q++) { float n = len > 0 ? (p[q] - c[q]) / len : (q == ax ? sg : 0); nor[vi * 3 + q] = n; pos[vi * 3 + q] = c[q] + n * r; }
                vi++;
            }
            for (int a = 0; a + 1 < n1; a++) for (int b = 0; b + 1 < n1; b++) {
                int v00 = start + a * n1 + b, v10 = v00 + n1, v11 = v10 + 1, v01 = v00 + 1;
                if (sg > 0) { idx[ii++] = v00; idx[ii++] = v10; idx[ii++] = v11; idx[ii++] = v00; idx[ii++] = v11; idx[ii++] = v01; }
                else { idx[ii++] = v00; idx[ii++] = v11; idx[ii++] = v10; idx[ii++] = v00; idx[ii++] = v01; idx[ii++] = v11; }
            }
            face++;
        }
        Geo g = new Geo();
        g.set("position", Attr.floats(pos, 3)); g.set("normal", Attr.floats(nor, 3));
        g.setIndex(Attr.index(idx, vi));
        return g;
    }
    /** 圆柱：沿 z 从 z0 到 z1，两头封口 */
    static Geo cylinder(float r, float z0, float z1, int seg) {
        int nv = (seg + 1) * 2 + (seg + 2) * 2;
        float[] pos = new float[nv * 3], nor = new float[nv * 3];
        List<Integer> ix = new ArrayList<>();
        int v = 0;
        for (int i = 0; i <= seg; i++) {
            double a = i * 2 * Math.PI / seg;
            float x = (float) Math.cos(a), y = (float) Math.sin(a);
            for (float z : new float[] { z0, z1 }) { pos[v * 3] = x * r; pos[v * 3 + 1] = y * r; pos[v * 3 + 2] = z; nor[v * 3] = x; nor[v * 3 + 1] = y; v++; }
            if (i < seg) { int b = i * 2; ix.add(b); ix.add(b + 2); ix.add(b + 3); ix.add(b); ix.add(b + 3); ix.add(b + 1); }
        }
        for (int e = 0; e < 2; e++) {
            float z = e == 0 ? z0 : z1, nz = e == 0 ? -1 : 1;
            int c = v;
            pos[v * 3 + 2] = z; nor[v * 3 + 2] = nz; v++;
            for (int i = 0; i <= seg; i++) {
                double a = i * 2 * Math.PI / seg;
                pos[v * 3] = (float) Math.cos(a) * r; pos[v * 3 + 1] = (float) Math.sin(a) * r; pos[v * 3 + 2] = z; nor[v * 3 + 2] = nz; v++;
                if (i < seg) { if (e == 1) { ix.add(c); ix.add(c + 1 + i); ix.add(c + 2 + i); } else { ix.add(c); ix.add(c + 2 + i); ix.add(c + 1 + i); } }
            }
        }
        int[] a = new int[ix.size()];
        for (int i = 0; i < a.length; i++) a[i] = ix.get(i);
        Geo g = new Geo();
        g.set("position", Attr.floats(pos, 3)); g.set("normal", Attr.floats(nor, 3));
        g.setIndex(Attr.index(a, v));
        return g;
    }
    /** 地上的椭圆环带：从车尾正中（-z）经左边（+x）、车头绕到右边（-x）——默认视角看的是车的右前方，电量过半时亮弧的头就在看得见的这一侧；uv.x 绕一圈 0～1，uv.y 从里到外 0～1；per[0] 交回周长（米） */
    static Geo ellipse(float rx, float rz, float w, int n, float[] per) {
        float[] pos = new float[(n + 1) * 2 * 3], nor = new float[(n + 1) * 2 * 3], uv = new float[(n + 1) * 2 * 2];
        int[] ix = new int[n * 6];
        float len = 0, px = 0, pz = 0;
        for (int i = 0; i <= n; i++) {
            double a = i * 2 * Math.PI / n;
            float x = (float) (rx * Math.sin(a)), z = (float) (-rz * Math.cos(a));
            float nx = (float) (Math.sin(a) / rx), nz = (float) (-Math.cos(a) / rz), l = (float) Math.sqrt(nx * nx + nz * nz);
            nx /= l; nz /= l;
            if (i > 0) len += (float) Math.hypot(x - px, z - pz);
            px = x; pz = z;
            for (int s = 0; s < 2; s++) {
                int v = i * 2 + s;
                float o = (s == 0 ? -w : w) / 2;
                pos[v * 3] = x + nx * o; pos[v * 3 + 1] = 0.006f; pos[v * 3 + 2] = z + nz * o;
                nor[v * 3 + 1] = 1;
                uv[v * 2] = i / (float) n; uv[v * 2 + 1] = s;
            }
            if (i < n) { int b = i * 2; ix[i * 6] = b; ix[i * 6 + 1] = b + 3; ix[i * 6 + 2] = b + 1; ix[i * 6 + 3] = b; ix[i * 6 + 4] = b + 2; ix[i * 6 + 5] = b + 3; }
        }
        per[0] = len;
        Geo g = new Geo();
        g.set("position", Attr.floats(pos, 3)); g.set("normal", Attr.floats(nor, 3)); g.set("uv", Attr.floats(uv, 2));
        g.setIndex(Attr.index(ix, (n + 1) * 2));
        return g;
    }

    /** 线缆：沿一条三次曲线的管子（顶点数不变，出现时只取曲线的前一段，每帧改写顶点） */
    static final class Tube {
        final Geo geo = new Geo();
        final int ns, nr;
        final float[] pos, nor, uv;
        final Attr aPos, aNor, aUv;
        private final V3[] pt, tg;
        Tube(int ns, int nr) {
            this.ns = ns; this.nr = nr;
            int nv = (ns + 1) * (nr + 1);
            pos = new float[nv * 3]; nor = new float[nv * 3]; uv = new float[nv * 2];
            pt = new V3[ns + 1]; tg = new V3[ns + 1];
            for (int i = 0; i <= ns; i++) { pt[i] = new V3(); tg[i] = new V3(); }
            int[] ix = new int[ns * nr * 6];
            int q = 0;
            for (int i = 0; i < ns; i++) for (int j = 0; j < nr; j++) {
                int a = i * (nr + 1) + j, b = a + nr + 1;
                ix[q++] = a; ix[q++] = a + 1; ix[q++] = b; ix[q++] = a + 1; ix[q++] = b + 1; ix[q++] = b;
            }
            aPos = Attr.floats(pos, 3); aNor = Attr.floats(nor, 3); aUv = Attr.floats(uv, 2);
            geo.set("position", aPos); geo.set("normal", aNor); geo.set("uv", aUv);
            geo.setIndex(Attr.index(ix, nv));
        }
        /** 从桩上的出线口 o 往下垂，到枪的握把尾巴 e（握把朝 hd 方向伸出来）；只要前 f 段 */
        void curve(V3 o, V3 e, V3 hd, float f) {
            V3 q1 = o.clone().add(new V3(0, -0.75f, 0)), q2 = e.clone().addScaled(hd, 0.55f);
            for (int i = 0; i <= ns; i++) {
                float t = f * i / ns;
                bez(o, q1, q2, e, t, pt[i]);
                if (pt[i].y < 0.02f) pt[i].y = 0.02f;
            }
            for (int i = 0; i <= ns; i++) tg[i].subVectors(pt[Math.min(ns, i + 1)], pt[Math.max(0, i - 1)]).normalize();
            V3 n = new V3(0, 1, 0).cross(tg[0]);
            if (n.length() < 0.1f) n.set(1, 0, 0).cross(tg[0]);
            n.normalize();
            V3 b = new V3();
            float s = 0, r = 0.017f;
            for (int i = 0; i <= ns; i++) {
                if (i > 0) { s += pt[i].distanceTo(pt[i - 1]); n.addScaled(tg[i], -n.dot(tg[i])).normalize(); }
                b.copy(tg[i]).cross(n);
                for (int j = 0; j <= nr; j++) {
                    double a = j * 2 * Math.PI / nr;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    int v = i * (nr + 1) + j;
                    float nx = n.x * ca + b.x * sa, ny = n.y * ca + b.y * sa, nz = n.z * ca + b.z * sa;
                    nor[v * 3] = nx; nor[v * 3 + 1] = ny; nor[v * 3 + 2] = nz;
                    pos[v * 3] = pt[i].x + nx * r; pos[v * 3 + 1] = pt[i].y + ny * r; pos[v * 3 + 2] = pt[i].z + nz * r;
                    uv[v * 2] = s; uv[v * 2 + 1] = j / (float) nr;
                }
            }
            put(aPos, pos); put(aNor, nor); put(aUv, uv);
        }
        private static void put(Attr a, float[] d) { a.buf.data.asFloatBuffer().put(d); a.buf.version++; }
        private static V3 bez(V3 a, V3 b, V3 c, V3 d, float t, V3 out) {
            float u = 1 - t, k0 = u * u * u, k1 = 3 * u * u * t, k2 = 3 * u * t * t, k3 = t * t * t;
            return out.set(a.x * k0 + b.x * k1 + c.x * k2 + d.x * k3, a.y * k0 + b.y * k1 + c.y * k2 + d.y * k3, a.z * k0 + b.z * k1 + c.z * k2 + d.z * k3);
        }
    }
}
