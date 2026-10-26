package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 车牌（照网页版 cartune.js 的车牌页）：前 / 后各自开关、牌种（含自定义颜色）、按国标的按键敲号、宽度、位置、角度、重新自动摆放；按住车上的车牌拖在 CarPage 里。
 * 不挡手势的面板（同全部车况）：车让到旁边，面板外面照样能拖车、拖车牌。改了就交给车（只换车牌，不重建车），同时存起来（Plates.saveOwn / saveSide）
 */
@SuppressLint("ViewConstructor")
final class PlatePanel extends Sheet {
    interface Act { void plates(JSONObject st); void edit(String side); void closed(); }

    private final Act act;
    /** 合并后的车牌配置（车模包的盖上自己改的），这里改了交给车 */
    private JSONObject st;
    /** 车的半宽、高、半长（滑块范围）、这台车在存储里的名字、正在调哪块 */
    private float[] dims = { 1.02f, 1.26f, 2.3f };
    private String key = "w70", side = "rear";
    /** 拖滑块时先不存，松手再存 */
    private boolean ownDirty;
    private final Set<String> sideDirty = new HashSet<>();
    private TextView numView;

    PlatePanel(Context c, Settings s, Act act) {
        super(c, s, "车牌", false);
        this.act = act;
    }
    boolean ready() { return st != null; }
    JSONObject current() { return st; }
    void open() { show(true); act.edit(side); }
    @Override protected void closed() { flush(); act.closed(); }
    @Override protected void afterDrag() { flush(); }
    /** 建好车、换了车模、另一个界面改过：合并后的车牌配置和车的尺寸 */
    void setData(JSONObject st, float[] dims, String key) {
        this.st = st; this.dims = dims; this.key = key;
        sideDirty.clear(); ownDirty = false;
        refresh();
    }
    /** 车上自动摆好的、拖完的位置：记下来（拖的、这块已经存着的才存） */
    void plateAt(String which, JSONArray at, boolean drag) {
        if (st == null) return;
        JSONObject p = side(which);
        put(p, "at", at);
        if (drag || Plates.hasSide(getContext(), key, which)) Plates.saveSide(getContext(), key, which, p);
        refresh();
    }
    private void refresh() {
        if (!open || dragging) return;
        int y = scroll.getScrollY();
        rows();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ================= 改了：交给车、存起来 =================
    private JSONObject side(String which) {
        JSONObject p = st.optJSONObject(which);
        if (p == null) { p = new JSONObject(); put(st, which, p); }
        return p;
    }
    private static void put(JSONObject o, String k, Object v) { try { o.put(k, v); } catch (Exception ignored) { } }
    private void push() { try { act.plates(new JSONObject(st.toString())); } catch (Exception ignored) { } }
    private void flush() {
        if (st == null) return;
        if (ownDirty) Plates.saveOwn(getContext(), st);
        for (String k : sideDirty) Plates.saveSide(getContext(), key, k, side(k));
        ownDirty = false; sideDirty.clear();
    }
    /** 号、牌种、颜色改了 */
    private void ownChanged() { ownDirty = true; if (!dragging) flush(); push(); }
    /** 这一块的开关、位置、宽度、角度改了 */
    private void sideChanged(String which) { sideDirty.add(which); if (!dragging) flush(); push(); }

    // ================= 面板里的一行行（同 platePage） =================
    @Override protected void rows() {
        body.removeAllViews();
        accentChanged();
        numView = null;
        if (st == null) { body.addView(note("车模还在载入，等一下再打开")); return; }
        final boolean front = "front".equals(side);
        final JSONObject p = side(side);
        final String style = st.optString("style", "blue");
        LinearLayout r = row(true);
        r.addView(segOf(Arrays.asList(Plates.SIDES), new String[] { "前车牌", "后车牌" }, side, 0, v -> { side = (String) v; act.edit(side); refresh(); }));
        r = row(false);
        r.addView(label("挂上这块", null, "先自动摆在" + (front ? "车头" : "车尾") + "下部，不对就按住车上的车牌拖"), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(new Switch(getContext(), p.optBoolean("on"), () -> { put(p, "on", !p.optBoolean("on")); sideChanged(side); refresh(); }), new LinearLayout.LayoutParams(px(0.78f), px(0.44f)));
        if (!p.optBoolean("on")) {
            body.addView(note("车牌跟车况不联动，纯装饰。蓝牌、绿牌、黄牌、警车、应急、教练、使馆、港澳等国内的牌种都有，也能自定义颜色；点下面的按键把车牌号敲进去，位置按住车牌拖，宽度、角度用滑块调。"));
            return;
        }
        // 牌种
        r = row(true);
        r.addView(label("牌种", null, PlateRule.typeNote(style)));
        List<String> ids = new ArrayList<>();
        String[] names = new String[PlateRule.TYPES.length];
        for (int i = 0; i < PlateRule.TYPES.length; i++) { ids.add(PlateRule.TYPES[i][0]); names[i] = PlateRule.TYPES[i][1]; }
        r.addView(segOf(ids, names, style, 5, v -> setStyle((String) v)));
        if ("custom".equals(style)) custom(r);
        // 号：以前存的不合国标的，不合的字去掉，别的留着
        String t0 = st.optString("text", "");
        if (!PlateRule.ok(style, t0)) t0 = PlateRule.conv(style, style, t0);
        final String tx = PlateRule.fill(style, t0);
        put(st, "text", tx);
        r = row(true);
        numView = new TextView(getContext());
        numView.setGravity(Gravity.CENTER);
        numView.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        numView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.3f));
        numView.setLetterSpacing(0.17f);
        numView.setMinHeight(px(0.56f));
        numView.setSingleLine(true);
        paintNum();
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = px(0.04f); nl.bottomMargin = px(0.12f);
        r.addView(numView, nl);
        String nx = PlateRule.next(style, tx);
        r.addView(label("车牌号", null, PlateRule.len(style) + " 位，照国标；" + PlateRule.hint(style, tx)));
        if (PlateRule.hasProv(style)) r.addView(keys(PlateRule.PROVS, nx, style, tx));
        r.addView(keys(PlateRule.PLET + PlateRule.PDIG, nx, style, tx));
        String ex = PlateRule.extra(style);
        if (!ex.isEmpty()) r.addView(keys(ex, nx, style, tx));
        LinearLayout acts = hbox();
        boolean any = PlateRule.typed(style, tx) > 0;
        View bk = actBtn("删一个", false, () -> { put(st, "text", PlateRule.back(style, tx)); ownChanged(); refresh(); });
        View cl = actBtn("清空", false, () -> { put(st, "text", PlateRule.fill(style, "")); ownChanged(); refresh(); });
        for (View b : new View[] { bk, cl }) { b.setEnabled(any); b.setAlpha(any ? 1 : 0.35f); acts.addView(b); }
        r.addView(acts);
        // 大小、位置
        r = row(true);
        r.addView(label("大小、位置", null, "正在调" + (front ? "前" : "后") + "这块"));
        Plates.Lay L = Plates.lay(Plates.style(st), !front);
        float w0 = (float) p.optDouble("w", L.W / 1000);
        r.addView(new Slider(getContext(), "宽度", 0.3f, 0.6f, 0.005f, clamp(w0, 0.3f, 0.6f), PlatePanel::meters, v -> { put(p, "w", r3(v)); sideChanged(side); }, null)); // 没调过是国标宽度
        final float[] at = atOf(p, front);
        float yMax = round2(dims[1] * 0.95f), xMax = round2(dims[0]), zMax = round2(dims[2]);
        String[] an = { "左右", "上下", "前后" };
        float[][] lim = { { -xMax, xMax }, { 0.05f, yMax }, { -zMax, zMax } };
        for (int i : new int[] { 1, 0, 2 }) {
            final int ix = i;
            r.addView(new Slider(getContext(), an[i], lim[i][0], lim[i][1], 0.005f, clamp(at[i], lim[i][0], lim[i][1]), PlatePanel::meters, v -> {
                at[ix] = v;
                put(p, "at", new JSONArray(Arrays.asList(r3(at[0]), r3(at[1]), r3(at[2]))));
                sideChanged(side);
            }, null));
        }
        r.addView(new Slider(getContext(), "左右转", -30, 30, 1, clamp((float) p.optDouble("yaw", 0), -30, 30), v -> (v > 0 ? "往右 " : v < 0 ? "往左 " : "") + Math.round(Math.abs(v)) + "°",
            v -> { put(p, "yaw", (double) Math.round(v)); sideChanged(side); }, null));
        r.addView(new Slider(getContext(), "俯仰", -30, 30, 1, clamp((float) p.optDouble("pitch", 0), -30, 30), v -> (v > 0 ? "朝上 " : v < 0 ? "朝下 " : "") + Math.round(Math.abs(v)) + "°",
            v -> { put(p, "pitch", (double) Math.round(v)); sideChanged(side); }, null));
        LinearLayout a2 = hbox();
        a2.addView(actBtn("重新自动摆放", false, () -> { p.remove("at"); sideChanged(side); })); // 车上摆好了交回来再显示
        r.addView(a2);
        r.addView(note("按住车上的车牌拖到想要的位置（后车牌贴在尾门上时，开尾门会跟着翻起来）；挂的地方是斜的、弯的，就把\"左右转、俯仰\"转一点贴上去（站在这块牌子前面看）。"));
    }
    /** 换牌种：号各归各位转过去，两块的宽度都回到这种牌的国标宽度 */
    private void setStyle(String v) {
        String from = st.optString("style", "blue");
        if (v.equals(from)) return;
        put(st, "text", PlateRule.conv(from, v, st.optString("text", "")));
        put(st, "style", v);
        Plates.clearWidths(getContext());
        for (String sd : Plates.SIDES) {
            JSONObject q = st.optJSONObject(sd);
            if (q != null && q.has("w")) { q.remove("w"); Plates.saveSide(getContext(), key, sd, q); }
        }
        ownChanged();
        refresh();
    }
    /** 自定义：底色的色相 / 饱和度 / 亮度（白、灰、黑拖色相时把饱和度、亮度拉到看得出颜色的地方，同设置里的自定义颜色），黑字 / 白字 */
    private void custom(LinearLayout r) {
        int[] a = Settings.hex2hsl(st.optString("bg", "#e9e9e9"));
        final float[] cur = { a[0], a[1], a[2] };
        TextView h = new TextView(getContext());
        h.setText("底色"); h.setTextColor(0xb3eef1f6); h.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        h.setPadding(0, px(0.16f), 0, 0);
        r.addView(h);
        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.parseColor(Settings.hsl2hex(i * 60 % 360, 100, 50));
        r.addView(new Slider(getContext(), "色相", 0, 360, 1, cur[0], v -> Math.round(v) + "°", v -> {
            cur[0] = v;
            if (cur[1] < 25) cur[1] = 70;
            if (cur[2] > 85 || cur[2] < 15) cur[2] = 50;
            bg(cur);
        }, hues));
        r.addView(new Slider(getContext(), "饱和度", 0, 100, 1, cur[1], v -> Math.round(v) + "%", v -> { cur[1] = v; bg(cur); },
            new int[] { Color.parseColor(Settings.hsl2hex(cur[0], 0, 50)), Color.parseColor(Settings.hsl2hex(cur[0], 100, 50)) }));
        r.addView(new Slider(getContext(), "亮度", 0, 100, 1, cur[2], v -> Math.round(v) + "%", v -> { cur[2] = v; bg(cur); },
            new int[] { 0xff000000, Color.parseColor(Settings.hsl2hex(cur[0], cur[1], 50)), 0xffffffff }));
        TextView f = new TextView(getContext());
        f.setText("字的颜色"); f.setTextColor(0xb3eef1f6); f.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        f.setPadding(0, px(0.16f), 0, 0);
        r.addView(f);
        String fg = "#f4f6fa".equalsIgnoreCase(st.optString("fg")) ? "#f4f6fa" : "#15171a";
        r.addView(segOf(Arrays.asList("#15171a", "#f4f6fa"), new String[] { "黑字", "白字" }, fg, 0, v -> { put(st, "fg", v); ownChanged(); refresh(); }));
    }
    private void bg(float[] c) { put(st, "bg", Settings.hsl2hex(c[0], c[1], c[2])); paintNum(); ownChanged(); }
    /** 一排按键（.pkeys）：这一位不能用的字是灰的 */
    private View keys(String chars, String nx, String style, String tx) {
        Flow box = new Flow(getContext());
        for (char ch : chars.toCharArray()) {
            final String c = String.valueOf(ch);
            boolean ok = nx.contains(c);
            TextView b = new TextView(getContext());
            b.setText(c);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.24f));
            b.setTextColor(0xa6eef1f6);
            b.setMinWidth(px(0.52f)); b.setMinimumWidth(px(0.52f));
            b.setMinHeight(px(0.48f)); b.setMinimumHeight(px(0.48f));
            b.setPadding(px(0.06f), 0, px(0.06f), 0);
            GradientDrawable d = new GradientDrawable(); d.setCornerRadius(px(0.24f)); d.setStroke(Math.max(1, Math.round(dens)), 0x24ffffff);
            b.setBackground(d);
            b.setEnabled(ok); b.setAlpha(ok ? 1 : 0.28f);
            b.setOnClickListener(v -> {
                if (!PlateRule.next(style, tx).contains(c)) return; // 这一位不能用这个字（按键本来是灰的）
                put(st, "text", PlateRule.fill(style, tx + c));
                ownChanged();
                refresh();
            });
            box.addView(b);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.12f);
        box.setLayoutParams(lp);
        return box;
    }

    // ================= 号码条（.pnum）：底色、字色同车上的牌面 =================
    private void paintNum() {
        if (numView == null) return;
        String style = st.optString("style", "blue");
        int[] lk = look(st);
        numView.setTextColor(lk[1]);
        numView.setText(PlateRule.num(style, st.optString("text", ""), side, Plates.RED));
        numView.setBackground(new NumBg(style, lk[0], lk[1]));
    }
    /** 底色、字色（绿牌、黄绿牌的底色是渐变，在 NumBg 里画） */
    private static int[] look(JSONObject st) {
        switch (st.optString("style", "blue")) {
            case "blue": return new int[] { 0xff213f99, 0xffffffff };
            case "green": case "bignev": return new int[] { 0, 0xff231f20 };
            case "yellow": case "coach": case "trailer": case "lowspeed": return new int[] { 0xffffc20d, 0xff231f20 };
            case "police": case "emergency": return new int[] { 0xffffffff, 0xff231f20 };
            case "embassy": case "consulate": case "hkmo": return new int[] { 0xff17181a, 0xffffffff };
            default: return new int[] { Color.parseColor(st.optString("bg", "#e9e9e9")), Color.parseColor(st.optString("fg", "#15171a")) };
        }
    }
    private final class NumBg extends android.graphics.drawable.Drawable {
        final String style; final int bg, fg; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        NumBg(String style, int bg, int fg) { this.style = style; this.bg = bg; this.fg = fg; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds());
            float r = 0.05f * rem, bw = Math.max(1, 0.015f * rem);
            if ("green".equals(style)) p.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[] { 0xffffffff, 0xff7dc685, 0xff7dc685 }, new float[] { 0, 0.85f, 1 }, Shader.TileMode.CLAMP));
            else if ("bignev".equals(style)) p.setShader(new LinearGradient(b.left, 0, b.right, 0, new int[] { 0xffeba749, 0xffeba749, 0xff7dc685, 0xff7dc685 }, new float[] { 0, 0.284f, 0.284f, 1 }, Shader.TileMode.CLAMP));
            else { p.setShader(null); p.setColor(bg); }
            p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(b, r, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(bw); p.setColor(fg);
            b.inset(bw / 2, bw / 2);
            c.drawRoundRect(b, r, r, p);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ================= 小工具 =================
    /** 位置（还没交回来时按外框兜底，同 plateAuto 的兜底） */
    private float[] atOf(JSONObject p, boolean front) {
        JSONArray a = p.optJSONArray("at");
        if (a != null && a.length() == 3) return new float[] { (float) a.optDouble(0), (float) a.optDouble(1), (float) a.optDouble(2) };
        return new float[] { 0, dims[1] * 0.3f, (front ? 1 : -1) * dims[2] * 0.98f };
    }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float round2(float v) { return Math.round(v * 100) / 100f; }
    private static double r3(float v) { return Math.round(v * 1000) / 1000.0; }
    private static String meters(float v) { return new BigDecimal(String.valueOf(r3(v))).stripTrailingZeros().toPlainString() + " 米"; }
}
