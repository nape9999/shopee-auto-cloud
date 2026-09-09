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

import androidx.annotation.Nullable;

import java.util.Locale;

public class AssistTimerService extends Service {
    public static final String ACTION_ARM = "com.rood.fastbuy.ARM_ASSIST";
    public static final String ACTION_STOP = "com.rood.fastbuy.STOP_ASSIST";
    private static final int NOTIF_ID = 2202;
    private static final String CHANNEL_ID = "fast_assist_timer";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long targetMs = 0L;
    private String note = "";
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
            note = intent.getStringExtra("note");
            if (note == null) note = "";
            signaled3 = signaled2 = signaled1 = signaled0 = false;
            startForeground(NOTIF_ID, buildNotification("กำลังจับเวลา", targetMs - System.currentTimeMillis()));
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
                pulse(70);
            }
            if (remain <= 2000 && remain > 1000 && !signaled2) {
                signaled2 = true;
                pulse(90);
            }
            if (remain <= 1000 && remain > 0 && !signaled1) {
                signaled1 = true;
                pulse(120);
            }
            if (remain <= 0 && !signaled0) {
                signaled0 = true;
                pulse(700);
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 500); } catch (Exception ignored) {}
                updateNotification("0.000 — กดซื้อทันที", 0);
                handler.postDelayed(this::stopTimer, 10000L);
                return;
            }

            updateNotification("กำลังจับเวลา", remain);
            long next = remain > 5000 ? 500L : 25L;
            handler.postDelayed(this, next);
        }
    };

    private void updateNotification(String title, long remain) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(title, remain));
    }

    private Notification buildNotification(String title, long remain) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (remain > 0) {
            long sec = remain / 1000;
            long ms = remain % 1000;
            text = String.format(Locale.US, "%02d:%02d.%03d", sec / 60, sec % 60, ms);
        } else {
            text = "ถึงเวลาแล้ว";
        }
        if (!note.isEmpty()) text += " • " + note;

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(remain > 0)
                .setOnlyAlertOnce(true)
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
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(millis);
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Fast Assist Timer", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("ตัวจับเวลาสำหรับเตือนช่วง Flash Sale");
            c.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(c);
        }
    }

    private void stopTimer() {
        handler.removeCallbacksAndMessages(null);
        targetMs = 0L;
        stopForeground(true);
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
