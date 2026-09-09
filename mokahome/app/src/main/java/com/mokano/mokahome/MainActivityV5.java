package com.mokano.mokahome;

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
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MokaHome v0.5
 *
 * Extiende la UI estable de v0.4 y corrige dos puntos importantes:
 * 1) la rueda dentada abre un panel propio de MokaHome y desde ahi Android Settings;
 * 2) App Killer y Liberar RAM respetan una lista de aplicaciones protegidas.
 */
public class MainActivityV5 extends MainActivityV4 {
    private static final String PREFS = "mokahome";
    private static final String EXEMPT_KEY = "killer_exempt_packages_v5";

    private static final int BG = Color.rgb(25, 25, 24);
    private static final int MUTED = Color.rgb(163, 163, 163);
    private static final int GREEN = Color.rgb(0, 215, 92);
    private static final int RED = Color.rgb(255, 66, 70);
    private static final int LINE = Color.rgb(47, 47, 46);

    private final ExecutorService v5Worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().postDelayed(this::wireV5Tools, 80);
        getWindow().getDecorView().postDelayed(this::ensureV5Home, 350);
        getWindow().getDecorView().postDelayed(this::ensureV5Home, 1100);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().postDelayed(this::wireV5Tools, 100);
        getWindow().getDecorView().postDelayed(this::ensureV5Home, 650);
    }

    @Override
    protected void onDestroy() {
        v5Worker.shutdownNow();
        super.onDestroy();
    }

    /** Localiza la barra superior creada por v0.4 sin tocar su layout estable. */
    private void wireV5Tools() {
        LinearLayout tools = findToolsRow(getWindow().getDecorView());
        if (tools == null || tools.getChildCount() < 13) return;

        // Estructura v0.4: RAM, sep, Killer, sep, red, sep, apps, sep, bell, sep, volumen, sep, ajustes.
        View ram = tools.getChildAt(0);
        View killer = tools.getChildAt(2);
        View settings = tools.getChildAt(12);

        ram.setOnClickListener(v -> freeMemoryRespectingProtection());
        ram.setContentDescription("Liberar RAM respetando apps protegidas");

        killer.setOnClickListener(v -> killBackgroundAppsRespectingProtection());
        killer.setOnLongClickListener(v -> {
            showProtectedAppsManager();
            return true;
        });
        killer.setContentDescription("App Killer. Mantener pulsado para apps protegidas");

        // La rueda ya no depende directamente de ACTION_SETTINGS.
        settings.setOnClickListener(v -> showMokaSettings());
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
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LinearLayout found = findToolsRow(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void showMokaSettings() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = dialogRoot();
        root.addView(title("Configuracion"));

        TextView info = text("MokaHome 0.5", 13, MUTED, false);
        info.setPadding(dp(10), 0, dp(10), dp(10));
        root.addView(info);

        root.addView(action("Configuracion de Android", () -> {
            dialog.dismiss();
            openAndroidSettingsRobust();
        }));

        int protectedCount = loadUserExemptions().size();
        root.addView(action("Apps protegidas del App Killer  (" + protectedCount + ")", () -> {
            dialog.dismiss();
            showProtectedAppsManager();
        }));

        TextView hint = text("Consejo: tambien puedes mantener pulsado el icono rojo del App Killer para editar esta lista.",
                12, MUTED, false);
        hint.setPadding(dp(12), dp(12), dp(12), dp(10));
        root.addView(hint);

        root.addView(cancel(dialog));
        dialog.setContentView(root);
        showDialog(dialog, dp(520), dp(330));
    }

    /**
     * Abre Settings usando varios caminos porque algunas ROM Allwinner no resuelven bien
     * el intent generico android.settings.SETTINGS desde un launcher de terceros.
     */
    private void openAndroidSettingsRobust() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.setPackage("com.android.settings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {}

        v5Worker.execute(() -> {
            boolean ok = runRootScriptV5(
                    "am start -n com.android.settings/.Settings >/dev/null 2>&1 || " +
                    "am start -a android.settings.SETTINGS >/dev/null 2>&1\n");
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "Abriendo configuracion..." : "La ROM no permite abrir Configuracion",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void showProtectedAppsManager() {
        final List<ProtectedApp> apps = getProtectableApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, "No encontre aplicaciones para mostrar", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = dialogRoot();
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        root.addView(title("Apps protegidas del App Killer"));

        TextView subtitle = text("Toca una aplicacion para protegerla. Las protegidas no se cerraran al usar App Killer ni Liberar RAM.",
                13, MUTED, false);
        subtitle.setPadding(dp(8), 0, dp(8), dp(10));
        root.addView(subtitle);

        final Set<String> exempt = loadUserExemptions();
        final ProtectedAppsAdapter adapter = new ProtectedAppsAdapter(this, apps, exempt);

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setBackgroundColor(BG);
        list.setSelector(rowSelector());
        list.setItemsCanFocus(false);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(4);
        root.addView(list, lp);
        root.addView(cancel(dialog));

        list.setOnItemClickListener((parent, view, position, id) -> {
            toggleProtection(apps.get(position).pkg, exempt);
            adapter.notifyDataSetChanged();
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0 && pos < apps.size()) {
                    toggleProtection(apps.get(pos).pkg, exempt);
                    adapter.notifyDataSetChanged();
                    return true;
                }
            }
            return false;
        });

        dialog.setContentView(root);
        int width = Math.min(dp(760), (int) (getResources().getDisplayMetrics().widthPixels * 0.62f));
        int height = Math.min(dp(780), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        showDialog(dialog, width, height);
        list.requestFocus();
        list.setSelection(0);
    }

    private void toggleProtection(String pkg, Set<String> exempt) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean nowProtected;
        if (exempt.contains(pkg)) {
            exempt.remove(pkg);
            nowProtected = false;
        } else {
            exempt.add(pkg);
            nowProtected = true;
        }
        saveUserExemptions(exempt);
        Toast.makeText(this,
                nowProtected ? "Protegida del App Killer" : "Proteccion quitada",
                Toast.LENGTH_SHORT).show();
    }

    private Set<String> loadUserExemptions() {
        Set<String> out = new HashSet<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(EXEMPT_KEY, "");
        if (raw != null) {
            for (String p : raw.split("\\n")) {
                p = p.trim();
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    private void saveUserExemptions(Set<String> packages) {
        List<String> sorted = new ArrayList<>(packages);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String p : sorted) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(EXEMPT_KEY, sb.toString()).commit();
    }

    private List<ProtectedApp> getProtectableApps() {
        PackageManager pm = getPackageManager();
        List<ProtectedApp> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Primero aplicaciones con launcher.
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                if (ri.activityInfo == null) continue;
                addProtectable(out, seen, pm, ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {}

        // Tambien agrega apps de usuario que trabajan como servicio aunque no tengan icono launcher.
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!system) addProtectable(out, seen, pm, ai.packageName);
            }
        } catch (Exception ignored) {}

        Collections.sort(out, Comparator.comparing(a -> a.label.toLowerCase(Locale.getDefault())));
        return out;
    }

    private void addProtectable(List<ProtectedApp> out, Set<String> seen, PackageManager pm, String pkg) {
        if (pkg == null || pkg.equals(getPackageName()) || !seen.add(pkg) || isAlwaysProtected(pkg)) return;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            ProtectedApp app = new ProtectedApp();
            app.pkg = pkg;
            CharSequence label = pm.getApplicationLabel(ai);
            app.label = label == null ? pkg : label.toString();
            try { app.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            out.add(app);
        } catch (Exception ignored) {}
    }

    private void killBackgroundAppsRespectingProtection() {
        v5Worker.execute(() -> {
            List<String> targets = collectKillTargetsV5();
            StringBuilder script = new StringBuilder();
            for (String pkg : targets) {
                if (safePackage(pkg)) {
                    script.append("am force-stop ").append(pkg).append(" >/dev/null 2>&1\n");
                }
            }
            boolean ok = runRootScriptV5(script.toString());
            int protectedCount = loadUserExemptions().size();
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? targets.size() + " apps cerradas · " + protectedCount + " protegidas"
                            : "No pude obtener acceso root",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void freeMemoryRespectingProtection() {
        final long before = availableRamMbV5();
        v5Worker.execute(() -> {
            List<String> targets = collectKillTargetsV5();
            StringBuilder script = new StringBuilder();
            for (String pkg : targets) {
                if (safePackage(pkg)) {
                    script.append("am force-stop ").append(pkg).append(" >/dev/null 2>&1\n");
                }
            }
            boolean ok = runRootScriptV5(script.toString());
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            final long after = availableRamMbV5();
            final long gain = Math.max(0, after - before);
            final int protectedCount = loadUserExemptions().size();
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? after + " MB libres" + (gain > 0 ? "  (+" + gain + " MB)" : "") +
                            " · " + protectedCount + " protegidas"
                            : "No pude obtener acceso root",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private List<String> collectKillTargetsV5() {
        PackageManager pm = getPackageManager();
        Set<String> protectedPkgs = getProtectedPackagesV5();
        Set<String> targets = new HashSet<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String pkg = ai.packageName;
                if (protectedPkgs.contains(pkg) || isAlwaysProtected(pkg)) continue;
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean launchable = pm.getLaunchIntentForPackage(pkg) != null;
                if (!system || launchable) targets.add(pkg);
            }
        } catch (Exception ignored) {}
        targets.remove(getPackageName());
        return new ArrayList<>(targets);
    }

    private Set<String> getProtectedPackagesV5() {
        Set<String> out = loadUserExemptions();
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

        String disabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("disabled_launchers_v4", "");
        if (disabled != null) {
            for (String p : disabled.split("\\n")) if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    private boolean isAlwaysProtected(String pkg) {
        if (pkg == null) return true;
        return pkg.equals(getPackageName()) || pkg.equals("android") ||
                pkg.equals("com.android.systemui") || pkg.equals("com.android.settings") ||
                pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf") ||
                pkg.startsWith("com.android.providers") ||
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

    private boolean safePackage(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9._]+");
    }

    private long availableRamMbV5() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / (1024L * 1024L);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean runRootScriptV5(String script) {
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

    /** Mantiene v0.5 como actividad HOME aunque v0.4 siga siendo la clase base. */
    private void ensureV5Home() {
        v5Worker.execute(() -> {
            String component = getPackageName() + "/.MainActivityV5";
            boolean ok = runRootScriptV5(
                    "cmd package set-home-activity --user 0 " + component +
                    " >/dev/null 2>&1 || cmd package set-home-activity " + component +
                    " >/dev/null 2>&1\n");
            if (ok) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("launcher_takeover_done_v5", true).apply();
        });
    }

    private LinearLayout dialogRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(16), dp(18), dp(12));
        return root;
    }

    private TextView title(String value) {
        TextView title = text(value, 21, Color.WHITE, true);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(8), 0, dp(8), dp(8));
        return title;
    }

    private TextView action(String value, Runnable runnable) {
        TextView item = text(value, 18, Color.WHITE, false);
        item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        item.setPadding(dp(18), dp(12), dp(18), dp(12));
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackground(rowBackground(false));
        item.setOnFocusChangeListener((v, focused) -> v.setBackground(rowBackground(focused)));
        item.setOnClickListener(v -> runnable.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(4);
        item.setLayoutParams(lp);
        return item;
    }

    private TextView cancel(Dialog dialog) {
        TextView cancel = text("CERRAR", 13, Color.WHITE, true);
        cancel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cancel.setPadding(dp(12), dp(8), dp(10), dp(4));
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        return cancel;
    }

    private GradientDrawable rowBackground(boolean focused) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(BG);
        if (focused) gd.setStroke(dp(2), Color.WHITE);
        return gd;
    }

    private android.graphics.drawable.StateListDrawable rowSelector() {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rowBackground(true));
        states.addState(new int[]{android.R.attr.state_selected}, rowBackground(true));
        states.addState(new int[]{android.R.attr.state_focused}, rowBackground(true));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private void showDialog(Dialog dialog, int width, int height) {
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

    private class ProtectedAppsAdapter extends BaseAdapter {
        private final Context context;
        private final List<ProtectedApp> apps;
        private final Set<String> exempt;

        ProtectedAppsAdapter(Context context, List<ProtectedApp> apps, Set<String> exempt) {
            this.context = context;
            this.apps = apps;
            this.exempt = exempt;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ProtectHolder h;
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
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                TextView pkg = text("", 11, MUTED, false);
                pkg.setSingleLine(true);
                pkg.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(name);
                labels.addView(pkg);

                TextView status = text("", 12, MUTED, true);
                status.setGravity(Gravity.CENTER);
                status.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.addView(status, new LinearLayout.LayoutParams(dp(128), dp(44)));

                h = new ProtectHolder();
                h.icon = icon; h.name = name; h.pkg = pkg; h.status = status;
                row.setTag(h);
                convertView = row;
            } else {
                h = (ProtectHolder) convertView.getTag();
            }

            ProtectedApp app = apps.get(position);
            h.icon.setImageDrawable(app.icon);
            h.name.setText(app.label);
            h.pkg.setText(app.pkg);
            boolean protectedNow = exempt.contains(app.pkg);
            h.status.setText(protectedNow ? "PROTEGIDA" : "LIBRE");
            h.status.setTextColor(protectedNow ? GREEN : MUTED);
            return convertView;
        }
    }

    private static class ProtectHolder {
        ImageView icon;
        TextView name;
        TextView pkg;
        TextView status;
    }

    private static class ProtectedApp {
        String pkg;
        String label;
        Drawable icon;
    }
}
