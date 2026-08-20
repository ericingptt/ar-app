package com.bigxreality.jorjinverifier;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the ToF module's 8x8 depth grid live, with the tracked hand centroid on top.
 *
 * <p>Gesture tuning is impossible blind: a tester who only sees "最後手勢：向左" cannot tell a
 * swipe the recogniser rejected from one it mirrored, nor learn where the sensor's usable
 * volume actually is. Showing the raw zones turns all of that into something visible - the hand
 * appears as a bright patch, and the direction it travels across the grid is the direction the
 * recogniser will report.
 */
public final class TofHeatmapView extends View {
    private static final int GRID = TofGestureRecognizer.GRID;

    private final float[] ranges = new float[TofGestureRecognizer.ZONES];
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centroidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private float centroidRow = -1f;
    private float centroidColumn = -1f;

    public TofHeatmapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        centroidPaint.setStyle(Paint.Style.STROKE);
        centroidPaint.setStrokeWidth(4f);
        centroidPaint.setColor(0xFF66D9FF);
    }

    /** @param ranges 64 zone distances in mm, zero where the zone saw nothing. */
    void setFrame(float[] ranges, float centroidRow, float centroidColumn) {
        System.arraycopy(ranges, 0, this.ranges, 0, this.ranges.length);
        this.centroidRow = centroidRow;
        this.centroidColumn = centroidColumn;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float step = size / GRID;
        float left = (getWidth() - size) / 2f;
        float gap = step * 0.08f;

        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                cell.set(left + column * step + gap, row * step + gap,
                        left + (column + 1) * step - gap, (row + 1) * step - gap);
                cellPaint.setColor(colourFor(ranges[row * GRID + column]));
                canvas.drawRoundRect(cell, gap, gap, cellPaint);
            }
        }
        if (centroidRow >= 0f && centroidColumn >= 0f) {
            canvas.drawCircle(left + (centroidColumn + 0.5f) * step,
                    (centroidRow + 0.5f) * step, step * 0.42f, centroidPaint);
        }
    }

    /**
     * Near is warm, far is cool, and anything outside the recogniser's working window is drawn
     * as empty - so the colours show exactly the zones the recogniser is willing to act on.
     */
    private static int colourFor(float rangeMm) {
        if (rangeMm < TofGestureRecognizer.MIN_RANGE_MM
                || rangeMm > TofGestureRecognizer.MAX_RANGE_MM) {
            return 0xFF161C24;
        }
        float span = TofGestureRecognizer.MAX_RANGE_MM - TofGestureRecognizer.MIN_RANGE_MM;
        float t = (rangeMm - TofGestureRecognizer.MIN_RANGE_MM) / span;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        // Hue 0 (near, red) through 200 (far, blue).
        return Color.HSVToColor(new float[]{t * 200f, 0.85f, 1f - t * 0.35f});
    }
}
