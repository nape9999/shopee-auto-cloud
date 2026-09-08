# Shopee Auto Cloud

ระบบรัน Playwright/Chromium บน Railway โดยไม่ต้องเปิด PC

## Preset
- Galaxy Z Flip8 — 9 Sep 2026 00:00 Asia/Bangkok — สีดำ — max 150 THB
- adidas Adizero Evo SL White — 9 Sep 2026 14:00 Asia/Bangkok — EU 45 — max 150 THB

## Required Railway settings
1. Deploy from this Dockerfile
2. Create a persistent Volume and mount it to `/data`
3. Set environment:
   - `DATA_DIR=/data`
   - `CONTROL_PIN=<your private PIN>`
4. Keep one replica running

## Login
Open the web control panel from mobile, press "เปิด Login", and interact with the remote headless browser.
Shopee session is stored in `/data/shopee-profile`.

The app does not intentionally save passwords or OTP to files/logs.
However, typed login data necessarily passes through your Railway service in RAM so it can be sent to the Shopee page.

## TEST mode
TEST goes through product selection and checkout, checks:
- login
- captcha/security verification
- requested color/size
- voucher handling attempt
- checkout total <= maxTotalThb
- final Place Order button exists

TEST does NOT click the final order button.

This is the strongest safe pre-flight test. A true proof that Shopee will accept the order can only happen by placing a real order.

## Important
- Do not bypass CAPTCHA/security verification.
- Shopee UI, stock, voucher availability, session rules, or anti-bot controls may change at any time.
- Use a strict price guard to prevent accidental full-price purchases.
