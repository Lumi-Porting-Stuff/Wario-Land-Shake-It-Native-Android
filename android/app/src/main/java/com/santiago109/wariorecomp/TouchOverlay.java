package com.santiago109.wariorecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * The on-screen Wii Remote. The game is only ever handed a remote held sideways, so the controls
 * here are the ones that remote has: a D-pad, 1, 2, A, plus and minus, and a shake.
 *
 * <p>The button bits are VirtualRemote::TouchButton (runtime/include/virtual_remote.h) and go
 * straight to GameActivity.nativeSetTouchState. Directions are screen directions, as they are for
 * the keyboard; the engine rotates them for the sideways remote.
 *
 * <p>Touches that miss every control are left unhandled so they reach SDL, which is how the game's
 * own menus and the settings overlay still see taps.
 */
final class TouchOverlay extends View {

    /** VirtualRemote::TouchButton. */
    private static final int LEFT = 0x001;
    private static final int RIGHT = 0x002;
    private static final int UP = 0x004;
    private static final int DOWN = 0x008;
    private static final int ONE = 0x010;
    private static final int TWO = 0x020;
    private static final int A = 0x040;
    private static final int PLUS = 0x080;
    private static final int MINUS = 0x100;

    interface Listener {
        /** Called whenever the held controls change. {@code buttons} is a TouchButton mask. */
        void onTouchState(int buttons, boolean shake);

        /**
         * A touch that hit no control, as fractions of the screen. The engine draws some menus
         * itself and reads them through NetplayUi::Tap, which is what this feeds; the touch is
         * still passed on to SDL as well.
         */
        void onTap(float x, float y);
    }

    /** A round button, or the D-pad, placed in {@link #onSizeChanged}. */
    private static final class Control {
        final String label;
        final int mask;
        final boolean shake;
        final boolean dpad;
        float centerX;
        float centerY;
        float radius;

        Control(String label, int mask, boolean shake, boolean dpad) {
            this.label = label;
            this.mask = mask;
            this.shake = shake;
            this.dpad = dpad;
        }
    }

    private final Control dpad = new Control("", 0, false, true);
    private final Control[] controls = {
            dpad,
            new Control("2", TWO, false, false),
            new Control("1", ONE, false, false),
            new Control("A", A, false, false),
            new Control("SHAKE", 0, true, false),
            new Control("+", PLUS, false, false),
            new Control("−", MINUS, false, false),
    };

    /** Pointer id to the control it is holding, as an index into {@link #controls}. */
    private final SparseIntArray held = new SparseIntArray();
    /** Pointer id to the direction bits it is pressing on the D-pad. */
    private final SparseIntArray directions = new SparseIntArray();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private int buttons;
    private boolean shake;
    private float density;

    TouchOverlay(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);

        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        outline.setColor(Color.argb(140, 255, 255, 255));
        text.setColor(Color.argb(190, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        // Sized against the short edge so the controls stay reachable on a tablet as on a phone.
        float unit = Math.min(width, height);
        float button = Math.max(dp(28), unit * 0.085f);
        float margin = Math.max(dp(16), unit * 0.06f);

        dpad.radius = Math.max(dp(64), unit * 0.20f);
        dpad.centerX = margin + dpad.radius;
        dpad.centerY = height - margin - dpad.radius;

        float clusterX = width - margin - button;
        float clusterY = height - margin - button;

        // 2 is jump and confirm, so it sits under the thumb; 1 (run) and A are within reach of it.
        place(controls[1], clusterX, clusterY, button * 1.25f);
        place(controls[2], clusterX - button * 2.5f, clusterY - button * 0.35f, button);
        place(controls[3], clusterX - button * 1.1f, clusterY - button * 2.1f, button);
        // Shake is the game's own verb, so it gets a button as big as jump, above the cluster.
        place(controls[4], clusterX - button * 3.2f, clusterY - button * 2.4f, button * 1.15f);

        float small = button * 0.62f;
        place(controls[5], width - margin - small, margin + small, small);          // +
        place(controls[6], width - margin - small * 3.2f, margin + small, small);   // -

        text.setTextSize(button * 0.5f);
    }

    private static void place(Control control, float centerX, float centerY, float radius) {
        control.centerX = centerX;
        control.centerY = centerY;
        control.radius = radius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Control control : controls) {
            if (control.dpad) {
                drawDpad(canvas);
            } else {
                boolean pressed = control.shake ? shake : (buttons & control.mask) != 0;
                fill.setColor(pressed ? Color.argb(120, 255, 236, 120) : Color.argb(70, 20, 20, 20));
                canvas.drawCircle(control.centerX, control.centerY, control.radius, fill);
                canvas.drawCircle(control.centerX, control.centerY, control.radius, outline);
                float baseline = control.centerY - (text.ascent() + text.descent()) / 2f;
                // SHAKE is a word, not a glyph: shrink it to fit instead of letting it overflow.
                float size = text.getTextSize();
                if (control.label.length() > 1) {
                    text.setTextSize(size * 0.45f);
                    baseline = control.centerY - (text.ascent() + text.descent()) / 2f;
                }
                canvas.drawText(control.label, control.centerX, baseline, text);
                text.setTextSize(size);
            }
        }
    }

