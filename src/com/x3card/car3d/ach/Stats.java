package com.x3card.car3d.ach;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 车主的数据：成就按它算。两类来源——
 * - 车自己的累计数（总里程、纯电里程）：随时都准，第一次打开就能按它解锁里程类的勋章；
 * - 应用开着时看到的事：一趟趟行程（上电 → 熄火）、夜里开了多少、充电、海拔、气温、去过的地方、听过的歌、车上的各种开关……
 *   只在 3D 车况开着（显示着）时记得到，统计页里写明。
 * 一趟：上电（ON / READY）后车动了算开始，熄火超过 60 秒算结束；里程按车速积分（每一小步按 250 km/h 封顶，宿主偶尔报错值），
 * 走过 0.5 公里才算一趟。连续开车的天数按"这天开过 1 公里以上"算。
 * 散的计数（倒车次数、ECO 里程……）放在 cnt，碰到过就算的事（开过双闪、到过哪个省会……）放在 flags，都按名字存。
 * 动力按看到过的数据认（bat / fuel，记成 flags，认出来就不改）；电量、油量按"这次补能之前的最低点"认补能（level），
 * 补能时一点点往上涨（应用开着）、隔了很久才看到（应用关着时补的）都认得出，开着车往上跳（下长坡能量回收、油箱晃）不算。
 * 存在 SharedPreferences 的一个 JSON 里，读的时候逐项校验（坏了的项回 0，不影响别的）
 */
final class Stats {
    // ---- 车的累计数 ----
    double odo = Double.NaN, evOdo = Double.NaN, hevOdo = Double.NaN, firstOdo = Double.NaN;
    long firstSeen;
    /** 现在（tick 时更新；演示时是演示的时间） */
    long nowMs;
    // ---- 行程 ----
    int trips, earlyStarts, dayCount, streak, bestStreak, beltStreak, beltBest;
    double totalKm, nightKm, hours, maxTripKm, maxDayKm, dayKm;
    int lastDay, dayKmDate;
    // ---- 能源 ----
    int charges, dcCharges;
    double chargeStartPct = Double.NaN;
    // ---- 探索 ----
    double maxAlt = Double.NaN, minAlt = Double.NaN, minTemp = Double.NaN, maxTemp = Double.NaN, maxSlope, farKm;
    double latMax = Double.NaN, latMin = Double.NaN, lonMax = Double.NaN, lonMin = Double.NaN;
    double firstLat = Double.NaN, firstLon = Double.NaN;
    final HashSet<Integer> cells = new HashSet<>();
    final HashSet<Integer> songs = new HashSet<>();
    final HashSet<Integer> artists = new HashSet<>();
    private final HashMap<Integer, Integer> plays = new HashMap<>();
    private int maxPlay;
    // ---- 散的计数和记号 ----
    private final HashMap<String, Double> cnt = new HashMap<>();
    private final HashSet<String> flags = new HashSet<>();
    // ---- 已解锁：id → 时间 ----
    final TreeMap<String, Long> unlocked = new TreeMap<>();

    double c(String k) { Double v = cnt.get(k); return v == null ? 0 : v; }
    void add(String k, double v) { cnt.put(k, c(k) + v); }
    private void max(String k, double v) { if (v > c(k)) cnt.put(k, v); }
    boolean has(String f) { return flags.contains(f); }
    void mark(String f) { if (flags.size() < 3000) flags.add(f); }
    /** 名字以 p 开头的记号有几个（到过几个省会、用过几种模式……） */
    int count(String p) { int n = 0; for (String f : flags) if (f.startsWith(p)) n++; return n; }
    /** 有电池（纯电、混动）：见过电量、纯电续航或纯电里程 */
    boolean bat() { return has("pt:bat"); }
    /** 有油箱（混动、纯油）：见过油量、燃油续航或混动里程 */
    boolean fuel() { return has("pt:fuel"); }
    int maxPlays() { return maxPlay; }

    // ---- 当前状态（不存） ----
    private boolean tripOn, tripBelt = true, charging, mediaPlaying;
    private double tripFuel0 = Double.NaN; // 这一趟出发时的油量
    private double tripKm, tripMaxV, tripJamSec, cruiseRun, cruiseOut, outTemp = Double.NaN;
    private int tripStops;
    private boolean tripHarsh, movedFast, sigOn;
    private long offSince, chargeSince, lastMove, sigOffAt;
    private double lastOdoInt = Double.NaN;
    private final HashMap<String, Double> live = new HashMap<>();
    private final long[] spT = new long[4];
    private final double[] spV = new double[4];
    private int spN;
    private double altS = Double.NaN, altRef = Double.NaN, refDist, fixLat = Double.NaN, fixLon = Double.NaN;
    private int lastSongKey;

    private double live(String k) { Double v = live.get(k); return v == null ? Double.NaN : v; }
    private static double nn0(double v) { return Double.isNaN(v) ? 0 : v; }
    /** 重新订阅之前：记下的车况值作废，订阅后的第一个值重新只当起点（藏起来期间挂过的挡、开过的门不算） */
    void rebase() { live.clear(); }

    // ================= 车况交进来 =================
    /** 总里程（公里）、纯电、混动里程 */
    void odometer(double km, double ev, double hev, long t) {
        if (!Double.isNaN(km) && km > 0 && km < 3e6) {
            if (Double.isNaN(firstOdo)) { firstOdo = km; if (firstSeen == 0) firstSeen = t; }
            if (!Double.isNaN(odo) && km < odo - 5) return; // 往回跳：宿主报错，不要
            odo = Math.max(Double.isNaN(odo) ? 0 : odo, km);
            int k = (int) Math.floor(odo);
            if (Double.isNaN(lastOdoInt)) odoPattern(k);
            else if (k > (int) lastOdoInt) {
                // 宿主推总里程不一定每公里都推：车开着、应用开着时一次跳了几公里，中间经过的整公里也都算看见了
                int from = k - (int) lastOdoInt <= 50 ? (int) lastOdoInt + 1 : k;
                for (int x = from; x <= k; x++) odoPattern(x);
            }
            lastOdoInt = k;
        }
        if (!Double.isNaN(ev) && ev >= 0 && ev < 3e6) evOdo = Math.max(Double.isNaN(evOdo) ? 0 : evOdo, ev);
        if (!Double.isNaN(hev) && hev >= 0 && hev < 3e6) hevOdo = Math.max(Double.isNaN(hevOdo) ? 0 : hevOdo, hev);
        if (nn0(evOdo) > 0) mark("pt:bat");
        if (nn0(hevOdo) > 0) mark("pt:fuel");
    }

