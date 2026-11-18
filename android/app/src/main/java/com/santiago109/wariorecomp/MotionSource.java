package com.santiago109.wariorecomp;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * The phone itself as a sideways Wii Remote: rotate it to tilt, shake it to shake.
 *
 * <p>This is the same idea as the DualSense accelerometer the PC build already supports, and it
 * shares that build's rule that the on-screen controls keep working at the same time: the engine is
 * handed whichever of the two is actually being used (see GameActivity).
 */
final class MotionSource implements SensorEventListener {

    interface Listener {
        /** @param tilt -1 counter-clockwise to 1 clockwise, as VirtualRemote::SetTouchState wants. */
        void onMotion(float tilt, boolean shake);
    }

    /** How far the phone must be rotated for full tilt. The Wii Remote's own range is about this. */
    private static final float FULL_TILT_DEGREES = 45f;
    /** Rotation smaller than this is a hand that is not quite level, not an intent to tilt. */
    private static final float TILT_DEAD_ZONE_DEGREES = 7f;
    /** Acceleration beyond gravity, in m/s^2, that counts as shaking rather than moving. */
    private static final float SHAKE_THRESHOLD = 6.0f;
    /** A rhythmic shake has quiet moments at the ends of each stroke; hold through them. */
    private static final long SHAKE_HOLD_MS = 220;

    /** The activity, so the display it is shown on can be asked how it is turned. */
    private final Context context;
    private final SensorManager sensors;
    private final WindowManager windows;
    private final Sensor accelerometer;
    private final Listener listener;

    /** Gravity, low-pass filtered out of the accelerometer; the remainder is the player's motion. */
    private final float[] gravity = new float[3];
    private boolean haveGravity;
    private long shakeUntil;

    private boolean enabled = true;
    private boolean inverted;
    private boolean registered;
    private float reportedTilt;
    private boolean reportedShake;

    MotionSource(Context context, Listener listener) {
        this.context = context;
        this.sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.accelerometer = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        this.listener = listener;
    }

    /** True when this phone can report motion at all. */
    boolean available() {
        return accelerometer != null;
    }

    /** Turned off from the game's settings; the on-screen controls are then the only input. */
    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (!enabled) {
            haveGravity = false;
            shakeUntil = 0;
            report(0f, false);
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Mirrors the tilt, for players who expect the opposite direction (gamepad.wario_motion_invert_roll). */
    void setInverted(boolean inverted) {
        this.inverted = inverted;
    }

    void start() {
        if (registered || accelerometer == null || sensors == null) {
            return;
        }
        // SENSOR_DELAY_GAME is about 50 Hz, which is close enough to the game's 60 frames that a
        // faster rate would only cost battery.
        registered = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (!registered || sensors == null) {
            return;
        }
        sensors.unregisterListener(this);
        registered = false;
        haveGravity = false;
        shakeUntil = 0;
        report(0f, false);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!enabled) {
            return;
        }
        float[] values = event.values;
        if (!haveGravity) {
            System.arraycopy(values, 0, gravity, 0, 3);
            haveGravity = true;
        } else {
            // A gentle filter: steady enough to read as tilt, quick enough to follow a real turn.
            final float alpha = 0.85f;
            for (int axis = 0; axis < 3; axis++) {
                gravity[axis] = alpha * gravity[axis] + (1 - alpha) * values[axis];
            }
        }

        float motionX = values[0] - gravity[0];
        float motionY = values[1] - gravity[1];
        float motionZ = values[2] - gravity[2];
        double motion = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ);
        long now = System.currentTimeMillis();
        if (motion >= SHAKE_THRESHOLD) {
            shakeUntil = now + SHAKE_HOLD_MS;
        }

        report(tiltFrom(gravity), now < shakeUntil);
    }

    /**
     * How far the phone is rotated about the axis coming out of the screen, as -1..1.
     *
     * <p>Gravity is first moved from the device's own axes into the screen's, because the sensor
     * does not turn when the display does; a phone that opens the game in reverse landscape would
     * otherwise tilt the wrong way.
     */
    private float tiltFrom(float[] deviceGravity) {
        int rotation = displayRotation();
        float x = deviceGravity[0];
        float y = deviceGravity[1];
        float screenX;
        float screenY;
        switch (rotation) {
            case Surface.ROTATION_90:
                screenX = -y;
                screenY = x;
                break;
            case Surface.ROTATION_180:
                screenX = -x;
                screenY = -y;
                break;
            case Surface.ROTATION_270:
                screenX = y;
                screenY = -x;
                break;
            default:
                screenX = x;
                screenY = y;
                break;
        }

        // Held normally the screen's "down" is where gravity points, so this angle is zero; turning
        // the phone clockwise makes it positive, which is the direction the engine calls 1.
        double degrees = Math.toDegrees(Math.atan2(screenX, -screenY));
        double magnitude = Math.abs(degrees);
        if (magnitude <= TILT_DEAD_ZONE_DEGREES) {
            return 0f;
        }
        // Measured from the edge of the dead zone, so tilt starts at zero rather than jumping.
        double scaled = (magnitude - TILT_DEAD_ZONE_DEGREES)
                / (FULL_TILT_DEGREES - TILT_DEAD_ZONE_DEGREES);
        float tilt = (float) Math.min(1.0, scaled);
        boolean negative = degrees < 0;
        if (inverted) {
            negative = !negative;
        }
        return negative ? -tilt : tilt;
    }

    /** How the display is turned relative to the phone's natural orientation. */
    @SuppressWarnings("deprecation")  // getDefaultDisplay is the only way below API 30
    private int displayRotation() {
        android.view.Display display = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display = context.getDisplay();
        } else if (windows != null) {
            display = windows.getDefaultDisplay();
        }
        return display != null ? display.getRotation() : Surface.ROTATION_0;
    }

    private void report(float tilt, boolean shake) {
        // Rounded, so a hand that is merely not still does not wake the game thread every sample.
        float quantised = Math.round(tilt * 50f) / 50f;
        if (quantised == reportedTilt && shake == reportedShake) {
            return;
        }
        reportedTilt = quantised;
        reportedShake = shake;
        listener.onMotion(quantised, shake);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
