package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调试面板（照网页版 demo3d/debugpanel.js；只在调试宿主下有）：应用读到的车况、媒体、系统数据、宿主状态都在这里改，应用对宿主的每次调用都记下来。
 * 选了哪项才改哪项：列表里点一项才出它的控件；不调了就回到默认值。勾了"自动"的项自己变（选项轮流切、数值来回扫）。
 * 样式同设置面板；不是 modal，面板外面照样能拖车（打开时车让到面板旁边，同全部车况）
 */
@SuppressLint("ViewConstructor")
final class DebugPanel extends Sheet {
    interface Act { void closed(); }
    private static final String[][] TABS = { { "picked", "正在调试" }, { "v", "车况" }, { "m", "媒体" }, { "s", "系统" }, { "host", "宿主" }, { "log", "记录" } };
    private final DebugHost D;
    private final Act act;
    private final LinearLayout tabs, foot;
    private final TextView sum;
    private String tab = "picked", q = "";
    private final Map<String, Boolean> fold = new HashMap<>();
    private final Map<String, JSONObject> FV = new LinkedHashMap<>(), FS = new LinkedHashMap<>(), FM = new LinkedHashMap<>();
    private boolean pending;
    private final DebugHost.On watch = this::onHost;
    private EditText search;
    private LinearLayout list;

