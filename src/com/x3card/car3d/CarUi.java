package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.x3card.car3d.ach.AchToast;

import java.util.List;

/**
 * 叠在 3D 画布上面的一层（照网页版车况页的 DOM）：字和图标（Hud）、胎压标签和车顶空调键（Tags）、右上角的齿轮、全屏键和奖杯键（成就墙）、
 * 解锁成就时从右边滑进来的提示（AchToast）、底部提示条、设置面板（Panel）。
 * CarPage 在 GL 线程里交出来的（Out）都切回主线程再改界面
 */
@SuppressLint("ViewConstructor")
final class CarUi extends FrameLayout implements CarPage.Out {
    interface Actions {
        void statusTap(); void statusPick(int i); void statusClose(); void allStatus();
        void roofTap(); void acSet(boolean on); void tagMax(float cssY);
        void panel(boolean open); void act(String name); void fullscreen(); void debug(); void trophies();
    }
    /** 深浅色变了：d 是要到的，k 是现在过渡到哪（成就墙跟着变） */
    interface Theme { void on(boolean d, float k); }
    private static final String GEAR = "M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L15 3.3h-4l-.4 2.6a7.4 7.4 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1l.3 2.6h4l.4-2.6a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5zM12 15.4a3.4 3.4 0 1 1 0-6.8 3.4 3.4 0 0 1 0 6.8z";
    /** 奖杯（实心）：杯身、两边的把手、杯脚、底座 */
    private static final String CUP = "M6 3h12v4.6a6 6 0 0 1-12 0V3zM10.6 13.4h2.8v3.4h-2.8zM7.5 17.4h9a1 1 0 0 1 1 1v1.8h-11v-1.8a1 1 0 0 1 1-1zM6 4.9H3.6a.6.6 0 0 0-.6.6v1.3a4.2 4.2 0 0 0 3.5 4.1V9.2A2.5 2.5 0 0 1 4.6 6.8v-.3H6zM18 4.9h2.4a.6.6 0 0 1 .6.6v1.3a4.2 4.2 0 0 1-3.5 4.1V9.2a2.5 2.5 0 0 0 1.9-2.4v-.3H18z";
    private static final String FS_FULL = "M4 9.5V4h5.5M14.5 4H20v5.5M20 14.5V20h-5.5M9.5 20H4v-5.5", FS_SMALL = "M9.5 4v5.5H4M20 9.5h-5.5V4M14.5 20v-5.5H20M4 14.5h5.5V20";

    final Hud hud;
    final Tags tags;
    final Panel panel;
    private final Busy busy;
    private final Settings S;
    private final Btn gear, fs, cup;
    final AchToast achToast;
    Theme onTheme;
    private boolean darkTo;
    private final TextView toast;
    private final Actions act;
    private final float dens;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float rem = 10;
    private int W = 1, H = 1;
    private String fsMode = "";
    private boolean panelOn;

