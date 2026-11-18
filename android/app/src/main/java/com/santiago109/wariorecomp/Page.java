package com.santiago109.wariorecomp;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Builds a settings page the way the PC launcher does, with the same pieces in the same order:
 * a header, sections, and rows of a label, a control and a line of help.
 *
 * <p>A port of launcher-avalonia/UI/Page.cs. Keeping the shape of that file means the two launchers
 * stay recognisably the same program, and a setting added to one is easy to add to the other.
 */
final class Page {

    /** An entry in a dropdown: what the user reads, and what gets written to Config.toml. */
    static final class Choice {
        final String label;
        final Object value;

        Choice(String label, Object value) {
            this.label = label;
            this.value = value;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    interface OnChoice {
        void onChoice(Object value);
    }

    interface OnBool {
        void onBool(boolean value);
    }

    interface OnInt {
        void onInt(int value);
    }

    private final Context context;
    private final LinearLayout panel;
    private final float density;

    Page(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
    }

    /** The view to put on screen. */
    View view() {
        return panel;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private int color(int id) {
        return ContextCompat.getColor(context, id);
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    void add(View child) {
        panel.addView(child, wide());
    }

    // ── Text ────────────────────────────────────────────────────────────────────────────────────

    TextView label(String text, int colorId, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color(colorId));
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    /** The page title and the one line that says what the page is for. */
    void header(String title, String subtitle) {
        TextView name = label(title, R.color.wario_gold, 22f, true);
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(20);
        panel.addView(name, params);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView under = label(subtitle, R.color.wario_muted, 13f, false);
            LinearLayout.LayoutParams subParams = wide();
            subParams.topMargin = dp(2);
            subParams.bottomMargin = dp(8);
            panel.addView(under, subParams);
        }
    }

    /** A group of related settings. Everything added after it goes inside, until the next one. */
    void section(String name) {
        TextView view = label(name.toUpperCase(java.util.Locale.ROOT), R.color.wario_muted, 11.5f, true);
        view.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(22);
        params.bottomMargin = dp(6);
        panel.addView(view, params);
    }

    /** A note under a control, or a status line when a colour is given. */
    TextView info(String text, int colorId) {
        TextView view = label(text, colorId, 12.5f, false);
        view.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(4);
        panel.addView(view, params);
        return view;
    }

    // ── Controls ────────────────────────────────────────────────────────────────────────────────

    /**
     * A labelled dropdown, laid out as the PC launcher lays one out: the label on the left, the
     * choices on the right, and the help line under both.
     */
    Spinner row(String labelText, List<Choice> choices, Object current, OnChoice onChange,
                String help) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(labelText, R.color.wario_text, 14f, false);
        line.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        Spinner spinner = new Spinner(context, Spinner.MODE_DROPDOWN);
        ArrayAdapter<Choice> adapter = new ArrayAdapter<Choice>(
                context, android.R.layout.simple_spinner_item, choices) {
            @Override
            public View getView(int position, View reused, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, reused, parent);
                view.setTextColor(color(R.color.wario_text));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundResource(R.drawable.bg_field);
        // Compact, like the PC launcher's combo boxes rather than a full-width block.
        spinner.setPadding(dp(10), dp(7), dp(8), dp(7));

        int selected = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (equal(choices.get(i).value, current)) {
                selected = i;
                break;
            }
        }
        spinner.setSelection(selected);
        // Set after the initial selection so restoring a saved value is not reported as a change.
        spinner.post(() -> spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onChange.onChoice(choices.get(position).value);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        }));
        line.addView(spinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));
        panel.addView(line, topMargin(12));

        if (help != null && !help.isEmpty()) {
            info(help, R.color.wario_muted);
        }
        return spinner;
    }

    /**
     * One switch, with its label on the left and the box on the right, as on PC, and its
     * explanation underneath.
     */
    CheckBox check(String labelText, boolean value, OnBool onChange, String help) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = label(labelText, R.color.wario_text, 14f, false);
        line.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox box = new CheckBox(context);
        box.setChecked(value);
        box.setOnCheckedChangeListener((button, checked) -> onChange.onBool(checked));
        line.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The whole line answers to a tap, not just the box: it is a far easier target.
        line.setOnClickListener(v -> box.toggle());
        panel.addView(line, topMargin(10));

        if (help != null && !help.isEmpty()) {
            info(help, R.color.wario_muted);
        }
        return box;
    }

    /** A 0..100 slider, used for the volume as the launcher uses it. */
    void slider(String labelText, int value, OnInt onChange) {
        TextView name = label(labelText + "   " + value + "%", R.color.wario_text, 14.5f, false);
        panel.addView(name, topMargin(14));

        SeekBar bar = new SeekBar(context);
        bar.setMax(100);
        bar.setProgress(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                name.setText(labelText + "   " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            /** Written once the finger lifts, rather than on every pixel of the drag. */
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                onChange.onInt(seekBar.getProgress());
            }
        });
        panel.addView(bar, topMargin(2));
    }

    /** A row of secondary actions. */
    void buttons(String[] texts, Runnable[] actions) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < texts.length; i++) {
            Button button = new Button(context);
            button.setText(texts[i]);
            button.setAllCaps(false);
            button.setTextColor(color(R.color.wario_text));
            button.setBackgroundResource(R.drawable.bg_button);
            Runnable action = actions[i];
            button.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            row.addView(button, params);
        }
        panel.addView(row, topMargin(14));
    }

    private LinearLayout.LayoutParams topMargin(float dps) {
        LinearLayout.LayoutParams params = wide();
        params.topMargin = dp(dps);
        return params;
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
