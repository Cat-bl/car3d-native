package com.x3card.car3d.ach;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车况页上解锁成就的提示（同 Xbox / Steam 的成就弹窗）：右上角、顶栏下面，一张卡片从屏幕右边外面滑进来停住（最后轻轻回弹一下），
 * 左边的勋章转半圈亮出正面、扫过一道高光，右边两行字"解锁成就" + 名字；停 4 秒再滑回右边外面。排着几个就一个接一个出。
 * 点一下打开成就墙（完整的庆祝在墙里放）。设置、全部车况这些面板开着时先排着（正出着的收回去，关了再出）。
 * 配色同车况页（浅色白卡片、深色深卡片、1px 描边），左边一道强调色（跟着车漆）。勋章图在后台线程里画（Medal2D），只动 transform 和透明度
 */
@SuppressLint("ViewConstructor")
public final class AchToast extends FrameLayout {
    private final ArrayDeque<List<Ach>> queue = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FrameLayout box;
    private final Card card;
    private final MedalView medal;
    private final Runnable onTap;
    private List<Ach> cur;
    private boolean held, busy;
    private int token;
    private float topRem = 1.16f, rightRem = 0.26f;
    private float darkK;
    private int acc = 0xffff4b55;

    public AchToast(Context c, Runnable onTap) {
        super(c);
        this.onTap = onTap;
        box = new FrameLayout(c);
        box.setVisibility(INVISIBLE);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rem() * 0.2f); }
        });
        box.setOnClickListener(v -> tapped());
        card = new Card(c);
        box.addView(card, new LayoutParams(-1, -1));
        medal = new MedalView(c);
        box.addView(medal);
        addView(box);
    }

    private float rem() { return Math.max(1, getHeight()) / 10.52f; }

    // ================= 外面交进来的 =================
    /** 位置：顶边、右边距（rem，同右上角的键） */
    public void place(float top, float right) { topRem = top; rightRem = right; if (cur != null) layoutBox(); }
    public void setDark(float k) { darkK = k; card.invalidate(); }
    public void setAccent(int a) { acc = a; card.invalidate(); }

    /** 新解锁了一组 */
    public void show(List<Ach> g) { queue.add(g); pump(); }

    /** 面板开着：先排着（正出着的收回去，回头再出） */
    public void hold(boolean h) {
        if (h == held) return;
        held = h;
        if (h && cur != null) { queue.addFirst(cur); dismiss(false); }
        else pump();
    }

    /** 成就墙打开了：排着的都在墙里庆祝，提示不用出了 */
    public void clear() {
        queue.clear();
        if (cur != null) dismiss(false);
        else if (busy) { token++; busy = false; } // 勋章图还在画：画好了也不出
    }

    private void tapped() {
        if (cur == null) return;
        dismiss(false);
        queue.clear();
        if (onTap != null) onTap.run();
    }

    // ================= 一个接一个 =================
    private void pump() {
        if (busy || held || queue.isEmpty() || getHeight() == 0) return;
        final List<Ach> g = queue.poll();
        if (g == null || g.isEmpty()) { pump(); return; }
        busy = true;
        final int tk = ++token;
        final int size = Math.max(48, Math.min(192, Math.round(rem() * 0.72f * 1.6f)));
        worker.execute(() -> {
            final Bitmap f = Medal2D.make(g.get(0), size, false), b = Medal2D.make(g.get(0), size, true);
            main.post(() -> {
                if (tk != token) { f.recycle(); b.recycle(); return; }
                if (held) { f.recycle(); b.recycle(); busy = false; queue.addFirst(g); return; }
                start(g, f, b);
            });
        });
    }

    private void start(List<Ach> g, Bitmap f, Bitmap b) {
        cur = g;
        medal.set(f, b);
        if (g.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, g.size()); i++) sb.append(i > 0 ? "、" : "").append(g.get(i).name);
            card.set("一下子解锁 " + g.size() + " 枚成就", g.size() > 3 ? sb + " 等" : sb.toString());
        } else card.set("解锁成就", g.get(0).name);
        layoutBox();
        box.setVisibility(INVISIBLE);
        box.post(this::enter);
    }

    private void layoutBox() {
        float r = rem();
        int h = Math.round(r * 0.92f);
        int w = Math.round(Math.min(Math.min(getWidth() * 0.62f, r * 5.8f), card.wantW()));
        LayoutParams lp = new LayoutParams(w, h, Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(r * topRem);
        lp.rightMargin = Math.round(r * rightRem);
        box.setLayoutParams(lp);
        int d = Math.round(r * 0.72f);
        LayoutParams mp = new LayoutParams(d, d, Gravity.CENTER_VERTICAL | Gravity.START);
        mp.leftMargin = Math.round(r * 0.16f);
        medal.setLayoutParams(mp);
        medal.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        box.setElevation(r * 0.1f);
    }

    private void enter() {
        if (cur == null) return;
        float off = box.getWidth() + rem() * rightRem + rem() * 0.3f;
        box.setTranslationX(off);
        box.setVisibility(VISIBLE);
        medal.setRotationY(180);
        medal.flipFace();
        medal.shine = -1;
        final int tk = token;
        box.animate().translationX(0).setDuration(450).setInterpolator(new OvershootInterpolator(1.1f)).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (tk != token || cur == null) return;
                // 停稳了：勋章转半圈亮出正面，再扫过一道光
                medal.animate().rotationY(0).setDuration(520).setInterpolator(new DecelerateInterpolator(1.6f))
                    .setUpdateListener(x -> medal.flipFace())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator a2) { if (tk == token && cur != null) medal.sweep(); }
                    }).start();
                main.postDelayed(autoOff, 4000);
            }
        }).start();
    }

    private final Runnable autoOff = () -> dismiss(true);

    /** 滑回右边外面；next：接着出下一个 */
    private void dismiss(boolean next) {
        main.removeCallbacks(autoOff);
        if (cur == null) return;
        cur = null;
        token++;
        medal.animate().cancel();
        float off = box.getWidth() + rem() * rightRem + rem() * 0.3f;
        box.animate().cancel();
        box.animate().translationX(off).setDuration(350).setInterpolator(new AccelerateInterpolator(1.5f)).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                box.setVisibility(INVISIBLE);
                medal.set(null, null);
                busy = false;
                if (next) main.postDelayed(() -> pump(), 200); else pump();
            }
        }).start();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (cur != null) layoutBox();
        else post(this::pump);
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); worker.shutdownNow(); }

    // ================= 卡片 =================
    private final class Card extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface ui600 = Typeface.create(Typeface.DEFAULT, 600, false), ui700 = Typeface.create(Typeface.DEFAULT, 700, false);
        private final RectF rr = new RectF();
        private String head = "", name = "";

        Card(Context c) { super(c); }

        void set(String h, String n) { head = h; name = n; invalidate(); }

        private float textX() { float r = rem(); return r * (0.16f + 0.72f + 0.2f); }

        float wantW() {
            float r = rem();
            p.setTypeface(ui600); p.setTextSize(r * 0.19f); p.setLetterSpacing(0.06f);
            float a = p.measureText(head);
            p.setLetterSpacing(0);
            p.setTypeface(ui700); p.setTextSize(r * 0.27f);
            float b = p.measureText(name);
            return textX() + Math.max(a, b) + r * 0.34f;
        }

        @Override protected void onDraw(Canvas c) {
            float r = rem(), w = getWidth(), h = getHeight(), rad = r * 0.2f;
            float sw = Math.max(1, getResources().getDisplayMetrics().density);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Overlay.mix(0xf7ffffff, 0xf51c1f25, darkK));
            rr.set(0, 0, w, h);
            c.drawRoundRect(rr, rad, rad, p);
            // 左边一道强调色（裁在圆角里）
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(rr, rad, rad, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setColor(acc);
            c.drawRect(0, 0, r * 0.06f, h, p);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(Overlay.mix(0x1f14171c, 0x21ffffff, darkK));
            rr.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2);
            c.drawRoundRect(rr, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
            float x = textX(), maxW = w - x - r * 0.3f;
            p.setTypeface(ui600); p.setTextSize(r * 0.19f); p.setLetterSpacing(0.06f);
            p.setColor(Overlay.mix(0x8014171c, 0x80eef1f6, darkK));
            c.drawText(fit(head, maxW), x, h * 0.42f, p);
            p.setLetterSpacing(0);
            p.setTypeface(ui700); p.setTextSize(r * 0.27f);
            p.setColor(Overlay.mix(0xff14171c, 0xffeef1f6, darkK));
            c.drawText(fit(name, maxW), x, h * 0.77f, p);
        }

        private String fit(String s, float w) {
            if (p.measureText(s) <= w) return s;
            for (int n = s.length() - 1; n > 0; n--) { String t = s.substring(0, n) + "…"; if (p.measureText(t) <= w) return t; }
            return s;
        }
    }

    // ================= 勋章（转面、扫光） =================
    private static final class MedalView extends View {
        private Bitmap front, back;
        private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG), lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dst = new RectF();
        private boolean showBack;
        /** 高光扫到哪（−1 没有，0～1 从左上扫到右下） */
        float shine = -1;
        private ValueAnimator sweepA;

        MedalView(Context c) { super(c); lp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)); }

        void set(Bitmap f, Bitmap b) {
            if (sweepA != null) sweepA.cancel();
            if (front != null && front != f) front.recycle();
            if (back != null && back != b) back.recycle();
            front = f; back = b; shine = -1;
            invalidate();
        }

        /** 转过 90° 以后看到的是正面 */
        void flipFace() {
            float a = Math.abs(getRotationY()) % 360;
            boolean b = a > 90 && a < 270;
            if (b != showBack) { showBack = b; invalidate(); }
        }

        void sweep() {
            if (sweepA != null) sweepA.cancel();
            sweepA = ValueAnimator.ofFloat(0, 1);
            sweepA.setDuration(700);
            sweepA.addUpdateListener(v -> { shine = (Float) v.getAnimatedValue(); invalidate(); });
            sweepA.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { shine = -1; invalidate(); } });
            sweepA.start();
        }

        @Override protected void onDraw(Canvas c) {
            Bitmap bm = showBack ? back : front;
            if (bm == null) return;
            float w = getWidth(), h = getHeight();
            dst.set(0, 0, w, h);
            if (shine < 0 || showBack) {
                if (showBack) { c.save(); c.scale(-1, 1, w / 2, h / 2); c.drawBitmap(bm, null, dst, bp); c.restore(); }
                else c.drawBitmap(bm, null, dst, bp);
                return;
            }
            // 高光只落在勋章上：先画勋章，再按勋章的形状叠一道斜的亮带
            int s = c.saveLayer(0, 0, w, h, null);
            c.drawBitmap(bm, null, dst, bp);
            float t = -0.6f + shine * 2.2f, bw = 0.35f;
            float x0 = w * (t - bw), x1 = w * (t + bw);
            lp.setShader(new LinearGradient(x0, x0 * 0.6f, x1, x1 * 0.6f, new int[] { 0x00ffffff, 0x8cffffff, 0x00ffffff }, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, lp);
            c.restoreToCount(s);
        }
    }
}
