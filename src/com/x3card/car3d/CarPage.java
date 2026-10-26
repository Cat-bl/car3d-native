package com.x3card.car3d;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.opengl.GLES30;
import android.util.Log;

import com.x3card.car3d.gl.Attr;
import com.x3card.car3d.gl.Box3;
import com.x3card.car3d.gl.Camera;
import com.x3card.car3d.gl.Col;
import com.x3card.car3d.gl.DirLight;
import com.x3card.car3d.gl.Geo;
import com.x3card.car3d.gl.Gltf;
import com.x3card.car3d.gl.M4;
import com.x3card.car3d.gl.Mat;
import com.x3card.car3d.gl.Mesh;
import com.x3card.car3d.gl.Obj3D;
import com.x3card.car3d.gl.Programs;
import com.x3card.car3d.gl.Ray;
import com.x3card.car3d.gl.Scene;
import com.x3card.car3d.gl.Shapes;
import com.x3card.car3d.gl.Tex;
import com.x3card.car3d.gl.V3;
import com.x3card.car3d.gl.Val;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D 车况（照网页版 demo3d/car3d.js 的展示部分逐段搬）：按车模配置建车（车漆 / 玻璃 / 灯罩换材质、门窗盖挂铰链、天窗遮阳帘、充电口、车轮分组、接触阴影、胎压光圈、
 * 灯的点亮层和路面光束、车牌、贴纸），车况一来门窗灯跟着动；镜头：进场、默认视角、挂 D 挡车顶视角、胎压异常转过去看轮子、开门转过去看门；
 * 下面那行字、车况图标、底部数据、胎压标签、车顶空调键交给界面（Out）画。调整车模的那些（点选、圈选、铰链小球、拖车牌……）在网页版里做，这里没有。
 * 全部在 GL 线程里跑；界面的回调（Out）由实现方切回主线程
 */
final class CarPage implements Stage.Page {
    /** 交给界面的（GL 线程里调） */
    interface Out {
        void loading(String text);
        void top(String gear, boolean speedOn, String speed, String power, boolean live);
        void stats(List<String[]> items);
        void status(List<St> list, boolean open);
        void tells(List<CarStat.Item> l, List<CarStat.Item> r, boolean portrait);
        void sys(List<CarStat.Sys> s);
        void tyres(float[] xy, String[] cls, String[] text, String[] temp);
        void roof(boolean has, boolean show, float x, float y, boolean on, boolean wait);
        void dark(boolean d);
        void hint(boolean show);
        void toast(String s, int ms);
        void modelUI(String name, String credit, boolean resetOff, String[] tips);
        void acSet(boolean on);
        /** 导入车模的进度（步骤、0～1，只往前走） */
        void progress(String step, float f);
        /** 导入的车模建好、画出来了（或者建不出来换回了默认车模）：收起导入框，说一句 */
        void imported(String msg);
    }
    /** 下面那行字的一条：等级、文字、点了看哪儿（轮子 / 部件） */
    static final class St { int lv; String t; List<String> tyres, parts; St(int lv, String t) { this.lv = lv; this.t = t; } boolean go() { return tyres != null || parts != null; } }

    private final Out out;
    private final Settings S;
    private final SharedPreferences prefs;
    private final android.content.Context ctx;
    private Stage app;
    private int gen;
    private Scene scene;
    private Camera camera;
    private Tex envRT, envRoom;
    private DirLight keyLight;
    private Obj3D root, modelObj;
    private M Mt;
    private Map<String, Part> parts;
    private Map<String, Wheel> wheels;
    private final Box3 hitBox = new Box3();
    private final Ray ray = new Ray();
    private boolean std;
    private static final float REFL = 0.7f;

    private final Map<String, Float> cur = new HashMap<>(), want = new HashMap<>();
    private boolean blinking, labelsOn = true;
    private final float[] HOME0 = { -0.72f, 0.2f };
    private final float[] HOME = { -0.72f, 0.2f };
    private float vAz = -0.72f, vEl = 0.2f, vVaz, vDist = 11;
    private boolean vHoming;
    private float W = 1, H = 1;
    private boolean portrait;
    private long lastTap;
    private boolean dark;
    private float themeK;
    private final Map<String, Integer> tyreBad = new HashMap<>();
    private final Map<String, Integer> tyreSeen = new HashMap<>();
    private final List<String> tyreNew = new ArrayList<>();
    private boolean tyreOff;
    private long tyreAt;
    private Tour tour;
    private Pin pin;
    private float focusK, focusZ, focusL = 0.85f;
    private V3 focusAt;
    private static final long TOUR_HOLD = 3200;
    private static final float TYRE_ZOOM = 0.55f;
    private Runnable tourTimer, doorTimer, topBack, warmT;
    private final Map<String, Integer> doorSeen = new HashMap<>();
    private List<String> doorOpen = new ArrayList<>();
    private Frame doorV;
    private long doorDue, doorEnd, doorAt;
    private boolean doorBoot = true;
    private float[] fitA;
    private static final float[] INTRO = { 1.15f, 0.16f, 0.3f, 1.8f };
    private float introT = 1, introRot = 1;
    private int introWait;
    private final Map<String, Float> lightOn = new HashMap<>();
    private String ambientHex;
    private Lamps LS;
    private String ambArea = "all";
    private boolean musicOn, playing, colorful;
    private final Col hueCol = new Col();
    private float welT = 1;
    private Object lastPw = "\u0000";
    private static final V3 KEY = new V3(-2.2f, 8, 3);
    private boolean shadowDirty;
    private final Val turnU = new Val(new float[4]);
    private static final String[] TURN_FB = { "head", "led", "side", "tail" }, QUADS = { "LF", "RF", "LB", "RB" };
    private Boolean nightData;
    private float finishK = 1;
    private Finish finishFrom;
    private static final float[] SHOW = { -1.38f, 0.1f };
    private boolean showOn, preview;
    private float showK, angK, offY0, remPx = 50;
    /** 挂 D 挡后的视角（设置里能改，默认车后正中、俯视 62° 的车顶视角） */
    private final float[] TOP = { (float) Math.PI, 1.08f };
    /** 设置面板开着、拖的是 D 挡视角的滑块：车转到 D 挡视角给人看 */
    private boolean previewTop;
    private boolean topOn;
    private float topK, topDist = 11;
    private long topLast;
    private int wheelN;
    private static final String[] SIDES = { "L", "R" };
    private static final float DEG = (float) (Math.PI / 180);
    private static final Map<String, String> DOORS = new LinkedHashMap<>(), WINS = new LinkedHashMap<>(), LIDS = new LinkedHashMap<>(), SLIDES = new LinkedHashMap<>(), FLAPS = new LinkedHashMap<>(), OPENS = new LinkedHashMap<>();
    static {
        DOORS.put("doorFL", "body.doors.frontLeft.state"); DOORS.put("doorFR", "body.doors.frontRight.state"); DOORS.put("doorRL", "body.doors.rearLeft.state"); DOORS.put("doorRR", "body.doors.rearRight.state");
        WINS.put("winFL", "body.windows.frontLeft.position"); WINS.put("winFR", "body.windows.frontRight.position"); WINS.put("winRL", "body.windows.rearLeft.position"); WINS.put("winRR", "body.windows.rearRight.position");
        LIDS.put("hood", "body.hood.state"); LIDS.put("trunk", "body.tailgate.state");
        SLIDES.put("sunroof", "body.sunroof.position"); SLIDES.put("sunshade", "body.sunshade.position");
        FLAPS.put("chargePort", "energy.connectorState");
        OPENS.putAll(DOORS); OPENS.putAll(LIDS);
    }
    static final List<String> CUSTOM = CarModel.CUSTOM_LIGHTS;
    static final List<String> LIGHTS = new ArrayList<>(Arrays.asList("head", "led", "side", "tail", "brake", "reverse", "turnL", "turnR", "fogF", "fogR", "ambient"));
    static { LIGHTS.addAll(CUSTOM); }
    static final List<String> LAMP_PRI = new ArrayList<>(Arrays.asList("turnL", "turnR", "brake", "reverse", "fogR", "fogF", "head"));
    static { LAMP_PRI.addAll(CUSTOM); LAMP_PRI.addAll(Arrays.asList("led", "side", "tail", "ambient")); }
    static final Map<String, String> LIGHT_COLOR = new HashMap<>(), AMBIENT = new HashMap<>(), DOOR_NAME = new HashMap<>(), TYRE_NAME = new HashMap<>(), POWER = new HashMap<>(), ABBR = new HashMap<>();
    static {
        LIGHT_COLOR.put("head", "#fff4e2"); LIGHT_COLOR.put("led", "#ffffff"); LIGHT_COLOR.put("side", "#ffe6b8"); LIGHT_COLOR.put("tail", "#ff2018"); LIGHT_COLOR.put("brake", "#ff0800");
        LIGHT_COLOR.put("reverse", "#ffffff"); LIGHT_COLOR.put("turnL", "#ffa31a"); LIGHT_COLOR.put("turnR", "#ffa31a"); LIGHT_COLOR.put("fogF", "#fff3d0"); LIGHT_COLOR.put("fogR", "#ff2018");
        LIGHT_COLOR.put("ambient", "#7fd6ff");
        for (String r : CUSTOM) LIGHT_COLOR.put(r, "#ff2018");
        AMBIENT.put("iceBlue", "#7fd6ff"); AMBIENT.put("coldWhite", "#e8f2ff"); AMBIENT.put("warmWhite", "#ffd9a0"); AMBIENT.put("redOrange", "#ff6a3d");
        AMBIENT.put("red", "#ff2a2a"); AMBIENT.put("redPurple", "#d63cff"); AMBIENT.put("darkBlue", "#2a5cff");
        DOOR_NAME.put("body.doors.frontLeft.state", "左前门"); DOOR_NAME.put("body.doors.frontRight.state", "右前门"); DOOR_NAME.put("body.doors.rearLeft.state", "左后门"); DOOR_NAME.put("body.doors.rearRight.state", "右后门");
        TYRE_NAME.put("frontLeft", "左前"); TYRE_NAME.put("frontRight", "右前"); TYRE_NAME.put("rearLeft", "左后"); TYRE_NAME.put("rearRight", "右后");
        POWER.put("off", "熄火"); POWER.put("acc", "ACC"); POWER.put("on", "已上电"); POWER.put("ready", "READY"); POWER.put("fakeReady", "READY");
        ABBR.put("frontLeft", "FL"); ABBR.put("frontRight", "FR"); ABBR.put("rearLeft", "RL"); ABBR.put("rearRight", "RR");
    }
    static final String[] TYRES = { "frontLeft", "frontRight", "rearLeft", "rearRight" };
    private CarModel.Rec rec;
    private JSONObject cfg;
    private float dimsHw = 1.02f, dimsH = 1.26f, dimsHl = 2.3f, wheelR = 0.345f, envK = 1, expK = 1;
    private final V3 aim = new V3();
    private Map<String, Object> carNow = new HashMap<>();
    private Map<String, Object> sysNow = new HashMap<>();
    private final Map<String, Long> blinkAt = new HashMap<>(), offAt = new HashMap<>();
    private final Map<String, Boolean> turnOn = new HashMap<>(), bulb = new HashMap<>();
    private boolean turnHz, pulsed, brakeFlash;
    private String applyKey = "";
    private Obj3D applyRoot;
    private long touchAt, movedAt;
    private boolean still;
    private List<St> stList = new ArrayList<>();
    private boolean stOpen;
    private Boolean roofWant;
    private Runnable roofTimer;
    private float tagMax = -1;
    private boolean bgImgTried;

    /** 一个会动的部件：绕铰链转（pivot + axis + angle）、往后滑（slide）、车窗降（meshes + y0 + drop） */
    static final class Part {
        Obj3D pivot; V3 axis, slide, base, size; float angle, drop; float side; List<Mesh> meshes = new ArrayList<>(); float[] y0;
    }
    static final class Wheel { Mesh ring; float side; V3 c, at; int idx; }
    static final class Tour { String w; V3 at; float zoom; long until; float[] view; }
    static final class Pin { List<String> tyres, parts; Frame v; }
    static final class Frame { List<String> keys; V3 at; float want, zoom; float[] view; }
    static final class Finish { Col paint; float pm, pr, pc, pcr, pi, pf, pp; List<Object[]> rim = new ArrayList<>(); List<Col> cal = new ArrayList<>(); }
    /** 这台车的材质 */
    static final class M {
        Mat paint, glass, head, tail;
        List<Mat> rims = new ArrayList<>(), cals = new ArrayList<>(), glassSrc = new ArrayList<>();
        boolean lens, hasPaint, keepMap;
        final Map<String, String> fx = new HashMap<>();
        final Val flake = new Val(0f), peel = new Val(0f);
        float fb;
        Col origColor; Tex origMap; float origMetal, origRough, origCoat;
        List<Object[]> rim0 = new ArrayList<>(); List<Col> cal0 = new ArrayList<>();
        final Map<Mesh, Boolean> notWrap = new IdentityHashMap<>();
    }

    CarPage(android.content.Context c, Settings s, Out out) {
        ctx = c; S = s; this.out = out;
        prefs = c.getSharedPreferences("d3", android.content.Context.MODE_PRIVATE);
        dark = initialDark();
        themeK = dark ? 1 : 0;
        for (String sd : SIDES) { blinkAt.put(sd, 0L); offAt.put(sd, 0L); turnOn.put(sd, false); bulb.put(sd, false); }
        homeView(); vAz = HOME[0]; vEl = HOME[1];
    }
    /** 一打开就用对深浅色（同 index.html 里的小脚本）：设置里选了就按设置；跟随车机时用上次车机报的；没有按钟点 19～6 点深色 */
    private boolean initialDark() {
        String t = S.str("theme");
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        String v = prefs.getString("carDark", null);
        if ("1".equals(v)) return true;
        if ("0".equals(v)) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6;
    }
    boolean isDark() { return dark; }
    private static V3 V(double x, double y, double z) { return new V3(x, y, z); }
    private static long now() { return System.currentTimeMillis(); }
    private float c(String k) { Float v = cur.get(k); return v == null ? 0 : v; }

    // ================= 场景 =================
    @Override public void load(Stage a) {
        app = a;
        final int g = ++gen;
        out.loading("正在载入 3D 车模型");
        scene = new Scene();
        camera = Camera.perspective(27, 1, 0.1f, 80);
        camera.layers |= 2; // 灯的点亮层、路面光束放在图层 1
        keyLight = new DirLight(0xffffff, 1.3f);
        keyLight.position.copy(KEY).normalize().multiplyScalar(12);
        Settings.Qual fx = S.fx();
        if (fx.shadow > 0) {
            keyLight.castShadow = true;
            keyLight.mapSize = fx.shadow;
            keyLight.bias = -0.0004f; keyLight.normalBias = 0.025f;
        }
        app.renderer.shadowsEnabled = fx.shadow > 0;
        scene.add(keyLight);
        hitBox.set(V(-1.1, 0, -2.4), V(1.1, 1.3, 2.4));
        cur.clear(); want.clear(); vAz = HOME[0]; vEl = HOME[1]; vVaz = 0; vHoming = false;
        doorSeen.clear(); doorOpen = new ArrayList<>(); doorV = null; doorDue = doorEnd = 0; doorBoot = true; pin = null; tyreOff = false;
        lastPw = "\u0000"; welT = 1;
        background();
        apply(carNow);
        sysIcons();
        openAt = now(); opening = true;
        com.x3card.car3d.gl.Prog.compiled = com.x3card.car3d.gl.Prog.loaded = 0; com.x3card.car3d.gl.Prog.compileMs = 0;
        try { rec = CarModel.current(ctx); }
        catch (Exception e) { Log.w("car3d", "车模读不出来", e); out.loading("3D 车模型加载失败"); return; }
        try { parse(g, null); }
        catch (Exception e) {
            Log.w("car3d", "车模建不起来", e);
            if (rec.builtin) { out.loading("3D 车模型加载失败"); return; }
            try { rec = CarModel.def(ctx); rec.broken = true; parse(g, null); } catch (Exception e2) { Log.w("car3d", "默认车模也建不起来", e2); out.loading("3D 车模型加载失败"); }
        }
    }
    /** 解析车模、按配置建车（c 不给就用车模自带的配置；没有就是 W70 套内置配置） */
    private void parse(int g, JSONObject c) throws Exception { parse(g, c, null); }
    private void parse(int g, JSONObject c, Gltf.Progress pr) throws Exception {
        String gk = GeoCache.key(ctx, rec);
        Gltf gl = gltf = new Gltf(rec.buf, pr, GeoCache.load(ctx, gk));
        geoKey = gl.fromCache() ? null : gk; // 没用上缓存：画出车以后存一份
        Obj3D model = gl.scene;
        CarModel.ids(model);
        cfg = c != null ? c : rec.cfg != null ? rec.cfg : CarModel.w70();
        if (rec.cfg == null && !CarModel.isW70(model)) throw new IllegalStateException("车模没有配置");
        packPlates = cfg.optJSONObject("plates");
        plateKey = Plates.modelKey(ctx, rec);
        JSONObject mp = Plates.merge(packPlates, Plates.load(ctx), plateKey);
        if (mp != null) cfg.put("plates", mp);
        if (pr != null) step("建车", 0.37f);
        build(model, cfg);
        prefetchTex(root);
        finishParse(g, c);
    }
    private void finishParse(int g, JSONObject c) {
        if (S.fx().lowp && std) lowp(root);
        out.loading(null);
        themeK = dark ? 1 : 0;
        look();
        if (c == null) { introT = S.bool("intro") ? 0 : 1; introRot = 1; introWait = 2; }
        warmLamps(g);
        apply(carNow);
        modelUI();
        reportPlates();
        if (W > 1) resize(W, H, portrait);
    }
    /** 车上看得见的零件用到的贴图交给后台线程先解码（画第一帧时就不用一张张在画图线程上解了） */
    private static void prefetchTex(Obj3D r) {
        final List<Tex> ts = new ArrayList<>();
        final Map<Mat, Boolean> seen = new IdentityHashMap<>();
        r.traverse(o -> {
            if (!(o instanceof Mesh) || !inScene(o)) return; // 藏着的不管（解好了不用，像素一直占着内存）
            Mat m = ((Mesh) o).material;
            if (m == null || seen.put(m, true) != null) return;
            Tex[] a = { m.map, m.aoMap, m.emissiveMap, m.normalMap, m.bumpMap, m.roughnessMap, m.metalnessMap, m.alphaMap, m.lightMap, m.clearcoatMap, m.clearcoatRoughnessMap,
                m.clearcoatNormalMap, m.specularIntensityMap, m.specularColorMap, m.sheenColorMap, m.sheenRoughnessMap, m.transmissionMap, m.thicknessMap, m.iridescenceMap, m.iridescenceThicknessMap };
            for (Tex t : a) if (t != null) ts.add(t);
        });
        Tex.prefetch(ts);
    }
    /** 换了车模（导入、恢复默认）：重新取车模、重建 */
    void reload() { reload(false); }
    /** imp：刚导入的车模包——导入框接着走进度（读取、解析、建车、第一次画：贴图解码上传和着色器编译都在第一帧里），画出来了才收起 */
    void reload(boolean imp) {
        if (scene == null) { if (imp) out.imported("车模包导入好了"); return; }
        int g = ++gen;
        long t0 = now();
        if (imp) { impF = 0.16f; step("读取车模", 0.17f); }
        dropModel();
        cur.clear();
        out.loading("正在载入 3D 车模型");
        rec = CarModel.current(ctx);
        long t1 = now();
        String fail = null;
        try { parse(g, null, imp ? f -> step("解析车模", 0.18f + 0.18f * f) : null); }
        catch (Exception e) {
            Log.w("car3d", "车模建不起来", e);
            if (imp) { CarModel.reset(ctx); fail = "这个车模显示不了，换回默认车模了"; }
            try { rec = CarModel.def(ctx); rec.broken = true; parse(g, null); } catch (Exception e2) { out.loading("3D 车模型加载失败"); }
        }
        if (imp) {
            if (fail != null) out.imported(fail);
            else {
                Log.w("car3d", "导入：读车模 " + (t1 - t0) + " 毫秒，解析和建车 " + (now() - t1) + " 毫秒");
                impName = cfg != null ? cfg.optString("name", "导入的车模") : "导入的车模";
                impAt = now();
                step("准备贴图和着色器", 0.4f);
                app.renderer.progress = f -> step("准备贴图和着色器", 0.4f + 0.59f * f);
                importing = true;
            }
        }
        app.invalidate();
    }
    private boolean importing;
    private String impName, impS;
    private float impF;
    private long impAt;
    private void step(String s, float f) {
        if (f < impF + 0.01f && s.equals(impS)) return; // 每 1% 才报一次
        impF = Math.max(impF, f); impS = s;
        out.progress(s, impF);
    }
    /** 导入的车第一次画完了 */
    private void importDone() {
        if (!importing) return;
        importing = false;
        app.renderer.progress = null;
        Log.w("car3d", "导入：第一次画 " + (now() - impAt) + " 毫秒");
        out.imported("车模包导入好了：" + impName + (rec != null && rec.fmt > CarModel.FMT ? "。这个车模包是用更新的网页版做的，有些设置这个版本的应用还显示不了，请更新应用" : ""));
    }
    private void freeRoot(Obj3D r) {
        scene.remove(r);
        if (r.get("lamps") != null) ((Lamps) r.get("lamps")).dispose();
        if (r.get("origMap") != null) ((Tex) r.get("origMap")).dispose();
        if (r.get("refl") != null) { ((Mirror) r.get("refl")).dispose(); if (mirror == r.get("refl")) mirror = null; }
        app.dispose(r);
    }
    private void dropModel() {
        if (root != null) freeRoot(root);
        root = modelObj = null; Mt = null; parts = null; wheels = null; LS = null; plates = null; chg = null;
        wheelN = 0;
    }
    @Override public void unload() {
        gen++;
        importDone();
        tour = null; pin = null;
        app.cancel(tourTimer); app.cancel(warmT); app.cancel(doorTimer); app.cancel(topBack);
        doorV = null; doorDue = doorEnd = 0;
        musicOn = playing = false;
        if (scene != null) app.dispose(scene);
        if (LS != null) LS.dispose();
        if (Mt != null && Mt.origMap != null) Mt.origMap.dispose();
        if (mirror != null) { mirror.dispose(); mirror = null; }
        if (keyLight != null) keyLight.disposeShadow();
        envRoom = null; std = false;
        app.renderer.toneMapping = Programs.ACES;
        scene = null; camera = null; root = null; Mt = null; parts = null; wheels = null; modelObj = null; LS = null; plates = null; pdrag = null; chg = null; camK = 0; chgOn = chgOff = false;
        wheelN = 0; rec = null;
        topOn = false; topK = 0; showK = angK = 0; finishK = 1; preview = false;
        out.loading(null);
    }

