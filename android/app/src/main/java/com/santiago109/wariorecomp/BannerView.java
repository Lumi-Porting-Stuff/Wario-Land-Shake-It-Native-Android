package com.santiago109.wariorecomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The head of the Home page: the artwork, with the game's name over it.
 *
 * <p>A port of launcher-avalonia/UI/Banner.cs, down to the two gradients that darken the left and
 * the bottom so the title stays readable, and the framing that keeps the top of the picture — which
 * is where the artist's credit sits — rather than centring it.
 *
 * <p>The picture is the fan art by Dreaminerryday the PC launcher ships. Replacing
 * {@code launcher_banner.jpg} or {@code .png} in the app's data folder overrides it, as it does on
 * PC.
 */
final class BannerView extends View {

    /** The launcher's background colour, which both gradients fade into. */
    private static final int BACK = 0x180E22;
    /** 0 keeps the top of the picture, so the credit in its corner is not cut off. 0.5 centres. */
    private static final float FOCUS_Y = 0f;

    private final Bitmap artwork;
    private final String subtitle;

    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint second = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint third = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;

    BannerView(Context context, String subtitle) {
        super(context);
        this.subtitle = subtitle;
        this.density = context.getResources().getDisplayMetrics().density;
        this.artwork = load(context);

        // The heaviest weight Android has, which is what FontWeight.Black asks for on the PC.
        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        title.setTypeface(black);
        title.setTextSize(dp(44));
        title.setColor(0xFFF6C426);

        second.setTypeface(black);
        second.setTextSize(dp(28));
        second.setColor(Color.WHITE);

        third.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        third.setTextSize(dp(13));
        third.setColor(0xFFF6C426);
    }

    /** The data folder's own banner first, as on PC, then the one in the app. */
    private Bitmap load(Context context) {
        File data = GameData.dataDirectory(context);
        for (String name : new String[]{"launcher_banner.jpg", "launcher_banner.png"}) {
            File own = new File(data, name);
            if (own.isFile()) {
                Bitmap bitmap = BitmapFactory.decodeFile(own.getAbsolutePath());
                if (bitmap != null) {
                    return bitmap;
                }
            }
        }
        try (InputStream input = context.getAssets().open("launcher_banner.jpg")) {
            return BitmapFactory.decodeStream(input);
        } catch (IOException e) {
            // No picture: the gradient below stands in, as it does on PC.
            return null;
        }
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(0, widthSpec), Math.round(dp(220)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        if (artwork != null) {
            drawCover(canvas, w, h);
        } else {
            shade.setShader(new LinearGradient(0, 0, w, h,
                    0xFF461A6E, 0xFF140A20, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, shade);
        }

        // Darker at the left, so the title reads against it.
        shade.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{alpha(200), alpha(120), alpha(0)},
                new float[]{0f, 0.35f, 0.6f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, shade);

        // And darker towards the bottom, where the page continues.
        shade.setShader(new LinearGradient(0, h * 0.5f, 0, h,
                alpha(0), alpha(230), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, shade);

        drawTitle(canvas, h);
    }

    private static int alpha(int a) {
        return (a << 24) | BACK;
    }

    /** Fills the banner with the picture, cropping rather than squashing it. */
    private void drawCover(Canvas canvas, int w, int h) {
        int iw = artwork.getWidth();
        int ih = artwork.getHeight();
        if (iw <= 0 || ih <= 0) {
            return;
        }
        float scale = Math.max((float) w / iw, (float) h / ih);
        float sw = iw * scale;
        float sh = ih * scale;
        float left = (w - sw) / 2f;
        float top = (h - sh) * FOCUS_Y;
        canvas.drawBitmap(artwork, new Rect(0, 0, iw, ih),
                new RectF(left, top, left + sw, top + sh), image);
    }

    /** The three lines, stacked up from the bottom as the PC launcher stacks them. */
    private void drawTitle(Canvas canvas, int h) {
        float left = dp(34);
        float bottom = h - dp(18);

        // The PC sets these at a window's width. Here the banner can be much narrower — a phone
        // held upright, with the page list beside it — so the whole title shrinks to fit rather
        // than running off the edge.
        float room = getWidth() - left - dp(12);
        float widest = Math.max(title.measureText("WARIO LAND"),
                Math.max(second.measureText("SHAKE IT!"), third.measureText(subtitle)));
        if (widest > room && widest > 0) {
            float fit = room / widest;
            title.setTextSize(dp(44) * fit);
            second.setTextSize(dp(28) * fit);
            third.setTextSize(dp(13) * fit);
        } else {
            title.setTextSize(dp(44));
            second.setTextSize(dp(28));
            third.setTextSize(dp(13));
        }

        canvas.drawText(subtitle, left + dp(6), bottom - third.descent(), third);
        float thirdTop = bottom - (third.descent() - third.ascent());

        float secondBottom = thirdTop - dp(8);
        canvas.drawText("SHAKE IT!", left + dp(4), secondBottom - second.descent(), second);
        float secondTop = secondBottom - (second.descent() - second.ascent());

        // The PC launcher pulls this line down by 6 so the two words sit tight together.
        float titleBottom = secondTop + dp(6);
        canvas.drawText("WARIO LAND", left, titleBottom - title.descent(), title);
    }
}
