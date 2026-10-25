package com.x3card.car3d.ach;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 整页的成就墙：盖在车况页上面（勋章墙 Wall 画在自己的画布 AchView 上，字和键在 Overlay），背景、深浅色、强调色同车况页。
 * 排版见 Hall：总览（进度环、各等级几枚、最近拿到的那枚）+ 一个分类一行的长廊。
 * 打开时建画布，总览和一行行勋章浮上来；关上时沉下去，放完拿掉画布（GL 上下文跟着释放，平时不占显存）。
 * 待庆祝的（Trophies 存着的）打开时一组组庆祝，庆祝完一组才从待庆祝里拿掉；开着时新解锁的接着排队
 */
@SuppressLint("ViewConstructor")
public final class AchPage extends FrameLayout implements Overlay.Host {
    private Trophies tro;
    private AchView view;
    private Wall wall;
    private Overlay ov;
    private Runnable onClosed;
    /** 墙上的勋章按行排成一串：第 r 行是 shown[start[r]] 起的 counts[r] 枚 */
    private List<Ach> shown = new ArrayList<>();
    private int[] start = { 0 }, counts = new int[0];
    private Ach latest;
    private final ArrayDeque<List<Ach>> queue = new ArrayDeque<>();
    private List<Ach> current;
    private boolean open, closing;
    // 外观（打开前、打开时都可以改）
    private boolean dark;
    private float darkK;
    private int acc = 0xffff4b55, bgKind;
    private float bgDim;
    private Callable<Bitmap> bgImage;

    public AchPage(Context c) { super(c); }

    public boolean isOpen() { return open; }

    // ================= 外观 =================
    /** 深浅色：d 是要到的，k 是现在过渡到哪（0 浅、1 深） */
    public void setDark(boolean d, float k) {
        dark = d;
        darkK = k;
        if (ov != null) { ov.darkK = k; ov.invalidate(); }
        if (wall != null) wall.setDay(!d);
    }
    public void setAccent(int a) { acc = a; if (ov != null) { ov.acc = a; ov.invalidate(); } }
    /** 背景同车况页：0 影棚、1 暖调、2 冷调、3 图片（img 在后台线程里读那张图）；压暗 */
    public void setBg(int kind, float dim, Callable<Bitmap> img) {
        bgKind = kind;
        bgDim = dim;
        bgImage = img;
        if (wall != null) loadBg();
    }
    private void loadBg() {
        final Wall w = wall;
        final int k = bgKind;
        final float d = bgDim;
        final Callable<Bitmap> src = k == 3 ? bgImage : null;
        if (src == null) { w.setBg(k, d, null); return; }
        new Thread(() -> {
            Bitmap b = null;
            try { b = src.call(); } catch (Exception ignored) { }
            final Bitmap bm = b;
            post(() -> { if (wall == w) w.setBg(k, d, bm); else if (bm != null) bm.recycle(); });
        }, "ach-bg").start();
    }

    // ================= 开、关 =================
    /** 打开：aa 抗锯齿、fps 帧率（同车况页的设置）；closed 是关上（动画放完、画布拿掉）以后 */
    public void open(Trophies t, boolean aa, int fps, Runnable closed) {
        if (open) return;
        open = true;
        closing = false;
        tro = t;
        t.opened();
        onClosed = closed;
        wall = new Wall();
        wall.num = Overlay.outfit(getContext(), 500);
        view = new AchView(getContext(), wall, aa);
        wall.view = view;
        wall.pacer.fps(fps);
        wall.setDay(!dark);
        wall.onClosed = this::removeAll;
        loadBg();
        ov = new Overlay(getContext(), this);
        ov.stats = t.stats();
        ov.darkK = darkK;
        ov.acc = acc;
        ov.setAlpha(0);
        addView(view, new LayoutParams(-1, -1));
        addView(ov, new LayoutParams(-1, -1));
        ov.addOnLayoutChangeListener((v, l, tp, r, b, ol, ot, or, ob) -> {
            if (wall == null) return;
            wall.size(v.getWidth(), v.getHeight());
            if (r - l != or - ol || b - tp != ob - ot) post(this::refresh);
        });
        ov.animate().alpha(1).setStartDelay(150).setDuration(400).start();
        refresh();
        queue.clear();
        queue.addAll(t.pendingGroups());
        current = null;
        if (!queue.isEmpty()) postDelayed(celebStart, 700); // 勋章浮上来以后再庆祝
    }

