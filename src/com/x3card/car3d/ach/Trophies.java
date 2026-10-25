package com.x3card.car3d.ach;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.x3card.car3d.Uiplus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 成就：按车的累计数和应用开着时看到的行程、充电、海拔……解锁勋章（Ach、Stats）。
 * 应用看得见时订自己那组宿主数据（AchFeed）、每秒记一次行程、看有没有新解锁的；每 30 秒、藏起来时存一次。
 * 新解锁的排成"待庆祝"（同一时刻解锁 3 枚以上的合成一组），存着：车况页上弹个提示，进了成就墙再一组组庆祝，庆祝过的才拿掉。
 * 数据整个进程共用一份（全屏页和小窗口是两个界面，同一时间只有一个看得见），调试宿主（没有车机时）另存一份
 */
public final class Trophies {
    /** 宿主（同 car3d 的 Host） */
    public interface Link {
        void request(String method, JSONObject params, Uiplus.Reply reply);
        String session();
        boolean ready();
        boolean canRead();
        boolean backendReady();
    }
    public interface Listener {
        /** 新解锁了一组（一枚，或者合成的一组） */
        void unlocked(List<Ach> group);
        /** 待庆祝的枚数变了 */
        void pending(int n);
        /** 记录变了（解锁、清空、定时存档）：成就墙开着就重画 */
        void changed();
    }

    private static final String[] VEH = { "driving.speed", "driving.gear", "power.level", "driving.totalDistance", "driving.evTotalDistance", "driving.hevTotalDistance",
        "energy.batteryPercent", "energy.fuelPercent", "energy.electricRange", "energy.fuelRange", "energy.chargingState", "energy.connectorState",
        "energy.operationMode", "energy.regenerationLevel", "energy.simulator", "energy.socSave", "energy.wirelessChargingState",
        "ac.power", "ac.driverTemperature", "ac.outdoorTemperature", "ac.frontDefrost",
        "seat.driver.heating", "seat.passenger.heating", "seat.driver.ventilation", "seat.passenger.ventilation", "seat.driver.beltFastened",
        "body.windows.anyOpen", "body.sunroof.position", "body.tailgate.state", "body.hood.state",
        "body.doors.frontLeft.state", "body.doors.frontRight.state", "body.doors.rearLeft.state", "body.doors.rearRight.state",
        "light.turnSignal.left", "light.turnSignal.right", "light.hazard", "light.frontFog", "light.brake",
        "light.ambient.color", "light.ambient.musicMode", "light.dynamicColorful",
        "environment.slope", "environment.pm25.indoorLevel", "environment.pm25.outdoorLevel" };
    private static final String[] SYS = { "gps.latitude", "gps.longitude", "gps.altitude", "gps.accuracy", "gps.timestamp", "gps.ageMs" };

    // ---- 整个进程共用 ----
    private static Stats stats;
    private static String key;
    /** 待庆祝的：一项是一组 id（等级高的在前） */
    private static final List<List<String>> pending = new ArrayList<>();

    private final SharedPreferences prefs;
    private final AchFeed feed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener out;
    private boolean on;
    private long lastTick, lastSave;

    public Trophies(Context c, Link host, boolean debug) {
        prefs = c.getApplicationContext().getSharedPreferences("ach", Context.MODE_PRIVATE);
        Geoid.load(c.getApplicationContext());
        String k = debug ? "stats-debug" : "stats";
        if (stats == null || !k.equals(key)) {
            key = k;
            stats = Stats.load(prefs, key);
            loadPending();
        }
        feed = new AchFeed(host, VEH, SYS, new AchFeed.Listener() {
            @Override public void onValues(Set<String> c) { values(c); }
            @Override public void onMedia(JSONObject m) { media(m); }
        });
    }

    public void listen(Listener l) { out = l; }
    Stats stats() { return stats; }

    // ================= 宿主 =================
    /** 宿主连上、授权变了、后端好了 */
    public void hostChanged() { feed.afterAuth(); }
    /** 订阅推送：是成就自己的返回真 */
    public boolean onEvent(JSONObject m) { return feed.onEvent(m); }

    /** 看得见 / 看不见：订阅、每秒的记录跟着开关；藏起来时存档 */
    public void setVisible(boolean v) {
        if (v == on) return;
        on = v;
        if (v) {
            stats.rebase();
            feed.use(true);
            lastTick = 0;
            main.removeCallbacks(tick);
            main.post(tick);
            main.postDelayed(this::checkUnlocks, 1500); // 以前就满足、还没记过的（第一次打开按总里程那一批）
        } else {
            main.removeCallbacks(tick);
            feed.use(false);
            save();
        }
    }

    // ================= 车况 =================
    private static Calendar cal() { return Calendar.getInstance(); }

    private void values(Set<String> c) {
        long tNow = System.currentTimeMillis();
        for (String k : c) stats.value(k, feed.d(k), tNow);
        if (c.contains("driving.totalDistance") || c.contains("driving.evTotalDistance") || c.contains("driving.hevTotalDistance")) {
            double k = feed.d("driving.totalDistance");
            String u = feed.s("driving.totalDistanceUnit");
            if ("m".equals(u)) k /= 1000; else if ("0.1km".equals(u)) k /= 10;
            stats.odometer(k, feed.d("driving.evTotalDistance"), feed.d("driving.hevTotalDistance"), tNow);
        }
        if (c.contains("energy.chargingState") || c.contains("energy.batteryPercent") || c.contains("energy.connectorState"))
            stats.charge(feed.n("energy.chargingState", 0), feed.n("energy.connectorState", 0), feed.d("energy.batteryPercent"), tNow);
        if (c.contains("ac.outdoorTemperature")) {
            double t = feed.d("ac.outdoorTemperature");
            if (feed.n("ac.outdoorTemperatureUnit", 1) == 0) t = (t - 32) / 1.8;
            stats.temperature(t);
        }
        if (c.contains("environment.slope")) stats.slope(feed.d("environment.slope"));
        if (c.contains("gps.timestamp")) {
            double age = feed.d("gps.ageMs"), acc = feed.d("gps.accuracy");
            if ((Double.isNaN(age) || age < 10000) && (Double.isNaN(acc) || acc < 80)) {
                double lat = feed.d("gps.latitude"), lon = feed.d("gps.longitude");
                stats.place(lat, lon, feed.d("gps.altitude"), Geoid.n(lat, lon), acc);
            }
        }
    }

