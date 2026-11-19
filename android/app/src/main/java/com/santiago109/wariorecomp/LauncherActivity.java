package com.santiago109.wariorecomp;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The launcher: the PC one's pages, on a phone.
 *
 * <p>A port of launcher-avalonia/UI/MainWindow.cs. It keeps that launcher's layout — the list of
 * pages down the left, the artwork at the head of Home, the gold PLAY button in the corner — along
 * with its wording and its colours, and it writes the same Config.toml keys. The engine reads them
 * identically here, so a setting changed in either launcher means the same thing.
 *
 * <p>What is deliberately missing is everything about building the port: on a PC the launcher runs
 * the translator and the compiler, and a phone can do neither. The APK already carries the
 * compiled game code, so Install becomes Game data: find the disc, check it, record where it is.
 */
public class LauncherActivity extends AppCompatActivity {

    /** Set by GameActivity when it has to come back here, holding what went wrong. */
    static final String EXTRA_MESSAGE = "message";

    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_READ_STORAGE = 2;
    private static final int REQUEST_PICK_IMAGE = 3;

    /** The pages, in the PC launcher's order. */
    private enum Tab {
        HOME(R.string.tab_home),
        DISPLAY(R.string.tab_display),
        GRAPHICS(R.string.tab_graphics),
        AUDIO(R.string.tab_audio),
        GAME(R.string.tab_game),
        CONTROLS(R.string.tab_controls),
        ADVANCED(R.string.tab_advanced),
        DATA(R.string.tab_data),
        LOG(R.string.tab_log);

        final int title;

        Tab(int title) {
            this.title = title;
        }
    }

    private Config config;
    private Tab tab = Tab.HOME;
    private String notice;

    private LinearLayout navList;
    private ScrollView pageHost;
    private Button playButton;
    private TextView summary;

