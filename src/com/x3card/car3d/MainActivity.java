package com.x3card.car3d;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.x3card.car3d.ach.AchPage;
import com.x3card.car3d.ach.Trophies;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 3D 车况原生版（照网页版 demo3d 的车况页，没有唱片机页）：3D 画布（SceneView + Stage + CarPage）+ 上面一层界面（CarUi：字、图标、胎压标签、齿轮、设置面板）
 * + 最上面的说明（InfoView：连不上宿主、没授权时）。
 * 宿主的数据（CarData）在主线程收，交给 GL 线程里的 CarPage；CarPage 交出来的界面变化由 CarUi 切回主线程。
 * 全屏同琉光原生版：自己在主屏上开一个全屏页（FullActivity，单独的任务），在全屏页里点"恢复小屏"关掉它；全屏页开着时小窗口里停画。设置两边共用，回到前台重新读。
 * 成就（ach/）：看得见时记录（Trophies，自己订一组宿主数据）；新解锁的在右上角滑出提示（CarUi.achToast），奖杯键打开整页的成就墙（AchPage，盖在车上，开着时车况页停画）
 */
public class MainActivity extends Activity implements Uiplus.Listener, CarData.Listener, CarUi.Actions {
    private static WeakReference<MainActivity> pip = new WeakReference<>(null);
    private static final int PICK_MODEL = 1, PICK_BG = 2;

    private Host host;
    private CarData data;
    private Settings S;
    private FrameLayout root;
    private SceneView view;
    private Stage stage;
    private CarPage page;
    private CarUi ui;
    private InfoView info;
    private InfoPanel infoP;
    private DebugPanel dbgP;
    private PlatePanel plateP;
    private Trophies tro;
    private AchPage achP;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread io = new HandlerThread("io");
    private Handler ioH;
    private boolean started, slept;
    private long fsAt;

    /** 全屏页（FullActivity）是真；多窗口里、从桌面直接打开的是假 */
    boolean isFull() { return false; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isFull()) pip = new WeakReference<>(this);
        io.start();
        com.x3card.car3d.gl.Prog.cacheDir(new java.io.File(getCacheDir(), "shaders"));
        ioH = new Handler(io.getLooper());
        S = new Settings(this);
        host = DebugHost.wanted(this) ? new DebugHost(this, this) : new Uiplus(this, this); // 不是车机：调试宿主（车况是模拟的，调试面板里改）
        data = new CarData(host, this);
        data.onActions = this::acOk;
        tro = new Trophies(this, link(), host instanceof DebugHost);
        root = new FrameLayout(this);
        ui = new CarUi(this, S, this);
        info = new InfoView(this, w -> { if ("auth".equals(w)) authorize(); });
        newScene();
        root.addView(info, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        infoP = new InfoPanel(this, S, host, this::sheetClosed);
        ui.addView(infoP, new FrameLayout.LayoutParams(-1, -1));
        if (host instanceof DebugHost) {
            dbgP = new DebugPanel(this, S, (DebugHost) host, this::sheetClosed);
            ui.addView(dbgP, new FrameLayout.LayoutParams(-1, -1));
            ui.hud.setDemo(true);
            ui.panel.setDebug(true);
        }
        plateP = new PlatePanel(this, S, new PlatePanel.Act() {
            @Override public void plates(JSONObject st) { pushPlates(st); }
            @Override public void edit(String side) { final CarPage p = page; view.queueEvent(() -> p.plateEdit(side)); }
            @Override public void closed() {
                final CarPage p = page;
                view.queueEvent(() -> p.plateEdit(null));
                ui.panel.setPlates(Plates.state(plateP.current()));
                sheetClosed();
            }
        });
        ui.addView(plateP, new FrameLayout.LayoutParams(-1, -1));
        achP = new AchPage(this);
        root.addView(achP, new FrameLayout.LayoutParams(-1, -1));
        ui.onTheme = (d, k) -> achP.setDark(d, k);
        tro.listen(new Trophies.Listener() {
            @Override public void unlocked(List<com.x3card.car3d.ach.Ach> g) { if (achP.isOpen()) achP.unlocked(g); else ui.achToast.show(g); }
            @Override public void pending(int n) { ui.setBadge(n); }
            @Override public void changed() { achP.changed(); }
        });
        ui.setBadge(tro.pendingCount());
        setContentView(root);
        ui.darkNow(page.isDark());
        ui.hud.setHint(page.hintShown());
        ui.setBlocked(info.covering());
        S.on(k -> { if (Looper.myLooper() == Looper.getMainLooper()) setting(k); else main.post(() -> setting(k)); });
        data.extra(statFields());
        applyBar();
    }

