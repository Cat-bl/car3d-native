package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
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

import java.util.List;

/**
 * 面板的外壳（照网页版 style.css 的 .set / .set-card）：深色卡片、顶上一道强调色细线、标题和关闭键、能滚动的内容；
 * 横屏靠右占 46%（从右边转着滑进来），竖屏在下面占 64%（从下面翻上来），内容一行行浮上来。
 * modal 的点面板外面关掉（设置）；不是的面板外面不挡手势（全部车况：照样能拖车，同 .tune）
 */
@SuppressLint("ViewConstructor")
abstract class Sheet extends FrameLayout {
    protected final Settings S;
    private final View shade;
    protected final LinearLayout card, body;
    protected final ScrollView scroll;
    private final TextView title;
    private final View close;
    protected final float dens;
    protected float rem = 10;
    protected int W = 1, H = 1;
    protected boolean open;
    private final boolean modal;
    private final PathInterpolator ease = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    static final int TXT = 0xffeef1f6, SUB = 0x73eef1f6;

    Sheet(Context c, Settings s, String name, boolean modal) {
        super(c);
        S = s;
        this.modal = modal;
        dens = c.getResources().getDisplayMetrics().density;
        shade = new View(c);
        shade.setAlpha(0);
        if (modal) shade.setOnClickListener(v -> show(false));
        addView(shade, new LayoutParams(-1, -1));
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setAlpha(0);
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(c);
        title.setText(name);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        title.setTextColor(TXT);
        title.setLetterSpacing(0.04f);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        close = new IconBtn(c, "M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4z");
        close.setOnClickListener(v -> show(false));
        head.addView(close);
        card.addView(head);
        scroll = new ScrollView(c);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(card);
        setVisibility(INVISIBLE);
    }
    /** 填内容（打开时、尺寸变了时） */
    protected abstract void rows();
    /** 关上了（点了关闭键、点了面板外面、外面叫关的都算） */
    protected abstract void closed();
    boolean isOpen() { return open; }