    DebugPanel(Context c, Settings s, DebugHost d, Act a) {
        super(c, s, "调试面板", false);
        D = d; act = a;
        for (JSONObject f : d.vehicle) FV.put(f.optString("k"), f);
        for (JSONObject f : d.system) FS.put(f.optString("k"), f);
        try {
            FM.put("track", new JSONObject().put("k", "track").put("n", "调试歌").put("t", "track").put("s", "调试宿主里的三首歌"));
            FM.put("lyrics", new JSONObject().put("k", "lyrics").put("n", "整首歌词").put("t", "lyrics").put("s", "media.getLyrics"));
        } catch (Exception ignored) { }
        for (JSONObject f : d.media) FM.put(f.optString("k"), f);
        tabs = new LinearLayout(c);
        tabs.setOrientation(LinearLayout.VERTICAL);
        card.addView(tabs, 1, new LinearLayout.LayoutParams(-1, -2));
        foot = new LinearLayout(c);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        sum = new TextView(c);
        sum.setTextColor(0x99eef1f6);
        foot.addView(sum, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(foot, new LinearLayout.LayoutParams(-1, -2));
    }
    private Map<String, JSONObject> F(char dom) { return dom == 'v' ? FV : dom == 's' ? FS : FM; }

    // ================= 外壳 =================
    @Override void show(boolean on) {
        if (on && !open) D.on(watch);
        super.show(on);
    }
    @Override protected void closed() { D.off(watch); act.closed(); }
    @Override protected void rows() {
        int y = scroll.getScrollY();
        body.removeAllViews();
        search = null; list = null;
        tabsRow();
        footRow();
        switch (tab) {
            case "picked": picked(); break;
            case "v": groups('v'); break;
            case "s": groups('s'); break;
            case "m": media(); break;
            case "host": host(); break;
            default: logs();
        }
        sum();
        scroll.post(() -> scroll.scrollTo(0, y));
    }
    private void tabsRow() {
        tabs.removeAllViews();
        List<String> vals = new ArrayList<>();
        String[] names = new String[TABS.length];
        for (int i = 0; i < TABS.length; i++) { vals.add(TABS[i][0]); names[i] = TABS[i][1]; }
        View sg = segOf(vals, names, tab, 3, v -> { tab = (String) v; q = ""; scroll.scrollTo(0, 0); rows(); });
        tabs.setPadding(px(0.32f), px(0.06f), px(0.32f), px(0.04f));
        tabs.addView(sg);
    }
    private void footRow() {
        foot.removeAllViews();
        foot.setPadding(px(0.32f), px(0.12f), px(0.32f), px(0.2f));
        sum.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        foot.addView(sum, new LinearLayout.LayoutParams(0, -2, 1));
        foot.addView(actBtn("全部恢复默认", false, () -> { D.resetAll(); rows(); }));
    }
    private void sum() {
        int n = D.picked().size(), a = 0;
        for (String dk : D.picked()) if (D.autoOf(dk.charAt(0), dk.substring(2)) > 0) a++;
        sum.setText(n > 0 ? "正在调 " + n + " 项" + (a > 0 ? "，" + a + " 项自动变化" : "") : "没在调：都是默认值");
    }
    /** 调试宿主那边变了：自动变化、应用调了操作改了值、订阅、记录。正拖着滑块、正在输入框里打字时先不重画 */
    private void onHost(String what) {
        if (!open) return;
        if ("log".equals(what) && !"log".equals(tab)) return;
        if (("host".equals(what) || "subs".equals(what)) && !"host".equals(tab)) return;
        if ("media".equals(what) && !"m".equals(tab) && !"picked".equals(tab)) return;
        if (dragging || editing()) { pending = true; return; }
        if (search != null && search.hasFocus()) { fill(tab.charAt(0)); sum(); return; } // 正在搜索框里：只重画列表，键盘不收
        if (pending || !"log".equals(what)) { pending = false; rows(); return; }
        if (!later) { later = true; postDelayed(() -> { later = false; if (open && "log".equals(tab)) rows(); }, 250); }
    }
    private boolean later;
    private boolean editing() { View f = findFocus(); return f instanceof EditText && f != search; }
    @Override protected void afterDrag() { if (pending) { pending = false; rows(); } }

    // ================= 一项：没选是一行（名字、字段、现在的值），选上了出控件 =================
    private static final Object NULL = JSONObject.NULL;
    private String show(char dom, JSONObject f, Object v) {
        if (v == null || v == NULL) return "无数据";
        JSONArray o = D.options(dom, f), x = f.optJSONArray("x");
        String hit = name(x, v);
        if (hit != null) return hit + "（" + v + "）";
        hit = name(o, v);
        if (hit != null) return hit + (v instanceof Number ? "（" + v + "）" : "");
        if (v instanceof JSONArray) { JSONArray a = (JSONArray) v; return a.optString(0) + " 时 " + a.optString(1) + " 分"; }
        if ("coverUrl".equals(f.optString("k")) && String.valueOf(v).startsWith("debug://")) return "调试封面";
        String u = f.optString("u", "");
        JSONObject c = f.optJSONObject("c");
        if (c != null && dom == 'v') { String cn = name(c.optJSONArray("e"), D.value('v', DebugHost.base(f.optString("k")) + c.optString("k"))); if (cn != null) u = cn; }
        String s = String.valueOf(v);
        return s.length() > 24 ? s.substring(0, 23) + "…" : s + (u.isEmpty() ? "" : " " + u);
    }
    private static String name(JSONArray o, Object v) {
        for (int i = 0; o != null && i < o.length(); i++) { JSONArray p = o.optJSONArray(i); if (p != null && DebugHost.same(p.opt(0), v)) return p.optString(1); }
        return null;
    }
    private View item(char dom, String k) {
        JSONObject f = F(dom).get(k);
        LinearLayout it = new LinearLayout(getContext());
        it.setOrientation(LinearLayout.VERTICAL);
        it.setPadding(0, px(0.12f), 0, px(0.12f));
        it.setBackground(new BottomLine(0x0fffffff));
        if (f == null) return it;
        boolean on = D.isPicked(dom, k);
        String t = f.optString("t");
        Object v = "track".equals(t) || "lyrics".equals(t) ? null : D.value(dom, k);
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new Check(getContext(), on), new LinearLayout.LayoutParams(px(0.32f), px(0.32f)));
        LinearLayout nm = new LinearLayout(getContext());
        nm.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(getContext());
        n.setText(f.optString("n")); n.setTextColor(TXT); n.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); n.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
        nm.addView(n);
        TextView sm = new TextView(getContext());
        sm.setText(f.optString("s", dom == 'm' ? "media." + k : k) + (dom == 'v' && "none".equals(f.optString("m")) ? " · 只能查询" : ""));
        sm.setTextColor(SUB); sm.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        nm.addView(sm);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1); nl.leftMargin = px(0.14f);
        top.addView(nm, nl);
        TextView val = new TextView(getContext());
        val.setText("track".equals(t) ? DebugHost.TRACKS[D.track()].title : "lyrics".equals(t) ? (D.lyricsOff() ? "返回空" : "正常") : show(dom, f, v));
        val.setTextColor(on ? acc() : 0xbfeef1f6); val.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); val.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        val.setGravity(Gravity.END);
        val.setMaxWidth(Math.round(W * 0.2f));
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2); vl.leftMargin = px(0.12f);
        top.addView(val, vl);
        top.setOnClickListener(x -> { D.pick(dom, k, !D.isPicked(dom, k)); rows(); });
        it.addView(top);
        if (on) it.addView(controls(dom, f, v));
        return it;
    }
    private View controls(char dom, JSONObject f, Object v) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(0.46f), 0, 0, px(0.04f));
        String k = f.optString("k"), t = f.optString("t");
        if ("track".equals(t)) {
            List<Object> vs = new ArrayList<>();
            String[] ns = new String[DebugHost.TRACKS.length];
            for (int i = 0; i < ns.length; i++) { vs.add(i); ns[i] = DebugHost.TRACKS[i].title; }
            c.addView(segOf(vs, ns, D.track(), 0, x -> { D.track((Integer) x); rows(); }));
            return c;
        }
        if ("lyrics".equals(t)) {
            c.addView(segOf(Arrays.asList(false, true), new String[] { "正常返回", "返回空（歌词还没准备好）" }, D.lyricsOff(), 0, x -> { D.lyricsOff((Boolean) x); rows(); }));
            return c;
        }
        JSONArray o = D.options(dom, f), x = f.optJSONArray("x"), r = f.optJSONArray("r");
        if (o != null) c.addView(segPairs(o, v, val -> { D.set(dom, k, val); rows(); }));
        if (x != null) c.addView(segPairs(x, v, val -> { D.set(dom, k, val); rows(); }));
        if ("array".equals(t)) {
            JSONArray a = v instanceof JSONArray ? (JSONArray) v : new JSONArray(Arrays.asList(0, 0));
            LinearLayout in = inRow();
            EditText h = input(String.valueOf(a.opt(0)), true), m = input(String.valueOf(a.opt(1)), true);
            Runnable commit = () -> { try { D.set(dom, k, new JSONArray(Arrays.asList(Long.parseLong(h.getText().toString().trim()), Long.parseLong(m.getText().toString().trim())))); } catch (Exception ignored) { } rows(); };
            done(h, commit); done(m, commit);
            in.addView(h, new LinearLayout.LayoutParams(0, px(0.52f), 1)); in.addView(unit("时")); in.addView(m, new LinearLayout.LayoutParams(0, px(0.52f), 1)); in.addView(unit("分"));
            c.addView(in);
        } else if ("number".equals(t) && (o == null || r != null)) {
            if (r != null) {
                float lo = (float) r.optDouble(0), hi = (float) r.optDouble(1), st = (float) r.optDouble(2, 1);
                float cur = v instanceof Number ? ((Number) v).floatValue() : lo;
                c.addView(new Slider(getContext(), "拖动", lo, hi, st, Math.max(lo, Math.min(hi, cur)), fv -> fv == Math.rint(fv) ? String.valueOf((long) fv) : String.valueOf(fv),
                    fv -> D.set(dom, k, fv == Math.rint(fv) ? (Object) (long) fv : (Object) (double) fv), null));
            }
            if (o == null) {
                LinearLayout in = inRow();
                EditText e = input(v instanceof Number ? String.valueOf(v) : "", true);
                done(e, () -> { String s = e.getText().toString().trim(); try { double d = Double.parseDouble(s); D.set(dom, k, d == Math.rint(d) ? (Object) (long) d : (Object) d); } catch (Exception ignored) { } rows(); });
                in.addView(e, new LinearLayout.LayoutParams(0, px(0.52f), 1));
                if (!f.optString("u", "").isEmpty()) in.addView(unit(f.optString("u")));
                c.addView(in);
            }
        } else if ("string".equals(t) && o == null) {
            LinearLayout in = inRow();
            EditText e = input(v == null || v == NULL ? "" : String.valueOf(v), false);
            done(e, () -> { D.set(dom, k, e.getText().toString()); rows(); });
            in.addView(e, new LinearLayout.LayoutParams(0, px(0.52f), 1));
            c.addView(in);
        }
        if ("coverUrl".equals(k)) {
            try { c.addView(segPairs(new JSONArray().put(new JSONArray().put("debug://cover/" + D.track()).put("调试封面")).put(new JSONArray().put("file:///no-such-cover.png").put("打不开的地址")), v, val -> { D.set(dom, k, val); rows(); })); } catch (Exception ignored) { }
        }
        JSONObject cp = f.optJSONObject("c");
        if (cp != null && dom == 'v') {
            final String ck = DebugHost.base(k) + cp.optString("k");
            c.addView(note(("stateSource".equals(cp.optString("k")) ? "状态来源" : "单位") + "（同节点 " + cp.optString("k") + "）"));
            c.addView(segPairs(cp.optJSONArray("e"), D.value('v', ck), val -> { D.set('v', ck, val); rows(); }));
        }
        boolean canAuto = dom != 'm' && (o != null || r != null);
        LinearLayout acts = hbox();
        View nb = actBtn("无数据（null）", false, () -> { D.set(dom, k, NULL); rows(); });
        if (v == null || v == NULL) nb.setAlpha(0.4f);
        acts.addView(nb);
        if (canAuto) acts.addView(actBtn(D.autoOf(dom, k) > 0 ? "自动变化中" : "自动变化", D.autoOf(dom, k) > 0, () -> { D.auto(dom, k, D.autoOf(dom, k) > 0 ? 0 : 3); rows(); }));
        acts.addView(actBtn("不调了", false, () -> { D.pick(dom, k, false); rows(); }));
        c.addView(acts);
        if (canAuto && D.autoOf(dom, k) > 0) c.addView(segOf(Arrays.asList(1, 3, 8), new String[] { "快（1 秒）", "中（3 秒）", "慢（8 秒）" }, D.autoOf(dom, k), 0, p -> { D.auto(dom, k, (Integer) p); rows(); }));
        return c;
    }
    /** [[值, 名字], ...] 排成分段按钮 */
    private View segPairs(JSONArray o, Object cur, Pick pick) {
        List<Object> vs = new ArrayList<>();
        String[] ns = new String[o == null ? 0 : o.length()];
        for (int i = 0; i < ns.length; i++) { JSONArray p = o.optJSONArray(i); vs.add(p.opt(0)); ns[i] = p.optString(1); }
        return segOf(vs, ns, cur, ns.length > 4 ? 3 : 0, pick);
    }
    private LinearLayout inRow() { LinearLayout l = new LinearLayout(getContext()); l.setGravity(Gravity.CENTER_VERTICAL); l.setPadding(0, px(0.12f), 0, 0); return l; }
    private TextView unit(String s) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(0xb3eef1f6); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        t.setPadding(px(0.14f), 0, px(0.14f), 0);
        return t;
    }
    private EditText input(String text, boolean number) {
        EditText e = new EditText(getContext());
        e.setText(text);
        e.setSingleLine(true);
        e.setTextColor(TXT);
        e.setHintTextColor(SUB);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED : InputType.TYPE_CLASS_TEXT);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setPadding(px(0.16f), 0, px(0.16f), 0);
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(px(0.14f)); d.setColor(0x0fffffff); d.setStroke(Math.max(1, Math.round(dens)), 0x29ffffff);
        e.setBackground(d);
        return e;
    }
    /** 输入框：按完成键、离开输入框时交出去 */
    private void done(EditText e, Runnable commit) {
        e.setOnEditorActionListener((v, id, ev) -> { e.clearFocus(); hideIme(e); return true; });
        e.setOnFocusChangeListener((v, has) -> { if (!has) post(commit); });
    }
    private void hideIme(View v) {
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
    private TextView empty(String s) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(0x99eef1f6); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f)); t.setLineSpacing(0, 1.4f);
        t.setPadding(px(0.06f), px(0.36f), px(0.06f), px(0.36f));
        return t;
    }

    // ================= 各页 =================
    private void picked() {
        List<String> l = D.picked();
        if (l.isEmpty()) { body.addView(empty("还没有在调的项。\n到\"车况\"\"媒体\"\"系统\"里点一项就开始调它，应用马上读到新的值；选了哪项才改哪项，没选的保持默认（一台停着的车）。")); return; }
        for (String dk : l) body.addView(item(dk.charAt(0), dk.substring(2)));
    }
    private void groups(char dom) {
        if (search == null) {
            search = input(q, false);
            search.setHint("搜名字或字段，比如 车门、tyre");
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { q = s.toString().trim(); fill(dom); }
            });
            search.setOnEditorActionListener((v, id, ev) -> { hideIme(v); return true; });
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, px(0.56f)); sl.topMargin = px(0.16f);
        body.addView(search, sl);
        list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, new LinearLayout.LayoutParams(-1, -2));
        fill(dom);
    }
    /** 搜索框下面的分组列表（打字时只重画这一块，输入框不动） */
    private void fill(char dom) {
        if (list == null) return;
        list.removeAllViews();
        Map<String, List<JSONObject>> by = new LinkedHashMap<>();
        String qq = q.toLowerCase(Locale.ROOT);
        for (JSONObject f : F(dom).values()) {
            String k = f.optString("k"), n = f.optString("n");
            if (!qq.isEmpty() && !k.toLowerCase(Locale.ROOT).contains(qq) && !n.contains(q)) continue;
            String g = f.optString("g", "其他");
            List<JSONObject> l = by.get(g);
            if (l == null) by.put(g, l = new ArrayList<>());
            l.add(f);
        }
        if (by.isEmpty()) { list.addView(empty("没有对得上的字段")); return; }
        for (Map.Entry<String, List<JSONObject>> e : by.entrySet()) {
            final String g = e.getKey(), key = dom + g;
            int n = 0;
            for (JSONObject f : e.getValue()) if (D.isPicked(dom, f.optString("k"))) n++;
            boolean op = !qq.isEmpty() || Boolean.TRUE.equals(fold.get(key));
            list.addView(groupBtn(g, e.getValue().size() + " 项" + (n > 0 ? " · 正在调 " + n : ""), op, () -> { fold.put(key, !Boolean.TRUE.equals(fold.get(key))); fill(dom); }));
            if (op) for (JSONObject f : e.getValue()) list.addView(item(dom, f.optString("k")));
        }
    }
    private View groupBtn(String g, String small, boolean on, Runnable click) {
        LinearLayout b = new LinearLayout(getContext());
        b.setGravity(Gravity.BOTTOM);
        b.setPadding(px(0.02f), px(0.14f), px(0.02f), px(0.14f));
        b.setBackground(new BottomLine(0x1affffff));
        TextView t = new TextView(getContext());
        t.setText(g); t.setTextColor(TXT); t.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false)); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.25f));
        b.addView(t);
        TextView s = new TextView(getContext());
        s.setText(small); s.setTextColor(0x73eef1f6); s.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -2, 1); sl.leftMargin = px(0.16f);
        b.addView(s, sl);
        TextView ar = new TextView(getContext());
        ar.setText("›"); ar.setTextColor(0x73eef1f6); ar.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.25f));
        ar.setRotation(on ? 90 : 0);
        b.addView(ar);
        b.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = px(0.14f);
        b.setLayoutParams(lp);
        return b;
    }
    private void media() {
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(actBtn(D.playing() ? "暂停" : "播放", true, () -> { D.set('m', "playing", !D.playing()); rows(); }));
        TextView n = note("播放时像车机一样每换一句歌词推一次快照（氛围灯的音乐律动跟着它）");
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(r);
        for (String k : FM.keySet()) body.addView(item('m', k));
    }
    private LinearLayout hostRow(String name, String small) {
        LinearLayout r = row(true);
        r.addView(label(name, null, small));
        return r;
    }
    private void host() {
        LinearLayout r = row(false);
        r.addView(label("车控权限", null, "关着：80 项原生操作（含车顶空调键、播放控制）都返回 PERMISSION_DENIED"), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(new Switch(getContext(), D.control(), () -> { D.setControl(!D.control()); rows(); }), new LinearLayout.LayoutParams(px(0.78f), px(0.44f)));
        r = hostRow("连接", "断开：订阅收到 BACKEND_UNAVAILABLE、请求失败；恢复时补发完整快照");
        r.addView(segOf(Arrays.asList(true, false), new String[] { "正常", "断开" }, D.online(), 0, v -> { D.setConnected((Boolean) v); rows(); }));
        r = hostRow("下几次订阅失败", D.failLeft() > 0 ? "还剩 " + D.failLeft() + " 次" : "应用藏起来再回来、重订时用得上");
        r.addView(segOf(Arrays.asList(0, 1, 3), new String[] { "不失败", "下 1 次", "下 3 次" }, D.failLeft() > 1 ? 3 : D.failLeft(), 0, v -> { D.setFail((Integer) v); rows(); }));
        r.addView(segOf(Arrays.asList("BUSY", "RESOURCE_LIMIT"), new String[] { "BUSY", "RESOURCE_LIMIT" }, D.failCode(), 0, v -> { D.setFailCode((String) v); rows(); }));
        List<DebugHost.Sub> subs = D.subs();
        r = hostRow("正在订阅", subs.isEmpty() ? "没有" : subs.size() + " 个");
        for (DebugHost.Sub s : subs) {
            String w = s.dom == 'v' ? "车况" : s.dom == 's' ? "系统数据" : "媒体";
            TextView t = note(w + (s.dom == 'm' ? "" : " " + s.fields.size() + " 个字段：" + String.join("、", s.fields)));
            t.setTextColor(0x99eef1f6);
            r.addView(t);
        }
    }
    private void logs() {
        List<DebugHost.Log> l = D.log();
        LinearLayout r = hbox();
        r.setGravity(Gravity.CENTER_VERTICAL);
        View cl = actBtn("清空", false, () -> { D.clearLog(); rows(); });
        if (l.isEmpty()) cl.setAlpha(0.4f);
        r.addView(cl);
        r.addView(note("应用对宿主的每次调用，最新的在上面"), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(r);
        if (l.isEmpty()) { body.addView(empty("还没有调用")); return; }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        for (int i = 0; i < Math.min(120, l.size()); i++) {
            DebugHost.Log e = l.get(i);
            LinearLayout it = new LinearLayout(getContext());
            it.setOrientation(LinearLayout.VERTICAL);
            it.setPadding(0, px(0.1f), 0, px(0.1f));
            it.setBackground(new BottomLine(0x0fffffff));
            TextView h = new TextView(getContext());
            h.setText(fmt.format(new Date(e.t)) + "  " + e.m); h.setTextColor(TXT); h.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
            it.addView(h);
            TextView a = new TextView(getContext());
            a.setText(e.a); a.setTextColor(0xa6eef1f6); a.setTypeface(Typeface.MONOSPACE); a.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f));
            it.addView(a);
            TextView rs = new TextView(getContext());
            rs.setText(e.ok ? "→ " + e.r : e.r + (e.msg.isEmpty() ? "" : "：" + e.msg)); rs.setTextColor(e.ok ? 0xff4cd38a : 0xffff6b6b); rs.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
            it.addView(rs);
            body.addView(it);
        }
    }

    /** 勾选框（.dbg-ck） */
    private final class Check extends View {
        final boolean on; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Check(Context c, boolean on) { super(c); this.on = on; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), sw = 0.025f * rem, r = 0.08f * rem;
            if (on) { p.setStyle(Paint.Style.FILL); p.setColor(acc()); c.drawRoundRect(0, 0, w, h, r, r, p); }
            else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw); p.setColor(0x59ffffff); c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, r, r, p); }
            if (on) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.03f * rem); p.setColor(0xffffffff); p.setStrokeCap(Paint.Cap.ROUND);
                android.graphics.Path k = new android.graphics.Path();
                k.moveTo(w * 0.26f, h * 0.52f); k.lineTo(w * 0.44f, h * 0.7f); k.lineTo(w * 0.76f, h * 0.32f);
                c.drawPath(k, p);
            }
        }
    }
}
