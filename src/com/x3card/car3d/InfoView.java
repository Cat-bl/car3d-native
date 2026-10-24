package com.x3card.car3d;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 叠在 3D 场景上面的一层（Canvas）：连不上宿主、没授权时的说明和"申请授权"按钮（提示条、设置在 Ui 里）。
 * 车在下面照常显示（压暗）。没有按钮可点时不接手势（交给下面的场景）。字号按窗口高度算（1rem = 高度 / 10.52）
 */
public class InfoView extends View {
    public interface Press { void on(String what); }

    private final Press press;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF authKey = new RectF();
    private String status = "正在连接宿主…";
    private boolean bad, read, ready;

    public InfoView(Context c, Press press) {
        super(c);
        this.press = press;
    }

    void setStatus(String s, boolean err) { status = s; bad = err; invalidate(); }
    void setPerms(boolean rd, boolean ok) { read = rd; ready = ok; invalidate(); }
    /** 要不要盖住场景：连着宿主、有读数据的授权、没出错时不盖 */
    boolean covering() { return !ready || !read || bad; }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float rem = h / 10.52f;
        authKey.setEmpty();
        if (covering()) {
            p.setColor(0xb3000000);
            c.drawRect(0, 0, w, h, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(rem * 0.4f);
            p.setColor(0xffffffff);
            boolean ask = ready && !read && !bad;
            c.drawText(ask ? "要读取车况" : status, w / 2f, h / 2f - rem * 0.7f, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(rem * 0.26f);
            p.setColor(bad ? 0xffff8a80 : 0xb3ffffff);
            c.drawText(ask ? "点下面的按钮，在宿主弹出的确认页里允许" : bad ? "过一会儿会自动重连" : "", w / 2f, h / 2f - rem * 0.2f, p);
            if (ask) {
                float bw = rem * 2.6f, bh = rem * 0.7f;
                authKey.set(w / 2f - bw / 2, h / 2f + rem * 0.2f, w / 2f + bw / 2, h / 2f + rem * 0.2f + bh);
                p.setColor(0xffffffff);
                c.drawRoundRect(authKey, bh / 2, bh / 2, p);
                p.setColor(0xff1a1d2e);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(rem * 0.28f);
                c.drawText("申请授权", w / 2f, authKey.centerY() + rem * 0.1f, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!covering()) return false; // 交给下面的 3D 场景
        if (e.getAction() == MotionEvent.ACTION_UP && authKey.contains(e.getX(), e.getY())) press.on("auth");
        return true;
    }
}
