package com.x3card.car3d.ach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 全部成就：分类、等级（铜 / 银 / 金 / 传奇）、名字、怎么拿、图案、进度（现在多少 / 目标多少）。
 * 里程、纯电里程这类按车自己的累计数，第一次打开就算得出来；别的要 3D 车况开着时看到才记得到（见 Stats）。
 * 没解锁的在墙上只显示"？？？"（名字、条件、图案、进度都不露），解锁时才揭晓。
 * 对车有要求的按 req 分（没有哪种车会有永远拿不到的勋章）：要电池的（纯电、混动）、要油箱的（混动、纯油）、只有混动有的；
 * 纯电车拿不到混动、加油那几枚，换成同样多、同样等级的纯电专属；纯油车拿不到电池、混动那些，换成纯油专属——三种动力的总数一样。
 * 要车机报某项数据的（驾驶模式、坡度）车机报过才算进总数；要车上有某个配置的（天窗、座椅通风、声浪……）是隐藏勋章：不占格子、不算总数，拿到了才出现。
 * id 存在本地记录里（已解锁的按 id 认），改名字、换图案都行，id 别改
 */
public final class Ach {
    static final int MILE = 0, TRIP = 1, DRIVE = 2, ENERGY = 3, EXPLORE = 4, EGG = 5;
    static final String[] CATS = { "里程", "旅途", "驾驶", "能源", "探索", "彩蛋" };
    static final int BRONZE = 0, SILVER = 1, GOLD = 2, LEGEND = 3;
    static final String[] TIERS = { "铜", "银", "金", "传奇" };
    /** 对车的要求：任何车、要电池、要油箱、只有混动、纯电专属、纯油专属、车上的配置（隐藏勋章）、车机报驾驶模式、车机报坡度 */
    static final int ANY = 0, BAT = 1, FUEL = 2, PHEV = 3, EV_ONLY = 4, ICE_ONLY = 5, BONUS = 6, HAS_MODE = 7, HAS_SLOPE = 8;

    interface Val { double of(Stats s); }

    final String id, name, desc, icon, unit;
    final int cat, tier, req;
    final double goal;
    final Val val;
    final boolean flag; // 是 / 否的成就（进度只有 0 和 1）

    private Ach(String id, int cat, int tier, String name, String desc, String icon, double goal, String unit, Val val, boolean flag) {
        this.id = id; this.cat = cat; this.tier = tier; this.name = name; this.desc = desc; this.icon = icon; this.goal = goal; this.unit = unit; this.val = val; this.flag = flag;
        this.req = nextReq;
    }

    /** 下面定义的勋章对车的要求（定义时按块设） */
    private static int nextReq = ANY;
    private static void req(int r) { nextReq = r; }

    static final List<Ach> ALL = new ArrayList<>();

    private static void num(String id, int cat, int tier, String name, String desc, String icon, double goal, String unit, Val v) { ALL.add(new Ach(id, cat, tier, name, desc, icon, goal, unit, v, false)); }
    private static void yes(String id, int cat, int tier, String name, String desc, String icon, Val v) { ALL.add(new Ach(id, cat, tier, name, desc, icon, 1, "", v, true)); }
    /** 计数到了多少（Stats 里的计数器） */
    private static void cnt(String id, int cat, int tier, String name, String desc, String icon, double goal, String unit, String key) { num(id, cat, tier, name, desc, icon, goal, unit, s -> s.c(key)); }
    /** 碰到过就算（Stats 里的记号，记号名同 id） */
    private static void got(String id, int cat, int tier, String name, String desc, String icon) { yes(id, cat, tier, name, desc, icon, s -> b(s.has(id))); }
    /** 地标：离那儿 r 公里以内（Stats.place 里按 SIGHTS 判断，记号 "sight:" + id） */
    private static void sight(String id, int tier, String name, String desc, String icon, double lat, double lon, double r) {
        SIGHTS.add(new double[] { lat, lon, r });
        SIGHT_IDS.add(id);
        yes(id, EXPLORE, tier, name, desc, icon, s -> b(s.has("sight:" + id)));
    }

    static final List<double[]> SIGHTS = new ArrayList<>();
    static final List<String> SIGHT_IDS = new ArrayList<>();

    private static double nn(double v) { return Double.isNaN(v) ? 0 : v; }
    private static double b(boolean x) { return x ? 1 : 0; }
    private static double days(Stats s) { return s.firstSeen > 0 && s.nowMs > s.firstSeen ? (s.nowMs - s.firstSeen) / 86400000.0 : 0; }
    /** 纯电里程：纯电车的总里程都是纯电的（宿主不一定报纯电里程） */
    private static double evKm(Stats s) { return s.bat() && !s.fuel() ? Math.max(nn(s.evOdo), nn(s.odo)) : nn(s.evOdo); }
    /** 混动里程：宿主不报时按总里程减纯电里程算 */
    private static double hevKm(Stats s) {
        if (nn(s.hevOdo) > 0) return s.hevOdo;
        return s.bat() && s.fuel() && !Double.isNaN(s.evOdo) && !Double.isNaN(s.odo) ? Math.max(0, s.odo - s.evOdo) : nn(s.hevOdo);
    }
    private static double evShare(Stats s, double minOdo) { return nn(s.odo) >= minOdo ? evKm(s) / s.odo * 100 : 0; }

