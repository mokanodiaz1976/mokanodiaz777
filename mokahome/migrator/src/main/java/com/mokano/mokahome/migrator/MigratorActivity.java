package com.mokano.mokahome.migrator;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Migracion de una sola pulsacion desde las firmas antiguas de MokaHome a la linea 1.x.
 * Conserva /shared_prefs/mokahome.xml, instala la nueva APK, la fija como HOME y
 * vuelve a desactivar los launchers alternativos.
 */
public class MigratorActivity extends Activity {
    private static final String OLD_PKG = "com.mokano.mokahome";
    private static final String NEW_COMPONENT = "com.mokano.mokahome/.MainActivityV5";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView button;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(80), dp(60), dp(80), dp(60));
        root.setBackgroundColor(Color.rgb(20,20,20));

        TextView title = text("MokaHome 1.0", 38, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = text("Migración automática", 19, Color.rgb(170,170,170), false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2,-2);
        slp.topMargin = dp(6); root.addView(sub, slp);

        TextView info = text("Conserva tus aplicaciones, orden, apps protegidas, audio y preferencias visuales.\nNo necesitas desinstalar MokaHome ni usar Rescue.", 16, Color.rgb(205,205,205), false);
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-2,-2);
        ilp.topMargin = dp(28); root.addView(info, ilp);

        button = text("ACTUALIZAR A MOKAHOME 1.0", 17, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setPadding(dp(30), dp(16), dp(30), dp(16));
        button.setBackground(buttonBg(false));
        button.setOnFocusChangeListener((v,f)->v.setBackground(buttonBg(f)));
        button.setOnClickListener(v -> migrate());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(420), dp(64));
        blp.topMargin = dp(34); root.addView(button, blp);

        status = text("Listo para migrar", 13, Color.rgb(150,150,150), false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-2,-2);
        stlp.topMargin = dp(20); root.addView(status, stlp);
        return root;
    }

    private void migrate() {
        button.setEnabled(false);
        button.setAlpha(.55f);
        status.setText("Preparando actualización...");
        final List<String> otherLaunchers = findOtherHomeLaunchers();

        worker.execute(() -> {
            try {
                File apk = new File(getFilesDir(), "MokaHome-v1.0.apk");
                extractAsset("MokaHome-v1.0.apk", apk);
                runOnUiThread(() -> status.setText("Guardando configuración actual..."));

                StringBuilder script = new StringBuilder();
                script.append("PKG=").append(OLD_PKG).append("\n");
                script.append("APK=/data/local/tmp/MokaHome-v1.0.apk\n");
                script.append("BACK=/data/local/tmp/mokahome-migration.xml\n");
                script.append("cp '").append(apk.getAbsolutePath()).append("' $APK || exit 11\n");
                script.append("OLD=/data/data/$PKG/shared_prefs/mokahome.xml\n");
                script.append("[ -f $OLD ] && cp $OLD $BACK\n");
                script.append("pm uninstall $PKG >/data/local/tmp/mokahome-old-uninstall.log 2>&1 || pm uninstall --user 0 $PKG >/data/local/tmp/mokahome-old-uninstall.log 2>&1\n");
                script.append("sleep 1\n");
                script.append("pm install $APK >/data/local/tmp/mokahome-new-install.log 2>&1 || exit 12\n");
                script.append("am start -n ").append(NEW_COMPONENT).append(" >/dev/null 2>&1\n");
                script.append("sleep 2\n");
                script.append("if [ -f $BACK ]; then\n");
                script.append(" UID=$(stat -c %u /data/data/$PKG)\n");
                script.append(" mkdir -p /data/data/$PKG/shared_prefs\n");
                script.append(" cp $BACK /data/data/$PKG/shared_prefs/mokahome.xml\n");
                script.append(" chown $UID:$UID /data/data/$PKG/shared_prefs/mokahome.xml\n");
                script.append(" chmod 660 /data/data/$PKG/shared_prefs/mokahome.xml\n");
                script.append(" restorecon /data/data/$PKG/shared_prefs/mokahome.xml >/dev/null 2>&1 || true\n");
                script.append("fi\n");
                script.append("cmd package set-home-activity --user 0 ").append(NEW_COMPONENT).append(" >/dev/null 2>&1 || cmd package set-home-activity ").append(NEW_COMPONENT).append(" >/dev/null 2>&1\n");
                for (String p : otherLaunchers) {
                    if (safePackage(p)) script.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1\n");
                }
                script.append("am force-stop $PKG >/dev/null 2>&1\n");
                script.append("am start -n ").append(NEW_COMPONENT).append(" >/dev/null 2>&1\n");
                script.append("rm -f $APK $BACK\n");

                int rc = runRoot(script.toString());
                runOnUiThread(() -> {
                    if (rc == 0) {
                        status.setText("Migración completada. Abriendo MokaHome...");
                        Toast.makeText(this, "MokaHome 1.0 instalado", Toast.LENGTH_LONG).show();
                        launchHome();
                        scheduleSelfRemoval();
                    } else {
                        status.setText("No se pudo completar (código " + rc + "). MokaHome anterior no fue borrado si la instalación nueva falló.");
                        button.setEnabled(true); button.setAlpha(1f);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Error preparando la migración");
                    button.setEnabled(true); button.setAlpha(1f);
                });
            }
        });
    }

    private List<String> findOtherHomeLaunchers() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            int flags = 0;
            if (android.os.Build.VERSION.SDK_INT >= 24) flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, flags)) {
                if (ri.activityInfo == null) continue;
                String p = ri.activityInfo.packageName;
                if (!safePackage(p) || p.equals(OLD_PKG) || p.equals(getPackageName()) || p.equals("android") || p.equals("com.android.systemui") || p.equals("com.android.settings")) continue;
                if (seen.add(p)) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void extractAsset(String name, File target) throws Exception {
        try (InputStream in = getAssets().open(name); FileOutputStream out = new FileOutputStream(target)) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
    }

    private int runRoot(String script) {
        Process su = null; DataOutputStream os = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(script); if (!script.endsWith("\n")) os.writeBytes("\n"); os.writeBytes("exit $?\n"); os.flush();
            return su.waitFor();
        } catch (Exception e) { return 99; }
        finally { try { if (os != null) os.close(); } catch (Exception ignored) {} if (su != null) su.destroy(); }
    }

    private void launchHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_HOME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
        } catch (Exception ignored) {}
    }

    private void scheduleSelfRemoval() {
        worker.execute(() -> {
            try { Thread.sleep(3500); } catch (InterruptedException ignored) {}
            try { Runtime.getRuntime().exec(new String[]{"su","-c","pm uninstall " + getPackageName() + " >/dev/null 2>&1"}); } catch (Exception ignored) {}
        });
    }

    private boolean safePackage(String p) { return p != null && p.matches("[A-Za-z0-9._]+"); }
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private GradientDrawable buttonBg(boolean focus){GradientDrawable g=new GradientDrawable();g.setColor(focus?Color.rgb(62,62,62):Color.rgb(45,45,45));g.setCornerRadius(dp(18));if(focus)g.setStroke(dp(3),Color.WHITE);return g;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
