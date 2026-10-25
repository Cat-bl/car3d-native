package com.x3card.car3d.ach;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 成就要的宿主数据（车辆字段、GPS 这类安卓系统字段、媒体快照），和车况页的订阅分开：车况页把编码解码成了字符串、真假，成就按宿主原始的数字编码算。
 * 有了读数据的授权、宿主后端也好了才订：先查能力目录，只订"要的 ∩ 宿主有的"（订阅数组里有一个不认识的字段整个订阅都会失败）；
 * 订失败了按错误码分：后端没好、超时这类过一会儿就好的 2、4、8、16 秒、之后每 30 秒再订，会话还在就一直试；没权限、参数不对、不支持的不再试。
 * 每次订都换一个新的订阅号（一个会话里订阅号不能重复用）。推送按叶子合并成一张平的表（"driving.speed" → 数），null 当没有数据。全部在主线程上
 */
final class AchFeed {
    interface Listener {
        /** 哪些键变了 */
        void onValues(Set<String> changed);
        /** 媒体快照（整体替换） */
        void onMedia(JSONObject snap);
    }

    private final Trophies.Link host;
    private final Listener out;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String[] veh, sys;
    private final HashMap<String, Object> val = new HashMap<>();
    private final HashSet<String> changed = new HashSet<>();
    private String subFor, vehId, sysId, mediaId;
    private int seq, tries;
    private boolean capsAsked, on;
    private HashSet<String> hostVeh, hostSys;

    AchFeed(Trophies.Link host, String[] veh, String[] sys, Listener out) {
        this.host = host;
        this.veh = veh;
        this.sys = sys;
        this.out = out;
    }

    // ================= 读 =================
    /** 数（没有、不是数时是 NaN） */
    double d(String k) { Object o = val.get(k); return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN; }
    int n(String k, int def) { Object o = val.get(k); return o instanceof Number ? ((Number) o).intValue() : def; }
    String s(String k) { Object o = val.get(k); return o instanceof String ? (String) o : null; }
    /** 现在有值的键（清空记录后整个重交一遍） */
    Set<String> keys() { return new HashSet<>(val.keySet()); }

    private void put(String k, Object v) {
        Object o = val.get(k);
        if (v == null ? o == null : v.equals(o)) return;
        if (v == null) val.remove(k); else val.put(k, v);
        changed.add(k);
    }

    // ================= 订阅 =================
    /** 要不要（应用看得见时要）；不要了就退订 */
    void use(boolean want) {
        if (want == on) return;
        on = want;
        if (!want) {
            unsub("vehicle", vehId); unsub("system", sysId); unsub("media", mediaId);
            main.removeCallbacks(retry);
            vehId = sysId = mediaId = null;
            capsAsked = false;
            val.clear(); // 再订时第一份快照从头算（Stats 那边也是重读的，第一个值只当起点）
            changed.clear();
            return;
        }
        subFor = null;
        afterAuth();
    }

    private void unsub(String ns, String id) {
        if (id == null || subFor == null || !subFor.equals(host.session())) return;
        try { host.request(ns + ".unsubscribe", new JSONObject().put("subscriptionId", id), null); } catch (Exception ignored) { }
    }

    /** 宿主连上、授权变了、后端好了时调：新会话从头订，订过的不再订 */
    void afterAuth() {
        if (!on || !host.ready() || !host.canRead()) return;
        String s = host.session();
        if (!s.equals(subFor)) {
            subFor = s;
            vehId = sysId = mediaId = null;
            capsAsked = false;
            hostVeh = hostSys = null;
            tries = 0;
            main.removeCallbacks(retry);
        }
        if (!host.backendReady()) return;
        if (!capsAsked) caps(s);
        else if (hostVeh != null) subscribeAll(s);
    }

    private final Runnable retry = this::afterAuth;

    private void again() {
        main.removeCallbacks(retry);
        main.postDelayed(retry, Math.min(30000, 2000L << Math.min(tries++, 4)));
    }

    /** 过一会儿就会好的错（后端没好、超时、宿主内部出错）；没权限、参数不对、不支持的再试也没用 */
    private static boolean transientErr(String code) {
        return !"PERMISSION_DENIED".equals(code) && !"INVALID_ARGUMENT".equals(code) && !"UNSUPPORTED".equals(code) && !"UNKNOWN_FIELD".equals(code);
    }

