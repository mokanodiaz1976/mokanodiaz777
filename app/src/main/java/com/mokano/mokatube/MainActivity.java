package com.mokano.mokatube;

import android.app.Activity;
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
    private static final String[] PIPED_BASES = new String[] {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            "https://pipedapi.syncpundit.io",
            "https://api-piped.mha.fi",
            "https://piped-api.garudalinux.org",
            "https://pipedapi.rivo.lol",
            "https://pipedapi.leptons.xyz"
    };

    private static final String[] INVIDIOUS_BASES = new String[] {
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de"
    };

    private static final int MAX_ITEMS = 28;
    private static final int COLS = 4;
    private static final String PREFS = "mokatube";
    private static final String HISTORY = "history";

    private final ExecutorService apiExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024) {
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
    private String activePiped = PIPED_BASES[0];
    private String activeInvidious = INVIDIOUS_BASES[0];
    private String lastProvider = "";

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
        loadHome();
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

        Button searchButton = makeButton("Buscar");
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

        addNav(sidebar, "⌂   Inicio", this::loadHome);
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
        pageTitle.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(pageTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(160, 160, 160));
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(statusText, new LinearLayout.LayoutParams(dp(260),
                LinearLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(30), dp(30));
        pp.setMargins(dp(8), 0, 0, 0);
        header.addView(progress, pp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(false);
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

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(38, 38, 38));
        b.setFocusable(true);
        return b;
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
            statusText.setText(loading ? "Buscando servidor…" : "");
            if (loading) gridContainer.removeAllViews();
        });
    }

    private void loadHome() {
        setLoading(true, "Inicio");
        apiExecutor.submit(() -> {
            List<VideoItem> items = null;

            try {
                String json = requestPiped("/trending?region=DO");
                items = parsePipedArray(new JSONArray(json));
                if (items.isEmpty()) throw new Exception("Piped empty");
                lastProvider = "Piped";
            } catch (Exception ignored) {}

            if (items == null || items.isEmpty()) {
                try {
                    String json = requestPiped("/trending?region=US");
                    items = parsePipedArray(new JSONArray(json));
                    if (items.isEmpty()) throw new Exception("Piped empty");
                    lastProvider = "Piped";
                } catch (Exception ignored) {}
            }

            if (items == null || items.isEmpty()) {
                try {
                    String json = requestInvidious("/api/v1/trending?region=DO");
                    items = parseInvidiousArray(new JSONArray(json));
                    if (items.isEmpty()) throw new Exception("Invidious empty");
                    lastProvider = "Invidious";
                } catch (Exception ignored) {}
            }

            if (items == null || items.isEmpty()) {
                try {
                    String json = requestInvidious("/api/v1/popular");
                    items = parseInvidiousArray(new JSONArray(json));
                    if (items.isEmpty()) throw new Exception("Invidious empty");
                    lastProvider = "Invidious";
                } catch (Exception ignored) {}
            }

            if (items == null || items.isEmpty()) {
                showError("No pude conectar con los servidores de videos.", true);
            } else {
                showVideos(items, "Inicio");
            }
        });
    }

    private void loadSearch(String query) {
        setLoading(true, query);
        apiExecutor.submit(() -> {
            List<VideoItem> items = null;
            String encoded;
            try {
                encoded = URLEncoder.encode(query, "UTF-8");
            } catch (Exception e) {
                encoded = query;
            }

            try {
                String json = requestPiped("/search?q=" + encoded + "&filter=videos");
                JSONObject root = new JSONObject(json);
                JSONArray arr = root.optJSONArray("items");
                items = parsePipedArray(arr != null ? arr : new JSONArray());
                if (items.isEmpty()) throw new Exception("Piped empty");
                lastProvider = "Piped";
            } catch (Exception ignored) {}

            if (items == null || items.isEmpty()) {
                try {
                    String json = requestInvidious("/api/v1/search?q=" + encoded + "&type=video&region=DO");
                    items = parseInvidiousArray(new JSONArray(json));
                    if (items.isEmpty()) throw new Exception("Invidious empty");
                    lastProvider = "Invidious";
                } catch (Exception ignored) {}
            }

            if (items == null || items.isEmpty()) {
                showError("No pude completar la búsqueda. Intenta nuevamente.", false);
            } else {
                showVideos(items, query);
            }
        });
    }

    private String requestPiped(String path) throws Exception {
        List<String> bases = orderedBases(activePiped, PIPED_BASES);
        Exception last = null;
        for (String base : bases) {
            try {
                String out = request(base + path, 4500, 7000);
                activePiped = base;
                return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("No Piped server");
    }

    private String requestInvidious(String path) throws Exception {
        List<String> bases = orderedBases(activeInvidious, INVIDIOUS_BASES);
        Exception last = null;
        for (String base : bases) {
            try {
                String out = request(base + path, 5000, 8000);
                activeInvidious = base;
                return out;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("No Invidious server");
    }

    private List<String> orderedBases(String active, String[] all) {
        List<String> out = new ArrayList<>();
        if (active != null && !active.isEmpty()) out.add(active);
        for (String b : all) if (!b.equals(active)) out.add(b);
        return out;
    }

    private String request(String fullUrl, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        try {
            conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 9; MokaTube/0.3)");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String out = sb.toString();
            if (out.length() < 2 || out.charAt(0) == '<') throw new Exception("Invalid response");
            return out;
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private List<VideoItem> parsePipedArray(JSONArray arr) {
        List<VideoItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String rawUrl = o.optString("url", "");
            String id = extractVideoId(rawUrl);
            if (id.isEmpty()) continue;
            VideoItem v = new VideoItem();
            v.url = "https://www.youtube.com/watch?v=" + id;
            v.title = o.optString("title", "Video");
            v.thumbnail = youtubeThumb(id);
            v.uploader = o.optString("uploaderName", o.optString("uploader", ""));
            v.uploaded = o.optString("uploadedDate", "");
            v.views = o.optLong("views", -1);
            v.duration = o.optLong("duration", 0);
            items.add(v);
        }
        return items;
    }

    private List<VideoItem> parseInvidiousArray(JSONArray arr) {
        List<VideoItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type", "video");
            if (!(type.equals("video") || type.equals("shortVideo") || type.isEmpty())) continue;
            String id = o.optString("videoId", "");
            if (id.isEmpty()) continue;
            VideoItem v = new VideoItem();
            v.url = "https://www.youtube.com/watch?v=" + id;
            v.title = o.optString("title", "Video");
            v.thumbnail = youtubeThumb(id);
            v.uploader = o.optString("author", "");
            v.uploaded = o.optString("publishedText", "");
            v.views = o.optLong("viewCount", -1);
            v.duration = o.optLong("lengthSeconds", 0);
            items.add(v);
        }
        return items;
    }

    private String extractVideoId(String raw) {
        if (raw == null) return "";
        try {
            if (raw.contains("watch?v=")) {
                String id = raw.substring(raw.indexOf("watch?v=") + 8);
                int amp = id.indexOf('&');
                if (amp >= 0) id = id.substring(0, amp);
                return id;
            }
            if (raw.contains("/shorts/")) {
                String id = raw.substring(raw.indexOf("/shorts/") + 8);
                int q = id.indexOf('?');
                if (q >= 0) id = id.substring(0, q);
                return id;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String youtubeThumb(String id) {
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }

    private void showVideos(List<VideoItem> items, String title) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            pageTitle.setText(title);
            String provider = lastProvider.isEmpty() ? "" : " · " + lastProvider;
            statusText.setText(items.size() + " videos" + provider);
            gridContainer.removeAllViews();

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
                        cardLp.setMargins(c > 0 ? dp(8) : 0, 0,
                                c < COLS - 1 ? dp(8) : 0, 0);
                        row.addView(card, cardLp);
                    } else {
                        row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
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

        if (item.duration > 0) {
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
        }
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
        String views = formatViews(item.views);
        if (!views.isEmpty() || !item.uploaded.isEmpty()) {
            if (m.length() > 0) m.append("\n");
            if (!views.isEmpty()) m.append(views);
            if (!views.isEmpty() && !item.uploaded.isEmpty()) m.append(" · ");
            if (!item.uploaded.isEmpty()) m.append(item.uploaded);
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
            v.setScaleX(hasFocus ? 1.012f : 1f);
            v.setScaleY(hasFocus ? 1.012f : 1f);
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
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(6500);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 9; MokaTube/0.3)");
                in = conn.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
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
        lastProvider = "Local";
        List<VideoItem> history = readHistory();
        if (history.isEmpty()) {
            showError("Todavía no has reproducido videos desde MokaTube.", false);
        } else {
            showVideos(history, "Historial");
        }
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

    private void showError(String message, boolean retryHome) {
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            statusText.setText("Sin conexión al proveedor");
            gridContainer.removeAllViews();

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(34), dp(12), dp(12));
            gridContainer.addView(box);

            TextView t = new TextView(this);
            t.setText(message);
            t.setTextColor(Color.LTGRAY);
            t.setTextSize(18);
            box.addView(t);

            TextView detail = new TextView(this);
            detail.setText("MokaTube probará automáticamente varios servidores al reintentar.");
            detail.setTextColor(Color.rgb(150, 150, 150));
            detail.setTextSize(13);
            detail.setPadding(0, dp(8), 0, dp(14));
            box.addView(detail);

            if (retryHome) {
                Button retry = makeButton("Reintentar");
                retry.setOnClickListener(v -> loadHome());
                box.addView(retry, new LinearLayout.LayoutParams(dp(150), dp(46)));
                retry.requestFocus();
            }
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
        return Math.round(value * getResources().getDisplayMetrics().density);
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