    private DiscImport currentImport;
    private DiscExtract currentExtract;
    private Thread importThread;
    private ProgressBar importProgress;
    private TextView importStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new Config(new File(GameData.dataDirectory(this), "Config.toml"));
        notice = getIntent() != null ? getIntent().getStringExtra(EXTRA_MESSAGE) : null;
        setContentView(buildShell());
        showTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (importThread == null) {
            // Coming back from Settings may have granted all-files access, which changes everything
            // the pages say about where the game is.
            config.reload();
            showTab(tab);
        }
    }

    @Override
    protected void onDestroy() {
        // The copy holds an open descriptor on a folder the user may be about to revoke.
        if (currentImport != null) {
            currentImport.cancel();
        }
        // And the extraction holds one on the image itself, for the same reason.
        if (currentExtract != null) {
            currentExtract.cancel();
        }
        super.onDestroy();
    }

    // ── The frame around the pages ──────────────────────────────────────────────────────────────

    /** Pages down the left, the current one on the right, PLAY in the corner: the PC layout. */
    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(color(R.color.wario_back));
        root.setFitsSystemWindows(true);

        // On PC the page list takes about a fifth of the window. Held upright a phone is far
        // narrower, so the same 156dp would eat well over a third of it and leave the settings in a
        // column too thin to read; it gives way rather than squeezing them.
        int widthPx = getResources().getDisplayMetrics().widthPixels;
        int sidebar = Math.max(dp(116), Math.min(dp(156), Math.round(widthPx * 0.28f)));
        root.addView(buildSidebar(), new LinearLayout.LayoutParams(
                sidebar, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        pageHost = new ScrollView(this);
        right.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        right.addView(buildBottomBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private View buildSidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(color(R.color.wario_side));

        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        TextView name = new TextView(this);
        name.setText(R.string.game_title_two_lines);
        name.setTypeface(black);
        name.setTextSize(17f);
        name.setLineSpacing(0f, 1.05f);
        name.setTextColor(color(R.color.wario_gold));
        name.setPadding(dp(16), dp(18), dp(12), dp(16));
        side.addView(name);

        navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.addView(navList);
        side.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText(R.string.sidebar_hint);
        hint.setTextSize(10.5f);
        hint.setTextColor(color(R.color.wario_muted));
        hint.setPadding(dp(16), dp(10), dp(12), dp(14));
        side.addView(hint);
        return side;
    }

    /** The status summary and PLAY, along the bottom as on PC. */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(color(R.color.wario_side));

        summary = new TextView(this);
        summary.setTextSize(12f);
        summary.setTextColor(color(R.color.wario_muted));
        summary.setPadding(dp(18), dp(10), dp(12), dp(10));
        // One line, cut short if it has to be: wrapped into a narrow column beside PLAY it is
        // unreadable, and it is only a reminder of settings the pages state properly.
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(summary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        playButton = new Button(this);
        playButton.setText(R.string.play_button);
        playButton.setTextSize(19f);
        playButton.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        playButton.setTextColor(color(R.color.wario_dark_text));
        playButton.setBackgroundResource(R.drawable.bg_play);
        playButton.setOnClickListener(v -> play());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(150), dp(58));
        params.setMargins(0, dp(6), dp(10), dp(6));
        bar.addView(playButton, params);
        return bar;
    }

    private void showTab(Tab wanted) {
        tab = wanted;
        rebuildNav();

        Page page = new Page(this);
        switch (tab) {
            case HOME: buildHome(page); break;
            case DISPLAY: buildDisplay(page); break;
            case GRAPHICS: buildGraphics(page); break;
            case AUDIO: buildAudio(page); break;
            case GAME: buildGame(page); break;
            case CONTROLS: buildControls(page); break;
            case ADVANCED: buildAdvanced(page); break;
            case DATA: buildData(page); break;
            default: buildLog(page); break;
        }

        // The artwork runs edge to edge, so it sits outside the page's own margins.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        if (tab == Tab.HOME) {
            column.addView(new BannerView(this, getString(R.string.banner_subtitle)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        View content = page.view();
        content.setPadding(dp(22), 0, dp(22), dp(20));
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageHost.removeAllViews();
        pageHost.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageHost.scrollTo(0, 0);
        updateBottomBar();
    }

    private void rebuildNav() {
        navList.removeAllViews();
        for (Tab item : Tab.values()) {
            boolean current = item == tab;
            TextView entry = new TextView(this);
            entry.setText(item.title);
            entry.setTextSize(14f);
            entry.setPadding(dp(16), dp(12), dp(10), dp(12));
            entry.setTextColor(color(current ? R.color.wario_gold : R.color.wario_text));
            entry.setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            // The current page is marked by a gold edge and a lighter strip, as on PC.
            entry.setBackgroundResource(current ? R.drawable.bg_nav_selected : 0);
            entry.setClickable(true);
            entry.setOnClickListener(v -> showTab(item));
            navList.addView(entry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** "1080p · Fill the screen · full intro", and whether PLAY can do anything. */
    private void updateBottomBar() {
        File found = GameData.resolve(this);
        playButton.setEnabled(found != null);

        String resolution = labelFor(scaleChoices(), currentScale());
        String aspect = labelFor(aspectChoices(), config.getInt("video", "aspect_mode", 2));
        String intro = getString(config.getBool("game", "short_intro", false)
                ? R.string.summary_short_intro : R.string.summary_full_intro);
        summary.setText(resolution + "  ·  " + aspect + "  ·  " + intro);
    }

    private static String labelFor(List<Page.Choice> choices, Object value) {
        for (Page.Choice choice : choices) {
            if (choice.value.equals(value)) {
                return choice.label;
            }
        }
        return String.valueOf(value);
    }

    // ── Settings, saved as the PC launcher saves them ───────────────────────────────────────────

    /** Every change is written at once, so closing the app never loses a setting. */
    private void write(Runnable change) {
        change.run();
        try {
            config.save();
        } catch (IOException e) {
            toast(getString(R.string.error_saving_settings, e.getMessage()));
        }
        updateBottomBar();
    }

    private static List<Page.Choice> choices(Page.Choice... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private List<Page.Choice> aspectChoices() {
        return choices(
                new Page.Choice(getString(R.string.aspect_4_3), 0),
                new Page.Choice(getString(R.string.aspect_16_9), 1),
                new Page.Choice(getString(R.string.aspect_fill), 2),
                new Page.Choice(getString(R.string.aspect_cinematic), 3));
    }

    /** The render scale in lines, as the launcher presents it: 480p per 1x. */
    private List<Page.Choice> scaleChoices() {
        return choices(
                new Page.Choice(getString(R.string.res_480), 1.0),
                new Page.Choice(getString(R.string.res_720), 1.5),
                new Page.Choice(getString(R.string.res_1080), 2.25),
                new Page.Choice(getString(R.string.res_1440), 3.0));
    }

    private Object currentScale() {
        double value = config.getDouble("video", "resolution_multiplier", 2.25);
        // Matched to the nearest offered step, so a value set on a PC still shows as something.
        double[] steps = {1.0, 1.5, 2.25, 3.0};
        double best = steps[0];
        for (double step : steps) {
            if (Math.abs(step - value) < Math.abs(best - value)) {
                best = step;
            }
        }
        return best;
    }

    // ── Home ────────────────────────────────────────────────────────────────────────────────────

    private void buildHome(Page page) {
        if (notice != null) {
            page.info(notice, R.color.wario_bad);
        }

        page.section(getString(R.string.quick_settings));
        page.row(getString(R.string.render_resolution), scaleChoices(), currentScale(),
                v -> write(() -> config.setDouble("video", "resolution_multiplier", (Double) v)), null);
        page.row(getString(R.string.aspect_ratio), aspectChoices(),
                config.getInt("video", "aspect_mode", 2),
                v -> write(() -> config.setInt("video", "aspect_mode", (Integer) v)), null);
        page.row(getString(R.string.intro), choices(
                        new Page.Choice(getString(R.string.intro_full), false),
                        new Page.Choice(getString(R.string.intro_short), true)),
                config.getBool("game", "short_intro", false),
                v -> write(() -> config.setBool("game", "short_intro", (Boolean) v)), null);
        page.check(getString(R.string.full_widescreen),
                config.getBool("video", "full_widescreen", false),
                v -> write(() -> config.setBool("video", "full_widescreen", v)),
                getString(R.string.full_widescreen_help));

        page.section(getString(R.string.status));
        File found = GameData.resolve(this);
        if (found != null) {
            page.info("✔ " + getString(R.string.status_ready, found.getAbsolutePath()),
                    R.color.wario_good);
        } else {
            page.info("✖ " + getString(R.string.status_missing), R.color.wario_bad);
            page.info(getString(R.string.home_go_to_data), R.color.wario_muted);
            page.buttons(new String[]{getString(R.string.tab_data)},
                    new Runnable[]{() -> showTab(Tab.DATA)});
        }

        page.section(getString(R.string.in_game));
        page.info(getString(R.string.in_game_help), R.color.wario_muted);
    }

    // ── Display ─────────────────────────────────────────────────────────────────────────────────

    private void buildDisplay(Page page) {
        page.header(getString(R.string.tab_display), getString(R.string.display_subtitle));

        page.section(getString(R.string.aspect_ratio));
        page.row(getString(R.string.aspect_ratio), aspectChoices(),
                config.getInt("video", "aspect_mode", 2),
                v -> write(() -> config.setInt("video", "aspect_mode", (Integer) v)),
                getString(R.string.aspect_help));
        page.check(getString(R.string.full_widescreen),
                config.getBool("video", "full_widescreen", false),
                v -> write(() -> config.setBool("video", "full_widescreen", v)),
                getString(R.string.full_widescreen_help));

        page.section(getString(R.string.overlay));
        page.check(getString(R.string.show_fps), config.getBool("video", "show_fps", false),
                v -> write(() -> config.setBool("video", "show_fps", v)), null);
        page.check(getString(R.string.frame_graph), config.getBool("video", "frame_graph", false),
                v -> write(() -> config.setBool("video", "frame_graph", v)),
                getString(R.string.frame_graph_help));

        page.section(getString(R.string.vsync));
        page.check(getString(R.string.vsync), config.getBool("video", "vsync", true),
                v -> write(() -> config.setBool("video", "vsync", v)),
                getString(R.string.vsync_help_android));
    }

    // ── Graphics ────────────────────────────────────────────────────────────────────────────────

    private void buildGraphics(Page page) {
        page.header(getString(R.string.tab_graphics), getString(R.string.graphics_subtitle));

        page.section(getString(R.string.resolution));
        page.row(getString(R.string.render_resolution), scaleChoices(), currentScale(),
                v -> write(() -> config.setDouble("video", "resolution_multiplier", (Double) v)),
                getString(R.string.render_resolution_help_android));
        page.check(getString(R.string.dynamic_resolution),
                config.getBool("video", "dynamic_resolution", true),
                v -> write(() -> config.setBool("video", "dynamic_resolution", v)),
                getString(R.string.dynamic_resolution_help));

        page.section(getString(R.string.renderer));
        page.info(getString(R.string.renderer_vulkan_only), R.color.wario_muted);
        page.row(getString(R.string.gpu_compat), choices(
                        new Page.Choice(getString(R.string.automatic), 0),
                        new Page.Choice(getString(R.string.on), 1),
                        new Page.Choice(getString(R.string.off), 2)),
                config.getInt("video", "gpu_compat", 0),
                v -> write(() -> config.setInt("video", "gpu_compat", (Integer) v)),
                getString(R.string.gpu_compat_help));

        page.section(getString(R.string.frame_interpolation));
        page.row(getString(R.string.frame_interpolation), choices(
                        new Page.Choice(getString(R.string.off), 0),
                        new Page.Choice("120 FPS", 120),
                        new Page.Choice("180 FPS", 180),
                        new Page.Choice("240 FPS", 240)),
                config.getInt("video", "frame_interpolation_fps", 0),
                v -> write(() -> config.setInt("video", "frame_interpolation_fps", (Integer) v)),
                getString(R.string.frame_interpolation_help_android));
    }

    // ── Audio ───────────────────────────────────────────────────────────────────────────────────

    private void buildAudio(Page page) {
        page.header(getString(R.string.tab_audio), getString(R.string.audio_subtitle));

        page.section(getString(R.string.volume));
        int volume = (int) Math.round(config.getDouble("audio", "volume", 1.0) * 100);
        page.slider(getString(R.string.volume), Math.max(0, Math.min(100, volume)),
                v -> write(() -> config.setDouble("audio", "volume", v / 100.0)));
        page.check(getString(R.string.muted), config.getBool("audio", "muted", false),
                v -> write(() -> config.setBool("audio", "muted", v)), null);

        page.section(getString(R.string.performance));
        page.check(getString(R.string.mix_worker), config.getBool("audio", "mix_worker", true),
                v -> write(() -> config.setBool("audio", "mix_worker", v)),
                getString(R.string.mix_worker_help));
    }

    // ── Game ────────────────────────────────────────────────────────────────────────────────────

    private void buildGame(Page page) {
        page.header(getString(R.string.tab_game), getString(R.string.game_subtitle));

        page.section(getString(R.string.language));
        page.row(getString(R.string.game_language), choices(
                        new Page.Choice(getString(R.string.automatic), ""),
                        new Page.Choice("English", "en"),
                        new Page.Choice("Espanol", "es"),
                        new Page.Choice("Francais", "fr")),
                config.getString("game", "language", ""),
                v -> write(() -> config.setString("game", "language", (String) v)),
                getString(R.string.game_language_help));

        page.section(getString(R.string.intro));
        page.row(getString(R.string.intro), choices(
                        new Page.Choice(getString(R.string.intro_full), false),
                        new Page.Choice(getString(R.string.intro_short), true)),
                config.getBool("game", "short_intro", false),
                v -> write(() -> config.setBool("game", "short_intro", (Boolean) v)), null);
    }

    // ── Controls ────────────────────────────────────────────────────────────────────────────────

    private void buildControls(Page page) {
        page.header(getString(R.string.tab_controls), getString(R.string.controls_subtitle));

        page.section(getString(R.string.on_screen));
        page.info(getString(R.string.controls_help), R.color.wario_muted);

        page.section(getString(R.string.phone_motion));
        page.check(getString(R.string.motion_enabled),
                config.getBool("gamepad", "wario_motion", true),
                v -> write(() -> config.setBool("gamepad", "wario_motion", v)),
                getString(R.string.motion_enabled_help));
        page.check(getString(R.string.motion_invert_roll),
                config.getBool("gamepad", "wario_motion_invert_roll", false),
                v -> write(() -> config.setBool("gamepad", "wario_motion_invert_roll", v)), null);

        page.section(getString(R.string.gamepad));
        page.check(getString(R.string.left_stick_tilt),
                config.getBool("gamepad", "wario_left_stick", true),
                v -> write(() -> config.setBool("gamepad", "wario_left_stick", v)),
                getString(R.string.left_stick_tilt_help));
        page.check(getString(R.string.rumble), config.getBool("controller", "rumble", true),
                v -> write(() -> config.setBool("controller", "rumble", v)), null);
    }

    // ── Advanced ────────────────────────────────────────────────────────────────────────────────

    private void buildAdvanced(Page page) {
        page.header(getString(R.string.tab_advanced), getString(R.string.advanced_subtitle));

        page.section(getString(R.string.textures));
        page.check(getString(R.string.texture_replacements),
                config.getBool("video", "texture_replacements", false),
                v -> write(() -> config.setBool("video", "texture_replacements", v)),
                getString(R.string.texture_replacements_help));
        page.check(getString(R.string.texture_dumps),
                config.getBool("video", "texture_dumps", false),
                v -> write(() -> config.setBool("video", "texture_dumps", v)),
                getString(R.string.texture_dumps_help));

        page.section(getString(R.string.compatibility));
        page.check(getString(R.string.skip_unready_pipelines),
                config.getBool("video", "skip_unready_pipelines", true),
                v -> write(() -> config.setBool("video", "skip_unready_pipelines", v)),
                getString(R.string.skip_unready_pipelines_help));
        page.check(getString(R.string.disable_copy_filter),
                config.getBool("video", "disable_copy_filter", false),
                v -> write(() -> config.setBool("video", "disable_copy_filter", v)),
                getString(R.string.disable_copy_filter_help));

        page.section(getString(R.string.folders));
        page.info(getString(R.string.data_folder_is, GameData.dataDirectory(this).getAbsolutePath()),
                R.color.wario_muted);
    }

    // ── Game data (the PC launcher's Install, minus everything a phone cannot compile) ──────────

    private void buildData(Page page) {
        page.header(getString(R.string.tab_data), getString(R.string.data_subtitle));

        File found = GameData.resolve(this);
        boolean access = GameData.hasSharedAccess(this);
        File shared = GameData.sharedRoot();

        page.section(getString(R.string.status));
        if (found != null) {
            page.info("✔ " + getString(R.string.status_ready, found.getAbsolutePath()),
                    R.color.wario_good);
        } else {
            String problem = GameData.problem(shared);
            page.info("✖ " + (problem == null ? getString(R.string.status_missing) : problem),
                    R.color.wario_bad);
        }

        page.section(getString(R.string.where_it_goes));
        page.info(shared.getAbsolutePath(), R.color.wario_text);
        if (!access) {
            page.info(getString(R.string.data_needs_access), R.color.wario_muted);
            page.buttons(new String[]{getString(R.string.grant_access)},
                    new Runnable[]{this::requestSharedAccess});
        }

        page.section(getString(R.string.extracting));
        page.info(getString(R.string.data_extract_help), R.color.wario_muted);
        page.buttons(new String[]{getString(R.string.extract_button)},
                new Runnable[]{this::pickImage});

        page.section(getString(R.string.importing));
        page.info(getString(R.string.data_import_help,
                GameData.privateRoot(this).getAbsolutePath()), R.color.wario_muted);

        importStatus = page.info("", R.color.wario_muted);
        importStatus.setVisibility(View.GONE);
        importProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importProgress.setVisibility(View.GONE);
        page.add(importProgress);

        page.buttons(new String[]{getString(R.string.import_button)},
                new Runnable[]{this::pickFolder});
    }

    // ── Log ─────────────────────────────────────────────────────────────────────────────────────

    private void buildLog(Page page) {
        page.header(getString(R.string.tab_log), getString(R.string.log_subtitle));

        File newest = newestLog();
        if (newest == null) {
            page.info(getString(R.string.log_none), R.color.wario_muted);
            return;
        }
        page.info(newest.getName(), R.color.wario_text);

        TextView text = new TextView(this);
        text.setTextColor(color(R.color.wario_text));
        text.setTextSize(11f);
        text.setTypeface(Typeface.MONOSPACE);
        text.setBackgroundColor(color(R.color.wario_log_back));
        text.setPadding(dp(10), dp(10), dp(10), dp(10));
        text.setHorizontallyScrolling(true);
        text.setText(tail(newest));
        page.add(text);
    }

    /** The log of the most recent run, which is the one worth reading after a crash. */
    private File newestLog() {
        File logs = new File(GameData.dataDirectory(this), "Logs");
        File[] runs = logs.listFiles();
        if (runs == null || runs.length == 0) {
            return null;
        }
        File newest = null;
        for (File run : runs) {
            File console = run.isDirectory() ? new File(run, "console.log") : run;
            if (!console.isFile()) {
                continue;
            }
            if (newest == null || console.lastModified() > newest.lastModified()) {
                newest = console;
            }
        }
        return newest;
    }

    /** The last part of a log: the whole thing can be megabytes, and the end is what matters. */
    private String tail(File file) {
        final int limit = 128 * 1024;
        try (java.io.RandomAccessFile reader = new java.io.RandomAccessFile(file, "r")) {
            long length = reader.length();
            long from = Math.max(0, length - limit);
            reader.seek(from);
            byte[] bytes = new byte[(int) Math.min(limit, length - from)];
            reader.readFully(bytes);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return from > 0 ? getString(R.string.log_truncated) + "\n\n" + text : text;
        } catch (IOException e) {
            return getString(R.string.log_unreadable, e.getMessage());
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────────────

    private void play() {
        File root = GameData.resolve(this);
        if (root == null) {
            showTab(Tab.DATA);
            return;
        }
        // A folder copied onto the phone has never been recorded in the engine's settings, so this
        // runs every time rather than only after an import.
        try {
            config.reload();
            config.setString("paths", "dvd_root", root.getAbsolutePath());
            config.save();
        } catch (IOException e) {
            toast(getString(R.string.error_saving_folder, e.getMessage()));
            return;
        }
        notice = null;
        startActivity(new Intent(this, GameActivity.class));
    }

    /**
     * Opens the all-files access screen in Settings. Android grants this only there, never from a
     * dialog, so the app can do no more than send the user to it.
     */
    private void requestSharedAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null)));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // Some builds have no per-app screen; the list of every app is the fallback.
            }
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (android.content.ActivityNotFoundException e) {
                toast(getString(R.string.error_no_access_screen));
            }
        } else {
            requestPermissions(
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_READ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_READ_STORAGE) {
            showTab(tab);
        }
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    /**
     * The disc image itself, which {@link DiscExtract} reads where it lies.
     *
     * <p>Asks for every type rather than a list: a .iso or .rvz has no registered MIME type, and
     * providers that guess one disagree with each other. A file that is not a disc image fails in
     * the extractor with something to read, which is better than being unselectable for reasons
     * the player cannot see.
     */
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_FOLDER) {
            startImport(data.getData());
        } else if (requestCode == REQUEST_PICK_IMAGE) {
            startExtract(data.getData());
        }
    }

    private void startExtract(Uri image) {
        if (importProgress == null || importStatus == null) {
            return;
        }
        importProgress.setVisibility(View.VISIBLE);
        importProgress.setIndeterminate(true);
        importStatus.setVisibility(View.VISIBLE);
        importStatus.setText(R.string.extract_reading);

        DiscExtract extractor = new DiscExtract(this);
        currentExtract = extractor;
        importThread = new Thread(() -> {
            String problem = extractor.run(image, new DiscExtract.Listener() {
                @Override
                public void onProgress(long done, long total, String file) {
                    showImportProgress(done, total, file);
                }

                @Override
                public boolean isCancelled() {
                    return extractor.wasCancelled();
                }
            });
            runOnUiThread(() -> {
                importThread = null;
                currentExtract = null;
                showTab(Tab.DATA);
                if (problem != null) {
                    report(problem);
                }
            });
        }, "disc-extract");
        importThread.start();
    }

    private void startImport(Uri tree) {
        if (importProgress == null || importStatus == null) {
            return;
        }
        importProgress.setVisibility(View.VISIBLE);
        importProgress.setIndeterminate(true);
        importStatus.setVisibility(View.VISIBLE);
        importStatus.setText(R.string.import_counting);

        DiscImport importer = new DiscImport(this);
        currentImport = importer;
        importThread = new Thread(() -> {
            String problem = importer.run(tree, this::showImportProgress);
            runOnUiThread(() -> {
                importThread = null;
                currentImport = null;
                showTab(Tab.DATA);
                if (problem != null) {
                    report(problem);
                }
            });
        }, "disc-import");
        importThread.start();
    }

    private void showImportProgress(long copied, long total, String file) {
        runOnUiThread(() -> {
            if (importProgress == null || importStatus == null) {
                return;
            }
            if (total > 0) {
                importProgress.setIndeterminate(false);
                importProgress.setMax(1000);
                importProgress.setProgress((int) Math.min(1000, copied * 1000 / total));
            }
            importStatus.setText(getString(R.string.import_progress,
                    copied / (1024f * 1024f), total / (1024f * 1024f), file));
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * A failure the player has to be able to read. A toast shows two lines and is gone in a few
     * seconds, which cuts a path and its reason down to the part that says nothing.
     */
    private void report(String message) {
        android.util.Log.e("WarioRecomp", message);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
