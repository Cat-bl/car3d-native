package com.x3card.car3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 导入车模的框（照网页版的 #busy）：整屏压暗、中间一张深色卡片，标题后面转圈，下面一行"步骤 · 百分比"、一根进度条（只往前走，0.3 秒缓动过去）。
 * 开着时挡住所有手势
 */
@SuppressLint("ViewConstructor")
final class Busy extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String title = "", step = "";
    private float f, shown, from;
    private long at;
    private int acc = 0xffff4b55;
    private boolean on;

    Busy(Context c) {
        super(c);
        setVisibility(GONE);
    }

    void show(String t, int accent) {
        title = t; step = ""; f = shown = from = 0; acc = accent;
        on = true;
        setVisibility(VISIBLE);
        invalidate();
    }
    void hide() { on = false; setVisibility(GONE); }
    boolean isOn() { return on; }
    /** 步骤文字 + 进度（0～1，只往前走） */
    void progress(String s, float v) {
        if (!on) return;
        step = s;
        if (v > f) { from = shown; f = Math.min(1, v); at = SystemClock.uptimeMillis(); }
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) { return on; }

    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), rem = H / 10.52f;
        c.drawColor(0x8c05070a); // rgba(5, 7, 10, .55)
        // 进度条缓动（同 transition: transform .3s ease）
        float k = Math.min(1, (SystemClock.uptimeMillis() - at) / 300f);
        shown = from + (f - from) * ease(k);
        String st = step + (f > 0 ? " · " + Math.round(f * 100) + "%" : "");
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        p.setTextSize(0.3f * rem);
        float tw = p.measureText(title), sp = 0.32f * rem, gap = 0.16f * rem; // 转圈 .26rem 加两边 .03rem 的边
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(0.22f * rem);
        float sw = p.measureText(st), bw = Math.min(3.6f * rem, W * 0.82f - 1.2f * rem);
        float cw = Math.min(W * 0.82f, Math.max(Math.max(tw + gap + sp, sw), f > 0 ? bw : 0) + 1.2f * rem);
        float th = 0.3f * rem * 1.3f, sh = 0.22f * rem * 1.3f;
        float ch = 0.36f * rem * 2 + th + 0.12f * rem + sh + (f > 0 ? 0.18f * rem + 0.06f * rem : 0);
        float x0 = (W - cw) / 2, y0 = (H - ch) / 2;
        r.set(x0, y0, x0 + cw, y0 + ch);
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(0.8f * rem / 2, 0, 0.3f * rem, 0x73000000);
        // linear-gradient(160deg, rgba(38, 42, 52, .97) 0%, rgba(15, 17, 22, .98) 70%)
        double a = Math.toRadians(160 - 90);
        float dx = (float) Math.cos(a), dy = (float) Math.sin(a), half = (Math.abs(cw * dx) + Math.abs(ch * dy)) / 2, cx = x0 + cw / 2, cy = y0 + ch / 2;
        p.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, new int[] { 0xf7262a34, 0xfa0f1116, 0xfa0f1116 }, new float[] { 0, 0.7f, 1 }, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 0.32f * rem, 0.32f * rem, p);
        p.setShader(null);
        p.clearShadowLayer();
        // 标题 + 转圈
        float y = y0 + 0.36f * rem;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        p.setTextSize(0.3f * rem);
        p.setColor(0xffeef1f6);
        float lx = (W - (tw + gap + sp)) / 2;
        Paint.FontMetrics fm = p.getFontMetrics();
        float base = y + (th - (fm.descent - fm.ascent)) / 2 - fm.ascent;
        c.drawText(title, lx, base, p);
        float scx = lx + tw + gap + sp / 2, scy = y + th / 2 + 0.03f * rem, sr = sp / 2 - 0.015f * rem;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.03f * rem);
        p.setColor(0x40ffffff);
        c.drawCircle(scx, scy, sr, p);
        p.setColor(0xffffffff);
        float rot = (SystemClock.uptimeMillis() % 800) / 800f * 360;
        r.set(scx - sr, scy - sr, scx + sr, scy + sr);
        c.drawArc(r, rot - 135, 90, false, p); // 只有上边一段亮（border-top-color）
        p.setStyle(Paint.Style.FILL);
        // 步骤 · 百分比
        y += th + 0.12f * rem;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(0.22f * rem);
        p.setColor(0x99eef1f6);
        fm = p.getFontMetrics();
        c.drawText(st, (W - sw) / 2, y + (sh - (fm.descent - fm.ascent)) / 2 - fm.ascent, p);
        // 进度条
        if (f > 0) {
            y += sh + 0.18f * rem;
            float bx = (W - bw) / 2, bh = 0.06f * rem;
            r.set(bx, y, bx + bw, y + bh);
            p.setColor(0x24ffffff);
            c.drawRoundRect(r, bh / 2, bh / 2, p);
            c.save();
            c.clipRect(bx, y, bx + bw * shown, y + bh);
            p.setColor(acc);
            c.drawRoundRect(r, bh / 2, bh / 2, p);
            c.restore();
        }
        if (on) postInvalidateOnAnimation(); // 转圈一直转
    }
    private static float ease(float t) { // cubic-bezier(.25, .1, .25, 1)
        float lo = 0, hi = 1;
        for (int i = 0; i < 12; i++) {
            float m = (lo + hi) / 2, u = 1 - m, x = 3 * u * u * m * 0.25f + 3 * u * m * m * 0.25f + m * m * m;
            if (x < t) lo = m; else hi = m;
        }
        float m = (lo + hi) / 2, u = 1 - m;
        return 3 * u * u * m * 0.1f + 3 * u * m * m + m * m * m;
    }
}
