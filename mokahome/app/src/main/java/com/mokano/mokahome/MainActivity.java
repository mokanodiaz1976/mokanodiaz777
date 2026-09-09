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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final int FAVORITE_COUNT = 8;
    private static final int COLS = 4;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout favoritesArea;
    private TextView clockText;
    private TextView dateText;
    private TextView ramText;
    private TextView killerStatus;
    private TextView killerLabel;
    private View killerCard;

    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            updateClock();
            refreshRam();
            uiHandler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildHome());
        initializeFavoritesIfNeeded();
        renderFavorites();
        updateClock();
        refreshRam();
        uiHandler.postDelayed(clockTask, 30_000);
    }

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 15, 18));
        root.setPadding(dp(42), dp(28), dp(42), dp(28));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(118)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        top.addView(titleBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView brand = text("MokaHome", 34, Color.WHITE, true);
        titleBox.addView(brand);

        ramText = text("RAM", 15, Color.rgb(156, 163, 175), false);
        LinearLayout.LayoutParams ramLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ramLp.topMargin = dp(8);
        titleBox.addView(ramText, ramLp);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.RIGHT);
        top.addView(clockBox, new LinearLayout.LayoutParams(dp(360),
                LinearLayout.LayoutParams.MATCH_PARENT));

        clockText = text("--:--", 40, Color.WHITE, true);
        clockText.setGravity(Gravity.RIGHT);
        clockBox.addView(clockText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        dateText = text("", 16, Color.rgb(174, 181, 190), false);
        dateText.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(4);
        clockBox.addView(dateText, dateLp);

        TextView favTitle = text("Favoritas", 20, Color.rgb(226, 230, 235), true);
        root.addView(favTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        favoritesArea = new LinearLayout(this);
        favoritesArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(favoritesArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView utilTitle = text("Accesos rápidos", 18, Color.rgb(200, 205, 212), true);
        LinearLayout.LayoutParams utilTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        utilTitleLp.topMargin = dp(8);
        root.addView(utilTitle, utilTitleLp);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(utilityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(156)));

        utilityRow.addView(createUtilityCard("APPS", "Todas las apps", this::showAllApps), cardLp(0));

        killerCard = createUtilityCard("✕", "Cerrar apps", this::killBackgroundApps);
        killerLabel = (TextView) killerCard.getTag();
        utilityRow.addView(killerCard, cardLp(1));

        utilityRow.addView(createUtilityCard("⚙", "Ajustes", () -> openSystem(Settings.ACTION_SETTINGS)), cardLp(2));
        utilityRow.addView(createUtilityCard("Wi‑Fi", "Red inalámbrica", () -> openSystem(Settings.ACTION_WIFI_SETTINGS)), cardLp(3));

        killerStatus = text("", 13, Color.rgb(145, 151, 160), false);
        killerStatus.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30));
        statusLp.topMargin = dp(6);
        root.addView(killerStatus, statusLp);

        return root;
    }

    private void renderFavorites() {
        favoritesArea.removeAllViews();
        List<AppEntry> apps = getLaunchableApps();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (rowIndex == 0) rowLp.bottomMargin = dp(14);
            favoritesArea.addView(row, rowLp);

            for (int c = 0; c < COLS; c++) {
                int slot = rowIndex * COLS + c;
                String component = prefs.getString("fav_" + slot, "");
                AppEntry entry = findByComponent(apps, component);
                row.addView(createFavoriteCard(entry, slot), cardLp(c));
            }
        }
    }

    private View createFavoriteCard(AppEntry entry, int slot) {
        LinearLayout card = baseCard();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (entry != null && entry.icon != null) {
            icon.setImageDrawable(entry.icon);
        } else {
            icon.setImageDrawable(getDrawable(android.R.drawable.ic_input_add));
        }
        card.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView label = text(entry != null ? entry.label : "Agregar", 18, Color.WHITE, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(12);
        card.addView(label, labelLp);

        if (entry != null) {
            card.setOnClickListener(v -> launchApp(entry));
        } else {
            card.setOnClickListener(v -> chooseFavorite(slot));
        }
        card.setOnLongClickListener(v -> {
            chooseFavorite(slot);
            return true;
        });
        card.setLongClickable(true);
        return card;
    }

    private View createUtilityCard(String symbol, String label, Runnable action) {
        LinearLayout card = baseCard();
        card.setGravity(Gravity.CENTER);
        card.setOrientation(LinearLayout.VERTICAL);

        TextView symbolView = text(symbol, symbol.length() > 3 ? 22 : 34, Color.WHITE, true);
        symbolView.setGravity(Gravity.CENTER);
        card.addView(symbolView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        TextView labelView = text(label, 18, Color.WHITE, true);
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxLines(1);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(labelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.setTag(labelView);
        card.setOnClickListener(v -> action.run());
        return card;
    }

    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBackground(false));
        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(cardBackground(focused));
            v.animate().scaleX(focused ? 1.025f : 1f)
                    .scaleY(focused ? 1.025f : 1f).setDuration(90).start();
        });
        return card;
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(focused ? Color.rgb(42, 46, 53) : Color.rgb(27, 30, 35));
        gd.setCornerRadius(dp(22));
        gd.setStroke(dp(focused ? 3 : 1),
                focused ? Color.WHITE : Color.rgb(52, 56, 64));
        return gd;
    }

    private LinearLayout.LayoutParams cardLp(int col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (col > 0) lp.leftMargin = dp(8);
        if (col < COLS - 1) lp.rightMargin = dp(8);
        return lp;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private void initializeFavoritesIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("favorites_initialized", false)) return;

        List<AppEntry> apps = getLaunchableApps();
        List<AppEntry> ordered = new ArrayList<>();
        String[] priorities = new String[] {
                "smarttube", "mokatube", "youtube", "netflix",
                "spotify", "chrome", "browser", "file", "explorer"
        };

        Set<String> used = new HashSet<>();
        for (String p : priorities) {
            for (AppEntry app : apps) {
                String haystack = (app.pkg + " " + app.label).toLowerCase(Locale.US);
                if (!used.contains(app.component()) && haystack.contains(p)) {
                    ordered.add(app);
                    used.add(app.component());
                    break;
                }
            }
            if (ordered.size() >= FAVORITE_COUNT) break;
        }
        for (AppEntry app : apps) {
            if (ordered.size() >= FAVORITE_COUNT) break;
            if (!used.contains(app.component())) {
                ordered.add(app);
                used.add(app.component());
            }
        }

        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < ordered.size() && i < FAVORITE_COUNT; i++) {
            ed.putString("fav_" + i, ordered.get(i).component());
        }
        ed.putBoolean("favorites_initialized", true).apply();
    }

    private void chooseFavorite(int slot) {
        List<AppEntry> apps = getLaunchableApps();
        String[] names = new String[apps.size() + 1];
        names[0] = "— Vaciar este espacio —";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Elegir aplicación")
                .setItems(names, (d, which) -> {
                    SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    if (which == 0) ed.remove("fav_" + slot);
                    else ed.putString("fav_" + slot, apps.get(which - 1).component());
                    ed.apply();
                    renderFavorites();
                })
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getListView().setTextColor(Color.WHITE));
        dialog.show();
    }

    private void showAllApps() {
        List<AppEntry> apps = getLaunchableApps();
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Todas las aplicaciones")
                .setItems(names, (d, which) -> launchApp(apps.get(which)))
                .setNegativeButton("Cerrar", null)
                .create();
        dialog.show();
    }

    private List<AppEntry> getLaunchableApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            String cls = ri.activityInfo.name;
            String key = pkg + "|" + cls;
            if (seen.contains(key)) continue;
            seen.add(key);

            CharSequence labelCs = ri.loadLabel(pm);
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.cls = cls;
            e.label = labelCs == null ? pkg : labelCs.toString();
            try { e.icon = ri.loadIcon(pm); } catch (Exception ignored) {}
            apps.add(e);
        }

        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return apps;
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
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSystem(String action) {
        try { startActivity(new Intent(action)); }
        catch (Exception e) { Toast.makeText(this, "Opción no disponible", Toast.LENGTH_SHORT).show(); }
    }

    private void killBackgroundApps() {
        if (killerCard != null) killerCard.setEnabled(false);
        if (killerLabel != null) killerLabel.setText("Cerrando…");
        if (killerStatus != null) killerStatus.setText("Liberando aplicaciones en segundo plano");

        worker.submit(() -> {
            List<String> targets = collectKillTargets();
            int attempted = targets.size();
            boolean rootOk = false;

            Process su = null;
            DataOutputStream os = null;
            try {
                su = Runtime.getRuntime().exec("su");
                os = new DataOutputStream(su.getOutputStream());
                os.writeBytes("id\n");
                for (String pkg : targets) {
                    if (pkg.matches("[A-Za-z0-9._]+")) {
                        os.writeBytes("am force-stop " + pkg + " >/dev/null 2>&1\n");
                    }
                }
                os.writeBytes("exit\n");
                os.flush();
                int code = su.waitFor();
                rootOk = code == 0;
            } catch (Exception ignored) {
                rootOk = false;
            } finally {
                try { if (os != null) os.close(); } catch (Exception ignored) {}
                if (su != null) su.destroy();
            }

            final boolean ok = rootOk;
            final int count = attempted;
            uiHandler.post(() -> {
                if (killerCard != null) killerCard.setEnabled(true);
                if (killerLabel != null) killerLabel.setText("Cerrar apps");
                if (killerStatus != null) {
                    killerStatus.setText(ok ? "✓ " + count + " aplicaciones cerradas" :
                            "No pude obtener acceso root");
                }
                refreshRam();
                uiHandler.postDelayed(() -> {
                    if (killerStatus != null) killerStatus.setText("");
                }, 2800);
            });
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
            if (ramText != null) ramText.setText("RAM libre  " + freeMb + " MB  /  " + totalMb + " MB");
        } catch (Exception ignored) {}
    }

    private void updateClock() {
        Date now = new Date();
        DateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        DateFormat dateFmt = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "DO"));
        if (clockText != null) clockText.setText(timeFmt.format(now));
        if (dateText != null) {
            String s = dateFmt.format(now);
            if (!s.isEmpty()) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
            dateText.setText(s);
        }
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
