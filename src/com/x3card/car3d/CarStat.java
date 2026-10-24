package com.x3card.car3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车况图标、顶栏系统图标、底部数据（照网页版 demo3d/carstat.js）。网页版拼 HTML，这里给界面一份结构化的列表（图标、颜色类、文字、小标），界面照 style.css 画。
 * 车况图标只在开着 / 有异常时亮：左列座舱，右列驾驶和能源、灯光指示、告警
 */
final class CarStat {
    private CarStat() {}
    /** 图标路径（viewBox 0 0 24 24，描边 1.8、圆头）——同网页版 ICON */
    static final Map<String, String> ICON = new HashMap<>();
    static {
        ICON.put("defF", "M3.5 17.5C6.5 9.5 17.5 9.5 20.5 17.5ZM8.6 15.2c-1-1.3 1-2.6 0-3.9M12 14.8c-1-1.4 1-2.8 0-4.2M15.4 15.2c-1-1.3 1-2.6 0-3.9");
        ICON.put("defR", "M4 7h16v10H4zM8.5 15c-1-1.5 1-3 0-4.5M12 15c-1-1.5 1-3 0-4.5M15.5 15c-1-1.5 1-3 0-4.5");
        ICON.put("mute", "M4 9.5h3.5L12 6v12l-4.5-3.5H4zM15.5 9.5l5 5M20.5 9.5l-5 5");
        ICON.put("regen", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v3.8h-3.8");
        ICON.put("sim", "M3 12h2M7 8.5v7M11 5.5v13M15 8.5v7M19 10.5v3");
        ICON.put("save", "M5 7.5h12v9H5zM17 10.5h2v3h-2M8 12l2 2 3.5-3.5");
        ICON.put("wc", "M9 3.5h6a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1zM5 9c-1.2 2-1.2 4 0 6M19 9c1.2 2 1.2 4 0 6");
        ICON.put("pm", "M7 18.5h10a3.8 3.8 0 0 0 .4-7.6A5.5 5.5 0 0 0 6.8 12 3.3 3.3 0 0 0 7 18.5z");
        ICON.put("slope", "M3 19h18V9.5zM7.5 17.2h3");
        ICON.put("rpm", "M4.5 16.5a7.5 7.5 0 1 1 15 0M12 16.5l3.8-4.6M4.5 16.5h1.6M17.9 16.5h1.6");
        ICON.put("high", "M11 6.5c4.2 0 7.5 2.5 7.5 5.5s-3.3 5.5-7.5 5.5zM3 8.5h5M3 12h5M3 15.5h5");
        ICON.put("fogF", "M13 6.5c4 0 7.5 2.5 7.5 5.5s-3.5 5.5-7.5 5.5zM3 8.5l6 1M3 12h6M3 15.5l6-1M6.5 5.5c-1.4 2.2 1.4 4.3 0 6.5s1.4 4.3 0 6.5");
        ICON.put("fogR", "M11 6.5C7 6.5 3.5 9 3.5 12S7 17.5 11 17.5zM15 8.5h6M15 12h6M15 15.5h6M17.5 5.5c-1.4 2.2 1.4 4.3 0 6.5s1.4 4.3 0 6.5");
        ICON.put("hazard", "M12 3.8 21 19.5H3zM12 9.2l3.5 6.3h-7z");
        ICON.put("brake", "M12 6a6 6 0 1 1 0 12 6 6 0 0 1 0-12zM12 8.8v3.8M12 15.2v.1M4.6 6.5a8.6 8.6 0 0 0 0 11M19.4 6.5a8.6 8.6 0 0 1 0 11");
        ICON.put("gps", "M12 21s-6.2-5.6-6.2-10.6a6.2 6.2 0 0 1 12.4 0C18.2 15.4 12 21 12 21zM12 8.2a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z");
        ICON.put("lowV", "M4 7.5h16v11H4zM7.5 5.5v2M16.5 5.5v2M7 13h3M14 13h3M15.5 11.5v3");
        ICON.put("key", "M8 15.5a3.8 3.8 0 1 1 3.4-5.5H21v3h-2v2h-2.5v-2h-5.1A3.8 3.8 0 0 1 8 15.5zM7 11.7h.01");
        ICON.put("bt", "M7 7.5l10 9-5 4.2V3.3l5 4.2-10 9");
        ICON.put("plane", "M20.5 15.2v-1.8l-7.3-4.6V4.5a1.2 1.2 0 0 0-2.4 0v4.3l-7.3 4.6v1.8l7.3-2.3v4.4l-1.9 1.4v1.4l3.1-.9 3.1.9v-1.4l-1.9-1.4v-4.4z");
        ICON.put("spot", "M12 13.3a1.3 1.3 0 1 0 0-2.6 1.3 1.3 0 0 0 0 2.6zM8.6 15.4a4.8 4.8 0 0 1 0-6.8M15.4 15.4a4.8 4.8 0 0 0 0-6.8M5.8 18.2a8.8 8.8 0 0 1 0-12.4M18.2 18.2a8.8 8.8 0 0 0 0-12.4");
        ICON.put("nosim", "M7 3.5h6.5L17 7v13.5H7zM10 11.5l4 4M14 11.5l-4 4");
        ICON.put("belt", "M12 6.6a2.1 2.1 0 1 0 0-4.2 2.1 2.1 0 0 0 0 4.2zM7.5 21v-7.2a4.5 4.5 0 0 1 9 0V21M9 10.4l6.6 8.3M15.6 18.7h2.2");
        ICON.put("heat", "M8 19.5c-1.6-2.2 1.6-4.3 0-6.5s1.6-4.3 0-6.5M12 19.5c-1.6-2.2 1.6-4.3 0-6.5s1.6-4.3 0-6.5M16 19.5c-1.6-2.2 1.6-4.3 0-6.5s1.6-4.3 0-6.5");
        ICON.put("vent", "M7 3.6c.9 0 1.6.6 1.8 1.5L10.2 13H16a2 2 0 0 1 0 4H9.6a2 2 0 0 1-2-1.6L5.6 5.6C5.4 4.5 6.1 3.6 7 3.6zM8 20.5h9M12.5 4.5h4a1.6 1.6 0 1 0-1.6-1.6M13.5 7.5h6a1.6 1.6 0 1 1-1.6 1.6M12.5 10.5h3");
    }
    /** 空调风扇（实心四片叶子，同车顶的空调按钮） */
    static final String FAN_D = "M11.2 9.9C9.6 6.6 10.4 2 14.3 1.4c3.2-.5 4.6 2.9 2.6 5.7-1.4 2-3.5 3.1-5.7 2.8z";

    /** 一个车况图标：cls 颜色类（ok / bad / warn / on / warm / blue，solo 只有图标），icon 图标名（fan 是风扇），text 文字，tags 小标（AUTO、送风方向……），modes 驾驶 / 能源模式小牌 */
    static final class Item {
        final String cls, icon, text; final List<String> tags = new ArrayList<>(); final List<String[]> modes = new ArrayList<>();
        Item(String cls, String icon, String text) { this.cls = cls == null ? "" : cls; this.icon = icon; this.text = text == null ? "" : text; }
        String key() { return cls + "|" + icon + "|" + text + "|" + tags + "|" + modes.size() + (modes.isEmpty() ? "" : modes.get(0)[0] + (modes.size() > 1 ? modes.get(1)[0] : "")); }
        boolean bad() { return cls.matches(".*\\bbad\\b.*"); }
        boolean warn() { return cls.matches(".*\\bwarn\\b.*"); }
    }
    static final String[] LV = { null, "低档", "高档" };
    static String seatLv(Map<String, Object> c, String what) {
        List<String> l = new ArrayList<>();
        String[][] who = { { "driver", "主驾" }, { "passenger", "副驾" } };
        for (String[] p : who) {
            Object v = c.get("seat." + p[0] + "." + what);
            if (v instanceof Number && ((Number) v).doubleValue() > 0) { int n = ((Number) v).intValue(); l.add(p[1] + (n >= 1 && n <= 2 ? LV[n] : n + " 档")); }
        }
        return String.join(" · ", l);
    }
    static Double num(Object v, boolean d) { return v instanceof Number ? (d ? Math.round(((Number) v).doubleValue() * 10) / 10.0 : (double) Math.round(((Number) v).doubleValue())) : null; }
    static String fmt(Double d) { return d == null ? "" : d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d); }
    static final Map<String, String> WIND = new HashMap<>();
    static final String[] PM = { null, "优", "良", "轻度", "中度", "重度", "严重" };
    static {
        WIND.put("face", "吹脸"); WIND.put("faceFeet", "脸+脚"); WIND.put("feet", "吹脚"); WIND.put("feetScreen", "脚+风挡"); WIND.put("screen", "吹风挡");
        WIND.put("faceFeetScreen", "脸脚风挡"); WIND.put("faceScreen", "脸+风挡");
    }
    private static void acTags(Map<String, Object> c, String pre, Item it) {
        if ("auto".equals(c.get(pre + "controlMode"))) it.tags.add("AUTO");
        String w = WIND.get(String.valueOf(c.get(pre + "windMode")));
        if (w != null) it.tags.add(w);
    }
    static boolean moving(Map<String, Object> c) {
        Object gear = c.get("driving.gear"), sp = c.get("driving.speed");
        return "D".equals(gear) || "R".equals(gear) || (sp instanceof Number && ((Number) sp).doubleValue() > 0);
    }
    /** 车况图标：[0] 左列座舱、[1] 右列驾驶和能源、灯光、告警 */
    static List<List<Item>> telltales(Map<String, Object> c) {
        List<Item> L = new ArrayList<>(), R = new ArrayList<>();
        boolean moving = moving(c);
        Object belt = c.get("seat.driver.beltFastened");
        if (Boolean.TRUE.equals(belt)) L.add(new Item("ok solo", "belt", ""));
        else if (Boolean.FALSE.equals(belt)) L.add(new Item("bad", "belt", moving ? "主驾没系安全带" : "主驾未系"));
        String heat = seatLv(c, "heating"), vent = seatLv(c, "ventilation");
        if (!heat.isEmpty()) L.add(new Item("warm", "heat", "加热 " + heat));
        if (!vent.isEmpty()) L.add(new Item("on", "vent", "通风 " + vent));
        if (Boolean.TRUE.equals(c.get("ac.power"))) {
            Double t = num(c.get("ac.driverTemperature"), true);
            Object f = c.get("ac.fanLevel");
            List<String> tx = new ArrayList<>();
            if (t != null) tx.add(fmt(t) + "°C");
            if (f instanceof Number) tx.add("风 " + fmt(((Number) f).doubleValue()));
            Item it = new Item("on", "fan", tx.isEmpty() ? "空调开着" : String.join(" · ", tx));
            acTags(c, "ac.", it);
            if ("inside".equals(c.get("ac.circulation"))) it.tags.add("内循环");
            if (Boolean.TRUE.equals(c.get("ac.compressor"))) it.tags.add("A/C");
            L.add(it);
        }
        if (Boolean.TRUE.equals(c.get("ac.rear.power"))) {
            Double rt = num(c.get("ac.rear.temperature"), true);
            Object rf = c.get("ac.rear.fanLevel");
            List<String> rx = new ArrayList<>();
            if (rt != null) rx.add(fmt(rt) + "°C");
            if (rf instanceof Number) rx.add("风 " + fmt(((Number) rf).doubleValue()));
            Item it = new Item("on", "fan", "后排" + (rx.isEmpty() ? "空调开着" : " " + String.join(" · ", rx)));
            acTags(c, "ac.rear.", it);
            L.add(it);
        }
        if (Boolean.TRUE.equals(c.get("ac.frontDefrost"))) L.add(new Item("warm", "defF", "前除霜"));
        if (Boolean.TRUE.equals(c.get("ac.rearDefrost"))) L.add(new Item("warm", "defR", "后除霜"));
        if (Boolean.TRUE.equals(c.get("audio.muted"))) L.add(new Item("", "mute", "静音"));
        String opm = String.valueOf(c.get("energy.operationMode")), emd = String.valueOf(c.get("energy.mode"));
        String om = "eco".equals(opm) ? "ECO" : "sport".equals(opm) ? "SPORT" : "comfort".equals(opm) ? "COMFORT" : null, em = "ev".equals(emd) ? "EV" : "hev".equals(emd) ? "HEV" : null;
        if (om != null || em != null) {
            Item it = new Item("modes", null, "");
            if (om != null) it.modes.add(new String[] { om, opm });
            if (em != null) it.modes.add(new String[] { em, emd });
            R.add(it);
        }
        if ("high".equals(c.get("energy.regenerationLevel"))) R.add(new Item("", "regen", "能量回收 高"));
        if (Boolean.TRUE.equals(c.get("energy.simulator"))) R.add(new Item("", "sim", "声浪"));
        if (c.get("energy.socSave") instanceof Boolean) {
            Double soc = num(c.get("energy.socTarget"), false);
            R.add(new Item("", "save", (Boolean.TRUE.equals(c.get("energy.socSave")) ? "强制保电" : "智能保电") + (soc != null ? " " + fmt(soc) + "%" : "")));
        }
        if (Boolean.TRUE.equals(c.get("energy.wirelessCharging"))) {
            Object ws = c.get("energy.wirelessChargingState");
            String t = "charging".equals(ws) ? "无线充电中" : "full".equals(ws) ? "手机已充满" : "fault".equals(ws) ? "无线充电故障" : "standby".equals(ws) ? "无线充电" : "无线充电";
            R.add(new Item("charging".equals(ws) ? "ok" : "fault".equals(ws) ? "bad" : "", "wc", t));
        }
        Object pl = c.get("environment.pm25.indoorLevel");
        if (pl instanceof Number && ((Number) pl).doubleValue() >= 3) {
            int p = ((Number) pl).intValue();
            Object pv = c.get("environment.pm25.indoor");
            R.add(new Item(p >= 5 ? "bad" : "warn", "pm", "车内 PM2.5 " + (p >= 1 && p <= 6 ? PM[p] : "") + (pv instanceof Number ? " " + Math.round(((Number) pv).doubleValue()) : "")));
        }
        Object sl = c.get("environment.slope");
        if (sl instanceof Number && Math.abs(((Number) sl).doubleValue()) >= 5) R.add(new Item("warn", "slope", "坡度 " + Math.round(((Number) sl).doubleValue()) + "%"));
        Object rpm = c.get("engine.rpm");
        if (rpm instanceof Number && ((Number) rpm).doubleValue() > 0) R.add(new Item("", "rpm", Math.round(((Number) rpm).doubleValue()) + " rpm"));
        if (Boolean.TRUE.equals(c.get("light.highBeam"))) R.add(new Item("blue", "high", "远光"));
        if (Boolean.TRUE.equals(c.get("light.frontFog"))) R.add(new Item("ok", "fogF", "前雾灯"));
        if (Boolean.TRUE.equals(c.get("light.rearFog"))) R.add(new Item("warn", "fogR", "后雾灯"));
        if (Boolean.TRUE.equals(c.get("light.hazard"))) R.add(new Item("bad", "hazard", "双闪"));
        if ("flash".equals(c.get("light.brake"))) R.add(new Item("bad", "brake", "紧急制动"));
        if (Boolean.TRUE.equals(c.get("warning.lowVoltageBatteryFault"))) R.add(new Item("bad", "lowV", "低压供电异常"));
        if (Boolean.TRUE.equals(c.get("warning.keyBatteryLow"))) R.add(new Item("warn", "key", "钥匙电池低"));
        List<List<Item>> o = new ArrayList<>();
        o.add(L); o.add(R);
        return o;
    }