    private final Runnable celebStart = this::nextCeleb;

    /** 关上（放动画）：庆祝着的那组留在待庆祝里，下次再放 */
    public void close() {
        if (!open || closing) return;
        closing = true;
        removeCallbacks(celebStart);
        ov.animate().cancel();
        ov.animate().alpha(0).setStartDelay(0).setDuration(250).start();
        wall.close();
    }

    /** 马上拿掉（应用藏起来了）：不放动画 */
    public void closeNow() {
        if (!open) return;
        removeAll();
    }

    private void removeAll() {
        if (!open) return;
        open = false;
        closing = false;
        removeCallbacks(celebStart);
        queue.clear();
        current = null;
        if (wall != null) wall.quit();
        removeAllViews(); // 画布离开窗口：GL 线程退出、上下文和里面的东西一起释放
        wall = null;
        view = null;
        ov = null;
        Runnable r = onClosed;
        onClosed = null;
        if (r != null) r.run();
    }

    /** 返回键：庆祝中看下一枚，统计页、看一枚时关上它们，不然关成就墙；没开着返回假 */
    public boolean back() {
        if (!open) return false;
        if (closing) return true;
        if (ov.celebShown) nextCeleb();
        else if (ov.statsShown) closeStats();
        else if (ov.focusShown) closeFocus();
        else close();
        return true;
    }

    // ================= 解锁 =================
    /** 开着时新解锁了一组：排进庆祝 */
    public void unlocked(List<Ach> g) {
        if (!open || closing) return;
        refresh();
        queue.add(g);
        kick();
    }

    /** 有排着的、眼下没在庆祝、没开着统计页和看一枚：过一会儿开始（看着的时候不打断，关上了再庆祝） */
    private void kick() {
        if (queue.isEmpty() || ov.celebShown || current != null || ov.statsShown || ov.focusShown) return;
        removeCallbacks(celebStart);
        postDelayed(celebStart, 300);
    }

    /** 记录变了（定时存档、清空）：重画 */
    public void changed() { if (open && !closing) refresh(); }

    private void nextCeleb() {
        if (!open) return;
        if (current == null && (ov.statsShown || ov.focusShown)) return; // 等关上了（kick）
        if (current != null) { tro.celebrated(current); current = null; }
        List<Ach> g = queue.poll();
        if (g == null || g.isEmpty() || closing) { ov.celebShown = false; ov.celeb = null; ov.celebGroup = null; wall.celebDone(); ov.invalidate(); return; }
        current = g;
        Ach a = g.get(0);
        ov.celeb = a;
        ov.celebGroup = g.size() > 1 ? g : null;
        int left = 0;
        for (List<Ach> q : queue) left += q.size();
        ov.celebLeft = left;
        ov.celebShown = true;
        ov.invalidate();
        Wall.Item[] more = new Wall.Item[Math.min(4, g.size() - 1)];
        for (int i = 0; i < more.length; i++) more[i] = item(g.get(i + 1));
        wall.celebrate(item(a), more);
    }

