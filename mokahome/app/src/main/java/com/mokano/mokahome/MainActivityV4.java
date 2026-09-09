package com.mokano.mokahome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV4 extends Activity {
    private static final String PREFS = "mokahome";
    private static final int COLS = 6;
    private static final int MAX_TILES = 18;
    private static final int INITIAL_TILES = 8;

    private static final int BG = Color.rgb(25, 25, 24);
    private static final int CARD = Color.rgb(45, 45, 45);
    private static final int CARD_FOCUS = Color.rgb(55, 55, 55);
    private static final int MUTED = Color.rgb(163, 163, 163);
    private static final int GREEN = Color.rgb(0, 215, 92);
    private static final int RED = Color.rgb(255, 66, 70);
    private static final int SEP = Color.rgb(142, 76, 27);
    private static final int PICKER_LINE = Color.rgb(47, 47, 46);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout appsArea;
    private TextView clockText;
    private TextView amPmText;
    private TextView dateText;
    private ImageView networkIcon;
    private ImageView ramIcon;
    private int tileSizePx;
    private long lastHomeTakeoverAt;
    private long appsCacheAt;
    private volatile List<AppEntry> cachedApps = new ArrayList<>();

    private final Runnable minuteTask = new Runnable() {
        @Override public void run() {
            updateClock();
            updateNetworkIcon();
            ui.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tileSizePx = calculateTileSize();
        cachedApps = getLaunchableApps();
        appsCacheAt = System.currentTimeMillis();
        migrateFavoritesStorage();
        initializeFavoritesIfNeeded();

        setContentView(buildHome());
        renderApps();
        updateClock();
        updateNetworkIcon();
        ui.postDelayed(minuteTask, 60_000);
        ui.postDelayed(this::ensureLauncherOwnership, 600);
    }

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(38), dp(18), dp(38), dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP | Gravity.CENTER_VERTICAL);
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(166)));

        top.addView(buildClockBlock(), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.TOP | Gravity.RIGHT);
        top.addView(tools, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

        ramIcon = topIconButton(R.drawable.ic_top_ram, GREEN, this::freeMemory);
        tools.addView(ramIcon, toolLp());
        tools.addView(separator());

        tools.addView(topIconButton(R.drawable.ic_top_kill, RED, this::killBackgroundApps), toolLp());
        tools.addView(separator());

        networkIcon = topIconButton(R.drawable.ic_top_offline, Color.WHITE, this::openNetworkSettings);
        tools.addView(networkIcon, toolLp());
        tools.addView(separator());

        tools.addView(topIconButton(R.drawable.ic_top_apps, Color.WHITE, this::showAllApps), toolLp());
        tools.addView(separator());

        tools.addView(topIconButton(R.drawable.ic_top_bell, Color.WHITE, this::expandNotifications), toolLp());
        tools.addView(separator());

        tools.addView(buildVolumeControl(), new LinearLayout.LayoutParams(dp(168), dp(66)));
        tools.addView(separator());

        ImageView settings = topIconButton(R.drawable.ic_top_settings, Color.WHITE,
                () -> openSystem(Settings.ACTION_SETTINGS));
        settings.setOnLongClickListener(v -> {
            showLauncherRecovery();
            return true;
        });
        tools.addView(settings, toolLp());

        appsArea = new LinearLayout(this);
        appsArea.setOrientation(LinearLayout.VERTICAL);
        appsArea.setGravity(Gravity.TOP | Gravity.LEFT);
        root.addView(appsArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildClockBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.LEFT | Gravity.TOP);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM);
        block.addView(timeRow);

        clockText = text("--:--", 84, Color.WHITE, true);
        clockText.setIncludeFontPadding(false);
        timeRow.addView(clockText);

        amPmText = text("", 32, Color.WHITE, true);
        amPmText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams amLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amLp.leftMargin = dp(5);
        amLp.bottomMargin = dp(8);
        timeRow.addView(amPmText, amLp);

        dateText = text("", 14, MUTED, false);
        dateText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        block.addView(dateText, dlp);
        return block;
    }

    private ImageView topIconButton(int res, int tint, Runnable action) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        makeTopFocusable(v);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private View buildVolumeControl() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(topIconButton(R.drawable.ic_top_minus, Color.WHITE, this::volumeDown),
                new LinearLayout.LayoutParams(dp(50), dp(66)));
        row.addView(topIconButton(R.drawable.ic_top_volume, Color.WHITE, this::showVolumeUi),
                new LinearLayout.LayoutParams(dp(68), dp(66)));
        row.addView(topIconButton(R.drawable.ic_top_plus, Color.WHITE, this::volumeUp),
                new LinearLayout.LayoutParams(dp(50), dp(66)));
        return row;
    }

    private void makeTopFocusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setAlpha(0.9f);
        v.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            view.animate().scaleX(focused ? 1.13f : 1f)
                    .scaleY(focused ? 1.13f : 1f)
                    .alpha(focused ? 1f : 0.9f)
                    .setDuration(55).start();
        });
    }

    private LinearLayout.LayoutParams toolLp() {
        return new LinearLayout.LayoutParams(dp(66), dp(66));
    }

    private View separator() {
        View sep = new View(this);
        sep.setBackgroundColor(SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(44));
        lp.leftMargin = dp(7);
        lp.rightMargin = dp(7);
        lp.topMargin = dp(10);
        sep.setLayoutParams(lp);
        return sep;
    }

    private void renderApps() {
        if (appsArea == null) return;
        appsArea.removeAllViews();
        List<AppEntry> snapshot = cachedApps;
        if (snapshot == null || snapshot.isEmpty()) snapshot = getLaunchableApps();

        List<String> stored = loadFavorites();
        List<String> clean = new ArrayList<>();
        for (String component : stored) {
            if (findByComponent(snapshot, component) != null &&
                    !clean.contains(component) && clean.size() < MAX_TILES) clean.add(component);
        }
        if (!clean.equals(stored)) saveFavorites(clean);

        int cellCount = clean.size() + (clean.size() < MAX_TILES ? 1 : 0);
        int rows = Math.min(3, Math.max(1, (cellCount + COLS - 1) / COLS));
        int pos = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, tileSizePx);
            if (r > 0) rlp.topMargin = dp(22);
            appsArea.addView(row, rlp);

            for (int c = 0; c < COLS && pos < cellCount; c++, pos++) {
                View tile = pos < clean.size()
                        ? createAppTile(findByComponent(snapshot, clean.get(pos)), pos)
                        : createAddTile();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSizePx, tileSizePx);
                if (c > 0) lp.leftMargin = dp(22);
                row.addView(tile, lp);
            }
        }
    }

    private View createAppTile(AppEntry app, int index) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setBackground(appCard(false));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (app != null && app.icon != null) icon.setImageDrawable(app.icon);
        int iconSize = Math.max(dp(62), (int) (tileSizePx * 0.50f));
        card.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        TextView label = text(app != null ? app.label : "", 13, Color.WHITE, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM);
        llp.leftMargin = dp(8);
        llp.rightMargin = dp(8);
        llp.bottomMargin = dp(6);
        card.addView(label, llp);

        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(appCard(focused));
            v.setScaleX(focused ? 1.025f : 1f);
            v.setScaleY(focused ? 1.025f : 1f);
            label.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        });

        if (app != null) {
            card.setOnClickListener(v -> launchApp(app));
            card.setOnLongClickListener(v -> {
                showTileOptions(index);
                return true;
            });
        }
        return card;
    }

    private View createAddTile() {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(appCard(false));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_top_plus);
        plus.setColorFilter(Color.WHITE);
        plus.setPadding(dp(43), dp(43), dp(43), dp(43));
        card.addView(plus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(appCard(focused));
            v.setScaleX(focused ? 1.025f : 1f);
            v.setScaleY(focused ? 1.025f : 1f);
        });
        card.setOnClickListener(v -> addNewTile());
        return card;
    }

    private GradientDrawable appCard(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? CARD_FOCUS : CARD);
        gd.setCornerRadius(dp(22));
        if (focused) gd.setStroke(dp(3), Color.WHITE);
        return gd;
    }

    private void addNewTile() {
        if (loadFavorites().size() < MAX_TILES) showAppBrowser(true, -1);
    }

    private void showTileOptions(int index) {
        Dialog dialog = baseDialog(dp(410), dp(240));
        LinearLayout root = dialogColumn();
        root.addView(dialogTitle("Editar cuadro"));
        root.addView(dialogAction("Cambiar aplicación", () -> {
            dialog.dismiss();
            showAppBrowser(true, index);
        }));
        root.addView(dialogAction("Eliminar cuadro", () -> {
            dialog.dismiss();
            removeFavorite(index);
        }));
        root.addView(dialogCancel(dialog));
        dialog.setContentView(root);
        showDialogSized(dialog, dp(430), dp(260));
    }

    private void showAllApps() {
        refreshAppCacheAsync();
        showAppBrowser(false, -1);
    }

    private void showAppBrowser(boolean choosingForHome, int replaceIndex) {
        final List<AppEntry> apps = new ArrayList<>(cachedApps);
        if (apps.isEmpty()) {
            Toast.makeText(this, "No encontré aplicaciones", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = baseDialog(0, 0);
        LinearLayout root = dialogColumn();
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        String title = choosingForHome
                ? (replaceIndex >= 0 ? "Cambiar aplicación" : "Agregar aplicación")
                : "Todas las aplicaciones";
        root.addView(dialogTitle(title));

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(PICKER_LINE));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(BG);
        list.setSelector(pickerSelector());
        list.setItemsCanFocus(false);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new AppPickerAdapter(this, apps));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(8);
        root.addView(list, llp);
        root.addView(dialogCancel(dialog));

        list.setOnItemClickListener((parent, view, position, id) -> {
            chooseFromBrowser(dialog, apps, position, choosingForHome, replaceIndex);
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0 && pos < apps.size()) {
                    chooseFromBrowser(dialog, apps, pos, choosingForHome, replaceIndex);
                    return true;
                }
            }
            return false;
        });

        dialog.setContentView(root);
        showDialogSized(dialog,
                Math.min(dp(720), (int) (getResources().getDisplayMetrics().widthPixels * 0.58f)),
                Math.min(dp(760), (int) (getResources().getDisplayMetrics().heightPixels * 0.80f)));
        list.requestFocus();
        list.setSelection(0);
    }

    private void chooseFromBrowser(Dialog dialog, List<AppEntry> apps, int position,
                                   boolean choosingForHome, int replaceIndex) {
        if (position < 0 || position >= apps.size()) return;
        AppEntry selected = apps.get(position);
        if (!choosingForHome) {
            dialog.dismiss();
            launchApp(selected);
            return;
        }
        applySelectedApp(selected, replaceIndex);
        dialog.dismiss();
        renderApps();
    }

    private void applySelectedApp(AppEntry selected, int replaceIndex) {
        List<String> favs = loadFavorites();
        String component = selected.component();

        if (replaceIndex >= 0 && replaceIndex < favs.size()) {
            int duplicate = favs.indexOf(component);
            if (duplicate >= 0 && duplicate != replaceIndex) {
                favs.remove(duplicate);
                if (duplicate < replaceIndex) replaceIndex--;
            }
            if (replaceIndex >= 0 && replaceIndex < favs.size()) favs.set(replaceIndex, component);
        } else {
            if (favs.contains(component)) {
                Toast.makeText(this, "Esa app ya está en Inicio", Toast.LENGTH_SHORT).show();
                return;
            }
            if (favs.size() < MAX_TILES) favs.add(component);
        }
        saveFavorites(favs);
    }

    private void removeFavorite(int index) {
        List<String> favs = loadFavorites();
        if (index >= 0 && index < favs.size()) {
            favs.remove(index);
            saveFavorites(favs);
            renderApps();
        }
    }

    private Dialog baseDialog(int ignoredW, int ignoredH) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        return dialog;
    }

    private LinearLayout dialogColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        return root;
    }

    private TextView dialogTitle(String value) {
        TextView title = text(value, 21, Color.WHITE, true);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), dp(8));
        return title;
    }

    private TextView dialogAction(String value, Runnable action) {
        TextView item = text(value, 18, Color.WHITE, false);
        item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        item.setPadding(dp(18), dp(12), dp(18), dp(12));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(pickerRow(false));
        item.setOnFocusChangeListener((v, f) -> v.setBackground(pickerRow(f)));
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(4);
        item.setLayoutParams(lp);
        return item;
    }

    private TextView dialogCancel(Dialog dialog) {
        TextView cancel = text("CANCELAR", 13, Color.WHITE, true);
        cancel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cancel.setPadding(dp(12), dp(8), dp(10), dp(4));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        return cancel;
    }

    private void showDialogSized(Dialog dialog, int width, int height) {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.55f;
            w.setAttributes(p);
            w.setLayout(width, height);
        }
    }

    private StateListDrawable pickerSelector() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable active = pickerRow(true);
        states.addState(new int[]{android.R.attr.state_pressed}, active);
        states.addState(new int[]{android.R.attr.state_selected}, active);
        states.addState(new int[]{android.R.attr.state_focused}, active);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private GradientDrawable pickerRow(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(BG);
        if (focused) gd.setStroke(dp(2), Color.WHITE);
        return gd;
    }

    private void initializeFavoritesIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("favorites_initialized_v3", false)) return;
        List<String> existing = loadFavorites();
        if (existing.isEmpty()) {
            String[] priorities = {"smarttube", "mokatube", "youtube", "netflix",
                    "spotify", "browser", "chrome", "file", "explorer"};
            Set<String> used = new HashSet<>();
            for (String p : priorities) {
                for (AppEntry app : cachedApps) {
                    String hay = (app.pkg + " " + app.label).toLowerCase(Locale.US);
                    if (!used.contains(app.component()) && hay.contains(p)) {
                        existing.add(app.component());
                        used.add(app.component());
                        break;
                    }
                }
                if (existing.size() >= INITIAL_TILES) break;
            }
            for (AppEntry app : cachedApps) {
                if (existing.size() >= INITIAL_TILES) break;
                if (!used.contains(app.component())) {
                    existing.add(app.component());
                    used.add(app.component());
                }
            }
            saveFavorites(existing);
        }
        prefs.edit().putBoolean("favorites_initialized_v3", true).apply();
    }

    private void migrateFavoritesStorage() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.contains("favorites_csv")) return;
        List<String> old = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            String value = p.getString("fav_" + i, "");
            if (value != null && !value.isEmpty()) old.add(value);
        }
        if (!old.isEmpty()) saveFavorites(old);
    }

    private List<String> loadFavorites() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("favorites_csv", "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split("\\n")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private void saveFavorites(List<String> favs) {
        StringBuilder sb = new StringBuilder();
        for (String f : favs) {
            if (f == null || f.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(f);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("favorites_csv", sb.toString()).commit();
    }

    private List<AppEntry> getLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(i, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            if (getPackageName().equals(pkg)) continue;
            String key = pkg + "|" + cls;
            if (!seen.add(key)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.cls = cls;
            CharSequence label = ri.loadLabel(pm);
            e.label = label == null ? pkg : label.toString();
            try { e.icon = ri.loadIcon(pm); } catch (Exception ignored) {}
            apps.add(e);
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return apps;
    }

    private void refreshAppCacheAsync() {
        if (System.currentTimeMillis() - appsCacheAt < 30_000) return;
        appsCacheAt = System.currentTimeMillis();
        worker.execute(() -> {
            List<AppEntry> fresh = getLaunchableApps();
            ui.post(() -> cachedApps = fresh);
        });
    }

    private AppEntry findByComponent(List<AppEntry> apps, String component) {
        if (component == null) return null;
        for (AppEntry a : apps) if (component.equals(a.component())) return a;
        return null;
    }

    private void launchApp(AppEntry app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(app.pkg, app.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void freeMemory() {
        if (ramIcon != null) ramIcon.setAlpha(0.45f);
        long before = availableRamMb();
        worker.execute(() -> {
            boolean ok = runRootScript("am kill-all >/dev/null 2>&1");
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            long after = availableRamMb();
            ui.post(() -> {
                if (ramIcon != null) ramIcon.setAlpha(0.9f);
                long gain = Math.max(0, after - before);
                Toast.makeText(this, ok
                        ? (gain > 0 ? after + " MB libres  (+" + gain + " MB)" : after + " MB libres")
                        : "No pude obtener acceso root", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void killBackgroundApps() {
        worker.execute(() -> {
            List<String> targets = collectKillTargets();
            StringBuilder script = new StringBuilder();
            for (String pkg : targets) {
                if (pkg.matches("[A-Za-z0-9._]+"))
                    script.append("am force-stop ").append(pkg).append(" >/dev/null 2>&1\n");
            }
            boolean ok = runRootScript(script.toString());
            ui.post(() -> Toast.makeText(this,
                    ok ? targets.size() + " apps cerradas" : "No pude obtener acceso root",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private List<String> collectKillTargets() {
        PackageManager pm = getPackageManager();
        Set<String> protectedPkgs = getProtectedPackages();
        Set<String> targets = new HashSet<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String pkg = ai.packageName;
                if (protectedPkgs.contains(pkg) || isCriticalPackage(pkg)) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean launchable = pm.getLaunchIntentForPackage(pkg) != null;
                if (!system || launchable) targets.add(pkg);
            }
        } catch (Exception ignored) {}
        targets.remove(getPackageName());
        return new ArrayList<>(targets);
    }

    private Set<String> getProtectedPackages() {
        Set<String> out = new HashSet<>();
        out.add(getPackageName());
        out.add("android");
        out.add("com.android.systemui");
        out.add("com.android.settings");
        out.add("com.google.android.gms");
        out.add("com.google.android.gsf");
        try {
            String input = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (input != null && input.contains("/")) out.add(input.substring(0, input.indexOf('/')));
        } catch (Exception ignored) {}
        for (String p : loadDisabledLaunchers()) out.add(p);
        return out;
    }

    private boolean isCriticalPackage(String pkg) {
        if (pkg == null) return true;
        return pkg.startsWith("com.android.providers") ||
                pkg.startsWith("com.android.permission") ||
                pkg.startsWith("com.android.packageinstaller") ||
                pkg.startsWith("com.google.android.packageinstaller") ||
                pkg.startsWith("com.android.inputmethod") ||
                pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.android.phone") || pkg.startsWith("com.android.shell") ||
                pkg.startsWith("com.android.bluetooth") || pkg.startsWith("com.android.networkstack");
    }

    private void ensureLauncherOwnership() {
        long now = System.currentTimeMillis();
        if (now - lastHomeTakeoverAt < 10_000) return;
        lastHomeTakeoverAt = now;
        String current = currentHomePackage();
        if (getPackageName().equals(current) &&
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("launcher_takeover_done_v4", false)) return;

        final List<String> alternatives = otherHomeLaunchers();
        worker.execute(() -> {
            String component = getPackageName() + "/.MainActivityV4";
            StringBuilder script = new StringBuilder();
            script.append("cmd package set-home-activity --user 0 ").append(component)
                    .append(" >/dev/null 2>&1 || cmd package set-home-activity ")
                    .append(component).append(" >/dev/null 2>&1\n");
            for (String pkg : alternatives) {
                if (pkg.matches("[A-Za-z0-9._]+"))
                    script.append("pm disable-user --user 0 ").append(pkg).append(" >/dev/null 2>&1\n");
            }
            script.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n ")
                    .append(component).append(" >/dev/null 2>&1\n");
            boolean ok = runRootScript(script.toString());
            if (ok) {
                saveDisabledLaunchers(alternatives);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("launcher_takeover_done_v4", true).apply();
            }
        });
    }

    private List<String> otherHomeLaunchers() {
        List<String> out = new ArrayList<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(getPackageName()) || pkg.equals("android") ||
                        pkg.equals("com.android.systemui") || pkg.equals("com.android.settings")) continue;
                if (!out.contains(pkg)) out.add(pkg);
            }
        } catch (Exception ignored) {}
        String current = currentHomePackage();
        if (current != null && !current.isEmpty() && !current.equals(getPackageName()) &&
                !current.equals("android") && !out.contains(current)) out.add(current);
        return out;
    }

    private String currentHomePackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Exception ignored) {}
        return "";
    }

    private void saveDisabledLaunchers(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        String legacy = getSharedPreferences(PREFS, MODE_PRIVATE).getString("disabled_launcher", "");
        if (legacy != null && !legacy.isEmpty() && sb.indexOf(legacy) < 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(legacy);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("disabled_launchers_v4", sb.toString()).apply();
    }

    private List<String> loadDisabledLaunchers() {
        List<String> out = new ArrayList<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("disabled_launchers_v4", "");
        if (raw != null) for (String p : raw.split("\\n")) if (!p.trim().isEmpty()) out.add(p.trim());
        String legacy = getSharedPreferences(PREFS, MODE_PRIVATE).getString("disabled_launcher", "");
        if (legacy != null && !legacy.isEmpty() && !out.contains(legacy)) out.add(legacy);
        return out;
    }

    private void showLauncherRecovery() {
        List<String> old = loadDisabledLaunchers();
        if (old.isEmpty()) {
            Toast.makeText(this, "No hay launcher anterior guardado", Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = baseDialog(0, 0);
        LinearLayout root = dialogColumn();
        root.addView(dialogTitle("Launcher anterior"));
        TextView msg = text("Reactivar launcher anterior", 17, Color.WHITE, false);
        msg.setPadding(dp(10), dp(14), dp(10), dp(14));
        root.addView(msg);
        root.addView(dialogAction("REACTIVAR", () -> {
            dialog.dismiss();
            worker.execute(() -> {
                StringBuilder s = new StringBuilder();
                for (String p : old) if (p.matches("[A-Za-z0-9._]+"))
                    s.append("pm enable ").append(p).append(" >/dev/null 2>&1\n");
                boolean ok = runRootScript(s.toString());
                if (ok) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove("disabled_launchers_v4").remove("disabled_launcher")
                        .putBoolean("launcher_takeover_done_v4", false).apply();
                ui.post(() -> Toast.makeText(this,
                        ok ? "Launcher anterior reactivado" : "No pude reactivarlo",
                        Toast.LENGTH_SHORT).show());
            });
        }));
        root.addView(dialogCancel(dialog));
        dialog.setContentView(root);
        showDialogSized(dialog, dp(430), dp(240));
    }

    private void expandNotifications() {
        boolean reflected = false;
        try {
            Object service = getSystemService("statusbar");
            if (service != null) {
                Method m = service.getClass().getMethod("expandNotificationsPanel");
                m.setAccessible(true);
                m.invoke(service);
                reflected = true;
            }
        } catch (Exception ignored) {}
        final boolean reflectionWorked = reflected;
        worker.execute(() -> {
            boolean rootWorked = runRootScript(
                    "input keyevent 83 >/dev/null 2>&1\n" +
                    "cmd statusbar expand-notifications >/dev/null 2>&1\n" +
                    "service call statusbar 1 >/dev/null 2>&1\n");
            if (!reflectionWorked && !rootWorked) ui.post(() -> {
                Toast.makeText(this, "Esta ROM no expone la barra de notificaciones", Toast.LENGTH_SHORT).show();
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_SETTINGS)); }
                catch (Exception ignored) {}
            });
        });
    }

    private boolean runRootScript(String script) {
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

    private long availableRamMb() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / (1024 * 1024);
        } catch (Exception e) { return 0; }
    }

    private void updateClock() {
        Date now = new Date();
        clockText.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        amPmText.setText(new SimpleDateFormat("a", Locale.getDefault()).format(now).toLowerCase(Locale.getDefault()));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "DO")).format(now);
        if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        dateText.setText(d);
    }

    private void updateNetworkIcon() {
        if (networkIcon == null) return;
        int res = R.drawable.ic_top_offline;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
            if (active != null && active.isConnected()) {
                res = active.getType() == ConnectivityManager.TYPE_WIFI
                        ? R.drawable.ic_top_wifi : R.drawable.ic_top_ethernet;
            }
        } catch (Exception ignored) {}
        networkIcon.setImageResource(res);
        networkIcon.setColorFilter(Color.WHITE);
    }

    private void openNetworkSettings() {
        try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        catch (Exception e) { openSystem(Settings.ACTION_SETTINGS); }
    }

    private void volumeDown() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    private void volumeUp() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
    }

    private void showVolumeUi() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
    }

    private void openSystem(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception e) { Toast.makeText(this, "Opción no disponible", Toast.LENGTH_SHORT).show(); }
    }

    private int calculateTileSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int byWidth = (dm.widthPixels - dp(76) - dp(22) * (COLS - 1)) / COLS;
        int byHeight = (dm.heightPixels - dp(166) - dp(48) - dp(44)) / 3;
        return Math.max(dp(112), Math.min(byWidth, byHeight));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        renderApps();
        updateClock();
        updateNetworkIcon();
        refreshAppCacheAsync();
        ui.postDelayed(this::ensureLauncherOwnership, 350);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private class AppPickerAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> apps;
        AppPickerAdapter(Context context, List<AppEntry> apps) { this.context = context; this.apps = apps; }
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(8), dp(14), dp(8));
                row.setBackgroundColor(Color.TRANSPARENT);
                row.setFocusable(false);
                row.setClickable(false);
                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

                ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                row.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));

                LinearLayout labels = new LinearLayout(context);
                labels.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                labelsLp.leftMargin = dp(16);
                row.addView(labels, labelsLp);
                TextView name = text("", 17, Color.WHITE, true);
                TextView pkg = text("", 11, MUTED, false);
                labels.addView(name);
                labels.addView(pkg);
                h = new Holder(); h.icon = icon; h.name = name; h.pkg = pkg;
                row.setTag(h);
                convertView = row;
            } else h = (Holder) convertView.getTag();

            AppEntry app = apps.get(position);
            h.icon.setImageDrawable(app.icon);
            h.name.setText(app.label);
            h.pkg.setText(app.pkg);
            return convertView;
        }
    }

    private static class Holder { ImageView icon; TextView name; TextView pkg; }
    private static class AppEntry {
        String pkg; String cls; String label; Drawable icon;
        String component() { return pkg + "|" + cls; }
    }
}
