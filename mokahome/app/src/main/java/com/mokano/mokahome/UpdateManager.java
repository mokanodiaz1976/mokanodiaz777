package com.mokano.mokahome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Actualizador tolerante a cambios de firma: conserva preferencias, reinstala y restaura. */
public final class UpdateManager {
    private static final String META_URL = "https://raw.githubusercontent.com/mokanodiaz1976/mokanodiaz777/mokahome-v1/mokahome/update.json";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private UpdateManager() {}

    public static void check(Activity a, boolean interactive) {
        if (a == null || a.isFinishing()) return;
        if (interactive) toast(a, "Buscando actualización...");
        EXEC.execute(() -> {
            try {
                JSONObject json = new JSONObject(readUrl(META_URL));
                int remoteCode = json.optInt("versionCode", 0);
                String remoteName = json.optString("versionName", "");
                String apkUrl = json.optString("url", "");
                String sha = json.optString("sha256", "");
                int localCode = BuildConfig.VERSION_CODE;
                a.runOnUiThread(() -> {
                    if (remoteCode > localCode && apkUrl.startsWith("https://")) {
                        showUpdateDialog(a, remoteName, apkUrl, sha);
                    } else if (interactive) {
                        toast(a, "MokaHome está actualizado");
                    }
                });
            } catch (Exception e) {
                if (interactive) a.runOnUiThread(() -> toast(a, "No pude consultar actualizaciones"));
            }
        });
    }