    static {
        // ================= 里程（车的总里程，第一次打开就算得出来） =================
        num("odo100", MILE, BRONZE, "新车上路", "总里程 100 公里", "key", 100, "公里", s -> nn(s.odo));
        num("odo1k", MILE, BRONZE, "初出茅庐", "总里程 1,000 公里", "road", 1000, "公里", s -> nn(s.odo));
        num("odo2k", MILE, BRONZE, "小试牛刀", "总里程 2,000 公里", "milestone", 2000, "公里", s -> nn(s.odo));
        num("odo4k", MILE, SILVER, "八千里路", "总里程 4,000 公里，正好八千里路云和月", "cloudmoon", 4000, "公里", s -> nn(s.odo));
        num("odo5k", MILE, SILVER, "渐入佳境", "总里程 5,000 公里", "road2", 5000, "公里", s -> nn(s.odo));
        num("odo6300", MILE, SILVER, "万里长江", "总里程 6,300 公里，和长江一样长", "river", 6300, "公里", s -> nn(s.odo));
        num("odo10k", MILE, GOLD, "万里行", "总里程 10,000 公里", "10k", 10000, "公里", s -> nn(s.odo));
        num("odo12500", MILE, GOLD, "二万五千里", "总里程 12,500 公里，走完一次长征的路", "march", 12500, "公里", s -> nn(s.odo));
        num("odo18k", MILE, GOLD, "丈量海岸线", "总里程 18,000 公里，和大陆海岸线一样长", "wave", 18000, "公里", s -> nn(s.odo));
        num("odo21196", MILE, GOLD, "丈量长城", "总里程 21,196 公里，和长城的总长度一样", "wall", 21196, "公里", s -> nn(s.odo));
        num("odo30k", MILE, GOLD, "老司机", "总里程 30,000 公里", "wheel", 30000, "公里", s -> nn(s.odo));
        num("odo40k", MILE, LEGEND, "环游地球", "总里程 40,075 公里，正好绕赤道一圈", "globe", 40075, "公里", s -> nn(s.odo));
        num("odo50k", MILE, GOLD, "千锤百炼", "总里程 50,000 公里", "txt:5万", 50000, "公里", s -> nn(s.odo));
        num("odo100k", MILE, LEGEND, "十万公里俱乐部", "总里程 100,000 公里", "100k", 100000, "公里", s -> nn(s.odo));
        num("odo200k", MILE, LEGEND, "岁月如歌", "总里程 200,000 公里", "txt:20万", 200000, "公里", s -> nn(s.odo));
        num("odoMoon", MILE, LEGEND, "奔月", "总里程 384,400 公里，地球到月亮的距离", "moon", 384400, "公里", s -> nn(s.odo));
        num("odoMoon2", MILE, LEGEND, "地月往返", "总里程 768,800 公里，去月亮再回来", "rocket", 768800, "公里", s -> nn(s.odo));

        // ================= 旅途（应用开着时记的行程） =================
        num("trip1", TRIP, BRONZE, "第一程", "记录下第一趟行程", "flag", 1, "趟", s -> s.trips);
        num("trip10", TRIP, BRONZE, "常来常往", "记录 10 趟行程", "car", 10, "趟", s -> s.trips);
        num("trip100", TRIP, SILVER, "百趟行程", "记录 100 趟行程", "flag2", 100, "趟", s -> s.trips);
        num("trip500", TRIP, GOLD, "五百趟", "记录 500 趟行程", "flag3", 500, "趟", s -> s.trips);
        num("trip1000", TRIP, LEGEND, "千次出发", "记录 1,000 趟行程", "cup", 1000, "趟", s -> s.trips);
        num("long100", TRIP, BRONZE, "百里之行", "一趟开 100 公里", "sign:100", 100, "公里", s -> s.maxTripKm);
        num("long300", TRIP, SILVER, "说走就走", "一趟开 300 公里", "route", 300, "公里", s -> s.maxTripKm);
        num("long500", TRIP, GOLD, "长途奔袭", "一趟开 500 公里", "sign:500", 500, "公里", s -> s.maxTripKm);
        num("day200", TRIP, BRONZE, "一日游", "一天开 200 公里", "sunrise", 200, "公里", s -> s.maxDayKm);
        num("day500", TRIP, GOLD, "一日长征", "一天开 500 公里", "sun", 500, "公里", s -> s.maxDayKm);
        num("day1000", TRIP, LEGEND, "一日千里", "一天开 1,000 公里", "bolt", 1000, "公里", s -> s.maxDayKm);
        num("night100", TRIP, SILVER, "夜行者", "夜里（22 点到 5 点）累计开 100 公里", "owl", 100, "公里", s -> s.nightKm);
        num("night500", TRIP, GOLD, "星夜兼程", "夜里（22 点到 5 点）累计开 500 公里", "nightroad", 500, "公里", s -> s.nightKm);
        num("early3", TRIP, BRONZE, "早起的鸟儿", "早上 5 点多出发 3 次", "bird", 3, "次", s -> s.earlyStarts);
        num("early30", TRIP, GOLD, "闻鸡起舞", "早上 5 点多出发 30 次", "rooster", 30, "次", s -> s.earlyStarts);
        num("streak3", TRIP, BRONZE, "三天连开", "连续 3 天都开车", "cal:3", 3, "天", s -> s.bestStreak);
        num("streak7", TRIP, SILVER, "七天打卡", "连续 7 天都开车", "cal7", 7, "天", s -> s.bestStreak);
        num("streak30", TRIP, GOLD, "月度全勤", "连续 30 天都开车", "cal30", 30, "天", s -> s.bestStreak);
        num("streak100", TRIP, LEGEND, "百日不辍", "连续 100 天都开车", "cal:100", 100, "天", s -> s.bestStreak);
        num("days30", TRIP, BRONZE, "常伴左右", "累计 30 天开过车", "calcheck", 30, "天", s -> s.dayCount);
        num("days100", TRIP, SILVER, "百日之约", "累计 100 天开过车", "cal:百", 100, "天", s -> s.dayCount);
        num("days365", TRIP, GOLD, "一年的路", "累计 365 天开过车", "cal:365", 365, "天", s -> s.dayCount);
        num("hours10", TRIP, BRONZE, "渐入角色", "累计开车 10 小时", "hourglass", 10, "小时", s -> s.hours);
        num("hours100", TRIP, GOLD, "百小时", "累计开车 100 小时", "clock", 100, "小时", s -> s.hours);
        num("hours500", TRIP, LEGEND, "时间的朋友", "累计开车 500 小时", "clock2", 500, "小时", s -> s.hours);
        num("belt10", TRIP, BRONZE, "系好再走", "连续 10 趟开车时都系好安全带", "belt", 10, "趟", s -> s.beltBest);
        num("belt50", TRIP, SILVER, "安全第一", "连续 50 趟开车时都系好安全带", "belt2", 50, "趟", s -> s.beltBest);
        num("belt200", TRIP, GOLD, "平安是福", "连续 200 趟开车时都系好安全带", "shield", 200, "趟", s -> s.beltBest);
        cnt("rest1", TRIP, SILVER, "劳逸结合", "连续开了两个小时以上，停下来歇了一刻钟", "coffee", 1, "次", "rest");
        cnt("rest10", TRIP, GOLD, "张弛有度", "长途开车时 10 次按时歇脚", "bench", 10, "次", "rest");
        cnt("weekend5", TRIP, SILVER, "周末出游", "5 个周末的日子各开了 50 公里以上", "tent", 5, "天", "weekend");
        cnt("commute20", TRIP, SILVER, "打工人", "工作日早上 7～9 点出发 20 次", "briefcase", 20, "次", "commute");
        cnt("short50", TRIP, BRONZE, "就在附近", "50 趟 3 公里以内的短途", "house", 50, "趟", "short");
        num("anniv100", TRIP, SILVER, "相伴百日", "成就墙陪你 100 天", "gift", 100, "天", Ach::days);
        num("anniv365", TRIP, GOLD, "一周年", "成就墙陪你一整年", "cake:1", 365, "天", Ach::days);
        num("anniv1095", TRIP, LEGEND, "三周年", "成就墙陪你三年", "cake:3", 1095, "天", Ach::days);

        // ================= 驾驶 =================
        cnt("smooth1", DRIVE, BRONZE, "温柔一脚", "一趟 10 公里以上，没有急刹、急加速", "feather", 1, "趟", "smooth");
        cnt("smooth10", DRIVE, SILVER, "平顺如丝", "10 趟 10 公里以上的行程，没有急刹、急加速", "silk", 10, "趟", "smooth");
        cnt("smooth50", DRIVE, GOLD, "滴水不洒", "50 趟 10 公里以上的行程，没有急刹、急加速", "glass", 50, "趟", "smooth");
        cnt("smooth200", DRIVE, LEGEND, "如履平地", "200 趟 10 公里以上的行程，没有急刹、急加速", "level", 200, "趟", "smooth");
        cnt("green1", DRIVE, SILVER, "一路绿灯", "一趟 10 公里以上，中间一次都没停", "light:g", 1, "趟", "green");
        cnt("green10", DRIVE, GOLD, "绿波通行", "10 趟 10 公里以上的行程，中间一次都没停", "light:gg", 10, "趟", "green");
        num("stops20", DRIVE, BRONZE, "一路红灯", "一趟里停下来 20 次以上", "light:r", 20, "次", s -> s.c("maxStops"));
        num("jam30", DRIVE, BRONZE, "堵车大师", "一趟里在拥堵中慢慢挪了 30 分钟", "snail", 30, "分钟", s -> s.c("jam"));
        num("jam90", DRIVE, SILVER, "佛系司机", "一趟里在拥堵中慢慢挪了 90 分钟", "lotus", 90, "分钟", s -> s.c("jam"));
        num("cruise30", DRIVE, BRONZE, "高速巡航", "连续 30 分钟保持在 80～120 km/h", "highway", 30, "分钟", s -> s.c("cruise"));
        num("cruise60", DRIVE, SILVER, "一路畅通", "连续 1 小时保持在 80～120 km/h", "speedo", 60, "分钟", s -> s.c("cruise"));
        cnt("lawful10", DRIVE, SILVER, "遵纪守法", "10 趟 50 公里以上的行程，全程没超过 120 km/h", "shieldok", 10, "趟", "lawful");
        cnt("lawful50", DRIVE, GOLD, "模范司机", "50 趟 50 公里以上的行程，全程没超过 120 km/h", "ribbon", 50, "趟", "lawful");
        cnt("rev50", DRIVE, BRONZE, "倒车入库", "挂倒挡 50 次", "plate:R", 50, "次", "rev");
        cnt("rev500", DRIVE, SILVER, "停车高手", "挂倒挡 500 次", "plate:P", 500, "次", "rev");
        cnt("sig100", DRIVE, BRONZE, "打灯是好习惯", "打转向灯 100 次", "turn", 100, "次", "sig");
        cnt("sig1000", DRIVE, SILVER, "礼让有序", "打转向灯 1,000 次", "turn2", 1000, "次", "sig");
        cnt("sig5000", DRIVE, GOLD, "转向灯十级学者", "打转向灯 5,000 次", "blinker", 5000, "次", "sig");
        req(HAS_MODE);
        cnt("eco500", DRIVE, SILVER, "经济适用", "ECO 模式开 500 公里", "txt:ECO", 500, "公里", "ecoKm");
        cnt("sport100", DRIVE, BRONZE, "运动一下", "SPORT 模式开 100 公里", "txt:S", 100, "公里", "sportKm");
        num("modes3", DRIVE, BRONZE, "百变座驾", "ECO、SPORT、COMFORT 三种模式都开过", "tri3", 3, "种", s -> s.count("mode:"));
        req(BAT);
        cnt("regen500", DRIVE, SILVER, "回收大师", "能量回收调到高，开 500 公里", "recycle", 500, "公里", "regenKm");
        req(ANY);
        got("hazard", DRIVE, BRONZE, "打双闪", "用过双闪", "hazard");
        req(BONUS);
        got("fog", DRIVE, BRONZE, "雾里看花", "开着前雾灯开车", "fog");
        req(HAS_SLOPE);
        num("slope15", DRIVE, BRONZE, "爬坡", "爬过 15% 的坡", "slope2", 15, "%", s -> s.maxSlope);
        num("slope30", DRIVE, SILVER, "坡道勇士", "爬过 30% 的坡", "slope", 30, "%", s -> s.maxSlope);
        req(ANY);
        num("climb1000", DRIVE, SILVER, "盘山公路", "一趟里爬升 1,000 米", "serpent", 1000, "米", s -> s.c("tripAsc"));
        num("down1000", DRIVE, SILVER, "一路下山", "一趟里下降 1,000 米", "downhill", 1000, "米", s -> s.c("tripDesc"));
        num("everest", DRIVE, GOLD, "攀登珠峰", "累计爬升 8,849 米，一座珠穆朗玛峰", "everest", 8849, "米", s -> s.c("asc"));
        num("everest10", DRIVE, LEGEND, "十登珠峰", "累计爬升 88,490 米", "everest2", 88490, "米", s -> s.c("asc"));

        // ================= 能源 =================
        req(BAT);
        num("ev100", ENERGY, BRONZE, "电动初体验", "纯电累计 100 公里", "evcar", 100, "公里", s -> evKm(s));
        num("ev1k", ENERGY, BRONZE, "纯电先锋", "纯电累计 1,000 公里", "leaf", 1000, "公里", s -> evKm(s));
        num("ev5k", ENERGY, SILVER, "电力十足", "纯电累计 5,000 公里", "boltc", 5000, "公里", s -> evKm(s));
        num("ev10k", ENERGY, GOLD, "绿色万里", "纯电累计 10,000 公里", "leaf2", 10000, "公里", s -> evKm(s));
        num("ev30k", ENERGY, GOLD, "零排放三万里", "纯电累计 30,000 公里", "earthleaf", 30000, "公里", s -> evKm(s));
        num("ev100k", ENERGY, LEGEND, "十万电公里", "纯电累计 100,000 公里", "txt:10万", 100000, "公里", s -> evKm(s));
        req(PHEV);
        num("hev1k", ENERGY, BRONZE, "油电双修", "混动累计 1,000 公里", "dual", 1000, "公里", Ach::hevKm);
        num("hev10k", ENERGY, SILVER, "混动万里", "混动累计 10,000 公里", "dual2", 10000, "公里", Ach::hevKm);
        req(BAT);
        num("evHalf", ENERGY, BRONZE, "纯电半边天", "纯电里程占总里程五成以上（总里程 1,000 公里起）", "half", 50, "%", s -> evShare(s, 1000));
        num("evRatio", ENERGY, GOLD, "纯电主义", "纯电里程占总里程九成以上（总里程 3,000 公里起）", "spark", 90, "%", s -> evShare(s, 3000));
        num("charge1", ENERGY, BRONZE, "第一次充电", "记录下一次充电", "plug", 1, "次", s -> s.charges);
        num("charge10", ENERGY, BRONZE, "补给站", "记录 10 次充电", "plug:10", 10, "次", s -> s.charges);
        num("charge50", ENERGY, SILVER, "补能达人", "记录 50 次充电", "plug2", 50, "次", s -> s.charges);
        num("charge100", ENERGY, GOLD, "百充成钢", "记录 100 次充电", "plug:100", 100, "次", s -> s.charges);
        num("charge300", ENERGY, LEGEND, "电网常客", "记录 300 次充电", "pylon", 300, "次", s -> s.charges);
        req(BONUS); // 有的混动车没有直流快充
        num("dc1", ENERGY, BRONZE, "极速快充", "用直流快充充一次电", "bolt2", 1, "次", s -> s.dcCharges);
        num("dc10", ENERGY, SILVER, "快充达人", "用直流快充充 10 次电", "boltfast", 10, "次", s -> s.dcCharges);
        num("dc50", ENERGY, GOLD, "闪电补能", "用直流快充充 50 次电", "bolt3", 50, "次", s -> s.dcCharges);
        req(BAT);
        cnt("ac20", ENERGY, SILVER, "细水长流", "交流慢充 20 次", "drop", 20, "次", "acCharges");
        cnt("nightc10", ENERGY, SILVER, "谷电达人", "夜里（22 点到 6 点）开始充电 10 次", "moonplug", 10, "次", "nightCharges");
        yes("full", ENERGY, BRONZE, "满格出发", "充到 100%", "battery", s -> b(s.has("full")));
        yes("revive", ENERGY, SILVER, "满血复活", "一次从 20% 以下充到 80% 以上", "heart", s -> b(s.has("revive")));
        got("revive10", ENERGY, GOLD, "绝处逢生", "一次从 10% 以下充到 90% 以上", "batup");
        req(PHEV);
        got("doubleFull", ENERGY, SILVER, "双满出发", "电量和油量都满着", "battery2");
        num("range1000", ENERGY, SILVER, "千里续航", "纯电续航和燃油续航加起来 500 公里（一千里）以上", "infinity", 500, "公里", s -> s.c("range"));
        got("socSave", ENERGY, BRONZE, "未雨绸缪", "开过保电", "shieldbolt");
        req(FUEL);
        cnt("refuel1", ENERGY, BRONZE, "加油站", "加过一次油", "fuel", 1, "次", "refuel");
        cnt("refuel20", ENERGY, SILVER, "老主顾", "加油 20 次", "fuel:20", 20, "次", "refuel");
        req(BONUS);
        got("v2l", ENERGY, SILVER, "移动电源", "用车给外面的电器供过电", "socket");
        got("wireless", ENERGY, BRONZE, "手机满电", "无线充电把手机充满", "phone");

        // 纯电专属：顶替纯电车拿不到的 7 枚（混动 2、双满、千里续航、保电、加油 2），同样 3 铜 4 银
        req(EV_ONLY);
        cnt("evDeep1", ENERGY, BRONZE, "一电到底", "从 95% 以上一路开到 20% 以下，中间没充电", "batdeep", 1, "次", "bDeep");
        cnt("evGulp1", ENERGY, BRONZE, "大口补能", "一次充进 50 个百分点以上", "batgulp", 1, "次", "bGulp");
        cnt("evRegen50", ENERGY, BRONZE, "回收新手", "能量回收调到高，开 50 公里", "recycle1", 50, "公里", "regenKm");
        cnt("evDeep10", ENERGY, SILVER, "电量过山车", "一电到底 10 次", "batdeep:10", 10, "次", "bDeep");
        cnt("evGulp10", ENERGY, SILVER, "补能大胃王", "一次充进 50 个百分点以上，充了 10 回", "batgulp:10", 10, "次", "bGulp");
        cnt("evFull10", ENERGY, SILVER, "满电控", "充到 100% 十次", "batfull:10", 10, "次", "bFull");
        num("evKeep30", ENERGY, SILVER, "电量守门员", "连续 30 个开车的日子，电量都没低于 20%", "batshield", 30, "天", s -> s.keep('b'));

        // 纯油专属：顶替纯油车拿不到的 24 枚（要电池的 19 枚、只有混动的 5 枚），同样 8 铜 9 银 5 金 2 传奇
        req(ICE_ONLY);
        cnt("fRefuel5", ENERGY, BRONZE, "常来加油", "加油 5 次", "fuel:5", 5, "次", "refuel");
        cnt("fRefuel10", ENERGY, BRONZE, "油站熟客", "加油 10 次", "fuel:10", 10, "次", "refuel");
        cnt("fFull1", ENERGY, BRONZE, "加满一箱", "油量加到 100%", "tank", 1, "次", "fFull");
        cnt("fGulp1", ENERGY, BRONZE, "大口加油", "一次加进 50 个百分点以上", "tankgulp", 1, "次", "fGulp");
        cnt("fDeep1", ENERGY, BRONZE, "一箱到底", "从 95% 以上一路开到 20% 以下才加油", "tankdeep", 1, "次", "fDeep");
        num("fLeg300", ENERGY, BRONZE, "一箱三百", "一箱油开了 300 公里", "tankroad:300", 300, "公里", s -> s.c("fLeg"));
        got("fLow25", ENERGY, BRONZE, "及时补给", "油量低于 25% 时去加油", "fuellow");
        got("fHalf", ENERGY, BRONZE, "有备无患", "还有一半多油就去加满", "fuelhalf");
        cnt("fRefuel50", ENERGY, SILVER, "加油五十回", "加油 50 次", "fuel:50", 50, "次", "refuel");
        cnt("fFull10", ENERGY, SILVER, "满箱达人", "油量加到 100% 十次", "tank:10", 10, "次", "fFull");
        cnt("fGulp10", ENERGY, SILVER, "加油大胃王", "一次加进 50 个百分点以上，加了 10 回", "tankgulp:10", 10, "次", "fGulp");
        got("fRev15", ENERGY, SILVER, "绝地加油", "一次从 15% 以下加到 90% 以上", "tankup");
        cnt("fDeep10", ENERGY, SILVER, "一箱又一箱", "一箱到底 10 次", "tankdeep:10", 10, "次", "fDeep");
        num("fLeg400", ENERGY, SILVER, "一箱八百里", "一箱油开了 400 公里", "tankroad:400", 400, "公里", s -> s.c("fLeg"));
        num("fRange400", ENERGY, SILVER, "续航八百里", "燃油续航显示 400 公里以上", "fuelgauge", 400, "公里", s -> s.c("fRange"));
        num("fKeep30", ENERGY, SILVER, "油箱不见底", "连续 30 个开车的日子，油量都没低于 25%", "tankshield", 30, "天", s -> s.keep('f'));
        got("fTrip300", ENERGY, SILVER, "满油远行", "油量 90% 以上出发，这一趟开了 300 公里", "tankcar");
        cnt("fRefuel100", ENERGY, GOLD, "百回加油", "加油 100 次", "fuel:100", 100, "次", "refuel");
        cnt("fFull50", ENERGY, GOLD, "满箱五十回", "油量加到 100% 五十次", "tank:50", 50, "次", "fFull");
        got("fRev10", ENERGY, GOLD, "油箱见底", "一次从 10% 以下加到 95% 以上", "tankup2");
        cnt("fDeep50", ENERGY, GOLD, "跑满五十箱", "一箱到底 50 次", "tankdeep:50", 50, "次", "fDeep");
        num("fKeep100", ENERGY, GOLD, "百日不见底", "连续 100 个开车的日子，油量都没低于 25%", "tankshield2", 100, "天", s -> s.keep('f'));
        cnt("fRefuel200", ENERGY, LEGEND, "加油站的老朋友", "加油 200 次", "fuel:200", 200, "次", "refuel");
        cnt("fDeep100", ENERGY, LEGEND, "百箱征程", "一箱到底 100 次", "tankdeep:100", 100, "次", "fDeep");
        req(ANY);

        // ================= 探索 =================
        num("alt1000", EXPLORE, BRONZE, "小有高度", "到过海拔 1,000 米", "hill", 1000, "米", s -> nn(s.maxAlt));
        num("alt2000", EXPLORE, BRONZE, "山间行", "到过海拔 2,000 米", "hill2", 2000, "米", s -> nn(s.maxAlt));
        num("alt3000", EXPLORE, SILVER, "高原行者", "到过海拔 3,000 米", "mount", 3000, "米", s -> nn(s.maxAlt));
        num("alt4000", EXPLORE, GOLD, "云端", "到过海拔 4,000 米", "mount2", 4000, "米", s -> nn(s.maxAlt));
        num("alt5000", EXPLORE, LEGEND, "世界屋脊", "到过海拔 5,000 米", "mount3", 5000, "米", s -> nn(s.maxAlt));
        yes("basin", EXPLORE, LEGEND, "盆地之底", "到过海拔 −100 米以下的地方", "basin", s -> b(!Double.isNaN(s.minAlt) && s.minAlt <= -100));
        yes("cold10", EXPLORE, BRONZE, "寒冬出行", "车外零下 10 °C 时开车", "thermo:-10", s -> b(!Double.isNaN(s.minTemp) && s.minTemp <= -10));
        yes("cold", EXPLORE, SILVER, "冰雪征途", "车外零下 20 °C 时开车", "snow", s -> b(!Double.isNaN(s.minTemp) && s.minTemp <= -20));
        yes("cold30", EXPLORE, GOLD, "极寒之地", "车外零下 30 °C 时开车", "thermo:-30", s -> b(!Double.isNaN(s.minTemp) && s.minTemp <= -30));
        yes("hot35", EXPLORE, BRONZE, "炎炎夏日", "车外 35 °C 以上时开车", "thermo:35", s -> b(!Double.isNaN(s.maxTemp) && s.maxTemp >= 35));
        yes("hot", EXPLORE, SILVER, "烈日炎炎", "车外 40 °C 以上时开车", "sun2", s -> b(!Double.isNaN(s.maxTemp) && s.maxTemp >= 40));
        yes("hot45", EXPLORE, GOLD, "火焰山", "车外 45 °C 以上时开车", "flame", s -> b(!Double.isNaN(s.maxTemp) && s.maxTemp >= 45));
        yes("iceFire", EXPLORE, GOLD, "冰火两重天", "零下和 35 °C 以上的天气都开过车", "yinyang", s -> b(!Double.isNaN(s.minTemp) && s.minTemp < 0 && s.maxTemp >= 35));
        num("cells10", EXPLORE, BRONZE, "出门转转", "到过 10 个不同的地方（每 10 公里一格）", "foot", 10, "格", s -> s.cells.size());
        num("cells50", EXPLORE, BRONZE, "足迹", "到过 50 个不同的地方（每 10 公里一格）", "pin", 50, "格", s -> s.cells.size());
        num("cells150", EXPLORE, SILVER, "四处游历", "到过 150 个不同的地方", "map", 150, "格", s -> s.cells.size());
        num("cells300", EXPLORE, GOLD, "行者无疆", "到过 300 个不同的地方", "pins", 300, "格", s -> s.cells.size());
        num("cells1000", EXPLORE, LEGEND, "足迹遍天下", "到过 1,000 个不同的地方", "globepin", 1000, "格", s -> s.cells.size());
        num("far100", EXPLORE, BRONZE, "出远门", "离第一次记录的地方 100 公里", "suitcase", 100, "公里", s -> s.farKm);
        num("far300", EXPLORE, SILVER, "远行", "离第一次记录的地方 300 公里", "sail", 300, "公里", s -> s.farKm);
        num("far1000", EXPLORE, GOLD, "远方", "离第一次记录的地方 1,000 公里", "compass", 1000, "公里", s -> s.farKm);
        num("far3000", EXPLORE, LEGEND, "万水千山", "离第一次记录的地方 3,000 公里", "horizon", 3000, "公里", s -> s.farKm);
        yes("northSouth", EXPLORE, GOLD, "南来北往", "到过北纬 40° 以北，也到过北纬 25° 以南", "arrows", s -> b(!Double.isNaN(s.latMax) && s.latMax >= 40 && s.latMin <= 25));
        yes("tropic", EXPLORE, BRONZE, "跨越北回归线", "北回归线（北纬 23.4°）两边都到过", "tropic", s -> b(!Double.isNaN(s.latMax) && s.latMax > 23.6 && s.latMin < 23.3));
        yes("qinling", EXPLORE, BRONZE, "南北分界", "秦岭—淮河一线（北纬 33°）两边都到过", "divide", s -> b(!Double.isNaN(s.latMax) && s.latMax > 33.3 && s.latMin < 32.7));
        yes("huLine", EXPLORE, SILVER, "跨越胡焕庸线", "黑河—腾冲线两边都到过", "diagonal", s -> b(s.has("hu:w") && s.has("hu:e")));
        yes("north45", EXPLORE, SILVER, "北国风光", "到过北纬 45° 以北", "pine", s -> b(!Double.isNaN(s.latMax) && s.latMax >= 45));
        yes("north52", EXPLORE, LEGEND, "神州北极", "到过北纬 52° 以北", "polaris", s -> b(!Double.isNaN(s.latMax) && s.latMax >= 52));
        yes("south20", EXPLORE, SILVER, "南海之滨", "到过北纬 20° 以南", "palm", s -> b(!Double.isNaN(s.latMin) && s.latMin <= 20));
        yes("west90", EXPLORE, SILVER, "西出阳关", "到过东经 90° 以西", "camel", s -> b(!Double.isNaN(s.lonMin) && s.lonMin <= 90));
        yes("east130", EXPLORE, SILVER, "日出东方", "到过东经 130° 以东", "sunsea", s -> b(!Double.isNaN(s.lonMax) && s.lonMax >= 130));
        yes("eastWest", EXPLORE, GOLD, "横贯东西", "到过的地方东西跨了 20 个经度", "lr", s -> b(!Double.isNaN(s.lonMax) && s.lonMax - s.lonMin >= 20));
        num("caps3", EXPLORE, BRONZE, "城市旅人", "到过 3 个省会城市（含直辖市）", "building", 3, "个", s -> s.count("cap:"));
        num("caps10", EXPLORE, SILVER, "走南闯北", "到过 10 个省会城市（含直辖市）", "buildings", 10, "个", s -> s.count("cap:"));
        num("caps20", EXPLORE, GOLD, "遍访省城", "到过 20 个省会城市（含直辖市）", "skyline", 20, "个", s -> s.count("cap:"));
        num("caps31", EXPLORE, LEGEND, "神州遍访", "31 个省会城市和直辖市都到过", "laurel", 31, "个", s -> s.count("cap:"));
        sight("tiananmen", SILVER, "天安门", "到过北京天安门", "gate", 39.9055, 116.3976, 3);
        sight("bund", SILVER, "外滩", "到过上海外滩", "pearl", 31.2400, 121.4900, 3);
        sight("westlake", SILVER, "西湖", "到过杭州西湖", "archbridge", 30.2460, 120.1500, 6);
        sight("taishan", GOLD, "五岳独尊", "到过泰山", "peak", 36.2560, 117.1010, 10);
        sight("huangshan", GOLD, "黄山归来", "到过黄山", "pinerock", 30.1320, 118.1650, 15);
        sight("zhangjiajie", GOLD, "张家界", "到过张家界", "pillars", 29.3500, 110.5000, 25);
        sight("yangshuo", SILVER, "桂林山水", "到过桂林阳朔", "karst", 24.7780, 110.4960, 15);
        sight("potala", LEGEND, "布达拉宫", "到过拉萨布达拉宫", "palace", 29.6575, 91.1170, 5);
        sight("qinghaiLake", GOLD, "青海湖", "到过青海湖边", "lake", 36.9000, 100.1800, 60);
        sight("mogao", GOLD, "莫高窟", "到过敦煌莫高窟", "cave", 40.0420, 94.8040, 15);
        sight("sanya", GOLD, "天涯海角", "到过三亚天涯海角", "rocks", 18.2930, 109.3500, 5);
        sight("sanxia", SILVER, "三峡大坝", "到过三峡大坝", "dam", 30.8235, 111.0030, 5);
        sight("daocheng", LEGEND, "稻城亚丁", "到过稻城亚丁", "snowpeak", 28.4000, 100.3300, 25);
        sight("kanas", LEGEND, "喀纳斯", "到过喀纳斯湖", "lakeforest", 48.7000, 87.0300, 25);
        sight("everestBC", LEGEND, "珠峰大本营", "到过珠峰大本营", "everestflag", 28.1400, 86.8500, 15);
        sight("erhai", SILVER, "苍山洱海", "到过大理洱海", "pagodas", 25.6900, 100.1800, 25);
        sight("changbai", GOLD, "长白山天池", "到过长白山", "crater", 42.0000, 128.0600, 20);
        sight("shaolin", SILVER, "少林寺", "到过嵩山少林寺", "temple", 34.5080, 112.9350, 5);
        sight("bingma", SILVER, "兵马俑", "到过西安兵马俑", "warrior", 34.3840, 109.2780, 5);
        sight("huangguoshu", SILVER, "黄果树瀑布", "到过黄果树瀑布", "waterfall", 25.9910, 105.6670, 8);
        sight("jiuzhai", GOLD, "九寨沟", "到过九寨沟", "pools", 33.2600, 103.9200, 25);
        sight("leshan", SILVER, "乐山大佛", "到过乐山大佛", "buddha", 29.5450, 103.7700, 5);
        sight("hukou", SILVER, "壶口瀑布", "到过黄河壶口瀑布", "falls2", 36.1500, 110.4400, 8);
        sight("hk", GOLD, "港珠澳", "开车到过香港", "cablebridge", 22.3200, 114.1700, 0); // 半径 0：和深圳挨着，Stats.inHongKong 按范围判断
        sight("macau", GOLD, "濠江", "开车到过澳门", "lotus2", 22.1900, 113.5450, 0); // 同上，Stats.inMacau
        num("songs10", EXPLORE, BRONZE, "开车听歌", "开车时听过 10 首不同的歌", "note1", 10, "首", s -> s.songs.size());
        num("songs100", EXPLORE, SILVER, "车载歌单", "开车时听过 100 首不同的歌", "note", 100, "首", s -> s.songs.size());
        num("songs500", EXPLORE, GOLD, "私人电台", "开车时听过 500 首不同的歌", "headphones", 500, "首", s -> s.songs.size());
        num("songs2000", EXPLORE, LEGEND, "行走的曲库", "开车时听过 2,000 首不同的歌", "vinyl", 2000, "首", s -> s.songs.size());
        num("artists50", EXPLORE, SILVER, "兼收并蓄", "开车时听过 50 位歌手的歌", "mic", 50, "位", s -> s.artists.size());
        num("fav20", EXPLORE, SILVER, "百听不厌", "同一首歌开车时放过 20 回", "heartnote", 20, "回", s -> s.maxPlays());
        num("musicH100", EXPLORE, GOLD, "移动音乐厅", "开车时听歌累计 100 小时", "speaker", 100, "小时", s -> s.c("musicH"));
        num("players3", EXPLORE, BRONZE, "多平台听众", "用过 3 个不同的播放器", "apps", 3, "个", s -> s.count("pl:"));

        // ================= 彩蛋 =================
        got("hello", EGG, BRONZE, "初次见面", "第一次打开成就墙", "hand");
        got("peek", EGG, BRONZE, "数据控", "打开过统计页", "chart");
        got("spin", EGG, BRONZE, "转转转", "把一枚勋章拿在手里转上 10 圈", "spinner");
        num("col10", EGG, BRONZE, "收藏家", "解锁 10 枚勋章", "case", 10, "枚", s -> s.unlocked.size());
        num("col50", EGG, SILVER, "陈列柜", "解锁 50 枚勋章", "cabinet", 50, "枚", s -> s.unlocked.size());
        num("col100", EGG, GOLD, "荣誉殿堂", "解锁 100 枚勋章", "hall", 100, "枚", s -> s.unlocked.size());
        num("col150", EGG, LEGEND, "传奇车主", "解锁 150 枚勋章", "crown", 150, "枚", s -> s.unlocked.size());
        yes("palin", EGG, SILVER, "回文里程", "看见里程表正着倒着一样，比如 12321", "mirror", s -> b(s.has("palin")));
        yes("same", EGG, GOLD, "豹子号", "看见里程表全是同一个数字，比如 22222", "dice", s -> b(s.has("same")));
        yes("seq", EGG, SILVER, "顺子", "看见里程表是连着的数，比如 12345", "steps", s -> b(s.has("seq")));
        yes("round", EGG, BRONZE, "整万时刻", "看见里程表正好整万", "gauge", s -> b(s.has("round")));
        got("o520", EGG, BRONZE, "520", "看见里程表末尾是 520", "txt:520");
        got("o1314", EGG, SILVER, "一生一世", "看见里程表里有 1314", "txt:1314");
        got("o666", EGG, BRONZE, "六六大顺", "看见里程表末尾是 666", "txt:666");
        got("o888", EGG, BRONZE, "一路发", "看见里程表末尾是 888", "txt:888");
        yes("newYear", EGG, GOLD, "跨年夜", "在新年钟声前后开着车", "firework", s -> b(s.has("newYear")));
        got("newYearDay", EGG, BRONZE, "新年第一程", "元旦那天开车", "cal:1");
        got("eve", EGG, SILVER, "年夜饭", "除夕那天开车", "dumpling");
        yes("spring", EGG, SILVER, "回家过年", "春节（初一到初七）开车出行", "lantern", s -> b(s.has("spring")));
        got("lanternFest", EGG, BRONZE, "闹元宵", "元宵节开车", "tangyuan");
        got("valentine", EGG, BRONZE, "情人节", "2 月 14 日开车", "rose");
        got("qingming", EGG, BRONZE, "清明时节", "清明那天开车", "willow");
        got("labor", EGG, BRONZE, "五一出游", "五一假期开车出行", "kite");
        got("children", EGG, BRONZE, "六一快乐", "6 月 1 日开车", "balloon");
        got("dragon", EGG, BRONZE, "端午安康", "端午节开车", "zongzi");
        got("qixi", EGG, SILVER, "鹊桥相会", "七夕开车", "qixi");
        got("midAutumn", EGG, SILVER, "月圆之夜", "中秋节的晚上开车", "mooncake");
        yes("national", EGG, BRONZE, "国庆出游", "国庆假期开车出行", "star", s -> b(s.has("national")));
        got("christmas", EGG, BRONZE, "平安夜", "12 月 24 日或 25 日开车", "tree");
        got("leap", EGG, GOLD, "四年一遇", "2 月 29 日开车", "cal:29");
        got("t1234", EGG, BRONZE, "时间顺子", "12:34 车正开着", "txt:12:34");
        got("midnight", EGG, SILVER, "跨越午夜", "零点整车正开着", "txt:0:00");
        got("starMoon", EGG, SILVER, "披星戴月", "同一天早上 6 点前和晚上 9 点后都开了车", "starmoon");
        got("lateNight", EGG, SILVER, "凌晨的路", "凌晨 2～4 点车正开着", "lamp");
        got("roadSong", EGG, BRONZE, "公路之歌", "开车时放了一首歌名里有“路”的歌", "guitar");
        got("rainSong", EGG, BRONZE, "听雨", "开车时放了一首歌名里有“雨”的歌", "umbrella");
        got("bdaySong", EGG, BRONZE, "生日歌", "开车时放了一首生日歌", "candle");
        req(BONUS);
        got("sunroofNight", EGG, SILVER, "星空顶", "夜里打开天窗", "starwindow");
        got("sunroofDrive", EGG, BRONZE, "天窗看云", "开着天窗开车", "cloud");
        req(ANY);
        got("breeze", EGG, BRONZE, "吹吹风", "车外 15～28 °C，开着车窗兜风", "wind");
        got("acHot", EGG, BRONZE, "清凉一夏", "车外 30 °C 以上，开着空调", "acsnow");
        got("acWarm", EGG, BRONZE, "温暖如春", "车外 5 °C 以下，开着暖风", "heater");
        req(BONUS);
        got("seatHeat", EGG, BRONZE, "暖暖的座椅", "开过座椅加热", "seatheat");
        got("seatVent", EGG, BRONZE, "透心凉", "开过座椅通风", "seatwind");
        req(ANY);
        got("defrost", EGG, BRONZE, "拨云见日", "开过前挡除雾", "defrost");
        req(BONUS);
        got("pmGuard", EGG, SILVER, "隔绝雾霾", "车外空气中度污染以上时，车里空气是优", "filter");
        num("ambient7", EGG, SILVER, "七彩氛围", "氛围灯七种颜色都用过", "rainbow", 7, "种", s -> s.count("amb:"));
        got("ambMusic", EGG, BRONZE, "灯随乐动", "放歌时开着氛围灯律动", "notelight");
        got("dynColor", EGG, BRONZE, "流光溢彩", "开过动态多彩氛围灯", "spectrum");
        got("simSound", EGG, BRONZE, "声浪", "开过模拟声浪", "soundwave");
        req(ANY);
        cnt("trunk50", EGG, BRONZE, "装得下", "打开尾门 50 次", "trunk", 50, "次", "trunk");
        got("hood", EGG, BRONZE, "一探究竟", "打开过前机舱盖", "hood");
        got("allOpen", EGG, BRONZE, "敞开怀抱", "四个车门和尾门同时开着", "caropen");
    }