    /** 顶栏系统图标：kind（plane / nosim / bars / wifi / bt / gps / spot）、level 格数、dim 变暗、warn 打叹号、label（4G / 5G） */
    static final class Sys {
        final String kind; final int level; final boolean dim, warn, hollow; final String label;
        Sys(String k, int lv, boolean d, boolean w, boolean h, String lb) { kind = k; level = lv; dim = d; warn = w; hollow = h; label = lb; }
        String key() { return kind + level + dim + warn + hollow + label; }
    }
    static List<Sys> sys(Map<String, Object> s) {
        List<Sys> h = new ArrayList<>();
        boolean bad = Boolean.FALSE.equals(s.get("network.validated"));
        Object tr = s.get("network.transport");
        if (Boolean.TRUE.equals(s.get("airplane.enabled"))) h.add(new Sys("plane", 0, false, false, false, null));
        else if (s.get("cellular.simState") instanceof Number && ((Number) s.get("cellular.simState")).intValue() == 1) h.add(new Sys("nosim", 0, true, false, false, null));
        else if (s.get("cellular.signalLevel") instanceof Number) {
            Object dt = s.get("cellular.displayType");
            h.add(new Sys("bars", ((Number) s.get("cellular.signalLevel")).intValue(), false, bad && "cellular".equals(tr), false, dt != null && !"".equals(dt) ? String.valueOf(dt) : null));
        }
        if (Boolean.TRUE.equals(s.get("wifi.enabled"))) {
            boolean con = Boolean.TRUE.equals(s.get("wifi.connected"));
            int lv = con ? (s.get("wifi.signalLevel") instanceof Number ? Math.min(3, ((Number) s.get("wifi.signalLevel")).intValue()) : 3) : 0;
            h.add(new Sys("wifi", lv, !con, bad && "wifi".equals(tr), !con, null));
        }
        if (Boolean.TRUE.equals(s.get("bluetooth.enabled"))) h.add(new Sys("bt", 0, !(s.get("bluetooth.connectionState") instanceof Number && ((Number) s.get("bluetooth.connectionState")).intValue() == 2), false, false, null));
        if (Boolean.TRUE.equals(s.get("gps.enabled"))) h.add(new Sys("gps", 0, Boolean.FALSE.equals(s.get("gps.permissionGranted")), Boolean.FALSE.equals(s.get("gps.permissionGranted")), false, null));
        if (Boolean.TRUE.equals(s.get("hotspot.enabled"))) h.add(new Sys("spot", 0, false, false, false, null));
        return h;
    }

