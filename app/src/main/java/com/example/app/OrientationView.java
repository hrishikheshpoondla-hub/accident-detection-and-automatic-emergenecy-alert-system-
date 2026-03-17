package com.example.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class OrientationView extends View {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float roll = 0;

    public OrientationView(Context context) {
        super(context);
        init();
    }

    public OrientationView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8);
        paint.setColor(Color.parseColor("#A855F7")); // Vibrant Purple
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void updateOrientation(float pitch, float roll) {
        this.roll = roll;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        int lineLength = Math.min(getWidth(), getHeight()) / 2;

        canvas.save();
        canvas.translate(centerX, centerY);
        
        // Rotate the canvas based on phone roll to represent the horizon/tilt
        canvas.rotate(-roll); 

        // Draw a single clean horizontal line representing the vehicle's tilt
        canvas.drawLine(-lineLength, 0, lineLength, 0, paint);

        // Optional: Draw a small vertical "center" notch
        paint.setStrokeWidth(4);
        canvas.drawLine(0, -20, 0, 20, paint);
        paint.setStrokeWidth(8);

        canvas.restore();
    }
}