    private void caps(final String s) {
        capsAsked = true;
        host.request("capabilities", null, (r, code, msg) -> {
            if (!on || !s.equals(host.session())) return;
            if (code != null) {
                Log.w("car3d", "成就：能力目录 " + code + " " + msg);
                capsAsked = false;
                if (transientErr(code)) again();
                return;
            }
            hostVeh = names(r.optJSONArray("fields"));
            hostSys = names(r.optJSONArray("systemFields"));
            subscribeAll(s);
        });
    }

    private static HashSet<String> names(JSONArray a) {
        HashSet<String> o = new HashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) { JSONObject f = a.optJSONObject(i); if (f != null) o.add(f.optString("name")); }
        return o;
    }

    private void subscribeAll(String s) {
        if (vehId == null && veh.length > 0) sub(s, "vehicle.subscribe", pickFields(veh, hostVeh), 0);
        if (sysId == null && sys.length > 0) sub(s, "system.subscribe", pickFields(sys, hostSys), 1);
        if (mediaId == null) sub(s, "media.subscribe", null, 2);
    }

    private static JSONArray pickFields(String[] want, Set<String> have) {
        JSONArray a = new JSONArray();
        for (String k : want) if (have == null || have.contains(k)) a.put(k);
        return a;
    }

    private void sub(final String s, String method, JSONArray fields, final int kind) {
        if (fields != null && fields.length() == 0) return; // 宿主一个都没有
        final String id = (kind == 0 ? "achVeh" : kind == 1 ? "achSys" : "achMedia") + (++seq);
        if (kind == 0) vehId = id; else if (kind == 1) sysId = id; else mediaId = id;
        try {
            JSONObject p = new JSONObject().put("subscriptionId", id);
            if (fields != null) p.put("fields", fields);
            host.request(method, p, (r, code, msg) -> {
                if (!s.equals(host.session()) || !id.equals(kind == 0 ? vehId : kind == 1 ? sysId : mediaId)) return;
                if (code == null) {
                    tries = 0;
                    if (kind == 2) host.request("media.get", null, (m, c2, m2) -> {
                        if (m == null || !s.equals(host.session()) || !id.equals(mediaId)) return;
                        JSONObject d = m.optJSONObject("data"); // 有的宿主查询回 {requestId, data}
                        out.onMedia(d != null && !m.has("title") ? d : m);
                    });
                    return;
                }
                Log.w("car3d", "成就：" + method + " 失败 " + code + " " + msg);
                if (!transientErr(code)) return; // 不再试（订阅号留着，换会话才重来）
                if (kind == 0) vehId = null; else if (kind == 1) sysId = null; else mediaId = null;
                again();
            });
        } catch (Exception ignored) { }
    }

    /** 宿主交来的订阅推送；是自己的返回真 */
    boolean onEvent(JSONObject m) {
        String id = m.optString("subscriptionId");
        boolean mine = !id.isEmpty() && (id.equals(vehId) || id.equals(sysId) || id.equals(mediaId));
        if (!mine) return false;
        JSONObject d = m.optJSONObject("data");
        if (d == null) { // 订阅被宿主终止了：过一会儿重订
            Log.w("car3d", "成就：订阅终止 " + m);
            if (id.equals(vehId)) vehId = null; else if (id.equals(sysId)) sysId = null; else mediaId = null;
            again();
            return true;
        }
        if (id.equals(mediaId)) { out.onMedia(d); return true; }
        merge(d, "");
        if (!changed.isEmpty()) {
            HashSet<String> c = new HashSet<>(changed);
            changed.clear();
            out.onValues(c);
        }
        return true;
    }

    /** 嵌套的业务树按叶子合并成平的键（数一律转 Double；JSONObject.NULL 是没有数据） */
    private void merge(JSONObject o, String pre) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            String key = pre.isEmpty() ? k : pre + "." + k;
            if (v instanceof JSONObject) merge((JSONObject) v, key);
            else if (v == null || v == JSONObject.NULL) put(key, null);
            else if (v instanceof Number) put(key, ((Number) v).doubleValue());
            else put(key, v);
        }
    }
}
