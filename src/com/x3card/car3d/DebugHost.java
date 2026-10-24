package com.x3card.car3d;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.x3card.car3d.gl.Half;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调试宿主（照网页版 demo3d/debughost.js）：没装宿主、或者这台机器不是比亚迪车机（模拟器、手机）时顶替真宿主，
 * 照接口文档的样子返回数据、接受调用，应用照常走车机上那条路（新版宿主格式：{data: 业务树}、数字编码、同节点伴随属性；订阅首次全量、之后只推变了的叶子）。
 * 车况默认是一台停着的车，不会自己变：调试面板（DebugPanel）里选了哪项才改哪项、勾了"自动"的才自己变。
 * 原生版用得到的都有：宿主信息、能力目录、车况 / 系统数据的查询和订阅、系统属性、媒体快照与整首歌词、80 项原生操作（参数按目录校验，对得上车况字段的改模拟车况）；
 * 还能模拟车控权限关着、连接断开、下几次订阅失败。窗口、加载遮罩、模型工具这些原生版用不到，没搬。
 * 字段目录是 assets 的 fields.json（全部车况也用），操作目录是 actions.json（_apk/car3d/assets.mjs 从网页版的目录里拿出来）。改过的存在 SharedPreferences d3debug
 */
final class DebugHost implements Host {
    /** 用不用调试宿主：没装宿主，或者没有比亚迪的系统应用（不是车机）。车上永远走真宿主 */
    static boolean wanted(Context c) {
        PackageManager pm = c.getPackageManager();
        if (!installed(pm, "com.wzw.bydmodule")) return true;
        for (String p : new String[] { "com.byd.car.server", "com.byd.autovoice", "com.byd.rapidmode", "com.byd.carsettings", "com.byd.systemsettings" }) if (installed(pm, p)) return false;
        return true;
    }
    private static boolean installed(PackageManager pm, String p) {
        try { pm.getPackageInfo(p, 0); return true; } catch (Exception e) { return false; }
    }

    private final Uiplus.Listener listener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;

    DebugHost(Context c, Uiplus.Listener l) {
        listener = l;
        prefs = c.getSharedPreferences("d3debug", Context.MODE_PRIVATE);
        catalog(c);
        load();
    }

