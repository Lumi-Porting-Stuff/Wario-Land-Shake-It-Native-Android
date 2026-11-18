package com.santiago109.wariorecomp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;

/**
 * The game itself. SDL owns the window and the thread that runs the engine's SDL_main; this class
 * supplies the three things the engine cannot work out for itself inside an APK — where its files
 * are, what the player is pressing, and how the display is configured — through the JNI entry
 * points in runtime/src/android/android_bridge.cpp.
 */
public class GameActivity extends SDLActivity implements TouchOverlay.Listener, MotionSource.Listener {

    private static final String TAG = "WarioRecomp";

    /** How often the engine's own settings window is checked for changes worth saving. */
    private static final long SETTINGS_POLL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TouchOverlay overlay;
    private MotionSource motion;
    private Config config;

    private int touchButtons;
    private boolean touchShake;
    private float motionTilt;
    private boolean motionShake;
    private boolean tapScreenWasUp;

    // ── The engine ──────────────────────────────────────────────────────────────────────────────

    /**
     * SDL3 and Dawn are linked into the engine itself on Android (aurora builds them static when
     * cross-compiling), so there is one library to load rather than SDL's usual two.
     */
    @Override
    protected String[] getLibraries() {
        return new String[]{"main"};
    }

    private static native void nativeSurfaceReady(boolean ready);

    private static native void nativeSetTouchState(int buttons, boolean shake, float tilt);


    private static native void nativeSetDeviceName(String name);

    private static native void nativeTap(float x, float y);

    private static native float[] nativeTakeSettings();

