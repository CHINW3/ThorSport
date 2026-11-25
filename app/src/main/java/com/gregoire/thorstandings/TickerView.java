package com.gregoire.thorstandings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * The scrolling line of other games under the top-screen Board.
 * Its own view so it can move every frame (60 fps) without redrawing the whole Board,
 * and the text is drawn once into a bitmap that slides at sub-pixel positions: no jumps.
 */
public class TickerView extends View {
    private static final float SPEED = 85f;   // pixels per second
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.DITHER_FLAG);
    private final Typeface tf = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private String text;
    private int top = -1, bottom = -1;
    private Bitmap strip;
    private long t0 = 0;
    private float offAtChange = 0;

    public TickerView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    /** Called by the Board when it draws; null text hides the ticker. */
    public void set(String s, int top, int bottom) {
        if (s != null && s.equals(text) && top == this.top && bottom == this.bottom) return;
        boolean wasEmpty = text == null;
        float cur = offset();
        text = s; this.top = top; this.bottom = bottom;
        if (strip != null) { strip.recycle(); strip = null; }
        if (s != null) {
            float th = bottom - top;
            p.setTypeface(tf);
            p.setTextSize(th * 0.45f);
            p.setColor(0xFF8B919C);
            int tw = Math.max(1, (int) Math.ceil(p.measureText(s)));
            strip = Bitmap.createBitmap(tw, Math.max(1, (int) th), Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(strip);
            Paint.FontMetrics fm = p.getFontMetrics();
            bc.drawText(s, 0, th / 2 - (fm.ascent + fm.descent) / 2, p);
            // keep the scroll position when the scores change, so the line doesn't jump back
            offAtChange = wasEmpty ? 0 : cur % tw;
            t0 = System.nanoTime();
        }
        postInvalidateOnAnimation();
    }

    private float offset() {
        if (t0 == 0) return 0;
        return offAtChange + (System.nanoTime() - t0) / 1e9f * SPEED;
    }

    @Override protected void onDraw(Canvas c) {
        if (text == null || strip == null || top < 0) return;
        int tw = strip.getWidth();
        float off = offset() % tw;
        c.save();
        c.clipRect(0, top, getWidth(), bottom);
        for (float x = -off; x < getWidth(); x += tw) c.drawBitmap(strip, x, top, p);
        c.restore();
        postInvalidateOnAnimation();
    }
}
