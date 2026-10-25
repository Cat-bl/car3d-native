package com.x3card.car3d.ach;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 勋章墙（GL）：背景同车况页（Bg：影棚 / 暖调 / 冷调三套渐变跟着深浅色，或者自己的图片 + 压暗），排版见 Hall：
 * 左边（竖屏是顶上一条）一块总览卡片，最近拿到的那枚在聚光灯下慢慢转；右边一个分类一行，勋章立在玻璃台面上，台面里有淡淡的倒影。
 * 打开时背景不动、总览和一行行勋章浮上来，浮完一道光扫过去；关上时沉下去（放完告诉外面拿掉画布）。
 * - 勋章：回转体（正面、凸起的边、倒角、侧面、背面），正面贴浮雕的法线 + 高度图（Icons）：凸起的是金属、凹下去的是珐琅（按分类上色）；
 *   金属按等级（铜 / 银 / 金 / 传奇带一点随角度变的彩虹色）。还没拿到的是没上色的石墨色，正面只有一个问号。
 * - 倒影：同一枚按台面上沿上下翻过来再画一遍，着色时把法线、视线翻回去（看起来是原来那枚的镜像），越往下越淡。
 * - 图案只给拿到了的做（加上一个大家共用的问号），图集按需要往下长：勋章再多，没拿到的也不占显存。
 * - 造型按等级：铜是圆章、侧面压竖纹；银的背后衬一圈花边；金背后是带棱的星芒；传奇是长短相间的星芒。
 *   没拿到的一律是素的圆章（看不出等级）。正面的珐琅底下有波纹放射的刻花，透过珐琅看得见，珐琅上面罩一层透明釉。
 * - 打光：解析的摄影棚环境（几块柔光箱 + 渐变），粗糙度越大柔光箱的边越软、越暗，不用环境贴图和预过滤；镜面 + 漫反射按 Karis 的近似算。
 * - 相机：透视，但 z = 0 平面上一个世界单位正好是窗口一个像素（和 Hall 的排版、点击判断对得上）。
 * - 上下滚（停在整行）、每行左右滚（惯性、到头回弹）、点开看一枚（放大、手指拖着转）、解锁庆祝（转着飞出来、光芒、火花），
 *   都在 GL 线程里按弹簧 / 缓动走；没在动就不出帧，只有聚光灯下那枚在转时按每秒 30 帧画。
 *   分类标题、名字也在 GL 里（字的图集），滚动时和勋章一起动、不会错开
 */
final class Wall implements GLSurfaceView.Renderer {
    AchView view;
    final AchView.Pacer pacer = new AchView.Pacer();
    /** 关上的动画放完了（在主线程里调） */
    Runnable onClosed;

    /** 没解锁的勋章用的图案（问号） */
    static final String HIDDEN = "?";

    /** 一枚勋章要显示的东西（主线程做好交过来） */
    static final class Item {
        String id, icon, name, sub;
        int cat, tier;
        boolean unlocked;
        /** 图集里用哪张图案：拿到了的按 id，没拿到的都用问号 */
        String key() { return unlocked ? id : HIDDEN; }
    }

    /** 墙上的一行行：items 按行排好，第 r 行是 items[start[r]] 到 items[start[r + 1]]；names 分类名，got / base 这一行拿到几枚、一共几枚 */
    static final class Rows {
        final Item[] items;
        final int[] start, got, base;
        final String[] names;
        Rows(Item[] items, int[] start, String[] names, int[] got, int[] base) { this.items = items; this.start = start; this.names = names; this.got = got; this.base = base; }
        int rows() { return names.length; }
        int count(int r) { return start[r + 1] - start[r]; }
        int rowOf(int i) { for (int r = 0; r < names.length; r++) if (i < start[r + 1]) return r; return names.length - 1; }
    }

    // ================= 主线程交进来的 =================
    private volatile Rows data = new Rows(new Item[0], new int[] { 0 }, new String[0], new int[0], new int[0]);
    private volatile int dataRev;
    private volatile Item spotWanted;
    private volatile boolean dayWanted;
    private volatile int vw = 1, vh = 1;
    private volatile int focusWanted = -1, focusRev;
    private volatile boolean focusFromSpot;
    private float rotDX, rotDY;   // 看一枚时手指转了多少（累计的增量，rotLock 管着）
    private volatile Item celebrate;
    private volatile Item[] celebrateMore;
    private volatile int celebRev;
    private volatile boolean openWanted = true;
    /** 背景：0 影棚、1 暖调、2 冷调、3 图片；压暗；图片（换了画布要重新传，留到关上） */
    private volatile int bgKind;
    private volatile float bgDim;
    private volatile Bitmap bgPending;
    private volatile int bgRev;
    /** 数字用的字体（Outfit，主线程交进来） */
    volatile Typeface num = Typeface.DEFAULT;

    /** 滚动的手势：上下（整面墙）开始、跟着手指、松手（带速度）；左右（某一行）开始、跟着手指、松手 */
    static final int V_START = 0, V_MOVE = 1, V_END = 2, H_START = 3, H_MOVE = 4, H_END = 5;
    private final ConcurrentLinkedQueue<float[]> events = new ConcurrentLinkedQueue<>();

    /** GL 线程报出去的：现在上下滚到哪、每行左右滚到哪（主线程点击判断用） */
    volatile float scrollYNow;
    volatile float[] rowXNow = new float[0];

    void setRows(Rows d) {
        askIcon(HIDDEN, HIDDEN, -1);
        for (Item m : d.items) if (m.unlocked) askIcon(m.id, m.icon, m.tier);
        data = d;
        dataRev++;
        wake();
    }
    /** 聚光灯下的那枚（最近拿到的；一枚都没有时是问号） */
    void setSpot(Item it) { if (it != null && it.unlocked) askIcon(it.id, it.icon, it.tier); spotWanted = it; dataRev++; wake(); }
    void setDay(boolean d) { dayWanted = d; wake(); }
    /** 背景（同车况页）：图片背景时 img 是那张图（交给 GL 线程传上去） */
    void setBg(int kind, float dim, Bitmap img) { bgKind = kind; bgDim = dim; bgPending = img; bgRev++; wake(); }
    /** 关上：勋章沉下去，放完调 onClosed */
    void close() { openWanted = false; wake(); }
    /** 滚动手势（type 见 V_START 这些；row 是左右滚的那一行；v 是手指挪了多少或者松手时的速度，窗口像素） */
    void scroll(int type, int row, float v) { events.add(new float[] { type, row, v }); wake(); }
    /** 看第 i 枚（−1 关上）；fromSpot：从聚光灯下那枚飞出来 */
    void focus(int i, boolean fromSpot) { focusFromSpot = fromSpot; focusWanted = i; focusRev++; wake(); }
    private final Object rotLock = new Object();
    void rotate(float dx, float dy) { synchronized (rotLock) { rotDX += dx; rotDY += dy; } wake(); }
    /** 庆祝一枚；more 是同时解锁的另外几枚（合成一次庆祝时排在两边，最多 4 枚），没有就是空数组 */
    void celebrate(Item it, Item[] more) { celebrate = it; celebrateMore = more; celebRev++; wake(); }

    private final Runnable wakeR = this::wake;
    void wake() { if (view != null) view.requestRender(); }

    // ================= 勋章图案：后台生成，传到图集里（一行 16 格，格子不够了图集往下长一倍） =================
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.HashSet<String> asked = new java.util.HashSet<>();
    private static final int SLOT = 128, COLS = 16, ATLAS_W = SLOT * COLS, MAX_ROWS = 16, DETAIL = 512;

    /** 主线程：要这张图案（做过的不再做）；tier −1 是不画等级记号 */
    private void askIcon(final String key, final String icon, final int tier) {
        if (!asked.add(key)) return;
        worker.execute(() -> {
            ByteBuffer b = Icons.make(icon, tier, SLOT);
            ready.add(new Object[] { key, b });
            wake();
        });
    }

    private String detailFor;
    private ByteBuffer detailBuf;

    private void askDetail(final Item it) {
        if (it == null || it.key().equals(detailFor)) return;
        final String key = it.key(), icon = it.icon;
        final int tier = it.unlocked ? it.tier : -1;
        detailFor = key;
        detailReady = false;
        worker.execute(() -> {
            ByteBuffer b = Icons.make(icon, tier, DETAIL);
            ready.add(new Object[] { null, b, key });
            wake();
        });
    }

    void quit() {
        worker.shutdownNow();
        bgPending = null; // 不 recycle：GL 线程这一刻可能正在传它，交给回收器
    }

    // ================= GL 资源 =================
    private G.Prog pBg, pCoin, pFlat, pLabel, pRays, pPts, pSpot;
    private int coinVao, coinCount, quadVao, ptsVao, ptsVbo;
    /** 背后衬的东西：按等级（银花边、金星芒、传奇长短星芒；铜没有） */
    private final int[] plateVao = new int[4], plateCount = new int[4];
    private int atlas, labels, detail, atlasRows = 4;
    /** 背景图片（GL 线程）；换了背景或者换了画布才重新传 */
    private int bgTex, seenBgRev = -1;
    private float bgImgW = 1, bgImgH = 1;
    /** 车况页背景的三套渐变：浅色三站、深色三站（同 car3d 的 Bg，sRGB） */
    private static final int[][] BG_L = { { 0xffffff, 0xf0f2f5, 0xdde1e7 }, { 0xfffaf3, 0xf3ebe0, 0xe2d6c6 }, { 0xf6fbff, 0xe4edf7, 0xcbd9ea } };
    private static final int[][] BG_D = { { 0x2b3038, 0x16191e, 0x0b0c0f }, { 0x3b3027, 0x1c1612, 0x0d0a08 }, { 0x1f2d42, 0x101824, 0x060a10 } };
    private boolean detailReady, reupload;
    /** 图案在图集的第几格（GL 线程）；换画布时格子不变，按 uploaded 重新传 */
    private final java.util.HashMap<String, Integer> slotOf = new java.util.HashMap<>();
    private final List<Object[]> uploaded = new ArrayList<>();
    private int bw = 1, bh = 1;
    private float scale = 1;