    /** 车的外框（只算看得见的网格） */
    private static Box3 visBox(Obj3D model) {
        Box3 b = new Box3();
        model.traverse(o -> { if (o instanceof Mesh && o.visible) b.union(CarModel.box((Mesh) o)); });
        return b;
    }
    private CarModel.D3 d3;
    private Gltf gltf;
    /** 画出车以后要存一份解好的几何（GeoCache） */
    private String geoKey;
    /**
     * 下面逐个网格换材质那一遍之前，先把要补法线、要理顺绕向的几何体挑出来：法线照原来的顺序补；理顺绕向（大车模要几百毫秒）各几何体互不相干，
     * 分到几个处理器核上同时做。挑法和那一遍一样：材质是双面的，或者前面已经因为平面着色被改成双面了。共用一块索引的几何体放在同一个线程里按原来的顺序做
     */
    private static void windAll(Obj3D model, List<Geo> normalsDone) {
        final Map<Mat, Boolean> flat = new IdentityHashMap<>();
        final Map<Geo, Boolean> seen = new IdentityHashMap<>();
        final Map<Object, List<Geo>> groups = new LinkedHashMap<>();
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            Geo g = m.geometry;
            Mat mt = m.material;
            if (g != null && g.attr("position") != null && g.attr("normal") == null && !normalsDone.contains(g)) { g.computeVertexNormals(); normalsDone.add(g); }
            if (g != null && (mt.side == Mat.DOUBLE || flat.containsKey(mt)) && seen.put(g, true) == null) {
                Object k = g.index != null ? g.index.buf : g;
                List<Geo> l = groups.get(k);
                if (l == null) groups.put(k, l = new ArrayList<>());
                l.add(g);
            }
            if (mt.flatShading) flat.put(mt, true);
        });
        try { com.x3card.car3d.gl.Work.all(new ArrayList<>(groups.values()), l -> { for (Geo g : l) CarModel.wind(g); }); }
        catch (Exception e) { Log.w("car3d", "理顺绕向出错", e); }
    }
    private List<Mesh> mrefs(Object r) { return CarModel.meshRefs(d3, r); }
    private List<Mat> matsOf(String k) { JSONObject m = cfg.optJSONObject("mats"); return CarModel.matRefs(d3, m == null ? null : m.opt(k)); }
    private JSONArray pickOf(String r) { JSONObject p = cfg.optJSONObject("pick"); return p == null ? null : p.optJSONArray(r); }

    private void build(Obj3D model, JSONObject c) {
        root = new Obj3D();
        scene.add(root);
        modelObj = model;
        d3 = (CarModel.D3) model.get("d3");
        JSONObject pc = c.optJSONObject("parts");
        if (pc == null) pc = new JSONObject();
        List<Mesh> hidden = mrefs(c.opt("hide"));
        for (Mesh m : hidden) m.visible = false;
        // ---- 摆正：先竖过来，再转车头；缩放，水平居中、贴地；车头朝 +z，左边是 +x ----
        model.rotate((float) c.optDouble("rotX", 0) * DEG, (float) c.optDouble("rotY", 0) * DEG, (float) c.optDouble("rotZ", 0) * DEG, "YXZ");
        root.add(model);
        model.updateMatrixWorld(true);
        Box3 bb = visBox(model);
        if (bb.isEmpty()) { for (Mesh m : hidden) m.visible = true; bb = visBox(model); }
        if (bb.isEmpty()) throw new IllegalStateException("车模里没有能显示的网格");
        float sc = c.has("scale") ? (float) c.optDouble("scale") : c.has("length") ? (float) (c.optDouble("length") / Math.max(1e-6, bb.max.z - bb.min.z)) : 1;
        model.scale.setScalar(sc);
        model.updateMatrixWorld(true);
        bb = visBox(model);
        model.position.set(-(bb.min.x + bb.max.x) / 2, -bb.min.y, -(bb.min.z + bb.max.z) / 2);
        final V3 off = model.position.clone();
        root.updateMatrixWorld(true);
        bb = visBox(model);
        JSONArray box = c.optJSONArray("box");
        if (box != null) { dimsHw = (float) box.optDouble(0); dimsH = (float) box.optDouble(1); dimsHl = (float) box.optDouble(2); }
        else { dimsHw = Math.max(-bb.min.x, bb.max.x); dimsH = bb.max.y; dimsHl = Math.max(-bb.min.z, bb.max.z); }
        hitBox.set(V(-dimsHw - 0.08, 0, -dimsHl - 0.1), V(dimsHw + 0.08, dimsH + 0.04, dimsHl + 0.1));
        final boolean modelCoords = "model".equals(c.optString("coords"));
        Pos P = a -> modelCoords ? V(a.optDouble(0), a.optDouble(1), a.optDouble(2)).add(off) : V(a.optDouble(0), a.optDouble(1), a.optDouble(2));
        envK = (float) c.optDouble("env", 1) * REFL; expK = (float) c.optDouble("exposure", 1);
        std = !CarModel.isW70(model);
        if (std && envRoom == null) envRoom = app.env("neutral");
        if (!std && envRT == null) envRT = app.env("studio");
        scene.environment = std ? envRoom : envRT;
        app.renderer.toneMapping = std ? Programs.CUSTOM_TONE : Programs.ACES;

        // ---- 材质 ----
        M M = Mt = new M();
        M.paint = Mat.physical();
        M.paint.color.setHex(0x9a0c13); M.paint.metalness = 0.3f; M.paint.roughness = 0.34f; M.paint.clearcoat = 1; M.paint.clearcoatRoughness = 0.03f;
        M.paint.aoMapIntensity = (float) c.optDouble("paintAo", 0.6);
        M.paint.iridescenceIOR = 1.8f; M.paint.iridescenceThickMin = 250; M.paint.iridescenceThickMax = 900;
        M.glass = Mat.standard(); M.glass.color.setHex(0x0a0e13); M.glass.metalness = 0; M.glass.roughness = 0.03f; M.glass.transparent = true; M.glass.opacity = 0.78f; M.glass.envMapIntensity = 2 * REFL;
        M.head = Mat.standard(); M.head.color.setHex(0xffffff); M.head.metalness = 0; M.head.roughness = 0.02f; M.head.transparent = true; M.head.opacity = 0.22f;
        M.tail = Mat.standard(); M.tail.color.setHex(0x4a0606); M.tail.metalness = 0; M.tail.roughness = 0.08f; M.tail.transparent = true; M.tail.opacity = 0.7f;
        M.rims.addAll(matsOf("rim")); M.cals.addAll(matsOf("caliper")); M.lens = c.optBoolean("lens");
        paintFx(M.paint);
        final List<Mat> paintSet = matsOf("paint"), glassSet = c.optBoolean("keepGlass") ? new ArrayList<>() : matsOf("glass");
        final Map<String, List<Mat>> lampSets = new HashMap<>();
        final Map<String, List<Ent>> ents = new HashMap<>();
        final Map<Mesh, String> picked = new IdentityHashMap<>();
        final Map<String, Mat> copies = new HashMap<>();
        M.glassSrc.addAll(matsOf("glass"));
        for (String r : LIGHTS) { lampSets.put(r, matsOf(r)); ents.put(r, new ArrayList<>()); }
        lightFx();
        final Map<Mesh, List<String>> pickedL = new IdentityHashMap<>();
        for (String r : CarModel.BODY_ROLES) for (Mesh m : mrefs(pickOf(r))) if (!picked.containsKey(m)) picked.put(m, r);
        for (String r : LIGHTS) for (Mesh m : mrefs(pickOf(r))) if (!picked.containsKey(m)) { List<String> l = pickedL.get(m); if (l == null) pickedL.put(m, l = new ArrayList<>()); l.add(r); }
        M.glassSrc.addAll(pickedAs(model, picked, "glass"));
        List<Mat> pp = pickedAs(model, picked, "paint");
        M.hasPaint = !paintSet.isEmpty() || !pp.isEmpty();
        List<Mat> srcs = new ArrayList<>(paintSet); srcs.addAll(pp);
        Mat src = srcs.isEmpty() ? null : srcs.get(0);
        List<Mat> rimKeep = new ArrayList<>(M.rims); rimKeep.addAll(pickedAs(model, picked, "rim"));
        CarModel.metalFix(d3, rimKeep);
        final List<Mesh> paintMeshes = new ArrayList<>();
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            if ("paint".equals(picked.get(m)) || (!picked.containsKey(m) && paintSet.contains(m.material))) paintMeshes.add(m);
        });
        boolean uvAll = true;
        for (Mesh m : paintMeshes) if (m.geometry.attr("uv") == null) uvAll = false;
        if (!paintSet.isEmpty() && src != null) {
            List<String> keep = new ArrayList<>();
            JSONArray pk = c.optJSONArray("paintKeep");
            if (pk != null) for (int i = 0; i < pk.length(); i++) keep.add(pk.optString(i));
            else { keep.add("aoMap"); keep.add("normalMap"); if (c.optBoolean("keepPaintMap")) keep.add("map"); }
            for (String k : keep) if (shared(srcs, src, k, uvAll)) setMap(M.paint, k, mapOf(src, k));
            if (M.paint.normalMap != null) { M.paint.normalScaleX = src.normalScaleX; M.paint.normalScaleY = src.normalScaleY; }
            if (M.paint.aoMap != null) for (Mesh m : paintMeshes) if (m.geometry.attr("uv2") == null && m.geometry.attr("uv") != null) m.geometry.set("uv2", m.geometry.attr("uv"));
        }
        Col oc = src != null ? src.color.clone() : null;
        Tex omap = src != null && shared(srcs, src, "map", uvAll) ? src.map : null;
        Col oavg = src != null && src.map != null && omap == null ? texAvg(src.map) : null;
        if (oavg != null) oc.multiply(oavg);
        if (oc != null) { M.origColor = oc; M.origMap = omap; M.origMetal = src.metalness; M.origRough = src.roughness; M.origCoat = src.clearcoat; }
        JSONArray pkk = c.optJSONArray("paintKeep");
        boolean hasMapKeep = false;
        if (pkk != null) for (int i = 0; i < pkk.length(); i++) if ("map".equals(pkk.optString(i))) hasMapKeep = true;
        M.keepMap = c.optBoolean("keepPaintMap") || hasMapKeep;
        if (M.origColor != null) {
            Col shown = M.origColor.clone();
            Col avg = M.origMap != null ? texAvg(M.origMap) : null;
            if (avg != null) shown.multiply(avg);
            S.modelPaint("#" + M.origColor.getHexString(), "#" + shown.getHexString(), M.origMetal, M.origRough, M.origCoat);
        } else S.modelPaint(null, null, 0, 0, 0);
        boolean twoP = false, twoG = false;
        List<Mat> pl = new ArrayList<>(paintSet); pl.addAll(pp);
        for (Mat m : pl) if (m.side == Mat.DOUBLE || m.flatShading) twoP = true;
        List<Mat> gl = new ArrayList<>(glassSet); gl.addAll(pickedAs(model, picked, "glass"));
        for (Mat m : gl) if (m.side == Mat.DOUBLE || m.flatShading) twoG = true;
        if (twoP) M.paint.side = Mat.DOUBLE;
        if (twoG) M.glass.side = Mat.DOUBLE;
        // 氛围灯分前排 / 后排
        Box3 zf = new Box3(), zr = new Box3();
        for (String k : new String[] { "doorFL", "doorFR" }) { JSONObject p = pc.optJSONObject(k); for (Mesh m : mrefs(p == null ? null : p.opt("meshes"))) zf.union(CarModel.box(m)); }
        for (String k : new String[] { "doorRL", "doorRR" }) { JSONObject p = pc.optJSONObject(k); for (Mesh m : mrefs(p == null ? null : p.opt("meshes"))) zr.union(CarModel.box(m)); }
        final Float zSplit = !zf.isEmpty() && !zr.isEmpty() ? (zf.min.z + zr.max.z) / 2 : null;
        final Map<Mat, Boolean> swap = new IdentityHashMap<>();
        final List<Geo> normalsDone = new ArrayList<>();
        windAll(model, normalsDone);
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mesh m = (Mesh) o;
            m.put("car", 1);
            Geo g = m.geometry;
            if (g != null && g.attr("position") != null && g.attr("normal") == null && !normalsDone.contains(g)) { g.computeVertexNormals(); normalsDone.add(g); }
            if (g != null && m.material.side == Mat.DOUBLE) CarModel.wind(g);
            Mat mt = m.material;
            if (mt.flatShading) mt.side = Mat.DOUBLE;
            String pr = picked.get(m);
            List<String> plr = pr != null ? null : pickedL.get(m);
            if (plr != null) for (String lr : plr) lampOf(ents, m, lr, zSplit);
            Mat r = pr != null ? asRole(pr, mt, m, copies, ents, zSplit) : byMat(mt, m, paintSet, glassSet, lampSets, ents, plr, zSplit);
            if (r == mt) fix(mt);
            if (r != mt) swap.put(mt, true);
            m.material = r;
        });
        final Map<Mat, Boolean> inUse = new IdentityHashMap<>();
        model.traverse(o -> { if (o instanceof Mesh) inUse.put(((Mesh) o).material, true); });
        for (Mat m : swap.keySet()) if (!inUse.containsKey(m)) m.dispose();
        for (Mat m : M.rims) M.rim0.add(new Object[] { m.color.clone(), m.metalness, m.roughness });
        for (Mat m : M.cals) M.cal0.add(m.color.clone());
        S.modelPart("rim", M.rims.isEmpty() ? null : shownOf(M.rims.get(0)));
        S.modelPart("caliper", M.cals.isEmpty() ? null : shownOf(M.cals.get(0)));
        finish(false);

        // ---- 能动的部件 ----
        parts = new LinkedHashMap<>();
        Map<String, List<Mesh>> wins = new LinkedHashMap<>();
        if (c.has("winSplit")) {
            List<Mesh> wg = mrefs(new JSONArray().put(c.optString("winSplit")));
            if (!wg.isEmpty()) { Mesh[] sp = splitX(wg.get(0)); wins.put("winFL", new ArrayList<>(Arrays.asList(sp[0]))); wins.put("winFR", new ArrayList<>(Arrays.asList(sp[1]))); }
        }
        for (String k : WINS.keySet()) {
            JSONObject p = pc.optJSONObject(k);
            List<Mesh> l = new ArrayList<>();
            for (Mesh m : mrefs(p == null ? null : p.opt("meshes"))) if (seen(m)) l.add(m);
            if (!l.isEmpty()) { List<Mesh> w = wins.get(k); if (w == null) wins.put(k, w = new ArrayList<>()); w.addAll(l); }
        }
        List<Mesh> inWin = new ArrayList<>();
        for (List<Mesh> l : wins.values()) inWin.addAll(l);
        List<String> doorish = new ArrayList<>(DOORS.keySet()); doorish.addAll(LIDS.keySet()); doorish.addAll(FLAPS.keySet());
        for (String k : doorish) {
            JSONObject p = pc.optJSONObject(k);
            List<Mesh> ms = new ArrayList<>();
            if (p != null) for (Mesh m : mrefs(p.opt("meshes"))) if (seen(m) && !inWin.contains(m)) ms.add(m);
            if (!ms.isEmpty()) parts.put(k, door(k, ms, p, P));
        }
        for (String k : SLIDES.keySet()) {
            JSONObject p = pc.optJSONObject(k);
            List<Mesh> ms = new ArrayList<>();
            if (p != null) for (Mesh m : mrefs(p.opt("meshes"))) if (seen(m)) ms.add(m);
            if (!ms.isEmpty()) parts.put(k, slider(ms, p));
        }
        for (Map.Entry<String, List<Mesh>> e : wins.entrySet()) {
            Part dp = parts.get("door" + e.getKey().substring(3));
            List<Mesh> list = e.getValue();
            Box3 b = new Box3();
            for (Mesh m : list) b.expandByObject(m);
            Obj3D host = dp != null ? dp.pivot : new Obj3D();
            if (dp == null) root.add(host);
            for (Mesh m : list) { host.attach(m); m.put("d3move", 1); }
            Part w = new Part();
            w.meshes = list;
            w.y0 = new float[list.size()];
            for (int i = 0; i < list.size(); i++) w.y0[i] = list.get(i).position.y;
            w.drop = c.has("winDrop") ? (float) c.optDouble("winDrop") : Math.min(0.3f, (b.max.y - b.min.y) * 0.85f);
            parts.put(e.getKey(), w);
        }

        // ---- 车轮按四个角分组 ----
        Map<String, List<Mesh>> groups = new LinkedHashMap<>();
        Map<String, V3> wp = new HashMap<>();
        Map<String, Float> outW = new HashMap<>();
        List<Float> rs = new ArrayList<>();
        Map<String, String> Q = new HashMap<>();
        Q.put("LF", "frontLeft"); Q.put("RF", "frontRight"); Q.put("LB", "rearLeft"); Q.put("RB", "rearRight");
        for (Mesh m : mrefs(pc.opt("wheels"))) M.notWrap.put(m, true);
        for (String r : LIGHTS) for (Ent e : ents.get(r)) M.notWrap.put(e.o, true);
        for (Mesh m : mrefs(pc.opt("wheels"))) {
            if (!seen(m)) continue;
            if (M.cals.contains(m.material)) continue;
            Box3 wb = new Box3().expandByObject(m);
            V3 cc = wb.getCenter(new V3());
            String q = (cc.x > 0 ? "L" : "R") + (cc.z > 0 ? "F" : "B");
            if ((wb.min.x < -0.1 * dimsHw && wb.max.x > 0.1 * dimsHw) || (wb.min.z < -0.1 * dimsHl && wb.max.z > 0.1 * dimsHl)) continue;
            List<Mesh> l = groups.get(q);
            if (l == null) groups.put(q, l = new ArrayList<>());
            l.add(m);
        }
        for (Map.Entry<String, List<Mesh>> e : groups.entrySet()) {
            Box3 b = new Box3();
            for (Mesh m : e.getValue()) b.expandByObject(m);
            V3 cc = b.getCenter(new V3());
            wheelN++;
            wp.put(Q.get(e.getKey()), V(cc.x, 0, cc.z)); outW.put(Q.get(e.getKey()), (b.max.x - b.min.x) / 2); rs.add((b.max.y - b.min.y) / 2);
        }
        if (c.has("wheelR")) wheelR = (float) c.optDouble("wheelR");
        else if (!rs.isEmpty()) { float s = 0; for (float r : rs) s += r; wheelR = s / rs.size(); }
        else wheelR = dimsH * 0.27f;
        Map<String, V3> WP = new HashMap<>();
        JSONObject cwp = c.optJSONObject("wheelPos");
        for (String w : TYRES) {
            int f = w.startsWith("front") ? 1 : -1, l = w.contains("Left") ? 1 : -1;
            WP.put(w, cwp != null && cwp.optJSONArray(w) != null ? P.of(cwp.optJSONArray(w)) : wp.containsKey(w) ? wp.get(w) : V(l * dimsHw * 0.8, 0, f * dimsHl * 0.55));
        }

        // ---- 胎压异常时地上一圈光 ----
        wheels = new LinkedHashMap<>();
        Tex ringTex = app.canvasTex(128, 128, (cv, w, h) -> {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w / 2f, w / 2f, w / 2f, new int[] { 0x00ffffff, 0x00ffffff, 0xe6ffffff, 0x00ffffff }, new float[] { 0, 0.5f, 0.72f, 1 }, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }, true);
        float ringS = 1.3f * wheelR / 0.345f;
        int wi = 0;
        for (String w : TYRES) {
            Mat rm = Mat.basic();
            rm.map = ringTex; rm.color.setHex(0xff8a1f); rm.transparent = true; rm.depthWrite = false; rm.opacity = 0;
            Mesh ring = new Mesh(Shapes.plane(ringS, ringS), rm);
            ring.rotate((float) (-Math.PI / 2), 0, 0); ring.position.copy(WP.get(w)).setY(0.004f); ring.renderOrder = 1; ring.visible = false;
            root.add(ring);
            float side = WP.get(w).x > 0 ? 1 : -1;
            float lo = c.has("labelOut") ? (float) c.optDouble("labelOut") : outW.containsKey(w) ? outW.get(w) + 0.3f : 0.42f;
            Wheel wh = new Wheel();
            wh.ring = ring; wh.side = side; wh.c = V(WP.get(w).x, wheelR, WP.get(w).z); wh.at = WP.get(w).clone().add(V(side * lo, 0, 0)); wh.idx = wi++;
            wheels.put(w, wh);
        }

        // ---- 接触阴影、真实阴影 ----
        root.add(shadow(WP));
        final Settings.Qual fx = S.fx();
        model.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mat mt = ((Mesh) o).material;
            o.castShadow = fx.shadow > 0 && !(mt.transparent && mt.opacity < 0.9f);
            o.receiveShadow = fx.shadow > 0;
        });
        if (fx.shadow > 0) {
            float L = Math.max(dimsHl, dimsHw) + 0.8f, SR = L * 1.6f;
            Camera s = keyLight.shadowCamera;
            s.left = s.bottom = -SR; s.right = s.top = SR; s.near = 2; s.far = 24; s.updateProjectionMatrix();
            Mat sm = new Mat(Mat.SHADOW);
            sm.opacity = 0.32f; sm.depthWrite = false;
            Mesh catcher = new Mesh(Shapes.plane(L * 2, L * 2), sm);
            catcher.rotate((float) (-Math.PI / 2), 0, 0); catcher.position.y = 0.001f; catcher.receiveShadow = true; catcher.renderOrder = -2;
            root.add(catcher);
            shadowDirty = true;
        }
        // ---- 充电桩、充电枪、地上的光环（插着枪才显示） ----
        V3[] spot = chargeSpot(pc);
        chg = new Charger(app, spot[0], spot[1], dimsHw, dimsHl, dimsH, REFL * envK, fx.shadow > 0);
        root.add(chg.root);
        if (M.origMap != null) root.put("origMap", M.origMap);
        if (fx.reflect) { mirror = new Mirror(dimsHl, dimsHw, app.bufW(), app.bufH(), app.aa); root.add(mirror.mesh); root.put("refl", mirror); }
        LS = new Lamps(ents);
        root.put("lamps", LS);
        plates = null; // 上一台车的车牌跟着旧的车一起释放了
        if (c.optJSONObject("plates") != null) plates(c.optJSONObject("plates"));
        JSONArray wl = c.optJSONArray("wraps");
        if (wl != null && wl.length() > 0) wraps(wl, M);
    }

    // ================= 贴纸（WrapArt 画贴图、WrapCut 投影贴上；装饰，跟车况不联动） =================
    /** 建车时一次贴好（贴纸在网页版里调，原生版只显示） */
    private void wraps(JSONArray list, final M M0) {
        final Obj3D R0 = root;
        final WrapArt.Dims d0 = new WrapArt.Dims(dimsHw, dimsH, dimsHl);
        final boolean sh = S.fx().shadow > 0;
        final float env0 = envK;
        WrapCut cut = new WrapCut(new WrapCut.Ctx() {
            @Override public Obj3D root() { return R0; }
            @Override public WrapArt.Dims dims() { return d0; }
            @Override public com.x3card.car3d.gl.Renderer renderer() { return app.renderer; }
            @Override public android.content.Context android() { return ctx; }
            @Override public boolean shadow() { return sh; }
            @Override public float envK() { return env0; }
            @Override public void atRest(Runnable r) { if (parts == null) { r.run(); return; } List<Object[]> keep = restPose(); try { r.run(); } finally { restore(keep); } }
            @Override public List<Mesh> occluders() { return carMeshes(R0); }
            @Override public List<Mesh> targets(String on) {
                boolean body = !"glass".equals(on), glass = !"body".equals(on);
                List<Mesh> out = new ArrayList<>();
                for (Mesh o : carMeshes(R0)) {
                    if (M0.notWrap.containsKey(o)) continue;
                    Mat m = o.material;
                    boolean ok = m == M0.glass || M0.glassSrc.contains(m) ? glass
                        : M0.hasPaint ? body && m == M0.paint
                        : body && !(m.transparent && m.opacity < 0.9f) && !M0.rims.contains(m) && !M0.cals.contains(m); // 没标车漆：玻璃、灯、车轮以外的都算
                    if (ok) out.add(o);
                }
                return out;
            }
            @Override public Obj3D owner(Mesh m) { for (Obj3D o = m; o != null && o != R0; o = o.parent) if (o.has("d3move")) return o; return R0; }
            @Override public ByteBuffer image(int i) { return gltf != null ? gltf.imageBytes(i) : null; }
        });
        long t0 = System.currentTimeMillis();
        cut.apply(list);
        Log.w("car3d", "贴纸：" + list.length() + " 张，" + cut.meshes + " 个网格，" + (System.currentTimeMillis() - t0) + " 毫秒");
    }
    /** 车上的零件（同 carMeshes：车模自己的、显示着的） */
    private static List<Mesh> carMeshes(Obj3D R0) {
        final List<Mesh> l = new ArrayList<>();
        R0.traverse(o -> {
            if (!(o instanceof Mesh) || !o.has("car") || ((Mesh) o).skinned) return;
            for (Obj3D p = o; p != null && p != R0; p = p.parent) if (!p.visible) return;
            l.add((Mesh) o);
        });
        return l;
    }

    // ================= 车牌（Plates 画，这里摆位置；装饰，跟车况不联动）。设置里的"调整车牌"开着时能按住车上的车牌拖 =================
    private Map<String, Obj3D> plates;
    /** 车模包自己的车牌配置（盖上自己改的之前）、这台车在存储里的名字 */
    private JSONObject packPlates;
    private String plateKey = "w70";
    /** 交给界面（GL 线程里调，回调里自己切到主线程）：建好车时合并后的车牌配置和车的尺寸（半宽、高、半长）、自动摆的 / 拖完的位置 */
    interface PlateOut { void plates(JSONObject st, float[] dims, String key); void plateAt(String which, JSONArray at, boolean drag); }
    PlateOut plateOut;
    /** 这次自动摆了位置的几块（要交回界面存起来） */
    private final List<String> autoAt = new ArrayList<>();
    private static boolean inScene(Obj3D o) { for (Obj3D p = o; p != null; p = p.parent) if (!p.visible) return false; return true; }
    /** 按配置建两块车牌（只建开着的），位置落在哪个会动的部件里就跟着它动 */
    private void plates(JSONObject st) {
        plates = new HashMap<>();
        for (String k : Plates.SIDES) plateOne(st, k);
    }
    private void plateOne(JSONObject st, String k) {
        JSONObject p = st.optJSONObject(k);
        if (p == null || !p.optBoolean("on")) return;
        Obj3D m;
        try { m = Plates.mesh(ctx, st, "rear".equals(k)); } catch (Exception e) { Log.w("car3d", "车牌画不出来", e); return; }
        m.put("which", k);
        plates.put(k, m);
        plateXform(m, p);
        root.add(m);
        plateAttach(m);
    }
    private void plateFree(Obj3D m) { m.removeFromParent(); app.dispose(m); }
    /** 车牌配置变了（设置里改的）：只换车牌，不重建车。只动了位置、角度的直接挪；牌种、号、宽度、开关变了的那块重建 */
    void setPlates(JSONObject st) {
        if (root == null || cfg == null || st == null) return;
        if (plates == null) plates = new HashMap<>();
        JSONObject old = cfg.optJSONObject("plates");
        try { cfg.put("plates", st); } catch (Exception ignored) { }
        for (String k : Plates.SIDES) {
            JSONObject p = st.optJSONObject(k);
            Obj3D m = plates.get(k);
            if (p == null || !p.optBoolean("on")) { if (m != null) { plateFree(m); plates.remove(k); } continue; }
            if (m != null && sameFace(old, st, k)) { platePlace(m, p); continue; }
            if (m != null) { plateFree(m); plates.remove(k); }
            plateOne(st, k);
        }
        app.invalidate();
        reportAuto();
    }
    /** 牌面、板子一样（牌种、号、颜色、宽度都没变），只是位置、角度变了 */
    private static boolean sameFace(JSONObject a, JSONObject b, String k) {
        if (a == null) return false;
        for (String f : new String[] { "style", "text", "bg", "fg" }) if (!a.optString(f).equals(b.optString(f))) return false;
        JSONObject x = a.optJSONObject(k), y = b.optJSONObject(k);
        return x != null && y != null && x.optBoolean("on") && x.optDouble("w", -1) == y.optDouble("w", -1);
    }
    /** 回到车底下重新摆，再看挂在哪个部件上 */
    private void platePlace(Obj3D m, JSONObject p) {
        root.attach(m);
        plateXform(m, p);
        plateAttach(m);
    }
    /** 回到前台：另一个界面（全屏页 / 小窗口）可能改过存着的车牌 */
    void platesFromStore() {
        if (root == null || cfg == null) return;
        JSONObject m = Plates.merge(packPlates, Plates.load(ctx), plateKey);
        JSONObject a = CarModel.cleanPlates(m), b = CarModel.cleanPlates(cfg.optJSONObject("plates")); // 整理成同样的写法再比（键的先后不算变了）
        if (m == null || a != null && b != null && a.toString().equals(b.toString())) return;
        setPlates(m);
        reportPlates();
    }
    private void reportPlates() {
        autoAt.clear();
        final PlateOut po = plateOut;
        JSONObject st = cfg != null ? cfg.optJSONObject("plates") : null;
        if (po == null || st == null) return;
        final String sj = st.toString(), key = plateKey;
        final float[] d = { dimsHw, dimsH, dimsHl };
        app.ui(() -> { try { po.plates(new JSONObject(sj), d, key); } catch (Exception ignored) { } });
    }
    private void reportAuto() {
        final PlateOut po = plateOut;
        JSONObject st = cfg.optJSONObject("plates");
        for (String k : autoAt) {
            JSONObject p = st != null ? st.optJSONObject(k) : null;
            JSONArray at = p != null ? p.optJSONArray("at") : null;
            if (po == null || at == null) continue;
            final String kk = k, aj = at.toString();
            app.ui(() -> { try { po.plateAt(kk, new JSONArray(aj), false); } catch (Exception ignored) { } });
        }
        autoAt.clear();
    }
    private static double r3(float v) { return Math.round(v * 1000) / 1000.0; }
    /** 自动摆一个起点（同 plateAuto）：从车外朝车里横着打射线，从低往高扫，找第一个"挂得住"的高度——
        打到的面得朝着车头 / 车尾、而且离那一头够远；横向居中、贴着那个面放 */
    private float[] plateAuto(String which) {
        List<Object[]> keep = parts != null ? restPose() : null; // 尾门、引擎盖正开着（或者还在关）：按关着的样子找
        try { return plateAuto0(which); } finally { if (keep != null) restore(keep); }
    }
    private float[] plateAuto0(String which) {
        int want = "front".equals(which) ? 1 : -1;
        List<Mesh> list = new ArrayList<>();
        root.updateMatrixWorld(true);
        for (Mesh m : d3.meshes) if (m.parent != null && inScene(m) && m.geometry != null) list.add(m);
        // 射线都是沿车长方向打的，只扫车中间一条窄带：横向、高度不在零件外框里的直接跳过；
        // 剩下的零件先把窄带里的三角形挑一遍，40 条射线只看这些（结果一样，大车模上少算很多三角形）
        float far = dimsHl * 1.5f + 1;
        Box3 strip = new Box3(V(-0.16, dimsH * 0.12, -far), V(0.16, dimsH * 0.47, far));
        Box3[] wb = new Box3[list.size()];
        int[][] tris = new int[list.size()][];
        for (int j = 0; j < wb.length; j++) {
            wb[j] = list.get(j).geometry.boundingBox().clone().applyMatrix4(list.get(j).matrixWorld);
            tris[j] = wb[j].intersectsBox(strip) ? Ray.trisIn(list.get(j), strip) : new int[0];
        }
        for (int k = 0; k < 8; k++) {
            float y = dimsH * (0.12f + k * 0.05f);
            V3 hit = null, hn = null;
            for (int i = 0; i < 5; i++) {
                float x = (i - 2) * 0.08f;
                ray.set(V(x, y, want * (dimsHl * 1.5 + 1)), V(0, 0, -want));
                Ray.Hit best = null;
                for (int j = 0; j < wb.length; j++) {
                    Box3 b = wb[j];
                    if (tris[j].length == 0 || x < b.min.x || x > b.max.x || y < b.min.y || y > b.max.y) continue;
                    Ray.Hit h = ray.intersectMesh(list.get(j), tris[j], tris[j].length);
                    if (h != null && (best == null || h.dist < best.dist)) best = h;
                }
                if (best == null || (hit != null && Math.abs(best.point.z) <= Math.abs(hit.z))) continue;
                hit = best.point.clone(); hn = best.normal.clone();
            }
            if (hit != null && Math.abs(hn.z) > 0.6f && Math.abs(hit.z) >= 0.8f * dimsHl) return new float[] { 0, Math.round(y * 1000) / 1000f, hit.z + want * 0.012f };
        }
        return new float[] { 0, dimsH * 0.3f, want * dimsHl * 0.98f }; // 一台都没找到（模型很怪）：按外框兜底
    }
    /** 位置、角度（pitch 上仰、yaw 站在牌子前面看往右转为正）；没摆过的先自动摆（位置记进配置，交回界面） */
    private void plateXform(Obj3D m, JSONObject p) {
        JSONArray at = p.optJSONArray("at");
        float[] a;
        if (at != null && at.length() == 3) a = new float[] { (float) at.optDouble(0), (float) at.optDouble(1), (float) at.optDouble(2) };
        else {
            a = plateAuto((String) m.get("which"));
            try { p.put("at", new JSONArray().put(r3(a[0])).put(r3(a[1])).put(r3(a[2]))); autoAt.add((String) m.get("which")); } catch (Exception ignored) { }
        }
        m.position.set(a[0], a[1], a[2]);
        m.rotate((float) (-p.optDouble("pitch", 0) * DEG), (float) (("front".equals(m.get("which")) ? 0 : Math.PI) + p.optDouble("yaw", 0) * DEG), 0, "YXZ");
    }
    /** 挂到会动的部件上：位置落在哪个部件（门窗关着时的外框）里就跟着它动（后车牌贴尾门，开尾门跟着翻） */
    private void plateAttach(Obj3D m) {
        if (parts == null) return;
        List<Object[]> keep = restPose();
        try {
            for (Part p : parts.values()) {
                if (p.pivot == null || p.meshes == null) continue;
                Box3 b = new Box3();
                for (Mesh mm : p.meshes) b.expandByObject(mm);
                if (b.containsPoint(m.position)) p.pivot.attach(m);
            }
        } finally { restore(keep); }
    }
    /** 门窗盖摆回关着的样子（量外框用），量完 restore */
    private List<Object[]> restPose() {
        List<Object[]> keep = new ArrayList<>();
        for (Part p : parts.values()) {
            if (p.pivot != null) { keep.add(new Object[] { p.pivot, p.pivot.position.clone(), p.pivot.quaternion.clone() }); if (p.slide != null) p.pivot.position.set(0, 0, 0); else p.pivot.quaternion.identity(); }
            else if (p.meshes != null && p.y0 != null) for (int i = 0; i < p.meshes.size(); i++) { Mesh mm = p.meshes.get(i); keep.add(new Object[] { mm, mm.position.clone(), mm.quaternion.clone() }); mm.position.y = p.y0[i]; }
        }
        root.updateMatrixWorld(true);
        return keep;
    }
    private void restore(List<Object[]> keep) {
        for (Object[] x : keep) { ((Obj3D) x[0]).position.copy((V3) x[1]); ((Obj3D) x[0]).quaternion.copy((com.x3card.car3d.gl.Quat) x[2]); }
        root.updateMatrixWorld(true);
    }
    /** 设置里"调整车牌"开着：正在调哪块（能按住拖；车保持静止）；镜头看着它；正拖着时：起点、在哪个平面上拖、手指按下的地方离牌子中心多远 */
    private String pkey;
    private Frame plateV;
    private V3 pdrag, pdragN, pdragOff;
    /** 开 / 换前后（which）、关（null） */
    void plateEdit(String which) {
        pkey = which;
        pdrag = null;
        if (which == null) { if (plateV != null) { plateV = null; vHoming = true; vVaz = 0; } }
        else { plateV = plateFrame(which); vHoming = true; vVaz = 0; }
        if (root != null) app.invalidate();
    }
    /** 看这块车牌的角度（后牌同网页版车牌页的 [π − 0.4, 0.32]，前牌左右对称），稍微拉近 */
    private Frame plateFrame(String which) {
        boolean front = "front".equals(which);
        Frame f = new Frame();
        f.view = new float[] { front ? 0.4f : (float) (Math.PI - 0.4), 0.32f };
        Obj3D m = root != null && plates != null ? plates.get(which) : null;
        if (m != null) {
            List<Object[]> keep = parts != null ? restPose() : null; // 尾门正开着（刚进来还在关）：按关着的样子量
            try { f.at = m.getWorldPosition(new V3()); } finally { if (keep != null) restore(keep); }
        } else f.at = V(0, dimsH * 0.3, (front ? 1 : -1) * dimsHl * 0.98);
        f.zoom = f.want = 0.25f;
        return f;
    }
    /** 按在正在调的那块上：投到屏幕上的牌子范围（外扩一点），或者离中心 0.9rem 以内 */
    private boolean plateHit(float x, float y) {
        Obj3D m = plates != null && pkey != null ? plates.get(pkey) : null;
        if (m == null || !inScene(m) || camera == null || root == null) return false;
        m.updateWorldMatrix(true, false);
        V3 c0 = m.getWorldPosition(new V3()), nz = V(0, 0, 1).applyMatrix4(m.matrixWorld).sub(c0);
        if (nz.dot(camera.position.clone().sub(c0)) <= 0) return false; // 牌面背对着镜头（在车的另一头，被车挡着）
        Object wo = m.get("w"), ho = m.get("h");
        float w = wo instanceof Float ? (Float) wo : 0.44f, h = ho instanceof Float ? (Float) ho : 0.14f;
        float l = 1e9f, r = -1e9f, t = 1e9f, b = -1e9f;
        for (int i = 0; i < 4; i++) {
            V3 q = V((i & 1) != 0 ? w / 2 : -w / 2, (i & 2) != 0 ? h / 2 : -h / 2, 0).applyMatrix4(m.matrixWorld).project(camera);
            float sx = (q.x + 1) / 2 * W, sy = (1 - q.y) / 2 * H;
            l = Math.min(l, sx); r = Math.max(r, sx); t = Math.min(t, sy); b = Math.max(b, sy);
        }
        float pad = remPx * 0.25f, cx = (l + r) / 2, cy = (t + b) / 2;
        return x >= l - pad && x <= r + pad && y >= t - pad && y <= b + pad || Math.hypot(x - cx, y - cy) < remPx * 0.9f;
    }
    /** 按住车牌：在牌子自己所在的平面上拖（只上下左右走，前后深度不变，不会一拖就钻进车身；深度用"前后"滑块调）；
        镜头差不多侧着看、贴着牌面拖不准时，改在过车牌、朝着镜头的平面上拖。记下手指离牌子中心多远，拖的时候牌子不跳到手指底下 */
    private void plateGrab(float x, float y) {
        Obj3D m = plates.get(pkey);
        pdrag = m.getWorldPosition(new V3());
        V3 face = V(0, 0, 1).applyMatrix4(m.matrixWorld).sub(pdrag).normalize(), cam = camera.getWorldDirection(new V3());
        pdragN = Math.abs(face.dot(cam)) > 0.3f ? face : cam;
        pdragOff = new V3();
        ray.setFromCamera(x / W * 2 - 1, 1 - y / H * 2, camera);
        V3 h = ray.intersectPlane(pdragN, pdrag, new V3());
        if (h == null) { pdragN = cam; h = ray.intersectPlane(pdragN, pdrag, new V3()); }
        if (h != null) pdragOff.subVectors(pdrag, h);
    }
    /** 拖（拖出部件的范围先回到车底下） */
    private void plateMove(float x, float y) {
        Obj3D m = plates != null && pkey != null ? plates.get(pkey) : null;
        if (m == null) return;
        ray.setFromCamera(x / W * 2 - 1, 1 - y / H * 2, camera);
        V3 o = ray.intersectPlane(pdragN, pdrag, new V3());
        if (o == null) return;
        if (m.parent != root) root.attach(m);
        m.position.copy(o.add(pdragOff));
        app.invalidate();
    }
    /** 松手：位置记进配置、交回界面存起来，再看挂在哪个部件上；手势被打断就回原位 */
    private void plateDone(boolean cancel) {
        Obj3D m = plates != null && pkey != null ? plates.get(pkey) : null;
        JSONObject st = cfg != null ? cfg.optJSONObject("plates") : null, p = st != null && pkey != null ? st.optJSONObject(pkey) : null;
        if (m == null || p == null) return;
        if (!cancel) {
            V3 w = m.getWorldPosition(new V3());
            try {
                JSONArray at = new JSONArray().put(r3(w.x)).put(r3(w.y)).put(r3(w.z));
                p.put("at", at);
                final PlateOut po = plateOut;
                final String kk = pkey, aj = at.toString();
                if (po != null) app.ui(() -> { try { po.plateAt(kk, new JSONArray(aj), true); } catch (Exception ignored) { } });
            } catch (Exception ignored) { }
        }
        platePlace(m, p);
        app.invalidate();
    }
    interface Pos { V3 of(JSONArray a); }
    private static boolean seen(Mesh m) { return m.visible && !m.skinned; }
    private static List<Mat> pickedAs(Obj3D model, Map<Mesh, String> picked, String r) {
        List<Mat> l = new ArrayList<>();
        model.traverse(o -> { if (o instanceof Mesh && r.equals(picked.get(o)) && !l.contains(((Mesh) o).material)) l.add(((Mesh) o).material); });
        return l;
    }
    private static Tex mapOf(Mat m, String k) {
        switch (k) { case "aoMap": return m.aoMap; case "normalMap": return m.normalMap; case "map": return m.map; case "roughnessMap": return m.roughnessMap; case "metalnessMap": return m.metalnessMap; default: return null; }
    }
    private static void setMap(Mat m, String k, Tex t) {
        switch (k) { case "aoMap": m.aoMap = t; break; case "normalMap": m.normalMap = t; break; case "map": m.map = t; break; case "roughnessMap": m.roughnessMap = t; break; case "metalnessMap": m.metalnessMap = t; break; }
    }
    private static boolean shared(List<Mat> srcs, Mat src, String k, boolean uvAll) {
        Tex t = mapOf(src, k);
        if (t == null || !uvAll) return false;
        for (Mat m : srcs) if (mapOf(m, k) != t) return false;
        return true;
    }
    private String shownOf(Mat m) { Col c = m.color.clone(); Col avg = m.map != null ? texAvg(m.map) : null; if (avg != null) c.multiply(avg); return "#" + c.getHexString(); }

    /** 一个灯的零件：网格、多材质时第几个（这里都是 -1）、氛围灯前排 F / 后排 R */
    static final class Ent { Mesh o; String z; Ent(Mesh o, String z) { this.o = o; this.z = z; } }
    private String zoneOf(Mesh o, Float zSplit) {
        if (zSplit == null || o == null) return "";
        Box3 b = CarModel.box(o);
        return b.min.z > zSplit - 0.05f ? "F" : b.max.z < zSplit + 0.05f ? "R" : "";
    }
    private void lampOf(Map<String, List<Ent>> ents, Mesh o, String r, Float zSplit) { if (o.visible && !o.skinned) ents.get(r).add(new Ent(o, "ambient".equals(r) ? zoneOf(o, zSplit) : "")); }
    private Mat asRole(String r, Mat m, Mesh o, Map<String, Mat> copies, Map<String, List<Ent>> ents, Float zSplit) {
        if ("paint".equals(r)) return Mt.paint;
        if ("glass".equals(r)) return cfg.optBoolean("keepGlass") || cover(o) ? m : Mt.glass;
        if ("rim".equals(r) || "caliper".equals(r)) {
            String key = r + m.id;
            Mat cp = copies.get(key);
            if (cp == null) { cp = m.clone(); cp.ud.putAll(m.ud); copies.put(key, cp); ("rim".equals(r) ? Mt.rims : Mt.cals).add(cp); }
            return cp;
        }
        lampOf(ents, o, r, zSplit);
        return m;
    }
    /** 车头、车尾两端、不高过车身四分之三的玻璃是灯罩：留着原来的 */
    private boolean cover(Mesh o) { if (o == null) return false; Box3 b = CarModel.box(o); return Math.abs(b.min.z + b.max.z) / 2 > 0.6f * dimsHl && b.max.y < 0.75f * dimsH; }
    private Mat byMat(Mat m, Mesh o, List<Mat> paintSet, List<Mat> glassSet, Map<String, List<Mat>> lampSets, Map<String, List<Ent>> ents, List<String> have, Float zSplit) {
        if (paintSet.contains(m)) return Mt.paint;
        if (glassSet.contains(m)) return cover(o) ? m : Mt.glass;
        Mat out = m;
        for (String r : LIGHTS) {
            if (!lampSets.get(r).contains(m)) continue;
            if (have == null || !have.contains(r)) lampOf(ents, o, r, zSplit);
            if (out == m && Mt.lens && "head".equals(r)) out = Mt.head;
            else if (out == m && Mt.lens && "tail".equals(r)) out = Mt.tail;
        }
        return out;
    }
    /** 用车模自己的材质：车模的"反光"、加载器补的默认材质、透射、只标了混合模式其实不透明的 */
    private void fix(Mat m) {
        if (m.ud.containsKey("fixed")) return;
        m.ud.put("fixed", 1);
        if (envK != 1 && m.isStandard()) m.envMapIntensity *= envK;
        if (m.ud.containsKey("gltfDefault") && m.isStandard()) { m.metalness = 0.05f; m.roughness = 0.6f; }
        if (m.transparent && m.opacity >= 0.99f && !(m.transmission > 0) && m.alphaMap == null && !m.vertexColors && (m.map == null || m.map.mime.matches(".*jpe?g.*"))) { m.transparent = false; m.depthWrite = true; }
        if (m.transmission > 0) { m.transmission = 0; m.transparent = true; m.opacity = Math.min(m.opacity, 0.45f); m.depthWrite = false; }
    }

    private String lampHex(String r, String hx) {
        JSONObject l = cfg != null && cfg.optJSONObject("lights") != null ? cfg.optJSONObject("lights").optJSONObject(r) : null;
        return hx != null ? hx : l != null && l.has("color") ? l.optString("color") : LIGHT_COLOR.get(r);
    }
    private float[] lampRgb(String r, String hx) {
        String h = lampHex(r, hx);
        return new float[] { Integer.parseInt(h.substring(1, 3), 16) / 255f, Integer.parseInt(h.substring(3, 5), 16) / 255f, Integer.parseInt(h.substring(5, 7), 16) / 255f };
    }
    /** 灯的效果按配置（转向灯默认闪烁，别的常亮） */
    private void lightFx() {
        JSONObject L = cfg.optJSONObject("lights");
        for (String r : LIGHTS) {
            JSONObject l = L == null ? null : L.optJSONObject(r);
            Mt.fx.put(r, l != null && l.has("effect") ? l.optString("effect") : ("turnL".equals(r) || "turnR".equals(r) ? "blink" : "steady"));
        }
    }
    private JSONObject lightCfg(String r) { JSONObject L = cfg == null ? null : cfg.optJSONObject("lights"); return L == null ? null : L.optJSONObject(r); }

    // ================= 灯的点亮层、路面光束 =================
    static final String LAMP_VS = "varying vec2 vUv;\nvarying float vX;\nvarying float vZ;\nvoid main() {\n  vUv = uv;\n  vec4 w = modelMatrix * vec4(position, 1.0);\n  vX = w.x; vZ = w.z;\n  gl_Position = projectionMatrix * viewMatrix * w;\n}";
    static final String LAMP_FS = String.join("\n",
        "uniform sampler2D tMap;", "uniform vec3 uTint;", "uniform vec3 uTurnCol;", "uniform float uOn;", "uniform vec4 uGlow;", "uniform float uFlow;", "uniform vec2 uAx;", "uniform vec4 uTurn;", "uniform float uBorrow;", "uniform vec2 uMute;",
        "varying vec2 vUv;", "varying float vX;", "varying float vZ;",
        "void main() {",
        "  vec4 t = texture2D(tMap, vUv);",
        "  vec3 base = uTint * (1.0 - uGlow.z + uGlow.z * (0.3 + 0.7 * t.r));",
        "  vec3 lit = base + (1.0 - base) * uGlow.y * pow(clamp(t.g / 0.9, 0.0, 1.0), 1.4);",
        "  vec3 col = lit + (1.0 - lit) * max(0.0, uGlow.x - 1.0) * 0.6;",
        "  float shape = clamp(uGlow.x, 0.0, 1.0) * t.a;",
        "  if (uGlow.w > 0.0) shape *= smoothstep(0.0, uGlow.w * 0.4 + t.b, t.g);",
        "  float m = step(clamp((vX - uAx.x) / (uAx.y - uAx.x + 1e-5), 0.0, 1.0), uFlow);",
        "  float tq = uBorrow * (vZ > 0.0 ? (vX > 0.0 ? uTurn.x : uTurn.y) : (vX > 0.0 ? uTurn.z : uTurn.w));",
        "  float a = mix(shape * uOn * m, shape, tq) * (1.0 - (vX > 0.0 ? uMute.x : uMute.y));",
        "  if (a < 0.004) discard;",
        "  gl_FragColor = vec4(pow(mix(col, uTurnCol, tq), vec3(2.2)), a);",
        "  #include <encodings_fragment>",
        "}");
    private static Mat lampMat(Map<String, Val> u) {
        Mat m = Mat.shader(LAMP_VS, LAMP_FS);
        m.uniforms.putAll(u);
        m.transparent = true; m.depthWrite = false; m.side = Mat.DOUBLE; m.toneMapped = false;
        m.polygonOffset = true; m.polygonOffsetFactor = -1; m.polygonOffsetUnits = -4;
        return m;
    }
    /** 一种灯建好的点亮层：共用的 uniform、按前排 / 后排分的材质、网格 */
    static final class Ov { Map<String, Val> u = new HashMap<>(); Map<String, Mat> mats = new LinkedHashMap<>(); List<Mesh> meshes = new ArrayList<>(); }
    final class Lamps {
        final Map<String, List<Ent>> ents;
        final Map<String, Ov> ov = new HashMap<>();
        final Map<String, Mesh> B = new HashMap<>();
        final Map<String, String> bkey = new HashMap<>();
        final Map<String, Float> bc = new HashMap<>();
        final Map<String, Boolean> fbQ = new HashMap<>();
        final Map<String, float[]> ax = new HashMap<>();
        final Map<String, float[]> xsC = new HashMap<>();
        final List<Object> own = new ArrayList<>();
        final Obj3D R0 = root;
        final float off;
        Lamps(Map<String, List<Ent>> ents) {
            this.ents = ents;
            float carL = 2 * dimsHl;
            off = Math.min(0.004f, Math.max(0.001f, carL * 0.0004f));
            Map<String, Boolean> hasTurn = new HashMap<>(), hasFb = new HashMap<>();
            List<Ent> tl = new ArrayList<>(ents.get("turnL")); tl.addAll(ents.get("turnR"));
            for (Ent e : tl) for (String q : quadsOf(e.o)) hasTurn.put(q, true);
            for (String r : TURN_FB) for (Ent e : ents.get(r)) for (String q : quadsOf(e.o)) hasFb.put(q, true);
            for (String q : QUADS) if (!hasTurn.containsKey(q) && hasFb.containsKey(q)) fbQ.put(q, true);
            for (String sd : SIDES) {
                Box3 b = new Box3();
                for (Ent e : ents.get("turn" + sd)) b.union(CarModel.box(e.o));
                if (!b.isEmpty()) ax.put("turn" + sd, "L".equals(sd) ? new float[] { b.min.x, b.max.x } : new float[] { b.max.x, b.min.x });
            }
        }
        private List<String> quadsOf(Mesh o) {
            Box3 b = CarModel.box(o);
            float cz = (b.min.z + b.max.z) / 2;
            String e = cz > 0.5f * dimsHl ? "F" : cz < -0.5f * dimsHl ? "B" : "";
            List<String> q = new ArrayList<>();
            if (e.isEmpty()) return q;
            if (b.max.x > 0.02f * dimsHw) q.add("L" + e);
            if (b.min.x < -0.02f * dimsHw) q.add("R" + e);
            return q;
        }
        boolean has(String r) { return !ents.get(r).isEmpty(); }
        List<Mesh> srcs(String r) { List<Mesh> l = new ArrayList<>(); for (Ent e : ents.get(r)) l.add(e.o); return l; }
        boolean built(String r) { return ov.containsKey(r); }
        Ov get(String r) { if (!ov.containsKey(r)) ov.put(r, has(r) ? build(r) : null); return ov.get(r); }
        /** 一块灯罩：用到的三角形、投影到它朝向的平面、点亮层的几何（原零件自己的坐标，沿法线往外挪一点） */
        private Object[] pieceOf(Ent e) {
            Mesh o = e.o;
            Geo g = o.geometry;
            Attr pos = g == null ? null : g.attr("position");
            if (pos == null) return null;
            o.updateWorldMatrix(true, false);
            Attr ix = g.index;
            int total = ix != null ? ix.count : pos.count;
            int nt = total - total % 3;
            if (nt == 0) return null;
            int[] map = new int[pos.count];
            Arrays.fill(map, -1);
            int[] used = new int[pos.count];
            int nu = 0;
            int[] idx = new int[nt];
            for (int k = 0; k < nt; k++) { int v = ix != null ? ix.idx(k) : k; if (map[v] < 0) { map[v] = nu; used[nu++] = v; } idx[k] = map[v]; }
            int n = nu;
            M4 mw = o.matrixWorld;
            float[] wp = new float[n * 3], lp = new float[n * 3];
            V3 v3 = new V3();
            for (int i = 0; i < n; i++) {
                pos.v3(used[i], v3);
                lp[i * 3] = v3.x; lp[i * 3 + 1] = v3.y; lp[i * 3 + 2] = v3.z;
                v3.applyMatrix4(mw);
                wp[i * 3] = v3.x; wp[i * 3 + 1] = v3.y; wp[i * 3 + 2] = v3.z;
            }
            float[] nl = new float[n * 3];
            double[] C = new double[6];
            Attr na = g.attr("normal");
            if (na != null) for (int i = 0; i < n; i++) { nl[i * 3] = na.getX(used[i]); nl[i * 3 + 1] = na.getY(used[i]); nl[i * 3 + 2] = na.getZ(used[i]); }
            for (int k = 0; k < idx.length; k += 3) {
                int a = idx[k] * 3, b = idx[k + 1] * 3, cc = idx[k + 2] * 3;
                double ux = wp[b] - wp[a], uy = wp[b + 1] - wp[a + 1], uz = wp[b + 2] - wp[a + 2], vx = wp[cc] - wp[a], vy = wp[cc + 1] - wp[a + 1], vz = wp[cc + 2] - wp[a + 2];
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx, len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-12) continue;
                double w = len / 2; nx /= len; ny /= len; nz /= len;
                C[0] += w * nx * nx; C[1] += w * nx * ny; C[2] += w * nx * nz; C[3] += w * ny * ny; C[4] += w * ny * nz; C[5] += w * nz * nz;
                if (na == null) {
                    float lx = lp[idx[k + 1] * 3] - lp[a], ly = lp[idx[k + 1] * 3 + 1] - lp[a + 1], lz = lp[idx[k + 1] * 3 + 2] - lp[a + 2];
                    float mx = lp[cc] - lp[a], my = lp[cc + 1] - lp[a + 1], mz = lp[cc + 2] - lp[a + 2], fx = ly * mz - lz * my, fy = lz * mx - lx * mz, fz = lx * my - ly * mx;
                    for (int q : new int[] { a, b, cc }) { nl[q] += fx; nl[q + 1] += fy; nl[q + 2] += fz; }
                }
            }
            double[] d = { C[0], C[3], C[5] };
            V3 ex = V(d[0] >= d[1] && d[0] >= d[2] ? 1 : 0, d[1] > d[0] && d[1] >= d[2] ? 1 : 0, d[2] > d[0] && d[2] > d[1] ? 1 : 0);
            for (int k = 0; k < 24; k++) {
                double x = C[0] * ex.x + C[1] * ex.y + C[2] * ex.z, y = C[1] * ex.x + C[3] * ex.y + C[4] * ex.z, z = C[2] * ex.x + C[4] * ex.y + C[5] * ex.z;
                if (x * x + y * y + z * z < 1e-24) break;
                ex.set(x, y, z).normalize();
            }
            if (ex.lengthSq() < 0.5f) ex.set(0, 0, 1);
            V3 e1 = new V3().crossVectors(Math.abs(ex.y) < 0.9f ? V(0, 1, 0) : V(1, 0, 0), ex).normalize(), e2 = new V3().crossVectors(ex, e1);
            float[] p2 = new float[n * 2], op = new float[n * 3];
            float offL = off / Math.max(1e-6f, mw.getMaxScaleOnAxis());
            for (int i = 0; i < n; i++) {
                float X = wp[i * 3], Y = wp[i * 3 + 1], Z = wp[i * 3 + 2];
                p2[i * 2] = X * e1.x + Y * e1.y + Z * e1.z; p2[i * 2 + 1] = X * e2.x + Y * e2.y + Z * e2.z;
                float qx = nl[i * 3], qy = nl[i * 3 + 1], qz = nl[i * 3 + 2], ql = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
                if (ql == 0) ql = 1;
                op[i * 3] = lp[i * 3] + qx / ql * offL; op[i * 3 + 1] = lp[i * 3 + 1] + qy / ql * offL; op[i * 3 + 2] = lp[i * 3 + 2] + qz / ql * offL;
            }
            Attr ua = g.attr("uv");
            float[] suv = null;
            if (ua != null) { suv = new float[n * 2]; for (int i = 0; i < n; i++) { suv[i * 2] = ua.getX(used[i]); suv[i * 2 + 1] = ua.getY(used[i]); } }
            Mat mat = o.material;
            Lamp.Piece pc = new Lamp.Piece();
            pc.p = p2; pc.i = idx; pc.uv = suv;
            Tex it = mat.emissiveMap != null ? mat.emissiveMap : mat.map;
            pc.img = imgOf(it);
            pc.base = mat.color != null ? new float[] { Col.linearToSrgb(mat.color.r), Col.linearToSrgb(mat.color.g), Col.linearToSrgb(mat.color.b) } : new float[] { 1, 1, 1 };
            Geo geo = new Geo();
            geo.set("position", Attr.floats(op, 3));
            geo.setIndex(Attr.index(idx, n));
            return new Object[] { o, e.z, geo, pc };
        }
        private final Map<Tex, Lamp.Img> imgs = new IdentityHashMap<>();
        /** 原灯罩贴图的像素（缩到 256 以内） */
        private Lamp.Img imgOf(Tex t) {
            if (t == null || t.src == null) return null;
            if (imgs.containsKey(t)) return imgs.get(t);
            Lamp.Img r = null;
            try {
                Bitmap bm = decodeSmall(t, 256);
                if (bm != null) {
                    int w = bm.getWidth(), h = bm.getHeight();
                    int[] px = new int[w * h];
                    bm.getPixels(px, 0, w, 0, 0, w, h);
                    byte[] d = new byte[w * h * 4];
                    for (int i = 0; i < px.length; i++) { d[i * 4] = (byte) (px[i] >> 16); d[i * 4 + 1] = (byte) (px[i] >> 8); d[i * 4 + 2] = (byte) px[i]; d[i * 4 + 3] = (byte) (px[i] >>> 24); }
                    bm.recycle();
                    r = new Lamp.Img(w, h, d);
                }
            } catch (Exception ignored) { }
            imgs.put(t, r);
            return r;
        }
        private Ov build(String r) {
            List<Object[]> pcs = new ArrayList<>();
            for (Ent e : ents.get(r)) { try { Object[] pc = pieceOf(e); if (pc != null) pcs.add(pc); } catch (Exception er) { Log.w("car3d", "灯罩的形状没算出来", er); } }
            if (pcs.isEmpty()) return null;
            Lamp.Baked art;
            try { List<Lamp.Piece> l = new ArrayList<>(); for (Object[] pc : pcs) l.add((Lamp.Piece) pc[3]); art = Lamp.bake(l); }
            catch (Exception er) { Log.w("car3d", "灯的点亮层没做成", er); for (Object[] pc : pcs) ((Geo) pc[2]).dispose(); return null; }
            float[] tc = lampRgb("turnR".equals(r) ? "turnR" : "turnL", LIGHT_COLOR.get("turnL"));
            Ov o = new Ov();
            Tex tmap = Tex.data(art.data, art.w, art.h);
            own.add(tmap);
            o.u.put("tMap", new Val(tmap)); o.u.put("uTint", new Val(new V3(1, 1, 1))); o.u.put("uTurnCol", new Val(new V3(tc[0], tc[1], tc[2]))); o.u.put("uOn", new Val(0f));
            o.u.put("uGlow", new Val(new float[] { 1, 0.45f, 0.6f, 0.3f })); o.u.put("uFlow", new Val(1f)); o.u.put("uAx", new Val(new float[] { 0, 1 })); o.u.put("uTurn", turnU);
            o.u.put("uBorrow", new Val(0f)); o.u.put("uMute", new Val(new float[] { 0, 0 }));
            if (ax.containsKey(r)) o.u.get("uAx").v = new float[] { ax.get(r)[0], ax.get(r)[1] };
            for (int i = 0; i < pcs.size(); i++) {
                Object[] pc = pcs.get(i);
                String z = (String) pc[1];
                if (!o.mats.containsKey(z)) { Map<String, Val> zu = new HashMap<>(o.u); zu.put("uOn", new Val(0f)); Mat m = lampMat(zu); o.mats.put(z, m); own.add(m); }
                Geo geo = (Geo) pc[2];
                geo.set("uv", Attr.floats(art.uvs.get(i), 2));
                Mesh m = new Mesh(geo, o.mats.get(z));
                m.renderOrder = 2; m.visible = false; m.put("d3ov", 1); m.put("z", z); m.put("src", pc[0]); m.layers = 2;
                ((Mesh) pc[0]).add(m);
                own.add(geo);
                o.meshes.add(m);
            }
            return o;
        }
        private float[] xsOf(String r) {
            if (xsC.containsKey(r)) return xsC.get(r);
            List<Float> xs = new ArrayList<>();
            V3 v = new V3();
            for (Ent e : ents.get(r)) {
                Attr pos = e.o.geometry == null ? null : e.o.geometry.attr("position");
                if (pos == null) continue;
                e.o.updateWorldMatrix(true, false);
                for (int i = 0, st = Math.max(1, pos.count / 400); i < pos.count; i += st) { pos.v3(i, v).applyMatrix4(e.o.matrixWorld); xs.add(v.x); }
            }
            float[] a = new float[xs.size()];
            for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
            xsC.put(r, a);
            return a;
        }
        /** 路面光束：a 淡入淡出（0 收起来）。参数变了重画（强度、颜色不用重画） */
        void beam(String slot, float a) {
            Lamp.BeamDef d = Lamp.BEAMS.get(slot);
            String r = d.role;
            Mesh x = B.get(slot);
            if (a <= 0.01f || ents.get(r).isEmpty()) { if (x != null) x.visible = false; return; }
            JSONObject L = lightCfg(r);
            Lamp.Beam b = Lamp.beam(slot, L == null ? null : L.optJSONObject(d.key));
            if (!b.enabled) { if (x != null) x.visible = false; return; }
            float wid = 2 * dimsHw, len = 2 * dimsHl, hwB = b.width * wid / 2, cx = b.side * wid;
            float[] xs = xsOf(r), lb = Lamp.lobes(xs, xs.length, cx, hwB, wid, b);
            StringBuilder ks = new StringBuilder();
            ks.append(b.shape).append(',').append(b.lobeMode).append(',').append(b.lobeSpacing).append(',').append(b.lobeWidth).append(',').append(b.length).append(',').append(b.width).append(',')
                .append(b.offset).append(',').append(b.side).append(',').append(b.height).append(',').append(b.spread).append(',').append(b.falloff).append(',').append(b.haze);
            for (float f : lb) ks.append('/').append(f);
            String key = ks.toString();
            if (x == null || !key.equals(bkey.get(slot))) {
                if (x != null) { R0.remove(x); x.geometry.dispose(); if (x.material.map != null) x.material.map.dispose(); x.material.dispose(); }
                float near = b.dir * (dimsHl + b.offset * len), far = near + b.dir * b.length * len, y = Math.max(0.004f, b.height);
                Geo g = new Geo();
                g.set("position", Attr.floats(new float[] { cx + hwB, y, near, cx - hwB, y, near, cx - hwB, y, far, cx + hwB, y, far }, 3));
                g.set("uv", Attr.floats(new float[] { 0, 1, 1, 1, 1, 0, 0, 0 }, 2));
                g.setIndex(Attr.index(new int[] { 0, 1, 2, 0, 2, 3 }, 4));
                Mat bm = Mat.basic();
                bm.map = Tex.data(Lamp.beamTex(b, lb), Lamp.BEAM_PX, Lamp.BEAM_PX);
                bm.transparent = true; bm.depthWrite = false; bm.toneMapped = false; bm.side = Mat.DOUBLE;
                Mesh mesh = new Mesh(g, bm);
                mesh.renderOrder = 1; mesh.layers = 2;
                R0.add(mesh);
                B.put(slot, x = mesh);
                bkey.put(slot, key);
            }
            float[] rgb = lampRgb(r, b.color);
            x.material.color.setSRGB(1 - 0.4f * (1 - rgb[0]), 1 - 0.4f * (1 - rgb[1]), 1 - 0.4f * (1 - rgb[2]));
            x.material.opacity = a * b.intensity;
            x.visible = true;
        }
        /** 预先做好一道光束（贴图、四边形），先藏着 */
        void warmBeam(String slot) { Mesh x = B.get(slot); beam(slot, 1); Mesh y = B.get(slot); if (y != null) y.visible = x != null && x.visible; }
        void dispose() {
            for (Ov o : ov.values()) if (o != null) for (Mesh m : o.meshes) if (m.parent != null) m.parent.remove(m);
            for (Mesh m : B.values()) { if (m.parent != null) m.parent.remove(m); m.geometry.dispose(); if (m.material.map != null) m.material.map.dispose(); m.material.dispose(); }
            for (Object x : own) { if (x instanceof Tex) ((Tex) x).dispose(); else if (x instanceof Mat) ((Mat) x).dispose(); else if (x instanceof Geo) ((Geo) x).dispose(); }
            ov.clear(); B.clear(); own.clear();
        }
    }

    /** 门 / 盖：配置写死了铰链就用；不然按门型和部件外框算 */
    private Part door(String k, List<Mesh> ms, JSONObject p, Pos P) {
        float sign = p.optBoolean("flip") ? -1 : 1;
        Box3 b = new Box3();
        for (Mesh m : ms) b.expandByObject(m);
        float side = b.min.x + b.max.x > 0 ? 1 : -1, outer = side > 0 ? b.max.x : b.min.x, inner = side > 0 ? b.min.x : b.max.x;
        boolean lid = "hood".equals(k) || "trunk".equals(k);
        if (p.has("at") && p.has("axis")) {
            JSONArray ax = p.optJSONArray("axis");
            return moved(hinge(ms, moveAt(P.of(p.optJSONArray("at")), p, b, lid ? 1 : side), V(ax.optDouble(0), ax.optDouble(1), ax.optDouble(2)).normalize(), (float) p.optDouble("angle", 60) * DEG * sign, "up"), P.of(p.optJSONArray("at")), b, side);
        }
        float midY = (b.min.y + b.max.y) / 2, midZ = (b.min.z + b.max.z) / 2;
        String st = p.optString("style", "normal"), dir = "up";
        V3 at, axis;
        float ang;
        if (lid) {
            float z = ("hood".equals(k)) != p.optBoolean("other") ? b.min.z : b.max.z;
            at = V(0, b.max.y, z); axis = V(1, 0, 0); ang = "hood".equals(k) ? 55 : 50;
        } else if ("butterfly".equals(st)) { at = V(inner + (outer - inner) * 0.15, b.min.y + (b.max.y - b.min.y) * 0.85, b.max.z - (b.max.z - b.min.z) * 0.12); axis = V(-0.32 * side, 0.5, -0.8); ang = 66; }
        else if ("scissor".equals(st)) { at = V(outer, b.min.y + (b.max.y - b.min.y) * 0.4, b.max.z); axis = V(1, 0, 0); ang = 75; }
        else if ("gull".equals(st)) { at = V(inner, b.max.y, midZ); axis = V(0, 0, 1); ang = 75; }
        else { at = V(outer, midY, "rear".equals(st) ? b.min.z : b.max.z); axis = V(0, 1, 0); ang = "chargePort".equals(k) ? 95 : 60; dir = "out"; }
        float sd = lid ? 1 : side;
        return moved(hinge(ms, moveAt(at.clone(), p, b, sd), axis.normalize(), (float) p.optDouble("angle", ang) * DEG * sign, dir), at, b, sd);
    }
    private V3 moveAt(V3 at, JSONObject p, Box3 b, float side) {
        JSONArray pv = p.optJSONArray("piv");
        if (pv == null) return at;
        V3 sz = b.getSize(new V3());
        return at.add(V(pv.optDouble(0) * sz.x * side, pv.optDouble(1) * sz.y, pv.optDouble(2) * sz.z));
    }
    private static Part moved(Part h, V3 base, Box3 b, float side) { h.base = base; h.size = b.getSize(new V3()); h.side = side; return h; }
    /** 天窗、遮阳帘：往后滑；mode 'tilt'：后缘为轴、前缘翘起 */
    private Part slider(List<Mesh> ms, JSONObject p) {
        Box3 b = new Box3();
        for (Mesh m : ms) b.expandByObject(m);
        if ("tilt".equals(p.optString("mode"))) return moved(hinge(ms, moveAt(V(0, b.max.y, b.min.z), p, b, 1), V(1, 0, 0), (float) p.optDouble("angle", 7) * DEG * (p.optBoolean("flip") ? -1 : 1), "up"), V(0, b.max.y, b.min.z), b, 1);
        Obj3D pivot = new Obj3D();
        pivot.put("d3move", 1);
        root.add(pivot);
        root.updateMatrixWorld(true);
        for (Mesh m : ms) pivot.attach(m);
        Part h = new Part();
        h.pivot = pivot; h.meshes = ms;
        h.slide = V(0, (b.max.y - b.min.y) * 0.15 + 0.004, -(b.max.z - b.min.z) * p.optDouble("travel", 0.85));
        return h;
    }
    /** 部件绕铰链转：pivot 放在铰链点，部件挂上去（世界位置不变）；dir：往哪边转算"打开"（up 往上掀，out 往外开） */
    private Part hinge(List<Mesh> meshes, V3 at, V3 axis, float angle, String dir) {
        Obj3D pivot = new Obj3D();
        pivot.put("d3move", 1);
        pivot.position.copy(at);
        root.add(pivot);
        root.updateMatrixWorld(true);
        for (Mesh m : meshes) pivot.attach(m);
        Box3 bb = new Box3();
        for (Mesh m : meshes) bb.expandByObject(m);
        V3 c = bb.getCenter(new V3()).sub(at), a = c.clone().applyAxisAngle(axis, 0.5f), b = c.clone().applyAxisAngle(axis, -0.5f);
        float up = "out".equals(dir) ? (Math.abs(a.x + at.x) > Math.abs(b.x + at.x) ? 1 : -1) : a.y > b.y ? 1 : -1;
        Part h = new Part();
        h.pivot = pivot; h.axis = axis; h.angle = angle * up; h.meshes = meshes;
        return h;
    }
    /** 左右两边车窗在同一个网格里：按三角形在车的哪一边拆成两个 */
    private Mesh[] splitX(Mesh mesh) {
        Geo g = mesh.geometry;
        Attr pos = g.attr("position"), ix = g.index;
        M4 mw = mesh.matrixWorld;
        List<Integer> L = new ArrayList<>(), R = new ArrayList<>();
        V3 v = new V3();
        int max = 0;
        for (int i = 0; i + 2 < ix.count; i += 3) {
            v.set(0, 0, 0);
            for (int j = 0; j < 3; j++) { int k = ix.idx(i + j); v.x += pos.getX(k) / 3; v.y += pos.getY(k) / 3; v.z += pos.getZ(k) / 3; max = Math.max(max, k); }
            v.applyMatrix4(mw);
            List<Integer> l = v.x > 0 ? L : R;
            l.add(ix.idx(i)); l.add(ix.idx(i + 1)); l.add(ix.idx(i + 2));
        }
        Mesh[] out = new Mesh[2];
        List<List<Integer>> sides = Arrays.asList(L, R);
        for (int s = 0; s < 2; s++) {
            Geo gg = new Geo();
            gg.attributes.putAll(g.attributes);
            int[] a = new int[sides.get(s).size()];
            for (int i = 0; i < a.length; i++) a[i] = sides.get(s).get(i);
            gg.setIndex(Attr.index(a, max));
            gg.setBoundingBox(g.boundingBox());
            Mesh m = new Mesh(gg, mesh.material);
            m.put("car", 1);
            mesh.parent.add(m);
            m.position.copy(mesh.position); m.quaternion.copy(mesh.quaternion); m.scale.copy(mesh.scale);
            m.updateMatrixWorld(true);
            out[s] = m;
        }
        mesh.parent.remove(mesh);
        return out;
    }
    /** 车底柔和的接触阴影（同网页版画布上画的：车身一块 + 四个轮子压地的深色，尺寸按车的大小和轮位） */
    private Mesh shadow(Map<String, V3> WP) {
        final float kx = dimsHw / 1.02f, kz = dimsHl / 2.3f, kr = wheelR / 0.345f;
        final float sw = 4.6f * kx, sl = 7.6f * kz;
        final Map<String, V3> wp = WP;
        Tex tex = app.canvasTex(184, Math.round(184 * sl / sw), (cv, w, h) -> {
            float sx = w / sw, sz = h / sl;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            blur(p, w * 0.09f); p.setColor(0x2e000000);
            cv.drawOval(new RectF(w / 2f - 1.5f * kx * sx, h / 2f - 2.75f * kz * sz, w / 2f + 1.5f * kx * sx, h / 2f + 2.75f * kz * sz), p);
            blur(p, w * 0.045f); p.setColor(0x6b000000);
            float rx = w / 2f - 0.95f * kx * sx, ry = h / 2f - 2.2f * kz * sz, rr = 0.45f * kx * sx;
            cv.drawRoundRect(new RectF(rx, ry, rx + 1.9f * kx * sx, ry + 4.4f * kz * sz), rr, rr, p);
            blur(p, w * 0.02f); p.setColor(0x8c000000);
            for (String t : TYRES) {
                float x = w / 2f + wp.get(t).x * sx, y = h / 2f - wp.get(t).z * sz, ex = 0.16f * kr * sx, ey = 0.36f * kr * sz;
                cv.drawOval(new RectF(x - ex, y - ey, x + ex, y + ey), p);
            }
        }, false);
        Mat m = Mat.basic();
        m.map = tex; m.color.setHex(0x000000); m.transparent = true; m.depthWrite = false;
        Mesh mesh = new Mesh(Shapes.plane(sw, sl), m);
        mesh.rotate((float) (-Math.PI / 2), 0, 0); mesh.position.y = 0.002f;
        return mesh;
    }
    /** CSS 的 blur(r) 是标准差 r 的高斯模糊；安卓 BlurMaskFilter 的半径按 Skia 的换算是 标准差 ≈ 半径 × 0.57735 + 0.5 */
    private static void blur(Paint p, float px) { p.setMaskFilter(new BlurMaskFilter(Math.max(0.1f, Math.round(px) - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)); }

    /** 车漆着色：金属漆的闪点、清漆的橘皮纹（同网页版 paintFx：onBeforeCompile 改着色器原文） */
    static final String NOISE = "vec3 d3h3(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx) - 0.5; }\n" +
        "float d3h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }\n" +
        "float d3n(vec3 p) { vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);\n" +
        "  return mix(mix(mix(d3h(i), d3h(i + vec3(1.0, 0.0, 0.0)), f.x), mix(d3h(i + vec3(0.0, 1.0, 0.0)), d3h(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),\n" +
        "    mix(mix(d3h(i + vec3(0.0, 0.0, 1.0)), d3h(i + vec3(1.0, 0.0, 1.0)), f.x), mix(d3h(i + vec3(0.0, 1.0, 1.0)), d3h(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z); }\n";
    private void paintFx(Mat m) {
        final Val flake = Mt.flake, peel = Mt.peel;
        Mt.fb = 0;
        m.onBeforeCompile = (vs, fs, u) -> {
            u.put("uD3Flake", flake); u.put("uD3Peel", peel);
            vs = "varying vec3 vD3P;\n" + vs.replace("#include <project_vertex>", "#include <project_vertex>\n  vD3P = (modelMatrix * vec4(transformed, 1.0)).xyz;");
            fs = "uniform float uD3Flake;\nuniform float uD3Peel;\nvarying vec3 vD3P;\n" + NOISE + fs
                .replace("#include <roughnessmap_fragment>", "#include <roughnessmap_fragment>\n  if (uD3Flake > 0.0) roughnessFactor = clamp(roughnessFactor * (1.0 + uD3Flake * 9.0 * d3h3(floor(vD3P * 160.0)).x), 0.04, 1.0);")
                .replace("#include <clearcoat_normal_fragment_maps>", "#include <clearcoat_normal_fragment_maps>\n#ifdef USE_CLEARCOAT\n  if (uD3Peel > 0.0) clearcoatNormal = normalize(clearcoatNormal + uD3Peel * vec3(d3n(vD3P * 70.0) - 0.5, d3n(vD3P * 70.0 + 13.1) - 0.5, 0.0));\n#endif");
            return new String[] { vs, fs };
        };
        m.cacheKey = "d3paint";
    }
    /** 车上的标准材质光照按中精度算（同网页版 lowp：只用在标准打光的车模上） */
    private static void lowp(Obj3D r) {
        r.traverse(o -> {
            if (!(o instanceof Mesh)) return;
            Mat m = ((Mesh) o).material;
            if (m == null || !m.isStandard() || m.defines.containsKey("D3_LOWP")) return;
            m.defines.put("D3_LOWP", "");
            m.needsUpdate();
        });
    }
    /** 贴图的平均颜色（线性的，同网页版：画到 4×4 的画布上取平均）；读不出来返回 null */
    private Col texAvg(Tex t) {
        try {
            Bitmap bm = decodeSmall(t, 64);
            if (bm == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(bm, 4, 4, true);
            float r = 0, g = 0, b = 0;
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) { int p = s.getPixel(x, y); r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
            if (s != bm) s.recycle();
            bm.recycle();
            return new Col(r / 16 / 255, g / 16 / 255, b / 16 / 255).convertSRGBToLinear();
        } catch (Exception e) { return null; }
    }
    private static Bitmap decodeSmall(Tex t, int max) {
        if (t == null || t.src == null) return null;
        byte[] b = new byte[t.src.remaining()];
        t.src.duplicate().get(b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(b, 0, b.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s; o.inPremultiplied = false;
        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
        if (bm == null) return null;
        int w = bm.getWidth(), h = bm.getHeight();
        float k = Math.min(1, max / (float) Math.max(w, h));
        if (k < 1) { Bitmap r = Bitmap.createScaledBitmap(bm, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true); if (r != bm) bm.recycle(); bm = r; }
        return bm;
    }

    /** 车漆 / 轮毂 / 卡钳按设置换色；animate 时从现在的颜色 0.6 秒渐变过去 */
    void finish(boolean animate) {
        if (Mt == null) return;
        Finish f = finishFrom = new Finish();
        f.paint = Mt.paint.color.clone(); f.pm = Mt.paint.metalness; f.pr = Mt.paint.roughness; f.pc = Mt.paint.clearcoat; f.pcr = Mt.paint.clearcoatRoughness; f.pi = Mt.paint.iridescence;
        f.pf = Mt.fb; f.pp = Mt.peel.f();
        for (Mat m : Mt.rims) f.rim.add(new Object[] { m.color.clone(), m.metalness, m.roughness });
        for (Mat m : Mt.cals) f.cal.add(m.color.clone());
        finishK = animate ? 0 : 1;
        tint(finishK);
        if (app != null) app.invalidate();
    }
    private void tint(float k) {
        Settings.Pick p = S.pick("paint"), r = S.pick("rim"), c = S.pick("caliper");
        float e = k * k * (3 - 2 * k);
        Finish f = finishFrom;
        Mt.paint.color.copy(f.paint).lerp(new Col(p.color), e);
        Mt.paint.metalness = f.pm + (p.metal - f.pm) * e;
        Mt.paint.roughness = f.pr + (p.rough - f.pr) * e;
        Mt.paint.clearcoat = f.pc + (p.coat - f.pc) * e;
        Mt.paint.clearcoatRoughness = f.pcr + (p.ccr - f.pcr) * e;
        Mt.paint.iridescence = f.pi + (p.iri - f.pi) * e;
        Mt.paint.iridescenceThickMin = p.thick[0]; Mt.paint.iridescenceThickMax = p.thick[1];
        boolean on = S.fx().flake;
        Mt.fb = f.pf + ((on ? p.flake : 0) - f.pf) * e;
        Mt.peel.v = f.pp + ((on && p.coat > 0.3f ? 0.035f : 0) - f.pp) * e;
        Tex map = p.model && Mt.origColor != null ? Mt.origMap : Mt.keepMap && Mt.origColor != null ? Mt.origMap : null;
        if (Mt.paint.map != map) { Mt.paint.map = map; Mt.paint.needsUpdate(); }
        for (int i = 0; i < Mt.rims.size(); i++) {
            Mat m = Mt.rims.get(i);
            Object[] o = f.rim.get(i), t = r.model && i < Mt.rim0.size() ? Mt.rim0.get(i) : new Object[] { new Col(r.color), r.metal, r.rough };
            m.color.copy((Col) o[0]).lerp((Col) t[0], e);
            m.metalness = (float) o[1] + ((float) t[1] - (float) o[1]) * e;
            m.roughness = (float) o[2] + ((float) t[2] - (float) o[2]) * e;
        }
        for (int i = 0; i < Mt.cals.size(); i++) Mt.cals.get(i).color.copy(f.cal.get(i)).lerp(c.model && i < Mt.cal0.size() ? Mt.cal0.get(i) : new Col(c.color), e);
    }

    // ================= 车况 → 画面、文字 =================
    void setCar(Map<String, Object> c) { carNow = c; apply(c); }
    void setSys(Map<String, Object> s) { sysNow = s; sysIcons(); }
    void setMedia(JSONObject m) {
        boolean p = m != null && m.optBoolean("playing");
        if (p == playing) return;
        playing = p;
        if (app != null && musicOn) app.invalidate();
    }
    boolean wantsMedia() { return musicOn; }
    private void apply(Map<String, Object> c) {
        Object n = c.get("display.dayNightMode");
        if (n != null) prefs.edit().putString("carDark", "night".equals(n) ? "1" : "0").apply();
        nightData = n != null ? (Boolean) "night".equals(n) : null;
        theme(wantDark());
        Object gear = c.get("driving.gear");
        topView("D".equals(gear));
        for (String w : TYRES) {
            Object st = c.get("tyre." + w + ".state"), p = c.get("tyre." + w + ".pressure");
            tyreBad.put(w, "burst".equals(p) || "fault".equals(p) || "critical".equals(st) ? 2 : st != null && !"normal".equals(st) ? 1 : 0);
        }
        tyreTour();
        text(c);
        if (root == null) return;
        doorWatch(c);
        for (Map.Entry<String, String> e : DOORS.entrySet()) want.put(e.getKey(), "open".equals(c.get(e.getValue())) ? 1f : 0f);
        for (Map.Entry<String, String> e : LIDS.entrySet()) want.put(e.getKey(), "open".equals(c.get(e.getValue())) ? 1f : 0f);
        for (Map.Entry<String, String> e : WINS.entrySet()) { Object w = c.get(e.getValue()); want.put(e.getKey(), w instanceof Number ? Math.max(0, Math.min(1, ((Number) w).floatValue() / 100)) : 0f); }
        for (Map.Entry<String, String> e : SLIDES.entrySet()) { Object w = c.get(e.getValue()); want.put(e.getKey(), w instanceof Number ? Math.max(0, Math.min(1, ((Number) w).floatValue() / 100)) : 0f); }
        Object con = c.get(FLAPS.get("chargePort"));
        want.put("chargePort", con != null && !"none".equals(con) ? 1f : 0f);
        boolean plugged = "ac".equals(con) || "dc".equals(con) || "acDc".equals(con), charging = "charging".equals(c.get("energy.chargingState"));
        Double bat = nm(c.get("energy.batteryPercent"));
        if (chg != null) chg.set(plugged || charging, charging, bat);
        String chgKey = (plugged || charging) + "" + charging + (bat == null ? "" : Math.round(bat));
        boolean low = Boolean.TRUE.equals(c.get("light.lowBeam")), high = Boolean.TRUE.equals(c.get("light.highBeam")), lit = low || high, side = Boolean.TRUE.equals(c.get("light.side"));
        Object pw = c.get("power.level"), ab = c.get("light.ambient.brightness"), drl = c.get("light.daytimeRunning");
        boolean on = "on".equals(pw) || "ready".equals(pw) || "fakeReady".equals(pw), powered = pw == null || on;
        if (on && !"\u0000".equals(lastPw) && !String.valueOf(lastPw).equals(String.valueOf(pw)) && !("on".equals(lastPw) || "ready".equals(lastPw) || "fakeReady".equals(lastPw))) welT = 0;
        lastPw = pw;
        lightOn.clear();
        lightOn.put("head", lit ? 1f : 0); lightOn.put("high", high ? 1f : 0); lightOn.put("tail", lit || side ? 1f : 0); lightOn.put("side", side ? 1f : 0);
        lightOn.put("led", (drl == null ? lit : Boolean.TRUE.equals(drl) && powered) ? 1f : 0);
        lightOn.put("brake", "on".equals(c.get("light.brake")) || "flash".equals(c.get("light.brake")) ? 1f : 0);
        lightOn.put("reverse", "R".equals(gear) ? 1f : 0); lightOn.put("fogF", Boolean.TRUE.equals(c.get("light.frontFog")) ? 1f : 0); lightOn.put("fogR", Boolean.TRUE.equals(c.get("light.rearFog")) ? 1f : 0);
        float abv = ab instanceof Number ? ((Number) ab).floatValue() : 0;
        lightOn.put("ambient", powered ? (ab instanceof Number ? Math.max(0.25f, Math.min(1, abv <= 5 ? abv / 5 : abv <= 10 ? abv / 10 : abv / 100)) : 0.8f) : 0);
        lightOn.put("power", powered ? 1f : 0);
        brakeFlash = "flash".equals(c.get("light.brake"));
        ambientHex = AMBIENT.get(String.valueOf(c.get("light.ambient.color")));
        Object aa = c.get("light.ambient.area");
        ambArea = aa != null ? String.valueOf(aa) : "all";
        colorful = Boolean.TRUE.equals(c.get("light.dynamicColorful"));
        boolean mm = Boolean.TRUE.equals(c.get("light.ambient.musicMode"));
        if (mm != musicOn) { musicOn = mm; if (needs != null) needs.run(); }
        turnHz = Boolean.TRUE.equals(c.get("light.hazard"));
        lightOn.put("hazard", turnHz ? 1f : 0);
        long t = now();
        for (String s : SIDES) {
            boolean l = Boolean.TRUE.equals(c.get("L".equals(s) ? "light.turnSignal.left" : "light.turnSignal.right"));
            if (l && !bulb.get(s) && t - offAt.get(s) < 1200) pulsed = true;
            if (!l && bulb.get(s)) offAt.put(s, t);
            if (l || bulb.get(s)) blinkAt.put(s, t);
            bulb.put(s, l);
        }
        turnState();
        for (String w : TYRES) {
            int bad = tyreBad.get(w);
            Mesh ring = wheels.get(w).ring;
            ring.visible = bad > 0;
            ring.material.color.setHex(bad == 2 ? 0xff3b30 : 0xff9a1f);
            ring.material.opacity = bad > 0 ? 0.9f : 0;
        }
        String k3 = want + "|" + lightOn + "|" + brakeFlash + ambientHex + ambArea + colorful + musicOn + turnHz + bulb + pulsed + tyreBad + chgKey;
        if (!k3.equals(applyKey) || root != applyRoot || welT == 0) { applyKey = k3; applyRoot = root; app.invalidate(); }
    }
    /** 车况页要不要媒体订阅变了（音乐律动开关）：由 MainActivity 接 */
    Runnable needs;
    private boolean wantDark() {
        String t = S.str("theme");
        return "dark".equals(t) ? true : "light".equals(t) ? false : nightData != null ? nightData : guessDark();
    }
    private void topView(boolean on) {
        if (on == topOn) return;
        topOn = on;
        topLast = 0;
        vHoming = true; vVaz = 0;
        if (app != null) app.invalidate();
    }
    private boolean guessDark() {
        String v = prefs.getString("carDark", null);
        if ("1".equals(v) || "0".equals(v)) return "1".equals(v);
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 6;
    }
    private static final String[] WELCOME_L = { "head", "tail", "led", "side" };
    private static boolean welcome(String r) { for (String w : WELCOME_L) if (w.equals(r)) return true; return false; }
    private float onOf(String r) {
        if (CUSTOM.contains(r)) { JSONObject l = lightCfg(r); String w = l != null && l.has("when") ? l.optString("when") : "power"; Float v = lightOn.get(w); return v == null ? 0 : v; }
        Float v = lightOn.get(r);
        return v == null ? 0 : v;
    }
    private static boolean strobe(long now, boolean late) { long t = (now + (late ? 500 : 0)) % 1000; return t < 300 && t % 100 < 50; }
    /** 每帧算灯（同 lightsTick）：返回要不要接着出帧 */
    private boolean lightsTick(float wf) {
        if (LS == null) return false;
        long t = now();
        boolean moving = false;
        long ph = t % 760;
        boolean blink = ph < 420, tOn = blinking && blink;
        boolean tlL = blink && blinking && turnOn.get("L"), tlR = blink && blinking && turnOn.get("R");
        boolean bw = cfg != null && cfg.optBoolean("borrow");
        float[] tu = (float[]) turnU.v;
        tu[0] = bw && LS.fbQ.containsKey("LF") && tlL ? 1 : 0; tu[1] = bw && LS.fbQ.containsKey("RF") && tlR ? 1 : 0;
        tu[2] = bw && LS.fbQ.containsKey("LB") && tlL ? 1 : 0; tu[3] = bw && LS.fbQ.containsKey("RB") && tlR ? 1 : 0;
        boolean borrowing = tu[0] + tu[1] + tu[2] + tu[3] > 0;
        float[] tc = lampRgb("turnL", null);
        Map<Mesh, String> claim = new IdentityHashMap<>();
        Map<String, float[]> mute = new HashMap<>();
        for (String r : LAMP_PRI) {
            if (!LS.has(r)) continue;
            boolean turn = "turnL".equals(r) || "turnR".equals(r);
            if (!(turn ? blinking && turnOn.get(r.substring(4)) : Math.max(onOf(r), welcome(r) ? wf : 0) > 0.003f)) continue;
            if (!turn) { for (Mesh u : LS.srcs(r)) if (!claim.containsKey(u)) claim.put(u, r); continue; }
            List<Mesh> src = LS.srcs(r);
            for (String q : LIGHTS) {
                if ("turnL".equals(q) || "turnR".equals(q) || !LS.has(q)) continue;
                boolean hit = false;
                for (Mesh u : LS.srcs(q)) if (src.contains(u)) { hit = true; break; }
                if (!hit) continue;
                float[] mq = mute.get(q);
                if (mq == null) mute.put(q, mq = new float[2]);
                mq["turnL".equals(r) ? 0 : 1] = 1;
            }
        }
        for (String r : LIGHTS) {
            if (!LS.has(r)) continue;
            String fx = Mt.fx.get(r);
            float k, flow = 1;
            JSONObject lc = lightCfg(r);
            if ("turnL".equals(r) || "turnR".equals(r)) {
                String s = r.substring(4);
                boolean on = tOn && turnOn.get(s);
                k = on ? 1 : 0;
                if ("flow".equals(fx) && on) moving = true;
                if ("flow".equals(fx)) flow = Math.min(1, ph / 300f);
            } else {
                k = Math.max(onOf(r), welcome(r) ? wf : 0);
                boolean late = lc != null && lc.optBoolean("late");
                if (k > 0 && "breath".equals(fx)) { k *= 0.5f + 0.5f * (float) Math.sin(t / 1000.0 * 2.4 + (late ? Math.PI : 0)); moving = true; }
                if (k > 0 && "blink".equals(fx)) { k = (t + (late ? 400 : 0)) % 800 < 400 ? k : 0; moving = true; }
                if (k > 0 && "strobe".equals(fx)) { k = strobe(t, late) ? k : 0; moving = true; }
                if (k > 0 && lc != null && "hazard".equals(lc.optString("when")) && (fx == null || "steady".equals(fx))) k = blink ? k : 0;
                if ("brake".equals(r) && brakeFlash && onOf("brake") > 0) { k = t % 250 < 125 ? k : 0; moving = true; }
            }
            boolean byCar = "ambient".equals(r) && onOf("ambient") > 0;
            if (byCar && musicOn && playing) { k *= beat(t); moving = true; }
            boolean lend = borrowing && Arrays.asList(TURN_FB).contains(r);
            if (k <= 0.003f && !lend && !LS.built(r)) continue;
            Ov o = LS.get(r);
            if (o == null) continue;
            String col = null;
            if ("ambient".equals(r) && !(lc != null && lc.has("color"))) {
                if (byCar && colorful) { col = "#" + hueCol.setHSL(t / 9000f % 1, 0.9f, 0.6f).getHexString(); moving = true; } else col = ambientHex;
            }
            float[] rgb = lampRgb(r, col), g = Lamp.glow(lc == null ? null : lc.optJSONObject("glow"), r);
            ((V3) o.u.get("uTint").v).set(rgb[0], rgb[1], rgb[2]);
            ((V3) o.u.get("uTurnCol").v).set(tc[0], tc[1], tc[2]);
            float[] gv = (float[]) o.u.get("uGlow").v; gv[0] = g[0]; gv[1] = g[1]; gv[2] = g[2]; gv[3] = g[3];
            o.u.get("uFlow").v = flow;
            o.u.get("uBorrow").v = lend ? 1f : 0f;
            float[] mv = (float[]) o.u.get("uMute").v; float[] mq = mute.get(r); mv[0] = mq != null ? mq[0] : 0; mv[1] = mq != null ? mq[1] : 0;
            String zone = byCar && !"all".equals(ambArea) ? ("front".equals(ambArea) ? "R" : "F") : "";
            for (Map.Entry<String, Mat> e : o.mats.entrySet()) e.getValue().uniforms.get("uOn").v = !zone.isEmpty() && e.getKey().equals(zone) ? 0f : k;
            boolean isTurn = "turnL".equals(r) || "turnR".equals(r);
            final float kk = k;
            for (Mesh m : o.meshes) {
                String cl = claim.get((Mesh) m.get("src"));
                m.visible = (isTurn || cl == null || cl.equals(r)) && (lend || (kk > 0.003f && !(!zone.isEmpty() && zone.equals(m.get("z")))));
            }
        }
        return moving;
    }
    /** 路面光束淡入淡出 */
    private boolean beamsTick(float dt) {
        if (LS == null) return false;
        boolean moving = false;
        for (String slot : Lamp.BEAMS.keySet()) {
            float on = "low".equals(slot) ? onOf("head") : lightOn.containsKey(slot) ? lightOn.get(slot) : 0;
            Float a0 = LS.bc.get(slot);
            float a = a0 == null ? 0 : a0;
            if (Math.abs(a - on) > 0.002f) { a += (on - a) * (1 - (float) Math.exp(-dt * 6)); moving = true; } else a = on;
            LS.bc.put(slot, a);
            LS.beam(slot, a);
        }
        return moving;
    }
    private static float beat(long t) { float x = t % 2000 / 500f, f = x - (int) x; int i = (int) x; return 0.3f + (i == 0 ? 0.7f : 0.5f) * (1 - f) * (1 - f); }
    private void turnState() {
        long t = now();
        for (String s : SIDES) turnOn.put(s, turnHz || bulb.get(s) || (pulsed && t - blinkAt.get(s) < 1600));
        blinking = turnOn.get("L") || turnOn.get("R");
    }
    private static String pressure(Object v, Object u) {
        if ("burst".equals(v)) return "爆胎";
        if ("fault".equals(v)) return "异常";
        if (!(v instanceof Number)) return "—";
        double d = ((Number) v).doubleValue();
        return "psi".equals(u) ? Math.round(d / 6.894757) + " psi" : "kPa".equals(u) ? Math.round(d) + " kPa" : String.format(java.util.Locale.US, "%.1f bar", d / 100);
    }
    private final String[] tyreText = new String[4], tyreTemp = new String[4];
    private void text(Map<String, Object> c) {
        Object gear = c.get("driving.gear"), sp = c.get("driving.speed");
        boolean moving = sp instanceof Number && (((Number) sp).doubleValue() > 0 || "D".equals(gear) || "R".equals(gear));
        Object pw = c.get("power.level");
        out.top(gear == null ? "" : String.valueOf(gear), moving, String.valueOf(moving ? Math.round(((Number) sp).doubleValue()) : 0), POWER.containsKey(String.valueOf(pw)) ? POWER.get(String.valueOf(pw)) : "—",
            "on".equals(pw) || "ready".equals(pw) || "fakeReady".equals(pw));
        for (int i = 0; i < 4; i++) {
            String w = TYRES[i];
            tyreText[i] = pressure(c.get("tyre." + w + ".pressure"), c.get("tyre.displayUnit"));
            Object tt = c.get("tyre." + w + ".temperature");
            tyreTemp[i] = tt instanceof Number ? "胎温 " + Math.round(((Number) tt).doubleValue()) : "";
        }
        stats(c);
        showStatus(statuses(c));
        telltales(c);
        roofState(c);
    }
    private static Double nm(Object v) { return v instanceof Number ? ((Number) v).doubleValue() : null; }
    private void stats(Map<String, Object> c) {
        List<String[]> items = new ArrayList<>();
        Double er = nm(c.get("energy.electricRange")), fr = nm(c.get("energy.fuelRange"));
        boolean has = er != null || fr != null;
        for (String id : S.stats()) {
            String[] row = CarStat.byId(id);
            String name = row == null ? id : row[1];
            switch (id) {
                case "bat": { Double v = nm(c.get("energy.batteryPercent")); items.add(energy(id, "charging".equals(c.get("energy.chargingState")) ? "电量 · 充电中" : "电量", v)); break; }
                case "fuel": items.add(energy(id, "油量", nm(c.get("energy.fuelPercent")))); break;
                case "range": items.add(new String[] { id, "总续航", has ? String.valueOf(Math.round((er != null ? er : 0) + (fr != null ? fr : 0))) : null, "km", null, null }); break;
                case "eRange": items.add(new String[] { id, "纯电续航", er == null ? null : String.valueOf(Math.round(er)), "km", null, null }); break;
                case "fRange": items.add(new String[] { id, "燃油续航", fr == null ? null : String.valueOf(Math.round(fr)), "km", null, null }); break;
                case "temp": { Double v = nm(c.get("ac.outdoorTemperature")); items.add(new String[] { id, "车外", v == null ? null : String.valueOf(Math.round(v)), "°C", null, null }); break; }
                case "inTemp": { Double v = nm(c.get("ac.indoorTemperature")); items.add(new String[] { id, "车内", v == null ? null : String.valueOf(Math.round(v)), "°C", null, null }); break; }
                case "odo": { Double v = nm(c.get("driving.totalDistance")); items.add(new String[] { id, "总里程", v == null ? null : String.valueOf(Math.round(v)), "km", null, null }); break; }
                default: { String[] sv = CarStat.stat(id, c, unitOf); items.add(new String[] { id, name, sv == null ? null : sv[0], sv == null ? "" : sv[1], null, null }); }
            }
        }
        out.stats(items);
    }
    CarStat.UnitOf unitOf = f -> null;
    /** 电量 / 油量：[id, 名字, 数, 单位, 进度条比例, 进度条颜色类] */
    private static String[] energy(String id, String label, Double v) {
        boolean ok = v != null;
        return new String[] { id, label, ok ? String.valueOf(Math.round(v)) : null, "%", String.valueOf(ok ? Math.max(0, Math.min(100, v)) / 100 : 0), !ok ? "" : v < 20 ? "low" : v < 50 ? "mid" : "" };
    }
    private void telltales(Map<String, Object> c) { List<List<CarStat.Item>> t = CarStat.telltales(c); out.tells(t.get(0), t.get(1), portrait); }
    private void sysIcons() { out.sys(CarStat.sys(sysNow)); }

    // ================= 下面那行字 =================
    private static final double LOW_RANGE = 50, LOW_BAT = 20, LOW_FUEL = 10;
    private List<String> gp(List<String> keys) { List<String> k = new ArrayList<>(); for (String x : keys) if (parts != null && parts.containsKey(x)) k.add(x); return k.isEmpty() ? null : k; }
    private List<St> statuses(Map<String, Object> c) {
        List<St> L = new ArrayList<>();
        boolean moving = CarStat.moving(c), anyP = false;
        Map<String, List<String>> tyre = new HashMap<>(), tw = new HashMap<>();
        for (String g : new String[] { "burst", "fault", "low", "high", "warn" }) { tyre.put(g, new ArrayList<>()); tw.put(g, new ArrayList<>()); }
        for (String w : TYRES) {
            Object p = c.get("tyre." + w + ".pressure"), st = c.get("tyre." + w + ".state");
            String g = "burst".equals(p) ? "burst" : "fault".equals(p) || "critical".equals(st) ? "fault" : "low".equals(st) ? "low" : "high".equals(st) ? "high" : st != null && !"normal".equals(st) ? "warn" : "";
            if (p instanceof Number) anyP = true;
            if (!g.isEmpty()) { tyre.get(g).add(TYRE_NAME.get(w)); tw.get(g).add(w); }
        }
        addT(L, 3, tyre.get("burst"), "爆胎", tw.get("burst"));
        addT(L, 3, tyre.get("fault"), "胎压异常", tw.get("fault"));
        if (Boolean.TRUE.equals(c.get("warning.lowVoltageBatteryFault"))) L.add(new St(3, "低压供电异常"));
        if (moving && Boolean.FALSE.equals(c.get("seat.driver.beltFastened"))) L.add(new St(3, "主驾没系安全带"));
        addT(L, 2, tyre.get("low"), "胎压偏低", tw.get("low"));
        addT(L, 2, tyre.get("high"), "胎压偏高", tw.get("high"));
        addT(L, 2, tyre.get("warn"), "胎压异常", tw.get("warn"));
        List<String> open = new ArrayList<>(), ok = new ArrayList<>(), wins = new ArrayList<>(), wk = new ArrayList<>();
        for (Map.Entry<String, String> e : DOORS.entrySet()) if ("open".equals(c.get(e.getValue()))) { open.add(DOOR_NAME.get(e.getValue())); ok.add(e.getKey()); }
        if ("open".equals(c.get("body.hood.state"))) { open.add("前机舱盖"); ok.add("hood"); }
        if ("open".equals(c.get("body.tailgate.state"))) { open.add("尾门"); ok.add("trunk"); }
        if (!open.isEmpty()) { St s = new St(2, String.join("、", open) + "开着"); s.parts = gp(ok); L.add(s); }
        for (String w : TYRES) {
            Object v = c.get("body.windows." + w + ".position");
            if (v instanceof Number && ((Number) v).doubleValue() > 0) { wins.add(TYRE_NAME.get(w) + " " + Math.round(((Number) v).doubleValue()) + "%"); wk.add(parts != null && parts.containsKey("win" + ABBR.get(w)) ? "win" + ABBR.get(w) : "door" + ABBR.get(w)); }
        }
        if (!wins.isEmpty()) { St s = new St(2, "车窗开着：" + String.join("、", wins)); s.parts = gp(wk); L.add(s); }
        else if (Boolean.TRUE.equals(c.get("body.windows.anyOpen"))) L.add(new St(2, "车窗开着"));
        Object sr = c.get("body.sunroof.position");
        if (sr instanceof Number && ((Number) sr).doubleValue() > 0) { St s = new St(2, "天窗开了 " + Math.round(((Number) sr).doubleValue()) + "%"); s.parts = gp(Arrays.asList("sunroof", "sunshade")); L.add(s); }
        if (Boolean.TRUE.equals(c.get("warning.keyBatteryLow"))) L.add(new St(2, "钥匙电池电量低"));
        Object cs = c.get("energy.chargingState"), con = c.get("energy.connectorState");
        Double bat = nm(c.get("energy.batteryPercent")), fuel = nm(c.get("energy.fuelPercent")), er = nm(c.get("energy.electricRange")), fr = nm(c.get("energy.fuelRange"));
        Double range = er != null || fr != null ? (er != null ? er : 0) + (fr != null ? fr : 0) : null;
        List<String> port = gp(Arrays.asList("chargePort"));
        if ("terminated".equals(cs)) { St s = new St(2, "充电中断"); s.parts = port; L.add(s); }
        if (!"charging".equals(cs) && range != null && range < LOW_RANGE) L.add(new St(2, "续航只剩 " + Math.round(range) + " km"));
        else if (!"charging".equals(cs) && bat != null && bat < LOW_BAT) L.add(new St(2, "电量低 " + Math.round(bat) + "%"));
        if (fuel != null && fuel < LOW_FUEL && !(range != null && range < LOW_RANGE)) L.add(new St(2, "油量低 " + Math.round(fuel) + "%"));
        St chg = null;
        if ("charging".equals(cs)) {
            Object rt = c.get("energy.charging.remainingTime");
            String left = "";
            if (rt instanceof double[] && ((double[]) rt).length == 2 && ((double[]) rt)[0] * 60 + ((double[]) rt)[1] > 0) { double[] a = (double[]) rt; left = " · 还要 " + (a[0] != 0 ? CarStat.fmt(a[0]) + " 小时 " : "") + CarStat.fmt(a[1]) + " 分"; }
            chg = new St(1, "充电中" + (bat != null ? " " + Math.round(bat) + "%" : "") + left);
        } else if ("finished".equals(cs)) chg = new St(1, "已充满");
        else if ("vtol".equals(con)) chg = new St(1, "对外放电中");
        else if ("ac".equals(con) || "dc".equals(con) || "acDc".equals(con)) chg = new St(1, "插着充电枪");
        if (chg != null) { chg.parts = port; L.add(chg); }
        boolean warn = false;
        for (St s : L) if (s.lv >= 2) warn = true;
        if (!c.isEmpty() && !warn) L.add(new St(0, "车门车窗都关好了" + (anyP ? " · 胎压正常" : "")));
        List<St> sorted = new ArrayList<>(L);
        java.util.Collections.sort(sorted, (x, y) -> y.lv - x.lv);
        return sorted;
    }
    private void addT(List<St> L, int lv, List<String> names, String t, List<String> ws) {
        if (names.isEmpty()) return;
        St s = new St(lv, String.join("、", names) + t);
        if (wheels != null) s.tyres = new ArrayList<>(ws);
        L.add(s);
    }
    private void showStatus(List<St> L) {
        stList = L;
        if (L.size() < 2) stOpen = false;
        out.status(L, stOpen);
    }
    /** 界面点了那行字：不止一条展开 / 收起列表，只有一条有对应位置就转过去 */
    void statusTap() {
        if (stList.size() > 1) { stOpen = !stOpen; showStatus(stList); }
        else if (!stList.isEmpty() && stList.get(0).go()) lookAt(stList.get(0));
    }
    /** 列表里点了第 i 条：镜头转过去，列表收起 */
    void statusPick(int i) {
        if (i < 0 || i >= stList.size()) return;
        St s = stList.get(i);
        if (s.go() && lookAt(s)) { stOpen = false; showStatus(stList); }
    }
    void statusClose() { if (stOpen) { stOpen = false; showStatus(stList); } }

    // ================= 深浅色、背景、车模那行 =================
    private void modelUI() {
        if (rec == null) return;
        String[] tips = new String[3];
        Object[][] r = { { Mt != null && Mt.hasPaint, "车漆", "车身" }, { Mt != null && !Mt.rims.isEmpty(), "轮毂", "轮毂" }, { Mt != null && !Mt.cals.isEmpty(), "卡钳", "卡钳" } };
        for (int i = 0; i < 3; i++) tips[i] = Mt == null || (Boolean) r[i][0] ? "" : "这个车模还没标出" + r[i][1] + "，换色不生效（在网页版的「调整车模」里点一下" + r[i][2] + "标上）";
        out.modelUI(rec.name, rec.broken ? "导入的车模读不出来，先用默认车模" : rec.credit, rec.builtin && !rec.broken, tips);
    }
    private void homeView() { HOME[0] = S.num("viewAz"); HOME[1] = S.num("viewEl"); TOP[0] = S.num("dAz"); TOP[1] = S.num("dEl"); }
    private void theme(boolean d) {
        if (d == dark) return;
        dark = d;
        out.dark(d);
        if (root != null) app.invalidate();
    }
    private void look() {
        app.renderer.toneMappingExposure = (1 - 0.15f * themeK) * expK;
        keyLight.intensity = std ? 0 : 1.3f - 0.6f * themeK;
        Mt.paint.envMapIntensity = (0.85f - 0.15f * themeK) * envK;
    }
    /** 背景：三套渐变（跟着深浅色）或者自己的图片 + 压暗 */
    void background() {
        if (app == null) return;
        String b = S.str("bg");
        app.bg.kind = "warm".equals(b) ? 1 : "cool".equals(b) ? 2 : "image".equals(b) ? 3 : 0;
        app.bg.dim = S.num("bgDim");
        if (app.bg.kind == 3 && app.bg.image == null && !bgImgTried) {
            bgImgTried = true;
            Bitmap bm = Background.load(ctx);
            if (bm != null) { Tex t = Tex.fromBitmap(bm); t.srgb = false; t.generateMipmaps = false; t.minFilter = t.magFilter = GLES30.GL_LINEAR; bm.recycle(); app.bg.image = t; }
        }
        if (app.bg.kind != 3 && app.bg.image != null) { app.bg.image.dispose(); app.bg.image = null; bgImgTried = false; }
        app.invalidate();
    }
    /** 换了背景图片：重新读 */
    void bgChanged() { if (app != null && app.bg.image != null) { app.bg.image.dispose(); app.bg.image = null; } bgImgTried = false; background(); }

    // ================= 每帧 =================
    private float bgK = -1;
    @Override public int update(float dt) {
        if (root == null) return 0;
        boolean moving = false;
        for (Map.Entry<String, Float> e : want.entrySet()) {
            String k = e.getKey();
            float b = pkey != null ? 0 : e.getValue(), a = cur.containsKey(k) ? cur.get(k) : b; // 调车牌时门窗、盖都按关着摆
            if (Math.abs(a - b) > 0.002f) { a += (b - a) * (1 - (float) Math.exp(-dt * 5)); moving = true; shadowDirty = true; } else a = b;
            if (!cur.containsKey(k) || a != cur.get(k)) shadowDirty = true;
            cur.put(k, a);
        }
        boolean chgAnim = false;
        if (chg != null) {
            int ca = chg.tick(dt, themeK);
            if (ca == 2) { moving = true; shadowDirty = true; } else if (ca == 1) chgAnim = true;
            if (chg.wanted() != chgOn) { // 插上枪：镜头转到看充电桩的角度；拔掉：转回来（手指转过车就不管）
                boolean was = chgNow();
                chgOn = chg.wanted(); chgOff = false;
                if (chgOn || was) { vHoming = true; vVaz = 0; }
            }
            float cw = chg.wanted() ? 1 : 0; // 镜头往外拉一点、挪一点，把充电桩和光环也装进来
            if (Math.abs(camK - cw) > 0.002f) { camK += (cw - camK) * (1 - (float) Math.exp(-dt * 2.4)); moving = true; } else camK = cw;
        }
        if (shadowDirty && keyLight.castShadow) { app.renderer.shadowNeedsUpdate = true; shadowDirty = false; }
        for (Map.Entry<String, Part> e : parts.entrySet()) {
            Part p = e.getValue();
            float v = c(e.getKey());
            if (p.slide != null) p.pivot.position.copy(p.slide).multiplyScalar(ease(v));
            else if (p.pivot != null) p.pivot.quaternion.setFromAxisAngle(p.axis, p.angle * ease(v));
            else for (int i = 0; i < p.meshes.size(); i++) p.meshes.get(i).position.y = p.y0[i] - p.drop * v;
        }
        float tk = dark ? 1 : 0;
        if (Math.abs(themeK - tk) > 0.002f) { themeK += (tk - themeK) * (1 - (float) Math.exp(-dt * 7)); moving = true; } else themeK = tk;
        // 背景深色那层：0.6 秒（同 CSS 的 transition: opacity .6s ease）
        if (bgK < 0) bgK = tk;
        if (bgK != tk) { bgK = tk > bgK ? Math.min(tk, bgK + dt / 0.6f) : Math.max(tk, bgK - dt / 0.6f); moving = true; }
        app.bg.darkK = cssEase(bgK);
        look();
        if (introT < 1) {
            if (introWait > 0) introWait--;
            else introT = Math.min(1, introT + dt / INTRO[3]);
            moving = true;
        }
        float wf = introT > 0.35f && introT < 0.95f ? (float) Math.sin((introT - 0.35f) / 0.6f * Math.PI) : 0;
        if (welT < 1) { welT = Math.min(1, welT + dt / 1.1f); wf = Math.max(wf, (float) Math.sin(welT * Math.PI)); moving = true; }
        turnState();
        boolean lightAnim = lightsTick(wf);
        if (beamsTick(dt)) moving = true;
        if (finishK < 1) { finishK = Math.min(1, finishK + dt / 0.6f); tint(finishK); moving = true; }
        float sw = showOn || asideOn ? 1 : 0, aw = showOn && !preview ? 1 : 0;
        if (Math.abs(showK - sw) > 0.002f) { showK += (sw - showK) * (1 - (float) Math.exp(-dt * 4.5)); moving = true; } else showK = sw;
        if (Math.abs(angK - aw) > 0.002f) { angK += (aw - angK) * (1 - (float) Math.exp(-dt * 4.5)); moving = true; } else angK = aw;
        lookTick();
        float qt = topNow() || preview && previewTop ? 1 : 0;
        Frame pf = plateV != null ? plateV : pin != null ? pin.v : null;
        float[] pvv = pf != null ? pf.view : null;
        V3 ft = pvv != null ? pf.at : tour != null ? tour.at : doorNow() ? doorV.at : null;
        float fz = pvv != null ? pf.zoom : tour != null ? tour.zoom : doorNow() ? doorV.zoom : focusZ, fw = ft != null ? 1 : 0;
        if (ft != null) {
            if (focusAt == null || focusK < 0.002f) { focusAt = ft.clone(); focusZ = fz; }
            else if (focusAt.distanceToSquared(ft) > 1e-6f) { focusAt.lerp(ft, 1 - (float) Math.exp(-dt * 2.4)); moving = true; } else focusAt.copy(ft);
        }
        if (Math.abs(focusZ - fz) > 0.001f) { focusZ += (fz - focusZ) * (1 - (float) Math.exp(-dt * 2.4)); moving = true; } else focusZ = fz;
        float fl = tour != null && pvv == null ? 1 : 0.85f;
        if (Math.abs(focusL - fl) > 0.001f) { focusL += (fl - focusL) * (1 - (float) Math.exp(-dt * 2.4)); moving = true; } else focusL = fl;
        if (Math.abs(focusK - fw) > 0.002f) { focusK += (fw - focusK) * (1 - (float) Math.exp(-dt * 2.4)); moving = true; } else focusK = fw;
        if (Math.abs(topK - qt) > 0.002f) { topK += (qt - topK) * (1 - (float) Math.exp(-dt * 2.4)); moving = true; } else topK = qt;
        labelsOn = S.bool("labels") && introT > 0.8f && (showK < 0.05f || asideOn);
        float[] hm = home();
        if (topNow() && !vHoming && topLast != 0 && now() - topLast > 6000 && (Math.abs(wrap(hm[0] - vAz)) > 0.01f || Math.abs(hm[1] - vEl) > 0.01f)) vHoming = true;
        if (vHoming) {
            float da = wrap(hm[0] - vAz), de = hm[1] - vEl, k2 = 1 - (float) Math.exp(-dt * (qt > 0 || topK > 0.01f || focusK > 0.01f || ft != null ? 2.4 : 6));
            vAz += da * k2; vEl += de * k2;
            if (Math.abs(da) < 0.002f && Math.abs(de) < 0.002f) { vAz = hm[0]; vEl = hm[1]; vHoming = false; } else moving = true;
        } else if (Math.abs(vVaz) > 0.0004f) {
            vAz += vVaz * dt * 60;
            vVaz *= Math.exp(-dt * 3.2);
            moving = true;
        } else vVaz = 0;
        place();
        if (moving || lightAnim) movedAt = now();
        still = !moving && mirror == null; // 开着地面反射时不用（反射里的灯也要跟着变）
        return moving ? 60 : lightAnim || chgAnim ? 30 : blinking ? 8 : 0;
    }
    /** CSS 的 ease（cubic-bezier(.25, .1, .25, 1)） */
    private static float cssEase(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        float lo = 0, hi = 1;
        for (int i = 0; i < 20; i++) { float t = (lo + hi) / 2, bx = bez(t, 0.25f, 0.25f); if (bx < x) lo = t; else hi = t; }
        return bez((lo + hi) / 2, 0.1f, 1f);
    }
    private static float bez(float t, float p1, float p2) { float u = 1 - t; return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t; }

    // ================= 灯的点亮层先做好 =================
    private void warmLamps(final int g) {
        app.cancel(warmT);
        if (LS == null) return;
        if (introT < 1) for (String r : WELCOME_L) if (LS.has(r) && !LS.built(r)) LS.get(r);
        final List<String> beams = new ArrayList<>(Lamp.BEAMS.keySet());
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (g != gen || LS == null || scene == null) return;
            long t = now();
            if (introT < 1 || t - touchAt < 1500 || t - movedAt < 800) { warmT = app.later(500, step[0]); return; }
            String r = null;
            for (String x : LIGHTS) if (LS.has(x) && !LS.built(x)) { r = x; break; }
            if (r != null) LS.get(r);
            else if (!beams.isEmpty()) LS.warmBeam(beams.remove(0));
            else return;
            warmT = app.later(300, step[0]);
        };
        warmT = app.later(1200, step[0]);
    }

    // ================= 镜头：胎压异常看轮子、开门看门、点那行字看那一条 =================
    private void tyreTour() {
        boolean ch = false;
        for (String w : TYRES) {
            if (tyreBad.get(w) > 0) { if (!tyreSeen.containsKey(w)) { tyreSeen.put(w, 1); tyreNew.add(w); tyreOff = false; tyreAt = now(); ch = true; } }
            else if (tyreSeen.containsKey(w)) { tyreSeen.remove(w); ch = true; }
        }
        if (root != null && ch) app.invalidate();
    }
    private boolean canLook() { return root != null && introT >= 1 && !showOn && pkey == null && visibleNow; }
    boolean visibleNow = true;
    private boolean canTour() { return wheels != null && canLook(); }
    private void lookTick() {
        long t = now();
        List<String> bad = new ArrayList<>();
        for (String w : TYRES) if (tyreBad.get(w) > 0) bad.add(w);
        if (pin != null) {
            if (pin.tyres != null) { List<String> l = new ArrayList<>(); for (String w : pin.tyres) if (tyreBad.get(w) > 0) l.add(w); pin.tyres = l; }
            boolean gone;
            if (pin.tyres != null) gone = pin.tyres.isEmpty();
            else { gone = true; for (String k : pin.parts) if (want.containsKey(k) && want.get(k) > 0.02f) gone = false; }
            if (gone) { pin = null; vHoming = true; vVaz = 0; }
        }
        if (((!bad.isEmpty() && tour == null) || doorDue != 0) && !canLook()) doorWake(1000);
        if (doorDue != 0 && t >= doorDue && canLook()) { doorDue = 0; doorFrame(); }
        if (doorEnd != 0 && t >= doorEnd) { doorEnd = 0; if (doorV != null) { doorV = null; vHoming = true; vVaz = 0; } }
        List<String> list = !canTour() ? new ArrayList<>() : pin != null ? (pin.tyres != null ? pin.tyres : new ArrayList<>())
            : !bad.isEmpty() && !tyreOff && !topOn && !preview && !(doorAuto() && doorAt > tyreAt + 1500) ? bad : new ArrayList<>();
        List<String> nn = new ArrayList<>();
        for (String w : tyreNew) if (tyreBad.get(w) > 0 && !(tour != null && tour.w.equals(w))) nn.add(w);
        tyreNew.clear(); tyreNew.addAll(nn);
        if (list.isEmpty()) { if (tour != null) { tour = null; app.cancel(tourTimer); vHoming = true; vVaz = 0; } return; }
        int i = tour != null ? list.indexOf(tour.w) : -1;
        if (i < 0) { String n = next(list); setTour(n != null ? n : list.get(0), list.size()); }
        else if (list.size() > 1 && t > tour.until) { String n = next(list); setTour(n != null ? n : list.get((i + 1) % list.size()), list.size()); }
    }
    private String next(List<String> list) {
        for (int j = 0; j < tyreNew.size(); j++) if (list.contains(tyreNew.get(j))) return tyreNew.remove(j);
        return null;
    }
    private void setTour(String w, int n) {
        V3 at = wheels.get(w).c.clone().applyMatrix4(root.matrixWorld);
        app.cancel(tourTimer);
        tour = new Tour();
        tour.w = w; tour.at = at; tour.zoom = TYRE_ZOOM; tour.until = now() + TOUR_HOLD + 1200; tour.view = new float[] { (float) Math.atan2(at.x, at.z), 0.3f };
        vHoming = true; vVaz = 0;
        if (n > 1) tourTimer = app.later(TOUR_HOLD + 1250, () -> { if (root != null) app.invalidate(); });
    }
    private void stopLook() {
        plateV = null;
        if (chgOn) chgOff = true;
        if (tour != null || pin != null) { tour = null; pin = null; app.cancel(tourTimer); }
        tyreOff = true;
        if (doorV != null || doorDue != 0) { doorV = null; doorDue = doorEnd = 0; }
    }
    private boolean lookAt(St go) {
        if (root == null || !canLook()) return false;
        if (go.tyres != null) {
            List<String> l = new ArrayList<>();
            for (String w : go.tyres) if (wheels != null && wheels.containsKey(w)) l.add(w);
            if (l.isEmpty()) return false;
            pin = new Pin(); pin.tyres = l; tour = null;
        } else {
            Frame f = frameOf(go.parts);
            if (f == null) return false;
            pin = new Pin(); pin.parts = go.parts; pin.v = f; tour = null;
        }
        vHoming = true; vVaz = 0; app.invalidate();
        return true;
    }
    private void doorWatch(Map<String, Object> c) {
        List<String> open = new ArrayList<>();
        for (Map.Entry<String, String> e : OPENS.entrySet()) if (parts.containsKey(e.getKey()) && "open".equals(c.get(e.getValue()))) open.add(e.getKey());
        for (String k : new ArrayList<>(doorSeen.keySet())) if (!open.contains(k)) doorSeen.remove(k);
        List<String> fresh = new ArrayList<>();
        for (String k : open) if (!doorSeen.containsKey(k)) fresh.add(k);
        for (String k : fresh) doorSeen.put(k, 1);
        doorOpen = open;
        if (!fresh.isEmpty()) { doorAt = doorBoot ? 0 : now(); doorDue = now() + 900; doorEnd = 0; doorWake(950); }
        else if (open.isEmpty() && doorV != null && doorEnd == 0) { doorEnd = now() + 1500; doorWake(1550); }
        doorBoot = false;
    }
    private void doorWake(long ms) { app.cancel(doorTimer); doorTimer = app.later(ms, () -> { if (root != null) app.invalidate(); }); }
    /** 一个画面框住这些开着的部件（同 frameOf） */
    private Frame frameOf(List<String> keys) {
        if (parts == null || keys == null || keys.isEmpty()) return null;
        float sx = Math.sin(HOME[0]) < 0 ? -1 : 1, el = 0;
        V3 sum = new V3(), at = new V3();
        int n = 0;
        // 按关着的样子取位置
        List<Object[]> keep = new ArrayList<>();
        for (Part p : parts.values()) {
            if (p.pivot != null) { keep.add(new Object[] { p.pivot, p.pivot.position.clone(), p.pivot.quaternion.clone() }); if (p.slide != null) p.pivot.position.set(0, 0, 0); else p.pivot.quaternion.identity(); }
            else if (p.meshes != null && p.y0 != null) for (int i = 0; i < p.meshes.size(); i++) { Mesh m = p.meshes.get(i); keep.add(new Object[] { m, m.position.clone(), m.quaternion.clone() }); m.position.y = p.y0[i]; }
        }
        root.updateMatrixWorld(true);
        try {
            for (String k : keys) {
                Part p = parts.get(k);
                if (p == null) continue;
                Box3 b = new Box3();
                for (Mesh m : p.meshes) b.expandByObject(m);
                if (b.isEmpty()) continue;
                V3 cc = b.getCenter(new V3()), d;
                float e;
                if ("hood".equals(k)) { d = V(sx * 0.5, 0, 1); e = 0.5f; }
                else if ("trunk".equals(k)) { d = V(sx * 0.5, 0, -1); e = 0.45f; }
                else if ("sunroof".equals(k) || "sunshade".equals(k)) { d = V(sx * 0.6, 0, -1); e = 0.85f; }
                else if (k.startsWith("door") || k.startsWith("win")) { d = V(p.side != 0 ? p.side : (cc.x > 0 ? 1 : -1), 0, -0.8); e = 0.34f; }
                else { d = V(cc.x, 0, cc.z); if (d.lengthSq() < 1e-6f) d.set(sx, 0, 0); e = 0.3f; }
                sum.add(d.normalize()); at.add(cc);
                el = Math.max(el, e);
                n++;
            }
        } finally {
            for (Object[] x : keep) { ((Obj3D) x[0]).position.copy((V3) x[1]); ((Obj3D) x[0]).quaternion.copy((com.x3card.car3d.gl.Quat) x[2]); }
            root.updateMatrixWorld(true);
        }
        if (n == 0) return null;
        float len = sum.length(), spread = 1 - len / n;
        Frame f = new Frame();
        f.keys = new ArrayList<>(keys); f.at = at.multiplyScalar(1f / n); f.want = 0.22f * (1 - spread);
        f.view = new float[] { len > 0.15f ? (float) Math.atan2(sum.x, sum.z) : (float) Math.PI, Math.min(0.9f, el + spread * 0.45f) };
        f.zoom = fitZoom(f.view, f.at, f.want, true);
        return f;
    }
    private void doorFrame() {
        Frame f = frameOf(doorOpen);
        if (f == null) return;
        doorV = f;
        vHoming = true; vVaz = 0;
        app.invalidate();
    }
    private boolean doorAuto() { return doorV != null && !topOn && !preview; }
    private boolean doorNow() { return pin == null && tour == null && doorAuto(); }
    /** 看轮子、看门时最多拉近多少：整车还在顶栏和底下数据之间 */
    private float fitZoom(float[] v, V3 at, float want, boolean wide) {
        if (fitA == null || camera == null || root == null) return want;
        float k0 = vAz, k1 = vEl, k2 = focusK, k4 = focusZ, k5 = introT, k6 = showK, k7 = angK, k9 = topK, k10 = focusL;
        V3 k3 = focusAt;
        List<V3> pts = new ArrayList<>();
        float hw = dimsHw + (wide ? 0.4f : 0.05f), lo = -0.4f, hi = want;
        for (int i = 0; i < 8; i++) pts.add(V((i & 1) != 0 ? hw : -hw, (i & 2) != 0 ? dimsH : 0, (i & 4) != 0 ? dimsHl : -dimsHl).applyMatrix4(root.matrixWorld));
        vAz = v[0]; vEl = v[1]; focusK = 1; focusAt = at; introT = 1; showK = angK = 0; topK = 0; focusL = 0.85f;
        if (!zoomOk(want, pts)) { for (int it = 0; it < 14; it++) { float m = (lo + hi) / 2; if (zoomOk(m, pts)) lo = m; else hi = m; } want = lo; }
        vAz = k0; vEl = k1; focusK = k2; focusAt = k3; focusZ = k4; introT = k5; showK = k6; angK = k7; topK = k9; focusL = k10;
        place();
        return want;
    }
    private boolean zoomOk(float z, List<V3> pts) {
        focusZ = z; place();
        float mx = 0, mn = 1, my = -1;
        for (V3 c : pts) { V3 q = c.clone().project(camera); mx = Math.max(mx, Math.abs(q.x)); my = Math.max(my, q.y); mn = Math.min(mn, q.y); }
        return mx <= fitA[1] && my <= fitA[2] + fitA[0] && mn >= fitA[2] - fitA[0];
    }
    private boolean topNow() { return topOn && !preview && pkey == null && tour == null && !(pin != null && pin.v != null); }
    private float[] home() {
        if (plateV != null) return plateV.view;
        if (pin != null && pin.v != null) return pin.v.view;
        if (tour != null) return tour.view;
        if (topNow() || preview && previewTop) return TOP;
        return doorNow() ? doorV.view : chgNow() ? chgV : HOME;
    }
    private static float ease(float t) { return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2; }
    private static float easeOut(float t) { return 1 - (float) Math.pow(1 - t, 3); }
    private static float wrap(float a) { while (a > Math.PI) a -= Math.PI * 2; while (a < -Math.PI) a += Math.PI * 2; return a; }
    private void place() {
        float r = 1 - easeOut(introT), qk = ease(topK), sk = showK * showK * (3 - 2 * showK), ak = angK * angK * (3 - 2 * angK), ok = sk;
        float fov = S.num("viewFov");
        float az = vAz + r * introRot * INTRO[0], el = vEl + r * introRot * INTRO[1];
        float d = vDist * (1 + r * INTRO[2]) * (1 - qk) + topDist * qk;
        if (sk > 0) { az += wrap(SHOW[0] - az) * ak; el += (SHOW[1] - el) * ak; d *= 1 + (portrait ? 0.3f : 0.42f) * sk; }
        float ty = dimsH * (0.357f - 0.157f * qk), ce = (float) Math.cos(el);
        aim.set(0, ty, 0);
        if (camK > 0) { float ck = ease(camK) * (1 - qk); aim.x += chgAim.x * ck; aim.z += chgAim.z * ck; d *= 1 + (chgDk - 1) * ck; }
        if (focusK > 0 && focusAt != null) { float fk = ease(focusK); aim.lerp(focusAt, fk * focusL); d *= 1 - focusZ * fk; }
        if (camera.fov != fov) camera.fov = fov;
        if (Mt != null && Mt.fb != 0) { float px = 0.00625f * H / (2 * d * (float) Math.tan(fov * Math.PI / 360)); Mt.flake.v = Mt.fb * Math.max(0, Math.min(1, (px - 1.5f) / 1.5f)); }
        else if (Mt != null) Mt.flake.v = 0f;
        camera.position.set(aim.x + Math.sin(az) * ce * d, aim.y + Math.sin(el) * d, aim.z + Math.cos(az) * ce * d);
        camera.lookAt(aim);
        camera.updateMatrixWorld(true);
        float showY = portrait ? (H / 2 - offY0) - (remPx * 1.15f + H * 0.36f - remPx * 0.16f) / 2 : 0;
        camera.setViewOffset(W, H, sk * 0.2f / Math.max(1, sk) * (portrait ? 0 : W), offY0 + ok * showY, W, H);
    }

    /** 画完一帧：胎压标签跟着轮子走；在车另一边的藏起来；镜头拉近时标签停在状态字上面 */
    private final V3 pv = new V3();
    private final float[] tagXY = new float[8];
    private final String[] tagCls = new String[4];
    /** 载入车况页到画出车花了多久（第一次还带上从进程启动算的） */
    private long openAt;
    private boolean opening;
    private static boolean firstOpen = true;
    private void openDone() {
        opening = false;
        String s = "载入车况页到画出车 " + (now() - openAt) + " 毫秒（着色器现编译 " + com.x3card.car3d.gl.Prog.compiled + " 个 " + com.x3card.car3d.gl.Prog.compileMs + " 毫秒，读缓存 " + com.x3card.car3d.gl.Prog.loaded + " 个）";
        if (firstOpen) { firstOpen = false; s = "打开：进程启动后 " + (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) + " 毫秒画出车；" + s; }
        Log.w("car3d", s);
    }
    @Override public void after() {
        if (opening && root != null) openDone();
        importDone();
        if (geoKey != null && root != null) { GeoCache.save(ctx, geoKey, gltf, root); geoKey = null; }
        roofPlace();
        if (wheels == null) return;
        float cx = camera.position.x, rm = app.rem();
        float lim = tagMax > 0 ? tagMax : H;
        for (Map.Entry<String, Wheel> e : wheels.entrySet()) {
            Wheel wh = e.getValue();
            boolean far = cx * wh.side < -0.25f * vDist;
            int bad = tyreBad.containsKey(e.getKey()) ? tyreBad.get(e.getKey()) : 0;
            tagCls[wh.idx] = (bad == 2 ? "bad" : bad > 0 ? "warn" : "") + (far || !labelsOn ? " off" : "");
            if (far) continue;
            pv.copy(wh.at).applyMatrix4(root.matrixWorld).project(camera);
            tagXY[wh.idx * 2] = (pv.x + 1) / 2 * W;
            tagXY[wh.idx * 2 + 1] = Math.min((1 - pv.y) / 2 * H + rm * 0.38f, lim);
        }
        out.tyres(tagXY.clone(), tagCls.clone(), tyreText.clone(), tyreTemp.clone());
    }
    /** 界面量好下面那行字的上沿（标签下沿最多到它：同网页版，往上让 0.22rem） */
    void setTagMax(float y) { tagMax = app != null ? y - app.rem() * 0.22f : y; }

    // ================= 车顶上方的空调键 =================
    boolean acOk;
    private void roofState(Map<String, Object> c) {
        boolean on = Boolean.TRUE.equals(c.get("ac.power"));
        if (roofWant != null && roofWant == on) { roofWant = null; app.cancel(roofTimer); }
        roofOn = roofWant != null ? roofWant : on;
    }
    private boolean roofOn;
    /** 界面点了空调键 */
    void roofTap() {
        if (!acOk || roofWant != null) return;
        final boolean to = !Boolean.TRUE.equals(carNow.get("ac.power"));
        roofWant = to;
        roofState(carNow);
        app.cancel(roofTimer);
        roofTimer = app.later(4000, () -> { roofWant = null; roofState(carNow); app.invalidate(); });
        out.acSet(to);
        app.invalidate();
    }
    /** 空调没开成（主线程报回来） */
    void roofFail(String msg) {
        roofWant = null; app.cancel(roofTimer); roofState(carNow);
        if (msg != null) out.toast(msg, 3500);
        app.invalidate();
    }
    private void roofPlace() {
        boolean ok = root != null && acOk && introT > 0.8f && (showK < 0.05f || asideOn);
        float x = 0, y = 0;
        if (ok) ok = (camera.position.y - aim.y) / Math.max(1e-6f, camera.position.distanceTo(aim)) > 0.82f;
        if (ok) {
            if (wheels != null) { for (Wheel w : wheels.values()) { pv.copy(w.c).applyMatrix4(root.matrixWorld).project(camera); x += (pv.x + 1) / 8 * W; y += (1 - pv.y) / 8 * H; } }
            else { pv.set(0, dimsH * 0.5f, 0).applyMatrix4(root.matrixWorld).project(camera); x = (pv.x + 1) / 2 * W; y = (1 - pv.y) / 2 * H; }
            ok = pv.z < 1 && x > remPx * 0.4f && x < W - remPx * 0.4f && y > remPx * 1.1f && y < (tagMax > 0 ? tagMax : H) - remPx * 0.2f;
        }
        out.roof(acOk, ok, x, y, roofOn, roofWant != null);
    }

    // ================= 充电桩（Charger 画；插着枪才显示） =================
    private Charger chg;
    /** 镜头装下充电桩、光环的程度（0～1）、那时镜头远多少倍、看的点往哪挪 */
    private float camK, chgDk = 1;
    private final V3 chgAim = new V3();
    /** 插着枪（chgOn）时镜头看充电桩的角度（chgV）；手指转过车（chgOff）就不再跟着，直到下次插枪 */
    private boolean chgOn, chgOff;
    private final float[] chgV = { -0.72f, 0.2f };
    private boolean chgNow() { return chgOn && !chgOff && chg != null && !topOn && !preview && pkey == null; }
    /**
     * 充电枪插在哪（车身表面上的点、那里朝外的方向）：绑定了充电盖的，插在盖子后面的车身上（盖子最薄的方向当朝外，从外面朝里打射线找车身）；
     * 没绑定的，从车右边横着往里打射线，找右后轮上方的车身表面（后翼子板），打到车窗、太靠里、朝向不对的往下挪着再找
     */
    private V3[] chargeSpot(JSONObject pc) {
        List<Object[]> keep = restPose(); // 门、盖正开着也按关着的样子找
        try {
            Map<Mesh, Boolean> skip = new IdentityHashMap<>();
            for (Mesh m : mrefs(pc.opt("wheels"))) skip.put(m, true);
            Part lid = parts.get("chargePort");
            if (lid != null) {
                Box3 b = new Box3();
                for (Mesh m : lid.meshes) { b.expandByObject(m); skip.put(m, true); }
                V3 c = b.getCenter(new V3()), s = b.getSize(new V3()), n;
                if (s.x <= s.y && s.x <= s.z) n = V(c.x >= 0 ? 1 : -1, 0, 0);
                else if (s.z <= s.y) n = V(0, 0, c.z >= 0 ? 1 : -1);
                else n = V(0, 1, 0);
                V3 o = c.clone().addScaled(n, 0.5f);
                Box3 strip = new Box3().expandByPoint(o).expandByPoint(c.clone().addScaled(n, -0.35f));
                strip.min.addScaled(V(1, 1, 1), -0.02f); strip.max.addScaled(V(1, 1, 1), 0.02f);
                Ray.Hit h = castCar(carTris(strip, skip), o, n.clone().negate(), 0.85f);
                if (h != null) return new V3[] { h.point.clone(), outward(h.normal, n) };
                return new V3[] { c.addScaled(n, -0.015f), n };
            }
            V3 wc = wheels.get("rearRight").c;
            float z = wc.z - wheelR * 0.25f, top = 2 * wheelR, y0 = top + (dimsH - top) * 0.32f;
            Box3 strip = new Box3(V(-dimsHw - 1.05f, top, z - 0.3f), V(0.05f, y0 + 0.02f, z + 0.3f));
            List<Object[]> tl = carTris(strip, skip);
            for (float lim : new float[] { -0.75f, -0.45f }) // 先找差不多竖着的车身侧面，找不到再放宽（跑车后翼子板鼓得厉害）
                for (float dz : new float[] { 0, -0.12f, 0.12f, -0.24f, 0.24f }) for (float y = y0; y >= top + 0.04f; y -= 0.03f) {
                    Ray.Hit h = castCar(tl, V(-dimsHw - 1, y, z + dz), V(1, 0, 0), dimsHw + 1);
                    if (h == null) continue;
                    V3 n = outward(h.normal, V(-1, 0, 0));
                    if (n.x < lim && h.point.x < -0.5f * dimsHw) return new V3[] { h.point.clone(), n };
                }
            return new V3[] { V(-dimsHw, y0, z), V(-1, 0, 0) }; // 一处都没找到（模型很怪）：按外框兜底
        } finally { restore(keep); }
    }
    private static V3 outward(V3 n, V3 want) { V3 r = n.clone().normalize(); return r.dot(want) < 0 ? r.negate() : r; }
    /** 车上看得见、挡得住射线的零件在 strip 里的三角形（车轮、玻璃这类透明的、skip 里的不算） */
    private List<Object[]> carTris(Box3 strip, Map<Mesh, Boolean> skip) {
        List<Object[]> l = new ArrayList<>();
        root.updateMatrixWorld(true);
        for (Mesh m : d3.meshes) {
            if (m.parent == null || !inScene(m) || m.geometry == null || m.material == null || skip.containsKey(m)) continue;
            if (m.material.transparent && m.material.opacity < 0.9f) continue;
            if (!m.geometry.boundingBox().clone().applyMatrix4(m.matrixWorld).intersectsBox(strip)) continue;
            int[] t = Ray.trisIn(m, strip);
            if (t.length > 0) l.add(new Object[] { m, t });
        }
        return l;
    }
    private Ray.Hit castCar(List<Object[]> l, V3 o, V3 d, float far) {
        ray.set(o, d);
        Ray.Hit best = null;
        for (Object[] x : l) {
            int[] t = (int[]) x[1];
            Ray.Hit h = ray.intersectMesh((Mesh) x[0], t, t.length);
            if (h != null && h.dist <= far && (best == null || h.dist < best.dist)) best = h;
        }
        return best;
    }
    /** 装下车、充电桩、光环时镜头远多少倍（相对只装车），看的点挪到它们合起来的外框中间（只挪水平方向） */
    private void chgFit(List<V3> corners, float aw, float ah) {
        chgDk = 1; chgAim.set(0, 0, 0);
        if (chg == null) return;
        chgV[0] = chg.viewAz(Math.max(0.35f, Math.min(1.2f, Math.abs(wrap(HOME[0]))))); chgV[1] = HOME[1];
        List<V3> all = new ArrayList<>(corners);
        all.addAll(chg.points());
        float x0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, z0 = x0, z1 = x1;
        for (V3 q : all) { x0 = Math.min(x0, q.x); x1 = Math.max(x1, q.x); z0 = Math.min(z0, q.z); z1 = Math.max(z1, q.z); }
        chgAim.set((x0 + x1) / 2, 0, (z0 + z1) / 2);
        float d0 = vDist, t0 = topDist, k0 = camK;
        camK = 1;
        chg.ringAt(chgV[0]);
        float dc = fit(chgV, 0, all, aw * 0.88f, ah) / S.num("viewZoom"); // 左右多留点地方：充电桩别压到两边的车况图标
        camK = k0; vDist = d0; topDist = t0;
        chgDk = dc / vDist;
    }

    // ================= 尺寸 =================
    @Override public void resize(float w, float h, boolean p) {
        boolean ch = portrait != p || W != w || H != h;
        W = w; H = h; portrait = p;
        if (ch) telltales(carNow);
        if (camera == null) return;
        float r = remPx = app.rem(), padT = (p ? 2.3f : 1.05f) * r, padB = (p ? 2.75f : 2.0f) * r, cy = (padT + h - padB) / 2;
        camera.aspect = w / h;
        offY0 = h / 2 - cy;
        camera.setViewOffset(w, h, 0, offY0, w, h);
        float ah = (h - padT - padB) / h * 0.92f;
        float it0 = introT, sk0 = showK, ak0 = angK, qk0 = topK, az = vAz, el0 = vEl;
        introT = 1; showK = angK = 0;
        List<V3> corners = new ArrayList<>(), tags = new ArrayList<>();
        for (int i = 0; i < 8; i++) corners.add(V((i & 1) != 0 ? dimsHw : -dimsHw, (i & 2) != 0 ? dimsH : 0, (i & 4) != 0 ? dimsHl : -dimsHl));
        if (wheels != null) for (Wheel wh : wheels.values()) { tags.add(wh.at); tags.add(V(wh.at.x + wh.side * 0.55, wh.at.y, wh.at.z)); }
        List<V3> all = new ArrayList<>(corners); all.addAll(tags);
        float dTop = fit(TOP, 1, all, p ? 0.9f : 0.8f, ah) / S.num("dZoom");
        vDist = fit(HOME, 0, corners, p ? 0.94f : 0.86f, ah) / S.num("viewZoom");
        chgFit(corners, p ? 0.94f : 0.86f, ah);
        topDist = dTop;
        introT = it0; showK = sk0; angK = ak0; topK = qk0; vAz = az; vEl = el0;
        fitA = new float[] { ah, p ? 0.94f : 0.86f, 1 - 2 * cy / h };
        if (mirror != null) mirror.resize(app.bufW(), app.bufH());
        if (doorV != null) doorV.zoom = fitZoom(doorV.view, doorV.at, doorV.want, true);
        if (pin != null && pin.v != null) pin.v.zoom = fitZoom(pin.v.view, pin.v.at, pin.v.want, true);
        place();
        camera.updateProjectionMatrix();
    }
    private float fit(float[] v, float k, List<V3> pts, float aw, float ah) {
        float lo = 2, hi = 60;
        topK = k; vAz = v[0]; vEl = v[1];
        for (int it = 0; it < 22; it++) {
            vDist = topDist = (lo + hi) / 2;
            place();
            camera.updateProjectionMatrix();
            float mx = 0, mn = 1, my = -1;
            for (V3 c : pts) { V3 q = c.clone().project(camera); mx = Math.max(mx, Math.abs(q.x)); my = Math.max(my, q.y); mn = Math.min(mn, q.y); }
            if (mx > aw || (my - mn) / 2 > ah) lo = vDist; else hi = vDist;
        }
        return hi;
    }

    // ================= 手势 =================
    @Override public boolean grab(float x, float y) {
        touchAt = now();
        pdrag = null; // 上一次按在车牌上没拖就抬起来了（只算点了一下，不会调 release）
        if (root == null) return false;
        if (pkey != null && plateHit(x, y)) { plateGrab(x, y); return true; }
        ray.setFromCamera(x / W * 2 - 1, 1 - y / H * 2, camera);
        return ray.intersectsBox(hitBox);
    }
    @Override public void drag(float dx, float dy, float x, float y) {
        if (pdrag != null) { plateMove(x, y); return; }
        vHoming = false; vVaz = 0; topLast = now();
        stopLook();
        if (introT < 1 && introRot != 0) { float r = 1 - easeOut(introT); vAz += r * INTRO[0]; vEl += r * INTRO[1]; introRot = 0; }
        vAz -= dx * 0.009f;
        vEl = Math.max(0.04f, Math.min(Math.max(topK > 0.5f ? 1.3f : 0.62f, vEl), vEl + dy * 0.004f));
        hint();
    }
    @Override public void release(float vx, float x, float y, boolean cancel) {
        if (pdrag != null) { pdrag = null; plateDone(cancel); return; }
        vVaz = -vx * 0.13f; topLast = now(); app.invalidate();
        app.cancel(topBack);
        if (topOn) topBack = app.later(6100, () -> { if (root != null) app.invalidate(); });
    }
    @Override public void tap(float x, float y) {
        statusClose();
        long t = now();
        if (t - lastTap < 450) { stopLook(); if (pkey != null) plateV = plateFrame(pkey); vHoming = true; vVaz = 0; app.invalidate(); lastTap = 0; } else lastTap = t; // 调车牌时双击回到看这块车牌
    }
    @Override public void wake(boolean on) { visibleNow = on; if (on) app.invalidate(); }
    /** 全部车况开 / 关：车让到面板旁边，但不转到侧面、照样挂 D 挡转到车顶看，胎压标签照样显示（同 aside） */
    private boolean asideOn;
    void aside(boolean on) { asideOn = on; if (root != null) app.invalidate(); }
    /** 设置面板开 / 关：车让到面板旁边、镜头转到侧面 */
    void focus(boolean on) {
        showOn = on;
        if (!on && preview) { preview = previewTop = false; vHoming = true; }
        if (root != null) app.invalidate();
    }
    private void hint() {
        if (prefs.getBoolean("carHint", false)) return;
        prefs.edit().putBoolean("carHint", true).apply();
        out.hint(false);
    }
    boolean hintShown() { return !prefs.getBoolean("carHint", false); }
    @Override public Obj3D scene() { return scene; }
    @Override public Camera camera() { return camera; }
    @Override public boolean still() { return still; }
    @Override public boolean fast() { return true; }
    // ================= 地面反射（画质高、极致） =================
    private Mirror mirror;
    @Override public void pre(com.x3card.car3d.gl.Renderer r) {
        if (mirror == null || root == null || !root.visible) return;
        mirror.setK(0.2f + 0.14f * themeK); // 深色时更明显
        mirror.render(r, scene, camera);
    }

    /** 设置变了（同网页版 S.on）：GL 线程里调 */
    void setting(String k) {
        if ("theme".equals(k)) theme(wantDark());
        if ("bg".equals(k) || "bgDim".equals(k)) background();
        if ("stats".equals(k)) apply(carNow);
        if ("act:viewSave".equals(k)) { final float az = Math.round(wrap(vAz) * 1000) / 1000f, el = Math.round(Math.min(0.62f, vEl) * 1000) / 1000f; app.ui(() -> { S.set("viewAz", az); S.set("viewEl", el); }); out.toast("以后打开就是这个角度", 3200); }
        if ("act:dSave".equals(k)) { final float az = Math.round(wrap(vAz) * 1000) / 1000f, el = Math.round(Math.max(0.04f, Math.min(1.3f, vEl)) * 1000) / 1000f; app.ui(() -> { S.set("dAz", az); S.set("dEl", el); }); out.toast("以后挂 D 挡就是这个角度", 3200); }
        if (k.matches("^(viewAz|viewEl|dAz|dEl)$")) homeView();
        if (k.matches("^(view(Az|El|Zoom|Fov)|d(Az|El|Zoom))$") && root != null) {
            if (showOn) { preview = true; previewTop = k.startsWith("d"); }
            if ("viewZoom".equals(k) || "viewFov".equals(k) || k.startsWith("d")) resize(W, H, portrait); // D 挡视角的远近按这个角度装得下整车和胎压标签算
            vHoming = true; vVaz = 0;
        }
        if ("labels".equals(k) && app != null) app.invalidate();
        if (root == null) return;
        if (k.matches("^(paint|rim|caliper|finish|paintHex|rimHex|calHex)$")) finish(true);
        app.invalidate();
    }
}
