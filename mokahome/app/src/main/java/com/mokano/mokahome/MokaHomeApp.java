package com.mokano.mokahome;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MokaHome 0.6 controller.
 *
 * Se monta encima de MainActivityV5 sin cambiar el componente HOME. De esta forma
 * la actualizacion 0.5 -> 0.6 conserva el launcher predeterminado y la firma.
 */
public class MokaHomeApp extends Application implements Application.ActivityLifecycleCallbacks {
    static final String PREFS = "mokahome";
    static final String KEY_THEME = "theme_v6";
    static final String KEY_FONT = "font_scale_v6";
    static final String KEY_DENSITY = "density_scale_v6";
    static final String KEY_CARD_SCALE = "card_scale_v6";
    static final String KEY_ICON_SCALE = "icon_scale_v6";
    static final String KEY_SAFE_MARGIN = "safe_margin_v6";
    static final String KEY_LABELS = "labels_v6";
    static final String KEY_CLOCK = "clock_v6";
    static final String KEY_DATE = "date_v6";
    static final String KEY_FOCUS = "focus_v6";
    static final String KEY_ANIM = "anim_v6";
    static final String KEY_PHYSICAL_DENSITY = "physical_density_v6";

    static final String EXEMPT_KEY = "killer_exempt_packages_v5";

    static final int GREEN = Color.rgb(0, 215, 92);
    static final int RED = Color.rgb(255, 66, 70);
    static final int ACCENT = Color.rgb(142, 76, 27);

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> activeActivity = new WeakReference<>(null);

