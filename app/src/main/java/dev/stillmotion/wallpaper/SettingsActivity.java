package dev.stillmotion.wallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SettingsActivity extends Activity {
    private static final int IMPORT_COMMUNITY = 3;
    private static final int EXPORT_PROBE_POSTER = 7;
    private static final int GALLERY = 0, SETTINGS = 1, TOOLS = 2;
    private static final String APPEARANCE = "appearance";
    private static final String LIGHT = "light";
    private static final String LANGUAGE = "language";

    private SharedPreferences settings, appearance;
    private List<FlymePack.Entry> entries;
    private Map<String, OrbitaArchive.Spec> orbitaThemes;
    private String selected;
    private int page;
    private boolean lockPreview = true;
    private int probePoster;
    private boolean light;
    private int background, surface, ink, muted, accent, onAccent, outline;
    private LinearLayout content, choices;
    private ImageView art;
    private TextView wallpaperTitle;
    private Button lock, home;
    private Object back;

    @Override
    protected void attachBaseContext(Context base) {
        String tag = base.getSharedPreferences(APPEARANCE, MODE_PRIVATE).getString(LANGUAGE, "");
        if (tag.isEmpty()) {
            super.attachBaseContext(base);
        } else {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(tag));
            super.attachBaseContext(base.createConfigurationContext(config));
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        appearance = getSharedPreferences(APPEARANCE, MODE_PRIVATE);
        light = appearance.getBoolean(LIGHT, false);
        setTheme(light ? R.style.AppLightTheme : R.style.AppTheme);
        super.onCreate(state);
        settings = Theme.preferences(this);
        background = light ? 0xfff7f7fc : 0xff101115;
        surface = light ? 0xffeceef7 : 0xff1c1e28;
        ink = light ? 0xff1b2030 : 0xfff1f0fa;
        muted = light ? 0xff565c72 : 0xffb5b8cb;
        accent = light ? 0xff344a98 : 0xffb8c6ff;
        onAccent = light ? 0xffffffff : 0xff17285a;
        outline = light ? 0xff6a7291 : 0xff858da9;
        if (state != null) {
            selected = state.getString("selection");
            page = state.getInt("page");
            lockPreview = state.getBoolean("lock", true);
            probePoster = state.getInt("probe-poster");
        }
        if (selected == null) selected = Theme.selectedVideo(settings).id;
        showPage(page);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("selection", selected);
        state.putInt("page", page);
        state.putBoolean("lock", lockPreview);
        state.putInt("probe-poster", probePoster);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (page == GALLERY) super.onBackPressed();
        else returnToGallery();
    }

    private void returnToGallery() {
        showPage(GALLERY);
    }

    @SuppressWarnings("deprecation")
    private void showPage(int destination) {
        page = destination;
        if (Build.VERSION.SDK_INT >= 33) {
            if (back != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) back);
            if (page != GALLERY) {
                OnBackInvokedCallback callback = this::returnToGallery;
                back = callback;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            } else {
                back = null;
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            getWindow().setStatusBarColor(background);
            getWindow().setNavigationBarColor(background);
        }
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content);
        setContentView(scroll);
        scroll.post(this::updateSystemBars);
        scroll.requestApplyInsets();
        addTopBar();
        switch (page) {
            case SETTINGS: showSettings(); break;
            case TOOLS: showTools(); break;
            default:
                if (BuildConfig.PROBE) showProbe();
                else showGallery();
        }
    }

    @SuppressWarnings("deprecation")
    private void updateSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? bars : 0, bars);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(light
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
    }

    private void addTopBar() {
        LinearLayout row = row();
        if (page != GALLERY) {
            View arrow = new BarIcon(false);
            arrow.setContentDescription(getString(R.string.back));
            arrow.setOnClickListener(view -> returnToGallery());
            row.addView(arrow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        TextView title = text(page == SETTINGS ? R.string.settings : page == TOOLS ? R.string.dev_tools
                : R.string.app_name, 24, ink, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        if (page == GALLERY) {
            View menu = new BarIcon(true);
            menu.setContentDescription(getString(R.string.more_options));
            menu.setOnClickListener(view -> {
                PopupMenu popup = new PopupMenu(this, menu);
                popup.getMenu().add(0, SETTINGS, 0, R.string.settings);
                popup.getMenu().add(0, TOOLS, 1, R.string.dev_tools);
                popup.setOnMenuItemClickListener(item -> {
                    showPage(item.getItemId());
                    return true;
                });
                popup.show();
            });
            row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showGallery() {
        orbitaThemes = Orbita.catalog(this);
        entries = FlymePack.installed(this, orbitaThemes);
        if (entries.isEmpty()) {
            add(text(R.string.empty_library, 26, ink, true), 40);
            add(text(R.string.empty_library_description, 16, muted, false), 10);
            addImportButton();
            return;
        }
        if (entries.stream().noneMatch(item -> item.id.equals(selected))) selected = entries.get(0).id;

        LinearLayout header = row();
        wallpaperTitle = text(0, 29, ink, true);
        header.addView(wallpaperTitle, new LinearLayout.LayoutParams(0, -2, 1));
        add(header, 10);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setBackground(shape(0xff020207, 16));
        art.setClipToOutline(true);
        int height = Math.max(195, Math.min(400, getResources().getConfiguration().screenHeightDp * 38 / 100));
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, dp(height));
        artParams.topMargin = dp(10);
        content.addView(art, artParams);

        LinearLayout modes = row();
        lock = button(R.string.lock_preview);
        home = button(R.string.home_preview);
        lock.setOnClickListener(view -> { lockPreview = true; updatePreview(); });
        home.setOnClickListener(view -> { lockPreview = false; updatePreview(); });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.setMarginEnd(dp(8));
        modes.addView(lock, half);
        modes.addView(home, new LinearLayout.LayoutParams(0, -2, 1f));
        add(modes, 8);

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        choices = row();
        strip.addView(choices);
        for (FlymePack.Entry item : entries) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(5), dp(5), dp(5), dp(5));
            card.setContentDescription(item.label);
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(shape(0xff06070e, 8));
            thumbnail.setClipToOutline(true);
            thumbnail.setImageBitmap(poster(item.id, true, 8));
            card.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(70)));
            TextView name = text(0, 12, ink, true);
            name.setText(item.label);
            name.setMaxLines(2);
            name.setPadding(dp(2), dp(4), dp(2), 0);
            card.addView(name);
            card.setTag(item.id);
            card.setOnClickListener(view -> { selected = item.id; updatePreview(); });
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(dp(88), -2);
            slot.setMarginEnd(dp(8));
            choices.addView(card, slot);
        }
        add(strip, 12);

        Button apply = button(R.string.apply_wallpaper);
        style(apply, true);
        apply.setOnClickListener(view -> {
            settings.edit().putString(Theme.FLYME_THEME_KEY, selected).apply();
            openPicker(FlymeVideoService.class);
        });
        add(apply, 14);
        addImportButton();
        updatePreview();
        View current = choices.findViewWithTag(selected);
        if (current != null) strip.post(() -> strip.scrollTo(current.getLeft(), 0));
    }

    private void addImportButton() {
        Button importPackage = button(R.string.community_import);
        style(importPackage, entries.isEmpty());
        importPackage.setOnClickListener(view -> pickPackage());
        add(importPackage, entries.isEmpty() ? 20 : 8);
    }

    private void updatePreview() {
        FlymePack.Entry item = entries.stream().filter(entry -> entry.id.equals(selected))
                .findFirst().orElse(entries.get(0));
        wallpaperTitle.setText(item.label);
        art.setContentDescription(getString(R.string.preview_description, item.label));
        art.setImageBitmap(poster(item.id, lockPreview, 2));
        lock.setSelected(lockPreview);
        home.setSelected(!lockPreview);
        style(lock, lockPreview);
        style(home, !lockPreview);
        for (int i = 0; i < choices.getChildCount(); i++) {
            View tile = choices.getChildAt(i);
            boolean active = item.id.equals(tile.getTag());
            tile.setSelected(active);
            tile.setBackground(ripple(active ? surface : background, active ? accent : background, 12));
        }
    }

    private Bitmap poster(String id, boolean locked, int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inSampleSize = sample;
        OrbitaArchive.Spec theme = orbitaThemes.get(id);
        if (theme != null) return BitmapFactory.decodeFile(theme.poster(locked ? "lock" : "home").getPath(), options);
        File file = CommunityPack.isCustom(id) ? CommunityPack.poster(this, id, locked)
                : FlymePack.file(this, id, ".webp");
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private void showSettings() {
        preference(R.string.appearance, light ? R.string.light : R.string.dark, view -> {
            new AlertDialog.Builder(this).setTitle(R.string.appearance)
                    .setSingleChoiceItems(new CharSequence[]{getText(R.string.light), getText(R.string.dark)},
                            light ? 0 : 1, (dialog, index) -> {
                                appearance.edit().putBoolean(LIGHT, index == 0).apply();
                                dialog.dismiss();
                                recreate();
                            }).setNegativeButton(android.R.string.cancel, null).show();
        });
        boolean english = getResources().getConfiguration().getLocales().get(0).getLanguage().equals("en");
        preference(R.string.language, english ? R.string.english : R.string.portuguese, view -> {
            new AlertDialog.Builder(this).setTitle(R.string.language)
                    .setSingleChoiceItems(new CharSequence[]{getText(R.string.portuguese), getText(R.string.english)},
                            english ? 1 : 0, (dialog, index) -> {
                                appearance.edit().putString(LANGUAGE, index == 1 ? "en" : "pt-BR").apply();
                                dialog.dismiss();
                                recreate();
                            }).setNegativeButton(android.R.string.cancel, null).show();
        });

        add(toggle(R.string.orbita_gas_effect, Orbita.GAS_EFFECT_KEY, false), 14);
        add(toggle(R.string.orbita_after, Orbita.AFTER_KEY, true), 6);
        add(toggle(R.string.static_mode, Theme.STATIC_KEY, false), 6);

        LinearLayout profile = row();
        profile.setPadding(dp(12), dp(8), dp(12), dp(8));
        profile.setMinimumHeight(dp(64));
        profile.setBackground(ripple(surface, surface, 14));
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.extremecodex_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(shape(surface, 24));
        avatar.setClipToOutline(true);
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = new LinearLayout(this);
        identity.setPaddingRelative(dp(12), 0, 0, 0);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(0, 16, ink, true);
        name.setText(R.string.github_owner);
        identity.addView(name);
        TextView github = text(0, 14, muted, false);
        github.setText(R.string.github_brand);
        identity.addView(github);
        profile.addView(identity);
        profile.setContentDescription(getString(R.string.github_profile));
        profile.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ExtremeCodeX")));
            } catch (ActivityNotFoundException missing) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });
        add(profile, 28);
    }

    private void showTools() {
        Button diagnostics = button(R.string.open_diagnostics);
        style(diagnostics, false);
        diagnostics.setOnClickListener(view -> showDiagnostics());
        add(diagnostics, 16);
        if (BuildConfig.PROBE) {
            Button poster = button(R.string.save_probe_poster);
            style(poster, false);
            poster.setOnClickListener(view -> openProbeExport());
            add(poster, 8);
        }
    }

    private void showProbe() {
        add(text(R.string.static_build_description, 16, muted, false), 12);
        android.widget.RadioGroup options = new android.widget.RadioGroup(this);
        for (int label : new int[]{R.string.dunes, R.string.orbit}) {
            android.widget.RadioButton choice = new android.widget.RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(label);
            choice.setMinHeight(dp(48));
            choice.setTextColor(ink);
            options.addView(choice);
            Theme theme = label == R.string.orbit ? Theme.ORBIT : Theme.DUNES;
            if (theme == Theme.selected(settings)) options.check(choice.getId());
            choice.setOnClickListener(view -> settings.edit().putString(Theme.THEME_KEY, theme.id).apply());
        }
        add(options, 12);
        Button apply = button(R.string.apply_wallpaper);
        style(apply, true);
        apply.setOnClickListener(view -> openPicker(StillMotionService.class));
        add(apply, 12);
    }

    private void preference(int label, int value, View.OnClickListener action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(10), dp(14), dp(10));
        tile.setMinimumHeight(dp(60));
        tile.addView(text(label, 16, ink, true));
        tile.addView(text(value, 14, muted, false));
        tile.setBackground(ripple(surface, surface, 14));
        tile.setOnClickListener(action);
        add(tile, 10);
    }

    private Switch toggle(int label, String key, boolean defaultValue) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(ink);
        control.setMinHeight(dp(52));
        control.setPaddingRelative(dp(4), dp(4), dp(4), dp(4));
        control.setSwitchPadding(dp(12));
        control.setChecked(settings.getBoolean(key, defaultValue));
        control.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean(key, enabled).apply());
        return control;
    }

    private void showDiagnostics() {
        String events = DebugEvents.snapshot();
        String report = getString(R.string.diagnostics_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
                + "\n\n" + (events.isEmpty() ? getString(R.string.diagnostics_empty) : events);
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_diagnostics).setMessage(report)
                .setNeutralButton(R.string.copy_diagnostics, (dialog, button) ->
                        getSystemService(ClipboardManager.class).setPrimaryClip(
                                ClipData.newPlainText(getString(R.string.app_name), report)))
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void openPicker(Class<? extends StillMotionService> wallpaper) {
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, wallpaper));
        try {
            startActivity(preview);
        } catch (ActivityNotFoundException | SecurityException missing) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException | SecurityException noChooser) {
                Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void pickPackage() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_COMMUNITY);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.community_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openProbeExport() {
        Theme theme = Theme.selected(settings);
        probePoster = theme.poster;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/webp")
                    .putExtra(Intent.EXTRA_TITLE, "StillMotion-" + theme.id + ".webp"), EXPORT_PROBE_POSTER);
        } catch (ActivityNotFoundException unavailable) {
            probePoster = 0;
            Toast.makeText(this, R.string.poster_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT_PROBE_POSTER) {
            int poster = probePoster;
            probePoster = 0;
            if (BuildConfig.PROBE && poster != 0 && result == RESULT_OK && data != null && data.getData() != null) {
                new Thread(() -> saveProbePoster(poster, data.getData()), "stillmotion-probe-export").start();
            }
            return;
        }
        if (request != IMPORT_COMMUNITY || result != RESULT_OK || data == null || data.getData() == null) return;
        Toast.makeText(this, R.string.community_working, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int status = R.string.community_saved;
            try {
                CommunityPack.install(getApplicationContext(), data.getData());
            } catch (IOException | RuntimeException invalid) {
                status = R.string.community_failed;
            }
            int message = status;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                if (message == R.string.community_saved) showPage(GALLERY);
            });
        }, "stillmotion-import").start();
    }

    private void saveProbePoster(int resource, Uri destination) {
        int status = R.string.poster_exported;
        try (InputStream input = getResources().openRawResource(resource);
                OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new IOException("Missing destination");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } catch (IOException | RuntimeException failure) {
            status = R.string.poster_export_failed;
        }
        int message = status;
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void add(View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        content.addView(view, params);
    }

    private TextView text(int label, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        if (label != 0) view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (strong) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(int label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setStateListAnimator(null);
        return button;
    }

    private void style(Button button, boolean filled) {
        button.setTextColor(filled ? onAccent : ink);
        button.setBackground(ripple(filled ? accent : surface, filled ? accent : outline, 14));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable ripple(int color, int border, int radius) {
        GradientDrawable body = shape(color, radius);
        if (color != border) body.setStroke(dp(1), border);
        return new RippleDrawable(ColorStateList.valueOf(light ? 0x221c306f : 0x40b8c6ff), body, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BarIcon extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean menu;

        BarIcon(boolean menu) {
            super(SettingsActivity.this);
            this.menu = menu;
            setBackground(ripple(background, background, 24));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            paint.setColor(ink);
            if (menu) {
                for (int offset = -5; offset <= 5; offset += 5) {
                    canvas.drawCircle(x, y + dp(offset), dp(2), paint);
                }
            } else {
                paint.setStrokeWidth(dp(2));
                canvas.drawLine(x - dp(8), y, x + dp(8), y, paint);
                canvas.drawLine(x - dp(8), y, x, y - dp(8), paint);
                canvas.drawLine(x - dp(8), y, x, y + dp(8), paint);
            }
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }
    }
}
