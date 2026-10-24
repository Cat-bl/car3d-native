package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.x3card.car3d.gl.Half;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "全部车况"面板（照网页版 carinfo.js）：接口文档里除 ADAS 以外的数据都列出来——宿主信息（版本、能力目录、车架号只露头尾）、
 * 车辆字段（按文档分组）、媒体快照、安卓系统数据。名字、枚举、单位来自字段目录 fields.json（网页版 uiplus-fields.js 原样转的）。
 * 打开时自己订阅、关掉退订；推送逐叶子合并、按目录解码；只改变了的文字，最多 0.3 秒刷新一次。面板外面照样能拖车（同 .tune），车让到旁边不转
 */
@SuppressLint("ViewConstructor")
final class InfoPanel extends Sheet {
    interface Act { void closed(); }
    private static final Map<String, String> HOST = new HashMap<>();
    static { HOST.put("preview", "预览"); HOST.put("dualPip", "画中画"); HOST.put("floating", "悬浮窗"); HOST.put("fullscreen", "全屏"); }

    private final Host host;
    private final Act act;
    private JSONObject F;
    private int gen, seq;
    private final List<String[]> subs = new ArrayList<>(); // [命名空间, 订阅号]
    private Map<String, String> caps;
    private int nFields, nActions, nSys;
    private JSONObject info, raw = new JSONObject(), sysT = new JSONObject(), media;
    private String vin, android;
    private boolean androidAsked;
    private final Map<String, TextView> cells = new HashMap<>();
    private LinearLayout hostBox;
    private boolean pending;

    InfoPanel(Context c, Settings s, Host host, Act act) {
        super(c, s, "全部车况", false);
        this.host = host;
        this.act = act;
    }
    @Override protected void closed() { stop(); act.closed(); }
    void open() {
        if (F == null) {
            try { F = new JSONObject(new String(Half.readAll(getContext().getAssets().open("fields.json")), "UTF-8")); }
            catch (Exception e) { F = null; }
        }
        show(true);
        start();
    }

