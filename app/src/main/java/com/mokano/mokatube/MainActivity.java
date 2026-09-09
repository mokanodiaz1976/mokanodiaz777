package com.mokano.mokatube;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String[] API_BASES = new String[] {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://api-piped.mha.fi",
            "https://piped-api.garudalinux.org"
    };

    private static final int MAX_ITEMS = 28;
    private static final int COLS = 4;
    private static final String PREFS = "mokatube";
    private static final String HISTORY = "history";

    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(12 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private LinearLayout gridContainer;
    private TextView pageTitle;
    private TextView statusText;
    private ProgressBar progress;
    private EditText searchInput;
    private int screenWidth;
    private int sidebarWidth;
    private int cardThumbHeight;
    private String activeBase = API_BASES[0];

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        screenWidth = dm.widthPixels;
        sidebarWidth = Math.max(dp(170), (int) (screenWidth * 0.125f));
        int contentWidth = screenWidth - sidebarWidth - dp(48);
        int cardWidth = Math.max(dp(220), (contentWidth - dp(16) * (COLS - 1)) / COLS);
        cardThumbHeight = cardWidth * 9 / 16;

        setContentView(buildUi());
        loadTrending();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(8), dp(18), dp(8));
        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        TextView logo = new TextView(this);
        logo.setText("▶  MokaTube");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(20);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(logo, new LinearLayout.LayoutParams(sidebarWidth - dp(10),
                LinearLayout.LayoutParams.MATCH_PARENT));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Buscar");
        searchInput.setHintTextColor(Color.rgb(160, 160, 160));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(16);
        searchInput.setBackground(new ColorDrawable(Color.rgb(28, 28, 28)));
        searchInput.setPadding(dp(18), 0, dp(14), 0);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        searchLp.setMargins(dp(8), 0, dp(8), 0);
        top.addView(searchInput, searchLp);

        Button searchButton = new Button(this);
        searchButton.setText("Buscar");
        searchButton.setAllCaps(false);
        searchButton.setTextColor(Color.WHITE);
        searchButton.setBackgroundColor(Color.rgb(38, 38, 38));
        searchButton.setFocusable(true);
        top.addView(searchButton, new LinearLayout.LayoutParams(dp(100), dp(42)));

        View.OnClickListener searchAction = v -> {
            String q = searchInput.getText().toString().trim();
            if (!q.isEmpty()) loadSearch(q);
        };
        searchButton.setOnClickListener(searchAction);
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchAction.onClick(v);
                return true;
            }
            return false;
        });

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(10), dp(8), dp(10), dp(10));
        body.addView(sidebar, new LinearLayout.LayoutParams(sidebarWidth,
                LinearLayout.LayoutParams.MATCH_PARENT));

        addNav(sidebar, "⌂   Inicio", this::loadTrending);
        addNav(sidebar, "♫   Música", () -> loadSearch("música"));
        addNav(sidebar, "▣   Tecnología", () -> loadSearch("tecnología"));
        addNav(sidebar, "🏍   Motocicletas", () -> loadSearch("motocicletas"));
        addNav(sidebar, "◈   Blender", () -> loadSearch("Blender 3D"));
        addNav(sidebar, "⌂   Arquitectura", () -> loadSearch("arquitectura 3D"));
        addSpacer(sidebar, dp(12));
        addNav(sidebar, "◷   Historial", this::showHistory);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(6), dp(20), dp(12));
        body.addView(right, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        pageTitle = new TextView(this);
        pageTitle.setText("Inicio");
        pageTitle.setTextColor(Color.WHITE);
        pageTitle.setTextSize(20);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(pageTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(160, 160, 160));
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(statusText, new LinearLayout.LayoutParams(dp(220),
                LinearLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pp.setMargins(dp(8), 0, 0, 0);
        header.addView(progress, pp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        right.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(0, dp(6), 0, dp(24));
        scroll.addView(gridContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void addNav(LinearLayout parent, String text, Runnable action) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextColor(Color.WHITE);
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), 0, dp(10), 0);
        item.setFocusable(true);
        item.setClickable(true);
        item.setBackgroundColor(Color.TRANSPARENT);
        item.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackgroundColor(hasFocus ? Color.rgb(47, 47, 47) : Color.TRANSPARENT));
        item.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(2), 0, dp(2));
        parent.addView(item, lp);
    }

    private void addSpacer(LinearLayout parent, int height) {
        Space s = new Space(this);
        parent.addView(s, new LinearLayout.LayoutParams(1, height));
    }

    private void setLoading(boolean loading, String title) {
        runOnUiThread(() -> {
            progress.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (title != null) pageTitle.setText(title);
            statusText.setText(loading ? "Cargando…" : "");
        });
    }

    private void loadTrending() {
        setLoading(true, "Inicio");
        apiExecutor.submit(() -> {
            try {
                String json = requestJson("/trending?region=DO");
                JSONArray arr = new JSONArray(json);
                List<VideoItem> items = new ArrayList<>();
                for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS; i++) {
                    VideoItem item = parseVideo(arr.optJSONObject(i));
                    if (item != null) items.add(item);
                }
                showVideos(items, "Inicio");
            } catch (Exception e) {
                showError("No pude cargar Inicio. Prueba Buscar o vuelve a intentar.");
            }
        });
    }

    private void loadSearch(String query) {
        setLoading(true, query);
        apiExecutor.submit(() -> {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String json = requestJson("/search?q=" + encoded + "&filter=videos");
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("items");
                List<VideoItem> items = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS; i++) {
                        VideoItem item = parseVideo(arr.optJSONObject(i));
                        if (item != null) items.add(item);
                    }
                }
                showVideos(items, query);
            } catch (Exception e) {
                showError("No pude completar la búsqueda. Intenta de nuevo.");
            }
        });
    }

    private String requestJson(String path) throws Exception {
        List<String> bases = new ArrayList<>();
        bases.add(activeBase);
        for (String b : API_BASES) if (!b.equals(activeBase)) bases.add(b);

        Exception last = null;
        for (String base : bases) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(9000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "MokaTube/0.2 Android");
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(
                        conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String out = sb.toString();
                if (out.length() < 2) throw new Exception("empty response");
                activeBase = base;
                return out;
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new Exception("No API available");
    }

    private VideoItem parseVideo(JSONObject o) {
        if (o == null) return null;
        String url = o.optString("url", "");
        if (url.isEmpty() || (!url.contains("watch?v=") && !url.contains("/shorts/"))) return null;
        if (url.startsWith("/")) url = "https://www.youtube.com" + url;

        VideoItem v = new VideoItem();
        v.url = url;
        v.title = o.optString("title", "Video");
        v.thumbnail = o.optString("thumbnail", o.optString("thumbnailUrl", ""));
        v.uploader = o.optString("uploaderName", o.optString("uploader", ""));
        v.uploaded = o.optString("uploadedDate", "");
        v.views = o.optLong("views", -1);
        v.duration = o.optLong("duration", 0);
        return v;
    }

    private void showVideos(List<VideoItem> items, String title) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            pageTitle.setText(title);
            statusText.setText(items.size() + " videos");
            gridContainer.removeAllViews();

            if (items.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No se encontraron videos.");
                empty.setTextColor(Color.LTGRAY);
                empty.setTextSize(18);
                empty.setPadding(dp(12), dp(40), 0, 0);
                gridContainer.addView(empty);
                return;
            }

            for (int i = 0; i < items.size(); i += COLS) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(16));
                gridContainer.addView(row, rowLp);

                for (int c = 0; c < COLS; c++) {
                    int idx = i + c;
                    if (idx < items.size()) {
                        View card = createCard(items.get(idx));
                        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                        if (c > 0) cardLp.setMargins(dp(8), 0, 0, 0);
                        if (c < COLS - 1) cardLp.rightMargin = dp(8);
                        row.addView(card, cardLp);
                    } else {
                        Space blank = new Space(this);
                        row.addView(blank, new LinearLayout.LayoutParams(0, 1, 1f));
                    }
                }
            }

            View first = findFirstFocusable(gridContainer);
            if (first != null) first.requestFocus();
        });
    }

    private View findFirstFocusable(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View c = row.getChildAt(j);
                    if (c.isFocusable()) return c;
                }
            }
        }
        return null;
    }

    private View createCard(VideoItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(8));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackgroundColor(Color.TRANSPARENT);

        FrameLayout thumbFrame = new FrameLayout(this);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Color.rgb(36, 36, 36));
        thumb.setTag(item.thumbnail);
        thumbFrame.addView(thumb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, cardThumbHeight));

        TextView duration = new TextView(this);
        duration.setText(formatDuration(item.duration));
        duration.setTextColor(Color.WHITE);
        duration.setTextSize(11);
        duration.setPadding(dp(5), dp(2), dp(5), dp(2));
        duration.setBackgroundColor(Color.argb(205, 0, 0, 0));
        FrameLayout.LayoutParams durLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT);
        durLp.setMargins(0, 0, dp(6), dp(6));
        thumbFrame.addView(duration, durLp);
        card.addView(thumbFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cardThumbHeight));

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setPadding(dp(2), dp(8), dp(2), 0);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        StringBuilder m = new StringBuilder();
        if (!item.uploader.isEmpty()) m.append(item.uploader);
        if (item.views >= 0) {
            if (m.length() > 0) m.append("\n");
            m.append(formatViews(item.views));
            if (!item.uploaded.isEmpty()) m.append(" · ").append(item.uploaded);
        } else if (!item.uploaded.isEmpty()) {
            if (m.length() > 0) m.append("\n");
            m.append(item.uploaded);
        }
        meta.setText(m.toString());
        meta.setTextColor(Color.rgb(170, 170, 170));
        meta.setTextSize(12);
        meta.setMaxLines(2);
        meta.setPadding(dp(2), dp(4), dp(2), 0);
        card.addView(meta, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        card.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackgroundColor(hasFocus ? Color.rgb(47, 47, 47) : Color.TRANSPARENT);
            if (hasFocus) {
                v.setScaleX(1.015f);
                v.setScaleY(1.015f);
            } else {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        });
        card.setOnClickListener(v -> {
            addToHistory(item);
            openInSmartTube(item.url);
        });

        loadImage(item.thumbnail, thumb);
        return card;
    }

    private void loadImage(String url, ImageView view) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = imageCache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        imageExecutor.submit(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(7000);
                conn.setRequestProperty("User-Agent", "MokaTube/0.2");
                in = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    imageCache.put(url, bmp);
                    runOnUiThread(() -> {
                        Object tag = view.getTag();
                        if (tag != null && url.equals(tag.toString())) view.setImageBitmap(bmp);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void showHistory() {
        List<VideoItem> history = readHistory();
        showVideos(history, "Historial");
    }

    private void addToHistory(VideoItem item) {
        List<VideoItem> list = readHistory();
        List<VideoItem> out = new ArrayList<>();
        out.add(item);
        Set<String> seen = new HashSet<>();
        seen.add(item.url);
        for (VideoItem v : list) {
            if (!seen.contains(v.url)) {
                out.add(v);
                seen.add(v.url);
            }
            if (out.size() >= 30) break;
        }

        JSONArray arr = new JSONArray();
        for (VideoItem v : out) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", v.url);
                o.put("title", v.title);
                o.put("thumbnail", v.thumbnail);
                o.put("uploader", v.uploader);
                o.put("uploaded", v.uploaded);
                o.put("views", v.views);
                o.put("duration", v.duration);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(HISTORY, arr.toString()).apply();
    }

    private List<VideoItem> readHistory() {
        List<VideoItem> list = new ArrayList<>();
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONArray arr = new JSONArray(p.getString(HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                VideoItem v = new VideoItem();
                v.url = o.optString("url", "");
                v.title = o.optString("title", "Video");
                v.thumbnail = o.optString("thumbnail", "");
                v.uploader = o.optString("uploader", "");
                v.uploaded = o.optString("uploaded", "");
                v.views = o.optLong("views", -1);
                v.duration = o.optLong("duration", 0);
                if (!v.url.isEmpty()) list.add(v);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            statusText.setText("Error");
            gridContainer.removeAllViews();
            TextView t = new TextView(this);
            t.setText(message);
            t.setTextColor(Color.LTGRAY);
            t.setTextSize(18);
            t.setPadding(dp(10), dp(36), 0, 0);
            gridContainer.addView(t);
        });
    }

    private boolean launchPackage(String pkg, Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage(pkg);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void openInSmartTube(String url) {
        Uri uri = Uri.parse(url);
        boolean ok =
                launchPackage("org.smarttube.stable", uri) ||
                launchPackage("org.smarttube.beta", uri) ||
                launchPackage("app.smarttube.fdroid", uri) ||
                launchPackage("com.liskovsoft.smarttubetv2.tv", uri);

        if (!ok) {
            Toast.makeText(this, "Instala SmartTube para reproducir los videos.", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
        }
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private String formatViews(long views) {
        if (views < 0) return "";
        if (views >= 1_000_000_000L) return String.format(Locale.US, "%.1f B vistas", views / 1_000_000_000f);
        if (views >= 1_000_000L) return String.format(Locale.US, "%.1f M vistas", views / 1_000_000f);
        if (views >= 1_000L) return String.format(Locale.US, "%.1f mil vistas", views / 1_000f);
        return views + " vistas";
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    @Override
    protected void onDestroy() {
        apiExecutor.shutdownNow();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }

    private static class VideoItem {
        String url = "";
        String title = "";
        String thumbnail = "";
        String uploader = "";
        String uploaded = "";
        long views = -1;
        long duration = 0;
    }
}
