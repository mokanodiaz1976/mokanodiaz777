package com.mokano.mokahome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Prepara el estado de launchers antes de que MokaHomeApp 0.6 ejecute su compatibilidad legacy.
 * Evita que versiones antiguas reactiven automaticamente el launcher que el usuario quiso desactivar.
 */
public class MokaHomeAppV10 extends MokaHomeAppV1 {
    private static final String PREFS = "mokahome";

    @Override public void onCreate() {
        preserveDisabledLaunchers();
        super.onCreate();
    }

    private void preserveDisabledLaunchers() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            Set<String> disabled = new LinkedHashSet<>();

            String v1 = p.getString("disabled_launchers_v1", "");
            addRaw(disabled, v1);
            addRaw(disabled, p.getString("disabled_launchers_v4", ""));
            String one = p.getString("disabled_launcher", "");
            if (safe(one)) disabled.add(one);

            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            int flags = android.os.Build.VERSION.SDK_INT >= 24 ? PackageManager.MATCH_DISABLED_COMPONENTS : 0;
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, flags)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (!safe(pkg) || pkg.equals(getPackageName()) || pkg.equals("android") ||
                        pkg.equals("com.android.systemui") || pkg.equals("com.android.settings")) continue;
                try {
                    int state = getPackageManager().getApplicationEnabledSetting(pkg);
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) disabled.add(pkg);
                } catch (Exception ignored) {}
            }

            StringBuilder sb = new StringBuilder();
            for (String pkg : disabled) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(pkg);
            }

            // La clase 0.6 solo intenta reactivar disabled_launchers_v4. Lo migramos antes de super.onCreate().
            p.edit()
                    .putString("disabled_launchers_v1", sb.toString())
                    .remove("disabled_launchers_v4")
                    .remove("disabled_launcher")
                    .putBoolean("launcher_takeover_done_v4", true)
                    .commit();
        } catch (Exception ignored) {}
    }

    private void addRaw(Set<String> out, String raw) {
        if (raw == null) return;
        for (String s : raw.split("\\n")) if (safe(s.trim())) out.add(s.trim());
    }

    private boolean safe(String p) {
        return p != null && p.matches("[A-Za-z0-9._]+");
    }
}
