package com.rood.fastbuy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoBuyAccessibilityService extends AccessibilityService {
    public static final String ACTION_TRIGGER = "com.rood.fastbuy.AUTO_TRIGGER";

    private static final String SHOPEE_PACKAGE = "com.shopee.th";
    private static final long FLOW_TIMEOUT_MS = 9000L;
    private static final long PRICE_PHASE_TIMEOUT_MS = 3500L;
    private static final long CHECK_INTERVAL_MS = 80L;
    private static final int MAX_REFRESHES = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private BroadcastReceiver receiver;

    private boolean running = false;
    private boolean testOnly = false;
    private boolean autoPlaceOrder = false;
    private double targetPrice = 99.0;
    private double maxTotal = 150.0;
    private long startedAt = 0L;
    private long pricePhaseStartedAt = 0L;
    private long lastRefreshAt = 0L;
    private int refreshCount = 0;
    private int stage = 0; // 1=wait promo price, 2=wait checkout

    private static final Pattern MONEY_PATTERN = Pattern.compile("(?:฿|THB\\s*)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = getSharedPreferences("fast_assist", MODE_PRIVATE);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_TRIGGER.equals(intent.getAction())) {
                    startAutoFlow();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TRIGGER);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void startAutoFlow() {
        if (prefs == null) prefs = getSharedPreferences("fast_assist", MODE_PRIVATE);
        running = true;
        testOnly = prefs.getBoolean("test_only", false);
        autoPlaceOrder = prefs.getBoolean("auto_place_order", false);
        targetPrice = safeDouble(prefs.getString("target_price", "99"), 99.0);
        maxTotal = safeDouble(prefs.getString("max_total", "150"), 150.0);
        startedAt = System.currentTimeMillis();
        pricePhaseStartedAt = startedAt;
        lastRefreshAt = 0L;
        refreshCount = 0;
        stage = 1;

        if (!isShopeeForeground()) {
            stopFlow("Shopee ไม่ได้อยู่ด้านหน้า — หยุดเพื่อความปลอดภัย");
            return;
        }

        if (hasSecurityChallenge(getRootInActiveWindow())) {
            stopFlow("พบหน้าตรวจสอบความปลอดภัย — หยุดอัตโนมัติ");
            return;
        }

        performRefresh();
        if (testOnly) {
            handler.postDelayed(() -> stopFlow("TEST สำเร็จ: สั่งรีเฟรชแล้ว และไม่ได้กดซื้อ"), 900L);
            return;
        }
        handler.postDelayed(scanLoop, 220L);
    }

    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            if (now - startedAt > FLOW_TIMEOUT_MS) {
                stopFlow("หมดเวลารอ — ไม่กดสั่งซื้อ");
                return;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !isShopeeRoot(root)) {
                handler.postDelayed(this, CHECK_INTERVAL_MS);
                return;
            }

            if (hasSecurityChallenge(root)) {
                stopFlow("พบหน้าตรวจสอบ/OTP/CAPTCHA — หยุดอัตโนมัติ");
                return;
            }

            if (stage == 1) {
                if (isPromoPriceReady(root)) {
                    AccessibilityNodeInfo buy = findBuyButton(root);
                    if (buy != null && clickNode(buy)) {
                        stage = 2;
                        vibrate(90);
                        handler.postDelayed(this, 140L);
                        return;
                    }
                }

                if (hasVariantSelectionPrompt(root)) {
                    stopFlow("ยังมีหน้าต่างเลือกสี/ไซซ์ — กรุณาเลือกไว้ก่อนเวลา");
                    return;
                }

                long sincePricePhase = now - pricePhaseStartedAt;
                if (sincePricePhase > PRICE_PHASE_TIMEOUT_MS) {
                    stopFlow("ราคาโปรยังไม่เปลี่ยนเป็นเงื่อนไขที่ตั้งไว้ — ไม่ซื้อ");
                    return;
                }

                if (refreshCount < MAX_REFRESHES && now - lastRefreshAt >= 700L) {
                    performRefresh();
                }
                handler.postDelayed(this, CHECK_INTERVAL_MS);
                return;
            }

            if (stage == 2) {
                if (hasVariantSelectionPrompt(root)) {
                    stopFlow("Shopee ขอเลือกตัวเลือกสินค้า — หยุดเพื่อไม่เลือกผิด");
                    return;
                }

                AccessibilityNodeInfo order = findOrderButton(root);
                if (order != null) {
                    Double total = findCheckoutTotal(root);
                    if (total == null) {
                        handler.postDelayed(this, CHECK_INTERVAL_MS);
                        return;
                    }
                    if (total > maxTotal + 0.001) {
                        stopFlow(String.format(Locale.US, "ยอดรวม %.2f เกินเพดาน %.2f — ไม่ซื้อ", total, maxTotal));
                        return;
                    }
                    if (!autoPlaceOrder) {
                        stopFlow(String.format(Locale.US, "ถึง Checkout แล้ว ยอด %.2f — รอคุณกดสั่งซื้อ", total));
                        return;
                    }
                    if (clickNode(order)) {
                        vibrate(300);
                        stopFlow(String.format(Locale.US, "กดสั่งซื้อแล้ว • ยอดตรวจพบ %.2f", total));
                        return;
                    }
                }
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

    private void performRefresh() {
        if (!running || !isShopeeForeground()) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.50f;
        float y1 = dm.heightPixels * 0.28f;
        float y2 = dm.heightPixels * 0.72f;
        Path path = new Path();
        path.moveTo(x, y1);
        path.lineTo(x, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 180))
                .build();
        dispatchGesture(gesture, null, null);
        refreshCount++;
        lastRefreshAt = System.currentTimeMillis();
    }

    private boolean isPromoPriceReady(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo buy = findBuyButton(root);
        if (buy == null) return false;

        String buyText = nodeText(buy);
        Double buttonPrice = firstMoney(buyText);
        if (buttonPrice != null) {
            return buttonPrice <= targetPrice + 0.001;
        }

        return containsExactTargetPrice(root, targetPrice);
    }

    private boolean containsExactTargetPrice(AccessibilityNodeInfo node, double target) {
        if (node == null) return false;
        String t = nodeText(node);
        Matcher m = MONEY_PATTERN.matcher(t);
        while (m.find()) {
            double v = parseMoneyNumber(m.group(1));
            if (Math.abs(v - target) < 0.01) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsExactTargetPrice(node.getChild(i), target)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findBuyButton(AccessibilityNodeInfo root) {
        return findFirstContaining(root,
                "ซื้อโดยใช้โค้ด",
                "ซื้อเลย",
                "ซื้อสินค้า");
    }

    private AccessibilityNodeInfo findOrderButton(AccessibilityNodeInfo root) {
        return findFirstContaining(root,
                "สั่งซื้อ",
                "ทำการสั่งซื้อ",
                "Place Order");
    }

    private boolean hasVariantSelectionPrompt(AccessibilityNodeInfo root) {
        return anyTextContains(root, "กรุณาเลือกตัวเลือกสินค้า")
                || anyTextContains(root, "เลือกตัวเลือกสินค้า")
                || anyTextContains(root, "เลือกสี")
                || anyTextContains(root, "เลือกไซซ์")
                || anyTextContains(root, "Select variation");
    }

    private boolean hasSecurityChallenge(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String[] words = new String[]{
                "CAPTCHA", "captcha", "OTP", "รหัส OTP", "ยืนยันตัวตน",
                "ตรวจสอบความปลอดภัย", "กรุณาตรวจสอบ", "ยืนยันหมายเลขโทรศัพท์",
                "verification", "verify your identity", "robot"
        };
        for (String w : words) {
            if (anyTextContains(root, w)) return true;
        }
        return false;
    }

    private Double findCheckoutTotal(AccessibilityNodeInfo root) {
        String[] labels = new String[]{
                "ยอดชำระทั้งหมด", "ยอดชำระ", "ยอดรวมทั้งหมด", "ยอดรวม",
                "รวมการสั่งซื้อ", "Total Payment", "Order Total"
        };
        for (String label : labels) {
            AccessibilityNodeInfo n = findFirstContaining(root, label);
            if (n == null) continue;

            Double same = firstMoney(nodeText(n));
            if (same != null) return same;

            AccessibilityNodeInfo p = n.getParent();
            if (p != null) {
                int index = indexOfChild(p, n);
                int start = Math.max(0, index);
                int end = Math.min(p.getChildCount() - 1, index + 4);
                for (int i = start; i <= end; i++) {
                    Double v = firstMoneyRecursive(p.getChild(i), 2);
                    if (v != null) return v;
                }
                Double parentValue = firstMoneyRecursive(p, 2);
                if (parentValue != null) return parentValue;
            }
        }
        return null;
    }

    private int indexOfChild(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        if (parent == null || child == null) return 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null && c.equals(child)) return i;
        }
        return 0;
    }

    private Double firstMoneyRecursive(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth < 0) return null;
        Double v = firstMoney(nodeText(node));
        if (v != null) return v;
        for (int i = 0; i < node.getChildCount(); i++) {
            Double child = firstMoneyRecursive(node.getChild(i), depth - 1);
            if (child != null) return child;
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstContaining(AccessibilityNodeInfo node, String... needles) {
        if (node == null) return null;
        String text = nodeText(node);
        for (String needle : needles) {
            if (!needle.isEmpty() && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFirstContaining(node.getChild(i), needles);
            if (found != null) return found;
        }
        return null;
    }

    private boolean anyTextContains(AccessibilityNodeInfo node, String needle) {
        return findFirstContaining(node, needle) != null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean isShopeeForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && isShopeeRoot(root);
    }

    private boolean isShopeeRoot(AccessibilityNodeInfo root) {
        CharSequence p = root.getPackageName();
        return p != null && SHOPEE_PACKAGE.contentEquals(p);
    }

    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        List<String> values = new ArrayList<>();
        if (node.getText() != null) values.add(node.getText().toString());
        if (node.getContentDescription() != null) values.add(node.getContentDescription().toString());
        if (node.getHintText() != null) values.add(node.getHintText().toString());
        return String.join(" ", values);
    }

    private Double firstMoney(String text) {
        if (text == null) return null;
        Matcher m = MONEY_PATTERN.matcher(text);
        if (m.find()) return parseMoneyNumber(m.group(1));
        return null;
    }

    private double parseMoneyNumber(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double safeDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim().replace(",", ""));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void stopFlow(String message) {
        running = false;
        handler.removeCallbacks(scanLoop);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        vibrate(120);
    }

    private void vibrate(long millis) {
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(millis);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The timed flow uses a local Handler. Events are intentionally not used to spam requests.
    }

    @Override
    public void onInterrupt() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