    // ================= 显示 =================
    /** 墙上的一枚：没解锁的只给"？？？"和问号图案（名字、条件、等级都不交出去） */
    private Wall.Item item(Ach a) {
        Wall.Item m = new Wall.Item();
        m.id = a.id; m.cat = a.cat;
        Long t = tro.stats().unlocked.get(a.id);
        m.unlocked = t != null;
        if (t != null) {
            m.icon = a.icon; m.name = a.name; m.tier = a.tier;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            m.sub = Ach.TIERS[a.tier] + " · " + (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日";
        } else {
            m.icon = Wall.HIDDEN; m.name = "？？？"; m.tier = Ach.SILVER; m.sub = "还没解锁";
        }
        return m;
    }

    /** 一枚都没拿到时聚光灯下放的问号 */
    private static Wall.Item hiddenItem() {
        Wall.Item m = new Wall.Item();
        m.id = Wall.HIDDEN; m.icon = Wall.HIDDEN; m.name = "？？？"; m.sub = ""; m.tier = Ach.SILVER; m.cat = 0;
        return m;
    }

    private void refresh() {
        if (wall == null) return;
        Stats s = tro.stats();
        ov.stats = s;
        // 一个分类一行；每行拿到几枚、一共几枚只算这台车能拿的（拿到的隐藏勋章照样摆在墙上）
        int nc = Ach.CATS.length;
        shown = new ArrayList<>();
        start = new int[nc + 1];
        counts = new int[nc];
        int[] got = new int[nc], base = new int[nc];
        for (int c = 0; c < nc; c++) {
            start[c] = shown.size();
            for (Ach a : Ach.list(c, s)) {
                shown.add(a);
                if (a.fits(s)) { base[c]++; if (s.unlocked.containsKey(a.id)) got[c]++; }
            }
            counts[c] = shown.size() - start[c];
        }
        start[nc] = shown.size();
        Wall.Item[] it = new Wall.Item[shown.size()];
        for (int i = 0; i < it.length; i++) it[i] = item(shown.get(i));
        wall.setRows(new Wall.Rows(it, start, Ach.CATS, got, base));
        // 总览
        int g = 0, b = 0, hid = 0;
        int[] tiers = new int[4];
        Ach last = null;
        long lt = 0;
        for (Ach a : Ach.ALL) {
            boolean f = a.fits(s);
            Long t = s.unlocked.get(a.id);
            if (f) b++;
            if (t == null) continue;
            if (f) g++; else hid++;
            tiers[a.tier]++;
            if (last == null || t > lt || t == lt && a.tier > last.tier) { lt = t; last = a; }
        }
        latest = last;
        ov.got = g;
        ov.base = b;
        ov.hidden = hid;
        ov.tiers = tiers;
        ov.latest = last;
        ov.latestT = lt;
        wall.setSpot(last != null ? item(last) : hiddenItem());
        ov.invalidate();
    }

    // ================= Overlay 交过来的手势 =================
    @Override
    public void scroll(int type, float y, float v) {
        if (wall == null) return;
        int row = -1;
        if (type == Wall.H_START) row = new Hall(Math.max(1, ov.getWidth()), Math.max(1, ov.getHeight())).rowAt(y, wall.scrollYNow, counts.length);
        wall.scroll(type, row, v);
    }

    @Override
    public void tapGrid(float x, float y) {
        Hall g = new Hall(Math.max(1, ov.getWidth()), Math.max(1, ov.getHeight()));
        if (g.inSpot(x, y)) {
            int i = latest != null ? shown.indexOf(latest) : -1;
            if (i >= 0) focusOn(i, true);
            return;
        }
        int[] hit = g.hit(x, y, wall.scrollYNow, wall.rowXNow, counts);
        if (hit != null) focusOn(start[hit[0]] + hit[1], false);
    }

    private void focusOn(int i, boolean fromSpot) {
        // "拖动可以转着看"只在第一次看一枚时写
        android.content.SharedPreferences sp = getContext().getSharedPreferences("ach", 0);
        ov.dragHint = !sp.getBoolean("dragHint", false);
        if (ov.dragHint) sp.edit().putBoolean("dragHint", true).apply();
        ov.focus = shown.get(i);
        ov.focusShown = true;
        ov.invalidate();
        wall.focus(i, fromSpot);
    }

    @Override
    public void rotate(float dx, float dy) {
        wall.rotate(dx, dy);
        tro.spin(Math.abs(dx) * 0.012); // 同 Wall 里手指拖动换成转角的比例
    }
    @Override public void closeFocus() { ov.focusShown = false; ov.focus = null; ov.invalidate(); wall.focus(-1, false); kick(); }
    @Override public void celebNext() { nextCeleb(); }
    @Override public void openStats() { ov.statsShown = true; tro.peek(); ov.invalidate(); }
    @Override public void closeStats() { ov.statsShown = false; ov.invalidate(); kick(); }
    @Override public void closeWall() { close(); }
}