    // ================= 底部数据：[id, 名字, 分组, 字段, 格式] =================
    static final String[][] STATS = {
        { "bat", "电量", "能源" }, { "fuel", "油量", "能源" }, { "range", "总续航", "能源" }, { "eRange", "纯电续航", "能源" }, { "fRange", "燃油续航", "能源" },
        { "soc", "SOC 目标", "能源", "energy.socTarget", "%" }, { "chgT", "充电还要", "能源", "energy.charging.remainingTime", "rt" },
        { "chgP", "充电功率", "能源", "energy.charging.power", "*" }, { "chgC", "充电量", "能源", "energy.charging.capacity", "*" },
        { "eAvg", "平均电耗", "能耗", "energy.consumption.averageElectric", "u*" }, { "eRec", "近期电耗", "能耗", "energy.consumption.recentElectric", "*" },
        { "eNow", "瞬时电耗", "能耗", "energy.consumption.instantElectric", "*" }, { "fAvg", "平均油耗", "能耗", "energy.consumption.averageFuel", "*" },
        { "fRec", "近期油耗", "能耗", "energy.consumption.recentFuel", "*" }, { "fNow", "瞬时油耗", "能耗", "energy.consumption.instantFuel", "u" },
        { "odo", "总里程", "里程" }, { "dur", "本次时长", "里程", "driving.sessionDuration", "dur" }, { "trip", "本次里程", "里程", "driving.sessionDistance", "km" },
        { "jour", "旅程里程", "里程", "driving.journeyDistance", "u" }, { "evT", "累计纯电", "里程", "driving.evTotalDistance", "km" }, { "hevT", "累计混动", "里程", "driving.hevTotalDistance", "km" },
        { "evS", "本次纯电", "里程", "driving.evSessionDistance", "km" }, { "hevS", "本次混动", "里程", "driving.hevSessionDistance", "km" }, { "avgSp", "平均速度", "里程", "driving.averageSpeed", "*" },
        { "temp", "车外温度", "空调" }, { "inTemp", "车内温度", "空调" }, { "setT", "空调设温", "空调", "ac.driverTemperature", "°C" }, { "fan", "风量", "空调", "ac.fanLevel", "档" },
        { "rSetT", "后排设温", "空调", "ac.rear.temperature", "°C" }, { "rFan", "后排风量", "空调", "ac.rear.fanLevel", "档" },
        { "pmIn", "车内 PM2.5", "环境", "environment.pm25.indoor", "*" }, { "pmOut", "车外 PM2.5", "环境", "environment.pm25.outdoor", "*" }, { "slope", "坡度", "环境", "environment.slope", "%" },
        { "acc", "油门踏板", "驾驶", "body.pedals.accelerator", "%" }, { "brk", "制动踏板", "驾驶", "body.pedals.brake", "%" },
        { "rpm", "发动机转速", "驾驶", "engine.rpm", "rpm" }, { "mF", "前电机转速", "驾驶", "motor.front.rpm", "rpm" }, { "mR", "后电机转速", "驾驶", "motor.rear.rpm", "rpm" },
        { "rdLF", "左前雷达", "雷达", "radar.distance.leftFront", "*" }, { "rdFLM", "前左中雷达", "雷达", "radar.distance.frontLeftMid", "*" }, { "rdFRM", "前右中雷达", "雷达", "radar.distance.frontRightMid", "*" },
        { "rdRF", "右前雷达", "雷达", "radar.distance.rightFront", "*" }, { "rdL", "左侧雷达", "雷达", "radar.distance.left", "*" }, { "rdR", "右侧雷达", "雷达", "radar.distance.right", "*" },
        { "rdLR", "左后雷达", "雷达", "radar.distance.leftRear", "*" }, { "rdMR", "后中雷达", "雷达", "radar.distance.middleRear", "*" }, { "rdRR", "右后雷达", "雷达", "radar.distance.rightRear", "*" },
        { "bsdL", "左盲区预警码", "预警原码", "warning.bsd.left", "" }, { "bsdR", "右盲区预警码", "预警原码", "warning.bsd.right", "" },
        { "dowL", "左开门预警码", "预警原码", "warning.dow.left", "" }, { "dowR", "右开门预警码", "预警原码", "warning.dow.right", "" },
        { "rctaL", "左后横向预警码", "预警原码", "warning.rcta.left", "" }, { "rctaR", "右后横向预警码", "预警原码", "warning.rcta.right", "" },
        { "cool", "冷却液", "其他", "engine.coolantTemperature", "*" }, { "vol", "音量", "其他", "audio.volume", "" },
        { "navVol", "导航音量", "其他", "audio.navigationVolume", "" }, { "mtD", "保养里程", "其他", "maintenance.distance", "km" }, { "mtDay", "保养天数", "其他", "maintenance.days", "天" }
    };
    static String[] byId(String id) { for (String[] s : STATS) if (s[0].equals(id)) return s; return null; }
    static String field(String id) { String[] s = byId(id); return s != null && s.length > 3 ? s[3] : null; }
    interface UnitOf { String of(String field); }
    /** 这一项的 [数（可能带"时"的小字，用 \u0001 隔开）, 单位]（没数据 null） */
    static String[] stat(String id, Map<String, Object> c, UnitOf unitOf) {
        String[] s = byId(id);
        Object v = s != null && s.length > 3 ? c.get(s[3]) : null;
        String f = s != null && s.length > 4 ? s[4] : null;
        if ("rt".equals(f)) {
            if (v instanceof double[] && ((double[]) v).length == 2) { double[] a = (double[]) v; return new String[] { a[0] != 0 ? fmt(a[0]) + "\u0001时\u0001" + fmt(a[1]) : fmt(a[1]), "分" }; }
            return null;
        }
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        if ("dur".equals(f)) { long m = (long) Math.floor(d / 60); return m >= 60 ? new String[] { (m / 60) + "\u0001时\u0001" + (m % 60), "分" } : new String[] { String.valueOf(m), "分" }; }
        boolean one = "*".equals(f) || "u*".equals(f) || "u".equals(f) || ("km".equals(f) && d < 100);
        double n = one ? Math.round(d * 10) / 10.0 : Math.round(d);
        if ("u".equals(f) || "u*".equals(f)) { String u = unitOf.of(s[3]); return new String[] { fmt(n), (u == null ? "" : u) + ("u*".equals(f) ? "*" : "") }; }
        if ("*".equals(f)) return new String[] { fmt(n), "*" };
        return new String[] { fmt(n), f == null ? "" : f };
    }
}