    private static final String V_QUAD = "layout(location = 0) in vec2 aP; out vec2 vUv; void main() { vUv = aP * 0.5 + 0.5; gl_Position = vec4(aP, 0.0, 1.0); }";

    /** 背景（同车况页的 Bg：椭圆径向渐变，中心在 50% 60%，在 sRGB 里插值；深色是盖在上面的一层；图片铺满再压暗） */
    private static final String F_BG = "uniform vec3 uL0, uL1, uL2, uD0, uD1, uD2; uniform float uK, uDim, uImg; uniform vec2 uImgS; uniform sampler2D tImg; in vec2 vUv; out vec4 o;\n"
        + "vec3 grad(vec2 p, vec2 e, float s, vec3 a, vec3 b, vec3 c) { float t = length(vec2((p.x - 0.5) / e.x, (p.y - 0.6) / e.y)); return t < s ? mix(a, b, t / s) : mix(b, c, clamp((t - s) / (1.0 - s), 0.0, 1.0)); }\n"
        + "void main() {\n"
        + "  vec2 p = vec2(vUv.x, 1.0 - vUv.y);\n"
        + "  vec3 c;\n"
        + "  if (uImg > 0.5) { vec2 q = (p - 0.5) * uImgS + 0.5; c = mix(texture(tImg, q).rgb, vec3(0.0), uDim); }\n"
        + "  else c = mix(grad(p, vec2(0.75, 0.46), 0.46, uL0, uL1, uL2), grad(p, vec2(0.72, 0.44), 0.52, uD0, uD1, uD2), uK);\n"
        + "  o = vec4(c, 1.0);\n"
        + "}";

    /** 平面的东西（卡片、台面、影子、进度条、变暗）：位置是像素世界坐标 */
    private static final String V_FLAT = "layout(location = 0) in vec2 aP; uniform mat4 uVP; uniform vec4 uRect; uniform float uZ; out vec2 vL; out vec2 vWp;\n"
        + "void main() { vL = aP * 0.5 + 0.5; vec2 p = uRect.xy + vL * uRect.zw; vWp = p; gl_Position = uVP * vec4(p, uZ, 1.0); }";
    /** 长廊的边：左右、上面（世界坐标，y 朝上）往里 uClip.w 的一段淡出去；不裁时给一个很大的范围 */
    private static final String CLIP = "uniform vec4 uClip;\n"
        + "float clipK(vec2 p) { float f = uClip.w; return smoothstep(uClip.x, uClip.x + f, p.x) * (1.0 - smoothstep(uClip.y - f, uClip.y, p.x)) * (1.0 - smoothstep(uClip.z - f, uClip.z, p.y)); }\n";
    /** uKind 0 实色、1 圆形柔影、3 台面（上面最亮、往下淡掉）、4 圆角矩形（uRR：宽、高、圆角，像素） */
    private static final String F_FLAT = CLIP + "uniform vec4 uCol; uniform int uKind; uniform vec3 uRR; in vec2 vL; in vec2 vWp; out vec4 o;\n"
        + "void main() {\n"
        + "  float a = uCol.a;\n"
        + "  if (uKind == 1) { vec2 d = vL * 2.0 - 1.0; float r = dot(d, d); a *= exp(-r * 3.2) * (1.0 - smoothstep(0.85, 1.0, r)); }\n"
        + "  else if (uKind == 3) a *= pow(vL.y, 1.6);\n"
        + "  else if (uKind == 4) { vec2 p = (vL - 0.5) * uRR.xy; vec2 q = abs(p) - uRR.xy * 0.5 + uRR.z; float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRR.z; a *= clamp(0.5 - d, 0.0, 1.0); }\n"
        + "  a *= clipK(vWp);\n"
        + "  o = vec4(uCol.rgb * a, a);\n"
        + "}";

    /** 聚光灯：从上往下张开的一道光，落到勋章底下一圈光斑（浅色时光斑换成淡淡的影子） */
    private static final String F_SPOT = "uniform float uDay; uniform float uA; in vec2 vL; out vec4 o;\n"
        + "void main() {\n"
        + "  float y = vL.y, hw = mix(0.47, 0.12, y), d = abs(vL.x - 0.5) / hw;\n"
        + "  float beam = (1.0 - smoothstep(0.45, 1.0, d)) * (0.3 + 0.7 * (1.0 - y)) * (1.0 - smoothstep(0.82, 1.0, y));\n"
        + "  vec2 f = vec2((vL.x - 0.5) / 0.42, (y - 0.09) / 0.07); float pool = exp(-dot(f, f) * 2.2);\n"
        + "  vec3 col = vec3(1.0, 0.95, 0.86);\n"
        + "  float add = mix(beam * 0.16 + pool * 0.26, beam * 0.05, uDay), shade = pool * 0.16 * uDay;\n"
        + "  o = vec4(col * add * uA, shade * uA);\n"
        + "}";

    /** 部件：0 正面（贴浮雕）、1 边（内壁、边顶、外倒角）、2 侧面、3 背面、4 背后衬的星芒 / 花边 */
    private static final String V_COIN = "layout(location = 0) in vec3 aP; layout(location = 1) in vec3 aN; layout(location = 2) in vec2 aUv; layout(location = 3) in float aPart;\n"
        + "uniform mat4 uM; uniform mat4 uVP; uniform mat3 uNm; uniform vec4 uSlot;\n"
        + "out vec3 vW; out vec3 vN; out vec2 vUv; out vec2 vF; out float vPart; out vec3 vT; out vec3 vB; out vec3 vO; out vec3 vA;\n"
        + "void main() { vec4 w = uM * vec4(aP, 1.0); vW = w.xyz; vN = uNm * aN; vT = uNm * vec3(1.0, 0.0, 0.0); vB = uNm * vec3(0.0, 1.0, 0.0);\n"
        + "  vA = uNm * vec3(-aP.y, aP.x, 0.0); vO = aP; vF = aUv; vUv = uSlot.xy + aUv * uSlot.zw; vPart = aPart; gl_Position = uVP * w; }";

