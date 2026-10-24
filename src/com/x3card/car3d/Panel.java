package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设置面板（照网页版 index.html 的 #set 和 style.css 的 .set-card：深色卡片、顶上一道强调色细线、一行行分组），去掉唱片机、调整车模、贴纸、调试面板、新手引导那几项。
 * 车漆 / 轮毂 / 卡钳是带高光的小球，最后一个"自定义"展开色相 / 饱和度 / 亮度三个滑块；强调色跟着车漆。
 * 滑块自己接触摸：竖着划是翻面板，横着拖才改值（同网页版 main.js 里那段）。点面板外面关掉
 */
@SuppressLint("ViewConstructor")
final class Panel extends Sheet {
    interface Act { void act(String name); void closed(); }

    private final Act act;
    private boolean fsRow;
    private String modelName = "", modelCredit = "";
    private boolean resetOff = true;
    private String[] tips = { "", "", "" };

    Panel(Context c, Settings s, Act act) {
        super(c, s, "设置", true);
        this.act = act;
    }
    @Override protected void closed() { act.closed(); }
    void setFs(boolean on) { fsRow = on; if (open) rows(); }
    /** 调试宿主（没有车机时）：最下面一行打开调试面板 */
    void setDebug(boolean on) { dbgRow = on; if (open) rows(); }
    private boolean dbgRow;
    void setModel(String name, String credit, boolean off, String[] t) { modelName = name; modelCredit = credit; resetOff = off; tips = t; if (open) rows(); }
    /** 设置在别处改了：面板开着就重画（保持滚动位置）。拖滑块改的（视角、压暗、自定义颜色）不重画（正拖着的滑块会被换掉），只让各处按新值重画一遍 */
    void refresh(String k) {
        if (!open) return;
        if (Settings.NUM.containsKey(k) || Settings.HEX.containsKey(k)) { repaint(body); accentChanged(); return; }
        int y = scroll.getScrollY();
        rows();
        scroll.post(() -> scroll.scrollTo(0, y));
    }
    private long resetArm;

