package com.kurdman.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/** Small dependency-free colour chooser with presets, RGB sliders and HEX. */
final class ColorPicker {
    interface Callback {
        void onColorSelected(int color);
    }

    private static final int[] PRESETS = {
            Color.BLACK, Color.WHITE, Color.rgb(75, 85, 99),
            Color.rgb(220, 38, 38), Color.rgb(234, 88, 12),
            Color.rgb(250, 204, 21), Color.rgb(22, 163, 74),
            Color.rgb(20, 184, 166), Color.rgb(37, 99, 235),
            Color.rgb(124, 58, 237), Color.rgb(219, 39, 119),
            Color.rgb(251, 207, 232), Color.rgb(254, 243, 199),
            Color.rgb(187, 247, 208), Color.rgb(191, 219, 254),
            Color.rgb(15, 23, 42), Color.rgb(69, 26, 3),
            Color.rgb(120, 53, 15), Color.rgb(217, 119, 6),
            Color.rgb(5, 46, 22), Color.rgb(80, 7, 36),
            Color.rgb(127, 29, 29), Color.rgb(30, 41, 59),
            Color.rgb(8, 145, 178)
    };

    private ColorPicker() {
    }

    static void show(Context context, String title, int initialColor,
                     Callback callback) {
        final int[] selected = {Color.rgb(
                Color.red(initialColor), Color.green(initialColor),
                Color.blue(initialColor))};

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 18);
        root.setPadding(padding, padding, padding, padding);

        View preview = new View(context);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 58));
        preview.setLayoutParams(previewParams);
        root.addView(preview);

        TextView hexLabel = new TextView(context);
        hexLabel.setTextColor(Color.WHITE);
        hexLabel.setPadding(0, dp(context, 8), 0, dp(context, 4));
        root.addView(hexLabel);

        SeekBar red = slider(context, Color.red(selected[0]));
        SeekBar green = slider(context, Color.green(selected[0]));
        SeekBar blue = slider(context, Color.blue(selected[0]));
        addSliderRow(context, root, "سوور R", red);
        addSliderRow(context, root, "سەوز G", green);
        addSliderRow(context, root, "شین B", blue);

        EditText hexInput = new EditText(context);
        hexInput.setSingleLine(true);
        hexInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hexInput.setHint("#RRGGBB");
        root.addView(hexInput);

        TextView presetTitle = new TextView(context);
        presetTitle.setText("ڕەنگە ئامادەکان");
        presetTitle.setPadding(0, dp(context, 14), 0, dp(context, 8));
        root.addView(presetTitle);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(6);
        root.addView(grid);

        Runnable refresh = () -> {
            selected[0] = Color.rgb(red.getProgress(), green.getProgress(),
                    blue.getProgress());
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(selected[0]);
            drawable.setCornerRadius(dp(context, 12));
            drawable.setStroke(dp(context, 1), Color.rgb(100, 116, 139));
            preview.setBackground(drawable);
            String hex = TextStyleSettings.hex(selected[0]);
            hexLabel.setText(hex);
            if (!hexInput.hasFocus()) {
                hexInput.setText(hex);
                hexInput.setSelection(hex.length());
            }
        };

        SeekBar.OnSeekBarChangeListener listener =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress,
                                                  boolean fromUser) {
                        refresh.run();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                };
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);

        for (final int preset : PRESETS) {
            Button swatch = new Button(context);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(context, 42);
            params.height = dp(context, 42);
            params.setMargins(dp(context, 3), dp(context, 3),
                    dp(context, 3), dp(context, 3));
            swatch.setLayoutParams(params);
            swatch.setMinWidth(0);
            swatch.setMinHeight(0);
            swatch.setText("");
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(preset);
            drawable.setCornerRadius(dp(context, 10));
            drawable.setStroke(dp(context, 1), Color.rgb(100, 116, 139));
            swatch.setBackground(drawable);
            swatch.setOnClickListener(v -> {
                red.setProgress(Color.red(preset));
                green.setProgress(Color.green(preset));
                blue.setProgress(Color.blue(preset));
                refresh.run();
            });
            grid.addView(swatch);
        }

        refresh.run();

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("هەڵپەساردن", null)
                .setPositiveButton("هەڵبژاردن", (dialog, which) -> {
                    int fromHex = parseHex(hexInput.getText().toString(), -1);
                    callback.onColorSelected(fromHex == -1 ? selected[0] : fromHex);
                })
                .show();
    }

    private static SeekBar slider(Context context, int value) {
        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(255);
        seekBar.setProgress(value);
        return seekBar;
    }

    private static void addSliderRow(Context context, LinearLayout root,
                                     String label, SeekBar seekBar) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setPadding(0, dp(context, 6), 0, 0);
        root.addView(text);
        root.addView(seekBar);
    }

    private static int parseHex(String value, int fallback) {
        try {
            String clean = value == null ? "" : value.trim();
            if (!clean.startsWith("#") && clean.length() == 6) {
                clean = "#" + clean;
            }
            return Color.parseColor(clean);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
