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
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
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
    private SharedPreferences p;
    private EditText url, time, variant, firstButton, secondButton, finalButton, maxPrice, preopen, requiredPrice;
    private TextView status;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        p = getSharedPreferences("fastbuy", MODE_PRIVATE);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        handler.post(refreshStatus);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 60);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Shopee Fast Buy — Local");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(205, 45, 60));
        title.setPadding(0,0,0,10);
        root.addView(title);

        TextView privacy = new TextView(this);
        privacy.setText("ไม่มีสิทธิ์ INTERNET / SMS / Contacts • Accessibility จำกัดเฉพาะแอป Shopee • ไม่เก็บ Password/OTP");
        privacy.setTextSize(13);
        privacy.setTextColor(Color.DKGRAY);
        privacy.setPadding(0,0,0,18);
        root.addView(privacy);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(16,14,16,14);
        status.setBackgroundColor(Color.rgb(240,240,240));
        root.addView(status);

        Button preset = button("Preset รองเท้า 99 / US 11");
        preset.setOnClickListener(v -> {
            url.setText("https://s.shopee.co.th/9fKbNzpTfL");
            time.setText("14:00:00");
            variant.setText("11");
            firstButton.setText("ซื้อโดยใช้โค้ด|ซื้อเลย");
            secondButton.setText("ซื้อเลย|ยืนยัน|ตกลง");
            finalButton.setText("สั่งสินค้า|สั่งซื้อ");
            maxPrice.setText("150");
            preopen.setText("15");
            requiredPrice.setText("");
            save();
        });
        root.addView(preset);

        url = field(root, "ลิงก์สินค้า", "product_url", "https://s.shopee.co.th/9fKbNzpTfL", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        time = field(root, "เวลาเป้าหมาย HH:mm:ss", "target_time", "14:00:00", InputType.TYPE_CLASS_DATETIME);
        variant = field(root, "ตัวเลือก/ไซซ์ (ใช้ | คั่นหลายคำ)", "variant", "11", InputType.TYPE_CLASS_TEXT);
        firstButton = field(root, "ปุ่มแรก", "first_button", "ซื้อโดยใช้โค้ด|ซื้อเลย", InputType.TYPE_CLASS_TEXT);
        secondButton = field(root, "ปุ่มยืนยัน/ซื้อรอบสอง", "second_button", "ซื้อเลย|ยืนยัน|ตกลง", InputType.TYPE_CLASS_TEXT);
        finalButton = field(root, "ปุ่มสุดท้าย", "final_button", "สั่งสินค้า|สั่งซื้อ", InputType.TYPE_CLASS_TEXT);
        maxPrice = field(root, "ยอดชำระสูงสุด (บาท)", "max_price", "150", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        requiredPrice = field(root, "ข้อความราคาที่ต้องพบก่อนสั่ง (เว้นว่างได้)", "required_price", "", InputType.TYPE_CLASS_TEXT);
        preopen = field(root, "เปิดสินค้าล่วงหน้า (วินาที)", "preopen_seconds", "15", InputType.TYPE_CLASS_NUMBER);

        Button save = button("บันทึกค่า");
        save.setOnClickListener(v -> { save(); toast("บันทึกแล้ว"); });
        root.addView(save);

        Button accessibility = button("1) เปิดสิทธิ์ Accessibility");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button open = button("2) เปิดหน้าสินค้า Shopee");
        open.setOnClickListener(v -> { save(); openShopee(); });
        root.addView(open);

        Button test = button("3) TEST ตอนนี้ — หยุดก่อนสั่งซื้อ");
        test.setOnClickListener(v -> {
            save();
            Intent i = new Intent(this, FastBuyService.class).setAction(FastBuyService.ACTION_TEST);
            startForegroundServiceCompat(i);
        });
        root.addView(test);

        Button live = button("4) ARM LIVE ตามเวลาที่ตั้ง");
        live.setBackgroundColor(Color.rgb(210,35,55));
        live.setTextColor(Color.WHITE);
        live.setOnClickListener(v -> armLive());
        root.addView(live);

        Button stop = button("หยุด Auto");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, FastBuyService.class).setAction(FastBuyService.ACTION_STOP);
            startForegroundServiceCompat(i);
        });
        root.addView(stop);

        Button battery = button("เปิดการตั้งค่าแบตเตอรี่ (แนะนำ: ไม่จำกัด)");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        root.addView(battery);

        TextView note = new TextView(this);
        note.setText("ก่อน LIVE: ต้องปลดล็อกหน้าจอ, Shopee Login อยู่แล้ว, เลือกวิธีชำระเงินที่ต้องการไว้ก่อน และทดสอบ TEST ให้ผ่านอย่างน้อย 1 ครั้ง\n\nระบบจะหยุดถ้าเจอ CAPTCHA/OTP/ยืนยันผ่านลิงก์ หรืออ่านยอด Checkout ไม่ได้");
        note.setPadding(0,18,0,0);
        note.setTextColor(Color.DKGRAY);
        root.addView(note);

        setContentView(scroll);
    }

    private EditText field(LinearLayout root, String label, String key, String def, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setPadding(0,18,0,4);
        root.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(p.getString(key, def));
        e.setInputType(inputType);
        root.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setPadding(10,12,10,12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0,14,0,0);
        b.setLayoutParams(lp);
        return b;
    }

    private void save() {
        p.edit()
            .putString("product_url", url.getText().toString().trim())
            .putString("target_time", time.getText().toString().trim())
            .putString("variant", variant.getText().toString().trim())
            .putString("first_button", firstButton.getText().toString().trim())
            .putString("second_button", secondButton.getText().toString().trim())
            .putString("final_button", finalButton.getText().toString().trim())
            .putString("max_price", maxPrice.getText().toString().trim())
            .putString("required_price", requiredPrice.getText().toString().trim())
            .putString("preopen_seconds", preopen.getText().toString().trim())
            .apply();
    }

    private void openShopee() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.getText().toString().trim()));
            i.setPackage("com.shopee.th");
            startActivity(i);
        } catch (Exception e) {
            toast("เปิด Shopee ไม่สำเร็จ: " + e.getMessage());
        }
    }

    private void armLive() {
        try {
            save();
            LocalTime lt = LocalTime.parse(time.getText().toString().trim(), DateTimeFormatter.ofPattern("HH:mm:ss"));
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime target = ZonedDateTime.of(LocalDate.now(), lt, ZoneId.systemDefault());
            if (!target.isAfter(now)) target = target.plusDays(1);
            Intent i = new Intent(this, FastBuyService.class).setAction(FastBuyService.ACTION_ARM);
            i.putExtra("target_ms", target.toInstant().toEpochMilli());
            startForegroundServiceCompat(i);
            toast("ARM แล้ว: " + target.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")));
        } catch (Exception e) {
            toast("เวลาไม่ถูกต้อง ใช้รูปแบบ HH:mm:ss");
        }
    }

    private void startForegroundServiceCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            String s = p.getString("status", "ยังไม่ทำงาน");
            status.setText("สถานะ: " + s);
            handler.postDelayed(this, 500);
        }
    };

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshStatus);
        super.onDestroy();
    }
}
