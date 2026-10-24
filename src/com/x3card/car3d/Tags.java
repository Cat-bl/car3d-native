package com.x3card.car3d;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 跟着车走的几样（每画一帧 3D 就摆一次，单独一层，不重画别的字）：四个胎压标签（照 .tp：圆角胶囊，左前 · 数 · 胎温，异常变色，在车另一边的淡出）、
 * 车顶上方的空调键（照 .roof-btn：俯视时才显示，开着蓝底白风扇，按下去等车况时半透明）。只有空调键接手势
 */
@SuppressLint("ViewConstructor")
final class Tags extends View {
    interface Roof { void tap(); }
    private static final String[] NAME = { "左前", "右前", "左后", "右后" };
    private final Hud hud;
    private final Roof roof;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface num500, ui500;
    private float[] xy = new float[8];
    private String[] cls = { "off", "off", "off", "off" }, text = { "—", "—", "—", "—" }, temp = { "", "", "", "" };
    private final float[] alpha = new float[4];
    private boolean roofHas, roofShow, roofOn, roofWait;
    private float roofX, roofY, roofA;
    private final float dens;
    private ValueAnimator fade;

    Tags(Context c, Hud hud, Roof roof) {
        super(c);
        this.hud = hud; this.roof = roof;
        dens = c.getResources().getDisplayMetrics().density;
        num500 = Hud.outfit(c, 500);
        ui500 = Typeface.create(Typeface.DEFAULT, 500, false);
    }
    /** 胎压标签：位置（CSS 像素）、颜色类、数、胎温 */
    void setTyres(float[] p, String[] c, String[] t, String[] tm) {
        xy = p; cls = c; text = t; temp = tm;
        animateAlpha();
    }
    /** 面板开着：竖屏时车顶空调键藏起来 */
    void setCovered(boolean on) { if (covered != on) { covered = on; animateAlpha(); } }
    private boolean covered;
    private boolean roofVis() { return roofHas && roofShow && !(covered && getWidth() < getHeight()); }
    void setRoof(boolean has, boolean show, float x, float y, boolean on, boolean wait) {
        roofHas = has; roofShow = show; roofX = x; roofY = y; roofOn = on; roofWait = wait;
        animateAlpha();
    }
    /** 淡入淡出（同 .tp / .roof-btn 的 transition: opacity .25s） */
    private void animateAlpha() {
        boolean need = false;
        for (int i = 0; i < 4; i++) { float t = cls[i] != null && cls[i].contains("off") ? 0 : 1; if (Math.abs(alpha[i] - t) > 0.01f) need = true; }
        float rt = roofVis() ? 1 : 0;
        if (Math.abs(roofA - rt) > 0.01f) need = true;
        if (need && (fade == null || !fade.isRunning())) {
            fade = ValueAnimator.ofFloat(0, 1);
            fade.setDuration(250);
            final long t0 = android.os.SystemClock.uptimeMillis();
            fade.addUpdateListener(a -> step());
            fade.start();
        }
        invalidate();
    }
    private long last;
    private void step() {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float k = dt / 0.25f;
        boolean more = false;
        for (int i = 0; i < 4; i++) {
            float t = cls[i] != null && cls[i].contains("off") ? 0 : 1;
            alpha[i] = alpha[i] < t ? Math.min(t, alpha[i] + k) : Math.max(t, alpha[i] - k);
            if (alpha[i] != t) more = true;
        }
        float rt = roofVis() ? 1 : 0;
        roofA = roofA < rt ? Math.min(rt, roofA + k) : Math.max(rt, roofA - k);
        if (roofA != rt) more = true;
        invalidate();
        if (!more) { last = 0; if (fade != null) fade.cancel(); }
    }
    private static int withA(int c, float a) { return (Math.round((c >>> 24) * a) << 24) | (c & 0xffffff); }
    private static float baseline(float cy, Paint p) { Paint.FontMetrics m = p.getFontMetrics(); return cy - (m.ascent + m.descent) / 2; }

    @Override protected void onDraw(Canvas c) {
        float rem = getHeight() / 10.52f;
        for (int i = 0; i < 4; i++) {
            if (alpha[i] <= 0.01f) continue;
            float x = xy[i * 2] * dens, y = xy[i * 2 + 1] * dens - 0.24f * rem; // margin-top: -.24rem
            boolean warn = cls[i] != null && cls[i].contains("warn"), bad = cls[i] != null && cls[i].contains("bad");
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(0.18f * rem);
            float w1 = p.measureText(NAME[i]);
            p.setTypeface(num500); p.setTextSize(0.24f * rem);
            float w2 = p.measureText(text[i]);
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(0.17f * rem);
            float w3 = temp[i].isEmpty() ? 0 : 0.08f * rem + p.measureText(temp[i]);
            float w = 0.28f * rem + w1 + 0.08f * rem + w2 + w3, h = 0.24f * rem * 1.3f + 0.1f * rem, x0 = x - w / 2, cy = y + h / 2;
            p.setColor(withA(hud.chip(), alpha[i]));
            c.drawRoundRect(x0, y, x0 + w, y + h, 0.3f * rem, 0.3f * rem, p);
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(0.18f * rem); p.setColor(withA(hud.mute(), alpha[i]));
            float bl = baseline(cy, num(0.24f * rem));
            c.drawText(NAME[i], x0 + 0.14f * rem, bl, p);
            p.setTypeface(num500); p.setTextSize(0.24f * rem); p.setColor(withA(bad ? hud.bad() : warn ? hud.warn() : hud.ink(), alpha[i]));
            c.drawText(text[i], x0 + 0.14f * rem + w1 + 0.08f * rem, bl, p);
            if (w3 > 0) { p.setTypeface(Typeface.DEFAULT); p.setTextSize(0.17f * rem); p.setColor(withA(hud.mute(), alpha[i])); c.drawText(temp[i], x0 + 0.14f * rem + w1 + 0.08f * rem + w2 + 0.08f * rem, bl, p); }
        }
        if (roofA > 0.01f) {
            float x = roofX * dens, y = roofY * dens, rr = 0.28f * rem, a = roofA * (roofWait ? 0.6f : 1);
            p.setColor(withA(0x24000000, a)); c.drawCircle(x, y + 0.04f * rem, rr * 1.05f, p);
            p.setColor(withA(roofOn ? hud.info() : hud.chip(), a)); c.drawCircle(x, y, rr, p);
            float pad = 0.09f * rem, sz = rr * 2 - pad * 2;
            android.graphics.Path path = SvgPath.of(CarStat.FAN_D);
            p.setColor(withA(roofOn ? 0xffffffff : hud.ink(), a));
            for (int k = 0; k < 4; k++) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setRotate(90 * k, 12, 12); m.postScale(sz / 24, sz / 24); m.postTranslate(x - sz / 2, y - sz / 2);
                android.graphics.Path q = new android.graphics.Path(); path.transform(m, q); c.drawPath(q, p);
            }
            c.drawCircle(x, y, 1.9f * sz / 24, p);
        }
    }
    private Paint num(float size) { Paint q = new Paint(); q.setTypeface(num500); q.setTextSize(size); return q; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!(roofVis() && roofA > 0.5f)) return false;
        float rem = getHeight() / 10.52f, dx = e.getX() - roofX * dens, dy = e.getY() - roofY * dens, rr = 0.36f * rem;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return dx * dx + dy * dy <= rr * rr;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && dx * dx + dy * dy <= rr * rr) roof.tap();
        return true;
    }
}