    void show(boolean on) {
        if (on == open) return;
        open = on;
        card.animate().cancel(); shade.animate().cancel();
        if (on) {
            setVisibility(VISIBLE);
            rows();
            scroll.scrollTo(0, 0);
            boolean narrow = W < H;
            card.setPivotX(narrow ? card.getWidth() / 2f : card.getWidth()); card.setPivotY(narrow ? card.getHeight() : card.getHeight() / 2f);
            if (narrow) { card.setTranslationY(H * 0.3f); card.setRotationX(18); } else { card.setTranslationX(W * 0.3f); card.setRotationY(-28); }
            card.setCameraDistance(14 * rem * 8);
            card.animate().alpha(1).translationX(0).translationY(0).rotationY(0).rotationX(0).setDuration(550).setInterpolator(ease).start();
            if (modal) shade.animate().alpha(1).setDuration(400).start();
            for (int i = 0; i < body.getChildCount(); i++) { // 一行行依次浮上来
                View v = body.getChildAt(i);
                v.setAlpha(0); v.setTranslationX(0.6f * rem);
                v.animate().alpha(1).translationX(0).setStartDelay(Math.round(Math.min(i * 35, 600))).setDuration(500).setInterpolator(ease).start();
            }
        } else {
            boolean narrow = W < H;
            card.animate().alpha(0).translationX(narrow ? 0 : W * 0.3f).translationY(narrow ? H * 0.3f : 0).rotationY(narrow ? 0 : -28).rotationX(narrow ? 18 : 0)
                .setDuration(450).setInterpolator(ease).withEndAction(() -> { if (!open) setVisibility(INVISIBLE); }).start();
            shade.animate().alpha(0).setDuration(400).start();
            closed();
        }
    }
    /** 不是 modal 的：面板外面的手势交给下面（车况页） */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (!modal && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && !hit(card, e.getX(), e.getY())) return false;
        return super.dispatchTouchEvent(e);
    }
    private static boolean hit(View v, float x, float y) { return x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        post(this::relayout);
    }
    protected int px(float r) { return Math.round(r * rem); }
    private void relayout() {
        boolean narrow = W < H;
        LayoutParams lp = narrow ? new LayoutParams(-1, Math.round(H * 0.64f), Gravity.BOTTOM) : new LayoutParams(Math.round(W * 0.46f), -1, Gravity.END);
        lp.setMargins(px(narrow ? 0.16f : 0.2f), px(0.2f), px(narrow ? 0.16f : 0.2f), px(narrow ? 0.16f : 0.2f));
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] { 0xf5262a34, 0xf70f1116 });
        bg.setCornerRadius(px(0.32f));
        bg.setStroke(Math.max(1, Math.round(dens)), 0x14ffffff);
        card.setBackground(new AccentBg(bg));
        card.setClipToOutline(true);
        card.setElevation(px(0.3f));
        shade.setBackground(new GradientDrawable(narrow ? GradientDrawable.Orientation.TOP_BOTTOM : GradientDrawable.Orientation.LEFT_RIGHT, new int[] { 0x0005070a, 0x0005070a, 0x8c05070a }));
        View head = card.getChildAt(0);
        head.setPadding(px(0.32f), px(0.26f), px(0.2f), px(0.04f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.34f));
        close.setLayoutParams(new LinearLayout.LayoutParams(px(0.56f), px(0.56f)));
        body.setPadding(px(0.32f), 0, px(0.32f), px(0.3f));
        if (open) rows();
    }
    /** 卡片背景 + 顶上一道强调色细线（同 .set-card::before） */
    private final class AccentBg extends android.graphics.drawable.Drawable {
        final GradientDrawable base;
        final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        AccentBg(GradientDrawable b) { base = b; }
        @Override public void draw(Canvas c) {
            base.setBounds(getBounds());
            base.draw(c);
            float l = getBounds().left + px(0.3f), r = getBounds().right - px(0.3f);
            lp.setShader(new LinearGradient(l, 0, r, 0, new int[] { 0x00000000, acc(), 0x00000000 }, null, Shader.TileMode.CLAMP));
            c.drawRect(l, getBounds().top, r, getBounds().top + Math.max(1, 0.03f * rem), lp);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    /** 强调色（跟着车漆，同 --acc） */
    protected int acc() { String a = S.pick("paint").acc; return Color.parseColor(a != null ? a : "#ff4b55"); }
    protected void accentChanged() { card.getBackground().invalidateSelf(); }

    // ---------------- 公用的几种行 ----------------
    protected void group(String t) {
        TextView g = new TextView(getContext());
        g.setText(t);
        g.setTextColor(0x66eef1f6);
        g.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.2f));
        g.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.22f); lp.bottomMargin = px(0.02f);
        body.addView(g, lp);
    }
    protected TextView note(String s) {
        TextView t = new TextView(getContext());
        t.setText(s); t.setTextColor(SUB); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.19f)); t.setLineSpacing(0, 1.15f);
        t.setPadding(0, px(0.04f), 0, 0);
        return t;
    }
    /** 一行底下的细线（同 .set-row 的 border-bottom） */
    protected final class BottomLine extends android.graphics.drawable.Drawable {
        final Paint p = new Paint();
        final int color;
        BottomLine(int color) { this.color = color; }
        @Override public void draw(Canvas c) { p.setColor(color); c.drawRect(getBounds().left, getBounds().bottom - Math.max(1, dens), getBounds().right, getBounds().bottom, p); }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    protected static void repaint(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) repaint(((ViewGroup) v).getChildAt(i));
    }

    // ---------------- 两个面板共用的控件（设置、调试面板） ----------------
    /** 滑块拖完了（设置面板拿它重画跟着颜色变的字） */
    protected void afterDrag() {}
    /** 手指正按着某个滑块（这时别重画面板，不然滑块被换掉、拖不动） */
    protected boolean dragging;
    protected LinearLayout row(boolean col) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(col ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        r.setGravity(col ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(0.16f), 0, px(0.16f));
        r.setBackground(new BottomLine(0x0fffffff));
        body.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }
    protected LinearLayout hbox() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, px(0.12f), 0, 0);
        return l;
    }
    /** 名字（.set-lb）+ 后面强调色的当前值（em）+ 下面的小字（small） */
    protected View label(String name, String em, String small) {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.BOTTOM);
        TextView n = new TextView(getContext());
        n.setText(name); n.setTextColor(TXT); n.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); n.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.26f));
        top.addView(n);
        if (em != null && !em.isEmpty()) {
            TextView e = new TextView(getContext());
            e.setText(em); e.setTextColor(acc()); e.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = px(0.14f);
            top.addView(e, lp);
        }
        l.addView(top);
        if (small != null && !small.isEmpty()) l.addView(note(small));
        return l;
    }
    protected View actBtn(String text, boolean pri, Runnable on) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
        b.setTextColor(pri ? 0xffffffff : TXT);
        b.setPadding(px(0.2f), px(0.1f), px(0.2f), px(0.1f));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(px(0.26f));
        d.setColor(pri ? acc() : 0x1affffff);
        b.setBackground(d);
        b.setOnClickListener(v -> on.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(0.12f);
        b.setLayoutParams(lp);
        return b;
    }
    protected interface Pick { void on(Object v); }
    /** 分段按钮（.seg）：第 i 个值显示 names[i]，cur 是现在选着的（数字 1 和 1.0 算一样）；perRow > 0 时一行放这么多个、排几行 */
    protected View segOf(List<?> vals, String[] names, Object cur, int perRow, Pick pick) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(0.05f), px(0.05f), px(0.05f), px(0.05f));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(px(0.3f)); bg.setColor(0x0fffffff);
        box.setBackground(bg);
        int n = perRow > 0 ? perRow : vals.size();
        LinearLayout line = null;
        for (int i = 0; i < vals.size(); i++) {
            if (i % n == 0) { line = new LinearLayout(getContext()); box.addView(line, new LinearLayout.LayoutParams(-1, -2)); }
            final Object v = vals.get(i);
            boolean on = DebugHost.same(v, cur);
            TextView b = new TextView(getContext());
            b.setText(names[i]); b.setGravity(Gravity.CENTER); b.setSingleLine(true);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            b.setTextColor(on ? 0xffffffff : 0x99eef1f6);
            b.setPadding(0, px(0.1f), 0, px(0.1f));
            if (on) { GradientDrawable d = new GradientDrawable(); d.setCornerRadius(px(0.26f)); d.setColor(0x24ffffff); b.setBackground(d); }
            b.setOnClickListener(x -> pick.on(v));
            line.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (perRow > 0 && line != null) for (int i = line.getChildCount(); i < n; i++) line.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(0.14f);
        box.setLayoutParams(lp);
        return box;
    }
    /** 开关（.sw） */
    protected final class Switch extends View {
        final boolean on; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Switch(Context c, boolean on, Runnable click) { super(c); this.on = on; setOnClickListener(v -> click.run()); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(on ? acc() : 0x24ffffff);
            c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            float d = 0.34f * rem, x = 0.05f * rem + (on ? 0.34f * rem : 0);
            p.setColor(0x66000000); c.drawCircle(x + d / 2, h / 2 + 0.02f * rem, d / 2, p);
            p.setColor(0xffffffff); c.drawCircle(x + d / 2, h / 2, d / 2, p);
        }
    }
    protected interface Fmt { String of(float v); }
    protected interface OnVal { void on(float v); }
    protected final class Slider extends LinearLayout {
        final TextView val; final Track track; final Fmt fmt;
        Slider(Context c, String name, float min, float max, float step, float v, Fmt fmt, OnVal on, int[] grad) {
            super(c);
            this.fmt = fmt;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, px(0.1f), 0, 0);
            TextView n = new TextView(c);
            n.setText(name); n.setTextColor(0xb3eef1f6); n.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            addView(n, new LayoutParams(px(0.9f), -2));
            track = new Track(c, min, max, step, v, on, grad);
            LayoutParams tp = new LayoutParams(0, px(0.44f), 1);
            tp.leftMargin = px(0.1f); tp.rightMargin = px(0.1f);
            addView(track, tp);
            val = new TextView(c);
            val.setText(fmt.of(v)); val.setTextColor(acc()); val.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); val.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.22f));
            val.setGravity(Gravity.END);
            addView(val, new LayoutParams(px(1f), -2));
            track.label = val; track.fmt = fmt;
        }
    }
    /** 滑块的轨道：竖着划交给面板滚动，横着拖才改值（从按下时的值跟着手指挪，不跳），点一下不改 */
    protected final class Track extends View {
        final float min, max, step; float v; final OnVal on; final int[] grad;
        TextView label; Fmt fmt;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float x0, y0, v0; int mode;
        Track(Context c, float min, float max, float step, float v, OnVal on, int[] grad) { super(c); this.min = min; this.max = max; this.step = step; this.v = v; this.on = on; this.grad = grad; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), th = 0.06f * rem, k = (v - min) / (max - min), r = 0.17f * rem, x = r + (w - 2 * r) * k;
            if (grad != null) { p.setShader(new LinearGradient(r, 0, w - r, 0, grad, null, Shader.TileMode.CLAMP)); c.drawRoundRect(r, h / 2 - th, w - r, h / 2 + th, th, th, p); p.setShader(null); }
            else {
                p.setColor(0x24ffffff); c.drawRoundRect(r, h / 2 - th / 2, w - r, h / 2 + th / 2, th / 2, th / 2, p);
                p.setColor(acc()); c.drawRoundRect(r, h / 2 - th / 2, x, h / 2 + th / 2, th / 2, th / 2, p);
            }
            p.setColor(acc()); c.drawCircle(x, h / 2, r + 0.04f * rem, p);
            p.setColor(0xffffffff); c.drawCircle(x, h / 2, r, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: x0 = e.getRawX(); y0 = e.getRawY(); v0 = v; mode = 0; dragging = true; getParent().requestDisallowInterceptTouchEvent(true); return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                    if (mode == 0 && (Math.abs(dx) > 8 * dens || Math.abs(dy) > 8 * dens)) {
                        mode = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                        if (mode == 2) { dragging = false; getParent().requestDisallowInterceptTouchEvent(false); scroll.scrollBy(0, Math.round(-dy)); return false; }
                    }
                    if (mode == 1) {
                        float w = Math.max(1, getWidth() - 0.34f * rem), nv = v0 + dx / w * (max - min);
                        nv = Math.max(min, Math.min(max, Math.round((nv - min) / step) * step + min));
                        if (nv != v) { v = nv; if (label != null) label.setText(fmt.of(v)); invalidate(); on.on(v); }
                    }
                    return true;
                }
                default: {
                    getParent().requestDisallowInterceptTouchEvent(false);
                    dragging = false;
                    if (mode == 1 && v != v0) post(Sheet.this::afterDrag); // 拖完：强调色这类跟着颜色变的字重画一遍
                    return true;
                }
            }
        }
    }

    /** 自动换行的一排（标签、分组名；分组名独占一行） */
    protected final class Flow extends ViewGroup {
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
    /** 圆形图标键（关闭）：路径同网页版的 svg */
    static final class IconBtn extends View {
        final String d; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); int bg = 0x14ffffff, fg = 0xffeef1f6;
        IconBtn(Context c, String d) { super(c); this.d = d; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(bg); c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, p);
            float pad = w * 0.25f, sz = w - pad * 2;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(sz / 24, sz / 24); m.postTranslate(pad, pad);
            android.graphics.Path q = new android.graphics.Path(); SvgPath.of(d).transform(m, q);
            p.setColor(fg); c.drawPath(q, p);
        }
    }
}