    /** 新的一套画布（第一次、换抗锯齿时）：Stage、CarPage 跟着画布走（GL 的东西都在画布的上下文里） */
    private void newScene() {
        stage = new Stage(getAssets(), S.bool("aa"));
        stage.fpsCap = "30".equals(S.str("fps")) ? 30 : 60;
        stage.fx = S.fx();
        page = new CarPage(this, S, ui);
        page.needs = () -> main.post(this::wants);
        page.plateOut = plateOut;
        page.unitOf = data::unit;
        view = new SceneView(this, stage, S.bool("aa"));
        view.setRatio(S.fx().ratio);
        root.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
        final CarPage p = page;
        final Map<String, Object> car = data.car(), sys = data.sys();
        final float tm = ui.hud.msgTop / getResources().getDisplayMetrics().density;
        view.queueEvent(() -> { p.setCar(car); p.setSys(sys); if (tm > 0) p.setTagMax(tm); });
        stage.setPage(page);
        acOk();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        reload();
        ui.setFsMode(isFull() ? "small" : onMainScreen() ? "" : "full");
        if (!slept) wake(true);
        host.start();
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(true); }
    }
    @Override protected void onStop() {
        started = false;
        wake(false);
        host.background(); // 不马上断开：宿主的授权确认页会把这里盖住，授权申请挂在这个会话上
        if (isFull()) { MainActivity p = pip.get(); if (p != null && p != this) p.sleep(false); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        host.quit();
        io.quitSafely();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (achP.back()) return;
        if (ui.panelOpen()) ui.panel(false);
        else if (plateP.isOpen()) plateP.show(false);
        else if (infoP.isOpen()) infoP.show(false);
        else if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        else super.onBackPressed();
    }

    /** 看得见 / 看不见：画布停画、车况退订（同网页版：页面藏起来取消订阅，回来重订） */
    private void wake(boolean on) {
        if (on) { view.onResume(); view.queueEvent(() -> stage.setVisible(true)); ui.setBadge(tro.pendingCount()); }
        else { achP.closeNow(); view.queueEvent(() -> stage.setVisible(false)); view.onPause(); }
        wants(on);
        tro.setVisible(on);
    }
    private void wants() { wants(started && !slept); }
    private void wants(boolean on) {
        final CarPage p = page;
        data.use(on, on && p.wantsMedia());
    }
    /** 全屏页开着：小窗口里停画；关了接着画，顺便读一下全屏页里改过的设置 */
    void sleep(boolean s) {
        if (s == slept) return;
        slept = s;
        if (!started) return;
        if (s) wake(false); else { reload(); wake(true); }
    }
    private boolean onMainScreen() {
        Display d = getWindowManager().getDefaultDisplay();
        return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY;
    }

    // ================= 设置 =================
    /** 回到前台：全屏页和小窗口共用一份设置，另一边改过的跟上 */
    private void reload() {
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        for (String k : KEYS) before.put(k, S.get(k));
        S.load();
        for (String k : KEYS) if (!String.valueOf(before.get(k)).equals(String.valueOf(S.get(k)))) S.emit(k);
        final CarPage p = page;
        view.queueEvent(p::platesFromStore); // 车牌：全屏页、小窗口另一边可能改过
    }
    private static final String[] KEYS = { "paint", "rim", "caliper", "finish", "paintHex", "rimHex", "calHex", "theme", "bg", "bgDim", "viewAz", "viewEl", "viewZoom", "viewFov",
        "stats", "labels", "intro", "quality", "fps", "aa", "fsBar" };

    /** 一项设置变了（主线程） */
    private void setting(String k) {
        final CarPage p = page;
        switch (k) {
            case "aa": rebuild(); break;
            case "quality": view.setRatio(S.fx().ratio); stage.fx = S.fx(); view.queueEvent(() -> stage.setPage(p)); break; // 画质一变车况页重建（材质按有没有阴影重新编译）
            case "fps": final int f = "30".equals(S.str("fps")) ? 30 : 60; view.queueEvent(() -> stage.fpsCap = f); break;
            case "fsBar": applyBar(); break;
            case "model": break;
            case "stats": data.extra(statFields()); view.queueEvent(() -> p.setting(k)); break;
            default: view.queueEvent(() -> p.setting(k));
        }
        ui.panel.refresh(k);
    }
    /** 底部数据选了的那几项要的字段（常驻订阅里没有的另外订） */
    private List<String> statFields() {
        List<String> l = new ArrayList<>();
        for (String id : S.stats()) { String f = CarStat.field(id); if (f != null) l.add(f); }
        return l;
    }
    /** 抗锯齿只能在建画布时定：换一块新画布（车况页重建） */
    private void rebuild() {
        SceneView old = view;
        old.onPause();
        root.removeView(old);
        newScene();
        if (!started || slept) view.onPause();
        else view.queueEvent(() -> stage.setVisible(true));
        ui.panel.refresh("");
    }
    private void applyBar() {
        if (!isFull()) return;
        if (S.bool("fsBar")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    // ================= 面板里的按钮 =================
    @Override public void act(String name) {
        final CarPage p = page;
        switch (name) {
            case "import": pick(PICK_MODEL, "*/*"); break;
            case "modelReset":
                ui.panel(false);
                CarModel.reset(this);
                view.queueEvent(p::reload);
                ui.toast("换回默认车模了", 2600);
                break;
            case "bgImage": pick(PICK_BG, "image/*"); break;
            case "debug": debug(); break;
            case "achOpen": trophies(); break;
            case "plates": plates(); break;
            case "achReset": tro.reset(); ui.toast("成就记录清空了", 2600); break;
            case "bgUse": if (Background.has(this)) S.set("bg", "image"); else pick(PICK_BG, "image/*"); break; // 没存过图片：先选一张
            default: view.queueEvent(() -> p.setting("act:" + name));
        }
    }
    private void pick(int code, String type) {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), code); }
        catch (Exception e) { ui.toast("这台车机打不开选文件的界面", 3500); }
    }
    @Override protected void onActivityResult(int code, int res, Intent d) {
        super.onActivityResult(code, res, d);
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        final Uri uri = d.getData();
        if (code == PICK_MODEL) {
            ui.panel(false);
            ui.busy("正在导入车模");
            ui.step("读取文件", 0.01f);
            ioH.post(() -> {
                String err = null;
                long t0 = SystemClock.uptimeMillis();
                // 读文件 0.01～0.12、检查 0.13、存下来 0.14～0.16；后面的（解析、建车、第一次画）在 CarPage.reload 里接着走
                try { CarModel.importPack(this, uri, (s, f) -> main.post(() -> ui.step(s, f))); }
                catch (Exception e) { err = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : "导入失败：" + e.getMessage(); Log.w(Uiplus.TAG, "导入车模包", e); }
                Log.w("car3d", "导入：读文件、检查、保存 " + (SystemClock.uptimeMillis() - t0) + " 毫秒");
                final String er = err;
                main.post(() -> {
                    if (er != null) { ui.busy(null); ui.toast(er, 5000); return; }
                    // 换了一台车：车漆、轮毂、卡钳、质感都回到车模自己的样子
                    for (String k : new String[] { "paint", "rim", "caliper" }) S.set(k, "model");
                    S.set("finish", "preset");
                    final CarPage p = page;
                    view.queueEvent(() -> p.reload(true));
                });
            });
        } else if (code == PICK_BG) {
            ioH.post(() -> {
                String err = null;
                try { Background.save(this, uri); } catch (Exception e) { err = e instanceof IllegalArgumentException ? e.getMessage() : "这张图片用不了"; }
                final String er = err;
                main.post(() -> {
                    if (er != null) { ui.toast(er, 3500); return; }
                    final CarPage p = page;
                    if ("image".equals(S.str("bg"))) view.queueEvent(p::bgChanged); else S.set("bg", "image");
                });
            });
        }
    }

    // ================= 界面交过来的 =================
    @Override public void statusTap() { final CarPage p = page; view.queueEvent(p::statusTap); }
    @Override public void statusPick(int i) { final CarPage p = page; view.queueEvent(() -> p.statusPick(i)); }
    @Override public void statusClose() { final CarPage p = page; view.queueEvent(p::statusClose); }
    /** 全部车况：设置开着先关掉；车让到旁边 */
    @Override public void allStatus() {
        if (ui.panelOpen()) ui.panel(false);
        if (plateP.isOpen()) plateP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        infoP.open();
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 调试面板（"调试数据"、设置里那一行）：再点一下关上；设置、全部车况开着先关掉；车让到旁边（同全部车况） */
    @Override public void debug() {
        if (dbgP == null) return;
        if (dbgP.isOpen()) { dbgP.show(false); return; }
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (plateP.isOpen()) plateP.show(false);
        dbgP.show(true);
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 车牌（设置里"调整车牌"）：设置、全部车况、调试面板先关掉；车让到旁边，镜头转过去看着这块车牌 */
    private void plates() {
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        if (!plateP.ready()) { ui.toast("车模还在载入，等一下再打开", 2600); return; }
        plateP.open();
        ui.infoOpen(true);
        final CarPage p = page;
        view.queueEvent(() -> p.aside(true));
    }
    /** 车上交回来的车牌（建好车时的配置、自动摆的 / 拖完的位置）：已经在主线程 */
    private final CarPage.PlateOut plateOut = new CarPage.PlateOut() {
        @Override public void plates(JSONObject st, float[] dims, String key) {
            if (plateP == null) return;
            plateP.setData(st, dims, key);
            ui.panel.setPlates(Plates.state(st));
        }
        @Override public void plateAt(String which, JSONArray at, boolean drag) {
            if (plateP == null) return;
            plateP.plateAt(which, at, drag);
            ui.panel.setPlates(Plates.state(plateP.current()));
        }
    };
    /** 车牌改了交给车：拖滑块时一帧只交最新的一份 */
    private final Object plateLock = new Object();
    private JSONObject plateNext;
    private boolean platePosted;
    private void pushPlates(JSONObject st) {
        synchronized (plateLock) { plateNext = st; if (platePosted) return; platePosted = true; }
        final CarPage p = page;
        view.queueEvent(() -> {
            JSONObject s;
            synchronized (plateLock) { s = plateNext; plateNext = null; platePosted = false; }
            if (s != null) p.setPlates(s);
        });
    }
    /** 全部车况、调试面板、车牌关上了：都关着才把车挪回来 */
    private void sheetClosed() {
        boolean any = infoP.isOpen() || dbgP != null && dbgP.isOpen() || plateP != null && plateP.isOpen();
        ui.infoOpen(any);
        if (any) return;
        final CarPage p = page;
        view.queueEvent(() -> p.aside(false));
    }
    /** 成就墙（奖杯键、弹出的提示、设置里那一行）：设置、全部车况这些先关掉；字和键淡出、车况页停画，关上以后恢复 */
    @Override public void trophies() {
        if (achP.isOpen() || !started || slept) return;
        if (ui.panelOpen()) ui.panel(false);
        if (infoP.isOpen()) infoP.show(false);
        if (dbgP != null && dbgP.isOpen()) dbgP.show(false);
        if (plateP.isOpen()) plateP.show(false);
        ui.achToast.clear();
        String b = S.str("bg");
        achP.setDark(ui.darkTo(), ui.hud.darkK());
        achP.setAccent(accent());
        achP.setBg("warm".equals(b) ? 1 : "cool".equals(b) ? 2 : "image".equals(b) ? 3 : 0, S.num("bgDim"), () -> Background.load(this));
        achP.open(tro, S.bool("aa"), "30".equals(S.str("fps")) ? 30 : 60, () -> {
            ui.animate().cancel();
            ui.setVisibility(View.VISIBLE);
            ui.animate().alpha(1).setDuration(250).start();
            final Stage st = stage;
            final boolean vis = started && !slept;
            view.queueEvent(() -> st.setVisible(vis));
        });
        ui.animate().alpha(0).setDuration(200).withEndAction(() -> { if (achP.isOpen()) ui.setVisibility(View.INVISIBLE); }).start();
        final Stage st = stage;
        view.queueEvent(() -> st.setVisible(false));
    }
    /** 强调色（同设置面板：跟着车漆） */
    private int accent() {
        String a = S.pick("paint").acc;
        try { if (a != null) return android.graphics.Color.parseColor(a); } catch (Exception ignored) { }
        return 0xffff4b55;
    }
    @Override public void roofTap() { final CarPage p = page; view.queueEvent(p::roofTap); }
    @Override public void tagMax(float y) { final CarPage p = page; view.queueEvent(() -> p.setTagMax(y)); }
    @Override public void panel(boolean open) { final CarPage p = page; view.queueEvent(() -> p.focus(open)); }
    /** 车顶空调键：宿主的原生操作 vehicle.ac.setPower（不用车主确认）；失败交回车况页（变回去、说原因） */
    @Override public void acSet(boolean on) {
        final CarPage p = page;
        if (!host.canExec()) { view.queueEvent(() -> p.roofFail("要先在宿主里允许「执行操作」")); authorize(); return; }
        try {
            host.request("vehicle.ac.setPower", new JSONObject().put("enabled", on), (r, code, msg) -> {
                if (code == null) return;
                final String m = "PERMISSION_DENIED".equals(code) ? "没有执行操作的授权：到宿主的「应用授权」里给 3D 车况打开" : "空调没" + (on ? "开" : "关") + "成" + (msg != null && !msg.isEmpty() ? "：" + msg : "");
                view.queueEvent(() -> p.roofFail(m));
            });
        } catch (Exception ignored) { }
    }
    /** 宿主能不能开关空调（能力目录里有这个操作）：没有就不显示车顶空调键 */
    private void acOk() {
        final boolean ok = data.actions().contains("vehicle.ac.setPower");
        final CarPage p = page;
        view.queueEvent(() -> { p.acOk = ok; if (stage.renderer != null) stage.invalidate(); });
    }

    // ================= 全屏 =================
    @Override public void fullscreen() {
        long t = SystemClock.uptimeMillis();
        if (t - fsAt < 2500) return; // 点一次 2.5 秒内不再切
        fsAt = t;
        if (isFull()) { finishAndRemoveTask(); return; }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            startActivity(new Intent(this, FullActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle());
        } catch (Exception e) {
            Log.w(Uiplus.TAG, "全屏打开失败", e);
            fsAt = 0;
            ui.toast("这台车机不让全屏打开", 3000);
        }
    }

    // ================= 宿主 =================
    @Override public void onState(String state, String detail) {
        info.setStatus(detail, "error".equals(state));
        info.setPerms(host.canRead(), host.ready());
        ui.setBlocked(info.covering());
        data.hostChanged();
        tro.hostChanged();
    }
    @Override public void onPermissions(boolean read, boolean exec) {
        info.setPerms(read, host.ready());
        ui.setBlocked(info.covering());
        data.hostChanged();
        tro.hostChanged();
    }
    @Override public void onEvent(JSONObject m) { if (!tro.onEvent(m) && !infoP.onEvent(m)) data.onEvent(m); }
    /** 成就用的宿主（同 Host） */
    private Trophies.Link link() {
        return new Trophies.Link() {
            @Override public void request(String m, JSONObject p, Uiplus.Reply r) { host.request(m, p, r); }
            @Override public String session() { return host.session(); }
            @Override public boolean ready() { return host.ready(); }
            @Override public boolean canRead() { return host.canRead(); }
            @Override public boolean backendReady() { return host.backendReady(); }
        };
    }
    void authorize() {
        try {
            host.authorize(new JSONObject().put("readData", true).put("executeActions", true), (r, code, msg) -> { if (code != null) ui.toast("授权没成：" + msg, 3500); });
        } catch (Exception ignored) { }
    }

    // ================= 车况数据（主线程）→ GL 线程 =================
    @Override public void car(Map<String, Object> c) { final CarPage p = page; view.queueEvent(() -> p.setCar(c)); }
    @Override public void sys(Map<String, Object> s) { final CarPage p = page; view.queueEvent(() -> p.setSys(s)); }
    @Override public void media(JSONObject m) { final CarPage p = page; view.queueEvent(() -> p.setMedia(m)); }
}