    CarUi(Context c, Settings s, Actions a) {
        super(c);
        act = a;
        S = s;
        dens = c.getResources().getDisplayMetrics().density;
        hud = new Hud(c, new Hud.Tap() {
            @Override public void status() { act.statusTap(); }
            @Override public void pick(int i) { act.statusPick(i); }
            @Override public void all() { act.allStatus(); }
            @Override public void close() { act.statusClose(); }
            @Override public void debug() { act.debug(); }
        });
        hud.onMsgTop = () -> act.tagMax(hud.msgTop / dens);
        tags = new Tags(c, hud, act::roofTap);
        addView(hud, new LayoutParams(-1, -1));
        addView(tags, new LayoutParams(-1, -1));
        gear = new Btn(c, GEAR, true);
        gear.setOnClickListener(v -> panel(true));
        fs = new Btn(c, FS_FULL, false);
        fs.setOnClickListener(v -> act.fullscreen());
        fs.setVisibility(GONE);
        addView(fs);
        cup = new Btn(c, CUP, true);
        cup.setOnClickListener(v -> act.trophies());
        addView(cup);
        addView(gear);
        achToast = new AchToast(c, act::trophies);
        addView(achToast, new LayoutParams(-1, -1));
        toast = new TextView(c);
        toast.setGravity(Gravity.CENTER);
        toast.setTextColor(0xffeef1f6);
        toast.setAlpha(0);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xf016191f);
        toast.setBackground(tb);
        addView(toast);
        panel = new Panel(c, s, new Panel.Act() {
            @Override public void act(String name) { act.act(name); }
            @Override public void closed() { panelOn = false; buttons(); act.panel(false); }
        });
        addView(panel, new LayoutParams(-1, -1));
        busy = new Busy(c);
        addView(busy, new LayoutParams(-1, -1));
        colors();
    }

    // ================= 右上角的键 =================
    void panel(boolean on) {
        if (on == panelOn) { if (!on) panel.show(false); return; }
        panelOn = on;
        panel.show(on);
        buttons();
        if (on) act.panel(true);
    }
    boolean panelOpen() { return panelOn; }
    private boolean infoOn;
    /** 全部车况、调试面板开着：齿轮、全屏键藏起来（会盖住面板的关闭键） */
    void infoOpen(boolean on) { infoOn = on; buttons(); }
    /** 连不上宿主、没授权时（上面盖着说明）：字和标签藏起来，齿轮照常能用 */
    void setBlocked(boolean b) {
        hud.animate().alpha(b ? 0 : 1).setDuration(300).start();
        tags.animate().alpha(b ? 0 : 1).setDuration(300).start();
    }
    /** 全屏键："full" 全屏、"small" 恢复小屏、"" 没有（同 fullscreen.js：在全屏页里是恢复小屏） */
    void setFsMode(String m) {
        fsMode = m;
        fs.d = "small".equals(m) ? FS_SMALL : FS_FULL;
        fs.setVisibility(m.isEmpty() ? GONE : VISIBLE);
        hud.setFs(!m.isEmpty());
        panel.setFs("small".equals(m));
        fs.invalidate();
        post(this::relayout); // 奖杯键的位置看有没有全屏键
    }
    /** 待庆祝的成就几枚（奖杯键上的红点） */
    void setBadge(int n) { cup.badge = n; cup.invalidate(); }

    /** 面板开着时齿轮转 90° 淡出、全屏键和奖杯键跟着藏；解锁的提示先排着 */
    private void buttons() {
        boolean hide = panelOn || infoOn;
        gear.animate().rotation(panelOn ? 90 : 0).alpha(hide ? 0 : 1).setDuration(400).start();
        fs.animate().alpha(hide ? 0 : 1).setDuration(300).start();
        cup.animate().alpha(hide ? 0 : 1).setDuration(300).start();
        gear.setClickable(!hide);
        fs.setClickable(!hide);
        cup.setClickable(!hide);
        achToast.hold(hide);
        hud.setCovered(hide); // 竖屏开着面板：车挤到上面那一小块，车况图标、车顶空调键会压在车上
        tags.setCovered(hide);
    }
    private void colors() {
        float k = hud.darkK();
        int bg = mix(0x1214171c, 0x1affffff, k), fg = mix(0xff14171c, 0xffffffff, k);
        gear.colors(bg, fg);
        fs.colors(bg, fg);
        cup.colors(bg, fg);
        cup.red = hud.bad();
        achToast.setDark(k);
        if (onTheme != null) onTheme.on(darkTo, k);
    }
    private static int mix(int a, int b, float k) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16) | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = Math.max(1, w); H = Math.max(1, h);
        rem = H / 10.52f;
        post(this::relayout);
    }
    private int px(float r) { return Math.round(r * rem); }
    private void relayout() {
        boolean p = W < H;
        LayoutParams g = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        g.topMargin = px(p ? 0.43f : 0.45f); g.rightMargin = px(p ? 0.22f : 0.26f);
        gear.setLayoutParams(g);
        LayoutParams f = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        f.topMargin = px(p ? 1.11f : 0.45f); f.rightMargin = px(p ? 0.22f : 0.96f);
        fs.setLayoutParams(f);
        // 奖杯键：横屏在全屏键左边、竖屏在全屏键下面（没有全屏键时占它的位置）
        boolean hasFs = fs.getVisibility() == VISIBLE;
        LayoutParams cp = new LayoutParams(px(0.56f), px(0.56f), Gravity.TOP | Gravity.END);
        cp.topMargin = px(p ? (hasFs ? 1.79f : 1.11f) : 0.45f); cp.rightMargin = px(p ? 0.22f : hasFs ? 1.66f : 0.96f);
        cup.setLayoutParams(cp);
        achToast.place(p ? (hasFs ? 2.5f : 1.82f) : 1.16f, p ? 0.22f : 0.26f);
        LayoutParams t = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        t.bottomMargin = px(p ? 2.9f : 1.3f);
        toast.setLayoutParams(t);
        toast.setMaxWidth(Math.min(px(p ? 4.6f : 7f), Math.round(W * 0.9f)));
        toast.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(0.23f));
        toast.setLineSpacing(0, 1.5f / 1.17f);
        toast.setPadding(px(0.3f), px(0.14f), px(0.3f), px(0.14f));
        ((GradientDrawable) toast.getBackground()).setCornerRadius(px(0.3f));
        toast.setElevation(px(0.1f));
    }

    // ================= 导入车模的框 =================
    /** 开（title）/ 关（null）；进度条的颜色同设置面板的强调色（跟着车漆） */
    void busy(String title) {
        if (title == null) { busy.hide(); return; }
        String a = S.pick("paint").acc;
        int acc = 0xffff4b55;
        try { if (a != null) acc = android.graphics.Color.parseColor(a); } catch (Exception ignored) { }
        busy.show(title, acc);
    }
    void step(String step, float f) { busy.progress(step, f); }

    // ================= 提示条（同 D3.toast：0.3 秒淡入、从下面浮上来） =================
    private Runnable toastOff;
    private void showToast(String msg, int ms) {
        if (toastOff != null) removeCallbacks(toastOff);
        toast.animate().cancel();
        if (msg == null) { toast.animate().alpha(0).translationY(px(0.2f)).setDuration(300).start(); return; }
        toast.setText(msg);
        if (toast.getAlpha() < 0.01f) toast.setTranslationY(px(0.2f));
        toast.animate().alpha(1).translationY(0).setDuration(300).start();
        toastOff = () -> toast.animate().alpha(0).translationY(px(0.2f)).setDuration(300).start();
        postDelayed(toastOff, ms > 0 ? ms : 2600);
    }

    // ================= CarPage.Out（GL 线程里调，切回主线程） =================
    @Override public void loading(String text) { main.post(() -> hud.setLoading(text)); }
    @Override public void top(String g, boolean on, String s, String pw, boolean lv) { main.post(() -> hud.setTop(g, on, s, pw, lv)); }
    @Override public void stats(List<String[]> items) { main.post(() -> hud.setStats(items)); }
    @Override public void status(List<CarPage.St> list, boolean open) { main.post(() -> hud.setStatus(list, open)); }
    @Override public void tells(List<CarStat.Item> l, List<CarStat.Item> r, boolean p) { main.post(() -> hud.setTells(l, r)); }
    @Override public void sys(List<CarStat.Sys> s) { main.post(() -> hud.setSys(s)); }
    @Override public void tyres(float[] xy, String[] cls, String[] text, String[] temp) { main.post(() -> tags.setTyres(xy, cls, text, temp)); }
    @Override public void roof(boolean has, boolean show, float x, float y, boolean on, boolean wait) { main.post(() -> tags.setRoof(has, show, x, y, on, wait)); }
    @Override public void dark(boolean d) { main.post(() -> { darkTo = d; hud.setDark(d, true, () -> { tags.invalidate(); colors(); }); }); }
    @Override public void hint(boolean show) { main.post(() -> hud.setHint(show)); }
    @Override public void toast(String s, int ms) { main.post(() -> showToast(s, ms)); }
    @Override public void modelUI(String name, String credit, boolean resetOff, String[] tips) { main.post(() -> panel.setModel(name, credit, resetOff, tips)); }
    @Override public void acSet(boolean on) { main.post(() -> act.acSet(on)); }
    @Override public void progress(String step, float f) { main.post(() -> busy.progress(step, f)); }
    @Override public void imported(String msg) { main.post(() -> { busy.hide(); showToast(msg, msg.length() > 40 ? 8000 : 4000); }); }
    /** 一打开就用对深浅色（不渐变） */
    void darkNow(boolean d) { darkTo = d; hud.setDark(d, false, null); colors(); }
    boolean darkTo() { return darkTo; }

    /** 圆形图标键（齿轮、奖杯实心，全屏键描边）；badge > 0 时右上角挂一个红点写个数 */
    private final class Btn extends View {
        String d; final boolean fill; int bg, fg, badge, red = 0xffdc2f2f;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Btn(Context c, String d, boolean fill) { super(c); this.d = d; this.fill = fill; }
        void colors(int b, int f) { bg = b; fg = f; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(isPressed() ? mix(bg, 0x38ffffff, 0.5f) : bg);
            c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, p);
            float pad = (fill ? 0.12f : 0.13f) * rem, sz = w - pad * 2;
            Matrix m = new Matrix();
            m.setScale(sz / 24, sz / 24); m.postTranslate(pad, pad);
            Path q = new Path(); SvgPath.of(d).transform(m, q);
            p.setColor(fg);
            if (fill) c.drawPath(q, p);
            else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f * sz / 24); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); c.drawPath(q, p); }
            if (badge > 0) {
                String t = badge > 99 ? "99+" : Integer.toString(badge);
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false));
                p.setTextSize(0.15f * rem);
                p.setTextAlign(Paint.Align.CENTER);
                float bh = 0.22f * rem, bw = Math.max(bh, p.measureText(t) + 0.1f * rem), bx = w - bw / 2, by = bh / 2;
                p.setColor(red);
                c.drawRoundRect(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2, bh / 2, bh / 2, p);
                p.setColor(0xffffffff);
                Paint.FontMetrics fm = p.getFontMetrics();
                c.drawText(t, bx, by - (fm.ascent + fm.descent) / 2, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }
}
