package com.rood.fastbuy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.Locale;

public class AssistTimerService extends Service {
    public static final String ACTION_ARM = "com.rood.fastbuy.ARM_ASSIST";
    public static final String ACTION_STOP = "com.rood.fastbuy.STOP_ASSIST";

    private static final int NOTIF_ID = 2202;
    private static final String CHANNEL_ID = "f_v4_timer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long targetMs = 0L;
    private String productUrl = "";
    private boolean signaled3, signaled2, signaled1, signaled0;
    private ToneGenerator tone;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopTimer();
            return START_NOT_STICKY;
        }

        if (ACTION_ARM.equals(action)) {
            targetMs = intent.getLongExtra("target_ms", 0L);
            productUrl = intent.getStringExtra("product_url");
            if (productUrl == null) productUrl = "";
            signaled3 = signaled2 = signaled1 = signaled0 = false;
            startForeground(NOTIF_ID, buildNotification("F กำลังจับเวลา", targetMs - System.currentTimeMillis(), true));
            handler.removeCallbacks(tick);
            handler.post(tick);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (targetMs <= 0L) return;
            long remain = targetMs - System.currentTimeMillis();

            if (remain <= 3000 && remain > 2000 && !signaled3) {
                signaled3 = true;
                pulse(60);
            }
            if (remain <= 2000 && remain > 1000 && !signaled2) {
                signaled2 = true;
                pulse(80);
            }
            if (remain <= 1000 && remain > 0 && !signaled1) {
                signaled1 = true;
                pulse(110);
            }

            if (remain <= 0 && !signaled0) {
                signaled0 = true;
                pulse(260);
                try {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180);
                } catch (Exception ignored) {
                }

                Intent refresh = new Intent(MainActivity.ACTION_REFRESH_NOW);
                refresh.setPackage(getPackageName());
                sendBroadcast(refresh);

                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, buildNotification("ถึงเวลาแล้ว • รีเฟรชสินค้าใน F", 0L, false));

                handler.postDelayed(this::stopTimer, 4000L);
                return;
            }

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification("F กำลังจับเวลา", remain, true));

            long next;
            if (remain > 5000) next = 500L;
            else if (remain > 1000) next = 50L;
            else next = 10L;
            handler.postDelayed(this, next);
        }
    };

    private Notification buildNotification(String title, long remain, boolean ongoing) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.putExtra("refresh_on_open", remain <= 0);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (remain > 0) {
            long sec = remain / 1000;
            long ms = remain % 1000;
            text = String.format(Locale.US, "%02d:%02d.%03d", sec / 60, sec % 60, ms);
        } else {
            text = "แตะเพื่อเปิด F และดูสินค้าล่าสุด";
        }

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(ongoing)
                .setAutoCancel(!ongoing)
                .setContentIntent(pi)
                .build();
    }

    private void pulse(long millis) {
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
        } catch (Exception ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "F Timer",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("จับเวลาเพื่อรีเฟรชสินค้าภายใน F");
            channel.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void stopTimer() {
        handler.removeCallbacksAndMessages(null);
        targetMs = 0L;
        stopForeground(false);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tone != null) {
            tone.release();
            tone = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