    // ================= 画：第一次建好所有行，之后只改文字 =================
    @Override protected void rows() {
        body.removeAllViews();
        cells.clear();
        accentChanged();
        if (F == null) { TextView t = new TextView(getContext()); t.setText("字段目录没加载出来"); t.setTextColor(0x99eef1f6); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f)); body.addView(t); return; }
        group("宿主");
        hostBox = new LinearLayout(getContext());
        hostBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(hostBox);
        String g = "";
        JSONArray vf = F.optJSONArray("vehicle");
        for (int i = 0; i < vf.length(); i++) {
            JSONObject f = vf.optJSONObject(i);
            if (!f.optString("g").equals(g)) { g = f.optString("g"); group(g); }
            String[] t = vehText(f);
            row(body, "v-" + f.optString("k"), f.optString("n"), t, f.optBoolean("q") ? "原始读数，单位没证实" : null);
        }
        group("媒体");
        JSONArray mf = F.optJSONArray("media");
        for (int i = 0; i < mf.length(); i++) { JSONObject f = mf.optJSONObject(i); row(body, "m-" + f.optString("k"), f.optString("n"), mediaText(f), null); }
        g = "";
        JSONArray sf = F.optJSONArray("system");
        for (int i = 0; i < sf.length(); i++) {
            JSONObject f = sf.optJSONObject(i);
            if (!f.optString("g").equals(g)) { g = f.optString("g"); group("系统 · " + g); }
            row(body, "s-" + f.optString("k"), f.optString("n"), sysText(f), null);
        }
        TextView n = note("\"—\"是暂时没有数据，\"宿主没有\"是这台车机的宿主没有这个字段；带 * 的是单位没被证实的原始读数。ADAS（障碍物、车道线、巡航）不在这里。");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.2f);
        body.addView(n, lp);
        hostPaint();
    }
    /** 一行（同 .ci-row）：左边名字（下面小字），右边值 */
    private void row(LinearLayout to, String id, String name, String[] t, String sub) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(0.1f), 0, px(0.1f));
        r.setBackground(new BottomLine(0x0fffffff));
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(getContext());
        a.setText(name); a.setTextColor(0xd9eef1f6); a.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.23f));
        l.addView(a);
        if (sub != null) { TextView b = new TextView(getContext()); b.setText(sub); b.setTextColor(0x66eef1f6); b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f)); l.addView(b); }
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = new TextView(getContext());
        v.setGravity(Gravity.END);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.23f));
        v.setMaxWidth(Math.round(card.getWidth() * 0.5f));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.leftMargin = px(0.16f);
        r.addView(v, vp);
        style(v, t);
        if (id != null) cells.put(id, v);
        to.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }
    private void style(TextView v, String[] t) {
        v.setText(t[0]);
        boolean na = "na".equals(t[1]), warn = "warn".equals(t[1]);
        v.setTextColor(na ? 0x61eef1f6 : warn ? 0xffffb547 : 0xffeef1f6);
        v.setTypeface(Typeface.create(Typeface.DEFAULT, na ? 400 : 500, false));
        v.setTag(t[0] + "|" + t[1]);
    }
    private void set(String id, String[] t) { TextView c = cells.get(id); if (c != null && !(t[0] + "|" + t[1]).equals(c.getTag())) style(c, t); }
    private void hostPaint() {
        if (hostBox == null) return;
        hostBox.removeAllViews();
        for (String[] r : hostRows()) row(hostBox, null, r[0], new String[] { r[1], r.length > 2 ? r[2] : "" }, null);
    }
    private void paint() {
        pending = false;
        if (!open || F == null) return;
        JSONArray vf = F.optJSONArray("vehicle"), mf = F.optJSONArray("media"), sf = F.optJSONArray("system");
        for (int i = 0; i < vf.length(); i++) set("v-" + vf.optJSONObject(i).optString("k"), vehText(vf.optJSONObject(i)));
        for (int i = 0; i < mf.length(); i++) set("m-" + mf.optJSONObject(i).optString("k"), mediaText(mf.optJSONObject(i)));
        for (int i = 0; i < sf.length(); i++) set("s-" + sf.optJSONObject(i).optString("k"), sysText(sf.optJSONObject(i)));
        hostPaint();
    }
    private final Runnable painter = this::paint;
    private void later() { if (!pending) { pending = true; postDelayed(painter, 300); } }

    // ================= 显示成什么（同 vehText / sysText / mediaText / hostRows） =================
    private static Object leaf(JSONObject t, String k) {
        String[] p = k.split("\\.");
        Object n = t;
        for (int i = 0; i < p.length && n instanceof JSONObject; i++) n = ((JSONObject) n).opt(p[i]);
        return n == null || n == JSONObject.NULL || n instanceof JSONObject ? null : n;
    }
    private static String base(String k) { return k.substring(0, k.lastIndexOf('.') + 1); }
    private static String pick(JSONArray list, Object v) {
        if (list == null || v == null) return null;
        for (int i = 0; i < list.length(); i++) { JSONArray x = list.optJSONArray(i); if (x != null && same(x.opt(0), v)) return x.optString(1); }
        return null;
    }
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }
    private static String num(Object v) {
        if (!(v instanceof Number)) return String.valueOf(v);
        double d = Math.round(((Number) v).doubleValue() * 100) / 100.0;
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
    private static String vinShort(String v) { return v.length() > 8 ? v.substring(0, 3) + "…" + v.substring(v.length() - 4) : v; }
    private String[] vehText(JSONObject f) {
        String k = f.optString("k");
        if (caps != null && !caps.containsKey(k)) return new String[] { "宿主没有", "na" };
        if ("identity.vin".equals(k)) return vin != null ? new String[] { vinShort(vin), "" } : new String[] { "—", "na" };
        Object v = leaf(raw, k);
        if (v == null) return new String[] { "—", "na" };
        String x = pick(f.optJSONArray("x"), v);
        if (x != null) return new String[] { x + "（" + num(v) + "）", "warn" };
        JSONObject es = f.optJSONObject("es");
        if (es != null) {
            Object src = leaf(raw, base(k) + "stateSource");
            String l = pick(es.optJSONArray(String.valueOf(src)), v);
            return new String[] { l != null ? l + (src != null ? "（" + src + "）" : "") : num(v), l != null && !"正常".equals(l) ? "warn" : "" };
        }
        JSONArray e = f.optJSONArray("e");
        if (e != null) { String el = pick(e, v); return new String[] { el != null ? el + "（" + num(v) + "）" : num(v), "" }; }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            if ("energy.charging.remainingTime".equals(k) && a.length() >= 2) return new String[] { num(a.opt(0)) + " 时 " + num(a.opt(1)) + " 分", "" };
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) b.append(i > 0 ? "、" : "").append(num(a.opt(i)));
            return new String[] { b.toString(), "" };
        }
        String u = f.optString("u", "");
        JSONObject c = f.optJSONObject("c");
        if (c != null) { Object cv = leaf(raw, base(k) + c.optString("k")); String cl = pick(c.optJSONArray("e"), cv); if (cl != null) u = cl; else if (cv != null) u = "单位编码 " + num(cv); }
        boolean q = f.optBoolean("q");
        return new String[] { num(v) + (!u.isEmpty() && !q ? " " + u : "") + (q ? " *" : ""), "" };
    }
    private String[] sysText(JSONObject f) {
        Object v = leaf(sysT, f.optString("k"));
        if (v == null) return new String[] { "—（未知）", "na" };
        if (v instanceof Boolean) return new String[] { (Boolean) v ? "是" : "否", "" };
        String e = pick(f.optJSONArray("e"), v), u = f.optString("u", "");
        return new String[] { (e != null && !e.equals(num(v)) ? e + "（" + num(v) + "）" : num(v)) + (!u.isEmpty() ? " " + u : ""), "" };
    }
    private String[] mediaText(JSONObject f) {
        if (media == null) return new String[] { "—", "na" };
        String k = f.optString("k");
        Object v = media.opt(k);
        if (v == null || v == JSONObject.NULL) return new String[] { "—", "na" };
        if ("playing".equals(k)) return new String[] { Boolean.TRUE.equals(v) ? "在放" : "暂停", "" };
        if (("positionMs".equals(k) || "durationMs".equals(k)) && v instanceof Number) { long s = Math.round(((Number) v).doubleValue() / 1000); return new String[] { s / 60 + ":" + String.format(Locale.US, "%02d", s % 60), "" }; }
        if ("coverUrl".equals(k)) return new String[] { String.valueOf(v).isEmpty() ? "—" : "有封面", "" };
        if ("updatedAt".equals(k) && v instanceof Number) return new String[] { new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(((Number) v).longValue())), "" };
        return new String[] { String.valueOf(v), "" };
    }
    private List<String[]> hostRows() {
        List<String[]> r = new ArrayList<>();
        if (!host.ready()) { r.add(new String[] { "宿主", "没连上宿主", "na" }); return r; }
        String v = info == null ? null : info.optString("appVersion", null);
        r.add(new String[] { "接口版本", info != null && info.has("apiVersion") ? info.optString("apiVersion") : "—" });
        r.add(new String[] { "宿主版本", v != null && !v.isEmpty() ? v : "—" });
        if (info != null && info.has("hostType")) r.add(new String[] { "窗口类型", HOST.containsKey(info.optString("hostType")) ? HOST.get(info.optString("hostType")) : info.optString("hostType") });
        if (androidAsked) r.add(new String[] { "安卓版本", android != null ? android : "—" });
        if (caps != null) r.add(new String[] { "能力目录", nFields + " 个车辆字段 · " + nActions + " 项操作 · " + nSys + " 个系统字段" });
        r.add(new String[] { "车架号", vin != null ? vinShort(vin) : "—" });
        return r;
    }

    // ================= 订阅：打开时订、关掉退订 =================
    private void stop() {
        gen++;
        for (String[] s : subs) { try { host.request(s[0] + ".unsubscribe", new JSONObject().put("subscriptionId", s[1]), null); } catch (Exception ignored) { } }
        subs.clear();
        removeCallbacks(painter);
        pending = false;
    }
    private String sub(String ns, JSONObject params) {
        final String id = "ci" + (++seq);
        final int g = gen;
        try {
            params.put("subscriptionId", id);
            host.request(ns + ".subscribe", params, (r, code, msg) -> {
                if (code != null) return;
                if (g != gen) { try { host.request(ns + ".unsubscribe", new JSONObject().put("subscriptionId", id), null); } catch (Exception ignored) { } }
            });
            subs.add(new String[] { ns, id });
        } catch (Exception ignored) { }
        return id;
    }
    private String carId, sysId, mediaId;
    private void start() {
        stop();
        if (!host.ready() || !host.canRead() || F == null) { later(); return; }
        final int g = gen;
        host.request("info", null, (r, c, m) -> { if (g == gen && r != null) { info = r; later(); } });
        try {
            host.request("system.getProperty", new JSONObject().put("name", "ro.build.version.release"), (r, c, m) -> {
                if (g != gen) return;
                if ("UNSUPPORTED".equals(c) || "UNKNOWN_METHOD".equals(c)) return; // 宿主没有这个方法：不显示这一行
                androidAsked = true;
                android = r != null && r.opt("value") instanceof String ? r.optString("value") : null;
                later();
            });
        } catch (Exception ignored) { }
        host.request("capabilities", null, (r, c, m) -> {
            if (g != gen || r == null) return;
            caps = new HashMap<>();
            JSONArray fs = r.optJSONArray("fields");
            nFields = 0;
            for (int i = 0; fs != null && i < fs.length(); i++) {
                JSONObject o = fs.optJSONObject(i);
                if (o == null || !o.has("name")) continue;
                caps.put(o.optString("name"), o.optString("subscriptionMode", "native"));
                if (!o.optString("name").startsWith("adas.")) nFields++;
            }
            nActions = r.optJSONArray("actions") != null ? r.optJSONArray("actions").length() : 0;
            nSys = r.optJSONArray("systemFields") != null ? r.optJSONArray("systemFields").length() : 0;
            JSONArray keys = new JSONArray(), vf = F.optJSONArray("vehicle");
            for (int i = 0; i < vf.length(); i++) { String k = vf.optJSONObject(i).optString("k"); String mode = caps.get(k); if (mode != null && !"none".equals(mode)) keys.put(k); }
            try { if (keys.length() > 0) carId = sub("vehicle", new JSONObject().put("fields", keys)); } catch (Exception ignored) { }
            if (caps.containsKey("identity.vin")) {
                try {
                    host.request("vehicle.get", new JSONObject().put("keys", new JSONArray().put("identity.vin")), (r2, c2, m2) -> {
                        if (g != gen || r2 == null) return;
                        Object v = leaf(r2.optJSONObject("data") != null ? r2.optJSONObject("data") : r2, "identity.vin");
                        vin = v instanceof String && !((String) v).isEmpty() ? (String) v : null;
                        later();
                    });
                } catch (Exception ignored) { }
            }
            later();
        });
        JSONArray sk = new JSONArray(), sf = F.optJSONArray("system");
        for (int i = 0; i < sf.length(); i++) sk.put(sf.optJSONObject(i).optString("k"));
        try { sysId = sub("system", new JSONObject().put("fields", sk)); } catch (Exception ignored) { }
        host.request("media.get", null, (r, c, m) -> { if (g == gen && r != null) { media = r; later(); } });
        mediaId = sub("media", new JSONObject());
    }
    /** 订阅推送（主线程）：是这里订的才收 */
    boolean onEvent(JSONObject msg) {
        String id = msg.optString("subscriptionId");
        JSONObject d = msg.optJSONObject("data");
        if (!open || d == null || id.isEmpty()) return false;
        if (id.equals(carId)) put(raw, d);
        else if (id.equals(sysId)) put(sysT, d);
        else if (id.equals(mediaId)) media = d;
        else return false;
        later();
        return true;
    }
    private static void put(JSONObject t, JSONObject p) {
        for (Iterator<String> it = p.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = p.opt(k);
            try {
                if (v instanceof JSONObject) { JSONObject sub = t.optJSONObject(k); if (sub == null) { sub = new JSONObject(); t.put(k, sub); } put(sub, (JSONObject) v); }
                else t.put(k, v);
            } catch (Exception ignored) { }
        }
    }
}