    private final Runnable clockTask = new Runnable() {
        @Override public void run() {
            Activity a = activeActivity.get();
            if (a != null && !a.isFinishing()) {
                updateClock(a);
                retintTopBar(a);
                ui.postDelayed(this, 15_000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);

        // Seguridad: v0.4 tenia logica para deshabilitar launchers alternativos.
        // Marcamos el takeover como completado antes de que se cree la Activity.
        prefs(this).edit().putBoolean("launcher_takeover_done_v4", true).apply();
        EXEC.execute(() -> reenableStoredFallbackLaunchers(this));
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (!isHome(activity)) return;
        activeActivity = new WeakReference<>(activity);
        activity.getWindow().getDecorView().postDelayed(() -> wireAndApply(activity), 180);
        activity.getWindow().getDecorView().postDelayed(() -> wireAndApply(activity), 900);
        activity.getWindow().getDecorView().postDelayed(() -> AudioController.applyPersisted(activity, false), 1300);
        activity.getWindow().getDecorView().postDelayed(() -> AudioController.applyPersisted(activity, false), 4200);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!isHome(activity)) return;
        activeActivity = new WeakReference<>(activity);
        ui.removeCallbacks(clockTask);
        ui.post(clockTask);
        activity.getWindow().getDecorView().postDelayed(() -> wireAndApply(activity), 240);
        activity.getWindow().getDecorView().postDelayed(() -> wireAndApply(activity), 1200);
        activity.getWindow().getDecorView().postDelayed(() -> AudioController.applyPersisted(activity, false), 800);
        activity.getWindow().getDecorView().postDelayed(() -> AudioController.applyPersisted(activity, false), 3200);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (isHome(activity)) ui.removeCallbacks(clockTask);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        Activity current = activeActivity.get();
        if (current == activity) activeActivity = new WeakReference<>(null);
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    private boolean isHome(Activity a) {
        return a != null && a.getClass().getName().equals("com.mokano.mokahome.MainActivityV5");
    }

    private void wireAndApply(Activity a) {
        if (a == null || a.isFinishing()) return;
        wireSettingsButton(a);
        applyLauncherVisuals(a);
        updateClock(a);
        applySavedSystemPreferences(a, false);
    }

    private void wireSettingsButton(Activity a) {
        LinearLayout tools = findToolsRow(a.getWindow().getDecorView());
        if (tools == null || tools.getChildCount() < 13) return;
        View settings = tools.getChildAt(12);
        settings.setOnClickListener(v -> showSettings(a));
        settings.setOnLongClickListener(v -> {
            openAndroidSettings(a);
            return true;
        });
        settings.setContentDescription("Configuracion de MokaHome");
    }

    private LinearLayout findToolsRow(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) view;
            if (row.getChildCount() >= 13 &&
                    row.getChildAt(0) instanceof ImageView &&
                    row.getChildAt(2) instanceof ImageView &&
                    row.getChildAt(4) instanceof ImageView &&
                    row.getChildAt(6) instanceof ImageView &&
                    row.getChildAt(8) instanceof ImageView &&
                    row.getChildAt(12) instanceof ImageView) {
                return row;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                LinearLayout found = findToolsRow(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void applyLauncherVisuals(Activity a) {
        LinearLayout root = homeRoot(a);
        if (root == null) return;

        int bg = bg(a);
        int fg = fg(a);
        int muted = muted(a);
        root.setBackgroundColor(bg);
        a.getWindow().setNavigationBarColor(bg);

        String margin = prefs(a).getString(KEY_SAFE_MARGIN, "normal");
        int edge = "compact".equals(margin) ? 24 : ("overscan".equals(margin) ? 62 : 38);
        int top = "compact".equals(margin) ? 12 : ("overscan".equals(margin) ? 30 : 18);
        int bottom = "compact".equals(margin) ? 16 : ("overscan".equals(margin) ? 42 : 24);
        root.setPadding(dp(a, edge), dp(a, top), dp(a, edge), dp(a, bottom));

        if (root.getChildCount() > 0 && root.getChildAt(0) instanceof ViewGroup) {
            ViewGroup topRow = (ViewGroup) root.getChildAt(0);
            if (topRow.getChildCount() > 0) recolorTextTree(topRow.getChildAt(0), fg, muted);
        }

        retintTopBar(a);

        if (root.getChildCount() < 2 || !(root.getChildAt(1) instanceof LinearLayout)) return;
        LinearLayout appsArea = (LinearLayout) root.getChildAt(1);

        float cardScale = parseFloat(prefs(a).getString(KEY_CARD_SCALE, "1.0"), 1f);
        float iconScale = parseFloat(prefs(a).getString(KEY_ICON_SCALE, "1.0"), 1f);
        String labelsMode = prefs(a).getString(KEY_LABELS, "focus");
        String focusMode = prefs(a).getString(KEY_FOCUS, "normal");
        final float focusScale = "soft".equals(focusMode) ? 1.012f : ("strong".equals(focusMode) ? 1.065f : 1.03f);
        final int stroke = "soft".equals(focusMode) ? 1 : ("strong".equals(focusMode) ? 4 : 3);

        for (int r = 0; r < appsArea.getChildCount(); r++) {
            View rowView = appsArea.getChildAt(r);
            if (!(rowView instanceof ViewGroup)) continue;
            ViewGroup row = (ViewGroup) rowView;
            for (int c = 0; c < row.getChildCount(); c++) {
                View tile = row.getChildAt(c);
                if (!(tile instanceof FrameLayout)) continue;
                FrameLayout card = (FrameLayout) tile;
                TextView label = directText(card);
                ImageView icon = directImage(card);

                card.setScaleX(cardScale);
                card.setScaleY(cardScale);
                card.setBackground(cardBackground(a, false, stroke));
                if (label != null) {
                    label.setTextColor(fg);
                    if ("always".equals(labelsMode)) label.setVisibility(View.VISIBLE);
                    else label.setVisibility(View.INVISIBLE);
                } else if (icon != null) {
                    // El unico cuadro sin TextView es el +.
                    icon.setColorFilter(fg);
                }
                if (icon != null) {
                    icon.setScaleX(iconScale);
                    icon.setScaleY(iconScale);
                }

                final TextView focusLabel = label;
                final float baseScale = cardScale;
                final String lm = labelsMode;
                card.setOnFocusChangeListener((v, focused) -> {
                    v.setBackground(cardBackground(a, focused, stroke));
                    float scale = focused ? baseScale * focusScale : baseScale;
                    v.animate().cancel();
                    String anim = prefs(a).getString(KEY_ANIM, "0.5");
                    if ("0".equals(anim)) {
                        v.setScaleX(scale);
                        v.setScaleY(scale);
                    } else {
                        v.animate().scaleX(scale).scaleY(scale).setDuration("1.0".equals(anim) ? 90 : 55).start();
                    }
                    if (focusLabel != null) {
                        if ("always".equals(lm)) focusLabel.setVisibility(View.VISIBLE);
                        else if ("never".equals(lm)) focusLabel.setVisibility(View.INVISIBLE);
                        else focusLabel.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
                    }
                });
            }
        }
    }

    private void retintTopBar(Activity a) {
        LinearLayout tools = findToolsRow(a.getWindow().getDecorView());
        if (tools == null || tools.getChildCount() < 13) return;
        int fg = fg(a);
        tintTopImage(tools.getChildAt(0), GREEN, a);
        tintTopImage(tools.getChildAt(2), RED, a);
        tintTopImage(tools.getChildAt(4), fg, a);
        tintTopImage(tools.getChildAt(6), fg, a);
        tintTopImage(tools.getChildAt(8), fg, a);
        tintImageTree(tools.getChildAt(10), fg, a);
        tintTopImage(tools.getChildAt(12), fg, a);

        for (int i = 1; i < tools.getChildCount(); i += 2) {
            if (i == 10) continue;
            View sep = tools.getChildAt(i);
            if (!(sep instanceof ViewGroup)) sep.setBackgroundColor(ACCENT);
        }
    }

    private void tintImageTree(View v, int color, Activity a) {
        if (v instanceof ImageView) tintTopImage(v, color, a);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintImageTree(g.getChildAt(i), color, a);
        }
    }

    private void tintTopImage(View v, int color, Activity a) {
        if (!(v instanceof ImageView)) return;
        ImageView iv = (ImageView) v;
        iv.setColorFilter(color);
        String focusMode = prefs(a).getString(KEY_FOCUS, "normal");
        float focusScale = "soft".equals(focusMode) ? 1.05f : ("strong".equals(focusMode) ? 1.18f : 1.12f);
        iv.setOnFocusChangeListener((view, focused) -> {
            float target = focused ? focusScale : 1f;
            String anim = prefs(a).getString(KEY_ANIM, "0.5");
            if ("0".equals(anim)) {
                view.setScaleX(target); view.setScaleY(target); view.setAlpha(focused ? 1f : 0.9f);
            } else {
                view.animate().cancel();
                view.animate().scaleX(target).scaleY(target).alpha(focused ? 1f : 0.9f)
                        .setDuration("1.0".equals(anim) ? 90 : 55).start();
            }
        });
    }

    private void recolorTextTree(View v, int fg, int muted) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(tv.getTextSize() <= dp(v.getContext(), 18) ? muted : fg);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolorTextTree(g.getChildAt(i), fg, muted);
        }
    }

    private TextView directText(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof TextView) return (TextView) g.getChildAt(i);
        return null;
    }

