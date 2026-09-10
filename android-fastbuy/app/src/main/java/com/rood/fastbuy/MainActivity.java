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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText(isAccessibilityEnabled()
                ? "พร้อม AUTO • โหมดราคา ≤ เปิดใช้งาน"
                : "ต้องเปิดสิทธิ์ AUTO 1 ครั้งก่อนใช้งาน");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 60);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("F");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(225, 62, 45));
        title.setPadding(0, 0, 0, 2);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("v3.1 AUTO • ซื้อเมื่อราคา ≤ ค่าที่ตั้ง");
        version.setTextSize(14);
        version.setTextColor(Color.DKGRAY);
        version.setPadding(0, 0, 0, 12);
        root.addView(version);

        TextView explain = new TextView(this);
        explain.setText("ถึงเวลา → รีราคาหน้าสินค้า → อ่านราคาบนปุ่มซื้อ → ถ้าราคา ≤ ที่ตั้งไว้จึงกดซื้อ → ตรวจยอด Checkout → กดสั่งซื้อเฉพาะเมื่อยอดรวม ≤ เพดาน");
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

        Button accessibility = button("1) เปิดสิทธิ์ F AUTO (ทำครั้งเดียว)");
        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("เปิดบริการ “F AUTO” แล้วกลับมาที่แอป");
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
            status.setText("โหลด Preset รองเท้าแล้ว • ซื้อเมื่อราคา ≤ 99 บาท");
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
            status.setText("โหลด Preset โทรศัพท์แล้ว • ซื้อเมื่อราคา ≤ 99 บาท");
        });
        root.addView(presetPhone);

        urlField = field(root,
                "ลิงก์สินค้า",
                "product_url",
                "https://s.shopee.co.th/8fS4AtohXU",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        timeField = field(root,
                "เวลาเริ่มโปร HH:mm:ss",
                "target_time",
                "00:00:00",
                InputType.TYPE_CLASS_DATETIME);

        noteField = field(root,
                "สินค้า / สี / ไซซ์ (ไว้เตือน)",
                "product_note",
                "Black",
                InputType.TYPE_CLASS_TEXT);

        targetPriceField = field(root,
                "ซื้อเมื่อราคาบนปุ่ม ≤ (บาท)",
                "target_price",
                "99",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        maxTotalField = field(root,
                "ยอดรวม Checkout สูงสุด ≤ (บาท)",
                "max_total",
                "150",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        autoOrderCheck = new CheckBox(this);
        autoOrderCheck.setText("AUTO สั่งซื้อ: กดปุ่มสั่งซื้อให้เอง เมื่ออ่านยอด Checkout ได้และยอด ≤ เพดาน");
        autoOrderCheck.setChecked(prefs.getBoolean("auto_place_order", false));
        autoOrderCheck.setTextSize(14);
        autoOrderCheck.setPadding(0, 14, 0, 4);
        root.addView(autoOrderCheck);

        Button save = button("บันทึกค่า");
        save.setOnClickListener(v -> {
            if (save(false)) toast("บันทึกแล้ว");
        });
        root.addView(save);

        Button open = button("เปิดหน้าสินค้าตอนนี้");
        open.setOnClickListener(v -> {
            if (save(false)) openProduct();
        });
        root.addView(open);

        Button test = button("TEST 5 วินาที — รีราคาเท่านั้น ไม่ซื้อ");
        test.setOnClickListener(v -> {
            if (!isAccessibilityEnabled()) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("ต้องเปิด F AUTO ก่อนทดสอบ");
                return;
            }
            if (!save(true)) return;
            long target = System.currentTimeMillis() + 5000L;
            startAssist(target);
            status.setText("TEST: เปิดหน้าสินค้าไว้ด้านหน้า รอ 5 วินาที");
            openProduct();
        });
        root.addView(test);

        Button arm = button("2) ARM AUTO + เปิดหน้าสินค้า");
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
        instructions.setText(
                "วิธีใช้:\n"
                        + "• ตั้ง ‘ซื้อเมื่อราคาบนปุ่ม ≤’ เช่น 120 บาท ถ้าราคาขึ้น 99 / 109 / 120 ระบบถือว่าผ่านทั้งหมด\n"
                        + "• ตั้ง ‘ยอดรวม Checkout สูงสุด’ แยกอีกชั้น เช่น 150 บาท\n"
                        + "• ก่อนเวลา เลือกสี/ไซซ์ คูปอง และวิธีชำระเงินให้พร้อม\n"
                        + "• กด ARM AUTO แล้วค้างหน้าสินค้าไว้\n"
                        + "• เมื่อถึงเวลา ระบบจะรีราคาอัตโนมัติสูงสุด 3 ครั้งในช่วงสั้น ๆ\n"
                        + "• ระบบอิงราคาที่อ่านได้จากปุ่มซื้อ ถ้าอ่านราคาไม่ได้จะไม่ซื้อ เพื่อป้องกันกดผิดราคา\n"
                        + "• ถ้าเจอ OTP, CAPTCHA หรือหน้าตรวจสอบ จะหยุดทันที");
        instructions.setTextSize(14);
        instructions.setTextColor(Color.DKGRAY);
        instructions.setPadding(0, 20, 0, 0);
        root.addView(instructions);

        setContentView(scroll);
        refreshStatus();
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

    private boolean save(boolean testOnly) {
        String targetRaw = targetPriceField.getText().toString().trim();
        String totalRaw = maxTotalField.getText().toString().trim();
        double targetPrice = parsePositiveNumber(targetRaw);
        double maxTotal = parsePositiveNumber(totalRaw);

        if (targetPrice <= 0) {
            toast("กรุณาใส่ราคาเงื่อนไขมากกว่า 0 บาท");
            return false;
        }
        if (maxTotal <= 0) {
            toast("กรุณาใส่ยอดรวมสูงสุดมากกว่า 0 บาท");
            return false;
        }

        prefs.edit()
                .putString("product_url", urlField.getText().toString().trim())
                .putString("target_time", timeField.getText().toString().trim())
                .putString("product_note", noteField.getText().toString().trim())
                .putString("target_price", targetRaw)
                .putString("max_total", totalRaw)
                .putBoolean("auto_place_order", autoOrderCheck.isChecked())
                .putBoolean("test_only", testOnly)
                .apply();
        return true;
    }

    private double parsePositiveNumber(String raw) {
        try {
            double v = Double.parseDouble(raw.replace(",", ""));
            return Double.isFinite(v) ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void armAndOpen() {
        if (!isAccessibilityEnabled()) {
            status.setText("ยังไม่ได้เปิดสิทธิ์ AUTO");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("เปิด “F AUTO” 1 ครั้ง แล้วกลับมากด ARM AUTO");
            return;
        }

        try {
            if (!save(false)) return;
            LocalTime t = LocalTime.parse(
                    timeField.getText().toString().trim(),
                    DateTimeFormatter.ofPattern("HH:mm:ss"));

            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime target = ZonedDateTime.of(LocalDate.now(zone), t, zone);
            if (!target.isAfter(now)) target = target.plusDays(1);

            long targetMs = target.toInstant().toEpochMilli();
            prefs.edit().putLong("target_ms", targetMs).apply();
            startAssist(targetMs);
            status.setText("ARM AUTO: " + target.format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))
                    + " • ราคา ≤ " + targetPriceField.getText().toString().trim());
            openProduct();
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

    private void openProduct() {
        String u = urlField.getText().toString().trim();
        if (u.isEmpty()) {
            toast("กรุณาใส่ลิงก์สินค้า");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            i.setPackage("com.shopee.th");
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
            } catch (Exception ignored) {
                toast("เปิดลิงก์สินค้าไม่สำเร็จ");
            }
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0);
            if (enabled != 1) return false;

            String services = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;

            ComponentName component = new ComponentName(this, AutoBuyAccessibilityService.class);
            String full = component.flattenToString();
            String shortName = component.flattenToShortString();
            return services.contains(full)
                    || services.contains(shortName)
                    || services.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void startForegroundCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