    // ================= 接口目录 =================
    final List<JSONObject> vehicle = new ArrayList<>(), system = new ArrayList<>(), media = new ArrayList<>();
    private final Map<String, JSONObject> VF = new HashMap<>(), SF = new HashMap<>(), AF = new LinkedHashMap<>();
    private final Map<String, String> COMP = new HashMap<>(); // 伴随属性 → 它的主字段
    private void catalog(Context c) {
        try {
            JSONObject f = new JSONObject(new String(Half.readAll(c.getAssets().open("fields.json")), "UTF-8"));
            add(f.optJSONArray("vehicle"), vehicle, VF);
            add(f.optJSONArray("system"), system, SF);
            add(f.optJSONArray("media"), media, null);
            for (JSONObject x : vehicle) { JSONObject cc = x.optJSONObject("c"); if (cc != null) COMP.put(base(x.optString("k")) + cc.optString("k"), x.optString("k")); }
            JSONArray a = new JSONArray(new String(Half.readAll(c.getAssets().open("actions.json")), "UTF-8"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); AF.put(o.getString("m"), o); }
        } catch (Exception e) { android.util.Log.w(Uiplus.TAG, "调试宿主的目录读不出来", e); }
    }
    private static void add(JSONArray a, List<JSONObject> list, Map<String, JSONObject> by) {
        for (int i = 0; a != null && i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o == null) continue; list.add(o); if (by != null) by.put(o.optString("k"), o); }
    }
    static String base(String k) { return k.substring(0, k.lastIndexOf('.') + 1); }
    JSONObject field(char dom, String k) { return dom == 'v' ? VF.get(k) : dom == 's' ? SF.get(k) : null; }

    // ================= 默认值：一台停着的车（数取自实车读数），手机连着车机 Wi-Fi、蓝牙 =================
    private static final Map<String, Object> DEF_V = new LinkedHashMap<>(), DEF_S = new LinkedHashMap<>(), PROPS = new HashMap<>();
    private static final Object NULL = JSONObject.NULL;
    private static void dv(Object... kv) { for (int i = 0; i < kv.length; i += 2) DEF_V.put((String) kv[i], kv[i + 1]); }
    private static void ds(Object... kv) { for (int i = 0; i < kv.length; i += 2) DEF_S.put((String) kv[i], kv[i + 1]); }
    static {
        int h0 = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        dv("driving.speed", 0, "driving.gear", 3, "driving.sessionDuration", 0, "driving.sessionDistance", 0, "driving.journeyDistance", 0, "driving.journeyDistanceUnit", 1,
            "driving.totalDistance", 12873000, "driving.totalDistanceUnit", "m", "driving.evTotalDistance", 6210, "driving.hevTotalDistance", 6663,
            "driving.evSessionDistance", 0, "driving.hevSessionDistance", 0, "driving.averageSpeed", 32, "power.level", 3, "identity.vin", "LGXCE4CB0R0123456",
            "energy.batteryPercent", 76, "energy.fuelPercent", 58, "energy.electricRange", 82, "energy.fuelRange", 640, "energy.wirelessCharging", 2, "energy.socSave", 1,
            "energy.socTarget", 25, "energy.regenerationLevel", 1, "energy.operationMode", 1, "energy.mode", 1, "energy.simulator", 0, "energy.chargingState", 1,
            "energy.connectorState", 1, "energy.charging.power", 0, "energy.charging.capacity", 0, "energy.charging.remainingTime", NULL, "energy.charging.type", 1,
            "energy.consumption.averageElectric", 15.2, "energy.consumption.averageElectricUnit", 1, "energy.consumption.recentElectric", 14.8,
            "energy.consumption.instantElectric", 0, "energy.consumption.averageFuel", 5.1, "energy.consumption.recentFuel", 4.9, "energy.consumption.instantFuel", 0,
            "energy.consumption.instantFuelUnit", 1, "energy.wirelessChargingState", 3,
            "ac.power", 0, "ac.compressor", 0, "ac.driverTemperature", 24, "ac.driverTemperatureUnit", 1, "ac.indoorTemperature", 24, "ac.indoorTemperatureUnit", 1,
            "ac.outdoorTemperature", 21, "ac.outdoorTemperatureUnit", 1, "ac.fanLevel", 0, "ac.circulation", 0, "ac.frontDefrost", 0, "ac.rearDefrost", 0,
            "seat.driver.heating", 1, "seat.passenger.heating", 1, "seat.driver.ventilation", 1, "seat.passenger.ventilation", 1, "seat.driver.beltFastened", 1,
            "body.windows.anyOpen", 0, "body.windows.ventilation", 0, "body.windows.halfOpen", 0, "body.sunroof.position", 0, "body.sunshade.position", 0,
            "body.tailgate.state", 0, "body.doors.frontLeft.state", 0, "body.doors.frontRight.state", 0, "body.doors.rearLeft.state", 0, "body.doors.rearRight.state", 0,
            "body.hood.state", 0, "body.windows.frontLeft.position", 0, "body.windows.frontRight.position", 0, "body.windows.rearLeft.position", 0, "body.windows.rearRight.position", 0,
            "light.daytimeRunning", 1, "light.side", 0, "light.lowBeam", 0, "light.dynamicColorful", 2, "light.ambient.area", 3, "light.ambient.brightness", 4,
            "light.ambient.color", 1, "light.ambient.musicMode", 2, "light.highBeam", 0, "light.turnSignal.left", 0, "light.turnSignal.right", 0,
            "light.frontFog", 0, "light.rearFog", 0, "light.hazard", 2, "light.brake", 3, "body.pedals.accelerator", 0, "body.pedals.brake", 0,
            "environment.pm25.indoor", 12, "environment.pm25.outdoor", 35, "environment.pm25.indoorLevel", 1, "environment.pm25.outdoorLevel", 2, "environment.slope", 0,
            "tyre.displayUnit", 1, "display.dayNightMode", h0 >= 19 || h0 < 6 ? 32 : 16, "engine.rpm", 0, "engine.coolantTemperature", 85,
            "warning.lowVoltageBatteryFault", 0, "warning.keyBatteryLow", 2, "audio.volume", 18, "audio.navigationVolume", 12, "audio.muted", 0,
            "maintenance.distance", 5000, "maintenance.days", 120,
            // 前后排空调、电机转速、雷达、三种告警：停着的车雷达没在工作（null）；告警照文档说的实车采样——盲区 null、开门预警和后方横向预警 0
            "ac.windMode", 1, "ac.controlMode", 1, "ac.rear.windMode", 1, "ac.rear.temperature", 24, "ac.rear.temperatureUnit", 1, "ac.rear.fanLevel", 0,
            "ac.rear.power", 0, "ac.rear.controlMode", 1, "motor.front.rpm", 0, "motor.rear.rpm", 0,
            "warning.bsd.left", NULL, "warning.bsd.right", NULL, "warning.dow.left", 0, "warning.dow.right", 0, "warning.rcta.left", 0, "warning.rcta.right", 0);
        for (String k : new String[] { "leftFront", "rightFront", "leftRear", "rightRear", "left", "right", "frontLeftMid", "frontRightMid", "middleRear" }) DEF_V.put("radar.distance." + k, NULL);
        String[] ws = { "frontLeft", "frontRight", "rearLeft", "rearRight" };
        for (int i = 0; i < 4; i++) {
            String t = "tyre." + ws[i] + ".";
            dv(t + "pressure", i == 2 ? 24 : 25, t + "pressureUnit", 1, t + "state", 0, t + "stateSource", "tyre", t + "temperature", 28); // 1 = 0.1 bar 的整数
        }
        ds("network.connected", true, "network.transport", "wifi", "network.validated", true, "network.captivePortal", false, "network.metered", false, "network.vpn", false,
            "wifi.state", 3, "wifi.enabled", true, "wifi.connected", true, "wifi.ssid", "MyPhone", "wifi.rssi", -55, "wifi.signalLevel", 3, "wifi.linkSpeed", 144, "wifi.frequency", 5180,
            "bluetooth.state", 12, "bluetooth.enabled", true, "bluetooth.connectionState", 2, "bluetooth.mediaConnectionState", 2, "bluetooth.phoneConnectionState", 2,
            "cellular.dataEnabled", true, "cellular.dataState", 2, "cellular.networkType", 13, "cellular.networkGeneration", "4G", "cellular.displayType", "4G", "cellular.simState", 5,
            "cellular.operator", "中国移动", "cellular.roaming", false, "cellular.signalLevel", 3, "cellular.signalDbm", -95, "airplane.enabled", false, "hotspot.state", 11, "hotspot.enabled", false,
            "gps.permissionGranted", true, "gps.enabled", true, "gps.latitude", 22.6847, "gps.longitude", 114.3542, "gps.altitude", 38, "gps.speed", 0, "gps.bearing", 0, "gps.accuracy", 6,
            "gps.timestamp", NULL, "gps.ageMs", 1000); // 定位时间没改过时按"现在 − 数据年龄"给
        PROPS.put("ro.build.version.release", "10"); PROPS.put("ro.build.version.sdk", "29"); PROPS.put("ro.product.manufacturer", "BYD");
        PROPS.put("ro.product.model", "DiLink"); PROPS.put("ro.build.type", "user");
    }

    // ================= 调试歌：完整一首、带重复的副歌，一句 3.5 秒 =================
    static final class Track { final String title, artist; final String[] lines; Track(String t, String a, String... l) { title = t; artist = a; lines = l; } }
    static final Track[] TRACKS = {
        new Track("夜航星", "3xCard 调试", "把星星装进后备箱", "沿着环路慢慢开", "车灯照亮一小段路", "剩下的交给月亮", "收音机里有人在唱", "唱一首没有名字的歌", "红绿灯一盏一盏亮", "像在给我数着心跳",
            "夜航星 夜航星", "你在高处陪我走", "不管开出多远", "抬头就能看见你", "隧道把城市藏起来", "出口又把灯火还给我", "夜航星 夜航星", "你在高处陪我走", "不管开出多远", "抬头就能看见你", "熄火之前再看一眼", "今晚的天很亮"),
        new Track("早高峰", "3xCard 调试", "红灯还有三十秒", "收音机在放老歌", "前车的尾灯一闪一闪", "像在打着拍子", "咖啡还有一点烫", "导航说前面在修路", "那就换一条小路", "路边的早餐摊冒着热气",
            "慢一点也没关系", "今天的风刚刚好", "车窗开一条缝", "让早晨进来", "慢一点也没关系", "今天的风刚刚好", "绿灯亮了", "我们出发"),
        new Track("回家", "3xCard 调试", "熄火之前", "再听完这一首", "车库的灯亮了", "今天也辛苦了", "后视镜里的自己", "有一点累 也有一点笑", "手机里有条消息", "说饭已经做好",
            "回家 回家", "路再远也有尽头", "回家 回家", "有人在等我", "把座椅调回原位", "把音乐调小一点", "回家 回家", "有人在等我")
    };
    private static final int LINE = 3500;
    private static long duration(int i) { return (long) TRACKS[i].lines.length * LINE + LINE; }

    // ================= 调试状态：v 车况、s 系统数据、m 媒体（都只存改过的）；sel 正在调试的项（'v:字段'）；auto 自动变化的项 → 周期秒 =================
    private JSONObject sv = new JSONObject(), ss = new JSONObject(), sm = new JSONObject();
    private final List<String> sel = new ArrayList<>();
    private final Map<String, Integer> auto = new LinkedHashMap<>();
    private int track;
    private boolean lyrOff, control = true, online = true;
    private String failCode = "BUSY";
    private int fail;
    private void load() {
        try {
            JSONObject o = new JSONObject(prefs.getString("state", "{}"));
            sv = o.optJSONObject("v") != null ? o.getJSONObject("v") : new JSONObject();
            ss = o.optJSONObject("s") != null ? o.getJSONObject("s") : new JSONObject();
            sm = o.optJSONObject("m") != null ? o.getJSONObject("m") : new JSONObject();
            JSONArray a = o.optJSONArray("sel");
            for (int i = 0; a != null && i < a.length(); i++) sel.add(a.getString(i));
            JSONObject au = o.optJSONObject("auto");
            if (au != null) for (Iterator<String> it = au.keys(); it.hasNext(); ) { String k = it.next(); int s = au.optInt(k); if (s > 0) auto.put(k, s); }
            int t = o.optInt("track", 0);
            track = t >= 0 && t < TRACKS.length ? t : 0;
            lyrOff = o.optBoolean("lyrOff");
            control = o.optBoolean("control", true);
            failCode = "RESOURCE_LIMIT".equals(o.optString("failCode")) ? "RESOURCE_LIMIT" : "BUSY";
        } catch (Exception e) { sv = new JSONObject(); ss = new JSONObject(); sm = new JSONObject(); sel.clear(); auto.clear(); }
    }
    private void save() {
        try {
            JSONObject au = new JSONObject();
            for (Map.Entry<String, Integer> e : auto.entrySet()) au.put(e.getKey(), e.getValue());
            JSONObject o = new JSONObject().put("v", sv).put("s", ss).put("m", sm).put("sel", new JSONArray(sel)).put("auto", au).put("track", track)
                .put("lyrOff", lyrOff).put("control", control).put("failCode", failCode);
            prefs.edit().putString("state", o.toString()).apply();
        } catch (Exception ignored) { }
    }
    private JSONObject store(char dom) { return dom == 'v' ? sv : dom == 's' ? ss : sm; }
    /** 现在的值（JSONObject.NULL 是没数据） */
    Object value(char dom, String k) {
        if (dom == 'm') return snap().opt(k);
        JSONObject o = store(dom);
        if (dom == 's' && "gps.timestamp".equals(k) && !o.has(k)) { Object age = value('s', "gps.ageMs"); return age instanceof Number ? (Object) (System.currentTimeMillis() - ((Number) age).longValue()) : NULL; }
        if (o.has(k)) return o.opt(k);
        Map<String, Object> d = dom == 'v' ? DEF_V : DEF_S;
        return d.containsKey(k) ? d.get(k) : NULL;
    }
    Object def(char dom, String k) { Map<String, Object> d = dom == 'v' ? DEF_V : dom == 's' ? DEF_S : null; return d != null && d.containsKey(k) ? d.get(k) : NULL; }

    // ================= 给面板的通知、调用记录 =================
    interface On { void on(String what); }
    private final List<On> fns = new ArrayList<>();
    void on(On f) { fns.add(f); }
    void off(On f) { fns.remove(f); }
    private void emit(String what) { for (On f : new ArrayList<>(fns)) f.on(what); }
    static final class Log { final long t; final String m, a, r, msg; final boolean ok; Log(String m, String a, boolean ok, String r, String msg) { t = System.currentTimeMillis(); this.m = m; this.a = a; this.ok = ok; this.r = r; this.msg = msg; } }
    private final List<Log> logs = new ArrayList<>();
    List<Log> log() { return logs; }
    void clearLog() { logs.clear(); emit("log"); }
    private static String brief(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }
    private void log(String m, JSONObject a, boolean ok, Object r, String msg) {
        logs.add(0, new Log(m, a == null ? "{}" : brief(a), ok, ok ? brief(r) : String.valueOf(r), msg == null ? "" : msg));
        while (logs.size() > 300) logs.remove(logs.size() - 1);
        emit("log");
    }
    /** 出错（同宿主：{code, message}） */
    private static final class E extends Exception { final String code; E(String code, String msg) { super(msg); this.code = code; } }

    // ================= Host =================
    @Override public void start() {
        if (started) return;
        started = true;
        main.post(() -> { if (!started) return; listener.onState("ready", "调试宿主（这台机器不是车机，车况是模拟的）"); listener.onPermissions(true, true); });
        autoRun();
    }
    @Override public void background() { }
    @Override public void stop() { started = false; for (Sub s : subs) s.live = false; subs.clear(); autoRun(); }
    @Override public void quit() { stop(); main.removeCallbacksAndMessages(null); }
    @Override public boolean ready() { return started; }
    @Override public boolean backendReady() { return started && online; }
    @Override public boolean canRead() { return true; }
    @Override public boolean canExec() { return true; }
    @Override public String session() { return started ? "debug" : null; }
    @Override public ParcelFileDescriptor openCover(String url) { return null; }
    @Override public void authorize(JSONObject permissions, Uiplus.Reply reply) {
        main.post(() -> { if (reply != null) { try { reply.done(new JSONObject().put("granted", true), null, null); } catch (Exception ignored) { } } listener.onPermissions(true, true); });
    }
    @Override public void request(String method, JSONObject params, Uiplus.Reply reply) {
        final JSONObject p = params == null ? new JSONObject() : params;
        main.post(() -> { // 结果晚一拍回来（同真宿主：单向调用、回调里给结果）
            Object r; String code = null, msg = null;
            try { r = handle(method, p); }
            catch (E e) { r = null; code = e.code; msg = e.getMessage(); }
            catch (Exception e) { r = null; code = "INVALID_ARGUMENT"; msg = String.valueOf(e.getMessage()); }
            log(method, p, code == null, code == null ? r : code, msg);
            if (reply != null) reply.done(code == null ? (r instanceof JSONObject ? (JSONObject) r : new JSONObject()) : null, code, msg);
        });
    }
    private Object handle(String m, JSONObject p) throws Exception {
        if (!started) throw new E("SESSION_CLOSED", "还没连上（调试宿主）");
        if (m.endsWith(".unsubscribe")) { unsubscribe(p.optString("subscriptionId")); return new JSONObject(); }
        if (!online) throw new E("BACKEND_UNAVAILABLE", "连接已断开（调试面板里模拟的）");
        switch (m) {
            case "info": return new JSONObject().put("apiVersion", "1.0").put("appVersion", "调试宿主").put("hostType", "dualPip");
            case "capabilities": return capabilities();
            case "authorization.request": return new JSONObject().put("granted", true);
            case "vehicle.get": return query('v', p.optJSONArray("keys"));
            case "system.get": return query('s', p.optJSONArray("keys"));
            case "vehicle.subscribe": return subscribe('v', p);
            case "system.subscribe": return subscribe('s', p);
            case "media.subscribe": return subscribe('m', p);
            case "media.get": return snap();
            case "media.getLyrics": {
                if (lyrOff || !"ok".equals(snap().optString("status"))) return new JSONObject().put("trackId", NULL).put("lines", new JSONArray());
                JSONArray l = new JSONArray();
                String[] ls = TRACKS[track].lines;
                for (int i = 0; i < ls.length; i++) l.put(new JSONObject().put("text", ls[i]).put("timeMs", i * LINE));
                return new JSONObject().put("trackId", snap().opt("trackId")).put("lines", l);
            }
            case "system.getProperty": {
                String n = p.optString("name", null);
                if (n == null || !n.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) throw new E("INVALID_ARGUMENT", "属性名不对：" + n);
                return new JSONObject().put("name", n).put("value", PROPS.containsKey(n) ? PROPS.get(n) : NULL);
            }
            default: return action(m, p);
        }
    }
    private JSONObject capabilities() throws Exception {
        JSONArray f = new JSONArray(), a = new JSONArray(), s = new JSONArray();
        for (JSONObject x : vehicle) {
            JSONObject o = new JSONObject().put("name", x.optString("k")).put("type", x.optString("t")).put("subscriptionMode", x.optString("m"));
            if (x.has("u")) o.put("unit", x.optString("u"));
            f.put(o);
        }
        for (JSONObject x : AF.values()) {
            JSONArray ps = new JSONArray(), pp = x.optJSONArray("p");
            for (int i = 0; pp != null && i < pp.length(); i++) ps.put(pp.getJSONArray(i).getString(0));
            a.put(new JSONObject().put("method", x.optString("m")).put("description", x.optString("n")).put("params", ps));
        }
        for (JSONObject x : system) {
            JSONObject o = new JSONObject().put("name", x.optString("k")).put("type", x.optString("t")).put("description", x.optString("n")).put("subscriptionMode", "native");
            if (x.has("u")) o.put("unit", x.optString("u"));
            s.put(o);
        }
        return new JSONObject().put("apiVersion", "1.0").put("fields", f).put("controls", new JSONArray()).put("actions", a).put("systemFields", s);
    }

    // ================= 车况 / 系统数据：业务树（同节点伴随属性一起给） =================
    private static void put(JSONObject d, String k, Object v) throws Exception {
        String[] ps = k.split("\\.");
        JSONObject n = d;
        for (int i = 0; i < ps.length - 1; i++) { JSONObject c = n.optJSONObject(ps[i]); if (c == null) { c = new JSONObject(); n.put(ps[i], c); } n = c; }
        n.put(ps[ps.length - 1], v == null ? NULL : v);
    }
    private JSONObject tree(char dom, Iterable<String> keys) throws Exception {
        JSONObject d = new JSONObject();
        for (String k : keys) {
            put(d, k, value(dom, k));
            JSONObject f = dom == 'v' ? VF.get(k) : null, c = f == null ? null : f.optJSONObject("c");
            if (c != null) put(d, base(k) + c.optString("k"), value(dom, base(k) + c.optString("k")));
        }
        return d;
    }
    private List<String> keys(char dom, JSONArray a) throws E {
        if (a == null || a.length() == 0) throw new E("INVALID_ARGUMENT", "字段必须是非空的字符串数组");
        List<String> l = new ArrayList<>(), bad = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object o = a.opt(i);
            if (!(o instanceof String)) throw new E("INVALID_ARGUMENT", "字段必须是非空的字符串数组");
            if ((dom == 'v' ? VF : SF).containsKey(o)) l.add((String) o); else bad.add((String) o);
        }
        if (!bad.isEmpty()) throw new E("UNKNOWN_FIELD", "未知字段：" + String.join("、", bad));
        return l;
    }
    private JSONObject query(char dom, JSONArray a) throws Exception { return new JSONObject().put("data", tree(dom, keys(dom, a))); }

    // ================= 订阅：首次全量，之后只推变了的（同一拍里变的合在一起送） =================
    static final class Sub { String id; char dom; List<String> fields; boolean live = true; Set<String> pend; }
    private final List<Sub> subs = new ArrayList<>();
    private boolean flushing;
    List<Sub> subs() { List<Sub> l = new ArrayList<>(); for (Sub s : subs) if (s.live) l.add(s); return l; }
    private Object subscribe(char dom, JSONObject p) throws Exception {
        String id = p.optString("subscriptionId", "");
        if (id.isEmpty()) throw new E("INVALID_ARGUMENT", "缺少 subscriptionId");
        List<String> fl = dom == 'm' ? Arrays.asList("media") : keys(dom, p.optJSONArray("fields"));
        if (dom == 'v') for (String k : fl) if ("none".equals(VF.get(k).optString("m"))) throw new E("UNSUPPORTED", "只能主动查询的字段不能订阅");
        for (Sub s : subs) if (s.live && s.id.equals(id)) throw new E("INVALID_ARGUMENT", "订阅号已经用过：" + id);
        if (fail > 0) { fail--; emit("host"); throw new E(failCode, "调试面板里模拟的订阅失败"); }
        if (subs().size() >= 32) throw new E("RESOURCE_LIMIT", "最多 32 个订阅");
        final Sub s = new Sub();
        s.id = id; s.dom = dom; s.fields = new ArrayList<>(fl);
        subs.add(s);
        main.postDelayed(() -> { if (s.live && online) push(s, dom == 'm' ? snap() : treeOr(s.dom, s.fields)); }, 15);
        emit("subs");
        return new JSONObject().put("subscriptionId", id);
    }
    private void unsubscribe(String id) {
        for (Sub s : subs) if (s.live && s.id.equals(id)) s.live = false;
        subs.removeIf(s -> !s.live);
        emit("subs");
    }
    private JSONObject treeOr(char dom, Iterable<String> keys) { try { return tree(dom, keys); } catch (Exception e) { return new JSONObject(); } }
    private void push(Sub s, JSONObject data) {
        try { listener.onEvent(new JSONObject().put("subscriptionId", s.id).put("data", data)); } catch (Exception ignored) { }
    }
    /** 这几个值变了：推给订阅了它们的（伴随属性推它的主字段） */
    private void changed(char dom, List<String> ks) {
        if (!online) return;
        for (Sub s : subs) {
            if (!s.live || s.dom != dom) continue;
            for (String k : ks) {
                String mk = dom == 'v' && COMP.containsKey(k) ? COMP.get(k) : k;
                if (dom == 'm' || s.fields.contains(mk)) { if (s.pend == null) s.pend = new LinkedHashSet<>(); s.pend.add(mk); }
            }
        }
        if (flushing) return;
        flushing = true;
        main.post(() -> {
            flushing = false;
            for (Sub s : new ArrayList<>(subs)) {
                if (!s.live || s.pend == null) continue;
                Set<String> ks2 = s.pend;
                s.pend = null;
                push(s, s.dom == 'm' ? snap() : treeOr(s.dom, ks2));
            }
        });
    }

    // ================= 媒体：播放时像实车一样每换一句歌词推一次快照 =================
    private boolean playing;
    private long mpos, mat;
    private final Runnable lineTick = () -> { changed('m', Arrays.asList("media")); tick(); };
    private long pos() { return playing ? Math.min(duration(track), mpos + System.currentTimeMillis() - mat) : mpos; }
    JSONObject snap() {
        JSONObject o = new JSONObject();
        try {
            Track t = TRACKS[track];
            long p = pos();
            int n = (int) Math.min(t.lines.length - 1, p / LINE);
            o.put("status", "ok").put("trackId", "debug-" + track).put("title", t.title).put("artist", t.artist).put("packageName", "com.byd.mediacenter").put("playing", playing)
                .put("positionMs", p).put("durationMs", duration(track)).put("lyric", t.lines[n]).put("coverUrl", "debug://cover/" + track).put("updatedAt", System.currentTimeMillis());
            for (Iterator<String> it = sm.keys(); it.hasNext(); ) { String k = it.next(); if (o.has(k)) o.put(k, sm.opt(k)); }
            if (!"ok".equals(o.optString("status"))) for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); if (!"status".equals(k) && !"updatedAt".equals(k)) o.put(k, NULL); } // 来源没就绪：别的属性都是 null
        } catch (Exception ignored) { }
        return o;
    }
    private void tick() {
        main.removeCallbacks(lineTick);
        if (!playing) return;
        long p = pos(), end = duration(track);
        if (p >= end) { setTrack(track + 1, true); return; } // 放完接下一首
        long next = Math.min(end, (p / LINE + 1) * LINE);
        main.postDelayed(lineTick, Math.max(30, next - p + 5));
    }
    private void setPlaying(boolean on) {
        if (playing == on) return;
        mpos = pos(); mat = System.currentTimeMillis(); playing = on;
        tick(); changed('m', Arrays.asList("media")); emit("media");
    }
    private void setTrack(int i, boolean keep) {
        track = ((i % TRACKS.length) + TRACKS.length) % TRACKS.length;
        for (String k : new String[] { "trackId", "title", "artist", "durationMs", "lyric", "coverUrl", "positionMs" }) sm.remove(k); // 换歌：歌的属性回到这首的
        mpos = 0; mat = System.currentTimeMillis();
        if (!keep) playing = false;
        save(); tick(); changed('m', Arrays.asList("media")); emit("media");
    }

    // ================= 自动变化（面板里勾了"自动"的项）：选项轮流切、数值来回扫，一秒 4 步 =================
    private long autoT0 = System.currentTimeMillis();
    private boolean autoOn;
    private final Runnable autoStep = this::autoStep;
    /** 这一项能选的值（胎压状态按现在的 stateSource；布尔是 否 / 是）：[[值, 名字], ...]；没有返回 null */
    JSONArray options(char dom, JSONObject f) {
        if (f == null) return null;
        JSONObject es = f.optJSONObject("es");
        if (es != null) { Object src = value('v', base(f.optString("k")) + "stateSource"); JSONArray o = es.optJSONArray(String.valueOf(src)); return o != null ? o : es.optJSONArray("tyre"); }
        if (f.optJSONArray("e") != null) return f.optJSONArray("e");
        if ("boolean".equals(f.optString("t"))) { try { return new JSONArray("[[false,\"否\"],[true,\"是\"]]"); } catch (Exception e) { return null; } }
        return null;
    }
    private void autoStep() {
        long t = System.currentTimeMillis() - autoT0;
        List<String> cv = new ArrayList<>(), cs = new ArrayList<>();
        for (Map.Entry<String, Integer> e : auto.entrySet()) {
            char dom = e.getKey().charAt(0);
            String k = e.getKey().substring(2);
            long per = e.getValue() * 1000L;
            JSONObject f = field(dom, k);
            if (f == null) continue;
            JSONArray o = options(dom, f), r = f.optJSONArray("r");
            Object v;
            if (o != null && o.length() > 0) v = o.optJSONArray((int) ((t / per) % o.length())).opt(0);
            else if (r != null) {
                double ph = (t % per) / (double) per, a = ph < 0.5 ? ph * 2 : 2 - ph * 2, st = r.optDouble(2, 1), lo = r.optDouble(0), hi = r.optDouble(1);
                double x = Math.round((lo + (hi - lo) * a) / st) * st;
                x = Math.round(x * 1000) / 1000.0;
                v = x == Math.rint(x) ? (Object) (long) x : (Object) x;
            } else continue;
            JSONObject st = store(dom);
            if (!same(st.opt(k), v)) { try { st.put(k, v); } catch (Exception ignored) { } (dom == 'v' ? cv : cs).add(k); }
        }
        if (!cv.isEmpty()) changed('v', cv);
        if (!cs.isEmpty()) changed('s', cs);
        if (!cv.isEmpty() || !cs.isEmpty()) emit("auto");
        if (autoOn) main.postDelayed(autoStep, 250);
    }
    private void autoRun() {
        boolean on = started && !auto.isEmpty();
        if (on && !autoOn) { autoOn = true; autoT0 = System.currentTimeMillis(); main.postDelayed(autoStep, 250); }
        if (!on && autoOn) { autoOn = false; main.removeCallbacks(autoStep); }
    }
    /** 两个值一样（1 和 1.0 算一样） */
    static boolean same(Object a, Object b) {
        if (a == null) a = NULL;
        if (b == null) b = NULL;
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }

    // ================= 原生操作：参数按目录校验，对得上车况字段的改模拟车况（编码按"30-原生操作"） =================
    private interface Fx { Object run(Object[] a) throws E; }
    private final Map<String, Fx> EFF = new HashMap<>();
    private Object V(String k) { return value('v', k); }
    private String setV(String k, Object v) { try { sv.put(k, v == null ? NULL : v); } catch (Exception ignored) { } return k; }
    private int need(String k) throws E { Object v = V(k); if (!(v instanceof Number)) throw new E("DEVICE_ERROR", "读不到 " + k + "，不执行"); return ((Number) v).intValue(); }
    private String flip01(String k) throws E { return setV(k, need(k) == 1 ? 0 : 1); }
    private String flip12(String k) throws E { return setV(k, need(k) == 1 ? 2 : 1); }
    private String cyc(String k, int... list) throws E {
        int v = need(k);
        for (int i = 0; i < list.length; i++) if (list[i] == v) return setV(k, list[(i + 1) % list.length]);
        throw new E("DEVICE_ERROR", k + " 编码不认识");
    }
    private static final String[] WIN = { "frontLeft", "frontRight", "rearLeft", "rearRight" };
    private List<String> windows(String mode) {
        List<String> ks = new ArrayList<>();
        Integer p = "open".equals(mode) ? 100 : "close".equals(mode) ? 0 : "half".equals(mode) ? 50 : "vent".equals(mode) ? 12 : null;
        if (p == null) return ks; // stop：停在原处
        for (String w : WIN) ks.add(setV("body.windows." + w + ".position", p));
        ks.add(setV("body.windows.anyOpen", p > 0 ? 1 : 0)); ks.add(setV("body.windows.halfOpen", "half".equals(mode) ? 1 : 0)); ks.add(setV("body.windows.ventilation", "vent".equals(mode) ? 1 : 0));
        return ks;
    }
    /** 设温：°C 源 17～33 整数；°F 源换算后 64～91 整数；单位不知道不执行 */
    private Fx tempOf(String k, String uk) {
        return a -> {
            double c = ((Number) a[0]).doubleValue();
            Object u = V(uk);
            if (u instanceof Number && ((Number) u).intValue() == 1) { if (c != Math.floor(c) || c < 17 || c > 33) throw new E("INVALID_ARGUMENT", "设温 17～33 °C 的整数"); return setV(k, (long) c); }
            if (u instanceof Number && ((Number) u).intValue() == 0) { double f = c * 9 / 5 + 32; if (Math.abs(f - Math.round(f)) > 1e-6 || f < 64 || f > 91) throw new E("INVALID_ARGUMENT", "换算成 °F 要是 64～91 的整数"); return setV(k, Math.round(f)); }
            throw new E("DEVICE_ERROR", "设温单位读不到");
        };
    }
    private static boolean b(Object o) { return Boolean.TRUE.equals(o); }
    private static int i(Object o) { return ((Number) o).intValue(); }
    private static final List<String> COLORS = Arrays.asList("iceBlue", "coldWhite", "warmWhite", "redOrange", "red", "redPurple", "darkBlue");
    {
        EFF.put("vehicle.ac.setPower", a -> setV("ac.power", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setCompressor", a -> setV("ac.compressor", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setDriverTemperature", tempOf("ac.driverTemperature", "ac.driverTemperatureUnit"));
        EFF.put("vehicle.ac.setFanLevel", a -> setV("ac.fanLevel", i(a[0])));
        EFF.put("vehicle.ac.setCirculation", a -> setV("ac.circulation", "inside".equals(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setFrontDefrost", a -> setV("ac.frontDefrost", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setRearDefrost", a -> setV("ac.rearDefrost", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setWindMode", a -> setV("ac.windMode", i(a[0])));
        EFF.put("vehicle.ac.setAuto", a -> setV("ac.controlMode", b(a[0]) ? 0 : 1)); // 原码 0 自动、1 手动；切手动不关空调
        EFF.put("vehicle.ac.setRearWindMode", a -> setV("ac.rear.windMode", i(a[0])));
        EFF.put("vehicle.ac.setRearTemperature", tempOf("ac.rear.temperature", "ac.rear.temperatureUnit"));
        EFF.put("vehicle.ac.setRearFanLevel", a -> setV("ac.rear.fanLevel", i(a[0])));
        EFF.put("vehicle.ac.setRearPower", a -> setV("ac.rear.power", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.setRearAuto", a -> setV("ac.rear.controlMode", b(a[0]) ? 0 : 1));
        EFF.put("vehicle.body.setWindowPosition", a -> { // 单个车窗开到几成：1 左前、2 右前、3 左后、4 右后，别的区域 UNSUPPORTED
            int area = i(a[0]);
            if (area < 1 || area > 4) throw new E("UNSUPPORTED", "区域 " + area + " 不支持（只有 1～4 四个车窗）");
            List<String> ks = new ArrayList<>();
            ks.add(setV("body.windows." + WIN[area - 1] + ".position", i(a[1])));
            boolean any = false;
            for (String w : WIN) { Object v = V("body.windows." + w + ".position"); if (v instanceof Number && ((Number) v).doubleValue() > 0) any = true; }
            ks.add(setV("body.windows.anyOpen", any ? 1 : 0));
            return ks;
        });
        EFF.put("system.sendBroadcast", a -> { // 不真的发，只校验 options
            if (!(a[0] instanceof JSONObject)) throw new E("INVALID_ARGUMENT", "options 要是对象");
            JSONObject o = (JSONObject) a[0];
            if (o.optString("action").trim().isEmpty()) throw new E("INVALID_ARGUMENT", "options.action 不能是空的");
            if (o.has("className") && !o.has("packageName")) throw new E("INVALID_ARGUMENT", "写了 className 就要写 packageName");
            return null;
        });
        EFF.put("vehicle.seat.setHeating", a -> setV("seat." + a[0] + ".heating", i(a[1]) + 1));
        EFF.put("vehicle.seat.setVentilation", a -> setV("seat." + a[0] + ".ventilation", i(a[1]) + 1));
        EFF.put("vehicle.body.setWindowsMode", a -> windows(String.valueOf(a[0])));
        EFF.put("vehicle.body.operateSunroof", a -> { Integer p = "close".equals(a[0]) ? 0 : "open".equals(a[0]) ? 100 : "comfort".equals(a[0]) ? 80 : "vent".equals(a[0]) ? 15 : null; return p == null ? null : setV("body.sunroof.position", p); });
        EFF.put("vehicle.body.operateSunshade", a -> { Integer p = "close".equals(a[0]) ? 0 : "open".equals(a[0]) ? 100 : null; return p == null ? null : setV("body.sunshade.position", p); });
        EFF.put("vehicle.body.operateTailgate", a -> { if (need("driving.gear") != 3) throw new E("DEVICE_ERROR", "不在 P 挡，尾门不动"); return setV("body.tailgate.state", "open".equals(a[0]) ? 1 : 0); });
        EFF.put("vehicle.light.setDaytimeRunning", a -> setV("light.daytimeRunning", b(a[0]) ? 1 : 2));
        EFF.put("vehicle.light.setLowBeam", a -> setV("light.lowBeam", b(a[0]) ? 1 : 0));
        EFF.put("vehicle.light.setDynamicColorful", a -> setV("light.dynamicColorful", b(a[0]) ? 1 : 2));
        EFF.put("vehicle.light.setAmbientBrightness", a -> setV("light.ambient.brightness", i(a[0])));
        EFF.put("vehicle.light.setAmbientColor", a -> setV("light.ambient.color", COLORS.indexOf(String.valueOf(a[0])) + 1));
        EFF.put("vehicle.light.setAmbientMusicMode", a -> setV("light.ambient.musicMode", "on".equals(a[0]) ? 1 : 2));
        EFF.put("vehicle.energy.setWirelessCharging", a -> setV("energy.wirelessCharging", b(a[0]) ? 1 : 2));
        EFF.put("vehicle.energy.setSocSave", a -> setV("energy.socSave", b(a[0]) ? 2 : 1));
        EFF.put("vehicle.energy.setSocTarget", a -> setV("energy.socTarget", i(a[0])));
        EFF.put("vehicle.energy.setRegenerationLevel", a -> setV("energy.regenerationLevel", "high".equals(a[0]) ? 2 : 1));
        EFF.put("vehicle.energy.setOperationMode", a -> setV("energy.operationMode", "eco".equals(a[0]) ? 1 : "sport".equals(a[0]) ? 2 : 3));
        EFF.put("vehicle.energy.setMode", a -> setV("energy.mode", "hev".equals(a[0]) ? 3 : 1));
        EFF.put("vehicle.energy.setSimulator", a -> setV("energy.simulator", "on".equals(a[0]) ? 1 : 0));
        EFF.put("vehicle.ac.togglePower", a -> flip01("ac.power"));
        EFF.put("vehicle.ac.toggleCompressor", a -> flip01("ac.compressor"));
        EFF.put("vehicle.ac.adjustDriverTemperature", a -> {
            int u = need("ac.driverTemperatureUnit"), lo = u == 1 ? 17 : 64, hi = u == 1 ? 33 : 91;
            double v = ((Number) V("ac.driverTemperature")).doubleValue() + ((Number) a[0]).doubleValue();
            if (v < lo || v > hi) throw new E("LIMIT_REACHED", "设温到头了");
            return setV("ac.driverTemperature", v == Math.rint(v) ? (Object) (long) v : (Object) v);
        });
        EFF.put("vehicle.ac.adjustFanLevel", a -> { int v = need("ac.fanLevel") + i(a[0]); if (v < 0 || v > 7) throw new E("LIMIT_REACHED", "风量到头了"); return setV("ac.fanLevel", v); });
        EFF.put("vehicle.ac.toggleCirculation", a -> flip01("ac.circulation"));
        EFF.put("vehicle.ac.toggleFrontDefrost", a -> flip01("ac.frontDefrost"));
        EFF.put("vehicle.ac.toggleRearDefrost", a -> flip01("ac.rearDefrost"));
        EFF.put("vehicle.seat.cycleHeating", a -> cyc("seat." + a[0] + ".heating", 1, 2, 3));
        EFF.put("vehicle.seat.cycleVentilation", a -> cyc("seat." + a[0] + ".ventilation", 1, 2, 3));
        EFF.put("vehicle.body.toggleWindowsMode", a -> {
            String m = String.valueOf(a[0]), f = "open".equals(m) ? "body.windows.anyOpen" : "half".equals(m) ? "body.windows.halfOpen" : "body.windows.ventilation";
            return windows(need(f) == 1 ? "close" : m);
        });
        EFF.put("vehicle.body.toggleSunroof", a -> setV("body.sunroof.position", need("body.sunroof.position") > 0 ? 0 : 100));
        EFF.put("vehicle.body.toggleSunshade", a -> setV("body.sunshade.position", need("body.sunshade.position") > 0 ? 0 : 100));
        EFF.put("vehicle.light.toggleDaytimeRunning", a -> flip12("light.daytimeRunning"));
        EFF.put("vehicle.energy.toggleWirelessCharging", a -> flip12("energy.wirelessCharging"));
        EFF.put("vehicle.energy.toggleSocSave", a -> flip12("energy.socSave"));
        EFF.put("vehicle.energy.toggleRegenerationLevel", a -> flip12("energy.regenerationLevel"));
        EFF.put("vehicle.energy.toggleSimulator", a -> flip01("energy.simulator"));
        EFF.put("vehicle.energy.cycleOperationMode", a -> cyc("energy.operationMode", 1, 2, 3));
        EFF.put("media.play", a -> { setPlaying(true); return null; });
        EFF.put("media.pause", a -> { setPlaying(false); return null; });
        EFF.put("media.next", a -> { setTrack(track + 1, true); return null; });
        EFF.put("media.previous", a -> { setTrack(track - 1, true); return null; });
        EFF.put("media.togglePlayPause", a -> { setPlaying(!playing); return null; });
        EFF.put("media.restoreLastSource", a -> { setPlaying(true); return null; });
    }
    /** 参数校验（同宿主：多 / 缺参数、类型不对都是 INVALID_ARGUMENT，不猜不截断） */
    private static void check(JSONObject sc, Object v, String name) throws E {
        String t = sc.optString("type");
        boolean ok = true;
        if ("boolean".equals(t)) ok = v instanceof Boolean;
        else if ("integer".equals(t)) ok = v instanceof Number && ((Number) v).doubleValue() == Math.rint(((Number) v).doubleValue());
        else if ("number".equals(t)) ok = v instanceof Number && !Double.isInfinite(((Number) v).doubleValue()) && !Double.isNaN(((Number) v).doubleValue());
        else if ("string".equals(t)) ok = v instanceof String && !((String) v).trim().isEmpty();
        else if ("array".equals(t)) ok = v instanceof JSONArray;
        else if ("object".equals(t)) ok = v instanceof JSONObject;
        JSONArray en = sc.optJSONArray("enum");
        if (ok && en != null) { boolean hit = false; for (int i = 0; i < en.length(); i++) if (same(en.opt(i), v)) hit = true; ok = hit; }
        if (ok && v instanceof Number && sc.has("minimum")) ok = ((Number) v).doubleValue() >= sc.optDouble("minimum");
        if (ok && v instanceof Number && sc.has("maximum")) ok = ((Number) v).doubleValue() <= sc.optDouble("maximum");
        if (ok && v instanceof String && sc.has("maxLength")) ok = ((String) v).length() <= sc.optInt("maxLength");
        if (ok && "packageName".equals(name)) ok = ((String) v).matches("[A-Za-z]\\w*(\\.[A-Za-z]\\w*)+");
        if (!ok) throw new E("INVALID_ARGUMENT", "参数 " + name + " 不合要求：" + brief(v));
    }
    private Object action(String m, JSONObject p) throws Exception {
        JSONObject d = AF.get(m);
        if (d == null) throw new E("UNSUPPORTED", "没有登记的方法：" + m);
        JSONArray ps = d.optJSONArray("p");
        int n = ps == null ? 0 : ps.length();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add(ps.getJSONArray(i).getString(0));
        for (Iterator<String> it = p.keys(); it.hasNext(); ) { String k = it.next(); if (!names.contains(k)) throw new E("INVALID_ARGUMENT", "多余参数 " + k); }
        Object[] a = new Object[n];
        for (int i = 0; i < n; i++) {
            String k = names.get(i);
            if (!p.has(k)) throw new E("INVALID_ARGUMENT", "缺参数 " + k);
            a[i] = p.opt(k);
            check(ps.getJSONArray(i).getJSONObject(1), a[i], k);
        }
        if (!control) throw new E("PERMISSION_DENIED", "没开车控权限（调试面板里模拟的）");
        if ("actions.runSequence".equals(m)) throw new E("UNSUPPORTED", "调试宿主不模拟组合执行");
        Fx f = EFF.get(m);
        Object r = f == null ? null : f.run(a);
        List<String> ks = new ArrayList<>();
        if (r instanceof String) ks.add((String) r);
        else if (r instanceof List) for (Object o : (List<?>) r) ks.add(String.valueOf(o));
        if (!ks.isEmpty()) { save(); changed('v', ks); emit("values"); }
        return new JSONObject().put("accepted", true);
    }

    // ================= 连接断开 / 恢复 =================
    private void setOnline(boolean on) {
        if (online == on) return;
        online = on;
        for (Sub s : subs()) {
            if (!on) {
                s.pend = null;
                try { listener.onEvent(new JSONObject().put("subscriptionId", s.id).put("error", new JSONObject().put("code", "BACKEND_UNAVAILABLE").put("message", "连接已断开（调试面板里模拟的）"))); } catch (Exception ignored) { }
            } else push(s, s.dom == 'm' ? snap() : treeOr(s.dom, s.fields)); // 重连：补发完整快照
        }
        if (on) listener.onState("ready", "调试宿主（这台机器不是车机，车况是模拟的）");
        emit("host");
    }

    // ================= 给调试面板 =================
    private String mainKey(char dom, String k) { return dom == 'v' && COMP.containsKey(k) ? COMP.get(k) : k; }
    private void select(String dk, boolean on) { if (on && !sel.contains(dk)) sel.add(dk); if (!on) sel.remove(dk); }
    /** 改一项（伴随属性也用这个）：算进"正在调试" */
    void set(char dom, String k, Object v) {
        if (v == null) v = NULL;
        if (dom == 'm') {
            if ("playing".equals(k)) { sm.remove("playing"); setPlaying(b(v)); }
            else { try { sm.put(k, v); } catch (Exception ignored) { } changed('m', Arrays.asList("media")); }
        } else { try { store(dom).put(k, v); } catch (Exception ignored) { } changed(dom, Arrays.asList(k)); }
        select(dom + ":" + mainKey(dom, k), true);
        save(); emit("values");
    }
    /** 选上 / 不调这一项（不调：值、伴随属性、自动都回到默认） */
    void pick(char dom, String k, boolean on) {
        String dk = dom + ":" + k;
        if (on) { select(dk, true); save(); emit("values"); return; }
        select(dk, false);
        auto.remove(dk);
        if (dom == 'm') {
            if ("track".equals(k)) setTrack(0, playing);
            else if ("lyrics".equals(k)) lyrOff = false;
            else { sm.remove(k); if ("playing".equals(k)) setPlaying(false); changed('m', Arrays.asList("media")); }
        } else {
            List<String> ks = new ArrayList<>(Arrays.asList(k));
            JSONObject f = field(dom, k), c = f == null ? null : f.optJSONObject("c");
            if (dom == 'v' && c != null) ks.add(base(k) + c.optString("k"));
            for (String x : ks) store(dom).remove(x);
            changed(dom, ks);
        }
        autoRun(); save(); emit("values");
    }
    List<String> picked() { return new ArrayList<>(sel); }
    boolean isPicked(char dom, String k) { return sel.contains(dom + ":" + k); }
    /** 这一项自己变（选项轮流切、数值来回扫）；sec 一圈几秒，0 停 */
    void auto(char dom, String k, int sec) {
        String dk = dom + ":" + k;
        if (sec > 0) { auto.put(dk, sec); select(dk, true); } else auto.remove(dk);
        autoRun(); save(); emit("values");
    }
    int autoOf(char dom, String k) { Integer s = auto.get(dom + ":" + k); return s == null ? 0 : s; }
    /** 全部恢复默认（车况、系统数据、媒体、正在调试、自动；宿主的设置不动） */
    void resetAll() {
        List<String> vk = new ArrayList<>(), sk = new ArrayList<>();
        for (Iterator<String> it = sv.keys(); it.hasNext(); ) vk.add(it.next());
        for (Iterator<String> it = ss.keys(); it.hasNext(); ) sk.add(it.next());
        sv = new JSONObject(); ss = new JSONObject(); sm = new JSONObject(); sel.clear(); auto.clear(); lyrOff = false;
        setTrack(0, false); changed('v', vk); changed('s', sk); autoRun(); save(); emit("values");
    }
    int track() { return track; }
    void track(int i) { setTrack(i, playing); select("m:track", true); save(); emit("values"); }
    boolean playing() { return playing; }
    boolean lyricsOff() { return lyrOff; }
    void lyricsOff(boolean on) { lyrOff = on; select("m:lyrics", on); save(); emit("values"); }
    boolean control() { return control; }
    boolean online() { return online; }
    int failLeft() { return fail; }
    String failCode() { return failCode; }
    void setControl(boolean on) { control = on; save(); emit("host"); }
    void setConnected(boolean on) { setOnline(on); }
    void setFail(int n) { fail = Math.max(0, n); emit("host"); }
    void setFailCode(String c) { failCode = "RESOURCE_LIMIT".equals(c) ? "RESOURCE_LIMIT" : "BUSY"; save(); emit("host"); }
}