    // ================= 面板里的一行行 =================
    @Override protected void afterDrag() { refresh(""); }
    @Override protected void rows() {
        body.removeAllViews();
        accentChanged();
        group("车模");
        LinearLayout r = row(true);
        r.addView(label("当前车模", modelName, modelCredit));
        LinearLayout acts = hbox();
        acts.addView(actBtn("导入车模包", true, () -> act.act("import")));
        boolean armed = System.currentTimeMillis() - resetArm < 3000;
        View rs = actBtn(armed ? "再点一次确认" : "恢复默认", false, () -> { // 连点两下才恢复
            if (System.currentTimeMillis() - resetArm > 3000) { resetArm = System.currentTimeMillis(); refresh(""); postDelayed(() -> refresh(""), 3100); return; }
            resetArm = 0;
            act.act("modelReset");
        });
        rs.setEnabled(!resetOff); rs.setAlpha(resetOff ? 0.35f : 1);
        acts.addView(rs);
        r.addView(acts);
        r.addView(note("车模在网页版的「调整车模」里调好（车门、车窗、灯、车牌、贴纸都在那里调），点「导出为 GLB」得到一个 .glb 文件，拷到车机上再在这里导入。"));
        r.addView(note("网页版用电脑浏览器打开：" + CarModel.WEB));
        r.addView(note("打不开时（GitHub 在国内有时连不上）：换个网络或过一会儿再试；也可以把网页版下载下来（github.com/Cat-bl/car3d-web 页面上的 Code → Download ZIP，或者找能打开的人要一份），解压后用电脑浏览器打开里面的 index.html，用法一样。"));
        group("外观");
        balls("paint", "车漆", tips[0]);
        r = row(true);
        r.addView(label("车漆质感", null, null));
        r.addView(seg("finish", Settings.FINISHES, Settings.FINISH_NAMES, 5));
        balls("rim", "轮毂", tips[1]);
        balls("caliper", "刹车卡钳", tips[2]);
        group("背景与视角");
        r = row(true);
        r.addView(label("车况页背景", null, null));
        r.addView(seg("bg", Settings.BGS, new String[] { "影棚", "暖调", "冷调", "图片" }, 0));
        if ("image".equals(S.str("bg"))) {
            LinearLayout a2 = hbox();
            a2.addView(actBtn("换一张图片", false, () -> act.act("bgImage")));
            r.addView(a2);
            r.addView(slider("bgDim", "压暗", v -> Math.round(v * 100) + "%"));
        }
        r = row(true);
        LinearLayout sub = new LinearLayout(getContext());
        sub.setGravity(Gravity.TOP);
        sub.addView(label("默认视角", null, "拖动时车况页跟着转过去给你看；挂 D 挡后的视角在下面单独设"), new LinearLayout.LayoutParams(0, -2, 1));
        sub.addView(smallBtn("恢复默认", () -> { for (String k : Settings.VIEW) S.set(k, Settings.NUM.get(k)[3]); }));
        r.addView(sub);
        r.addView(slider("viewAz", "水平角度", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("viewEl", "俯视角", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("viewZoom", "车的大小", v -> Math.round(v * 100) + "%"));
        r.addView(slider("viewFov", "视野", v -> Math.round(v) + "°"));
        LinearLayout a3 = hbox();
        a3.addView(actBtn("把打开设置前的角度设为默认", false, () -> act.act("viewSave")));
        r.addView(a3);
        r = row(true);
        LinearLayout dsub = new LinearLayout(getContext());
        dsub.setGravity(Gravity.TOP);
        dsub.addView(label("挂 D 挡后的视角", null, "挂 D 挡时镜头转到这个角度，默认是车顶视角（车后上方往下看四个轮子）；拖动时车况页跟着转过去给你看"), new LinearLayout.LayoutParams(0, -2, 1));
        dsub.addView(smallBtn("恢复默认", () -> { for (String k : Settings.DVIEW) S.set(k, Settings.NUM.get(k)[3]); }));
        r.addView(dsub);
        r.addView(slider("dAz", "水平角度", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("dEl", "俯视角", v -> Math.round(v * 180 / Math.PI) + "°"));
        r.addView(slider("dZoom", "车的大小", v -> Math.round(v * 100) + "%"));
        LinearLayout a4 = hbox();
        a4.addView(actBtn("把打开设置前的角度设为 D 挡视角", false, () -> act.act("dSave")));
        r.addView(a4);
        group("底部数据");
        r = row(true);
        r.addView(label("显示哪几项", null, "选 1～4 项，按点的顺序排；满 4 项再点会换掉最后一项"));
        r.addView(tags());
        group("显示");
        r = row(true);
        r.addView(label("深浅色", null, null));
        r.addView(seg("theme", Settings.THEMES, new String[] { "跟随车机", "浅色", "深色" }, 0));
        if (fsRow) swRow("fsBar", "全屏时收起状态栏", "只在全屏页生效；每次进全屏先显示，这里开着会自动再收起");
        swRow("labels", "胎压标签", "轮子旁边的胎压");
        swRow("intro", "进场动画", "镜头转进来、迎宾灯");
        group("性能");
        r = row(true);
        r.addView(label("画质", null, S.fx().note));
        r.addView(seg("quality", new ArrayList<>(Settings.QUAL.keySet()), new String[] { "低", "标准", "高", "极致" }, 0));
        r = row(true);
        r.addView(label("帧率", null, "转车、开门这些动的时候每秒画多少帧；静止时不画，和帧率、画质都没关系"));
        r.addView(seg("fps", Settings.FPS, new String[] { "30 帧", "60 帧（流畅）" }, 0));
        swRow("aa", "抗锯齿", "车和字的边缘更平滑，更费显卡；切换后画面重建一次");
        if (dbgRow) {
            group("调试");
            r = row(true);
            r.addView(label("调试面板", null, "没有车机时才有：应用读到的车况、音乐、系统数据、宿主状态都在这里改，选了哪项才动哪项"));
            LinearLayout a5 = hbox();
            a5.addView(actBtn("打开调试面板", false, () -> act.act("debug")));
            r.addView(a5);
        }
    }
    /** 标题右边的小描边键（.set-mini，"恢复默认"） */
    private View smallBtn(String text, Runnable on) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(0xb3eef1f6);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        t.setPadding(px(0.16f), px(0.06f), px(0.16f), px(0.06f));
        GradientDrawable rb = new GradientDrawable(); rb.setCornerRadius(px(0.2f)); rb.setStroke(Math.max(1, Math.round(dens)), 0x24ffffff);
        t.setBackground(rb);
        t.setOnClickListener(v -> on.run());
        return t;
    }
    /** 分段按钮（.seg）；perRow > 0 时一行放这么多个、排几行 */
    private View seg(String k, List<String> vals, String[] names, int perRow) {
        return segOf(vals, names, S.str(k), perRow, v -> { if ("bg".equals(k) && "image".equals(v)) act.act("bgUse"); else S.set(k, (String) v); });
    }
    private void swRow(String k, String name, String small) {
        LinearLayout r = row(false);
        r.addView(label(name, null, small), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(new Switch(getContext(), S.bool(k), () -> S.set(k, !S.bool(k))), new LinearLayout.LayoutParams(px(0.78f), px(0.44f)));
    }
    /** 滑块（.rng）：名字、轨道（已选部分强调色）、当前值 */
    private View slider(String k, String name, Fmt fmt) {
        float[] lim = Settings.NUM.get(k);
        return new Slider(getContext(), name, lim[0], lim[1], lim[2], S.num(k), fmt, v -> S.set(k, v), null);
    }

    // ---------------- 车漆 / 轮毂 / 卡钳的小球 ----------------
    private void balls(String k, String name, String tip) {
        LinearLayout r = row(true);
        Settings.Pick pick = S.pick(k);
        r.addView(label(name, pick.name + ("caliper".equals(k) && !"custom".equals(S.str(k)) && !"model".equals(S.str(k)) ? "色" : ""), tip));
        LinearLayout box = new LinearLayout(getContext());
        box.setPadding(0, px(0.14f), 0, 0);
        for (Settings.Opt o : Settings.OPTS.get(k)) {
            int color;
            boolean ring = false, enabled = true;
            if (o.model) {
                String css = "paint".equals(k) ? S.modelPaintCss() : S.modelPartCss(k);
                color = Color.parseColor(css != null ? css : "paint".equals(k) ? "#9a0c13" : "#9a9da3");
                enabled = css != null; ring = true;
            } else color = o.hex != null ? 0 : Color.parseColor(o.color);
            Ball b = new Ball(getContext(), color, o.id.equals(S.str(k)), o.hex, ring);
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1 : 0.35f);
            b.setOnClickListener(v -> S.set(k, o.id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(0.56f), px(0.56f));
            lp.rightMargin = px(W < H ? 0.12f : 0.16f);
            box.addView(b, lp);
        }
        r.addView(box);
        if ("custom".equals(S.str(k))) hsl(r, k);
    }
    /** 自定义颜色：色相 / 饱和度 / 亮度（滑轨画成当前色相的渐变；白、灰、黑拖色相时把饱和度、亮度拉到看得出颜色的地方） */
    private void hsl(LinearLayout r, String k) {
        String hx = Settings.OPTS.get(k).get(Settings.OPTS.get(k).size() - 1).hex;
        int[] a = Settings.hex2hsl(S.str(hx));
        if (a[1] < 3 && S.hueKeep.containsKey(hx)) a[0] = Math.round(S.hueKeep.get(hx));
        final float[] cur = { a[0], a[1], a[2] };
        int[] hues = new int[7];
        for (int i = 0; i < 7; i++) hues[i] = Color.parseColor(Settings.hsl2hex(i * 60 % 360, 100, 50));
        r.addView(new Slider(getContext(), "色相", 0, 360, 1, cur[0], v -> Math.round(v) + "°", v -> {
            cur[0] = v;
            if (cur[1] < 25) cur[1] = 70;
            if (cur[2] > 85 || cur[2] < 15) cur[2] = 50;
            S.hueKeep.put(hx, v);
            S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2]));
        }, hues));
        r.addView(new Slider(getContext(), "饱和度", 0, 100, 1, cur[1], v -> Math.round(v) + "%", v -> { cur[1] = v; S.hueKeep.put(hx, cur[0]); S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2])); },
            new int[] { Color.parseColor(Settings.hsl2hex(cur[0], 0, 50)), Color.parseColor(Settings.hsl2hex(cur[0], 100, 50)) }));
        r.addView(new Slider(getContext(), "亮度", 0, 100, 1, cur[2], v -> Math.round(v) + "%", v -> { cur[2] = v; S.hueKeep.put(hx, cur[0]); S.set(hx, Settings.hsl2hex(cur[0], cur[1], cur[2])); },
            new int[] { 0xff000000, Color.parseColor(Settings.hsl2hex(cur[0], cur[1], 50)), 0xffffffff }));
    }
    /** 小球：底色 + 高光 + 暗部（同 .ball），选中的放大一点、外圈一道强调色的光；自定义的是彩虹圈；原车颜色外面一圈虚线 */
    private final class Ball extends View {
        final int color0; final boolean on, custom, ring; final String hex; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ball(Context c, int color, boolean on, String hex, boolean ring) { super(c); color0 = color; this.on = on; this.hex = hex; custom = hex != null; this.ring = ring; setScaleX(on ? 1.14f : 1); setScaleY(on ? 1.14f : 1); }
        @Override protected void onDraw(Canvas c) {
            int color = hex != null ? Color.parseColor(S.str(hex)) : color0; // 自定义颜色拖滑块时跟着变
            float w = getWidth(), cx = w / 2, cy = w / 2, r = 0.25f * rem;
            if (on) { p.setColor(acc()); c.drawCircle(cx, cy, r + 0.07f * rem, p); p.setColor(0xff16191f); c.drawCircle(cx, cy, r + 0.035f * rem, p); }
            if (custom) { p.setShader(new android.graphics.SweepGradient(cx, cy, new int[] { 0xffff3b30, 0xffffcc00, 0xff34c759, 0xff00c7be, 0xff007aff, 0xffaf52de, 0xffff3b30 }, null)); c.drawCircle(cx, cy, r, p); p.setShader(null); p.setColor(color); c.drawCircle(cx, cy, r * 0.62f, p); }
            else { p.setColor(color); c.drawCircle(cx, cy, r, p); }
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, 0x1affffff, 0x61000000, Shader.TileMode.CLAMP)); c.drawCircle(cx, cy, r, p);
            p.setShader(new RadialGradient(cx - 0.32f * r, cy - 0.44f * r, r * 0.64f, new int[] { 0xd9ffffff, 0x40ffffff, 0x00ffffff }, new float[] { 0, 0.4f, 1 }, Shader.TileMode.CLAMP)); c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            if (ring) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, 0.02f * rem)); p.setColor(0x80ffffff); p.setPathEffect(new android.graphics.DashPathEffect(new float[] { 0.05f * rem, 0.04f * rem }, 0)); c.drawCircle(cx, cy, r + 0.045f * rem, p); p.setPathEffect(null); p.setStyle(Paint.Style.FILL); }
        }
    }

    // ---------------- 底部数据的标签 ----------------
    private View tags() {
        Flow box = new Flow(getContext());
        List<String> cur = S.stats();
        String g = "";
        for (String[] o : CarStat.STATS) {
            if (!o[2].equals(g)) {
                g = o[2];
                TextView h = new TextView(getContext());
                h.setText(g); h.setTextColor(0x73eef1f6); h.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f)); h.setLetterSpacing(0.1f);
                h.setTag("break");
                box.addView(h);
            }
            final String id = o[0];
            int n = cur.indexOf(id);
            TextView b = new TextView(getContext());
            b.setText(n >= 0 ? (n + 1) + " " + o[1] : o[1]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            b.setTextColor(n >= 0 ? 0xffffffff : 0xb3eef1f6);
            b.setPadding(px(0.16f), px(0.07f), px(0.16f), px(0.07f));
            GradientDrawable d = new GradientDrawable(); d.setCornerRadius(px(0.24f)); d.setColor(n >= 0 ? acc() : 0x14ffffff);
            b.setBackground(d);
            b.setOnClickListener(v -> {
                List<String> l = S.stats();
                int i = l.indexOf(id);
                if (i >= 0) { if (l.size() > 1) l.remove(i); } else { if (l.size() >= 4) l.remove(l.size() - 1); l.add(id); }
                S.set("stats", l);
            });
            box.addView(b);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.1f);
        box.setLayoutParams(lp);
        return box;
    }
    /** 自动换行的一排（标签、分组名；分组名独占一行） */
    private final class Flow extends ViewGroup {
        Flow(Context c) { super(c); }
        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), x = 0, y = 0, lh = 0, gap = px(0.08f);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                boolean br = "break".equals(v.getTag());
                if (br || x + v.getMeasuredWidth() > w) { if (x > 0) { y += lh + gap; x = 0; lh = 0; } }
                x += v.getMeasuredWidth() + gap; lh = Math.max(lh, v.getMeasuredHeight());
                if (br) { y += lh + gap / 2; x = 0; lh = 0; }
            }
            setMeasuredDimension(w, y + lh);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int w = r - l, x = 0, y = 0, lh = 0, gap = px(0.08f);
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                boolean br = "break".equals(v.getTag());
                if (br || x + v.getMeasuredWidth() > w) { if (x > 0) { y += lh + gap; x = 0; lh = 0; } }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap; lh = Math.max(lh, v.getMeasuredHeight());
                if (br) { y += lh + gap / 2; x = 0; lh = 0; }
            }
        }
    }
}