    private static native boolean nativeTapScreen();

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Puts everything the engine reads at load time in place, before SDL loads it.
     *
     * <p>There is no executable directory inside an APK, so the engine takes both of its folders
     * from the environment (runtime/src/platform/host_platform.cpp). They are set from Java rather
     * than through JNI because a JNI call would arrive far too late: the engine asks for
     * Config.toml from a static initialiser, which runs inside System.loadLibrary, and
     * RuntimeConfigFile keeps that first answer for the rest of the run. Set afterwards, the
     * engine would look for its settings somewhere that does not exist, cache an empty config and
     * stop at "No DVD root is configured" with the right path printed in the log.
     *
     * @return null when the game can start, or what went wrong ("" for the plain missing-game case).
     */
    private String prepare() {
        File data = GameData.dataDirectory(this);
        config = new Config(new File(data, "Config.toml"));

        File runtimeDirectory;
        try {
            runtimeDirectory = RuntimeFiles.prepare(this);
        } catch (IOException e) {
            // Without these the engine has no DSP ROM and cannot start, so there is no fallback.
            Log.e(TAG, "could not unpack the runtime files", e);
            return "The app's own files could not be unpacked: " + e.getMessage();
        }

        try {
            Os.setenv("WIICOMPILED_RUNTIME_DIR", runtimeDirectory.getAbsolutePath(), true);
            Os.setenv("WIICOMPILED_DATA_DIR", data.getAbsolutePath(), true);
        } catch (ErrnoException e) {
            Log.e(TAG, "could not set the engine's paths", e);
            return "The engine's folders could not be set: " + e.getMessage();
        }

        File disc = GameData.resolve(this);
        if (disc == null) {
            return "";
        }
        // The engine finds the disc only through dvd_root in Config.toml, so it is recorded here
        // rather than trusting whoever started this activity to have done it: Android restarts the
        // game on its own after killing the process, and a LAN session replaces the process
        // outright. Both skip the launcher.
        if (!disc.getAbsolutePath().equals(config.getString("paths", "dvd_root", ""))) {
            try {
                config.setString("paths", "dvd_root", disc.getAbsolutePath());
                config.save();
            } catch (IOException e) {
                Log.e(TAG, "could not record the game folder", e);
                return "The game folder could not be saved: " + e.getMessage();
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Everything the engine needs in place has to be in place BEFORE super.onCreate(), because
        // that is where SDL loads the engine — and the engine reads Config.toml while its library
        // is still loading, from a static initialiser. Whatever it finds then is cached for the
        // whole run, so settings applied afterwards would simply never be seen.
        String failure = prepare();

        super.onCreate(savedInstanceState);

        // Reported only now: an Activity must call through to super.onCreate, and leaving before
        // it throws SuperNotCalledException on top of whatever actually went wrong.
        if (failure != null) {
            failBackToLauncher(failure.isEmpty() ? null : failure);
            return;
        }

        // The display settings are not pushed in from here: the engine reads them out of
        // Config.toml itself when it boots (main.cpp), and the launcher writes that same file, so
        // sending a second copy of them would only fight it.
        nativeSetDeviceName(Build.MODEL != null ? Build.MODEL : "Android");

        watchSurface();
        addOverlay();

        motion = new MotionSource(this, this);
        motion.setEnabled(config.getBool("gamepad", "wario_motion", true) && motion.available());
        motion.setInverted(config.getBool("gamepad", "wario_motion_invert_roll", false));

        handler.postDelayed(settingsPoll, SETTINGS_POLL_MS);
    }

    /**
     * Tells the engine when there is a surface to draw on.
     *
     * <p>aurora will not prepare or present a frame while it believes there is none, and on Android
     * it starts out believing exactly that. Upstream it is a patched copy of SDL's own
     * SDLSurface.java that reports this; this port builds SDL from its released sources, so the
     * app watches the same surface through the ordinary SurfaceHolder callbacks instead. SDL's own
     * callback was added first and still runs first, so the engine's thread is already started by
     * the time this one says the surface is there.
     */
    private void watchSurface() {
        if (mSurface == null) {
            Log.e(TAG, "SDL has no surface to watch; the game would stay black");
            return;
        }
        mSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                nativeSurfaceReady(true);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                nativeSurfaceReady(true);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // Presenting into a surface Android has taken back is a crash, not a dropped frame.
                nativeSurfaceReady(false);
            }
        });
    }

    /** The overlay goes above SDL's surface, which SDL has already made the content view. */
    private void addOverlay() {
        overlay = new TouchOverlay(this);
        overlay.setListener(this);
        addContentView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (motion != null) {
            motion.start();
        }
    }

    @Override
    protected void onPause() {
        if (motion != null) {
            motion.stop();
        }
        // A phone that is put down mid-level should not leave Wario running into a wall.
        touchButtons = 0;
        touchShake = false;
        sendInput();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(settingsPoll);
        super.onDestroy();
    }

    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    @Override
    public void onTouchState(int buttons, boolean shake) {
        touchButtons = buttons;
        touchShake = shake;
        sendInput();
    }

    @Override
    public void onTap(float x, float y) {
        nativeTap(x, y);
    }

    @Override
    public void onMotion(float tilt, boolean shake) {
        motionTilt = tilt;
        motionShake = shake;
        sendInput();
    }

    /**
     * One state for the engine, from both sources at once. The on-screen shake button and shaking
     * the phone mean the same thing, so either is enough; this mirrors how the PC build lets the
     * DualSense's sensors and its sticks drive the same remote.
     */
    private void sendInput() {
        boolean shake = touchShake || motionShake;
        float tilt = motion != null && motion.isEnabled() ? motionTilt : 0f;
        nativeSetTouchState(touchButtons, shake, tilt);
    }

    // ── The engine's own settings window ────────────────────────────────────────────────────────

    /**
     * The settings the player changes inside the game are the engine's, not the app's, so they are
     * read back and saved here to survive the next start.
     */
    private final Runnable settingsPoll = new Runnable() {
        @Override
        public void run() {
            float[] changed = nativeTakeSettings();
            if (changed != null && changed.length >= 5) {
                // Into Config.toml, the same file the launcher edits and the engine reads at boot,
                // so a change made in the game's own settings window shows up in the launcher and
                // survives the next start.
                try {
                    config.reload();
                    config.setDouble("video", "resolution_multiplier", changed[0]);
                    config.setBool("video", "show_fps", changed[1] != 0f);
                    config.setInt("video", "aspect_mode", (int) changed[2]);
                    config.setBool("video", "frame_graph", changed[4] != 0f);
                    config.save();
                } catch (IOException e) {
                    Log.w(TAG, "could not save the settings changed in game", e);
                }
            }

            // Menus the engine draws itself are driven by taps, so the controls step out of the way.
            boolean tapScreenUp = nativeTapScreen();
            if (tapScreenUp != tapScreenWasUp) {
                tapScreenWasUp = tapScreenUp;
                if (overlay != null) {
                    overlay.setVisibility(tapScreenUp ? android.view.View.GONE
                            : android.view.View.VISIBLE);
                }
                if (tapScreenUp) {
                    touchButtons = 0;
                    touchShake = false;
                    sendInput();
                }
            }

            handler.postDelayed(this, SETTINGS_POLL_MS);
        }
    };

    // ── Network play ────────────────────────────────────────────────────────────────────────────

    /**
     * Called from AndroidBridge::RestartGame. A LAN session has to begin with the engine's state
     * untouched, and the engine cannot unwind itself, so the process is replaced: the parked native
     * thread ends with it.
     */
    @SuppressWarnings("unused")  // called through JNI
    public void restartForSession() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, GameActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            Runtime.getRuntime().exit(0);
        });
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────────────

    /** Hands the problem back to the launcher, which can explain it and offer the import. */
    private void failBackToLauncher(String message) {
        Intent intent = new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (message != null) {
            intent.putExtra(LauncherActivity.EXTRA_MESSAGE, message);
        }
        startActivity(intent);
        finish();
        // SDL's thread would otherwise start the engine with no disc and no files to read.
        Runtime.getRuntime().exit(0);
    }
}