    private static void showUpdateDialog(Activity a, String version, String url, String sha) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = root(a);
        root.addView(title(a, "Nueva versión disponible"));
        TextView info = text(a, "MokaHome " + version + "\nLa actualización conserva tus cuadros, apps protegidas, audio y opciones visuales.", 14, muted(a), false);
        info.setPadding(dp(a,12), dp(a,8), dp(a,12), dp(a,12));
        root.addView(info);
        root.addView(action(a, "ACTUALIZAR AHORA", () -> {
            d.dismiss(); downloadAndInstall(a, url, sha);
        }));
        root.addView(action(a, "MÁS TARDE", d::dismiss));
        d.setContentView(root);
        show(d, a, dp(a,520), dp(a,270));
    }

    private static void downloadAndInstall(Activity a, String url, String expectedSha) {
        toast(a, "Descargando actualización...");
        EXEC.execute(() -> {
            try {
                File apk = new File(a.getFilesDir(), "mokahome-update.apk");
                download(url, apk);
                if (expectedSha != null && !expectedSha.trim().isEmpty()) {
                    String actual = sha256(apk);
                    if (!actual.equalsIgnoreCase(expectedSha.trim())) throw new IllegalStateException("SHA mismatch");
                }
                File script = new File(a.getFilesDir(), "mokahome-update.sh");
                writeScript(script);
                String prep =
                        "cp '" + apk.getAbsolutePath() + "' /data/local/tmp/mokahome-update.apk\n" +
                        "cp '" + script.getAbsolutePath() + "' /data/local/tmp/mokahome-update.sh\n" +
                        "chmod 755 /data/local/tmp/mokahome-update.sh\n" +
                        "(sleep 1; sh /data/local/tmp/mokahome-update.sh >/data/local/tmp/mokahome-update.log 2>&1) </dev/null &\n";
                boolean ok = runRoot(prep);
                a.runOnUiThread(() -> toast(a, ok ? "Actualizando. MokaHome se reiniciará sola..." : "No pude iniciar la actualización root"));
            } catch (Exception e) {
                a.runOnUiThread(() -> toast(a, "La actualización no pudo instalarse"));
            }
        });
    }

    private static void writeScript(File file) throws Exception {
        String s = "#!/system/bin/sh\n" +
                "PKG=com.mokano.mokahome\n" +
                "APK=/data/local/tmp/mokahome-update.apk\n" +
                "BACK=/data/local/tmp/mokahome-prefs.xml\n" +
                "OLD=/data/data/$PKG/shared_prefs/mokahome.xml\n" +
                "[ -f $OLD ] && cp $OLD $BACK\n" +
                "pm uninstall $PKG >/data/local/tmp/mokahome-uninstall.log 2>&1\n" +
                "sleep 1\n" +
                "pm install $APK >/data/local/tmp/mokahome-install.log 2>&1 || exit 9\n" +
                "am start -n $PKG/.MainActivityV5 >/dev/null 2>&1\n" +
                "sleep 2\n" +
                "if [ -f $BACK ]; then\n" +
                "  UID=$(stat -c %u /data/data/$PKG)\n" +
                "  mkdir -p /data/data/$PKG/shared_prefs\n" +
                "  cp $BACK /data/data/$PKG/shared_prefs/mokahome.xml\n" +
                "  chown $UID:$UID /data/data/$PKG/shared_prefs/mokahome.xml\n" +
                "  chmod 660 /data/data/$PKG/shared_prefs/mokahome.xml\n" +
                "  restorecon /data/data/$PKG/shared_prefs/mokahome.xml >/dev/null 2>&1 || true\n" +
                "fi\n" +
                "cmd package set-home-activity --user 0 $PKG/.MainActivityV5 >/dev/null 2>&1 || cmd package set-home-activity $PKG/.MainActivityV5 >/dev/null 2>&1\n" +
                "am force-stop $PKG >/dev/null 2>&1\n" +
                "am start -n $PKG/.MainActivityV5 >/dev/null 2>&1\n" +
                "rm -f $APK $BACK /data/local/tmp/mokahome-update.sh\n";
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(s.getBytes("UTF-8")); }
    }

    private static String readUrl(String value) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(value).openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(9000); c.setUseCaches(false); c.setRequestProperty("User-Agent","MokaHome/"+BuildConfig.VERSION_NAME);
        try (java.io.InputStream in = new BufferedInputStream(c.getInputStream()); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n; while((n=in.read(b))>0) out.write(b,0,n); return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static void download(String value, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(value).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setUseCaches(false); c.setRequestProperty("User-Agent","MokaHome/"+BuildConfig.VERSION_NAME);
        try (BufferedInputStream in = new BufferedInputStream(c.getInputStream()); BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] b = new byte[8192]; int n; while((n=in.read(b))>0) out.write(b,0,n);
        } finally { c.disconnect(); }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try(FileInputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)md.update(b,0,n);} StringBuilder s=new StringBuilder(); for(byte x:md.digest())s.append(String.format(Locale.US,"%02x",x)); return s.toString();
    }

    private static boolean runRoot(String script) {
        Process su=null; DataOutputStream os=null;
        try { su=Runtime.getRuntime().exec("su"); os=new DataOutputStream(su.getOutputStream()); os.writeBytes(script); if(!script.endsWith("\n"))os.writeBytes("\n"); os.writeBytes("exit\n"); os.flush(); return su.waitFor()==0; }
        catch(Exception e){return false;} finally{try{if(os!=null)os.close();}catch(Exception ignored){} if(su!=null)su.destroy();}
    }

    private static LinearLayout root(Activity a){LinearLayout r=new LinearLayout(a);r.setOrientation(LinearLayout.VERTICAL);r.setBackgroundColor(bg(a));r.setPadding(dp(a,18),dp(a,16),dp(a,18),dp(a,12));return r;}
    private static TextView title(Activity a,String s){TextView t=text(a,s,21,fg(a),true);t.setPadding(dp(a,8),0,dp(a,8),dp(a,8));return t;}
    private static TextView action(Activity a,String s,Runnable r){TextView v=text(a,s,17,fg(a),false);v.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);v.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,8));v.setFocusable(true);v.setClickable(true);v.setBackground(row(a,false));v.setOnFocusChangeListener((x,f)->x.setBackground(row(a,f)));v.setOnClickListener(x->r.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(a,52));lp.topMargin=dp(a,3);v.setLayoutParams(lp);return v;}
    private static GradientDrawable row(Activity a,boolean f){GradientDrawable g=new GradientDrawable();g.setColor(bg(a));g.setCornerRadius(dp(a,8));if(f)g.setStroke(dp(a,2),fg(a));return g;}
    private static TextView text(Activity a,String s,int sp,int color,boolean bold){TextView v=new TextView(a);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return v;}
    private static int bg(Activity a){return MokaHomeApp.bg(a);} private static int fg(Activity a){return MokaHomeApp.fg(a);} private static int muted(Activity a){return MokaHomeApp.muted(a);} private static int dp(Activity a,int v){return Math.round(v*a.getResources().getDisplayMetrics().density);} private static void toast(Activity a,String s){Toast.makeText(a,s,Toast.LENGTH_SHORT).show();}
    private static void show(Dialog d,Activity a,int w,int h){d.show();Window x=d.getWindow();if(x!=null){x.setBackgroundDrawable(new ColorDrawable(bg(a)));x.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams p=x.getAttributes();p.dimAmount=.62f;x.setAttributes(p);x.setLayout(w,h);}}
}