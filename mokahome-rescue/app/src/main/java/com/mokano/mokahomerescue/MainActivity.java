package com.mokano.mokahomerescue;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String OLD = "com.mokano.mokahome";
    private static final int BG = Color.rgb(24,24,23);
    private static final int CARD = Color.rgb(45,45,45);
    private static final int GREEN = Color.rgb(0,215,92);
    private static final int RED = Color.rgb(255,66,70);
    private static final int MUTED = Color.rgb(170,170,170);

    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(70), dp(46), dp(70), dp(46));
        root.setBackgroundColor(BG);

        TextView title = text("MokaHome Rescue", 38, Color.WHITE, true);
        root.addView(title);

        TextView msg = text("V4 quedó como HOME y desactivó el launcher anterior. Esta utilidad lo recupera sin dejar la caja sin pantalla de inicio.",
                18, MUTED, false);
        msg.setPadding(0, dp(16), 0, dp(26));
        root.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(button("1  LIBERAR V4 Y REACTIVAR LAUNCHER", GREEN, this::rescueV4), lp());
        root.addView(button("2  ELIMINAR MOKAHOME V4", RED, this::uninstallV4), lp());
        root.addView(button("ABRIR INFORMACIÓN DE V4", Color.WHITE, this::openOldAppInfo), lp());

        status = text("Primero pulsa el botón 1. Cuando veas que el launcher anterior vuelve a estar disponible, usa el botón 2.",
                15, MUTED, false);
        status.setPadding(0, dp(24), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        lp.bottomMargin = dp(15);
        return lp;
    }

    private TextView button(String label, int accent, Runnable action) {
        TextView b = text(label, 18, accent, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(bg(false));
        b.setOnFocusChangeListener((v,f) -> v.setBackground(bg(f)));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private GradientDrawable bg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        if (focused) g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private void rescueV4() {
        status.setText("Buscando launcher anterior...");
        new Thread(() -> {
            Set<String> launchers = findAlternativeHomePackages();
            launchers.addAll(readSavedDisabledLaunchers());
            launchers.remove(OLD);
            launchers.remove(getPackageName());
            launchers.remove("android");

            if (launchers.isEmpty()) {
                runOnUiThread(() -> status.setText(
                        "No pude identificar automáticamente otro launcher. NO desactivé V4. Usa 'Abrir información de V4' solo después de instalar otro launcher."));
                return;
            }

            StringBuilder sh = new StringBuilder();
            for (String p : launchers) {
                if (safe(p)) sh.append("pm enable ").append(p).append(" >/dev/null 2>&1\n");
            }
            sh.append("cmd package clear-preferred-activities ").append(OLD).append(" >/dev/null 2>&1\n");
            sh.append("pm disable-user --user 0 ").append(OLD).append(" >/dev/null 2>&1\n");
            sh.append("input keyevent 3 >/dev/null 2>&1\n");

            boolean ok = runRoot(sh.toString());
            runOnUiThread(() -> {
                if (ok) {
                    status.setText("V4 quedó desactivada y reactivé " + launchers.size() +
                            " candidato(s) a launcher. Pulsa HOME. Si ves el launcher anterior, vuelve aquí y pulsa el botón 2.");
                    Toast.makeText(this, "V4 liberada", Toast.LENGTH_SHORT).show();
                } else {
                    status.setText("No obtuve acceso root. Concede permiso root a MokaHome Rescue y vuelve a intentarlo.");
                }
            });
        }).start();
    }

    private void uninstallV4() {
        status.setText("Eliminando V4...");
        new Thread(() -> {
            String output = runRootCapture(
                    "pm uninstall --user 0 " + OLD + " 2>&1 || pm uninstall " + OLD + " 2>&1");
            boolean success = output != null && output.toLowerCase().contains("success");
            runOnUiThread(() -> {
                status.setText(success
                        ? "V4 eliminada. Ya puedes instalar MokaHome V5 normalmente."
                        : "Android no confirmó la desinstalación. Abre 'Información de V4' e intenta Desinstalar ahora que ya no es HOME.");
                Toast.makeText(this, success ? "V4 eliminada" : "Revisa información de V4", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private Set<String> findAlternativeHomePackages() {
        Set<String> out = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, flags);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null)
                    out.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private Set<String> readSavedDisabledLaunchers() {
        Set<String> out = new HashSet<>();
        String xml = runRootCapture("cat /data/data/" + OLD + "/shared_prefs/mokahome.xml 2>/dev/null");
        if (xml == null || xml.isEmpty()) return out;
        Pattern block = Pattern.compile("<string name=\"(?:disabled_launchers_v4|disabled_launcher)\">(.*?)</string>", Pattern.DOTALL);
        Matcher bm = block.matcher(xml);
        Pattern pkg = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+){1,}");
        while (bm.find()) {
            Matcher pm = pkg.matcher(bm.group(1));
            while (pm.find()) out.add(pm.group());
        }
        return out;
    }

    private void openOldAppInfo() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + OLD));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No pude abrir Ajustes", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean runRoot(String script) {
        String r = runRootCapture(script + "\necho __MOKA_OK__");
        return r != null && r.contains("__MOKA_OK__");
    }

    private String runRootCapture(String script) {
        Process su = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        try {
            su = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(su.getOutputStream());
            os.writeBytes(script);
            if (!script.endsWith("\n")) os.writeBytes("\n");
            os.writeBytes("exit\n");
            os.flush();
            reader = new BufferedReader(new InputStreamReader(su.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            su.waitFor();
            return out.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (su != null) su.destroy();
        }
    }

    private boolean safe(String p) {
        return p != null && p.matches("[A-Za-z0-9._]+");
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