    private void drawDpad(Canvas canvas) {
        float radius = dpad.radius;
        fill.setColor(Color.argb(55, 20, 20, 20));
        canvas.drawCircle(dpad.centerX, dpad.centerY, radius, fill);
        canvas.drawCircle(dpad.centerX, dpad.centerY, radius, outline);

        float arrow = radius * 0.42f;
        drawArrow(canvas, dpad.centerX - arrow, dpad.centerY, LEFT);
        drawArrow(canvas, dpad.centerX + arrow, dpad.centerY, RIGHT);
        drawArrow(canvas, dpad.centerX, dpad.centerY - arrow, UP);
        drawArrow(canvas, dpad.centerX, dpad.centerY + arrow, DOWN);
    }

    private void drawArrow(Canvas canvas, float x, float y, int mask) {
        boolean pressed = (buttons & mask) != 0;
        fill.setColor(pressed ? Color.argb(150, 255, 236, 120) : Color.argb(90, 240, 240, 240));
        canvas.drawCircle(x, y, dpad.radius * 0.20f, fill);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                int id = event.getPointerId(index);
                int control = controlAt(event.getX(index), event.getY(index));
                if (control < 0) {
                    if (listener != null && getWidth() > 0 && getHeight() > 0) {
                        listener.onTap(event.getX(index) / getWidth(), event.getY(index) / getHeight());
                    }
                    return false;  // not ours: let SDL see it too
                }
                held.put(id, control);
                if (controls[control].dpad) {
                    directions.put(id, directionAt(event.getX(index), event.getY(index)));
                }
                publish();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                boolean changed = false;
                for (int index = 0; index < event.getPointerCount(); index++) {
                    int id = event.getPointerId(index);
                    int control = held.get(id, -1);
                    if (control < 0) {
                        continue;
                    }
                    // Sliding across the D-pad changes direction without lifting, as a real one does.
                    if (controls[control].dpad) {
                        int direction = directionAt(event.getX(index), event.getY(index));
                        if (directions.get(id, 0) != direction) {
                            directions.put(id, direction);
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    publish();
                }
                return held.size() > 0;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(event.getActionIndex());
                if (held.indexOfKey(id) < 0) {
                    return false;
                }
                held.delete(id);
                directions.delete(id);
                publish();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                held.clear();
                directions.clear();
                publish();
                return true;
            }
            default:
                return false;
        }
    }

    /** The control under a point, or -1. */
    private int controlAt(float x, float y) {
        for (int i = 0; i < controls.length; i++) {
            Control control = controls[i];
            float dx = x - control.centerX;
            float dy = y - control.centerY;
            // A slightly generous radius: thumbs land off-centre, and a missed press is worse than
            // an early one.
            float reach = control.radius * (control.dpad ? 1.0f : 1.15f);
            if (dx * dx + dy * dy <= reach * reach) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The direction bits for a touch on the D-pad: eight ways, with a dead zone in the middle so
     * resting a thumb there does not push the player.
     */
    private int directionAt(float x, float y) {
        float dx = x - dpad.centerX;
        float dy = y - dpad.centerY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance < dpad.radius * 0.22f) {
            return 0;
        }
        // Each of the eight sectors is 45 degrees wide; a diagonal sets both of its directions.
        double angle = Math.toDegrees(Math.atan2(-dy, dx));
        if (angle < 0) {
            angle += 360;
        }
        int sector = (int) Math.round(angle / 45.0) % 8;
        switch (sector) {
            case 0: return RIGHT;
            case 1: return RIGHT | UP;
            case 2: return UP;
            case 3: return LEFT | UP;
            case 4: return LEFT;
            case 5: return LEFT | DOWN;
            case 6: return DOWN;
            default: return RIGHT | DOWN;
        }
    }

    private void publish() {
        int mask = 0;
        boolean shaking = false;
        for (int i = 0; i < held.size(); i++) {
            int id = held.keyAt(i);
            Control control = controls[held.valueAt(i)];
            if (control.dpad) {
                mask |= directions.get(id, 0);
            } else if (control.shake) {
                shaking = true;
            } else {
                mask |= control.mask;
            }
        }
        if (mask == buttons && shaking == shake) {
            return;
        }
        buttons = mask;
        shake = shaking;
        invalidate();
        if (listener != null) {
            listener.onTouchState(buttons, shake);
        }
    }

    /** The buttons currently held, for the caller that merges them with the phone's motion. */
    int buttons() {
        return buttons;
    }

    /** True while the shake button is held. */
    boolean shaking() {
        return shake;
    }
}
