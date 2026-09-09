package com.rood.fastbuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class FastBuyService extends Service {
    public static final String ACTION_ARM = "com.rood.fastbuy.ARM";
    public static final String ACTION_TEST = "com.rood.fastbuy.TEST";
    public static final String ACTION_STOP = "com.rood.fastbuy.STOP";
    private static final String CHANNEL = "fastbuy_live";

    private SharedPreferences p;
    private volatile boolean cancelled = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        p = getSharedPreferences("fastbuy", MODE_PRIVATE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cancelled = true;
            p.edit().putBoolean("armed", false).putBoolean("flow_active", false).putString("status", "หยุดแล้ว").apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Shopee Fast Buy")
                .setContentText(ACTION_TEST.equals(action) ? "กำลัง TEST" : "ARM รอเวลา")
                .setSmallIcon(R.drawable.ic_stat_fastbuy)
                .setOngoing(true)
                .build();
        startForeground(1001, notification);
        acquireWakeLock();
        cancelled = false;
        if (worker != null) worker.interrupt();

        if (ACTION_TEST.equals(action)) {
            worker = new Thread(this::runTest, "fastbuy-test");
        } else {
            long target = intent.getLongExtra("target_ms", 0L);
            worker = new Thread(() -> runArmed(target), "fastbuy-arm");
        }
        worker.start();
        return START_STICKY;
    }

    private void runTest() {
        try {
            p.edit()
                    .putBoolean("armed", true)
                    .putString("mode", "test")
                    .putBoolean("flow_active", true)
                    .putString("flow_state", "FIRST")
                    .putLong("flow_deadline", System.currentTimeMillis() + 30000L)
                    .putString("status", "TEST: เปิดสินค้าและเริ่มตรวจปุ่ม")
                    .apply();
            launchShopee();
            waitForFlowEnd(32000L);
        } finally {
            stopSelf();
        }
    }

    private void runArmed(long targetMs) {
        try {
            if (targetMs <= 0) {
                p.edit().putString("status", "เวลาเป้าหมายไม่ถูกต้อง").apply();
                return;
            }
            int pre = intPref("preopen_seconds", 15);
            long preAt = targetMs - Math.max(0, pre) * 1000L;
            p.edit().putBoolean("armed", true).putBoolean("flow_active", false)
                    .putString("mode", "live")
                    .putString("status", "ARM: รอเปิดสินค้าล่วงหน้า").apply();

            waitUntil(preAt);
            if (cancelled) return;
            p.edit().putString("status", "เปิดหน้าสินค้าล่วงหน้าแล้ว").apply();
            launchShopee();

            waitUntil(targetMs);
            if (cancelled) return;
            p.edit()
                    .putBoolean("flow_active", true)
                    .putString("flow_state", "FIRST")
                    .putLong("flow_deadline", targetMs + 35000L)
                    .putString("status", "LIVE: ถึงเวลา กำลังเปิด/รีเฟรชสินค้า")
                    .apply();
            launchShopee();
            waitForFlowEnd(37000L);
        } finally {
            stopSelf();
        }
    }

    private void waitUntil(long target) {
        while (!cancelled) {
            long left = target - System.currentTimeMillis();
            if (left <= 0) return;
            try {
                if (left > 3000) Thread.sleep(Math.min(500, left));
                else if (left > 500) Thread.sleep(Math.min(100, left));
                else if (left > 100) Thread.sleep(20);
                else Thread.sleep(2);
            } catch (InterruptedException e) { return; }
        }
    }

    private void waitForFlowEnd(long maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        while (!cancelled && System.currentTimeMillis() < end) {
            if (!p.getBoolean("flow_active", false)) return;
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
        }
        if (p.getBoolean("flow_active", false)) {
            p.edit().putBoolean("flow_active", false).putString("status", "หมดเวลา ระบบหยุดโดยไม่สั่งซื้อ").apply();
        }
    }

    private void launchShopee() {
        String url = p.getString("product_url", "");
        ShopeeAccessibilityService a = ShopeeAccessibilityService.instance();
        if (a != null) {
            a.launchShopee(url);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setPackage("com.shopee.th");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            p.edit().putBoolean("flow_active", false).putString("status", "เปิด Shopee ไม่สำเร็จ: " + e.getMessage()).apply();
        }
    }

    private int intPref(String key, int def) {
        try { return Integer.parseInt(p.getString(key, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Fast Buy Live", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("แสดงสถานะขณะรอเวลา/ทดสอบ");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastBuy:Live");
        wakeLock.acquire(15 * 60 * 1000L);
    }

    @Override public void onDestroy() {
        cancelled = true;
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