    /** 里程表读数的彩蛋（只在应用开着时看见那一刻才算） */
    private void odoPattern(int k) {
        if (k < 100) return;
        String s = Integer.toString(k);
        if (new StringBuilder(s).reverse().toString().equals(s)) mark("palin");
        boolean allSame = true, up = true, down = true;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != s.charAt(0)) allSame = false;
            if (s.charAt(i) != s.charAt(i - 1) + 1) up = false;
            if (s.charAt(i) != s.charAt(i - 1) - 1) down = false;
        }
        if (allSame) mark("same");
        if ((up || down) && s.length() >= 4) mark("seq");
        if (k >= 10000 && k % 10000 == 0) mark("round");
        if (s.endsWith("520")) mark("o520");
        if (s.contains("1314")) mark("o1314");
        if (s.endsWith("666")) mark("o666");
        if (s.endsWith("888")) mark("o888");
    }

    /** 媒体在不在放（开车听歌的时长、氛围灯律动用） */
    void media(boolean playing) { mediaPlaying = playing; }

    /**
     * 宿主推来的一个车况值（数字编码同接口文档；不是数的是 NaN）：记下来给 tick 用，开关类的在这里看"刚打开"
     */
    void value(String k, double v, long t) {
        Double old = live.put(k, v);
        // 订阅后的第一个值只当起点：挂着倒挡、开着尾门打开应用不算"挂了一次""开了一次"
        boolean first = old == null;
        double o = first ? Double.NaN : old;
        if (Double.isNaN(v)) return;
        switch (k) {
            case "driving.gear": if (v == 1 && o != 1 && !first) add("rev", 1); break;
            case "light.turnSignal.left": case "light.turnSignal.right": {
                // 有的宿主按灯泡一亮一灭地报：灭了不到 2 秒又亮的算同一次
                boolean on = live("light.turnSignal.left") == 1 || live("light.turnSignal.right") == 1;
                if (on && !sigOn && t - sigOffAt > 2000 && !first) add("sig", 1);
                if (!on && sigOn) sigOffAt = t;
                sigOn = on;
                break;
            }
            case "light.hazard": if (v == 1) mark("hazard"); break;
            case "light.brake": if (v == 2 && tripOn) tripHarsh = true; break; // 紧急制动时刹车灯快闪
            case "body.hood.state": if (v == 1) mark("hood"); allOpen(); break;
            case "body.tailgate.state": if (v == 1 && o != 1 && !first) add("trunk", 1); allOpen(); break;
            case "body.doors.frontLeft.state": case "body.doors.frontRight.state": case "body.doors.rearLeft.state": case "body.doors.rearRight.state": allOpen(); break;
            case "light.ambient.color": if (v >= 1 && v <= 7) mark("amb:" + (int) v); break;
            case "light.dynamicColorful": if (v == 1) mark("dynColor"); break;
            case "energy.simulator": if (v == 1) mark("simSound"); break;
            case "energy.socSave": if (v == 2) mark("socSave"); break; // 1 关、2 开
            case "energy.wirelessChargingState": if (v == 1) mark("wireless"); break;
            case "energy.connectorState": if (v == 5) mark("v2l"); break;
            case "seat.driver.heating": case "seat.passenger.heating": if (v >= 2 && v <= 3) mark("seatHeat"); break;
            case "seat.driver.ventilation": case "seat.passenger.ventilation": if (v >= 2 && v <= 3) mark("seatVent"); break;
            case "ac.frontDefrost": if (v == 1) mark("defrost"); break;
            case "environment.pm25.outdoorLevel": case "environment.pm25.indoorLevel":
                if (live("environment.pm25.outdoorLevel") >= 4 && live("environment.pm25.indoorLevel") == 1) mark("pmGuard");
                break;
            case "energy.electricRange": case "energy.fuelRange": {
                double e = live("energy.electricRange"), f = live("energy.fuelRange");
                boolean ev = e > 0 && e < 2046, fv = f > 0 && f < 2046; // 2046 是没有数据
                if (ev) mark("pt:bat");
                if (fv) { mark("pt:fuel"); max("fRange", f); }
                double sum = (ev ? e : 0) + (fv ? f : 0);
                if (sum < 5000) max("range", sum);
                break;
            }
            case "energy.fuelPercent":
                if (v > 0 && v <= 100) mark("pt:fuel");
                level('f', v, t);
                fullBoth();
                break;
            case "energy.batteryPercent":
                if (v > 0 && v <= 100) mark("pt:bat");
                level('b', v, t);
                fullBoth();
                break;
            case "energy.operationMode": if (v >= 1 && v <= 3) mark("has:mode"); break;
            case "environment.slope": if (Math.abs(v) < 100) mark("has:slope"); break;
            default: break;
        }
    }

    private void allOpen() {
        if (live("body.doors.frontLeft.state") == 1 && live("body.doors.frontRight.state") == 1 && live("body.doors.rearLeft.state") == 1
            && live("body.doors.rearRight.state") == 1 && live("body.tailgate.state") == 1) mark("allOpen");
    }

    private void fullBoth() { if (live("energy.batteryPercent") >= 99 && live("energy.fuelPercent") >= 99) mark("doubleFull"); }

    // ================= 电量 / 油量（k：'b' 电池、'f' 油箱） =================
    /**
     * 一个读数（%）：
     * - 补能：停着（车速 < 3、插着枪，或者离上一个读数过了 10 分钟以上——应用关着时补的）读数比"这次补能前的最低点"高出一截（电 10、油 12 个点）算开始补，
     *   之后往上涨记最高点，读数比最高点低 3 个点（开始用了）算补完。开着车往上跳的（下长坡能量回收、油箱晃）不算，最低点跟着读数走。
     * - 一次补能里：补进 50 个点以上、补到 99% 以上、油从 15% 以下补到 90% 以上……各记一次。
     * - 一路用到底：补到 95% 以上以后，中间没再补，一直用到 20% 以下。
     * - 每个开车的日子的最低读数（keep）。
     * 这些数存在 cnt 里（应用被收掉也接得上）
     */
    private void level(char k, double pct, long t) {
        if (Double.isNaN(pct) || pct < 0 || pct > 100) return;
        String K = String.valueOf(k);
        double lt = c(K + "Lt");
        cnt.put(K + "Lt", (double) t);
        double v = live("driving.speed"), cs = live("energy.chargingState"), con = live("energy.connectorState");
        boolean plugged = k == 'b' && (cs == 2 || con >= 2 && con <= 4);
        boolean still = Double.isNaN(v) || v < 3 || plugged || lt == 0 || t - lt > 10 * 60000L;
        double jump = k == 'f' ? 12 : 10;
        double tr = cnt.containsKey(K + "Tr") ? c(K + "Tr") : pct, pk = c(K + "Pk");
        if (pk > 0) {
            if (pct > pk) pk = pct;
            else if (pct <= pk - 3) { pk = 0; tr = pct; } // 补完了，开始用
        } else if (still && pct >= tr + jump) {
            pk = pct;
            refillStart(k, tr);
        } else if (!still) tr = pct; // 开着车：跟着读数走（往上跳的不算补能）
        else if (pct < tr) tr = pct;
        if (pk > 0) refillGoing(k, tr, pk);
        cnt.put(K + "Tr", tr);
        cnt.put(K + "Pk", pk);
        // 一路用到底
        if (pct >= 95) cnt.put(K + "Hi", 1.0);
        if (pk == 0 && pct <= 20 && c(K + "Hi") == 1) { add(K + "Deep", 1); cnt.put(K + "Hi", 0.0); }
        // 这个开车的日子的最低读数
        if (c("lvDay") == day(cal(t))) { String m = K + "Min"; if (!cnt.containsKey(m) || pct < c(m)) cnt.put(m, pct); }
    }

    private static Calendar cal(long t) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(t); return c; }

    /** 开始补了（tr：补之前的最低点） */
    private void refillStart(char k, double tr) {
        String K = String.valueOf(k);
        cnt.put(K + "S", 0.0); // 这次补能里已经记过的（按位）
        cnt.put(K + "Hi", 0.0); // 中间补过：一路用到底重新算
        cnt.put(K + "From", tr);
        if (k == 'f') {
            add("refuel", 1);
            if (tr < 25) mark("fLow25");
            // 一箱油开了多远：从上次加油到这次
            if (!Double.isNaN(odo)) {
                if (cnt.containsKey("fOdo")) max("fLeg", odo - c("fOdo"));
                cnt.put("fOdo", odo);
            }
        } else if (!(charging && counted)) charges++; // 充电：没被 charge() 按充电状态记过才记
    }

    /** 正在补（tr：补之前的最低点，pk：补到的最高点）：够了哪条记哪条，一次补能里每条只记一次 */
    private void refillGoing(char k, double tr, double pk) {
        String K = String.valueOf(k);
        int done = (int) c(K + "S"), was = done;
        if ((done & 1) == 0 && pk - tr >= 50) { done |= 1; add(K + "Gulp", 1); }
        if ((done & 2) == 0 && pk >= 99) { done |= 2; add(K + "Full", 1); if (k == 'b') mark("full"); }
        if (k == 'f') {
            if (tr < 15 && pk >= 90) mark("fRev15");
            if (tr < 10 && pk >= 95) mark("fRev10");
            if (tr >= 50 && pk >= 95) mark("fHalf");
        } else {
            if (tr < 20 && pk >= 80) mark("revive");
            if (tr < 10 && pk >= 90) mark("revive10");
        }
        if (done != was) cnt.put(K + "S", (double) done);
    }

    /** 开车的日子换了（tick 里车开起来时）：上一个开车的日子最低读数够不够，连着几个开车的日子都够 */
    private void levelDay(int d) {
        if (c("lvDay") == d) return;
        if (c("lvDay") > 0) {
            if (bat()) keepStep('b', 20);
            if (fuel()) keepStep('f', 25);
        }
        cnt.put("lvDay", (double) d);
        double b = live("energy.batteryPercent"), f = live("energy.fuelPercent");
        if (b >= 0 && b <= 100) cnt.put("bMin", b); else cnt.remove("bMin");
        if (f >= 0 && f <= 100) cnt.put("fMin", f); else cnt.remove("fMin");
    }

    private void keepStep(char k, double thr) {
        String K = String.valueOf(k);
        if (!cnt.containsKey(K + "Min")) return; // 那天没读到：不算断也不算够
        if (c(K + "Min") >= thr) max(K + "KeepBest", c(K + "Keep") + 1);
        cnt.put(K + "Keep", c(K + "Min") >= thr ? c(K + "Keep") + 1 : 0);
    }

    /** 连着几个开车的日子读数都够（今天开着车、到现在都够的也算上） */
    double keep(char k) {
        String K = String.valueOf(k);
        double thr = k == 'b' ? 20 : 25, now = c(K + "Keep");
        if (c("lvDay") > 0 && cnt.containsKey(K + "Min") && c(K + "Min") >= thr) now += 1;
        return Math.max(c(K + "KeepBest"), now);
    }

    /**
     * 每一小段（主线程按 1 秒左右调）：dt 秒、车速 km/h、电源档位、安全带（null 不知道）
     */
    void tick(double dt, double kmh, int power, Boolean belt, Calendar now) {
        if (dt <= 0 || dt > 30) dt = Math.min(Math.max(dt, 0), 30);
        long t = now.getTimeInMillis();
        nowMs = t;
        if (!Double.isNaN(kmh) && kmh > 1) lastMove = t;
        boolean on = power >= 2 || power < 0 && t - lastMove < 600000; // 宿主不报电源档位时：10 分钟内车动过就当上着电
        if (on && !Double.isNaN(kmh) && kmh > 1 && !tripOn) startTrip(now);
        if (!on && tripOn) { if (offSince == 0) offSince = t; else if (t - offSince > 60000) endTrip(now); }
        else offSince = 0;
        if (!tripOn || Double.isNaN(kmh)) return;
        double v = Math.max(0, Math.min(250, kmh)), km = v * dt / 3600;
        if (v > 5) levelDay(day(now));
        tripKm += km;
        totalKm += km;
        if (v > 0.5) hours += dt / 3600;
        int h = now.get(Calendar.HOUR_OF_DAY), mi = now.get(Calendar.MINUTE);
        if (h >= 22 || h < 5) nightKm += km;
        if (tripKm >= 300 && tripFuel0 >= 90) mark("fTrip300");
        if (belt != null && !belt && v > 10) tripBelt = false;
        int d = day(now);
        if (d != dayKmDate) { dayKmDate = d; dayKm = 0; }
        dayKm += km;
        maxDayKm = Math.max(maxDayKm, dayKm);
        if (dayKm >= 1 && lastDay != d) countDay(d, now);
        int dow = now.get(Calendar.DAY_OF_WEEK);
        if (dayKm >= 50 && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) && c("wkDate") != d) { cnt.put("wkDate", (double) d); add("weekend", 1); }
        drive(v, dt, t);
        cabin(v, km, dt, h, now);
        dates(v, now, d, h, mi);
    }

    /** 开法：急刹 / 急加速、停了几次、堵车、巡航、超速、按时歇脚 */
    private void drive(double v, double dt, long t) {
        tripMaxV = Math.max(tripMaxV, v);
        // 急刹、急加速：拿 1.5～4 秒前的车速比（宿主的车速一档一档推，相邻两次直接相减抖得厉害）
        for (int i = 0; i < spN; i++) {
            double s = (t - spT[i]) / 1000.0;
            if (s < 1.5 || s > 4) continue;
            double a = (v - spV[i]) / 3.6 / s;
            if (a < -3.4 && spV[i] > 20 || a > 3.0) tripHarsh = true;
            break;
        }
        System.arraycopy(spT, 0, spT, 1, spT.length - 1);
        System.arraycopy(spV, 0, spV, 1, spV.length - 1);
        spT[0] = t; spV[0] = v; spN = Math.min(spN + 1, spT.length);
        // 停车：开起来（15 km/h 以上）以后又停住算一次
        if (v > 15) movedFast = true;
        else if (v < 2 && movedFast) { movedFast = false; tripStops++; max("maxStops", tripStops); }
        // 堵车：1～12 km/h 慢慢挪
        if (v >= 1 && v <= 12) { tripJamSec += dt; max("jam", tripJamSec / 60); }
        // 巡航：80～120 km/h，偏出去 20 秒以内不算断
        if (v >= 80 && v <= 120) { cruiseRun += dt; cruiseOut = 0; max("cruise", cruiseRun / 60); }
        else if ((cruiseOut += dt) > 20) cruiseRun = 0;
        // 歇脚：连续开了两小时以上（中间没停过一刻钟），停了一刻钟再走；歇的时候应用可能被关掉，所以两个数都存着
        if (v > 5) {
            double lm = c("restMove");
            if (lm > 0 && t - lm >= 15 * 60000L) { if (c("sinceRest") >= 7200) add("rest", 1); cnt.put("sinceRest", 0.0); }
            add("sinceRest", dt);
            cnt.put("restMove", (double) t);
        }
        if (v > 5 && live("light.frontFog") == 1) mark("fog");
        double m = live("energy.operationMode");
        if (v > 5 && m >= 1 && m <= 3) mark("mode:" + (int) m);
    }

    /** 车上的东西：驾驶模式和能量回收的里程、空调、天窗、车窗、听歌 */
    private void cabin(double v, double km, double dt, int h, Calendar now) {
        double m = live("energy.operationMode");
        if (m == 1) add("ecoKm", km); else if (m == 2) add("sportKm", km); else if (m == 3) add("comfortKm", km);
        if (live("energy.regenerationLevel") == 2) add("regenKm", km);
        boolean moving = v > 3;
        if (live("ac.power") == 1 && moving && !Double.isNaN(outTemp)) {
            double dtC = live("ac.driverTemperature");
            if (live("ac.driverTemperatureUnit") == 0 && !Double.isNaN(dtC)) dtC = (dtC - 32) / 1.8;
            if (outTemp >= 30) mark("acHot");
            if (outTemp <= 5 && (Double.isNaN(dtC) || dtC >= 20)) mark("acWarm");
        }
        double roof = live("body.sunroof.position");
        boolean night = live("display.dayNightMode") == 32 || h >= 20 || h < 5;
        if (roof >= 30 && v > 5) mark("sunroofDrive");
        if (roof >= 50 && night) mark("sunroofNight");
        if (live("body.windows.anyOpen") == 1 && v >= 30 && outTemp >= 15 && outTemp <= 28) mark("breeze");
        if (mediaPlaying && moving) add("musicH", dt / 3600);
        if (mediaPlaying && live("light.ambient.musicMode") == 1) mark("ambMusic");
    }

    /** 日子和时刻的彩蛋（车在开才算） */
    private void dates(double v, Calendar now, int d, int h, int mi) {
        if (v <= 5) return;
        int md = d % 10000;
        if (md == 101) mark("newYearDay");
        if (md == 1231 && h == 23 && mi >= 30 || md == 101 && h == 0 && mi < 30) mark("newYear");
        if (md >= 1001 && md <= 1007) mark("national");
        if (md >= 501 && md <= 505) mark("labor");
        if (md == 214) mark("valentine");
        if (md == 601) mark("children");
        if (md == 1224 || md == 1225) mark("christmas");
        if (md == 229) mark("leap");
        if (in(EVE, d)) mark("eve");
        if (springFestival(d)) mark("spring");
        if (in(LANTERN, d)) mark("lanternFest");
        if (in(QINGMING, d)) mark("qingming");
        if (in(DRAGON, d)) mark("dragon");
        if (in(QIXI, d)) mark("qixi");
        if (in(MID_AUTUMN, d) && h >= 18) mark("midAutumn");
        if (h == 12 && mi == 34) mark("t1234");
        if (h == 0 && mi == 0) mark("midnight");
        if (h == 2 || h == 3) mark("lateNight");
        if (h < 6) cnt.put("early", (double) d);
        if (h >= 21) cnt.put("late", (double) d);
        if (c("early") == d && c("late") == d) mark("starMoon");
    }

    private void startTrip(Calendar now) {
        tripOn = true;
        tripKm = 0;
        tripBelt = true;
        tripHarsh = false;
        tripMaxV = 0;
        tripStops = 0;
        movedFast = false;
        tripJamSec = 0;
        cruiseRun = 0;
        spN = 0;
        tripFuel0 = live("energy.fuelPercent");
        cnt.remove("tripAscNow");
        cnt.remove("tripDescNow");
        offSince = 0;
        int h = now.get(Calendar.HOUR_OF_DAY), d = day(now), dow = now.get(Calendar.DAY_OF_WEEK);
        if (h == 5) earlyStarts++;
        if (h >= 7 && h < 9 && dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && c("cmDate") != d) { cnt.put("cmDate", (double) d); add("commute", 1); }
    }

    private void endTrip(Calendar now) {
        tripOn = false;
        if (tripKm < 0.5) return;
        trips++;
        maxTripKm = Math.max(maxTripKm, tripKm);
        if (tripBelt) { beltStreak++; beltBest = Math.max(beltBest, beltStreak); } else beltStreak = 0;
        if (tripKm >= 10 && !tripHarsh) add("smooth", 1);
        if (tripKm >= 10 && tripStops <= 1) add("green", 1); // 最后到地方停下的那一次不算
        if (tripKm >= 50 && tripMaxV <= 120) add("lawful", 1);
        if (tripKm < 3) add("short", 1);
    }

    /** 要停了（藏起来很久、关掉）：开着的这趟按现在结束 */
    void flushTrip(Calendar now) { if (tripOn) endTrip(now); }

    private void countDay(int d, Calendar now) {
        Calendar y = (Calendar) now.clone();
        y.add(Calendar.DAY_OF_MONTH, -1);
        streak = lastDay == day(y) ? streak + 1 : 1;
        bestStreak = Math.max(bestStreak, streak);
        lastDay = d;
        dayCount++;
    }

    static int day(Calendar c) { return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH); }

    // 农历节日、清明的公历日期（2026～2100，按 _shared/lunar.js 算的）
    private static final int[] SPRING = {
        20260217, 20270206, 20280126, 20290213, 20300203, 20310123, 20320211, 20330131, 20340219, 20350208, 20360128, 20370215, 20380204, 20390124, 20400212,
        20410201, 20420122, 20430210, 20440130, 20450217, 20460206, 20470126, 20480214, 20490202, 20500123, 20510211, 20520201, 20530219, 20540208, 20550128,
        20560215, 20570204, 20580124, 20590212, 20600202, 20610121, 20620209, 20630129, 20640217, 20650205, 20660126, 20670214, 20680203, 20690123, 20700211,
        20710131, 20720219, 20730207, 20740127, 20750215, 20760205, 20770124, 20780212, 20790202, 20800122, 20810209, 20820129, 20830217, 20840206, 20850126,
        20860214, 20870203, 20880124, 20890210, 20900130, 20910218, 20920207, 20930127, 20940215, 20950205, 20960125, 20970212, 20980201, 20990121, 21000209,
    };
    private static final int[] EVE = {
        20260216, 20270205, 20280125, 20290212, 20300202, 20310122, 20320210, 20330130, 20340218, 20350207, 20360127, 20370214, 20380203, 20390123, 20400211,
        20410131, 20420121, 20430209, 20440129, 20450216, 20460205, 20470125, 20480213, 20490201, 20500122, 20510210, 20520131, 20530218, 20540207, 20550127,
        20560214, 20570203, 20580123, 20590211, 20600201, 20610120, 20620208, 20630128, 20640216, 20650204, 20660125, 20670213, 20680202, 20690122, 20700210,
        20710130, 20720218, 20730206, 20740126, 20750214, 20760204, 20770123, 20780211, 20790201, 20800121, 20810208, 20820128, 20830216, 20840205, 20850125,
        20860213, 20870202, 20880123, 20890209, 20900129, 20910217, 20920206, 20930126, 20940214, 20950204, 20960124, 20970211, 20980131, 20990120, 21000208,
    };
    private static final int[] LANTERN = {
        20260303, 20270220, 20280209, 20290227, 20300217, 20310206, 20320225, 20330214, 20340305, 20350222, 20360211, 20370301, 20380218, 20390207, 20400226,
        20410215, 20420205, 20430224, 20440213, 20450303, 20460220, 20470209, 20480228, 20490216, 20500206, 20510225, 20520215, 20530305, 20540222, 20550211,
        20560229, 20570218, 20580207, 20590226, 20600216, 20610204, 20620223, 20630212, 20640302, 20650219, 20660209, 20670228, 20680217, 20690206, 20700225,
        20710214, 20720304, 20730221, 20740210, 20750301, 20760219, 20770207, 20780226, 20790216, 20800205, 20810223, 20820212, 20830303, 20840220, 20850209,
        20860228, 20870217, 20880207, 20890224, 20900213, 20910304, 20920221, 20930210, 20940301, 20950219, 20960208, 20970226, 20980215, 20990204, 21000223,
    };
    private static final int[] QINGMING = {
        20260405, 20270405, 20280404, 20290404, 20300405, 20310405, 20320404, 20330404, 20340405, 20350405, 20360404, 20370404, 20380405, 20390405, 20400404,
        20410404, 20420404, 20430405, 20440404, 20450404, 20460404, 20470405, 20480404, 20490404, 20500404, 20510405, 20520404, 20530404, 20540404, 20550405,
        20560404, 20570404, 20580404, 20590405, 20600404, 20610404, 20620404, 20630405, 20640404, 20650404, 20660404, 20670405, 20680404, 20690404, 20700404,
        20710405, 20720404, 20730404, 20740404, 20750404, 20760404, 20770404, 20780404, 20790404, 20800404, 20810404, 20820404, 20830404, 20840404, 20850404,
        20860404, 20870404, 20880404, 20890404, 20900404, 20910404, 20920404, 20930404, 20940404, 20950404, 20960404, 20970404, 20980404, 20990404, 21000405,
    };
    private static final int[] DRAGON = {
        20260619, 20270609, 20280528, 20290616, 20300605, 20310624, 20320612, 20330601, 20340620, 20350610, 20360530, 20370618, 20380607, 20390527, 20400614,
        20410603, 20420622, 20430611, 20440531, 20450619, 20460608, 20470529, 20480615, 20490604, 20500623, 20510613, 20520601, 20530620, 20540610, 20550530,
        20560617, 20570606, 20580625, 20590614, 20600603, 20610622, 20620611, 20630601, 20640619, 20650608, 20660528, 20670616, 20680604, 20690623, 20700613,
        20710602, 20720620, 20730610, 20740530, 20750617, 20760606, 20770624, 20780614, 20790604, 20800622, 20810611, 20820601, 20830619, 20840607, 20850527,
        20860615, 20870605, 20880623, 20890613, 20900602, 20910621, 20920609, 20930529, 20940617, 20950606, 20960624, 20970614, 20980604, 20990623, 21000612,
    };
    private static final int[] QIXI = {
        20260819, 20270808, 20280826, 20290816, 20300805, 20310824, 20320812, 20330801, 20340820, 20350810, 20360828, 20370817, 20380807, 20390826, 20400814,
        20410803, 20420822, 20430811, 20440731, 20450819, 20460808, 20470827, 20480816, 20490805, 20500823, 20510812, 20520801, 20530820, 20540810, 20550829,
        20560817, 20570806, 20580825, 20590814, 20600802, 20610821, 20620811, 20630801, 20640819, 20650808, 20660827, 20670816, 20680804, 20690823, 20700812,
        20710802, 20720820, 20730810, 20740828, 20750818, 20760806, 20770824, 20780814, 20790803, 20800821, 20810811, 20820731, 20830819, 20840808, 20850826,
        20860815, 20870805, 20880823, 20890812, 20900802, 20910821, 20920809, 20930828, 20940817, 20950806, 20960824, 20970813, 20980803, 20990822, 21000812,
    };
    private static final int[] MID_AUTUMN = {
        20260925, 20270915, 20281003, 20290922, 20300912, 20311001, 20320919, 20330908, 20340927, 20350916, 20361004, 20370924, 20380913, 20391002, 20400920,
        20410910, 20420928, 20430917, 20441005, 20450925, 20460915, 20471004, 20480922, 20490911, 20500930, 20510919, 20520907, 20530926, 20540916, 20551005,
        20560924, 20570913, 20581002, 20590921, 20600909, 20610928, 20620917, 20631006, 20640925, 20650915, 20661003, 20670923, 20680911, 20690929, 20700919,
        20710908, 20720926, 20730916, 20741005, 20750924, 20760912, 20771001, 20780920, 20790910, 20800928, 20810917, 20821006, 20830926, 20840914, 20851003,
        20860922, 20870911, 20880929, 20890918, 20900908, 20910927, 20920916, 20931005, 20940924, 20950913, 20960930, 20970920, 20980909, 20990929, 21000918,
    };

    private static boolean in(int[] a, int d) { for (int x : a) if (x == d) return true; return false; }

    /** 春节（正月初一到初七）的最后一天 */
    private static final int[] SPRING_END = new int[SPRING.length];
    static {
        for (int i = 0; i < SPRING.length; i++) {
            Calendar c = Calendar.getInstance();
            c.set(SPRING[i] / 10000, SPRING[i] / 100 % 100 - 1, SPRING[i] % 100, 12, 0, 0);
            c.add(Calendar.DAY_OF_MONTH, 6);
            SPRING_END[i] = day(c);
        }
    }

    private static boolean springFestival(int d) {
        for (int i = 0; i < SPRING.length; i++) if (d >= SPRING[i] && d <= SPRING_END[i]) return true;
        return false;
    }

    /** 充电状态（1 就绪、2 充电中、3 完成、4 终止）、充电连接（2 交流、3 直流）、电量：充了一分钟以上算一次，期间见到直流算一次快充 */
    void charge(int state, int connector, double pct, long t) {
        boolean now = state == 2;
        if (now && !charging) { charging = true; chargeSince = t; chargeStartPct = pct; sessionDc = false; sessionAc = false; counted = false; dcAdded = false; }
        if (charging && now) {
            if (connector == 3) sessionDc = true;
            if (connector == 2) sessionAc = true;
            if (!counted && t - chargeSince > 60000) {
                counted = true;
                if (c("bPk") == 0) charges++; // 电量读数那边已经认出这次补能、记过了就不再记
                if (sessionAc && !sessionDc) add("acCharges", 1);
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(chargeSince);
                int h = c.get(Calendar.HOUR_OF_DAY);
                if (h >= 22 || h < 6) add("nightCharges", 1);
            }
            if (counted && sessionDc && !dcAdded) { dcAdded = true; dcCharges++; }
        }
        if (!now && charging) {
            charging = false;
            if (counted && !Double.isNaN(chargeStartPct) && chargeStartPct < 20 && pct >= 80) mark("revive");
            if (counted && !Double.isNaN(chargeStartPct) && chargeStartPct < 10 && pct >= 90) mark("revive10");
        }
        if (!Double.isNaN(pct) && pct >= 100 && (now || state == 3)) mark("full");
    }

    private boolean sessionDc, sessionAc, counted, dcAdded;

    // 31 个省会城市和直辖市（纬度、经度），25 公里以内算到过
    private static final double[][] CAPS = {
        { 39.904, 116.407 }, { 39.084, 117.200 }, { 31.230, 121.474 }, { 29.563, 106.551 }, { 38.042, 114.514 }, { 37.870, 112.549 },
        { 40.842, 111.749 }, { 41.805, 123.432 }, { 43.817, 125.324 }, { 45.803, 126.535 }, { 32.060, 118.797 }, { 30.274, 120.155 },
        { 31.821, 117.227 }, { 26.074, 119.297 }, { 28.682, 115.858 }, { 36.651, 117.120 }, { 34.746, 113.625 }, { 30.593, 114.305 },
        { 28.228, 112.939 }, { 23.129, 113.264 }, { 22.817, 108.366 }, { 20.044, 110.199 }, { 30.573, 104.066 }, { 26.647, 106.630 },
        { 25.038, 102.718 }, { 29.652, 91.172 }, { 34.341, 108.940 }, { 36.061, 103.834 }, { 36.617, 101.778 }, { 38.487, 106.231 },
        { 43.825, 87.617 },
    };

    /** GPS 一次定位（水平精度 acc 米） */
    void place(double lat, double lon, double ellAlt, double geoid, double acc) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180 || lat == 0 && lon == 0) return;
        if (Double.isNaN(firstLat)) { firstLat = lat; firstLon = lon; }
        farKm = Math.max(farKm, haversine(firstLat, firstLon, lat, lon));
        latMax = Double.isNaN(latMax) ? lat : Math.max(latMax, lat);
        latMin = Double.isNaN(latMin) ? lat : Math.min(latMin, lat);
        lonMax = Double.isNaN(lonMax) ? lon : Math.max(lonMax, lon);
        lonMin = Double.isNaN(lonMin) ? lon : Math.min(lonMin, lon);
        int li = (int) Math.floor(lat / 0.09);
        double band = Math.cos(Math.toRadians((li + 0.5) * 0.09));
        int lo = (int) Math.floor(lon * Math.max(band, 0.05) / 0.09);
        if (cells.size() < 20000) cells.add((li + 1100) * 10000 + (lo + 5000));
        // 省会、地标：按等距投影算距离（几十公里内足够准）
        double kx = 111.32 * Math.cos(Math.toRadians(lat)), ky = 110.57;
        for (int i = 0; i < CAPS.length; i++) {
            double dx = (lon - CAPS[i][1]) * kx, dy = (lat - CAPS[i][0]) * ky;
            if (dx * dx + dy * dy < 25 * 25) mark("cap:" + i);
        }
        for (int i = 0; i < Ach.SIGHTS.size(); i++) {
            double[] s = Ach.SIGHTS.get(i);
            double dx = (lon - s[1]) * kx, dy = (lat - s[0]) * ky;
            if (dx * dx + dy * dy < s[2] * s[2]) mark("sight:" + Ach.SIGHT_IDS.get(i));
        }
        if (inHongKong(lat, lon)) mark("sight:hk");
        if (inMacau(lat, lon)) mark("sight:macau");
        // 胡焕庸线（黑河 50.25°N 127.50°E — 腾冲 25.02°N 98.49°E，地图上的直线）：离线 0.3° 以外才算到了哪一边
        double side = ((lon - 127.50) * (25.02 - 50.25) - (lat - 50.25) * (98.49 - 127.50)) / 38.45;
        if (side > 0.3) mark("hu:w"); else if (side < -0.3) mark("hu:e");
        altitude(lat, lon, ellAlt, geoid, acc);
    }

    /**
     * 香港：九龙为中心 25 公里以内、深圳河以南（北纬 22.49° 以南），去掉蛇口那一角（北纬 22.44° 以北、东经 113.96° 以西）。
     * 深圳最靠南的蛇口、深圳湾口岸离九龙都在 30 公里以外；香港往大屿山的港珠澳大桥口岸在 24 公里
     */
    static boolean inHongKong(double lat, double lon) {
        double dx = (lon - 114.17) * 111.32 * Math.cos(Math.toRadians(lat)), dy = (lat - 22.32) * 110.57;
        return dx * dx + dy * dy < 25 * 25 && lat < 22.49 && !(lat > 22.44 && lon < 113.96);
    }

    /**
     * 澳门：北纬 22.105°～22.212°（关闸以南）、东经 113.533°～113.600°（内港以东），去掉西南的横琴（北纬 22.165° 以南、东经 113.548° 以西）
     */
    static boolean inMacau(double lat, double lon) {
        return lat > 22.105 && lat < 22.212 && lon > 113.533 && lon < 113.600 && !(lat < 22.165 && lon < 113.548);
    }

    /**
     * 海拔（椭球高 − 大地水准面差距）：平滑一下记最高最低；车在开时按 6 米的回差累计爬升、下降，
     * 两次计数之间要走过 40 米以上、坡度不超过 30%（GPS 的高度在原地也会飘，这样不把飘的算进去）
     */
    private void altitude(double lat, double lon, double ellAlt, double geoid, double acc) {
        if (Double.isNaN(ellAlt) || !Double.isNaN(acc) && acc > 30) return;
        double a = ellAlt - geoid;
        if (a < -500 || a > 9000) return;
        altS = Double.isNaN(altS) ? a : altS + (a - altS) * 0.3;
        maxAlt = Double.isNaN(maxAlt) ? altS : Math.max(maxAlt, altS);
        minAlt = Double.isNaN(minAlt) ? altS : Math.min(minAlt, altS);
        if (!Double.isNaN(fixLat)) refDist += haversine(fixLat, fixLon, lat, lon) * 1000;
        fixLat = lat;
        fixLon = lon;
        double v = live("driving.speed");
        if (!tripOn || !(v > 5)) { altRef = altS; refDist = 0; return; }
        if (Double.isNaN(altRef)) { altRef = altS; refDist = 0; return; }
        double dh = altS - altRef;
        if (Math.abs(dh) < 6 || refDist < 40) return;
        if (Math.abs(dh) / refDist <= 0.3) {
            if (dh > 0) { add("asc", dh); add("tripAscNow", dh); max("tripAsc", c("tripAscNow")); }
            else { add("tripDescNow", -dh); max("tripDesc", c("tripDescNow")); }
        }
        altRef = altS;
        refDist = 0;
    }

    static double haversine(double a1, double o1, double a2, double o2) {
        double p1 = Math.toRadians(a1), p2 = Math.toRadians(a2), dp = p2 - p1, dl = Math.toRadians(o2 - o1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0088 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    void temperature(double c) {
        if (Double.isNaN(c) || c <= -60 || c >= 70) return;
        outTemp = c;
        if (tripOn) { minTemp = Double.isNaN(minTemp) ? c : Math.min(minTemp, c); maxTemp = Double.isNaN(maxTemp) ? c : Math.max(maxTemp, c); }
    }

    void slope(double pct) { if (!Double.isNaN(pct) && tripOn && Math.abs(pct) < 100) maxSlope = Math.max(maxSlope, Math.abs(pct)); }

    private static final Pattern ROAD = Pattern.compile("路|(?i)\\b(road|drive|highway|route)\\b");
    private static final Pattern RAIN = Pattern.compile("雨|(?i)\\brain(s|y|ing)?\\b");
    private static final Pattern BDAY = Pattern.compile("生日|(?i)birthday");

    /** 开车时听的歌（按歌名 + 歌手认）；换了一首算放了一回 */
    void song(String title, String artist, String pkg, boolean moving) {
        if (!moving || title == null || title.isEmpty()) return;
        int key = (title + "\u0001" + (artist == null ? "" : artist)).hashCode();
        if (songs.size() < 5000) songs.add(key);
        if (artist != null && !artist.isEmpty() && artists.size() < 3000) artists.add(artist.hashCode());
        if (key != lastSongKey) {
            lastSongKey = key;
            Integer n = plays.get(key);
            if (n != null || plays.size() < 3000) { int k = (n == null ? 0 : n) + 1; plays.put(key, k); maxPlay = Math.max(maxPlay, k); }
        }
        if (pkg != null && !pkg.isEmpty() && count("pl:") < 30) mark("pl:" + pkg);
        if (ROAD.matcher(title).find()) mark("roadSong");
        if (RAIN.matcher(title).find()) mark("rainSong");
        if (BDAY.matcher(title).find()) mark("bdaySong");
    }

    /** 看一枚勋章时手指转了多少（弧度）：累计转满 10 圈 */
    void spin(double rad) { add("spinR", rad); if (c("spinR") >= 10 * 2 * Math.PI) mark("spin"); }

    boolean tripOn() { return tripOn; }

    // ================= 存取 =================
    void save(SharedPreferences p, String key) {
        try {
            JSONObject o = new JSONObject();
            o.put("odo", nz(odo)).put("ev", nz(evOdo)).put("hev", nz(hevOdo)).put("firstOdo", nz(firstOdo)).put("firstSeen", firstSeen);
            o.put("trips", trips).put("early", earlyStarts).put("days", dayCount).put("streak", streak).put("best", bestStreak).put("belt", beltStreak).put("beltBest", beltBest);
            o.put("km", totalKm).put("night", nightKm).put("hours", hours).put("maxTrip", maxTripKm).put("maxDay", maxDayKm).put("dayKm", dayKm).put("lastDay", lastDay).put("dayKmDate", dayKmDate);
            o.put("charges", charges).put("dc", dcCharges);
            o.put("maxAlt", nz(maxAlt)).put("minAlt", nz(minAlt)).put("minT", nz(minTemp)).put("maxT", nz(maxTemp)).put("slope", maxSlope).put("far", farKm);
            o.put("latMax", nz(latMax)).put("latMin", nz(latMin)).put("lonMax", nz(lonMax)).put("lonMin", nz(lonMin));
            o.put("lat0", nz(firstLat)).put("lon0", nz(firstLon));
            o.put("cells", ints(cells)).put("songs", ints(songs)).put("artists", ints(artists));
            JSONObject pl = new JSONObject();
            for (Map.Entry<Integer, Integer> e : plays.entrySet()) pl.put(Integer.toString(e.getKey()), e.getValue());
            o.put("plays", pl);
            JSONObject cn = new JSONObject();
            for (Map.Entry<String, Double> e : cnt.entrySet()) if (!e.getKey().endsWith("Now")) cn.put(e.getKey(), e.getValue());
            o.put("cnt", cn);
            JSONArray fl = new JSONArray();
            for (String f : flags) fl.put(f);
            o.put("flags", fl);
            JSONObject u = new JSONObject();
            for (Map.Entry<String, Long> e : unlocked.entrySet()) u.put(e.getKey(), e.getValue());
            o.put("unlocked", u);
            p.edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONArray ints(HashSet<Integer> s) { JSONArray a = new JSONArray(); for (int k : s) a.put(k); return a; }

    private static Object nz(double v) { return Double.isNaN(v) ? JSONObject.NULL : v; }

    /** 以前的版本把这些记号存成单独的真假值 */
    private static final String[][] OLD_FLAGS = {
        { "full", "full" }, { "revive", "revive" }, { "palin", "palin" }, { "same", "same" }, { "seq", "seq" }, { "round", "round" },
        { "newYear", "newYear" }, { "spring", "spring" }, { "national", "national" },
    };

    static Stats load(SharedPreferences p, String key) {
        Stats s = new Stats();
        try {
            JSONObject o = new JSONObject(p.getString(key, "{}"));
            s.odo = d(o, "odo", 0, 3e6); s.evOdo = d(o, "ev", 0, 3e6); s.hevOdo = d(o, "hev", 0, 3e6); s.firstOdo = d(o, "firstOdo", 0, 3e6);
            s.firstSeen = Math.max(0, o.optLong("firstSeen"));
            s.trips = i(o, "trips"); s.earlyStarts = i(o, "early"); s.dayCount = i(o, "days"); s.streak = i(o, "streak"); s.bestStreak = i(o, "best"); s.beltStreak = i(o, "belt"); s.beltBest = i(o, "beltBest");
            s.totalKm = z(o, "km", 3e6); s.nightKm = z(o, "night", 3e6); s.hours = z(o, "hours", 1e6); s.maxTripKm = z(o, "maxTrip", 1e5); s.maxDayKm = z(o, "maxDay", 1e5); s.dayKm = z(o, "dayKm", 1e5);
            s.lastDay = i(o, "lastDay"); s.dayKmDate = i(o, "dayKmDate");
            s.charges = i(o, "charges"); s.dcCharges = i(o, "dc");
            s.maxAlt = d(o, "maxAlt", -500, 9000); s.minAlt = d(o, "minAlt", -500, 9000); s.minTemp = d(o, "minT", -60, 70); s.maxTemp = d(o, "maxT", -60, 70);
            s.maxSlope = z(o, "slope", 100); s.farKm = z(o, "far", 25000);
            s.latMax = d(o, "latMax", -90, 90); s.latMin = d(o, "latMin", -90, 90); s.lonMax = d(o, "lonMax", -180, 180); s.lonMin = d(o, "lonMin", -180, 180);
            s.firstLat = d(o, "lat0", -90, 90); s.firstLon = d(o, "lon0", -180, 180);
            ints(o.optJSONArray("cells"), s.cells, 20000);
            ints(o.optJSONArray("songs"), s.songs, 5000);
            ints(o.optJSONArray("artists"), s.artists, 3000);
            JSONObject pl = o.optJSONObject("plays");
            if (pl != null) {
                Iterator<String> it = pl.keys();
                while (it.hasNext() && s.plays.size() < 3000) {
                    String k = it.next();
                    int n = pl.optInt(k, 0);
                    try { if (n > 0 && n < 1_000_000) { s.plays.put(Integer.parseInt(k), n); s.maxPlay = Math.max(s.maxPlay, n); } } catch (NumberFormatException ignored) { }
                }
            }
            JSONObject cn = o.optJSONObject("cnt");
            if (cn != null) {
                Iterator<String> it = cn.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    double v = cn.optDouble(k, Double.NaN);
                    if (k.length() <= 40 && !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0 && v <= 1e13) s.cnt.put(k, v);
                }
            }
            JSONArray fl = o.optJSONArray("flags");
            for (int k = 0; fl != null && k < fl.length() && s.flags.size() < 3000; k++) { String f = fl.optString(k, ""); if (!f.isEmpty() && f.length() <= 120) s.flags.add(f); }
            for (String[] f : OLD_FLAGS) if (o.optBoolean(f[0])) s.flags.add(f[1]);
            JSONObject u = o.optJSONObject("unlocked");
            if (u != null) { Iterator<String> it = u.keys(); while (it.hasNext()) { String k = it.next(); long v = u.optLong(k); if (v > 0 && Ach.byId(k) != null) s.unlocked.put(k, v); } }
        } catch (Exception ignored) { /* 坏了就从头记 */ }
        return s;
    }

    private static void ints(JSONArray a, HashSet<Integer> to, int cap) { for (int k = 0; a != null && k < a.length() && k < cap; k++) to.add(a.optInt(k)); }
    private static double d(JSONObject o, String k, double lo, double hi) { double v = o.optDouble(k, Double.NaN); return v >= lo && v <= hi ? v : Double.NaN; }
    private static double z(JSONObject o, String k, double hi) { double v = o.optDouble(k, 0); return v >= 0 && v <= hi ? v : 0; }
    private static int i(JSONObject o, String k) { int v = o.optInt(k, 0); return Math.max(0, Math.min(10_000_000, v)); }
}