    /** 勋章：解析的摄影棚环境 + Karis 的环境 BRDF 近似；正面按浮雕图分金属 / 珐琅 */
    private static final String F_COIN = CLIP + "uniform vec3 uRefl; uniform vec3 uEye; uniform vec3 uBase; uniform vec3 uEnam; uniform float uRough; uniform float uLock; uniform float uIri; uniform float uSheen; uniform float uAlpha; uniform float uDay; uniform float uReed;\n"
        + "uniform sampler2D uTex;\n"
        + "in vec3 vW; in vec3 vN; in vec2 vUv; in vec2 vF; in float vPart; in vec3 vT; in vec3 vB; in vec3 vO; in vec3 vA; out vec4 o;\n"
        + "float box(vec3 d, vec3 c, vec3 t, vec2 s, float soft) {\n"
        + "  float w = dot(d, c); if (w <= 0.0) return 0.0;\n"
        + "  vec3 b = cross(c, t); float u = dot(d, t) / w, v = dot(d, b) / w;\n"
        + "  return smoothstep(s.x + soft, s.x - soft, abs(u)) * smoothstep(s.y + soft, s.y - soft, abs(v));\n"
        + "}\n"
        + "vec3 env(vec3 d, float r) {\n"
        + "  float soft = 0.03 + r * 0.9, k = 1.0 / (1.0 + r * 5.0);\n"
        + "  vec3 sky = mix(vec3(0.05, 0.05, 0.07), vec3(0.42, 0.42, 0.46), smoothstep(-0.4, 0.8, d.y));\n"
        + "  sky = mix(sky, vec3(0.22, 0.18, 0.15), smoothstep(0.1, -0.7, d.y) * 0.6);\n"
        + "  vec3 c = sky * mix(0.6, 1.1, uDay);\n"
        + "  c += vec3(1.0, 0.97, 0.92) * 5.5 * k * box(d, normalize(vec3(-0.25, 0.32, 0.92)), normalize(vec3(0.97, 0.0, 0.26)), vec2(0.55, 0.30), soft);\n"
        + "  c += vec3(0.95, 0.97, 1.0) * 4.0 * k * box(d, normalize(vec3(0.0, 0.92, 0.38)), vec3(1.0, 0.0, 0.0), vec2(1.6, 0.10), soft);\n"
        + "  c += vec3(0.9, 0.94, 1.0) * 2.6 * k * box(d, normalize(vec3(0.85, 0.05, 0.52)), vec3(0.0, 1.0, 0.0), vec2(0.9, 0.16), soft);\n"
        + "  c += vec3(1.0, 0.86, 0.70) * 1.4 * k * box(d, normalize(vec3(-0.80, -0.30, 0.50)), vec3(0.0, 1.0, 0.0), vec2(0.6, 0.25), soft);\n"
        + "  return c;\n"
        + "}\n"
        + "vec3 envBRDF(vec3 F0, float r, float nv) {\n"
        + "  vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022), c1 = vec4(1.0, 0.0425, 1.04, -0.04);\n"
        + "  vec4 rr = r * c0 + c1; float a = min(rr.x * rr.x, exp2(-9.28 * nv)) * rr.x + rr.y;\n"
        + "  vec2 ab = vec2(-1.04, 1.04) * a + rr.zw; return F0 * ab.x + ab.y;\n"
        + "}\n"
        + "vec3 neutral(vec3 c) { float x = min(c.r, min(c.g, c.b)); float off = x < 0.08 ? x - 6.25 * x * x : 0.04; c -= off;\n"
        + "  float p = max(c.r, max(c.g, c.b)); if (p < 0.76) return c; float d = 0.24; float np = 1.0 - d * d / (p + d - 0.76); c *= np / p; return mix(c, vec3(np), 1.0 - 1.0 / (0.15 * (p - np) + 1.0)); }\n"
        + "void main() {\n"
        + "  // 倒影：画的是上下翻过来的那枚；台面上面的不要，法线、视线、切线翻回去再着色（看起来是原来那枚的镜像）\n"
        + "  bool mir = uRefl.z > 0.5;\n"
        + "  if (mir && vW.y > uRefl.x + 0.5) discard;\n"
        + "  vec3 N = normalize(vN), V = normalize(uEye - vW), tT = normalize(vT), tB = normalize(vB), tA = vA;\n"
        + "  if (mir) { N.y = -N.y; V.y = -V.y; tT.y = -tT.y; tB.y = -tB.y; tA.y = -tA.y; }\n"
        + "  vec3 Ng = N;\n"
        + "  float metal = 1.0, h = 1.0, clear = 0.0, ao = 1.0;\n"
        + "  vec3 base = uBase; float rough = uRough;\n"
        + "  if (vPart < 0.5) {\n"
        + "    vec4 t = texture(uTex, vUv);\n"
        + "    vec3 tn = t.xyz * 2.0 - 1.0; h = t.a;\n"
        + "    N = normalize(tT * tn.x + tB * tn.y + N * tn.z);\n"
        + "    metal = smoothstep(0.40, 0.56, h);\n"
        + "    // 珐琅底下的刻花：波纹放射线（太密、在墙上只有几个像素时淡掉，不闪）\n"
        + "    vec2 q = vF - 0.5; float r = length(q) * 2.0, a = atan(q.y, q.x) * 60.0 + sin(r * 24.0) * 2.4;\n"
        + "    float g = 0.5 + 0.5 * sin(a); g = mix(0.5, g, clamp(1.6 - fwidth(a), 0.0, 1.0));\n"
        + "    vec3 enam = uEnam * (0.7 + 0.3 * g) * (1.08 - 0.2 * r);\n"
        + "    base = mix(enam, uBase, metal);\n"
        + "    rough = mix(0.12, uRough * 0.8, metal);\n"
        + "    clear = 1.0 - metal;\n"
        + "    ao = mix(0.6, 1.0, smoothstep(0.05, 0.45, h));\n"
        + "  } else if (vPart < 1.5) { rough = uRough * 0.7;\n"
        + "  } else if (vPart < 2.5) {\n"
        + "    // 侧面的竖纹（同上，太密时淡掉）\n"
        + "    float th = atan(vO.y, vO.x) * 90.0;\n"
        + "    N = normalize(N + normalize(tA) * sin(th) * 0.55 * uReed * clamp(1.4 - fwidth(th), 0.0, 1.0));\n"
        + "    rough = uRough * 1.2;\n"
        + "  } else if (vPart < 3.5) {\n"
        + "    // 背面：微微鼓起的弧面（转的时候光从上面走过去），上面一圈圈细密的同心纹（太密时淡掉），中间一道宽一点的圆环\n"
        + "    float rr = length(vO.xy), g = rr * 150.0;\n"
        + "    float ring = sin(g) * 0.16 * clamp(1.4 - fwidth(g), 0.0, 1.0) + (smoothstep(0.5, 0.53, rr) - smoothstep(0.56, 0.59, rr)) * 0.5;\n"
        + "    vec2 dir = rr > 1e-4 ? vO.xy / rr : vec2(0.0);\n"
        + "    N = normalize(N + (tT * dir.x + tB * dir.y) * (rr * 0.42 + ring));\n"
        + "    rough = uRough * 0.9;\n"
        + "  } else { rough = uRough * 0.6; }\n"
        + "  float nv = clamp(dot(N, V), 0.001, 1.0);\n"
        + "  if (uIri > 0.0 && metal > 0.5) { vec3 ir = 0.5 + 0.5 * cos(6.2832 * (vec3(0.0, 0.33, 0.67) + nv * 1.3 + uIri)); base = mix(base, base * (0.55 + 0.6 * ir), 0.55); }\n"
        + "  // 没拿到：石墨色，珐琅变成深灰\n"
        + "  vec3 gray = vec3(dot(base, vec3(0.3, 0.55, 0.15)));\n"
        + "  base = mix(base, mix(gray * 0.32, vec3(0.11, 0.115, 0.13), metal), uLock);\n"
        + "  rough = mix(rough, mix(0.5, 0.42, metal), uLock);\n"
        + "  clear *= 1.0 - 0.6 * uLock;\n"
        + "  vec3 F0 = mix(vec3(0.04), base, metal);\n"
        + "  vec3 R = reflect(-V, N);\n"
        + "  vec3 spec = env(R, rough) * envBRDF(F0, rough, nv);\n"
        + "  vec3 diff = env(N, 1.0) * base * (1.0 - metal) * 0.95;\n"
        + "  vec3 c = (spec + diff) * ao;\n"
        + "  // 珐琅上罩的透明釉：平的、很亮的一层反光\n"
        + "  if (clear > 0.0) { float ng = clamp(dot(Ng, V), 0.001, 1.0); c = c * (1.0 - 0.04 * clear) + env(reflect(-V, Ng), 0.04) * envBRDF(vec3(0.04), 0.04, ng) * clear * 0.16; }\n"
        + "  float sb = (vW.x * 0.7 + vW.y * 0.7 - uSheen) / 26.0, band = exp(-sb * sb);\n"
        + "  if (!mir) c += band * metal * (1.0 - uLock) * vec3(0.9, 0.85, 0.75) * 0.9;\n"
        + "  c = neutral(c * mix(1.0, 0.92, uDay));\n"
        + "  c = pow(c, vec3(1.0 / 2.2));\n"
        + "  float al = uAlpha * clipK(vW.xy);\n"
        + "  if (mir) { float f = clamp(1.0 - (uRefl.x - vW.y) / uRefl.y, 0.0, 1.0); al *= f * (0.4 + 0.6 * f); }\n"
        + "  o = vec4(c * al, al);\n"
        + "}";

    private static final String F_LABEL = CLIP + "uniform sampler2D uT; uniform vec4 uUv; uniform float uA; in vec2 vL; in vec2 vWp; out vec4 o;\n"
        + "void main() { o = texture(uT, uUv.xy + vec2(vL.x, 1.0 - vL.y) * uUv.zw) * uA * clipK(vWp); }";

    /** 光芒：从中心往外的放射条纹，慢慢转 */
    private static final String F_RAYS = "uniform float uT; uniform float uA; uniform vec3 uCol; in vec2 vL; out vec4 o;\n"
        + "void main() { vec2 d = vL * 2.0 - 1.0; float r = length(d), a = atan(d.y, d.x);\n"
        + "  float rays = pow(0.5 + 0.5 * cos(a * 14.0 + uT * 0.6), 6.0) * 0.8 + pow(0.5 + 0.5 * cos(a * 9.0 - uT * 0.9 + 1.0), 8.0) * 0.5;\n"
        + "  float k = (rays * (1.0 - smoothstep(0.2, 1.0, r)) + exp(-r * r * 9.0) * 0.9) * uA;\n"
        + "  o = vec4(uCol * k, 0.0); }";

