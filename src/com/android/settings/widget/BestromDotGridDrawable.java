/*
 * Copyright (C) 2026 The BestROM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.widget;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settings.R;

/**
 * The homepage device card avatar: a flat circle filled with a repeating dot pattern.
 *
 * <p>The pattern is one small tile bitmap wrapped in a repeating shader rather than a vector with
 * dozens of paths, which is both cheaper to inflate and far smaller to hold than the vector's
 * raster cache. The tile is shared process-wide; drawing allocates nothing.
 */
public class BestromDotGridDrawable extends Drawable {

    private static final Object sTileLock = new Object();

    private static Bitmap sTile;
    private static int sTilePitch;
    private static float sTileRadius;
    private static int sTileColor;

    private final int mSize;
    private final int mFillColor;
    private final int mDotColor;
    private final Paint mFillPaint;
    private final Paint mDotPaint;

    public BestromDotGridDrawable(@NonNull Context context) {
        final Resources res = context.getResources();
        mSize = res.getDimensionPixelSize(R.dimen.bestrom_avatar_size);
        final int pitch = Math.max(1, res.getDimensionPixelSize(R.dimen.bestrom_avatar_dot_pitch));
        final float dotRadius = res.getDimension(R.dimen.bestrom_avatar_dot_radius);
        mDotColor = res.getColor(R.color.bestrom_avatar_dot, context.getTheme());
        mFillColor = res.getColor(R.color.bestrom_avatar_bg, context.getTheme());

        mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFillPaint.setColor(mFillColor);

        mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotPaint.setColor(mDotColor);
        mDotPaint.setShader(new BitmapShader(tile(pitch, dotRadius, mDotColor),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    private static Bitmap tile(int pitch, float dotRadius, int dotColor) {
        synchronized (sTileLock) {
            if (sTile == null || sTilePitch != pitch || sTileRadius != dotRadius
                    || sTileColor != dotColor) {
                final Bitmap bitmap = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888);
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(dotColor);
                new Canvas(bitmap).drawCircle(pitch / 2f, pitch / 2f, dotRadius, paint);
                sTile = bitmap;
                sTilePitch = pitch;
                sTileRadius = dotRadius;
                sTileColor = dotColor;
            }
            return sTile;
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        final float cx = bounds.exactCenterX();
        final float cy = bounds.exactCenterY();
        final float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        canvas.drawCircle(cx, cy, radius, mFillPaint);
        canvas.drawCircle(cx, cy, radius, mDotPaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }

    @Override
    public void setAlpha(int alpha) {
        // setAlpha writes into the paint colour, so the colour has to be restored first or a
        // second call would compound the first.
        mFillPaint.setColor(mFillColor);
        mFillPaint.setAlpha(alpha);
        mDotPaint.setColor(mDotColor);
        mDotPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFillPaint.setColorFilter(colorFilter);
        mDotPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
