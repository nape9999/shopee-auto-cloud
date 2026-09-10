package com.rood.fastbuy;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
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
import android.widget.CheckBox;
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
    private EditText urlField, timeField, noteField, targetPriceField, maxTotalField;
    private CheckBox autoOrderCheck;
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

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) {
            status.setText(isAccessibilityEnabled()
                    ? "พร้อม AUTO • สิทธิ์ควบคุม Shopee เปิดแล้ว"
                    : "ต้องเปิดสิทธิ์ AUTO 1 ครั้ง ก่อนใช้งาน");
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 60);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Shopee Fast Assist v3 AUTO");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(225, 62, 45));
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView explain = new TextView(this);
        explain.setText("แนวใหม่: ถึงเวลา → รีราคาหน้าสินค้า → รอจนราคาโปรตรงเงื่อนไข → กดซื้อ → ตรวจยอด Checkout → กดสั่งซื้อเฉพาะเมื่อยอดไม่เกินเพดาน");
        explain.setTextSize(14);
        explain.setTextColor(Color.DKGRAY);
        explain.setPadding(0, 0, 0, 18);
        root.addView(explain);

        status = new TextView(this);
        status.setText("ตรวจสอบสิทธิ์ AUTO...");
        status.setTextSize(16);
        status.setPadding(18, 16, 18, 16);
        status.setBackgroundColor(Color.rgb(242, 242, 242));
        root.addView(status);

        Button accessibility = button("1) เปิดสิทธิ์ AUTO (ทำครั้งเดียว)");
        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("เปิดบริการ “Shopee Fast Assist v3” แล้วกลับมาที่แอป");
        });
        root.addView(accessibility);

        Button presetShoe = button("Preset: Adidas 99 / US 11");
        presetShoe.setOnClickListener(v -> {
            urlField.setText("https://s.shopee.co.th/9fKbNzpTfL");
            timeField.setText("14:00:00");
            noteField.setText("Adidas Adizero Evo SL • US 11");
            targetPriceField.setText("99");
            maxTotalField.setText("150");
            save(false);
            status.setText("โหลด Preset รองเท้าแล้ว");
        });
        root.addView(presetShoe);

        Button presetPhone = button("Preset: Galaxy Z Flip8 / Black");
        presetPhone.setOnClickListener(v -> {
            urlField.setText("https://s.shopee.co.th/8fS4AtohXU");
            timeField.setText("00:00:00");
            noteField.setText("Galaxy Z Flip8 • Black");
            targetPriceField.setText("99");
            maxTotalField.setText("150");
            save(false);
            status.setText("โหลด Preset โทรศัพท์แล้ว");
        });
        root.addView(presetPhone);

        urlField = field(root, "ลิงก์สินค้า", "product_url", "https://s.shopee.co.th/8fS4AtohXU", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        timeField = field(root, "เวลาเริ่มโปร HH:mm:ss", "target_time", "00:00:00", InputType.TYPE_CLASS_DATETIME);
        noteField = field(root, "สินค้า / สี / ไซซ์ (ไว้เตือน)", "product_note", "Black", InputType.TYPE_CLASS_TEXT);
        targetPriceField = field(root, "ราคาโปรที่ต้องเห็นก่อนกดซื้อ (บาท)", "target_price", "99", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxTotalField = field(root, "ยอดรวมสูงสุดที่ยอมให้สั่งซื้อ (บาท)", "max_total", "150", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        autoOrderCheck = new CheckBox(this);
        autoOrderCheck.setText("AUTO สั่งซื้อ: กด “สั่งซื้อ” ให้เอง เมื่อระบบอ่านยอด Checkout ได้และยอด ≤ เพดาน");
        autoOrderCheck.setChecked(prefs.getBoolean("auto_place_order", false));
        autoOrderCheck.setTextSize(14);
        autoOrderCheck.setPadding(0, 14, 0, 4);
        root.addView(autoOrderCheck);

        Button save = button("บันทึกค่า");
        save.setOnClickListener(v -> {
            save(false);
            toast("บันทึกแล้ว");
        });
        root.addView(save);

        Button open = button("เปิดหน้าสินค้า Shopee ตอนนี้");
        open.setOnClickListener(v -> {
            save(false);
            openShopee();
        });
        root.addView(open);

        Button test = button("TEST 5 วินาที — รีราคาเท่านั้น ไม่ซื้อ");
        test.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("ต้องเปิด Shopee Fast Assist v3 ก่อนทดสอบ");
                return;
            }
            save(true);
            long target = System.currentTimeMillis() + 5000L;
            startAssist(target);
            status.setText("TEST: เปิด Shopee ไว้ด้านหน้า รอ 5 วินาที");
            openShopee();
        });
        root.addView(test);

        Button arm = button("2) ARM AUTO + เปิด Shopee");
        arm.setTextColor(Color.WHITE);
        arm.setBackgroundColor(Color.rgb(225, 62, 45));
        arm.setOnClickListener(v -> armAndOpen());
        root.addView(arm);

        Button stop = button("หยุด AUTO / ยกเลิกเวลา");
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
        instructions.setText("ก่อนเวลาโปร:\n• เปิดหน้าสินค้าและเลือกสี/ไซซ์ที่ต้องการไว้แล้ว\n• เตรียมคูปองและวิธีชำระเงินไว้ให้พร้อม\n• กด ARM AUTO แล้วค้างหน้า Shopee ไว้\n\nเมื่อถึงเวลา แอปจะสั่งรีราคาอัตโนมัติสูงสุด 3 ครั้งในช่วงสั้น ๆ โดยไม่ยิง request เอง จากนั้นจะกดซื้อเมื่อพบราคาโปรที่ตั้งไว้ หากถึง Checkout จะกดสั่งซื้อเฉพาะกรณีอ่านยอดรวมได้และยอดไม่เกินเพดาน หากอ่านยอดไม่ได้/ราคาไม่ตรง/มี OTP, CAPTCHA หรือหน้าตรวจสอบ ระบบจะหยุดทันที");
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

    private void save(boolean testOnly) {
        prefs.edit()
                .putString("product_url", urlField.getText().toString().trim())
                .putString("target_time", timeField.getText().toString().trim())
                .putString("product_note", noteField.getText().toString().trim())
                .putString("target_price", targetPriceField.getText().toString().trim())
                .putString("max_total", maxTotalField.getText().toString().trim())
                .putBoolean("auto_place_order", autoOrderCheck.isChecked())
                .putBoolean("test_only", testOnly)
                .apply();
    }

    private void armAndOpen() {
        if (!isAccessibilityEnabled()) {
            status.setText("ยังไม่ได้เปิดสิทธิ์ AUTO");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("เปิด “Shopee Fast Assist v3” 1 ครั้ง แล้วกลับมากด ARM AUTO");
            return;
        }
        try {
            save(false);
            LocalTime t = LocalTime.parse(timeField.getText().toString().trim(), DateTimeFormatter.ofPattern("HH:mm:ss"));
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime target = ZonedDateTime.of(LocalDate.now(zone), t, zone);
            if (!target.isAfter(now)) target = target.plusDays(1);
            long targetMs = target.toInstant().toEpochMilli();
            prefs.edit().putLong("target_ms", targetMs).apply();
            startAssist(targetMs);
            status.setText("ARM AUTO: " + target.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")));
            openShopee();
        } catch (Exception e) {
            toast("รูปแบบเวลาไม่ถูกต้อง ใช้ HH:mm:ss เช่น 00:00:00");
        }
    }

    private void startAssist(long targetMs) {
        Intent i = new Intent(this, AssistTimerService.class).setAction(AssistTimerService.ACTION_ARM);
        i.putExtra("target_ms", targetMs);
        i.putExtra("note", noteField.getText().toString().trim());
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

    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;
            ComponentName component = new ComponentName(this, AutoBuyAccessibilityService.class);
            String full = component.flattenToString();
            String shortName = component.flattenToShortString();
            return services.contains(full) || services.contains(shortName) || services.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void startForegroundCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
