package com.mokano.mokahome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.Application;
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
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MokaHome 1.0
 * Capa avanzada sobre 0.6: actualizaciones, launcher manager, backup,
 * power menu, informacion del sistema, RAM suave y reordenamiento.
 */
public class MokaHomeAppV1 extends MokaHomeApp {
    private static final String PREFS = "mokahome";
    private static final String EXEMPT_KEY = "killer_exempt_packages_v5";
    private static final String DISABLED_V1 = "disabled_launchers_v1";
    private static final int ACCENT = Color.rgb(142, 76, 27);
    private static final int GREEN = Color.rgb(0, 215, 92);
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        super.onCreate();
        // La vieja capa v0.4 no debe volver a deshabilitar launchers por su cuenta.
        prefs(this).edit().putBoolean("launcher_takeover_done_v4", true).apply();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        super.onActivityCreated(activity, state);
        if (!isHome(activity)) return;
        activity.getWindow().getDecorView().postDelayed(() -> wireV1(activity), 1450);
        activity.getWindow().getDecorView().postDelayed(() -> wireV1(activity), 2600);
    }

    @Override public void onActivityResumed(Activity activity) {
        super.onActivityResumed(activity);
        if (!isHome(activity)) return;
        activity.getWindow().getDecorView().postDelayed(() -> wireV1(activity), 1500);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        super.onActivityDestroyed(activity);
    }

    private boolean isHome(Activity a) {
        return a != null && a.getClass().getName().equals("com.mokano.mokahome.MainActivityV5");
    }

    private void wireV1(Activity a) {
        if (a == null || a.isFinishing()) return;
        LinearLayout tools = findToolsRow(a.getWindow().getDecorView());
        if (tools != null && tools.getChildCount() >= 13) {
            View ram = tools.getChildAt(0);
            View killer = tools.getChildAt(2);
            View network = tools.getChildAt(4);
            View settings = tools.getChildAt(12);

            ram.setOnClickListener(v -> gentleRam(a));
            ram.setContentDescription("Liberar RAM sin cerrar apps protegidas");

            // Conserva el killer de V5; solo reforzamos el acceso directo a protegidas.
            killer.setOnLongClickListener(v -> {
                ProtectedAppsDialog.show(a);
                return true;
            });

            network.setOnLongClickListener(v -> {
                showSystemInfo(a);
                return true;
            });

            settings.setOnClickListener(v -> showV1Settings(a));
            settings.setOnLongClickListener(v -> {
                openAndroidSettings(a);
                return true;
            });
        }
        wireTileLongPress(a);
    }

    private LinearLayout findToolsRow(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) view;
            if (row.getChildCount() >= 13 && row.getChildAt(0) instanceof ImageView &&
                    row.getChildAt(2) instanceof ImageView && row.getChildAt(4) instanceof ImageView &&
                    row.getChildAt(6) instanceof ImageView && row.getChildAt(8) instanceof ImageView &&
                    row.getChildAt(12) instanceof ImageView) return row;
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

    private void wireTileLongPress(Activity a) {
        try {
            ViewGroup content = a.findViewById(android.R.id.content);
            if (content == null || content.getChildCount() == 0 || !(content.getChildAt(0) instanceof LinearLayout)) return;
            LinearLayout root = (LinearLayout) content.getChildAt(0);
            if (root.getChildCount() < 2 || !(root.getChildAt(1) instanceof LinearLayout)) return;
            LinearLayout area = (LinearLayout) root.getChildAt(1);
            int index = 0;
            for (int r = 0; r < area.getChildCount(); r++) {
                View rv = area.getChildAt(r);
                if (!(rv instanceof ViewGroup)) continue;
                ViewGroup row = (ViewGroup) rv;
                for (int c = 0; c < row.getChildCount(); c++) {
                    View tile = row.getChildAt(c);
                    if (!(tile instanceof FrameLayout)) continue;
                    // Los cuadros de app tienen TextView; el + no.
                    if (directText((ViewGroup) tile) != null) {
                        final int idx = index++;
                        tile.setLongClickable(true);
                        tile.setOnLongClickListener(v -> {
                            showTileTools(a, idx);
                            return true;
                        });
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private TextView directText(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof TextView) return (TextView) g.getChildAt(i);
        return null;
    }

    private void showTileTools(Activity a, int index) {
        Dialog d = dialog(a);
        LinearLayout root = dialogRoot(a);
        root.addView(title(a, "Organizar aplicación"));
        root.addView(action(a, "Cambiar o eliminar", () -> {
            d.dismiss();
            invokeOriginalTileMenu(a, index);
        }));
        root.addView(action(a, "Mover a la izquierda", () -> { d.dismiss(); moveFavorite(a, index, index - 1, true); }));
        root.addView(action(a, "Mover a la derecha", () -> { d.dismiss(); moveFavorite(a, index, index + 1, true); }));
        root.addView(action(a, "Mover arriba", () -> { d.dismiss(); moveFavorite(a, index, index - 6, false); }));
        root.addView(action(a, "Mover abajo", () -> { d.dismiss(); moveFavorite(a, index, index + 6, false); }));
        root.addView(close(a, d));
        d.setContentView(root);
        showDialog(d, a, dp(a, 500), dp(a, 405));
    }

    private void invokeOriginalTileMenu(Activity a, int index) {
        try {
            Method m = MainActivityV4.class.getDeclaredMethod("showTileOptions", int.class);
            m.setAccessible(true);
            m.invoke(a, index);
        } catch (Exception e) {
            toast(a, "No pude abrir el editor del cuadro");
        }
    }

    private void moveFavorite(Activity a, int from, int to, boolean horizontal) {
        List<String> favs = loadFavorites(a);
        if (from < 0 || from >= favs.size() || to < 0 || to >= favs.size()) {
            toast(a, "No se puede mover más"); return;
        }
        if (horizontal && (from / 6 != to / 6)) {
            toast(a, "Usa arriba o abajo para cambiar de fila"); return;
        }
        String tmp = favs.get(from);
        favs.set(from, favs.get(to));
        favs.set(to, tmp);
        saveFavorites(a, favs);
        rerender(a);
    }

    private List<String> loadFavorites(Context c) {
        List<String> out = new ArrayList<>();
        String raw = prefs(c).getString("favorites_csv", "");
        if (raw != null) for (String s : raw.split("\\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private void saveFavorites(Context c, List<String> favs) {
        StringBuilder sb = new StringBuilder();
        for (String s : favs) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        prefs(c).edit().putString("favorites_csv", sb.toString()).commit();
    }

    private void rerender(Activity a) {
        try {
            Method m = MainActivityV4.class.getDeclaredMethod("renderApps");
            m.setAccessible(true);
            m.invoke(a);
            a.getWindow().getDecorView().postDelayed(() -> wireV1(a), 180);
        } catch (Exception e) {
            a.recreate();
        }
    }

    private void gentleRam(Activity a) {
        final long before = availableRamMb(a);
        exec.execute(() -> {
            Set<String> protectedPkgs = protectedPackages(a);
            PackageManager pm = a.getPackageManager();
            StringBuilder script = new StringBuilder();
            int count = 0;
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    String pkg = ai.packageName;
                    if (!safePackage(pkg) || protectedPkgs.contains(pkg) || critical(pkg)) continue;
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    if (system && pm.getLaunchIntentForPackage(pkg) == null) continue;
                    script.append("am kill ").append(pkg).append(" >/dev/null 2>&1\n");
                    count++;
                }
            } catch (Exception ignored) {}
            boolean ok = runRoot(script.toString());
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            long after = availableRamMb(a);
            long gain = Math.max(0, after - before);
            final int processed = count;
            a.runOnUiThread(() -> toast(a, ok
                    ? after + " MB libres" + (gain > 0 ? "  (+" + gain + " MB)" : "") + " · " + processed + " procesos revisados"
                    : "No pude obtener acceso root"));
        });
    }

    private Set<String> protectedPackages(Context c) {
        Set<String> out = new HashSet<>();
        out.add(c.getPackageName()); out.add("android"); out.add("com.android.systemui");
        out.add("com.android.settings"); out.add("com.google.android.gms"); out.add("com.google.android.gsf");
        String raw = prefs(c).getString(EXEMPT_KEY, "");
        if (raw != null) for (String s : raw.split("\\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private boolean critical(String pkg) {
        return pkg == null || pkg.startsWith("com.android.providers") || pkg.startsWith("com.android.permission") ||
                pkg.startsWith("com.android.packageinstaller") || pkg.startsWith("com.google.android.packageinstaller") ||
                pkg.startsWith("com.android.inputmethod") || pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.android.phone") || pkg.startsWith("com.android.shell") ||
                pkg.startsWith("com.android.bluetooth") || pkg.startsWith("com.android.networkstack");
    }

    private void showV1Settings(Activity a) {
        Dialog d = dialog(a);
        LinearLayout outer = dialogRoot(a);
        outer.addView(title(a, "Configuración de MokaHome"));
        TextView ver = text(a, "MokaHome 1.0 · Android 9 optimizado", 12, muted(a), false);
        ver.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 8));
        outer.addView(ver);

        ScrollView scroll = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        outer.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addSection(a, body, "PERSONALIZACIÓN");
        body.addView(action(a, "Apariencia, audio, reloj y tamaño", () -> {
            d.dismiss(); invokeV06Settings(a);
        }));
        body.addView(action(a, "Apps protegidas del App Killer", () -> {
            d.dismiss(); ProtectedAppsDialog.show(a);
        }));

        addSection(a, body, "SISTEMA");
        body.addView(action(a, "Administrador de launcher", () -> { d.dismiss(); showLauncherManager(a); }));
        body.addView(action(a, "Copia de seguridad / Restaurar", () -> { d.dismiss(); showBackupManager(a); }));
        body.addView(action(a, "Información del sistema", () -> { d.dismiss(); showSystemInfo(a); }));
        body.addView(action(a, "Buscar actualización", () -> { d.dismiss(); UpdateManager.check(a, true); }));
        body.addView(action(a, "Reiniciar / Apagar", () -> { d.dismiss(); showPowerMenu(a); }));
        body.addView(action(a, "Configuración de Android", () -> { d.dismiss(); openAndroidSettings(a); }));

        TextView hint = text(a, "Mantén pulsada una app para moverla, cambiarla o eliminarla. Mantén pulsada la red para ver información del sistema.", 12, muted(a), false);
        hint.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 4));
        outer.addView(hint);
        outer.addView(close(a, d));
        d.setContentView(outer);
        showDialog(d, a, Math.min(dp(a, 720), (int)(a.getResources().getDisplayMetrics().widthPixels * .68f)),
                Math.min(dp(a, 760), (int)(a.getResources().getDisplayMetrics().heightPixels * .86f)));
    }

    private void invokeV06Settings(Activity a) {
        try {
            Method m = MokaHomeApp.class.getDeclaredMethod("showSettings", Activity.class);
            m.setAccessible(true);
            m.invoke(this, a);
        } catch (Exception e) {
            toast(a, "No pude abrir las opciones visuales");
        }
    }

    private void showLauncherManager(Activity a) {
        Dialog d = dialog(a);
        LinearLayout root = dialogRoot(a);
        root.addView(title(a, "Administrador de launcher"));
        TextView current = text(a, "Inicio actual: " + currentHomePackage(a), 13, muted(a), false);
        current.setPadding(dp(a, 10), 0, dp(a, 10), dp(a, 10)); root.addView(current);
        root.addView(action(a, "Usar MokaHome como Inicio", () -> {
            exec.execute(() -> {
                boolean ok = runRoot("cmd package set-home-activity --user 0 com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1 || cmd package set-home-activity com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1");
                a.runOnUiThread(() -> toast(a, ok ? "MokaHome establecido como Inicio" : "No pude cambiar el launcher"));
            });
        }));
        root.addView(action(a, "Desactivar otros launchers", () -> disableOtherLaunchers(a)));
        root.addView(action(a, "Reactivar launchers anteriores", () -> reactivateLaunchers(a)));
        root.addView(close(a, d));
        d.setContentView(root); showDialog(d, a, dp(a, 560), dp(a, 330));
    }

    private String currentHomePackage(Activity a) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN); home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = a.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Exception ignored) {}
        return "No detectado";
    }

    private List<String> otherHomeLaunchers(Activity a) {
        List<String> out = new ArrayList<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN); home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : a.getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (p == null || p.equals(a.getPackageName()) || p.equals("android") || p.equals("com.android.systemui") || p.equals("com.android.settings")) continue;
                if (!out.contains(p)) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void disableOtherLaunchers(Activity a) {
        List<String> list = otherHomeLaunchers(a);
        if (list.isEmpty()) { toast(a, "No encontré otro launcher activo"); return; }
        StringBuilder raw = new StringBuilder(); StringBuilder script = new StringBuilder();
        script.append("cmd package set-home-activity --user 0 com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1\n");
        for (String p : list) if (safePackage(p)) {
            if (raw.length() > 0) raw.append('\n'); raw.append(p);
            script.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1\n");
        }
        prefs(a).edit().putString(DISABLED_V1, raw.toString()).commit();
        exec.execute(() -> {
            boolean ok = runRoot(script.toString());
            a.runOnUiThread(() -> toast(a, ok ? list.size() + " launcher(s) desactivado(s)" : "No pude desactivarlos"));
        });
    }

    private void reactivateLaunchers(Activity a) {
        String raw = prefs(a).getString(DISABLED_V1, "");
        String legacy = prefs(a).getString("disabled_launchers_v4", "");
        String all = (raw == null ? "" : raw) + "\n" + (legacy == null ? "" : legacy);
        StringBuilder script = new StringBuilder(); int count = 0;
        for (String p : all.split("\\n")) if (safePackage(p.trim())) {
            script.append("pm enable --user 0 ").append(p.trim()).append(" >/dev/null 2>&1\n"); count++;
        }
        final int total = count;
        if (total == 0) { toast(a, "No hay launchers guardados"); return; }
        exec.execute(() -> {
            boolean ok = runRoot(script.toString());
            if (ok) prefs(a).edit().remove(DISABLED_V1).remove("disabled_launchers_v4").remove("disabled_launcher").apply();
            a.runOnUiThread(() -> toast(a, ok ? total + " launcher(s) reactivado(s)" : "No pude reactivarlos"));
        });
    }

    private void showBackupManager(Activity a) {
        Dialog d = dialog(a); LinearLayout root = dialogRoot(a);
        root.addView(title(a, "Copia de seguridad"));
        TextView path = text(a, "/sdcard/MokaHome/mokahome-backup.xml", 12, muted(a), false);
        path.setPadding(dp(a, 10), 0, dp(a, 10), dp(a, 10)); root.addView(path);
        root.addView(action(a, "Crear copia ahora", () -> backup(a)));
        root.addView(action(a, "Restaurar última copia", () -> restoreBackup(a)));
        root.addView(close(a, d)); d.setContentView(root); showDialog(d, a, dp(a, 560), dp(a, 280));
    }

    private void backup(Activity a) {
        exec.execute(() -> {
            boolean ok = runRoot("mkdir -p /sdcard/MokaHome\ncp /data/data/com.mokano.mokahome/shared_prefs/mokahome.xml /sdcard/MokaHome/mokahome-backup.xml\nchmod 644 /sdcard/MokaHome/mokahome-backup.xml");
            a.runOnUiThread(() -> toast(a, ok ? "Copia guardada en /sdcard/MokaHome" : "No pude crear la copia"));
        });
    }

    private void restoreBackup(Activity a) {
        exec.execute(() -> {
            boolean ok = runRoot("test -f /sdcard/MokaHome/mokahome-backup.xml || exit 7\nUID=$(stat -c %u /data/data/com.mokano.mokahome)\nmkdir -p /data/data/com.mokano.mokahome/shared_prefs\ncp /sdcard/MokaHome/mokahome-backup.xml /data/data/com.mokano.mokahome/shared_prefs/mokahome.xml\nchown $UID:$UID /data/data/com.mokano.mokahome/shared_prefs/mokahome.xml\nchmod 660 /data/data/com.mokano.mokahome/shared_prefs/mokahome.xml\nrestorecon /data/data/com.mokano.mokahome/shared_prefs/mokahome.xml >/dev/null 2>&1 || true");
            a.runOnUiThread(() -> {
                toast(a, ok ? "Configuración restaurada" : "No encontré una copia válida");
                if (ok) a.getWindow().getDecorView().postDelayed(a::recreate, 400);
            });
        });
    }

    private void showPowerMenu(Activity a) {
        Dialog d = dialog(a); LinearLayout root = dialogRoot(a);
        root.addView(title(a, "Energía"));
        root.addView(action(a, "Reiniciar", () -> confirmPower(a, d, true)));
        root.addView(action(a, "Apagar", () -> confirmPower(a, d, false)));
        root.addView(close(a, d)); d.setContentView(root); showDialog(d, a, dp(a, 430), dp(a, 245));
    }

    private void confirmPower(Activity a, Dialog parent, boolean reboot) {
        parent.dismiss(); Dialog d = dialog(a); LinearLayout root = dialogRoot(a);
        root.addView(title(a, reboot ? "¿Reiniciar ahora?" : "¿Apagar ahora?"));
        root.addView(action(a, reboot ? "REINICIAR" : "APAGAR", () -> {
            d.dismiss(); exec.execute(() -> runRoot(reboot ? "reboot" : "reboot -p >/dev/null 2>&1 || poweroff"));
        }));
        root.addView(close(a, d)); d.setContentView(root); showDialog(d, a, dp(a, 420), dp(a, 205));
    }

    private void showSystemInfo(Activity a) {
        exec.execute(() -> {
            String network = networkInfo(a);
            long ram = availableRamMb(a);
            long total = totalRamMb(a);
            long freeStorage = freeStorageMb();
            String temp = cpuTemp();
            String value = "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT + "\n" +
                    "Equipo: " + Build.MODEL + "\n" +
                    "RAM libre: " + ram + " / " + total + " MB\n" +
                    "Almacenamiento libre: " + freeStorage + " MB\n" +
                    "Red: " + network + "\n" +
                    "Resolución: " + a.getResources().getDisplayMetrics().widthPixels + " × " + a.getResources().getDisplayMetrics().heightPixels + "\n" +
                    (temp.isEmpty() ? "" : "CPU: " + temp + "\n") +
                    "MokaHome 1.0";
            a.runOnUiThread(() -> {
                Dialog d = dialog(a); LinearLayout root = dialogRoot(a);
                root.addView(title(a, "Información del sistema"));
                TextView info = text(a, value, 15, fg(a), false);
                info.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 14)); info.setLineSpacing(0, 1.25f); root.addView(info);
                root.addView(close(a, d)); d.setContentView(root); showDialog(d, a, dp(a, 560), dp(a, 390));
            });
        });
    }

    private String networkInfo(Activity a) {
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Sin conexión";
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "LAN";
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) {
                    int level = WifiManager.calculateSignalLevel(wi.getRssi(), 101);
                    return "Wi‑Fi " + level + "% · " + ip(wi.getIpAddress());
                }
                return "Wi‑Fi";
            }
            return ni.getTypeName();
        } catch (Exception e) { return "Conectado"; }
    }

    private String ip(int addr) {
        return String.format(Locale.US, "%d.%d.%d.%d", addr & 255, (addr >> 8) & 255, (addr >> 16) & 255, (addr >> 24) & 255);
    }

    private String cpuTemp() {
        String s = captureRoot("cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null");
        try {
            if (s != null && !s.trim().isEmpty()) {
                float v = Float.parseFloat(s.trim()); if (v > 1000) v /= 1000f;
                return String.format(Locale.US, "%.1f °C", v);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private long freeStorageMb() {
        try { StatFs s = new StatFs(Environment.getDataDirectory().getAbsolutePath()); return s.getAvailableBytes() / (1024L * 1024L); }
        catch (Exception e) { return 0; }
    }

    private long availableRamMb(Context c) {
        try { ActivityManager am = (ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE); ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.availMem/(1024L*1024L); }
        catch (Exception e) { return 0; }
    }

    private long totalRamMb(Context c) {
        try { ActivityManager am = (ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE); ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return mi.totalMem/(1024L*1024L); }
        catch (Exception e) { return 0; }
    }

    private void openAndroidSettings(Activity a) {
        try { Intent i = new Intent(Settings.ACTION_SETTINGS); i.setPackage("com.android.settings"); a.startActivity(i); return; } catch (Exception ignored) {}
        try { Intent i = new Intent(); i.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings")); a.startActivity(i); return; } catch (Exception ignored) {}
        try { a.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception e) { toast(a, "Configuración no disponible"); }
    }

    private void addSection(Activity a, LinearLayout body, String value) {
        TextView v = text(a, value, 11, muted(a), true); v.setPadding(dp(a, 10), dp(a, 14), dp(a, 10), dp(a, 4)); body.addView(v);
    }

    private Dialog dialog(Activity a) { Dialog d = new Dialog(a); d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setCanceledOnTouchOutside(true); return d; }
    private LinearLayout dialogRoot(Activity a) { LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.VERTICAL); r.setBackgroundColor(bg(a)); r.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 12)); return r; }
    private TextView title(Activity a, String s) { TextView t = text(a, s, 22, fg(a), true); t.setPadding(dp(a, 8), 0, dp(a, 8), dp(a, 8)); return t; }

    private TextView action(Activity a, String s, Runnable run) {
        TextView v = text(a, s, 17, fg(a), false); v.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); v.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,8));
        v.setFocusable(true); v.setClickable(true); v.setBackground(rowBg(a,false)); v.setOnFocusChangeListener((x,f)->x.setBackground(rowBg(a,f))); v.setOnClickListener(x->run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a,52)); lp.topMargin=dp(a,2); v.setLayoutParams(lp); return v;
    }

    private TextView close(Activity a, Dialog d) { TextView v=text(a,"CERRAR",13,fg(a),true); v.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL); v.setPadding(dp(a,12),dp(a,8),dp(a,10),dp(a,4)); v.setFocusable(true); v.setClickable(true); v.setOnClickListener(x->d.dismiss()); return v; }
    private GradientDrawable rowBg(Context c, boolean focused) { GradientDrawable g=new GradientDrawable(); g.setColor(bg(c)); g.setCornerRadius(dp(c,9)); if(focused)g.setStroke(dp(c,2),fg(c)); return g; }
    private TextView text(Context c,String s,int sp,int color,boolean bold){ TextView v=new TextView(c);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v; }
    private int bg(Context c){ return MokaHomeApp.bg(c); }
    private int fg(Context c){ return MokaHomeApp.fg(c); }
    private int muted(Context c){ return MokaHomeApp.muted(c); }
    private int dp(Context c,int v){ return Math.round(v*c.getResources().getDisplayMetrics().density); }
    private SharedPreferences prefs(Context c){ return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private void toast(Context c,String s){ Toast.makeText(c,s,Toast.LENGTH_SHORT).show(); }
    private boolean safePackage(String p){ return p!=null && p.matches("[A-Za-z0-9._]+"); }

    private boolean runRoot(String script) {
        if (script == null || script.trim().isEmpty()) return true;
        Process su=null; DataOutputStream os=null;
        try { su=Runtime.getRuntime().exec("su"); os=new DataOutputStream(su.getOutputStream()); os.writeBytes(script); if(!script.endsWith("\n"))os.writeBytes("\n"); os.writeBytes("exit\n"); os.flush(); return su.waitFor()==0; }
        catch(Exception e){ return false; }
        finally { try{if(os!=null)os.close();}catch(Exception ignored){} if(su!=null)su.destroy(); }
    }

    private String captureRoot(String cmd) {
        Process p=null; try { p=Runtime.getRuntime().exec(new String[]{"su","-c",cmd}); BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream())); StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null){if(sb.length()>0)sb.append('\n');sb.append(line);} p.waitFor(); return sb.toString(); }
        catch(Exception e){ return ""; } finally { if(p!=null)p.destroy(); }
    }

    private void showDialog(Dialog d, Context c, int width, int height) {
        d.show(); Window w=d.getWindow(); if(w!=null){w.setBackgroundDrawable(new ColorDrawable(bg(c)));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams p=w.getAttributes();p.dimAmount=.62f;w.setAttributes(p);w.setLayout(width,height);}
    }
}