    private void media(JSONObject m) {
        boolean playing = m.optBoolean("playing");
        stats.media(playing);
        if (!playing) return;
        double v = feed.d("driving.speed");
        stats.song(str(m, "title"), str(m, "artist"), str(m, "packageName"), !Double.isNaN(v) && v > 3);
    }

    private static String str(JSONObject o, String k) { return o.isNull(k) ? null : o.optString(k, null); }

    // ================= 每秒 =================
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long t = SystemClock.elapsedRealtime();
            double dt = lastTick == 0 ? 0 : (t - lastTick) / 1000.0;
            lastTick = t;
            int bf = feed.n("seat.driver.beltFastened", -1);
            stats.tick(dt, feed.d("driving.speed"), feed.n("power.level", -1), bf < 0 ? null : bf == 1, cal());
            checkUnlocks();
            if (t - lastSave > 30000) { lastSave = t; save(); if (out != null) out.changed(); }
            main.postDelayed(this, 1000);
        }
    };

    private void checkUnlocks() {
        if (!on) return;
        // 解锁了这些以后，按"解锁了几枚"算的（收藏家……）可能也到了：一直算到没有新的，算作同一时刻解锁的
        List<Ach> n = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (List<Ach> k = Ach.newly(stats); !k.isEmpty(); k = Ach.newly(stats)) {
            for (Ach a : k) stats.unlocked.put(a.id, t);
            n.addAll(k);
        }
        if (n.isEmpty()) return;
        // 一下子解锁好几枚（第一次打开时按总里程的那批）：合成一组，提示、庆祝都只一次
        List<List<Ach>> groups = new ArrayList<>();
        if (n.size() >= 3) {
            List<Ach> g = new ArrayList<>(n);
            Collections.sort(g, (x, y) -> y.tier - x.tier);
            groups.add(g);
        } else for (Ach a : n) groups.add(Collections.singletonList(a));
        for (List<Ach> g : groups) { List<String> ids = new ArrayList<>(); for (Ach a : g) ids.add(a.id); pending.add(ids); }
        save();
        if (out == null) return;
        out.changed();
        for (List<Ach> g : groups) out.unlocked(g);
        out.pending(pendingCount());
    }

    // ================= 待庆祝 =================
    public int pendingCount() { int n = 0; for (List<String> g : pending) n += g.size(); return n; }

    /** 待庆祝的每一组（从早到晚） */
    List<List<Ach>> pendingGroups() {
        List<List<Ach>> o = new ArrayList<>();
        for (List<String> g : pending) o.add(achs(g));
        return o;
    }

    /** 这一组庆祝过了 */
    void celebrated(List<Ach> g) {
        for (int i = 0; i < pending.size(); i++) {
            List<String> p = pending.get(i);
            if (p.size() == g.size() && p.get(0).equals(g.get(0).id)) { pending.remove(i); break; }
        }
        save();
        if (out != null) out.pending(pendingCount());
    }

    private static List<Ach> achs(List<String> ids) {
        List<Ach> o = new ArrayList<>();
        for (String id : ids) { Ach a = Ach.byId(id); if (a != null) o.add(a); }
        return o;
    }

    private void loadPending() {
        pending.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString(key + "-pending", "[]"));
            for (int i = 0; i < a.length() && pending.size() < 300; i++) {
                JSONArray g = a.optJSONArray(i);
                List<String> ids = new ArrayList<>();
                for (int j = 0; g != null && j < g.length(); j++) { String id = g.optString(j, ""); if (Ach.byId(id) != null && stats.unlocked.containsKey(id) && !ids.contains(id)) ids.add(id); }
                if (!ids.isEmpty()) pending.add(ids);
            }
        } catch (Exception ignored) { /* 坏了就当没有 */ }
    }

    private void save() {
        stats.save(prefs, key);
        JSONArray a = new JSONArray();
        for (List<String> g : pending) a.put(new JSONArray(g));
        prefs.edit().putString(key + "-pending", a.toString()).apply();
    }

    /** 看一枚勋章时手指转了多少（彩蛋：转满 10 圈） */
    void spin(double rad) { stats.spin(rad); }
    /** 打开过统计页（彩蛋） */
    void peek() { stats.mark("peek"); }
    /** 打开过成就墙（彩蛋"初次见面"） */
    void opened() { stats.mark("hello"); }

    /** 清空记录：勋章和统计从头记（按总里程的会马上重新解锁） */
    public void reset() {
        stats = new Stats();
        pending.clear();
        if (on) values(feed.keys()); // 宿主只推变了的：现在的值（总里程这些）重新交给新记录，第一个值只当起点
        save();
        if (out != null) { out.changed(); out.pending(0); }
        main.postDelayed(this::checkUnlocks, 800);
    }
}
