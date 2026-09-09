package com.mokano.mokahome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.text.DateFormat;
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

public class MainActivity extends Activity {
    private static final String PREFS = "mokahome";
    private static final int COLS = 4;
    private static final int MAX_TILES = 12;
    private static final int INITIAL_TILES = 8;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout favoritesArea;
    private TextView clockText;
    private TextView dateText;
    private TextView ramText;
    private TextView killerStatus;
    private TextView killerLabel;
    private View killerCard;
    private ImageView networkIcon;
    private TextView networkLabel;
    private TextView volumeLabel;

    private int tileSize;

    private final Runnable statusTask = new Runnable() {
        @Override
        public void run() {
            updateClock();
            refreshRam();
            updateNetworkStatus();
            updateVolumeStatus();
            uiHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tileSize = calculateTileSize();
        migrateFavoritesStorage();
        initializeFavoritesIfNeeded();
        setContentView(buildHome());
        renderFavorites();
        updateClock();
        refreshRam();
        updateNetworkStatus();
        updateVolumeStatus();
        uiHandler.postDelayed(statusTask, 5000);
        uiHandler.postDelayed(this::ensureDefaultLauncher, 900);
    }

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 13, 16));
        root.setPadding(dp(30), dp(18), dp(30), dp(18));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92)));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView favTitle = text("Aplicaciones", 20, Color.rgb(232, 235, 239), true);
        titleRow.addView(favTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView help = text("Mantén OK para cambiar o eliminar", 13,
                Color.rgb(135, 142, 152), false);
        titleRow.addView(help);
        root.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        favoritesArea = new LinearLayout(this);
        favoritesArea.setOrientation(LinearLayout.VERTICAL);
        favoritesArea.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        scroll.addView(favoritesArea, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView utilTitle = text("Accesos rápidos", 15,
                Color.rgb(175, 181, 190), true);
        LinearLayout.LayoutParams utilTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        utilTitleLp.topMargin = dp(6);
        root.addView(utilTitle, utilTitleLp);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        utilityRow.setGravity(Gravity.CENTER);
        root.addView(utilityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88)));

        utilityRow.addView(createUtilityCard("APPS", "Todas las apps", this::showAllApps), utilityLp(0));
        killerCard = createUtilityCard("✕", "Cerrar apps", this::killBackgroundApps);
        killerLabel = (TextView) killerCard.getTag();
        utilityRow.addView(killerCard, utilityLp(1));
        utilityRow.addView(createUtilityCard("⚙", "Ajustes", () -> openSystem(Settings.ACTION_SETTINGS)), utilityLp(2));
        utilityRow.addView(createUtilityCard("Wi‑Fi", "Red", () -> openSystem(Settings.ACTION_WIFI_SETTINGS)), utilityLp(3));

        killerStatus = text("", 12, Color.rgb(145, 151, 160), false);
        killerStatus.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        root.addView(killerStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        return root;
    }

    private View buildHeader() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("MokaHome", 30, Color.WHITE, true);
        titleBox.addView(brand);
        ramText = text("RAM", 13, Color.rgb(145, 151, 160), false);
        LinearLayout.LayoutParams ramLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ramLp.topMargin = dp(5);
        titleBox.addView(ramText, ramLp);
        top.addView(titleBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        View network = createNetworkChip();
        top.addView(network, headerLp(dp(116)));

        top.addView(createSmallHeaderButton("−", this::volumeDown), headerLp(dp(48)));
        top.addView(createVolumeChip(), headerLp(dp(86)));
        top.addView(createSmallHeaderButton("+", this::volumeUp), headerLp(dp(48)));
        top.addView(createIconHeaderButton(R.drawable.ic_notifications, "Notificaciones", this::expandNotifications),
                headerLp(dp(60)));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        clockText = text("--:--", 28, Color.WHITE, true);
        clockText.setGravity(Gravity.RIGHT);
        clockBox.addView(clockText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        dateText = text("", 12, Color.rgb(160, 166, 176), false);
        dateText.setGravity(Gravity.RIGHT);
        clockBox.addView(dateText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(dp(220),
                LinearLayout.LayoutParams.MATCH_PARENT);
        clockLp.leftMargin = dp(12);
        top.addView(clockBox, clockLp);

        return top;
    }

    private LinearLayout.LayoutParams headerLp(int width) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(58));
        lp.leftMargin = dp(8);
        return lp;
    }

    private View createNetworkChip() {
        LinearLayout chip = headerChip();
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setOnClickListener(v -> openSystem(Settings.ACTION_WIFI_SETTINGS));

        networkIcon = new ImageView(this);
        networkIcon.setColorFilter(Color.WHITE);
        chip.addView(networkIcon, new LinearLayout.LayoutParams(dp(25), dp(25)));

        networkLabel = text("Red", 14, Color.WHITE, true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(8);
        chip.addView(networkLabel, labelLp);
        return chip;
    }

    private View createVolumeChip() {
        LinearLayout chip = headerChip();
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER);
        chip.setOnClickListener(v -> {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
        });

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_volume);
        icon.setColorFilter(Color.WHITE);
        chip.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        volumeLabel = text("--", 13, Color.WHITE, true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(6);
        chip.addView(volumeLabel, labelLp);
        return chip;
    }

    private View createSmallHeaderButton(String value, Runnable action) {
        TextView button = text(value, 28, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        styleHeaderFocusable(button);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private View createIconHeaderButton(int iconRes, String description, Runnable action) {
        FrameLayout box = new FrameLayout(this);
        styleHeaderFocusable(box);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setContentDescription(description);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(15);
        box.setPadding(p, p, p, p);
        box.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        box.setOnClickListener(v -> action.run());
        return box;
    }

    private LinearLayout headerChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        styleHeaderFocusable(chip);
        return chip;
    }

    private void styleHeaderFocusable(View view) {
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(headerBackground(false));
        view.setOnFocusChangeListener((v, focused) -> v.setBackground(headerBackground(focused)));
    }

    private GradientDrawable headerBackground(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? Color.rgb(42, 46, 53) : Color.rgb(24, 27, 32));
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : Color.rgb(48, 53, 61));
        return gd;
    }

    private void renderFavorites() {
        if (favoritesArea == null) return;
        favoritesArea.removeAllViews();

        List<AppEntry> apps = getLaunchableApps();
        List<String> stored = loadFavorites();
        List<String> clean = new ArrayList<>();
        for (String component : stored) {
            if (findByComponent(apps, component) != null && clean.size() < MAX_TILES) clean.add(component);
        }
        if (!clean.equals(stored)) saveFavorites(clean);

        int cellCount = clean.size() + (clean.size() < MAX_TILES ? 1 : 0);
        int rows = Math.max(1, (cellCount + COLS - 1) / COLS);
        rows = Math.min(3, rows);
        int position = 0;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, tileSize);
            if (r > 0) rowLp.topMargin = dp(12);
            favoritesArea.addView(row, rowLp);

            for (int c = 0; c < COLS; c++) {
                View cell;
                if (position < clean.size()) {
                    AppEntry entry = findByComponent(apps, clean.get(position));
                    cell = createAppTile(entry, position);
                } else if (position == clean.size() && clean.size() < MAX_TILES) {
                    cell = createAddTile();
                } else {
                    cell = new View(this);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSize, tileSize);
                if (c > 0) lp.leftMargin = dp(12);
                row.addView(cell, lp);
                position++;
            }
        }
    }

    private View createAppTile(AppEntry entry, int index) {
        LinearLayout card = baseAppCard();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (entry != null && entry.icon != null) icon.setImageDrawable(entry.icon);
        card.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView label = text(entry != null ? entry.label : "Aplicación", 17, Color.WHITE, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(10);
        card.addView(label, labelLp);

        if (entry != null) card.setOnClickListener(v -> launchApp(entry));
        card.setOnLongClickListener(v -> {
            showTileOptions(index);
            return true;
        });
        return card;
    }

    private View createAddTile() {
        LinearLayout card = baseAppCard();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);
        TextView plus = text("+", 46, Color.rgb(220, 224, 230), false);
        plus.setGravity(Gravity.CENTER);
        card.addView(plus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        TextView label = text("Agregar", 16, Color.rgb(190, 196, 205), true);
        label.setGravity(Gravity.CENTER);
        card.addView(label);
        card.setOnClickListener(v -> addNewTile());
        return card;
    }

    private LinearLayout baseAppCard() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        card.setBackground(appCardBackground(false));
        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(appCardBackground(focused));
            v.animate().scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f).setDuration(80).start();
        });
        return card;
    }

    private GradientDrawable appCardBackground(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? Color.rgb(42, 46, 53) : Color.rgb(25, 28, 33));
        gd.setCornerRadius(dp(24));
        gd.setStroke(dp(focused ? 3 : 1),
                focused ? Color.WHITE : Color.rgb(49, 54, 63));
        return gd;
    }

    private View createUtilityCard(String symbol, String label, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(headerBackground(false));
        card.setOnFocusChangeListener((v, focused) -> v.setBackground(headerBackground(focused)));

        TextView symbolView = text(symbol, symbol.length() > 3 ? 14 : 24, Color.WHITE, true);
        symbolView.setGravity(Gravity.CENTER);
        card.addView(symbolView, new LinearLayout.LayoutParams(dp(52),
                LinearLayout.LayoutParams.MATCH_PARENT));

        TextView labelView = text(label, 15, Color.WHITE, true);
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        labelView.setMaxLines(1);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(labelView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        card.setTag(labelView);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private LinearLayout.LayoutParams utilityLp(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (col > 0) lp.leftMargin = dp(8);
        return lp;
    }

    private void showTileOptions(int index) {
        List<String> favorites = loadFavorites();
        if (index < 0 || index >= favorites.size()) return;
        AppEntry current = findByComponent(getLaunchableApps(), favorites.get(index));
        String title = current != null ? current.label : "Aplicación";
        String[] options = {"Cambiar aplicación", "Eliminar cuadro"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) chooseReplacement(index);
                    else deleteTile(index);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addNewTile() {
        List<String> favorites = loadFavorites();
        if (favorites.size() >= MAX_TILES) {
            Toast.makeText(this, "Máximo de 12 cuadros", Toast.LENGTH_SHORT).show();
            return;
        }
        showAppPicker("Agregar aplicación", app -> {
            List<String> list = loadFavorites();
            if (list.contains(app.component())) {
                Toast.makeText(this, "Esa aplicación ya está en Inicio", Toast.LENGTH_SHORT).show();
                return;
            }
            list.add(app.component());
            saveFavorites(list);
            renderFavorites();
        });
    }

    private void chooseReplacement(int index) {
        showAppPicker("Cambiar aplicación", app -> {
            List<String> list = loadFavorites();
            if (index < 0 || index >= list.size()) return;
            int existing = list.indexOf(app.component());
            if (existing >= 0 && existing != index) {
                Toast.makeText(this, "Esa aplicación ya está en Inicio", Toast.LENGTH_SHORT).show();
                return;
            }
            list.set(index, app.component());
            saveFavorites(list);
            renderFavorites();
        });
    }

    private void deleteTile(int index) {
        List<String> list = loadFavorites();
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        saveFavorites(list);
        renderFavorites();
    }

    private interface AppSelectionListener {
        void onSelected(AppEntry app);
    }

    private void showAppPicker(String title, AppSelectionListener listener) {
        List<AppEntry> apps = getLaunchableApps();
        ListView list = new ListView(this);
        list.setDividerHeight(1);
        list.setBackgroundColor(Color.rgb(17, 19, 23));
        list.setAdapter(new AppPickerAdapter(apps));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(list)
                .setNegativeButton("Cancelar", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            listener.onSelected(apps.get(position));
        });
        dialog.show();
        list.requestFocus();
    }

    private void showAllApps() {
        showAppPicker("Todas las aplicaciones", this::launchApp);
    }

    private class AppPickerAdapter extends BaseAdapter {
        private final List<AppEntry> apps;
        AppPickerAdapter(List<AppEntry> apps) { this.apps = apps; }
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry app = apps.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setMinimumHeight(dp(72));
            row.setBackgroundColor(Color.rgb(20, 23, 27));
            row.setFocusable(true);

            ImageView icon = new ImageView(MainActivity.this);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.leftMargin = dp(16);
            row.addView(texts, textsLp);

            TextView name = text(app.label, 17, Color.WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            TextView pkg = text(app.pkg, 11, Color.rgb(135, 142, 152), false);
            pkg.setSingleLine(true);
            pkg.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(pkg);
            return row;
        }
    }

    private void migrateFavoritesStorage() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("favorites_v2", false)) return;
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < MAX_TILES; i++) {
            String value = prefs.getString("fav_" + i, "");
            if (value != null && !value.isEmpty()) existing.add(value);
        }
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean("favorites_v2", true);
        ed.putInt("fav_count", existing.size());
        for (int i = 0; i < existing.size(); i++) ed.putString("fav_" + i, existing.get(i));
        ed.apply();
    }

    private void initializeFavoritesIfNeeded() {
        List<String> current = loadFavorites();
        if (!current.isEmpty()) return;

        List<AppEntry> apps = getLaunchableApps();
        List<String> ordered = new ArrayList<>();
        String[] priorities = new String[] {
                "smarttube", "mokatube", "youtube", "netflix",
                "spotify", "chrome", "browser", "file", "explorer"
        };
        for (String p : priorities) {
            for (AppEntry app : apps) {
                String haystack = (app.pkg + " " + app.label).toLowerCase(Locale.US);
                if (!ordered.contains(app.component()) && haystack.contains(p)) {
                    ordered.add(app.component());
                    break;
                }
            }
            if (ordered.size() >= INITIAL_TILES) break;
        }
        for (AppEntry app : apps) {
            if (ordered.size() >= INITIAL_TILES) break;
            if (!ordered.contains(app.component())) ordered.add(app.component());
        }
        saveFavorites(ordered);
    }

    private List<String> loadFavorites() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int count = Math.min(MAX_TILES, Math.max(0, prefs.getInt("fav_count", 0)));
        List<String> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String value = prefs.getString("fav_" + i, "");
            if (value != null && !value.isEmpty()) list.add(value);
        }
        return list;
    }

    private void saveFavorites(List<String> list) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < MAX_TILES; i++) ed.remove("fav_" + i);
        int count = Math.min(MAX_TILES, list.size());
        for (int i = 0; i < count; i++) ed.putString("fav_" + i, list.get(i));
        ed.putInt("fav_count", count);
        ed.putBoolean("favorites_v2", true);
        ed.apply();
    }

    private List<AppEntry> getLaunchableApps() {
        PackageManager pm = getPackageManager();
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectLaunchableApps(pm, Intent.CATEGORY_LAUNCHER, apps, seen);
        collectLaunchableApps(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, apps, seen);
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return apps;
    }

    private void collectLaunchableApps(PackageManager pm, String category,
                                       List<AppEntry> apps, Set<String> seen) {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(category);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            String cls = ri.activityInfo.name;
            String key = pkg + "|" + cls;
            if (!seen.add(key)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.cls = cls;
            CharSequence labelCs = ri.loadLabel(pm);
            e.label = labelCs == null ? pkg : labelCs.toString();
            try { e.icon = ri.loadIcon(pm); } catch (Exception ignored) {}
            apps.add(e);
        }
    }

    private AppEntry findByComponent(List<AppEntry> apps, String component) {
        if (component == null || component.isEmpty()) return null;
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
        } catch (Exception first) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(app.pkg);
                if (i != null) startActivity(i);
                else throw first;
            } catch (Exception e) {
                Toast.makeText(this, "No pude abrir " + app.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openSystem(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception e) { Toast.makeText(this, "Opción no disponible", Toast.LENGTH_SHORT).show(); }
    }

    private void updateNetworkStatus() {
        if (networkIcon == null || networkLabel == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
            if (info == null || !info.isConnected()) {
                networkIcon.setImageResource(R.drawable.ic_network_off);
                networkLabel.setText("Sin red");
            } else if (info.getType() == ConnectivityManager.TYPE_ETHERNET) {
                networkIcon.setImageResource(R.drawable.ic_ethernet);
                networkLabel.setText("LAN");
            } else if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                networkIcon.setImageResource(R.drawable.ic_wifi);
                networkLabel.setText("Wi‑Fi");
            } else {
                networkIcon.setImageResource(R.drawable.ic_wifi);
                networkLabel.setText("Red");
            }
        } catch (Exception ignored) {
            networkIcon.setImageResource(R.drawable.ic_network_off);
            networkLabel.setText("Red");
        }
    }

    private void volumeUp() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE, 0);
        updateVolumeStatus();
    }

    private void volumeDown() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER, 0);
        updateVolumeStatus();
    }

    private void updateVolumeStatus() {
        if (volumeLabel == null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int pct = max > 0 ? Math.round(current * 100f / max) : 0;
            volumeLabel.setText(pct + "%");
        } catch (Exception ignored) {}
    }

    private void expandNotifications() {
        worker.submit(() -> {
            boolean ok = runRootCommand("cmd statusbar expand-notifications >/dev/null 2>&1 || service call statusbar 1 >/dev/null 2>&1");
            if (!ok) uiHandler.post(() -> Toast.makeText(this,
                    "No pude abrir las notificaciones", Toast.LENGTH_SHORT).show());
        });
    }

    private void ensureDefaultLauncher() {
        if (isDefaultLauncher()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("default_attempt_v2", false)) return;
        prefs.edit().putBoolean("default_attempt_v2", true).apply();

        worker.submit(() -> {
            runRootCommand("cmd package set-home-activity --user 0 com.mokano.mokahome/.MainActivity >/dev/null 2>&1 || pm set-home-activity --user 0 com.mokano.mokahome/.MainActivity >/dev/null 2>&1");
            try { Thread.sleep(450); } catch (InterruptedException ignored) {}
            boolean selected = isDefaultLauncher();
            uiHandler.post(() -> {
                if (selected) {
                    Toast.makeText(this, "MokaHome quedó como Inicio predeterminado", Toast.LENGTH_SHORT).show();
                } else {
                    try {
                        Toast.makeText(this, "Selecciona MokaHome como aplicación de Inicio", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Pulsa HOME y selecciona MokaHome → Siempre", Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    private boolean isDefaultLauncher() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private void killBackgroundApps() {
        if (killerCard != null) killerCard.setEnabled(false);
        if (killerLabel != null) killerLabel.setText("Cerrando…");
        if (killerStatus != null) killerStatus.setText("Liberando aplicaciones en segundo plano");

        worker.submit(() -> {
            List<String> targets = collectKillTargets();
            StringBuilder command = new StringBuilder();
            for (String pkg : targets) {
                if (pkg.matches("[A-Za-z0-9._]+")) {
                    command.append("am force-stop ").append(pkg).append(" >/dev/null 2>&1; ");
                }
            }
            boolean ok = runRootCommand(command.toString());
            int count = targets.size();
            uiHandler.post(() -> {
                if (killerCard != null) killerCard.setEnabled(true);
                if (killerLabel != null) killerLabel.setText("Cerrar apps");
                if (killerStatus != null) killerStatus.setText(ok ?
                        "✓ " + count + " aplicaciones cerradas" : "No pude obtener acceso root");
                refreshRam();
                uiHandler.postDelayed(() -> {
                    if (killerStatus != null) killerStatus.setText("");
                }, 2600);
            });
        });
    }

    private boolean runRootCommand(String command) {
        Process su = null;
        DataOutputStream os = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            return su.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (su != null) su.destroy();
        }
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
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
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
                pkg.startsWith("com.android.phone") ||
                pkg.startsWith("com.android.shell") ||
                pkg.startsWith("com.android.bluetooth") ||
                pkg.startsWith("com.android.networkstack");
    }

    private void refreshRam() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long freeMb = mi.availMem / (1024 * 1024);
            long totalMb = mi.totalMem / (1024 * 1024);
            if (ramText != null) ramText.setText("RAM libre  " + freeMb + " MB / " + totalMb + " MB");
        } catch (Exception ignored) {}
    }

    private void updateClock() {
        Date now = new Date();
        DateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        DateFormat dateFmt = new SimpleDateFormat("EEE d MMM", new Locale("es", "DO"));
        if (clockText != null) clockText.setText(timeFmt.format(now));
        if (dateText != null) dateText.setText(dateFmt.format(now));
    }

    private int calculateTileSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / dm.density;
        float desired = (widthDp - 100f) / 4f;
        desired = Math.max(118f, Math.min(176f, desired));
        return dp(Math.round(desired));
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderFavorites();
        updateClock();
        refreshRam();
        updateNetworkStatus();
        updateVolumeStatus();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private static class AppEntry {
        String pkg;
        String cls;
        String label;
        Drawable icon;
        String component() { return pkg + "|" + cls; }
    }
}
