package com.mokano.mokahome.updater;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class MainActivity extends Activity {
    private TextView status;
    private Button updateButton;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(70), dp(42), dp(70), dp(42));
        root.setBackgroundColor(Color.rgb(11, 13, 16));

        TextView title = new TextView(this);
        title.setText("MokaHome · Actualizador");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(this);
        info.setText("Actualización directa 0.5 → 0.6\nConserva tus cuadros y preferencias. No desinstales MokaHome 0.5.");
        info.setTextColor(Color.rgb(190, 195, 202));
        info.setTextSize(20);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(18);
        root.addView(info, infoLp);

        updateButton = new Button(this);
        updateButton.setText("ACTUALIZAR AHORA");
        updateButton.setTextSize(21);
        updateButton.setTextColor(Color.BLACK);
        updateButton.setBackgroundColor(Color.WHITE);
        updateButton.setAllCaps(false);
        updateButton.setFocusable(true);
        updateButton.setOnClickListener(v -> startUpdate());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(dp(340), dp(68));
        buttonLp.topMargin = dp(34);
        root.addView(updateButton, buttonLp);

        status = new TextView(this);
        status.setText("Listo para actualizar.");
        status.setTextColor(Color.rgb(150, 158, 168));
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(24);
        root.addView(status, statusLp);

        setContentView(root);
        updateButton.requestFocus();
    }

    private void startUpdate() {
        if (running) return;
        running = true;
        updateButton.setEnabled(false);
        status.setText("Actualizando… no apagues la caja. Puede pedir permiso ROOT una vez.");

        new Thread(() -> {
            try {
                File apk = new File(getCacheDir(), "MokaHome-v0.6.apk");
                copyBundledApk(apk);

                ShellResult rootTest = runRoot("id");
                if (rootTest.exitCode != 0 || !rootTest.output.contains("uid=0")) {
                    fail("No se obtuvo acceso ROOT. Acepta el permiso y vuelve a pulsar Actualizar.\n" + rootTest.output);
                    return;
                }

                String apkPath = shellQuote(apk.getAbsolutePath());
                String script =
                        "OLD=/data/user/0/com.mokano.mokahome\n" +
                        "BACK=/data/local/tmp/mokahome_bridge_backup\n" +
                        "TARGET=/data/local/tmp/MokaHome-v0.6.apk\n" +
                        "rm -rf \"$BACK\"\n" +
                        "mkdir -p \"$BACK\"\n" +
                        "if pm path com.mokano.mokahome >/dev/null 2>&1; then\n" +
                        "  [ -d \"$OLD/shared_prefs\" ] && cp -a \"$OLD/shared_prefs\" \"$BACK/\" || true\n" +
                        "  [ -d \"$OLD/files\" ] && cp -a \"$OLD/files\" \"$BACK/\" || true\n" +
                        "fi\n" +
                        "cp " + apkPath + " \"$TARGET\" || exit 20\n" +
                        "chmod 0644 \"$TARGET\"\n" +
                        "cmd package set-home-activity --user 0 com.mokano.mokahome.updater/.MainActivity >/dev/null 2>&1 || pm set-home-activity --user 0 com.mokano.mokahome.updater/.MainActivity >/dev/null 2>&1 || true\n" +
                        "if pm path com.mokano.mokahome >/dev/null 2>&1; then\n" +
                        "  U=$(pm uninstall com.mokano.mokahome 2>&1)\n" +
                        "  echo \"$U\"\n" +
                        "  echo \"$U\" | grep -q Success || exit 31\n" +
                        "fi\n" +
                        "I=$(pm install \"$TARGET\" 2>&1)\n" +
                        "echo \"$I\"\n" +
                        "echo \"$I\" | grep -q Success || exit 32\n" +
                        "NEW=/data/user/0/com.mokano.mokahome\n" +
                        "if [ -d \"$BACK/shared_prefs\" ]; then\n" +
                        "  rm -rf \"$NEW/shared_prefs\"\n" +
                        "  cp -a \"$BACK/shared_prefs\" \"$NEW/\" || exit 40\n" +
                        "fi\n" +
                        "if [ -d \"$BACK/files\" ]; then\n" +
                        "  rm -rf \"$NEW/files\"\n" +
                        "  cp -a \"$BACK/files\" \"$NEW/\" || exit 41\n" +
                        "fi\n" +
                        "UIDN=$(stat -c %u \"$NEW\" 2>/dev/null)\n" +
                        "GIDN=$(stat -c %g \"$NEW\" 2>/dev/null)\n" +
                        "if [ -n \"$UIDN\" ]; then\n" +
                        "  [ -d \"$NEW/shared_prefs\" ] && chown -R \"$UIDN:$GIDN\" \"$NEW/shared_prefs\" || true\n" +
                        "  [ -d \"$NEW/files\" ] && chown -R \"$UIDN:$GIDN\" \"$NEW/files\" || true\n" +
                        "fi\n" +
                        "restorecon -RF \"$NEW\" >/dev/null 2>&1 || true\n" +
                        "cmd package set-home-activity --user 0 com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1 || pm set-home-activity --user 0 com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1 || true\n" +
                        "rm -rf \"$BACK\" \"$TARGET\"\n" +
                        "am start -n com.mokano.mokahome/.MainActivityV5 >/dev/null 2>&1 || exit 50\n" +
                        "echo UPDATE_OK\n";

                ShellResult result = runRoot(script);
                if (result.exitCode == 0 && result.output.contains("UPDATE_OK")) {
                    runOnUiThread(() -> {
                        status.setText("Actualización completada. MokaHome 0.6 ya está instalado.");
                        updateButton.setText("COMPLETADO");
                        updateButton.setEnabled(false);
                    });
                } else {
                    fail("La actualización no terminó. No desinstales nada; este actualizador puede reintentarlo.\nCódigo: "
                            + result.exitCode + "\n" + result.output);
                }
            } catch (Exception e) {
                fail("Error: " + e.getMessage());
            }
        }).start();
    }

    private void copyBundledApk(File out) throws Exception {
        try (InputStream in = getResources().openRawResource(R.raw.mokahome_v06);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) fos.write(buffer, 0, n);
            fos.flush();
        }
    }

    private void fail(String message) {
        runOnUiThread(() -> {
            running = false;
            updateButton.setEnabled(true);
            updateButton.setText("REINTENTAR");
            status.setText(message);
        });
    }

    private ShellResult runRoot(String command) throws Exception {
        Process process = new ProcessBuilder("su").redirectErrorStream(true).start();
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream())) {
            writer.write(command);
            writer.write("\nexit\n");
            writer.flush();
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        int code = process.waitFor();
        return new ShellResult(code, out.toString().trim());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class ShellResult {
        final int exitCode;
        final String output;
        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