    private ImageView directImage(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof ImageView) return (ImageView) g.getChildAt(i);
        return null;
    }

    private GradientDrawable cardBackground(Context c, boolean focused, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        boolean light = isLight(c);
        gd.setColor(focused
                ? (light ? Color.rgb(231, 231, 228) : Color.rgb(55, 55, 55))
                : (light ? Color.WHITE : Color.rgb(45, 45, 45)));
        gd.setCornerRadius(dp(c, 22));
        if (focused) gd.setStroke(dp(c, strokeDp), fg(c));
        return gd;
    }

    private LinearLayout homeRoot(Activity a) {
        try {
            ViewGroup content = a.findViewById(android.R.id.content);
            if (content != null && content.getChildCount() > 0 && content.getChildAt(0) instanceof LinearLayout)
                return (LinearLayout) content.getChildAt(0);
        } catch (Exception ignored) {}
        return null;
    }

    private void updateClock(Activity a) {
        LinearLayout root = homeRoot(a);
        if (root == null || root.getChildCount() == 0 || !(root.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup top = (ViewGroup) root.getChildAt(0);
        if (top.getChildCount() == 0 || !(top.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup clockBlock = (ViewGroup) top.getChildAt(0);
        if (clockBlock.getChildCount() < 2 || !(clockBlock.getChildAt(0) instanceof ViewGroup)) return;
        ViewGroup timeRow = (ViewGroup) clockBlock.getChildAt(0);
        if (timeRow.getChildCount() < 2 || !(timeRow.getChildAt(0) instanceof TextView) || !(timeRow.getChildAt(1) instanceof TextView)) return;

        TextView clock = (TextView) timeRow.getChildAt(0);
        TextView ampm = (TextView) timeRow.getChildAt(1);
        TextView date = clockBlock.getChildAt(1) instanceof TextView ? (TextView) clockBlock.getChildAt(1) : null;

        boolean h24 = "24".equals(prefs(a).getString(KEY_CLOCK, "12"));
        Date now = new Date();
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        ampm.setText(h24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        clock.setTextColor(fg(a));
        ampm.setTextColor(fg(a));

        if (date != null) {
            boolean showDate = prefs(a).getBoolean(KEY_DATE, true);
            date.setVisibility(showDate ? View.VISIBLE : View.GONE);
            if (showDate) {
                String value = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "DO")).format(now);
                if (!value.isEmpty()) value = value.substring(0, 1).toUpperCase(new Locale("es", "DO")) + value.substring(1);
                date.setText(value);
                date.setTextColor(muted(a));
            }
        }
    }

    private void showSettings(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);

        LinearLayout outer = new LinearLayout(a);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(bg(a));
        outer.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 12));

        TextView title = tv(a, "Configuracion visual y del sistema", 23, fg(a), true);
        title.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 4));
        outer.addView(title);
        TextView version = tv(a, "MokaHome 0.6", 12, muted(a), false);
        version.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 10));
        outer.addView(version);

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addSection(a, body, "APARIENCIA");
        body.addView(settingRow(a, "Tema", themeLabel(a), () -> chooseTheme(a, d)));
        body.addView(settingRow(a, "Tamaño de letras", fontLabel(a), () -> chooseFont(a, d)));
        body.addView(settingRow(a, "Zoom de Android", densityLabel(a), () -> chooseDensity(a, d)));
        body.addView(settingRow(a, "Tamaño de cuadros", scaleLabel(prefs(a).getString(KEY_CARD_SCALE, "1.0")), () -> chooseCardScale(a, d)));
        body.addView(settingRow(a, "Tamaño de iconos", scaleLabel(prefs(a).getString(KEY_ICON_SCALE, "1.0")), () -> chooseIconScale(a, d)));
        body.addView(settingRow(a, "Margen seguro TV", marginLabel(a), () -> chooseMargin(a, d)));
        body.addView(settingRow(a, "Etiquetas de apps", labelsLabel(a), () -> chooseLabels(a, d)));
        body.addView(settingRow(a, "Foco de seleccion", focusLabel(a), () -> chooseFocus(a, d)));
        body.addView(settingRow(a, "Animaciones", animationLabel(a), () -> chooseAnimations(a, d)));

        addSection(a, body, "RELOJ");
        body.addView(settingRow(a, "Formato de hora", prefs(a).getString(KEY_CLOCK, "12") + " h", () -> chooseClock(a, d)));
        body.addView(settingRow(a, "Mostrar fecha", prefs(a).getBoolean(KEY_DATE, true) ? "Si" : "No", () -> chooseDate(a, d)));

        addSection(a, body, "AUDIO");
        body.addView(settingRow(a, "Salida preferida", AudioController.label(AudioController.savedOutput(a)), () -> chooseAudio(a, d)));
        body.addView(settingRow(a, "Restaurar audio al encender", AudioController.isRestoreEnabled(a) ? "Si" : "No", () -> chooseAudioRestore(a, d)));

        addSection(a, body, "SISTEMA");
        body.addView(settingRow(a, "Apps protegidas del App Killer", String.valueOf(ProtectedAppsDialog.count(a)), () -> {
            d.dismiss(); ProtectedAppsDialog.show(a);
        }));
        body.addView(settingRow(a, "Configuracion de Android", "Abrir", () -> {
            d.dismiss(); openAndroidSettings(a);
        }));
        body.addView(settingRow(a, "Restablecer opciones visuales", "", () -> {
            d.dismiss(); resetVisuals(a);
        }));

        TextView close = tv(a, "CERRAR", 13, fg(a), true);
        close.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        close.setPadding(dp(a, 12), dp(a, 10), dp(a, 10), dp(a, 6));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(v -> d.dismiss());
        outer.addView(close);

        d.setContentView(outer);
        showDialog(d, a, Math.min(dp(a, 760), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.68f)),
                Math.min(dp(a, 820), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.88f)));
    }

    private void addSection(Activity a, LinearLayout body, String name) {
        TextView t = tv(a, name, 11, muted(a), true);
        t.setPadding(dp(a, 10), dp(a, 14), dp(a, 10), dp(a, 4));
        body.addView(t);
    }

    private View settingRow(Activity a, String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(settingBackground(a, false));
        row.setOnFocusChangeListener((v, focused) -> v.setBackground(settingBackground(a, focused)));
        row.setOnClickListener(v -> action.run());

        TextView left = tv(a, label, 17, fg(a), false);
        left.setSingleLine(true);
        left.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView right = tv(a, value, 14, muted(a), true);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        right.setSingleLine(true);
        row.addView(right, new LinearLayout.LayoutParams(dp(a, 210), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 54));
        lp.topMargin = dp(a, 2);
        row.setLayoutParams(lp);
        return row;
    }

    private GradientDrawable settingBackground(Context c, boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg(c));
        gd.setCornerRadius(dp(c, 10));
        if (focused) gd.setStroke(dp(c, 2), fg(c));
        return gd;
    }

    private void showChoice(Activity a, String titleText, String[] labels, String[] values, ChoiceHandler handler) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg(a));
        root.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 12));
        TextView title = tv(a, titleText, 21, fg(a), true);
        title.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 10));
        root.addView(title);

        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            TextView item = tv(a, labels[i], 18, fg(a), false);
            item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            item.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 10));
            item.setFocusable(true);
            item.setClickable(true);
            item.setBackground(settingBackground(a, false));
            item.setOnFocusChangeListener((v, focused) -> v.setBackground(settingBackground(a, focused)));
            item.setOnClickListener(v -> {
                d.dismiss(); handler.chosen(value);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 54));
            lp.topMargin = dp(a, 3);
            root.addView(item, lp);
        }
        d.setContentView(root);
        showDialog(d, a, dp(a, 480), dp(a, 100 + labels.length * 58));
    }

    private void chooseTheme(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Tema", new String[]{"Oscuro", "Claro"}, new String[]{"dark", "light"}, value -> {
            prefs(a).edit().putString(KEY_THEME, value).commit();
            applySystemTheme(a, value);
            a.getWindow().getDecorView().postDelayed(a::recreate, 250);
        });
    }

    private void chooseFont(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Tamaño de letras", new String[]{"Pequeño 85%", "Normal 100%", "Grande 115%", "Muy grande 130%"},
                new String[]{"0.85", "1.0", "1.15", "1.30"}, value -> {
                    prefs(a).edit().putString(KEY_FONT, value).commit();
                    EXEC.execute(() -> {
                        boolean ok = runRootScript("settings put system font_scale " + value);
                        a.runOnUiThread(() -> {
                            toast(a, ok ? "Tamaño de letras aplicado" : "No pude cambiar el tamaño de letras");
                            a.getWindow().getDecorView().postDelayed(a::recreate, 300);
                        });
                    });
                });
    }

    private void chooseDensity(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Zoom de Android", new String[]{"Compacto 90%", "Normal 100%", "Grande 110%"},
                new String[]{"0.90", "1.0", "1.10"}, value -> {
                    prefs(a).edit().putString(KEY_DENSITY, value).commit();
                    applyDensity(a, value, true);
                });
    }

    private void chooseCardScale(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Tamaño de cuadros", new String[]{"Compacto", "Normal", "Grande"},
                new String[]{"0.88", "1.0", "1.04"}, value -> {
                    prefs(a).edit().putString(KEY_CARD_SCALE, value).commit(); applyLauncherVisuals(a);
                });
    }

    private void chooseIconScale(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Tamaño de iconos", new String[]{"Pequeño", "Normal", "Grande"},
                new String[]{"0.85", "1.0", "1.15"}, value -> {
                    prefs(a).edit().putString(KEY_ICON_SCALE, value).commit(); applyLauncherVisuals(a);
                });
    }

    private void chooseMargin(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Margen seguro TV", new String[]{"Compacto", "Normal", "Overscan / bordes cortados"},
                new String[]{"compact", "normal", "overscan"}, value -> {
                    prefs(a).edit().putString(KEY_SAFE_MARGIN, value).commit(); applyLauncherVisuals(a);
                });
    }

    private void chooseLabels(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Etiquetas de apps", new String[]{"Solo al seleccionar", "Siempre", "Nunca"},
                new String[]{"focus", "always", "never"}, value -> {
                    prefs(a).edit().putString(KEY_LABELS, value).commit(); applyLauncherVisuals(a);
                });
    }

    private void chooseFocus(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Foco de seleccion", new String[]{"Suave", "Normal", "Fuerte"},
                new String[]{"soft", "normal", "strong"}, value -> {
                    prefs(a).edit().putString(KEY_FOCUS, value).commit(); applyLauncherVisuals(a);
                });
    }

    private void chooseAnimations(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Animaciones", new String[]{"Sin animaciones", "Rapidas", "Normales"},
                new String[]{"0", "0.5", "1.0"}, value -> {
                    prefs(a).edit().putString(KEY_ANIM, value).commit();
                    applyAnimations(a, value);
                    applyLauncherVisuals(a);
                });
    }

    private void chooseClock(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Formato de hora", new String[]{"12 horas", "24 horas"}, new String[]{"12", "24"}, value -> {
            prefs(a).edit().putString(KEY_CLOCK, value).commit(); updateClock(a);
        });
    }

    private void chooseDate(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Mostrar fecha", new String[]{"Si", "No"}, new String[]{"yes", "no"}, value -> {
            prefs(a).edit().putBoolean(KEY_DATE, "yes".equals(value)).commit(); updateClock(a);
        });
    }

    private void chooseAudio(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Salida de audio", new String[]{"CODEC / salida analogica", "HDMI", "SPDIF", "Automatico"},
                new String[]{AudioController.CODEC, AudioController.HDMI, AudioController.SPDIF, AudioController.AUTO}, value -> {
                    AudioController.saveOutput(a, value);
                    if (!AudioController.AUTO.equals(value)) {
                        EXEC.execute(() -> {
                            boolean ok = AudioController.applyOutput(a, value);
                            a.runOnUiThread(() -> toast(a, ok ? "Salida de audio: " + AudioController.label(value) : "No pude cambiar la salida de audio"));
                        });
                    } else toast(a, "Salida automatica guardada");
                });
    }

    private void chooseAudioRestore(Activity a, Dialog parent) {
        parent.dismiss();
        showChoice(a, "Restaurar audio al encender", new String[]{"Si", "No"}, new String[]{"yes", "no"}, value -> {
            AudioController.setRestoreEnabled(a, "yes".equals(value));
            toast(a, "yes".equals(value) ? "MokaHome restaurara la salida al iniciar" : "Restauracion automatica desactivada");
        });
    }

    private void applySavedSystemPreferences(Activity a, boolean force) {
        SharedPreferences p = prefs(a);
        String theme = p.getString(KEY_THEME, "dark");
        String anim = p.getString(KEY_ANIM, "0.5");
        if (force || !p.getBoolean("system_visuals_applied_v6", false)) {
            applySystemTheme(a, theme);
            applyAnimations(a, anim);
            p.edit().putBoolean("system_visuals_applied_v6", true).apply();
        }
    }

    private void applySystemTheme(Context c, String theme) {
        EXEC.execute(() -> runRootScript("cmd uimode night " + ("light".equals(theme) ? "no" : "yes")));
    }

    private void applyAnimations(Context c, String scale) {
        EXEC.execute(() -> runRootScript(
                "settings put global window_animation_scale " + scale + "\n" +
                "settings put global transition_animation_scale " + scale + "\n" +
                "settings put global animator_duration_scale " + scale));
    }

    private void applyDensity(Activity a, String scale, boolean showToast) {
        EXEC.execute(() -> {
            boolean ok;
            if ("1.0".equals(scale)) {
                ok = runRootScript("wm density reset");
            } else {
                int physical = physicalDensity(a);
                float factor = parseFloat(scale, 1f);
                int target = Math.max(160, Math.round(physical * factor));
                ok = runRootScript("wm density " + target);
            }
            final boolean result = ok;
            a.runOnUiThread(() -> {
                if (showToast) toast(a, result ? "Zoom aplicado" : "No pude cambiar el zoom");
                a.getWindow().getDecorView().postDelayed(a::recreate, 450);
            });
        });
    }

    private int physicalDensity(Context c) {
        SharedPreferences p = prefs(c);
        int saved = p.getInt(KEY_PHYSICAL_DENSITY, 0);
        if (saved > 0) return saved;
        int physical = 0;
        String out = runRootCapture("wm density");
        if (out != null) {
            Matcher m = Pattern.compile("Physical density:\\s*(\\d+)").matcher(out);
            if (m.find()) {
                try { physical = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
            }
        }
        if (physical <= 0) physical = c.getResources().getDisplayMetrics().densityDpi;
        p.edit().putInt(KEY_PHYSICAL_DENSITY, physical).apply();
        return physical;
    }

    private void resetVisuals(Activity a) {
        prefs(a).edit()
                .putString(KEY_THEME, "dark")
                .putString(KEY_FONT, "1.0")
                .putString(KEY_DENSITY, "1.0")
                .putString(KEY_CARD_SCALE, "1.0")
                .putString(KEY_ICON_SCALE, "1.0")
                .putString(KEY_SAFE_MARGIN, "normal")
                .putString(KEY_LABELS, "focus")
                .putString(KEY_CLOCK, "12")
                .putBoolean(KEY_DATE, true)
                .putString(KEY_FOCUS, "normal")
                .putString(KEY_ANIM, "0.5")
                .putBoolean("system_visuals_applied_v6", false)
                .commit();
        EXEC.execute(() -> {
            boolean ok = runRootScript(
                    "cmd uimode night yes\n" +
                    "settings put system font_scale 1.0\n" +
                    "wm density reset\n" +
                    "settings put global window_animation_scale 0.5\n" +
                    "settings put global transition_animation_scale 0.5\n" +
                    "settings put global animator_duration_scale 0.5");
            a.runOnUiThread(() -> {
                toast(a, ok ? "Opciones visuales restauradas" : "Restauracion parcial");
                a.getWindow().getDecorView().postDelayed(a::recreate, 500);
            });
        });
    }

    private String themeLabel(Context c) { return isLight(c) ? "Claro" : "Oscuro"; }
    private String fontLabel(Context c) {
        String v = prefs(c).getString(KEY_FONT, "1.0");
        if ("0.85".equals(v)) return "85%"; if ("1.15".equals(v)) return "115%"; if ("1.30".equals(v)) return "130%"; return "100%";
    }
    private String densityLabel(Context c) {
        String v = prefs(c).getString(KEY_DENSITY, "1.0");
        if ("0.90".equals(v)) return "90%"; if ("1.10".equals(v)) return "110%"; return "100%";
    }
    private String scaleLabel(String v) { if ("0.88".equals(v) || "0.85".equals(v)) return "Compacto"; if ("1.04".equals(v) || "1.15".equals(v)) return "Grande"; return "Normal"; }
    private String marginLabel(Context c) { String v = prefs(c).getString(KEY_SAFE_MARGIN, "normal"); return "compact".equals(v) ? "Compacto" : ("overscan".equals(v) ? "Overscan" : "Normal"); }
    private String labelsLabel(Context c) { String v = prefs(c).getString(KEY_LABELS, "focus"); return "always".equals(v) ? "Siempre" : ("never".equals(v) ? "Nunca" : "Al seleccionar"); }
    private String focusLabel(Context c) { String v = prefs(c).getString(KEY_FOCUS, "normal"); return "soft".equals(v) ? "Suave" : ("strong".equals(v) ? "Fuerte" : "Normal"); }
    private String animationLabel(Context c) { String v = prefs(c).getString(KEY_ANIM, "0.5"); return "0".equals(v) ? "Sin animaciones" : ("1.0".equals(v) ? "Normales" : "Rapidas"); }

    static boolean isLight(Context c) { return "light".equals(prefs(c).getString(KEY_THEME, "dark")); }
    static int bg(Context c) { return isLight(c) ? Color.rgb(244, 244, 241) : Color.rgb(25, 25, 24); }
    static int fg(Context c) { return isLight(c) ? Color.rgb(28, 28, 27) : Color.WHITE; }
    static int muted(Context c) { return isLight(c) ? Color.rgb(92, 92, 89) : Color.rgb(163, 163, 163); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    static TextView tv(Context c, String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static void showDialog(Dialog d, Context c, int width, int height) {
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(bg(c)));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.62f;
            w.setAttributes(p);
            w.setLayout(width, height);
        }
    }

    static GradientDrawable simpleFocusBackground(Context c, boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg(c));
        gd.setCornerRadius(dp(c, 8));
        if (focused) gd.setStroke(dp(c, 2), fg(c));
        return gd;
    }

    private void openAndroidSettings(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.setPackage("com.android.settings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i); return;
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i); return;
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i); return;
        } catch (Exception ignored) {}
        EXEC.execute(() -> runRootScript("am start -n com.android.settings/.Settings >/dev/null 2>&1 || am start -a android.settings.SETTINGS >/dev/null 2>&1"));
    }

    private static void reenableStoredFallbackLaunchers(Context c) {
        String raw = prefs(c).getString("disabled_launchers_v4", "");
        if (raw == null || raw.trim().isEmpty()) return;
        StringBuilder script = new StringBuilder();
        for (String pkg : raw.split("\\n")) {
            pkg = pkg.trim();
            if (pkg.matches("[A-Za-z0-9._]+")) script.append("pm enable --user 0 ").append(pkg).append(" >/dev/null 2>&1\n");
        }
        if (script.length() > 0) runRootScript(script.toString());
    }

    static boolean runRootScript(String script) {
        if (script == null || script.trim().isEmpty()) return true;
        Process su = null;
        DataOutputStream os = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(script);
            if (!script.endsWith("\n")) os.writeBytes("\n");
            os.writeBytes("exit\n");
            os.flush();
            return su.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (su != null) su.destroy();
        }
    }

    static String runRootCapture(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    static void toast(Context c, String msg) { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show(); }

    private float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); } catch (Exception e) { return fallback; }
    }

    private interface ChoiceHandler { void chosen(String value); }
}
