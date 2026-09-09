package com.rood.fastbuy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopeeAccessibilityService extends AccessibilityService {
    private static ShopeeAccessibilityService INSTANCE;
    private SharedPreferences p;
    private final Handler h = new Handler();
    private long lastHandle = 0;
    private boolean busy = false;
    private boolean pollStarted = false;

    private static final String[] SECURITY = new String[]{
            "captcha", "security verification", "verify you are human", "ยืนยันผ่านลิงก์", "รหัส otp", "ยืนยันตัวตน"
    };

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            try {
                if (p != null && p.getBoolean("flow_active", false)) {
                    long now = System.currentTimeMillis();
                    long deadline = p.getLong("flow_deadline", now + 1);
                    if (now > deadline) {
                        stopFlow("หมดเวลา ระบบหยุดโดยไม่สั่งซื้อ", true);
                    } else if (!busy) {
                        handle();
                    }
                }
            } finally {
                h.postDelayed(this, 90);
            }
        }
    };

    public static ShopeeAccessibilityService instance() { return INSTANCE; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        p = getSharedPreferences("fastbuy", MODE_PRIVATE);
        setStatus("Accessibility พร้อม");
        if (!pollStarted) {
            pollStarted = true;
            h.post(poller);
        }
    }

    public void launchShopee(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage("com.shopee.th");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            stopFlow("เปิด Shopee ไม่สำเร็จ: " + e.getMessage(), true);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (p == null || !p.getBoolean("flow_active", false)) return;
        if (event.getPackageName() == null || !"com.shopee.th".contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now > p.getLong("flow_deadline", now + 1)) {
            stopFlow("หมดเวลา ระบบหยุดโดยไม่สั่งซื้อ", true);
            return;
        }
        if (now - lastHandle < 45 || busy) return;
        lastHandle = now;
        h.post(this::handle);
    }

    private void handle() {
        if (busy || p == null || !p.getBoolean("flow_active", false)) return;
        busy = true;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                setStatus("รอหน้า Shopee พร้อม...");
                return;
            }
            if (root.getPackageName() != null && !"com.shopee.th".contentEquals(root.getPackageName())) return;

            List<NodeText> nodes = flatten(root);
            if (containsAny(nodes, SECURITY)) {
                stopFlow("พบ CAPTCHA/OTP/Verification จึงหยุด", true);
                return;
            }

            String state = p.getString("flow_state", "FIRST");
            if ("FIRST".equals(state)) {
                AccessibilityNodeInfo n = findAny(nodes, split(p.getString("first_button", "ซื้อโดยใช้โค้ด|ซื้อเลย")), false);
                if (n != null) {
                    setStatus("พบปุ่มแรก: " + textOf(n));
                    if (click(n)) {
                        p.edit().putString("flow_state", "VARIANT").apply();
                        setStatus("กดปุ่มแรกแล้ว");
                        h.postDelayed(this::handle, 80);
                    }
                } else {
                    setStatus("รอปุ่มแรก...");
                }
                return;
            }

            if ("VARIANT".equals(state)) {
                String v = p.getString("variant", "").trim();
                if (!v.isEmpty()) {
                    AccessibilityNodeInfo n = findAny(nodes, split(v), true);
                    if (n != null && click(n)) {
                        setStatus("เลือกตัวเลือก: " + textOf(n));
                    } else {
                        setStatus("ยังไม่พบตัวเลือก: " + v + " — ไปขั้นถัดไป");
                    }
                }
                p.edit().putString("flow_state", "SECOND").apply();
                h.postDelayed(this::handle, 70);
                return;
            }

            if ("SECOND".equals(state)) {
                if (findAny(nodes, split(p.getString("final_button", "สั่งสินค้า|สั่งซื้อ")), true) != null) {
                    p.edit().putString("flow_state", "CHECKOUT").apply();
                    h.postDelayed(this::handle, 50);
                    return;
                }
                AccessibilityNodeInfo n = findAny(nodes, split(p.getString("second_button", "ซื้อเลย|ยืนยัน|ตกลง")), false);
                if (n != null) {
                    setStatus("พบปุ่มรอบสอง: " + textOf(n));
                    if (click(n)) {
                        p.edit().putString("flow_state", "CHECKOUT").apply();
                        setStatus("กดปุ่มยืนยัน/ซื้อรอบสองแล้ว");
                        h.postDelayed(this::handle, 160);
                    }
                } else {
                    setStatus("รอปุ่มยืนยัน/ซื้อรอบสอง...");
                }
                return;
            }

            if ("CHECKOUT".equals(state)) {
                Double total = extractCheckoutTotal(nodes);
                if (total == null) {
                    setStatus("ถึง Checkout แต่ยังอ่านยอดไม่ได้ — ยังไม่กดสั่ง");
                    return;
                }
                setStatus(String.format(Locale.US, "Checkout %.2f บาท", total));
                String mode = p.getString("mode", "test");
                if ("test".equals(mode)) {
                    stopFlow(String.format(Locale.US, "TEST ผ่าน: Checkout %.2f บาท — ไม่กดสั่งซื้อ", total), false);
                    return;
                }

                double max = doublePref("max_price", 150.0);
                if (total > max) {
                    stopFlow(String.format(Locale.US, "หยุด: ยอด %.2f > เพดาน %.2f บาท", total, max), true);
                    return;
                }

                String required = p.getString("required_price", "").trim();
                if (!required.isEmpty() && !containsText(nodes, required)) {
                    setStatus("ยอดผ่านเพดาน แต่ยังไม่พบข้อความราคา: " + required);
                    return;
                }

                AccessibilityNodeInfo finalBtn = findAny(nodes, split(p.getString("final_button", "สั่งสินค้า|สั่งซื้อ")), true);
                if (finalBtn == null) {
                    setStatus("ยอดผ่าน แต่ยังไม่พบปุ่มสั่งสินค้า");
                    return;
                }
                if (click(finalBtn)) {
                    stopFlow(String.format(Locale.US, "LIVE: กดสั่งสินค้าแล้วที่ยอด %.2f บาท", total), false);
                }
            }
        } finally {
            busy = false;
        }
    }

    private List<NodeText> flatten(AccessibilityNodeInfo root) {
        List<NodeText> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private void walk(AccessibilityNodeInfo n, List<NodeText> out) {
        if (n == null) return;
        String t = textOf(n);
        if (!t.isEmpty() && n.isVisibleToUser()) out.add(new NodeText(n, t));
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out);
    }

    private String textOf(AccessibilityNodeInfo n) {
        String a = n.getText() == null ? "" : n.getText().toString();
        String b = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        return (a + " " + b).trim();
    }

    private AccessibilityNodeInfo findAny(List<NodeText> nodes, String[] terms, boolean exactPreferred) {
        if (terms.length == 0) return null;
        if (exactPreferred) {
            for (String term : terms) {
                for (NodeText nt : nodes) if (nt.text.equalsIgnoreCase(term)) return nt.node;
            }
        }
        for (String term : terms) {
            String q = term.toLowerCase(Locale.ROOT);
            for (NodeText nt : nodes) if (nt.text.toLowerCase(Locale.ROOT).contains(q)) return nt.node;
        }
        return null;
    }

    private boolean click(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 7 && cur != null; i++) {
            if (cur.isClickable() && cur.isEnabled()) {
                try {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                } catch (Exception ignored) {}
            }
            cur = cur.getParent();
        }
        try {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Exception ignored) {}
        return tapCenter(n);
    }

    private boolean tapCenter(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 24 || n == null || !n.isVisibleToUser()) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        float x = r.centerX();
        float y = r.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 35);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private Double extractCheckoutTotal(List<NodeText> nodes) {
        String[] anchors = new String[]{"ยอดชำระทั้งหมด", "ยอดรวมทั้งหมด", "ยอดรวมการสั่งซื้อ", "total payment", "order total"};
        Pattern baht = Pattern.compile("฿\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");
        for (int i = 0; i < nodes.size(); i++) {
            String lower = nodes.get(i).text.toLowerCase(Locale.ROOT);
            boolean anchor = false;
            for (String a : anchors) if (lower.contains(a.toLowerCase(Locale.ROOT))) { anchor = true; break; }
            if (!anchor) continue;
            for (int j = i; j < Math.min(nodes.size(), i + 10); j++) {
                Matcher m = baht.matcher(nodes.get(j).text);
                if (m.find()) {
                    try { return Double.parseDouble(m.group(1).replace(",", "")); }
                    catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private boolean containsAny(List<NodeText> nodes, String[] terms) {
        for (String t : terms) if (containsText(nodes, t)) return true;
        return false;
    }

    private boolean containsText(List<NodeText> nodes, String term) {
        String q = term.toLowerCase(Locale.ROOT);
        for (NodeText nt : nodes) if (nt.text.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private String[] split(String s) {
        if (s == null || s.trim().isEmpty()) return new String[0];
        String[] raw = s.split("\\|");
        List<String> list = new ArrayList<>();
        for (String x : raw) if (!x.trim().isEmpty()) list.add(x.trim());
        return list.toArray(new String[0]);
    }

    private double doublePref(String key, double def) {
        try { return Double.parseDouble(p.getString(key, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private void setStatus(String s) { p.edit().putString("status", s).apply(); }

    private void stopFlow(String message, boolean warn) {
        p.edit().putBoolean("flow_active", false).putBoolean("armed", false).putString("status", message).apply();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void onInterrupt() {
        if (p != null) p.edit().putBoolean("flow_active", false).putString("status", "Accessibility ถูกหยุด").apply();
    }

    @Override public void onDestroy() {
        h.removeCallbacks(poller);
        pollStarted = false;
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private static class NodeText {
        final AccessibilityNodeInfo node;
        final String text;
        NodeText(AccessibilityNodeInfo n, String t) { node = n; text = t; }
    }
}
