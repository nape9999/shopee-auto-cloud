package com.rood.fastbuy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private EditText urlField, timeField, noteField;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("fast_assist", MODE_PRIVATE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 60);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Shopee Fast Assist v2");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(225, 62, 45));
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView safe = new TextView(this);
        safe.setText("โหมดช่วยจับเวลา • ไม่ใช้ Accessibility • ไม่อ่านหน้าจอ • ไม่กดปุ่มซื้อ/ชำระเงินแทน");
        safe.setTextSize(14);
        safe.setTextColor(Color.DKGRAY);
        safe.setPadding(0, 0, 0, 18);
        root.addView(safe);

        status = new TextView(this);
        status.setText("พร้อมตั้งเวลา");
        status.setTextSize(16);
        status.setPadding(18, 16, 18, 16);
        status.setBackgroundColor(Color.rgb(242, 242, 242));
        root.addView(status);

        Button presetShoe = button("Preset: Adidas 99 / US 11");
        presetShoe.setOnClickListener(v -> {
            urlField.setText("https://s.shopee.co.th/9fKbNzpTfL");
            timeField.setText("14:00:00");
            noteField.setText("Adidas Adizero Evo SL • US 11");
            save();
            status.setText("โหลด Preset รองเท้าแล้ว");
        });
        root.addView(presetShoe);

        Button presetPhone = button("Preset: Galaxy Z Flip8 / Black");
        presetPhone.setOnClickListener(v -> {
            urlField.setText("https://s.shopee.co.th/8fS4AtohXU");
            timeField.setText("00:00:00");
            noteField.setText("Galaxy Z Flip8 • Black");
            save();
            status.setText("โหลด Preset โทรศัพท์แล้ว");
        });
        root.addView(presetPhone);

        urlField = field(root, "ลิงก์สินค้า", "product_url", "https://s.shopee.co.th/8fS4AtohXU", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        timeField = field(root, "เวลาเป้าหมาย HH:mm:ss", "target_time", "00:00:00", InputType.TYPE_CLASS_DATETIME);
        noteField = field(root, "โน้ตสินค้า / สี / ไซซ์", "product_note", "Black", InputType.TYPE_CLASS_TEXT);

        Button save = button("บันทึกค่า");
        save.setOnClickListener(v -> {
            save();
            toast("บันทึกแล้ว");
        });
        root.addView(save);

        Button open = button("เปิดหน้าสินค้า Shopee ตอนนี้");
        open.setOnClickListener(v -> {
            save();
            openShopee();
        });
        root.addView(open);

        Button test = button("TEST 5 วินาที — ทดสอบเสียง/สั่น");
        test.setOnClickListener(v -> {
            save();
            long target = System.currentTimeMillis() + 5000L;
            startAssist(target, false);
            status.setText("TEST: รอ 5 วินาที");
        });
        root.addView(test);

        Button arm = button("ARM + เปิด Shopee แล้วรอวินาทีจริง");
        arm.setTextColor(Color.WHITE);
        arm.setBackgroundColor(Color.rgb(225, 62, 45));
        arm.setOnClickListener(v -> armAndOpen());
        root.addView(arm);

        Button stop = button("หยุดการจับเวลา");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, AssistTimerService.class).setAction(AssistTimerService.ACTION_STOP);
            startForegroundCompat(i);
            status.setText("หยุดแล้ว");
        });
        root.addView(stop);

        Button battery = button("ตั้งค่าแบตเตอรี่ (แนะนำ: ไม่จำกัด)");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery);

        TextView instructions = new TextView(this);
        instructions.setText("วิธีใช้เร็วสุด:\n1) เลือก Preset หรือใส่ลิงก์+เวลา\n2) ก่อนโปรเริ่ม กด ARM + เปิด Shopee\n3) แอปจะจับเวลาอยู่เบื้องหลัง ขณะคุณอยู่หน้าสินค้า\n4) ช่วง 3-2-1 วินาทีจะสั่นเตือน และที่ 0 จะสั่นยาว+เสียง\n5) คุณกดปุ่มซื้อ/สั่งสินค้าบน Shopee เองทันที\n\nแนะนำ: เลือกสี/ไซซ์ วิธีชำระเงิน และคูปองให้พร้อมก่อนเวลา รวมถึงปิด Battery Saver");
        instructions.setTextSize(14);
        instructions.setTextColor(Color.DKGRAY);
        instructions.setPadding(0, 20, 0, 0);
        root.addView(instructions);

        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String key, String def, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setPadding(0, 18, 0, 4);
        root.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(prefs.getString(key, def));
        root.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 14, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void save() {
        prefs.edit()
                .putString("product_url", urlField.getText().toString().trim())
                .putString("target_time", timeField.getText().toString().trim())
                .putString("product_note", noteField.getText().toString().trim())
                .apply();
    }

    private void armAndOpen() {
        try {
            save();
            LocalTime t = LocalTime.parse(timeField.getText().toString().trim(), DateTimeFormatter.ofPattern("HH:mm:ss"));
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime target = ZonedDateTime.of(LocalDate.now(), t, ZoneId.systemDefault());
            if (!target.isAfter(now)) target = target.plusDays(1);
            long targetMs = target.toInstant().toEpochMilli();
            startAssist(targetMs, true);
            status.setText("ARM แล้ว: " + target.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")));
            openShopee();
        } catch (Exception e) {
            toast("รูปแบบเวลาไม่ถูกต้อง ใช้ HH:mm:ss เช่น 00:00:00");
        }
    }

    private void startAssist(long targetMs, boolean live) {
        Intent i = new Intent(this, AssistTimerService.class).setAction(AssistTimerService.ACTION_ARM);
        i.putExtra("target_ms", targetMs);
        i.putExtra("note", noteField.getText().toString().trim());
        i.putExtra("live", live);
        startForegroundCompat(i);
    }

    private void openShopee() {
        try {
            String u = urlField.getText().toString().trim();
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.setPackage("com.shopee.th");
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(urlField.getText().toString().trim())));
            } catch (Exception ignored) {
                toast("เปิดลิงก์สินค้าไม่สำเร็จ");
            }
        }
    }

    private void startForegroundCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
