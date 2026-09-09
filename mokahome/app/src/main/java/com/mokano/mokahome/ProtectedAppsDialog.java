package com.mokano.mokahome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ProtectedAppsDialog {
    private ProtectedAppsDialog() {}

    static int count(Context c) { return load(c).size(); }

    static void show(Activity a) {
        final List<AppItem> apps = getApps(a);
        if (apps.isEmpty()) {
            MokaHomeApp.toast(a, "No encontre aplicaciones para mostrar");
            return;
        }

        final Set<String> protectedPkgs = load(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MokaHomeApp.bg(a));
        root.setPadding(MokaHomeApp.dp(a, 18), MokaHomeApp.dp(a, 16), MokaHomeApp.dp(a, 18), MokaHomeApp.dp(a, 12));

        TextView title = MokaHomeApp.tv(a, "Apps protegidas del App Killer", 22, MokaHomeApp.fg(a), true);
        title.setPadding(MokaHomeApp.dp(a, 8), 0, MokaHomeApp.dp(a, 8), MokaHomeApp.dp(a, 4));
        root.addView(title);
        TextView hint = MokaHomeApp.tv(a,
                "Las aplicaciones marcadas no se cerraran con la X roja ni con Liberar RAM.",
                13, MokaHomeApp.muted(a), false);
        hint.setPadding(MokaHomeApp.dp(a, 8), 0, MokaHomeApp.dp(a, 8), MokaHomeApp.dp(a, 10));
        root.addView(hint);

        final ProtectedAdapter adapter = new ProtectedAdapter(a, apps, protectedPkgs);
        ListView list = new ListView(a);
        list.setDivider(new ColorDrawable(MokaHomeApp.isLight(a) ? Color.rgb(218,218,215) : Color.rgb(47,47,46)));
        list.setDividerHeight(MokaHomeApp.dp(a, 1));
        list.setBackgroundColor(MokaHomeApp.bg(a));
        list.setSelector(selector(a));
        list.setItemsCanFocus(false);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = MokaHomeApp.tv(a, "CERRAR", 13, MokaHomeApp.fg(a), true);
        close.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        close.setPadding(MokaHomeApp.dp(a, 12), MokaHomeApp.dp(a, 8), MokaHomeApp.dp(a, 10), MokaHomeApp.dp(a, 4));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(v -> d.dismiss());
        root.addView(close);

        list.setOnItemClickListener((parent, view, position, id) -> {
            toggle(a, apps.get(position).pkg, protectedPkgs);
            adapter.notifyDataSetChanged();
        });
        list.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                int pos = list.getSelectedItemPosition();
                if (pos >= 0 && pos < apps.size()) {
                    toggle(a, apps.get(pos).pkg, protectedPkgs);
                    adapter.notifyDataSetChanged();
                    return true;
                }
            }
            return false;
        });

        d.setContentView(root);
        int width = Math.min(MokaHomeApp.dp(a, 780), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.66f));
        int height = Math.min(MokaHomeApp.dp(a, 820), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.86f));
        MokaHomeApp.showDialog(d, a, width, height);
        list.requestFocus();
        list.setSelection(0);
    }

    private static StateListDrawable selector(Context c) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, MokaHomeApp.simpleFocusBackground(c, true));
        states.addState(new int[]{android.R.attr.state_selected}, MokaHomeApp.simpleFocusBackground(c, true));
        states.addState(new int[]{android.R.attr.state_focused}, MokaHomeApp.simpleFocusBackground(c, true));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private static void toggle(Context c, String pkg, Set<String> set) {
        if (set.contains(pkg)) {
            set.remove(pkg);
            MokaHomeApp.toast(c, "Proteccion quitada");
        } else {
            set.add(pkg);
            MokaHomeApp.toast(c, "Protegida del App Killer");
        }
        save(c, set);
    }

    private static Set<String> load(Context c) {
        Set<String> out = new HashSet<>();
        String raw = MokaHomeApp.prefs(c).getString(MokaHomeApp.EXEMPT_KEY, "");
        if (raw != null) {
            for (String pkg : raw.split("\\n")) {
                pkg = pkg.trim();
                if (!pkg.isEmpty()) out.add(pkg);
            }
        }
        return out;
    }

    private static void save(Context c, Set<String> packages) {
        List<String> sorted = new ArrayList<>(packages);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String pkg : sorted) {
            if (pkg == null || pkg.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(pkg);
        }
        MokaHomeApp.prefs(c).edit().putString(MokaHomeApp.EXEMPT_KEY, sb.toString()).commit();
    }

    private static List<AppItem> getApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<AppItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                if (ri.activityInfo != null) addApp(c, pm, ri.activityInfo.packageName, out, seen);
            }
        } catch (Exception ignored) {}

        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!system) addApp(c, pm, ai.packageName, out, seen);
            }
        } catch (Exception ignored) {}

        Collections.sort(out, Comparator.comparing(x -> x.label.toLowerCase(Locale.getDefault())));
        return out;
    }

    private static void addApp(Context c, PackageManager pm, String pkg, List<AppItem> out, Set<String> seen) {
        if (pkg == null || pkg.equals(c.getPackageName()) || !seen.add(pkg)) return;
        if (isCritical(pkg)) return;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            AppItem item = new AppItem();
            item.pkg = pkg;
            CharSequence label = pm.getApplicationLabel(ai);
            item.label = label == null ? pkg : label.toString();
            try { item.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            out.add(item);
        } catch (Exception ignored) {}
    }

    private static boolean isCritical(String pkg) {
        return pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.android.settings") ||
                pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf") ||
                pkg.startsWith("com.android.providers") || pkg.startsWith("com.android.permission") ||
                pkg.startsWith("com.android.packageinstaller") || pkg.startsWith("com.google.android.packageinstaller") ||
                pkg.startsWith("com.android.inputmethod") || pkg.startsWith("com.google.android.inputmethod") ||
                pkg.startsWith("com.android.phone") || pkg.startsWith("com.android.shell") ||
                pkg.startsWith("com.android.bluetooth") || pkg.startsWith("com.android.networkstack");
    }

    private static class ProtectedAdapter extends BaseAdapter {
        private final Context c;
        private final List<AppItem> apps;
        private final Set<String> protectedPkgs;

        ProtectedAdapter(Context c, List<AppItem> apps, Set<String> protectedPkgs) {
            this.c = c; this.apps = apps; this.protectedPkgs = protectedPkgs;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(MokaHomeApp.dp(c, 14), MokaHomeApp.dp(c, 7), MokaHomeApp.dp(c, 14), MokaHomeApp.dp(c, 7));
                row.setBackgroundColor(Color.TRANSPARENT);
                row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

                ImageView icon = new ImageView(c);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                row.addView(icon, new LinearLayout.LayoutParams(MokaHomeApp.dp(c, 50), MokaHomeApp.dp(c, 50)));

                LinearLayout labels = new LinearLayout(c);
                labels.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                labelsLp.leftMargin = MokaHomeApp.dp(c, 16);
                row.addView(labels, labelsLp);

                TextView name = MokaHomeApp.tv(c, "", 17, MokaHomeApp.fg(c), true);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                TextView pkg = MokaHomeApp.tv(c, "", 11, MokaHomeApp.muted(c), false);
                pkg.setSingleLine(true);
                pkg.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(name);
                labels.addView(pkg);

                TextView status = MokaHomeApp.tv(c, "", 12, MokaHomeApp.muted(c), true);
                status.setGravity(Gravity.CENTER);
                row.addView(status, new LinearLayout.LayoutParams(MokaHomeApp.dp(c, 126), MokaHomeApp.dp(c, 42)));

                h = new Holder(); h.icon = icon; h.name = name; h.pkg = pkg; h.status = status;
                row.setTag(h);
                convertView = row;
            } else h = (Holder) convertView.getTag();

            AppItem app = apps.get(position);
            h.icon.setImageDrawable(app.icon);
            h.name.setText(app.label);
            h.name.setTextColor(MokaHomeApp.fg(c));
            h.pkg.setText(app.pkg);
            h.pkg.setTextColor(MokaHomeApp.muted(c));
            boolean yes = protectedPkgs.contains(app.pkg);
            h.status.setText(yes ? "PROTEGIDA" : "LIBRE");
            h.status.setTextColor(yes ? MokaHomeApp.GREEN : MokaHomeApp.muted(c));
            return convertView;
        }
    }

    private static class Holder { ImageView icon; TextView name; TextView pkg; TextView status; }
    private static class AppItem { String pkg; String label; Drawable icon; }
}