    static Ach byId(String id) { for (Ach a : ALL) if (a.id.equals(id)) return a; return null; }

    /** 进度 0～1 */
    double progress(Stats s) { double v = val.of(s); return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v / goal)); }

    boolean met(Stats s) {
        if (req != BONUS && !fits(s)) return false; // 不是这种车的（纯电车的纯油专属……）拿不到，也不显示
        double v = val.of(s);
        return !Double.isNaN(v) && v >= goal - 1e-9;
    }

    /**
     * 这台车能不能拿（算不算进墙上的格子和总数）：动力按看到过的数据认（Stats.bat / fuel），还没看到电量、油量时按混动列（同原来的全部）；
     * 隐藏勋章（车上的配置）不算，拿到了才出现
     */
    boolean fits(Stats s) {
        boolean b = s.bat(), f = s.fuel(), unknown = !b && !f;
        switch (req) {
            case BAT: return b || unknown;
            case FUEL: return f || unknown;
            case PHEV: return b && f || unknown;
            case EV_ONLY: return b && !f;
            case ICE_ONLY: return f && !b;
            case BONUS: return false;
            case HAS_MODE: return s.has("has:mode");
            case HAS_SLOPE: return s.has("has:slope");
            default: return true;
        }
    }

    /** 墙上有它：这台车能拿的，或者已经拿到了的 */
    boolean shown(Stats s) { return s.unlocked.containsKey(id) || fits(s); }

    /** 墙上一共几枚（这台车能拿的 + 拿到了的隐藏勋章） */
    static int total(Stats s) { int n = 0; for (Ach a : ALL) if (a.shown(s)) n++; return n; }

    static String fmt(double v) {
        if (v >= 100) return String.format(Locale.US, "%,d", Math.round(Math.floor(v)));
        if (v == Math.floor(v)) return String.format(Locale.US, "%d", (long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    /** 新解锁的（按 ALL 的顺序） */
    static List<Ach> newly(Stats s) {
        List<Ach> o = new ArrayList<>();
        for (Ach a : ALL) if (!s.unlocked.containsKey(a.id) && a.met(s)) o.add(a);
        return o;
    }

    /** 某一类（−1 全部）：已解锁的按等级从高到低在前，没解锁的在后（快要解锁的排前面，反正都是"？？？"） */
    static List<Ach> list(int cat, Stats s) {
        List<Ach> o = new ArrayList<>();
        for (Ach a : ALL) if ((cat < 0 || a.cat == cat) && a.shown(s)) o.add(a);
        Collections.sort(o, (x, y) -> {
            boolean ux = s.unlocked.containsKey(x.id), uy = s.unlocked.containsKey(y.id);
            if (ux != uy) return ux ? -1 : 1;
            if (ux) return x.cat != y.cat ? x.cat - y.cat : y.tier - x.tier;
            return Double.compare(y.progress(s), x.progress(s));
        });
        return o;
    }
}
