package com.rood.fastbuy;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    public static final String ACTION_REFRESH_NOW = "com.rood.fastbuy.REFRESH_NOW";

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_RECENTS = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private EditText urlField;
    private EditText timeField;
    private TextView status;
    private WebView webView;
    private LinearLayout recentBox;
    private BroadcastReceiver refreshReceiver;
    private boolean receiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("fast_assist", MODE_PRIVATE);
        buildUi();
        handleIncomingIntent(getIntent());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerRefreshReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(this::checkClipboardAutomatically, 180L);
        if (getIntent() != null && getIntent().getBooleanExtra("refresh_on_open", false)) {
            getIntent().removeExtra("refresh_on_open");
            handler.postDelayed(this::refreshPreviewBurst, 250L);
        }
    }

    @Override
    protected void onStop() {
        unregisterRefreshReceiver();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(50));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("F");
        title.setTextSize(32);
        title.setTextColor(Color.rgb(225, 62, 45));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v4 Quick Capture • แชร์เข้า F • วางลิงก์อัตโนมัติ • ดูสินค้าในแอป");
        sub.setTextSize(14);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        status = new TextView(this);
        status.setText("พร้อม • ไม่ใช้ Accessibility");
        status.setTextSize(15);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(242, 242, 242));
        root.addView(status);

        TextView hint = new TextView(this);
        hint.setText("เร็วสุด: จากหน้าสินค้า กด แชร์ → F แล้วลิงก์จะเข้ามาและเปิดดูให้เอง\nหรือกดคัดลอกลิงก์ แล้วกลับเข้า F — ระบบจะวางให้อัตโนมัติ");
        hint.setTextSize(14);
        hint.setTextColor(Color.DKGRAY);
        hint.setPadding(0, dp(14), 0, dp(4));
        root.addView(hint);

        urlField = field(root, "ลิงก์สินค้าล่าสุด", "product_url", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        Button clipboard = button("รับลิงก์จากคลิปบอร์ดตอนนี้");
        clipboard.setOnClickListener(v -> {
            if (!captureFromClipboard(true)) {
                toast("ยังไม่พบลิงก์สินค้าที่รองรับในคลิปบอร์ด");
            }
        });
        root.addView(clipboard);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actionRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button preview = button("ดูใน F");
        preview.setOnClickListener(v -> loadCurrentPreview());
        actionRow.addView(preview, rowWeight());

        Button external = button("เปิดในแอปต้นทาง");
        external.setOnClickListener(v -> openCurrentInSourceApp());
        actionRow.addView(external, rowWeight());

        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(refreshRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button reload = button("รีเฟรชราคาใน F");
        reload.setOnClickListener(v -> refreshPreviewBurst());
        refreshRow.addView(reload, rowWeight());

        Button clear = button("ล้างลิงก์");
        clear.setOnClickListener(v -> {
            urlField.setText("");
            prefs.edit().remove("product_url").apply();
            if (webView != null) webView.loadUrl("about:blank");
            status.setText("ล้างลิงก์แล้ว");
        });
        refreshRow.addView(clear, rowWeight());

        TextView previewLabel = new TextView(this);
        previewLabel.setText("ดูสินค้าภายใน F");
        previewLabel.setTextSize(17);
        previewLabel.setTextColor(Color.BLACK);
        previewLabel.setPadding(0, dp(18), 0, dp(8));
        root.addView(previewLabel);

        webView = new WebView(this);
        configureWebView(webView);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(500));
        root.addView(webView, webLp);

        TextView webNote = new TextView(this);
        webNote.setText("หมายเหตุ: โปรบางรายการเป็น App-only จึงอาจไม่แสดงราคา/ปุ่มเหมือนในแอปต้นทาง ใช้ปุ่ม “เปิดในแอปต้นทาง” เมื่อต้องการหน้าจริง");
        webNote.setTextSize(12);
        webNote.setTextColor(Color.GRAY);
        webNote.setPadding(0, dp(8), 0, dp(8));
        root.addView(webNote);

        timeField = field(root, "เวลารีเฟรช HH:mm:ss", "target_time", "00:00:00", InputType.TYPE_CLASS_DATETIME);

        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(timerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button arm = button("ARM รีเฟรช");
        arm.setTextColor(Color.WHITE);
        arm.setBackgroundColor(Color.rgb(225, 62, 45));
        arm.setOnClickListener(v -> armRefreshTimer());
        timerRow.addView(arm, rowWeight());

        Button stop = button("หยุดเวลา");
        stop.setOnClickListener(v -> stopTimer());
        timerRow.addView(stop, rowWeight());

        TextView recentTitle = new TextView(this);
        recentTitle.setText("สินค้าล่าสุด");
        recentTitle.setTextSize(18);
        recentTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(recentTitle);

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(recentBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderRecents();

        TextView privacy = new TextView(this);
        privacy.setText("F v4 ไม่ใช้ Accessibility และไม่อ่านหน้าจอแอปอื่น คลิปบอร์ดจะถูกตรวจเฉพาะตอนที่ F เปิดอยู่ด้านหน้าเท่านั้น");
        privacy.setTextSize(12);
        privacy.setTextColor(Color.GRAY);
        privacy.setPadding(0, dp(18), 0, 0);
        root.addView(privacy);

        setContentView(scroll);
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Uri u = Uri.parse(url);
                String scheme = u.getScheme();
                return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }
        });
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String candidate = null;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null) candidate = extractSupportedUrl(shared.toString());
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            candidate = extractSupportedUrl(intent.getData().toString());
        }

        if (candidate != null) {
            acceptUrl(candidate, "รับจากการแชร์แล้ว", true);
        }
    }

    private void checkClipboardAutomatically() {
        captureFromClipboard(false);
    }

    private boolean captureFromClipboard(boolean forceMessage) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return false;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;

            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return false;

            String found = extractSupportedUrl(text.toString());
            if (found == null) return false;

            String current = urlField.getText().toString().trim();
            if (found.equals(current)) {
                if (forceMessage) status.setText("ลิงก์ในคลิปบอร์ดเป็นสินค้าปัจจุบันอยู่แล้ว");
                return true;
            }

            acceptUrl(found, "พบลิงก์จากคลิปบอร์ด • วางให้อัตโนมัติแล้ว", true);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractSupportedUrl(String text) {
        if (text == null) return null;
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = cleanUrl(matcher.group());
            if (isSupportedShoppingUrl(candidate)) return candidate;
        }
        return null;
    }

    private String cleanUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (c == '.' || c == ',' || c == ')' || c == ']' || c == '}' || c == '>' || c == '"' || c == '\'') {
                s = s.substring(0, s.length() - 1);
            } else {
                break;
            }
        }
        return s;
    }

    private boolean isSupportedShoppingUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.contains("shopee") || host.equals("shp.ee") || host.endsWith(".shp.ee");
        } catch (Exception e) {
            return false;
        }
    }

    private void acceptUrl(String url, String message, boolean autoPreview) {
        urlField.setText(url);
        prefs.edit().putString("product_url", url).apply();
        addRecent(url);
        renderRecents();
        status.setText(message);
        if (autoPreview) handler.postDelayed(this::loadCurrentPreview, 120L);
    }

    private void loadCurrentPreview() {
        String url = urlField.getText().toString().trim();
        if (!isSupportedShoppingUrl(url)) {
            toast("ยังไม่มีลิงก์สินค้าที่รองรับ");
            return;
        }
        prefs.edit().putString("product_url", url).apply();
        addRecent(url);
        renderRecents();
        webView.loadUrl(url);
        status.setText("กำลังเปิดสินค้าภายใน F");
    }

    private void refreshPreviewBurst() {
        String url = urlField.getText().toString().trim();
        if (!isSupportedShoppingUrl(url)) {
            status.setText("ยังไม่มีสินค้าสำหรับรีเฟรช");
            return;
        }
        if (webView.getUrl() == null || "about:blank".equals(webView.getUrl())) {
            webView.loadUrl(url);
        } else {
            webView.reload();
        }
        handler.postDelayed(() -> {
            if (webView != null) webView.reload();
        }, 350L);
        handler.postDelayed(() -> {
            if (webView != null) webView.reload();
        }, 700L);
        status.setText("รีเฟรชราคาใน F 3 จังหวะ");
    }

    private void openCurrentInSourceApp() {
        String url = urlField.getText().toString().trim();
        if (!isSupportedShoppingUrl(url)) {
            toast("ยังไม่มีลิงก์สินค้า");
            return;
        }
        prefs.edit().putString("product_url", url).apply();
        addRecent(url);
        renderRecents();

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.shopee.th");
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                toast("เปิดลิงก์ไม่สำเร็จ");
            }
        }
    }

    private void armRefreshTimer() {
        try {
            String url = urlField.getText().toString().trim();
            if (!isSupportedShoppingUrl(url)) {
                toast("ใส่หรือแชร์ลิงก์สินค้าก่อน");
                return;
            }

            LocalTime time = LocalTime.parse(
                    timeField.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("HH:mm:ss"));
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime target = ZonedDateTime.of(LocalDate.now(zone), time, zone);
            if (!target.isAfter(now)) target = target.plusDays(1);

            prefs.edit()
                    .putString("product_url", url)
                    .putString("target_time", timeField.getText().toString().trim())
                    .apply();

            Intent intent = new Intent(this, AssistTimerService.class).setAction(AssistTimerService.ACTION_ARM);
            intent.putExtra("target_ms", target.toInstant().toEpochMilli());
            intent.putExtra("product_url", url);
            startForegroundCompat(intent);
            status.setText("ARM แล้ว • " + target.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")));
        } catch (Exception e) {
            toast("เวลาไม่ถูกต้อง ใช้ HH:mm:ss เช่น 00:00:00");
        }
    }

    private void stopTimer() {
        Intent intent = new Intent(this, AssistTimerService.class).setAction(AssistTimerService.ACTION_STOP);
        startForegroundCompat(intent);
        status.setText("หยุดเวลาแล้ว");
    }

    private void registerRefreshReceiver() {
        if (receiverRegistered) return;
        refreshReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_REFRESH_NOW.equals(intent.getAction())) {
                    refreshPreviewBurst();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REFRESH_NOW);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterRefreshReceiver() {
        if (!receiverRegistered || refreshReceiver == null) return;
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void addRecent(String url) {
        List<String> urls = readRecents();
        urls.remove(url);
        urls.add(0, url);
        while (urls.size() > MAX_RECENTS) urls.remove(urls.size() - 1);
        writeRecents(urls);
    }

    private List<String> readRecents() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("recent_urls", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) out.add(value);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeRecents(List<String> urls) {
        JSONArray array = new JSONArray();
        for (String url : urls) array.put(url);
        prefs.edit().putString("recent_urls", array.toString()).apply();
    }

    private void renderRecents() {
        if (recentBox == null) return;
        recentBox.removeAllViews();
        List<String> urls = readRecents();

        if (urls.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("ยังไม่มีสินค้า • แชร์สินค้ามาที่ F ได้เลย");
            empty.setTextColor(Color.GRAY);
            empty.setTextSize(13);
            recentBox.addView(empty);
            return;
        }

        int index = 1;
        for (String url : urls) {
            Button b = button(index + ") " + shortDisplay(url));
            b.setOnClickListener(v -> acceptUrl(url, "เลือกสินค้าจากประวัติแล้ว", true));
            recentBox.addView(b);
            index++;
        }
    }

    private String shortDisplay(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "สินค้า" : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String text = host + path;
            return text.length() > 48 ? text.substring(0, 45) + "…" : text;
        } catch (Exception e) {
            return url.length() > 48 ? url.substring(0, 45) + "…" : url;
        }
    }

    private EditText field(LinearLayout root, String label, String key, String def, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setPadding(0, dp(16), 0, dp(4));
        root.addView(l);

        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(prefs.getString(key, def));
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout.LayoutParams rowWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startForegroundCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
