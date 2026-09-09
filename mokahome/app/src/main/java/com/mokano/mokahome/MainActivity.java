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
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
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
    private static final int COLS = 6;
    private static final int MAX_TILES = 18;
    private static final int INITIAL_TILES = 8;

    private static final int BG = Color.rgb(24, 24, 23);
    private static final int CARD = Color.rgb(45, 45, 45);
    private static final int CARD_FOCUS = Color.rgb(57, 57, 57);
    private static final int MUTED = Color.rgb(165, 165, 165);
    private static final int GREEN = Color.rgb(0, 215, 92);
    private static final int RED = Color.rgb(255, 63, 67);
    private static final int SEP = Color.rgb(145, 77, 25);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout appsArea;
    private TextView clockText;
    private TextView amPmText;
    private TextView dateText;
    private ImageView networkIcon;
    private TextView ramTopText;
    private int tileSizePx;
    private long lastHomeTakeoverAt;
    private List<AppEntry> cachedApps = new ArrayList<>();

    private final Runnable minuteTask = new Runnable() {
        @Override
        public void run() {
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
        migrateFavoritesStorage();
        initializeFavoritesIfNeeded();

        setContentView(buildHome());
        renderApps();
        updateClock();
        updateNetworkIcon();

        ui.postDelayed(minuteTask, 60_000);
        ui.postDelayed(this::ensureLauncherOwnership, 700);
    }

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(38), dp(22), dp(38), dp(26));

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

        ramTopText = topTextButton("RAM", GREEN, this::freeMemory);
        ramTopText.setTextSize(16);
        tools.addView(ramTopText, toolLp(dp(82)));
        tools.addView(separator());

        TextView killer = topTextButton("✕", RED, this::killBackgroundApps);
        killer.setTextSize(34);
        tools.addView(killer, toolLp(dp(76)));
        tools.addView(separator());

        networkIcon = topIconButton(R.drawable.ic_network_off, this::openNetworkSettings);
        tools.addView(networkIcon, toolLp(dp(72)));
        tools.addView(separator());

        ImageView bell = topIconButton(R.drawable.ic_notifications, this::expandNotifications);
        tools.addView(bell, toolLp(dp(72)));
        tools.addView(separator());

        tools.addView(buildVolumeControl(), new LinearLayout.LayoutParams(dp(150), dp(68)));
        tools.addView(separator());

        TextView settings = topTextButton("⚙", Color.WHITE, () -> openSystem(Settings.ACTION_SETTINGS));
        settings.setTextSize(32);
        settings.setOnLongClickListener(v -> {
            showLauncherRecovery();
            return true;
        });
        tools.addView(settings, toolLp(dp(72)));

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
        block.addView(timeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(4);
        block.addView(dateText, dateLp);
        return block;
    }

    private TextView topTextButton(String value, int color, Runnable action) {
        TextView v = text(value, 26, color, true);
        v.setGravity(Gravity.CENTER);
        makeTopFocusable(v);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private ImageView topIconButton(int res, Runnable action) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(Color.WHITE);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        makeTopFocusable(v);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private View buildVolumeControl() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        TextView minus = topTextButton("−", Color.WHITE, this::volumeDown);
        minus.setTextSize(25);
        row.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(64)));

        ImageView speaker = topIconButton(R.drawable.ic_volume, this::showVolumeUi);
        row.addView(speaker, new LinearLayout.LayoutParams(dp(66), dp(64)));

        TextView plus = topTextButton("+", Color.WHITE, this::volumeUp);
        plus.setTextSize(24);
        row.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(64)));
        return row;
    }

    private void makeTopFocusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setAlpha(0.88f);
        v.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            view.animate()
                    .scaleX(focused ? 1.16f : 1f)
                    .scaleY(focused ? 1.16f : 1f)
                    .alpha(focused ? 1f : 0.88f)
                    .setDuration(65)
                    .start();
        });
    }

    private LinearLayout.LayoutParams toolLp(int width) {
        return new LinearLayout.LayoutParams(width, dp(68));
    }

    private View separator() {
        View sep = new View(this);
        sep.setBackgroundColor(SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(46));
        lp.leftMargin = dp(7);
        lp.rightMargin = dp(7);
        lp.topMargin = dp(10);
        sep.setLayoutParams(lp);
        return sep;
    }

    private void renderApps() {
        if (appsArea == null) return;
        appsArea.removeAllViews();
        if (cachedApps == null || cachedApps.isEmpty()) cachedApps = getLaunchableApps();

        List<String> stored = loadFavorites();
        List<String> clean = new ArrayList<>();
        for (String component : stored) {
            if (findByComponent(cachedApps, component) != null &&
                    !clean.contains(component) && clean.size() < MAX_TILES) {
                clean.add(component);
            }
        }
        if (!clean.equals(stored)) saveFavorites(clean);

        int cellCount = clean.size() + (clean.size() < MAX_TILES ? 1 : 0);
        int rows = Math.max(1, (cellCount + COLS - 1) / COLS);
        rows = Math.min(3, rows);

        int pos = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, tileSizePx);
            if (r > 0) rowLp.topMargin = dp(22);
            appsArea.addView(row, rowLp);

            for (int c = 0; c < COLS; c++) {
                if (pos >= cellCount) break;
                View tile;
                if (pos < clean.size()) {
                    tile = createAppTile(findByComponent(cachedApps, clean.get(pos)), pos);
                } else {
                    tile = createAddTile();
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSizePx, tileSizePx);
                if (c > 0) lp.leftMargin = dp(22);
                row.addView(tile, lp);
                pos++;
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
        int iconSize = Math.max(dp(62), (int)(tileSizePx * 0.48f));
        card.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));

        TextView label = text(app != null ? app.label : "", 13, Color.WHITE, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAlpha(0f);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM);
        labelLp.leftMargin = dp(8);
        labelLp.rightMargin = dp(8);
        labelLp.bottomMargin = dp(7);
        card.addView(label, labelLp);

        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(appCard(focused));
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f).setDuration(70).start();
            label.animate().alpha(focused ? 1f : 0f).setDuration(70).start();
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

        TextView plus = text("+", 58, Color.WHITE, false);
        plus.setGravity(Gravity.CENTER);
        card.addView(plus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(appCard(focused));
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.035f : 1f)
                    .scaleY(focused ? 1.035f : 1f).setDuration(70).start();
        });
        card.setOnClickListener(v -> addNewTile());
        return card;
    }

    private GradientDrawable appCard(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? CARD_FOCUS : CARD);
        gd.setCornerRadius(dp(22));
        gd.setStroke(dp(focused ? 3 : 0), focused ? Color.WHITE : CARD);
        return gd;
    }

    private void addNewTile() {
        if (loadFavorites().size() < MAX_TILES) showAppPicker(-1);
    }

    private void showTileOptions(int index) {
        String[] options = {"Cambiar aplicación", "Eliminar cuadro"};
        new AlertDialog.Builder(this)
                .setTitle("Editar cuadro")
                .setItems(options, (d, which) -> {
                    if (which == 0) showAppPicker(index);
                    else removeFavorite(index);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showAppPicker(int replaceIndex) {
        if (cachedApps == null || cachedApps.isEmpty()) cachedApps = getLaunchableApps();
        final List<AppEntry> apps = new ArrayList<>(cachedApps);

        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setAdapter(new AppPickerAdapter(this, apps));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(replaceIndex >= 0 ? "Cambiar aplicación" : "Agregar aplicación")
                .setView(list)
                .setNegativeButton("Cancelar", null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry selected = apps.get(position);
            List<String> favs = loadFavorites();
            favs.remove(selected.component());
            if (replaceIndex >= 0 && replaceIndex < favs.size()) {
                favs.set(replaceIndex, selected.component());
            } else if (replaceIndex >= 0 && replaceIndex == favs.size()) {
                favs.add(selected.component());
            } else if (favs.size() < MAX_TILES) {
                favs.add(selected.component());
            }
            saveFavorites(favs);
            dialog.dismiss();
            renderApps();
        });
        dialog.show();
    }

    private void removeFavorite(int index) {
        List<String> favs = loadFavorites();
        if (index >= 0 && index < favs.size()) {
            favs.remove(index);
            saveFavorites(favs);
            renderApps();
        }
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
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString("favorites_csv", sb.toString()).apply();
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
        long before = availableRamMb();
        ramTopText.setText("...");
        worker.execute(() -> {
            boolean ok = runRootScript("am kill-all >/dev/null 2>&1");
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            long after = availableRamMb();
            ui.post(() -> {
                ramTopText.setText("RAM");
                if (ok) {
                    long gain = Math.max(0, after - before);
                    String msg = gain > 0 ? "RAM libre: " + after + " MB  (+" + gain + " MB)"
                            : "RAM libre: " + after + " MB";
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No pude obtener acceso root", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void killBackgroundApps() {
        worker.execute(() -> {
            List<String> targets = collectKillTargets();
            StringBuilder script = new StringBuilder();
            for (String pkg : targets) {
                if (pkg.matches("[A-Za-z0-9._]+")) {
                    script.append("am force-stop ").append(pkg).append(" >/dev/null 2>&1\n");
                }
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
        String disabled = getSharedPreferences(PREFS, MODE_PRIVATE).getString("disabled_launcher", "");
        if (disabled != null && !disabled.isEmpty()) out.add(disabled);
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

    private void ensureLauncherOwnership() {
        long now = System.currentTimeMillis();
        if (now - lastHomeTakeoverAt < 10_000) return;
        lastHomeTakeoverAt = now;

        String current = currentHomePackage();
        if (getPackageName().equals(current) &&
                getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("launcher_takeover_done", false)) return;

        String old = isSafeLauncherPackage(current) ? current : "";
        worker.execute(() -> {
            String component = getPackageName() + "/.MainActivity";
            StringBuilder script = new StringBuilder();
            script.append("cmd package set-home-activity --user 0 ").append(component)
                    .append(" >/dev/null 2>&1 || cmd package set-home-activity ")
                    .append(component).append(" >/dev/null 2>&1\n");
            if (!old.isEmpty()) {
                script.append("pm disable-user --user 0 ").append(old).append(" >/dev/null 2>&1\n");
            }
            script.append("am start -a android.intent.action.MAIN -c android.intent.category.HOME -n ")
                    .append(component).append(" >/dev/null 2>&1\n");

            boolean ok = runRootScript(script.toString());
            if (ok) {
                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("launcher_takeover_done", true);
                if (!old.isEmpty()) ed.putString("disabled_launcher", old);
                ed.apply();
            }
        });
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

    private boolean isSafeLauncherPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName()) || pkg.equals("android") ||
                pkg.equals("com.android.systemui") || pkg.equals("com.android.settings")) return false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void showLauncherRecovery() {
        String old = getSharedPreferences(PREFS, MODE_PRIVATE).getString("disabled_launcher", "");
        if (old == null || old.isEmpty()) {
            Toast.makeText(this, "No hay launcher anterior guardado", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Launcher anterior")
                .setMessage("¿Reactivar " + old + "?")
                .setPositiveButton("Reactivar", (d, w) -> worker.execute(() -> {
                    boolean ok = runRootScript("pm enable " + old + " >/dev/null 2>&1");
                    if (ok) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .remove("disabled_launcher")
                                .putBoolean("launcher_takeover_done", false).apply();
                    }
                    ui.post(() -> Toast.makeText(this,
                            ok ? "Launcher anterior reactivado" : "No pude reactivarlo",
                            Toast.LENGTH_SHORT).show());
                }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void expandNotifications() {
        worker.execute(() -> {
            boolean ok = runRootScript(
                    "cmd statusbar expand-notifications >/dev/null 2>&1 || " +
                    "service call statusbar 1 >/dev/null 2>&1 || " +
                    "service call statusbar 2 >/dev/null 2>&1");
            if (!ok) ui.post(() -> Toast.makeText(this,
                    "La ROM no permitió abrir notificaciones", Toast.LENGTH_SHORT).show());
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
        amPmText.setText(new SimpleDateFormat("a", Locale.getDefault())
                .format(now).toLowerCase(Locale.getDefault()));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "DO")).format(now);
        if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        dateText.setText(d);
    }

    private void updateNetworkIcon() {
        if (networkIcon == null) return;
        int res = R.drawable.ic_network_off;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo active = cm == null ? null : cm.getActiveNetworkInfo();
            if (active != null && active.isConnected()) {
                if (active.getType() == ConnectivityManager.TYPE_WIFI) res = R.drawable.ic_wifi;
                else res = R.drawable.ic_ethernet;
            }
        } catch (Exception ignored) {}
        networkIcon.setImageResource(res);
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

    @Override
    protected void onResume() {
        super.onResume();
        cachedApps = getLaunchableApps();
        renderApps();
        updateClock();
        updateNetworkIcon();
        ui.postDelayed(this::ensureLauncherOwnership, 350);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private class AppPickerAdapter extends BaseAdapter {
        private final Context context;
        private final List<AppEntry> apps;

        AppPickerAdapter(Context context, List<AppEntry> apps) {
            this.context = context;
            this.apps = apps;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry app = apps.get(position);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));
            row.setFocusable(true);

            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelsLp.leftMargin = dp(16);
            row.addView(labels, labelsLp);
            labels.addView(text(app.label, 17, Color.WHITE, true));
            labels.addView(text(app.pkg, 11, Color.rgb(155, 155, 155), false));

            row.setBackground(appPickerRow(false));
            row.setOnFocusChangeListener((v, focused) -> v.setBackground(appPickerRow(focused)));
            return row;
        }
    }

    private GradientDrawable appPickerRow(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? Color.rgb(54, 54, 54) : Color.rgb(34, 34, 34));
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(focused ? 2 : 0), focused ? Color.WHITE : Color.TRANSPARENT);
        return gd;
    }

    private static class AppEntry {
        String pkg;
        String cls;
        String label;
        Drawable icon;
        String component() { return pkg + "|" + cls; }
    }
}