    private static final String V_PTS = "layout(location = 0) in vec4 aP; uniform mat4 uVP; uniform float uPx; out float vA;\n"
        + "void main() { vA = aP.w; gl_Position = uVP * vec4(aP.xy, 30.0, 1.0); gl_PointSize = uPx * aP.z; }";
    private static final String F_PTS = "uniform vec3 uCol; in float vA; out vec4 o;\n"
        + "void main() { vec2 d = gl_PointCoord - 0.5; float r = dot(d, d) * 4.0; if (r > 1.0) discard; float k = (1.0 - r) * (1.0 - r) * vA; o = vec4(mix(uCol, vec3(1.0), 0.5 * (1.0 - r)) * k, 0.0); }";

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        G.resetCache();
        pBg = new G.Prog(V_QUAD, F_BG);
        pFlat = new G.Prog(V_FLAT, F_FLAT);
        pCoin = new G.Prog(V_COIN, F_COIN);
        pLabel = new G.Prog(V_FLAT, F_LABEL);
        pRays = new G.Prog(V_FLAT, F_RAYS);
        pPts = new G.Prog(V_PTS, F_PTS);
        pSpot = new G.Prog(V_FLAT, F_SPOT);
        buildCoin();
        int[] r;
        r = buildRosette(10, 0.11f); plateVao[Ach.SILVER] = r[0]; plateCount[Ach.SILVER] = r[1];
        r = buildStar(12, new float[] { 1.27f }, new float[] { 0.26f }); plateVao[Ach.GOLD] = r[0]; plateCount[Ach.GOLD] = r[1];
        r = buildStar(24, new float[] { 1.34f, 1.13f }, new float[] { 0.34f, 0.26f }); plateVao[Ach.LEGEND] = r[0]; plateCount[Ach.LEGEND] = r[1];
        int[] b = new int[2];
        GLES30.glGenVertexArrays(1, b, 0);
        quadVao = b[0];
        GLES30.glBindVertexArray(quadVao);
        GLES30.glGenBuffers(1, b, 1);
        FloatBuffer q = fb(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[1]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 32, q, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glGenVertexArrays(1, b, 0);
        ptsVao = b[0];
        GLES30.glBindVertexArray(ptsVao);
        GLES30.glGenBuffers(1, b, 1);
        ptsVbo = b[1];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ptsVbo);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, PN * 16, null, GLES30.GL_DYNAMIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0);
        GLES30.glBindVertexArray(0);
        atlas = emptyTex(ATLAS_W, atlasRows * SLOT);
        detail = emptyTex(DETAIL, DETAIL);
        labels = 0;
        labelKey = "";
        detailReady = false;
        bgTex = 0;
        seenBgRev = -1;
        // 换了画布：已经做好的图案按原来的格子重新传
        reupload = true;
        if (detailBuf != null && detailFor != null) ready.add(new Object[] { null, detailBuf, detailFor });
    }

    private void put(int slot, ByteBuffer b) {
        G.bindTex(0, atlas);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, (slot % COLS) * SLOT, (slot / COLS) * SLOT, SLOT, SLOT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
    }

    /** 图集不够了：换一张高一倍的，已经传过的照原来的格子再传一遍 */
    private void grow(int rowsNeed) {
        int r = atlasRows;
        while (r < rowsNeed) r *= 2;
        atlasRows = Math.min(MAX_ROWS, r);
        G.free(atlas);
        atlas = emptyTex(ATLAS_W, atlasRows * SLOT);
        for (Object[] q : uploaded) put(slotOf.get((String) q[0]), (ByteBuffer) q[1]);
    }

    private static FloatBuffer fb(float[] a) { FloatBuffer f = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); f.put(a).position(0); return f; }

    private static int emptyTex(int w, int h) {
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        G.bindTex(0, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** 勋章的网格：截面（半径 r、厚度方向 z）绕 z 轴转一圈；每段截面各自的法线（棱角清楚），绕圈方向平滑 */
    private void buildCoin() {
        float[][] prof = {
            { 0.00f, 0.055f }, { 0.80f, 0.055f },           // 正面（贴浮雕）
            { 0.80f, 0.055f }, { 0.82f, 0.100f },           // 边的内壁
            { 0.82f, 0.100f }, { 0.86f, 0.110f }, { 0.91f, 0.110f }, { 0.95f, 0.100f }, // 边顶（圆一点）
            { 0.95f, 0.100f }, { 1.00f, 0.066f },           // 外倒角
            { 1.00f, 0.066f }, { 1.00f, -0.066f },          // 侧面
            { 1.00f, -0.066f }, { 0.95f, -0.090f },         // 背面倒角
            { 0.95f, -0.090f }, { 0.00f, -0.090f },         // 背面
        };
        int[][] segs = { { 0, 1, 0 }, { 2, 3, 1 }, { 4, 5, 1 }, { 5, 6, 1 }, { 6, 7, 1 }, { 8, 9, 1 }, { 10, 11, 2 }, { 12, 13, 3 }, { 14, 15, 3 } };
        int n = 96;
        List<float[]> v = new ArrayList<>();
        List<Integer> ix = new ArrayList<>();
        for (int[] sg : segs) {
            float[] a = prof[sg[0]], c = prof[sg[1]];
            float dr = c[0] - a[0], dz = c[1] - a[1], l = (float) Math.hypot(dr, dz);
            float nr = -dz / l, nz = dr / l; // 截面上往外的法线（从正面中心往背面走，左手边是外面）
            boolean face = sg[2] == 0;
            int base = v.size();
            for (int i = 0; i <= n; i++) {
                double t = 2 * Math.PI * i / n;
                float cs = (float) Math.cos(t), sn = (float) Math.sin(t);
                for (float[] q : new float[][] { a, c }) {
                    float x = q[0] * cs, y = q[0] * sn;
                    v.add(new float[] { x, y, q[1], nr * cs, nr * sn, nz, face ? 0.5f + x / (2 * 0.84f) : 0, face ? 0.5f - y / (2 * 0.84f) : 0, sg[2] });
                }
            }
            for (int i = 0; i < n; i++) {
                int p0 = base + i * 2, p1 = p0 + 1, p2 = p0 + 2, p3 = p0 + 3;
                ix.add(p0); ix.add(p2); ix.add(p1);
                ix.add(p1); ix.add(p2); ix.add(p3);
            }
        }
        int[] r = upload(v, ix);
        coinVao = r[0];
        coinCount = r[1];
    }

    /**
     * 背后衬的星芒：rays 道，尖在 tip[i % tip.length]、根部半宽 width[i % width.length]（按两道之间的角度算，长短相间），
     * 根部在 0.97（藏在章后面），道和道之间是空的；每道从中间一条棱往两边斜下去（两个平的刻面），外沿一圈侧壁，背面平的。
     * 高度：棱在 r = 0.75 处 0.08、尖上 −0.03
     */
    private int[] buildStar(int rays, float[] tip, float[] width) {
        List<float[]> v = new ArrayList<>();
        List<Integer> ix = new ArrayList<>();
        float rin = 0.75f, valley = 0.97f, zr = 0.08f, z0 = -0.03f, z1 = -0.075f;
        double step = 2 * Math.PI / rays;
        for (int i = 0; i < rays; i++) {
            double a = Math.PI / 2 + i * step;
            float tr = tip[i % tip.length];
            double hw = step * width[i % width.length];
            float[] B = { (float) Math.cos(a) * rin, (float) Math.sin(a) * rin, zr };
            float[] T = { (float) Math.cos(a) * tr, (float) Math.sin(a) * tr, z0 };
            float[] L = { (float) Math.cos(a - hw) * valley, (float) Math.sin(a - hw) * valley, z0 };
            float[] Rr = { (float) Math.cos(a + hw) * valley, (float) Math.sin(a + hw) * valley, z0 };
            tri(v, ix, B, L, T);
            tri(v, ix, B, T, Rr);
            // 侧壁：两条外沿往下到背面
            wall(v, ix, L, T, z1);
            wall(v, ix, T, Rr, z1);
            // 背面
            float[] O = { 0, 0, z1 };
            tri(v, ix, O, new float[] { T[0], T[1], z1 }, new float[] { L[0], L[1], z1 });
            tri(v, ix, O, new float[] { Rr[0], Rr[1], z1 }, new float[] { T[0], T[1], z1 });
        }
        return upload(v, ix);
    }

    /**
     * 背后衬的花边：petals 瓣，轮廓 r(θ) = 0.97 + amp·(1 + cos(petals·θ))/2（圆润的瓣），
     * 面是从里往外一圈圈降下去的圆顶（法线按网格算、平滑），外沿一圈侧壁，背面平的
     */
    private int[] buildRosette(int petals, float amp) {
        List<float[]> v = new ArrayList<>();
        List<Integer> ix = new ArrayList<>();
        int n = 384;
        float[] fr = { 0.80f, 0.90f, 0.96f, 1.0f }, fz = { 0.075f, 0.055f, 0.03f, -0.01f };
        float z1 = -0.075f;
        float[][][] P = new float[n][fr.length][];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            float rr = 0.97f + amp * (0.5f + 0.5f * (float) Math.cos(petals * t));
            for (int j = 0; j < fr.length; j++) P[i][j] = new float[] { (float) Math.cos(t) * rr * fr[j], (float) Math.sin(t) * rr * fr[j], fz[j] };
        }
        int base = v.size();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < fr.length; j++) {
                float[] a = P[(i + 1) % n][j], b = P[(i + n - 1) % n][j];
                float[] c = P[i][Math.min(fr.length - 1, j + 1)], d = P[i][Math.max(0, j - 1)];
                float[] du = { a[0] - b[0], a[1] - b[1], a[2] - b[2] }, dv = { c[0] - d[0], c[1] - d[1], c[2] - d[2] };
                float nx = du[1] * dv[2] - du[2] * dv[1], ny = du[2] * dv[0] - du[0] * dv[2], nz = du[0] * dv[1] - du[1] * dv[0];
                if (nz < 0) { nx = -nx; ny = -ny; nz = -nz; }
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                v.add(new float[] { P[i][j][0], P[i][j][1], P[i][j][2], nx / l, ny / l, nz / l, 0, 0, 4 });
            }
        }
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = 0; j + 1 < fr.length; j++) {
                int a = base + i * fr.length + j, b = base + i2 * fr.length + j, c = a + 1, d = b + 1;
                ix.add(a); ix.add(b); ix.add(c);
                ix.add(c); ix.add(b); ix.add(d);
            }
        }
        // 侧壁、背面
        for (int i = 0; i < n; i++) {
            float[] a = P[i][fr.length - 1], b = P[(i + 1) % n][fr.length - 1];
            wall(v, ix, a, b, z1);
            tri(v, ix, new float[] { 0, 0, z1 }, new float[] { b[0], b[1], z1 }, new float[] { a[0], a[1], z1 });
        }
        return upload(v, ix);
    }

    /** 一个平的三角形（按顶点顺序算法线，自己的三个顶点不和别的共用：刻面的棱是清楚的） */
    private static void tri(List<float[]> v, List<Integer> ix, float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
        float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9f) return;
        int base = v.size();
        for (float[] q : new float[][] { a, b, c }) v.add(new float[] { q[0], q[1], q[2], nx / l, ny / l, nz / l, 0, 0, 4 });
        ix.add(base); ix.add(base + 1); ix.add(base + 2);
    }

    /** 从外沿 a→b 往下到 z1 的一块侧壁（法线朝外） */
    private static void wall(List<float[]> v, List<Integer> ix, float[] a, float[] b, float z1) {
        float[] a1 = { a[0], a[1], z1 }, b1 = { b[0], b[1], z1 };
        // 朝外：边的方向叉乘 z 轴
        float ex = b[0] - a[0], ey = b[1] - a[1];
        boolean out = (ey * (a[0] + b[0]) - ex * (a[1] + b[1])) > 0;
        if (out) { tri(v, ix, a, a1, b1); tri(v, ix, a, b1, b); }
        else { tri(v, ix, a, b1, a1); tri(v, ix, a, b, b1); }
    }

    /** 顶点（位置 3、法线 3、贴图坐标 2、部件 1）交给 GL：返回 { 顶点数组, 索引数 } */
    private static int[] upload(List<float[]> v, List<Integer> ix) {
        float[] d = new float[v.size() * 9];
        for (int i = 0; i < v.size(); i++) System.arraycopy(v.get(i), 0, d, i * 9, 9);
        short[] s = new short[ix.size()];
        for (int i = 0; i < s.length; i++) s[i] = (short) (int) ix.get(i);
        int[] b = new int[3];
        GLES30.glGenVertexArrays(1, b, 0);
        GLES30.glBindVertexArray(b[0]);
        GLES30.glGenBuffers(2, b, 1);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[1]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, d.length * 4, fb(d), GLES30.GL_STATIC_DRAW);
        int st = 36;
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, st, 0);
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, st, 12);
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, st, 24);
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, st, 32);
        ShortBuffer sb = ByteBuffer.allocateDirect(s.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        sb.put(s).position(0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b[2]);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.length * 2, sb, GLES30.GL_STATIC_DRAW);
        GLES30.glBindVertexArray(0);
        return new int[] { b[0], s.length };
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        bw = Math.max(1, w);
        bh = Math.max(1, h);
        wake();
    }

    // ================= 每帧 =================
    private Hall hall;
    private Rows rows = data;
    private float sy, syV, syTarget, vStart, vD, hStart, hD, day = -1, sheenT = -1, swing, swingV, vswing, vswingV, spinA;
    private float[] rx = new float[0], rxV = new float[0];
    private int hRow = -1, swingRow = -1;
    private boolean vDrag, hDrag, sheenDone;
    /** 打开的进度（0 → 1 浮上来；关上时退回 0） */
    private float intro;
    private boolean closedSent;
    private int seenFocusRev, seenCelebRev, seenDataRev = -1, focusIx = -1;
    private boolean focusSpot;
    private float focusT, rotX, rotY, rotVX, rotVY, celebT = -1, clock;
    private long t0, lastTouchRot;
    private Item celebItem, focusItem, spot;
    private Item[] celebMore = new Item[0];
    private final float[] P = new float[16], V = new float[16], VP = new float[16], M = new float[16], T = new float[16], R1 = new float[16], R2 = new float[16], MI = new float[16], TMP = new float[16];
    private final float[] nm = new float[9];
    private float eyeZ;

    @Override
    public void onDrawFrame(GL10 gl) {
        float dt = pacer.begin();
        long now = SystemClock.uptimeMillis();
        if (t0 == 0) t0 = now;
        clock = (now - t0) / 1000f;
        boolean moving = false;
        // 传图案
        if (reupload) { reupload = false; for (Object[] q : uploaded) put(slotOf.get((String) q[0]), (ByteBuffer) q[1]); moving = true; }
        Object[] u;
        while ((u = ready.poll()) != null) {
            ByteBuffer b = (ByteBuffer) u[1];
            if (u[0] != null) {
                String key = (String) u[0];
                if (slotOf.containsKey(key)) continue;
                int slot = slotOf.size();
                if (slot >= MAX_ROWS * COLS) continue; // 2048×2048 都满了（256 枚）
                if (slot >= atlasRows * COLS) grow(slot / COLS + 1);
                slotOf.put(key, slot);
                uploaded.add(u);
                put(slot, b);
            } else if (u.length > 2 && u[2].equals(detailFor)) {
                G.bindTex(0, detail);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, DETAIL, DETAIL, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b);
                detailReady = true;
                detailBuf = b;
            }
            moving = true;
        }
        if (bgRev != seenBgRev) {
            seenBgRev = bgRev;
            if (bgTex != 0) { G.free(bgTex); bgTex = 0; }
            Bitmap bm = bgPending;
            if (bm != null && !bm.isRecycled()) { bgTex = G.texture(bm, false); bgImgW = bm.getWidth(); bgImgH = Math.max(1, bm.getHeight()); }
            moving = true;
        }
        // 打开：0.8 秒总览和一行行浮上来，浮完一道光扫过去；关上：0.4 秒沉下去，放完告诉外面
        float iT = openWanted ? 1 : 0;
        if (intro != iT) {
            intro = iT > intro ? Math.min(1, intro + dt / 0.8f) : Math.max(0, intro - dt / 0.4f);
            moving = true;
            if (intro >= 1 && !sheenDone) { sheenDone = true; sheenT = 0; }
        }
        if (!openWanted && intro <= 0 && !closedSent) { closedSent = true; Runnable r = onClosed; if (r != null) view.post(r); }
        float W = vw, H = vh;
        if (hall == null || hall.w != W || hall.h != H) { hall = new Hall(W, H); labelKey = ""; }
        Hall g = hall;
        scale = bw / W;
        // 深浅色（先定下来再画字：第一帧按还没定的深浅色画，字的颜色就反了）
        float dT = dayWanted ? 1 : 0;
        if (day < 0) day = dT;
        if (dataRev != seenDataRev) { seenDataRev = dataRev; rows = data; spot = spotWanted; labelKey = ""; moving = true; }
        if (day != dT) { day += Math.signum(dT - day) * Math.min(Math.abs(dT - day), dt / 0.6f); moving = true; }
        labelsFor(rows, g);
        if (scrollStep(rows, g, dt)) moving = true;
        if (sheenT >= 0) { sheenT += dt / 1.1f; if (sheenT > 1) sheenT = -1; else moving = true; }
        // 看一枚
        Item[] it = rows.items;
        if (focusRev != seenFocusRev) {
            seenFocusRev = focusRev;
            int f = focusWanted;
            if (f >= 0 && f < it.length) { focusIx = f; focusSpot = focusFromSpot; focusItem = focusSpot && spot != null ? spot : it[f]; askDetail(focusItem); rotX = 0; rotY = 0; rotVX = rotVY = 0; }
            else focusIx = -1;
        }
        float fT = focusIx >= 0 ? 1 : 0;
        if (focusT != fT) { focusT += Math.signum(fT - focusT) * Math.min(Math.abs(fT - focusT), dt / 0.45f); moving = true; }
        if (focusT <= 0 && focusIx < 0) focusItem = null;
        // 手指转勋章：拖的增量直接转，松手后按最后的速度接着转、慢慢停；2.5 秒没碰俯仰角回正
        float rxx, ryy;
        synchronized (rotLock) { rxx = rotDX; ryy = rotDY; rotDX = 0; rotDY = 0; }
        if (focusItem != null || celebItem != null) {
            if (rxx != 0 || ryy != 0) {
                rotY += rxx * 0.012f; rotX += ryy * 0.012f;
                rotVY = rxx * 0.012f / Math.max(dt, 1e-3f); rotVX = ryy * 0.012f / Math.max(dt, 1e-3f);
                lastTouchRot = now;
            } else {
                rotY += rotVY * dt; rotX += rotVX * dt;
                float damp = (float) Math.exp(-dt * 3);
                rotVY *= damp; rotVX *= damp;
            }
            rotX = Math.max(-1.2f, Math.min(1.2f, rotX));
            if (now - lastTouchRot > 2500) { rotX *= (float) Math.exp(-dt * 2); rotY *= (float) Math.exp(-dt * 0.8); }
            moving = true;
        }
        // 解锁庆祝
        if (celebRev != seenCelebRev) { seenCelebRev = celebRev; celebItem = celebrate; Item[] cm = celebrateMore; celebMore = cm == null ? new Item[0] : cm; celebT = 0; rotX = rotY = 0; rotVX = rotVY = 0; if (celebItem != null) { askDetail(celebItem); burst(); } }
        if (celebItem != null) { celebT += dt; moving = true; }
        if (celebItem == null) celebT = -1;
        // 聚光灯下那枚慢慢转整圈（正面朝外时稍慢一点）：一圈约 13 秒
        boolean spinning = spot != null && intro > 0 && focusT < 1 && celebItem == null;
        if (spinning) { spinA += dt * 0.5f * (1 - 0.35f * (float) Math.cos(spinA)); if (spinA > Math.PI * 2) spinA -= (float) (Math.PI * 2); }

        draw(g);
        if (closedSent) pacer.idle();
        else if (moving) view.requestRender();
        else if (spinning) { view.removeCallbacks(wakeR); view.postDelayed(wakeR, 33); } // 只有它在转：每秒 30 帧就够
        else pacer.idle();
    }

    /** 滚动：手势、弹簧、惯性、跟着拖动晃的那一下；还在动返回真 */
    private boolean scrollStep(Rows d, Hall g, float dt) {
        int nr = d.rows();
        if (rx.length != nr) { rx = Arrays.copyOf(rx, nr); rxV = Arrays.copyOf(rxV, nr); rowXNow = new float[nr]; }
        boolean mv = false;
        float[] e;
        while ((e = events.poll()) != null) {
            int r = (int) e[1];
            switch ((int) e[0]) {
                case V_START: vDrag = true; vStart = sy; vD = 0; syV = 0; break;
                case V_MOVE: vD = e[2]; break;
                case V_END: vDrag = false; syTarget = g.snapY(sy - e[2] * 0.22f, nr); if (Math.abs(syTarget - vStart) > 1) sheenT = 0; break;
                case H_START: if (r >= 0 && r < nr) { hDrag = true; hRow = r; hStart = rx[r]; hD = 0; rxV[r] = 0; swingRow = r; } break;
                case H_MOVE: hD = e[2]; break;
                case H_END: if (hDrag && hRow >= 0 && hRow < nr) rxV[hRow] = -Math.max(-6000, Math.min(6000, e[2])); hDrag = false; break;
                default: break;
            }
            mv = true;
        }
        // 上下：拖着跟手（到头了阻尼），松手后弹簧停到最近的一行
        float maxY = g.maxY(nr);
        if (vDrag) {
            float want = vStart - vD;
            if (want < 0) want *= 0.35f; else if (want > maxY) want = maxY + (want - maxY) * 0.35f;
            float v = (want - sy) / Math.max(dt, 1e-3f);
            vswingV += (Math.max(-3000, Math.min(3000, v)) * 0.00012f - vswing) * Math.min(1, dt * 12);
            sy = want;
            syV = 0;
            mv = true;
        } else {
            syTarget = Math.max(0, Math.min(maxY, syTarget));
            float k = 140, c = 2 * (float) Math.sqrt(k);
            syV += (k * (syTarget - sy) - c * syV) * dt;
            sy += syV * dt;
            if (Math.abs(syTarget - sy) < 0.3f && Math.abs(syV) < 2) { sy = syTarget; syV = 0; } else mv = true;
        }
        // 每行左右：拖着跟手，松手后惯性滑、慢慢停，滑过头弹回来
        for (int r = 0; r < nr; r++) {
            float mx = g.maxX(d.count(r));
            if (hDrag && r == hRow) {
                float want = hStart - hD;
                if (want < 0) want *= 0.35f; else if (want > mx) want = mx + (want - mx) * 0.35f;
                float v = (want - rx[r]) / Math.max(dt, 1e-3f);
                swingV += (Math.max(-3000, Math.min(3000, v)) * 0.00025f - swing) * Math.min(1, dt * 12);
                rx[r] = want;
                mv = true;
                continue;
            }
            float x = rx[r], v = rxV[r];
            if (x < 0 || x > mx) {
                float tgt = x < 0 ? 0 : mx, k = 160, c = 2 * (float) Math.sqrt(k);
                v += (k * (tgt - x) - c * v) * dt;
                x += v * dt;
                if (Math.abs(tgt - x) < 0.3f && Math.abs(v) < 2) { x = tgt; v = 0; }
            } else if (v != 0) {
                x += v * dt;
                v *= (float) Math.exp(-dt * 3.2f);
                if (Math.abs(v) < 4) v = 0;
            }
            if (x != rx[r] || v != 0) mv = true;
            rx[r] = x;
            rxV[r] = v;
        }
        // 勋章跟着拖动晃一下（弹簧）：左右拖那一行绕竖轴晃，上下拖整面墙往前后点一下头
        float ks = 90, cs = 2 * 0.35f * (float) Math.sqrt(ks);
        swingV += (-ks * swing - cs * swingV) * dt;
        swing += swingV * dt;
        vswingV += (-ks * vswing - cs * vswingV) * dt;
        vswing += vswingV * dt;
        if (Math.abs(swing) > 1e-3f || Math.abs(swingV) > 1e-3f) mv = true; else { swing = 0; swingV = 0; }
        if (Math.abs(vswing) > 1e-3f || Math.abs(vswingV) > 1e-3f) mv = true; else { vswing = 0; vswingV = 0; }
        scrollYNow = sy;
        float[] pub = rowXNow;
        if (pub.length == nr) System.arraycopy(rx, 0, pub, 0, nr);
        return mv;
    }

    // ================= 画 =================
    private void clip(G.Prog p, boolean on) {
        Hall g = hall;
        if (on) p.f4("uClip", g.gx0, g.gx1, -(g.gy0 - g.rem * 0.2f), g.rem * 0.28f);
        else p.f4("uClip", -60000, 60000, 60000, 1);
    }

    private static float mix(float a, float b, float k) { return a + (b - a) * k; }

    private void draw(Hall g) {
        float W = g.w, H = g.h;
        // 相机：z = 0 平面上一个单位是一个窗口像素（y 朝上）
        float fov = 30;
        eyeZ = (H / 2) / (float) Math.tan(Math.toRadians(fov / 2));
        Matrix.perspectiveM(P, 0, fov, W / H, eyeZ * 0.3f, eyeZ * 3f);
        Matrix.setLookAtM(V, 0, W / 2, -H / 2, eyeZ, W / 2, -H / 2, 0, 0, 1, 0);
        Matrix.multiplyMM(VP, 0, P, 0, V, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, bw, bh);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDepthMask(true);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        drawBg(W, H);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        Rows d = rows;
        Item[] it = d.items;
        float dk = 1 - day, ovK = easeOut(Math.max(0, Math.min(1, intro * 1.5f)));
        float over = Math.max(focusT, celebItem != null ? Math.min(1, celebT / 0.35f) : 0);
        float sheenU = sheenT >= 0 ? mix(-0.7f * H - 80, 0.7f * W + 80, sheenT) : -1e4f;

        // ---------- 总览卡片 + 聚光灯下的那枚 ----------
        pFlat.use();
        pFlat.m4("uVP", VP);
        clip(pFlat, false);
        if (ovK > 0) {
            float cw = g.ovR - g.ovL, ch = g.ovB - g.ovT;
            pFlat.i1("uKind", 4);
            pFlat.f3("uRR", cw, ch, g.rem * 0.22f);
            pFlat.f4("uCol", mix(1, 0.11f, dk), mix(1, 0.12f, dk), mix(1, 0.145f, dk), mix(0.5f, 0.6f, dk) * ovK);
            rect(pFlat, g.ovL, g.ovT + (1 - ovK) * g.rem * 0.3f, cw, ch, -4);
        }
        if (spot != null && ovK > 0) {
            float R = g.spotR, lift = (1 - ovK) * g.rem * 0.3f;
            pSpot.use();
            pSpot.m4("uVP", VP);
            pSpot.f("uDay", day);
            pSpot.f("uA", ovK);
            rect(pSpot, g.spotX - 2 * R, g.spotY - 2.4f * R + lift, 4 * R, 4 * R, -3);
            if (!(focusSpot && focusT > 0)) {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);
                pCoin.use();
                coinFrame(sheenU, W);
                clip(pCoin, false);
                pCoin.tex("uTex", 0, atlas);
                coin(spot, g.spotX, g.spotY + lift, R * (0.85f + 0.15f * ovK), -0.08f, spinA, ovK, false, Float.NaN);
                GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            }
        }

        // ---------- 长廊 ----------
        int nr = d.rows();
        int rTop = (int) Math.floor(sy / g.rowH);
        int r0 = Math.max(0, (int) Math.floor((sy - g.rem * 0.3f) / g.rowH)), r1 = Math.min(nr - 1, (int) Math.floor((sy + H - g.gy0) / g.rowH));
        float R = g.medalR, gw = g.gx1 - g.gx0;
        // 标题旁的进度条、玻璃台面、勋章脚下的影子
        pFlat.use();
        clip(pFlat, true);
        for (int r = r0; r <= r1; r++) {
            float rk = rowK(r - rTop);
            if (rk <= 0) continue;
            float drop = (1 - rk) * g.rem * 0.4f;
            if (r < headW.length) {
                float bx0 = g.gx0 + g.padL + headW[r] + g.rem * 0.22f, bx1 = g.gx1 - g.rem * 0.35f, hb = g.rem * 0.07f, by = g.headY(r, sy) + drop - hb / 2;
                if (bx1 - bx0 > g.rem * 0.6f) {
                    pFlat.i1("uKind", 4);
                    pFlat.f3("uRR", bx1 - bx0, hb, hb / 2);
                    pFlat.f4("uCol", mix(0.078f, 1, dk), mix(0.09f, 1, dk), mix(0.11f, 1, dk), mix(0.12f, 0.13f, dk) * rk);
                    rect(pFlat, bx0, by, bx1 - bx0, hb, -2);
                    float frac = d.base[r] > 0 ? Math.min(1, d.got[r] / (float) d.base[r]) : 0;
                    float fw = (bx1 - bx0) * frac * Math.min(1, intro * 1.2f);
                    if (fw > 0.5f) {
                        int cc = Palette.SRGB[r % Palette.SRGB.length];
                        pFlat.f3("uRR", Math.max(hb, fw), hb, hb / 2);
                        pFlat.f4("uCol", ((cc >> 16) & 255) / 255f, ((cc >> 8) & 255) / 255f, (cc & 255) / 255f, rk);
                        rect(pFlat, bx0, by, Math.max(hb, fw), hb, -2);
                    }
                }
            }
            float ly = g.ledgeY(r, sy) + drop;
            pFlat.i1("uKind", 3);
            pFlat.f4("uCol", 1, 1, 1, mix(0.55f, 0.07f, dk) * rk);
            rect(pFlat, g.gx0, ly, gw, g.rem * 0.34f, -2);
            pFlat.i1("uKind", 0);
            float hl = Math.max(1.2f / scale, g.rem * 0.018f);
            if (dk < 0.5f) { pFlat.f4("uCol", 0, 0, 0, 0.07f * rk); rect(pFlat, g.gx0, ly + hl, gw, hl, -2); }
            pFlat.f4("uCol", 1, 1, 1, mix(0.95f, 0.16f, dk) * rk);
            rect(pFlat, g.gx0, ly, gw, hl, -2);
            // 脚下的影子
            pFlat.i1("uKind", 1);
            pFlat.f4("uCol", 0, 0, 0, mix(0.22f, 0.5f, dk) * rk);
            int n = d.count(r), k0 = kFirst(r, g), k1 = kLast(r, g, n);
            for (int k = k0; k <= k1; k++) {
                float cx = g.cx(k, rx[r]);
                rect(pFlat, cx - R * 0.85f, ly - R * 0.1f, R * 1.7f, R * 0.26f, -1);
            }
        }
        // 分类标题
        if (labels != 0) {
            pLabel.use();
            pLabel.m4("uVP", VP);
            clip(pLabel, true);
            pLabel.tex("uT", 0, labels);
            for (int r = r0; r <= r1 && r < headUv.length; r++) {
                float rk = rowK(r - rTop);
                if (rk <= 0) continue;
                float[] uv = headUv[r];
                pLabel.f4("uUv", uv[0], uv[1], uv[2], uv[3]);
                pLabel.f("uA", rk);
                rect(pLabel, g.gx0 + g.padL, g.headY(r, sy) + (1 - rk) * g.rem * 0.4f - headSH / 2, headSW, headSH, 0);
            }
        }
        // 倒影，再是勋章
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        pCoin.use();
        coinFrame(sheenU, W);
        clip(pCoin, true);
        pCoin.tex("uTex", 0, atlas);
        float reflA = mix(0.34f, 0.4f, dk);
        for (int pass = 0; pass < 2; pass++) {
            for (int r = r0; r <= r1; r++) {
                float rk = rowK(r - rTop);
                if (rk <= 0) continue;
                float drop = (1 - rk) * g.rem * 0.4f, cy = g.cy(r, sy) + drop, ly = g.ledgeY(r, sy) + drop;
                float ay = r == swingRow ? swing * 1.6f : 0, ax = -0.12f + vswing * 1.2f;
                int n = d.count(r), k0 = kFirst(r, g), k1 = kLast(r, g, n);
                for (int k = k0; k <= k1; k++) {
                    int i = d.start[r] + k;
                    if (i == focusIx && !focusSpot && focusT > 0) continue;
                    float cx = g.cx(k, rx[r]);
                    if (pass == 0) coin(it[i], cx, cy, R * (0.8f + 0.2f * rk), ax, ay, rk * reflA, false, ly);
                    else coin(it[i], cx, cy, R * (0.8f + 0.2f * rk), ax, ay, rk, false, Float.NaN);
                }
            }
            if (pass == 0) GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT); // 勋章总在倒影前面
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        // 名字
        if (labels != 0) {
            pLabel.use();
            pLabel.tex("uT", 0, labels);
            for (int r = r0; r <= r1; r++) {
                float rk = rowK(r - rTop);
                if (rk <= 0) continue;
                float yl = g.labelY(r, sy) + (1 - rk) * g.rem * 0.4f - nameH / 2;
                int n = d.count(r), k0 = kFirst(r, g), k1 = kLast(r, g, n);
                for (int k = k0; k <= k1; k++) {
                    int i = d.start[r] + k;
                    if (i >= nameUv.length) break;
                    float[] uv = nameUv[i];
                    pLabel.f4("uUv", uv[0], uv[1], uv[2], uv[3]);
                    pLabel.f("uA", rk);
                    rect(pLabel, g.cx(k, rx[r]) - nameW / 2, yl, nameW, nameH, 0);
                }
            }
        }

        // ---------- 看一枚 / 解锁：底下压暗，勋章飞到前面放大 ----------
        if (over > 0) {
            pFlat.use();
            clip(pFlat, false);
            pFlat.i1("uKind", 0);
            pFlat.f4("uCol", 0.043f + (0.94f - 0.043f) * day, 0.047f + (0.95f - 0.047f) * day, 0.059f + (0.96f - 0.059f) * day, 0.9f * ease(over));
            rect(pFlat, -10, -10, W + 20, H + 20, 1);
        }
        if (celebItem != null || (focusItem != null && focusT > 0)) {
            pCoin.use();
            clip(pCoin, false);
        }
        if (celebItem != null) drawCeleb(W, H);
        else if (focusItem != null && focusT > 0) {
            float e = ease(focusT);
            float gx, gy, gr;
            int i = focusIx >= 0 ? focusIx : lastFocus;
            boolean fromSpot = focusIx >= 0 ? focusSpot : lastFocusSpot;
            if (fromSpot || i < 0 || i >= it.length) { gx = g.spotX; gy = g.spotY; gr = g.spotR; }
            else { int r = d.rowOf(i); gx = g.cx(i - d.start[r], r < rx.length ? rx[r] : 0); gy = g.cy(r, sy); gr = g.medalR; }
            boolean narrow = W / H < 0.8f;
            float fx = narrow ? W / 2 : W * 0.3f, fy = narrow ? H * 0.32f : H * 0.5f, fr = narrow ? Math.min(W * 0.3f, H * 0.17f) : Math.min(H * 0.3f, W * 0.2f);
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            pCoin.tex("uTex", 0, detailReady ? detail : atlas);
            float ay0 = fromSpot ? (spinA > Math.PI ? spinA - (float) (Math.PI * 2) : spinA) : 0;
            coin(focusItem, gx + (fx - gx) * e, gy + (fy - gy) * e, gr + (fr - gr) * e, -0.12f * (1 - e) + rotX, ay0 * (1 - e) + rotY + (float) Math.sin(clock * 0.7) * 0.18f * e, 1, detailReady, Float.NaN);
            if (focusIx >= 0) { lastFocus = focusIx; lastFocusSpot = focusSpot; }
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindVertexArray(0);
    }

    private int lastFocus = -1;
    private boolean lastFocusSpot;

    /** 每帧勋章程序共用的 uniform */
    private void coinFrame(float sheenU, float W) {
        pCoin.m4("uVP", VP);
        pCoin.f3("uEye", hall.w / 2, -hall.h / 2, eyeZ);
        pCoin.f("uDay", day);
        pCoin.f("uSheen", sheenU);
    }

    /** 这一行露出来的第一枚、最后一枚 */
    private int kFirst(int r, Hall g) { return Math.max(0, (int) Math.floor((rx[r] - g.padL) / g.cellW) - 1); }
    private int kLast(int r, Hall g, int n) { return Math.min(n - 1, (int) Math.floor((rx[r] + g.gx1 - g.gx0) / g.cellW) + 1); }

    /** 背景（同车况页的 Bg）：深色那层按 CSS 的 ease 曲线过渡；图片背景还没传上去时是深灰 */
    private void drawBg(float W, float H) {
        if (bgKind == 3 && bgTex == 0) {
            GLES30.glClearColor(0x1a / 255f, 0x1c / 255f, 0x20 / 255f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        pBg.use();
        int k = Math.min(2, bgKind);
        String[] ln = { "uL0", "uL1", "uL2" }, dn = { "uD0", "uD1", "uD2" };
        for (int i = 0; i < 3; i++) {
            int a = BG_L[k][i], b = BG_D[k][i];
            pBg.f3(ln[i], ((a >> 16) & 255) / 255f, ((a >> 8) & 255) / 255f, (a & 255) / 255f);
            pBg.f3(dn[i], ((b >> 16) & 255) / 255f, ((b >> 8) & 255) / 255f, (b & 255) / 255f);
        }
        pBg.f("uK", cssEase(1 - day));
        boolean img = bgKind == 3;
        pBg.f("uImg", img ? 1 : 0);
        pBg.f("uDim", img ? bgDim : 0);
        if (img) { // cover：短的那边撑满，长的那边裁掉
            float ia = bgImgW / bgImgH, va = W / Math.max(1f, H);
            if (ia > va) pBg.f2("uImgS", va / ia, 1); else pBg.f2("uImgS", 1, ia / va);
            pBg.tex("tImg", 0, bgTex);
        } else pBg.f2("uImgS", 1, 1);
        GLES30.glBindVertexArray(quadVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** 打开 / 关上时第 vi 行（从看得见的第一行数起）浮上来了多少：总览先出来，一行比一行晚一点 */
    private float rowK(int vi) {
        float k = intro * 1.75f - 0.2f - 0.12f * Math.max(0, vi);
        return easeOut(Math.max(0, Math.min(1, k)));
    }

    /** CSS 的 ease（cubic-bezier(.25, .1, .25, 1)），同车况页背景深色那层的过渡 */
    private static float cssEase(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        float lo = 0, hi = 1;
        for (int i = 0; i < 20; i++) { float t = (lo + hi) / 2, bx = bez(t, 0.25f, 0.25f); if (bx < x) lo = t; else hi = t; }
        return bez((lo + hi) / 2, 0.1f, 1f);
    }
    private static float bez(float t, float p1, float p2) { float u = 1 - t; return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t; }

    private static float ease(float t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** 一块平面（像素坐标：左上角 x、y、宽、高；y 朝下），z 是前后（越大越靠前） */
    private void rect(G.Prog p, float x, float y, float w, float h, float z) {
        p.f4("uRect", x, -(y + h), w, h);
        p.f("uZ", z);
        GLES30.glBindVertexArray(quadVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** 画一枚：中心 (cx, cy)（像素，y 朝下）、半径 R、绕 x 转 ax（往后仰为负）、绕 y 转 ay；ledge 不是 NaN 时画它在台面（这条线，像素 y）里的倒影 */
    private void coin(Item m, float cx, float cy, float R, float ax, float ay, float alpha, boolean full, float ledge) {
        Matrix.setIdentityM(T, 0);
        Matrix.translateM(T, 0, cx, -cy, R * 0.12f);
        Matrix.setRotateM(R1, 0, (float) Math.toDegrees(ay), 0, 1, 0);
        Matrix.setRotateM(R2, 0, (float) Math.toDegrees(ax), 1, 0, 0);
        Matrix.multiplyMM(M, 0, R1, 0, R2, 0);
        nm[0] = M[0]; nm[1] = M[1]; nm[2] = M[2]; nm[3] = M[4]; nm[4] = M[5]; nm[5] = M[6]; nm[6] = M[8]; nm[7] = M[9]; nm[8] = M[10];
        Matrix.multiplyMM(R1, 0, T, 0, M, 0);
        Matrix.scaleM(R1, 0, R, R, R);
        if (!Float.isNaN(ledge)) {
            // 按台面上沿（世界坐标 y = −ledge）上下翻：y' = −2·ledge − y；法线跟着翻
            Matrix.setIdentityM(MI, 0);
            MI[5] = -1;
            MI[13] = -2 * ledge;
            Matrix.multiplyMM(TMP, 0, MI, 0, R1, 0);
            System.arraycopy(TMP, 0, R1, 0, 16);
            nm[1] = -nm[1]; nm[4] = -nm[4]; nm[7] = -nm[7];
            pCoin.f3("uRefl", -ledge, hall.rem * 0.5f, 1);
        } else pCoin.f3("uRefl", 0, 1, 0);
        pCoin.m4("uM", R1);
        GLES30.glUniformMatrix3fv(pCoin.u("uNm"), 1, false, nm, 0);
        Integer sl = slotOf.get(m.key());
        if (sl == null) sl = slotOf.get(HIDDEN); // 图案还在做：先用问号
        int slot = sl == null ? 0 : sl;
        float ah = atlasRows * SLOT;
        if (full) pCoin.f4("uSlot", 0, 0, 1, 1);
        else pCoin.f4("uSlot", (slot % COLS) * (float) SLOT / ATLAS_W, (slot / COLS) * SLOT / ah, (float) SLOT / ATLAS_W, SLOT / ah);
        float[] t = Palette.TIER[m.tier];
        pCoin.f3("uBase", t[0], t[1], t[2]);
        pCoin.f("uRough", t[3]);
        float[] e = Palette.ENAMEL[m.cat];
        pCoin.f3("uEnam", e[0], e[1], e[2]);
        pCoin.f("uIri", m.tier == Ach.LEGEND ? 0.15f : 0);
        pCoin.f("uLock", m.unlocked ? 0 : 1);
        pCoin.f("uAlpha", alpha);
        pCoin.f("uReed", !m.unlocked || m.tier == Ach.BRONZE ? 1 : 0);
        GLES30.glBindVertexArray(coinVao);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, coinCount, GLES30.GL_UNSIGNED_SHORT, 0);
        int pl = m.unlocked ? plateVao[m.tier] : 0;
        if (pl != 0) {
            GLES30.glBindVertexArray(pl);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, plateCount[m.tier], GLES30.GL_UNSIGNED_SHORT, 0);
        }
    }

    // ================= 解锁庆祝 =================
    private static final int PN = 180;
    private final float[] px = new float[PN], py = new float[PN], pvx = new float[PN], pvy = new float[PN], plife = new float[PN], psize = new float[PN];
    private final FloatBuffer pbuf = ByteBuffer.allocateDirect(PN * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private long seed = 7;

    private float rnd() { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed >>> 33) & 0xffffff) / (float) 0x1000000; }

    private void burst() {
        for (int i = 0; i < PN; i++) {
            double a = rnd() * Math.PI * 2;
            float sp = 180 + rnd() * 620;
            px[i] = 0; py[i] = 0;
            pvx[i] = (float) Math.cos(a) * sp;
            pvy[i] = (float) Math.sin(a) * sp + 120;
            plife[i] = 0.9f + rnd() * 1.4f;
            psize[i] = 2.5f + rnd() * 5;
        }
    }

    private void drawCeleb(float W, float H) {
        float t = celebT;
        boolean narrow = W / H < 0.8f;
        float cx = W / 2, cy = narrow ? H * 0.36f : H * 0.42f, fr = narrow ? Math.min(W * 0.3f, H * 0.18f) : Math.min(H * 0.22f, W * 0.2f);
        if (celebMore.length > 0) fr *= 0.82f; // 合成的庆祝：中间那枚小一点，两边放得下
        float[] e = Palette.ENAMEL[celebItem.cat], tc = Palette.TIER[celebItem.tier];
        // 光芒
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        pRays.use();
        pRays.m4("uVP", VP);
        pRays.f("uT", clock);
        pRays.f("uA", ease(Math.min(1, t / 0.6f)) * (day > 0.5f ? 0.55f : 0.85f));
        pRays.f3("uCol", (tc[0] + 1) * 0.5f, (tc[1] + 0.9f) * 0.5f, (tc[2] + 0.7f) * 0.5f);
        float rr = fr * 3.4f;
        rect(pRays, cx - rr, cy - rr, rr * 2, rr * 2, 2);
        // 勋章：从小转着飞出来（转两圈），再轻轻晃
        float k = Math.min(1, t / 1.5f), sc = k < 1 ? 0.25f + 0.75f * back(k) : 1;
        float spin = (1 - easeOut(k)) * (float) Math.PI * 4;
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        pCoin.use();
        pCoin.f("uSheen", t > 1.2f && t < 2.4f ? (t - 1.2f) / 1.2f * W * 1.4f - W * 0.7f : -1e5f);
        // 同时解锁的另外几枚：排在两边（近的大、远的小，稍微往下），比中间那枚晚一点依次飞出来，画在中间那枚后面
        if (celebMore.length > 0) {
            pCoin.tex("uTex", 0, atlas);
            for (int j = 0; j < celebMore.length; j++) {
                int side = j % 2 == 0 ? -1 : 1, rank = j / 2;
                float kj = Math.min(1, Math.max(0, (t - 0.25f - j * 0.12f) / 1.2f));
                if (kj <= 0) continue;
                float sj = rank == 0 ? 0.66f : 0.5f, scj = (kj < 1 ? 0.25f + 0.75f * back(kj) : 1) * sj;
                float off = Math.min(fr * (rank == 0 ? 1.15f : 2.0f), W * 0.5f - fr * sj * 1.32f - 4); // 连星芒都在窗口里
                float spinj = (1 - easeOut(kj)) * (float) Math.PI * 3;
                coin(celebMore[j], cx + side * off, cy + fr * (rank == 0 ? 0.12f : 0.3f), fr * scj, rotX - 0.05f, spinj - side * 0.35f + rotY, 1, false, Float.NaN);
            }
        }
        pCoin.tex("uTex", 0, detailReady ? detail : atlas);
        Item m = celebItem;
        boolean was = m.unlocked;
        m.unlocked = true;
        coin(m, cx, cy, fr * sc, rotX - 0.05f, spin + rotY + (float) Math.sin(clock * 0.7) * 0.15f * k, 1, detailReady, Float.NaN);
        m.unlocked = was;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        // 火花
        float dt2 = Math.min(0.05f, t - lastBurstT);
        lastBurstT = t;
        pbuf.clear();
        int n = 0;
        for (int i = 0; i < PN; i++) {
            if (t > plife[i] || t < 0.15f) continue;
            pvy[i] -= 520 * dt2;
            pvx[i] *= (float) Math.exp(-dt2 * 1.2f);
            px[i] += pvx[i] * dt2; py[i] += pvy[i] * dt2;
            float a = 1 - t / plife[i];
            pbuf.put(cx + px[i]).put(-(cy - py[i])).put(psize[i]).put(a * a);
            n++;
        }
        if (n > 0) {
            pbuf.position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ptsVbo);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, n * 16, pbuf);
            pPts.use();
            pPts.m4("uVP", VP);
            pPts.f("uPx", scale);
            pPts.f3("uCol", (tc[0] + e[0]) * 0.6f, (tc[1] + e[1]) * 0.6f, (tc[2] + e[2]) * 0.6f);
            GLES30.glBindVertexArray(ptsVao);
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n);
        }
    }

    private float lastBurstT;

    /** 庆祝结束（主线程点了继续） */
    void celebDone() { celebrate = null; celebRev++; wake(); }

    private static float easeOut(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
    private static float back(float t) { float c1 = 1.70158f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2); }

    // ================= 分类标题、名字（字的图集） =================
    private static final Typeface UI600 = Typeface.create(Typeface.DEFAULT, 600, false), UI700 = Typeface.create(Typeface.DEFAULT, 700, false);
    private String labelKey = "";
    private float[][] nameUv = new float[0][], headUv = new float[0][];
    /** 每行标题字有多宽（窗口像素；进度条接在后面） */
    private float[] headW = new float[0];
    private float nameW, nameH, headSW, headSH;

    /** 字的图集：上面一段是每行的标题（分类色小圆点 + 分类名 + 拿到几枚 / 一共几枚），下面是每枚的名字；内容、大小、深浅色变了才重画 */
    private void labelsFor(Rows d, Hall g) {
        boolean dd = day > 0.5f;
        if (!labelKey.isEmpty() && labelDay == dd) return;
        StringBuilder sb = new StringBuilder();
        sb.append(Math.round(g.cellW)).append('|').append(Math.round(g.rem)).append('|').append(dd).append('|').append(scale);
        for (int r = 0; r < d.rows(); r++) sb.append('|').append(d.names[r]).append(d.got[r]).append('/').append(d.base[r]);
        for (Item m : d.items) sb.append('|').append(m.name).append(m.unlocked);
        String key = sb.toString();
        labelDay = dd;
        if (key.equals(labelKey)) return;
        labelKey = key;
        nameW = g.cellW * 0.98f;
        nameH = g.rem * 0.36f;
        headSW = g.rem * 4.2f;
        headSH = g.rem * 0.5f;
        float s = scale;
        int ew = Math.max(8, Math.round(nameW * s)), eh = Math.max(8, Math.round(nameH * s)), hw = Math.max(8, Math.round(headSW * s)), hh = Math.max(8, Math.round(headSH * s));
        int nr = d.rows(), n = d.items.length;
        int hPer = Math.max(1, 2048 / hw), hRows = (nr + hPer - 1) / hPer, nPer = Math.max(1, 2048 / ew), nRows = (n + nPer - 1) / nPer;
        int aw = Math.min(2048, Math.max(hPer * hw, nPer * ew)), ah = Math.max(8, Math.min(2048, hRows * hh + nRows * eh));
        Bitmap b = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ink = dd ? 0xff14171c : 0xffeef1f6, mute = dd ? 0x8014171c : 0x80eef1f6;
        headUv = new float[nr][];
        headW = new float[nr];
        for (int r = 0; r < nr; r++) {
            float x0 = (r % hPer) * hw, y0 = (r / hPer) * hh, cyl = y0 + hh / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.SRGB[r % Palette.SRGB.length]);
            c.drawCircle(x0 + g.rem * 0.06f * s, cyl, g.rem * 0.055f * s, p);
            float x = x0 + g.rem * 0.2f * s;
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(UI700);
            p.setTextSize(g.rem * 0.26f * s);
            p.setColor(ink);
            c.drawText(d.names[r], x, base(cyl, p), p);
            x += p.measureText(d.names[r]) + g.rem * 0.14f * s;
            p.setTypeface(num);
            p.setTextSize(g.rem * 0.22f * s);
            p.setColor(mute);
            String cnt = d.got[r] + " / " + d.base[r];
            c.drawText(cnt, x, base(cyl, p), p);
            x += p.measureText(cnt);
            headW[r] = (x - x0) / s;
            headUv[r] = new float[] { x0 / aw, y0 / ah, (float) hw / aw, (float) hh / ah };
        }
        nameUv = new float[n][];
        float yb = hRows * hh;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(UI600);
        p.setTextSize(g.rem * 0.19f * s);
        for (int i = 0; i < n; i++) {
            float x0 = (i % nPer) * ew, y0 = yb + (i / nPer) * eh;
            if (y0 + eh > ah) { nameUv[i] = new float[] { 0, 0, 0, 0 }; continue; }
            Item m = d.items[i];
            p.setColor(m.unlocked ? ink : mute);
            c.drawText(ellipsize(p, m.name, ew * 0.96f), x0 + ew / 2f, base(y0 + eh / 2f, p), p);
            nameUv[i] = new float[] { x0 / aw, y0 / ah, (float) ew / aw, (float) eh / ah };
        }
        if (labels != 0) G.free(labels);
        labels = G.texture(b, false);
        b.recycle();
    }

    private boolean labelDay;

    private static float base(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    private static String ellipsize(Paint p, String s, float w) {
        if (s == null) return "";
        if (p.measureText(s) <= w) return s;
        for (int n = s.length() - 1; n > 0; n--) { String t = s.substring(0, n) + "…"; if (p.measureText(t) <= w) return t; }
        return s;
    }

    void size(int w, int h) { vw = Math.max(1, w); vh = Math.max(1, h); wake(); }
}
