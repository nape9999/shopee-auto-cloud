import fs from "fs";
import path from "path";
import { chromium } from "playwright";

const DATA_DIR = process.env.DATA_DIR || "./data";
const PROFILE_DIR = path.join(DATA_DIR, "shopee-profile");
const JOBS_FILE = path.join(DATA_DIR, "jobs.json");
const LOGS_FILE = path.join(DATA_DIR, "logs.json");

fs.mkdirSync(DATA_DIR, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, "utf8")); }
  catch { return fallback; }
}

function writeJson(file, data) {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), "utf8");
  fs.renameSync(tmp, file);
}

export function logEvent(kind, message, jobId = null, extra = {}) {
  const logs = readJson(LOGS_FILE, []);
  logs.unshift({
    id: crypto.randomUUID(),
    at: new Date().toISOString(),
    kind, message, jobId, ...extra
  });
  writeJson(LOGS_FILE, logs.slice(0, 500));
}

export function getLogs(limit = 100) {
  return readJson(LOGS_FILE, []).slice(0, limit);
}

export function getJobs() {
  return readJson(JOBS_FILE, []);
}

export function saveJobs(jobs) {
  writeJson(JOBS_FILE, jobs);
}

function parseBaht(text) {
  if (!text) return null;
  const cleaned = String(text).replace(/,/g, "");
  const nums = cleaned.match(/\d+(?:\.\d+)?/g);
  if (!nums?.length) return null;
  return Number(nums[nums.length - 1]);
}

function cleanTrackingParams(urlText) {
  try {
    const u = new URL(urlText);
    u.hash = "";
    if (/(^|\.)shopee\.co\.th$/i.test(u.hostname)) {
      u.search = "";
      return u.toString();
    }
    for (const [k] of [...u.searchParams]) {
      if (/^utm_|^af_|^aff|^affiliate|^share|^smtt$|^uls_trackid$|^sp_atk$|^deep_and_deferred$/i.test(k)) {
        u.searchParams.delete(k);
      }
    }
    return u.toString();
  } catch {
    return urlText;
  }
}

async function bodyText(page) {
  return (await page.locator("body").innerText({ timeout: 1200 }).catch(() => "")) || "";
}

async function firstVisible(locator, max = 30) {
  try {
    const n = Math.min(await locator.count(), max);
    for (let i=0;i<n;i++) {
      const el = locator.nth(i);
      if (await el.isVisible({ timeout: 80 }).catch(() => false)) return el;
    }
  } catch {}
  return null;
}

async function clickText(page, texts) {
  for (const text of texts) {
    const escaped = String(text).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const rx = new RegExp(`^\\s*${escaped}\\s*$`, "i");

    const a = await firstVisible(page.getByRole("button", { name: rx }));
    if (a && await a.isEnabled().catch(() => true)) {
      await a.click({ force: true, timeout: 1000 }).catch(() => {});
      return true;
    }

    const b = await firstVisible(page.getByText(rx));
    if (b && await b.isEnabled().catch(() => true)) {
      await b.click({ force: true, timeout: 1000 }).catch(() => {});
      return true;
    }
  }
  return false;
}

async function hasCaptcha(page) {
  return /captcha|verify you are human|ยืนยันว่าคุณไม่ใช่หุ่นยนต์|security verification|การยืนยันความปลอดภัย/i.test(await bodyText(page));
}

async function needsLogin(page) {
  const t = await bodyText(page);
  return /\/buyer\/login/i.test(page.url()) || /เข้าสู่ระบบ|ลงชื่อเข้าใช้/i.test(t);
}

async function resolveCanonical(context, rawUrl) {
  const p = await context.newPage();
  try {
    await p.goto(rawUrl, { waitUntil:"domcontentloaded", timeout:30000 }).catch(()=>{});
    await sleep(1200);
    let u = await p.locator('link[rel="canonical"]').getAttribute("href").catch(()=>null);
    if (!u) u = await p.locator('meta[property="og:url"]').getAttribute("content").catch(()=>null);
    if (!u) u = p.url();
    return cleanTrackingParams(u || rawUrl);
  } finally {
    await p.close().catch(()=>{});
  }
}

async function chooseVariations(page, groups = []) {
  for (const group of groups) {
    let done = false;
    for (const target of group) {
      const escaped = String(target).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const loc = page.getByText(new RegExp(escaped, "i"));
      const n = Math.min(await loc.count().catch(()=>0), 30);
      for (let i=0;i<n;i++) {
        const el = loc.nth(i);
        if (!await el.isVisible().catch(()=>false)) continue;
        if (!await el.isEnabled().catch(()=>true)) continue;
        const txt = ((await el.innerText().catch(()=> "")) || "").trim();
        if (/ซื้อเลย|buy now|เพิ่มไปยังรถเข็น|add to cart|สั่งซื้อ|place order/i.test(txt)) continue;
        await el.click({force:true, timeout:700}).catch(()=>{});
        done = true;
        break;
      }
      if (done) break;
    }
    if (!done) return { ok:false, missing:group };
  }
  return { ok:true };
}

async function claimVoucher(page) {
  for (let i=0;i<4;i++) {
    const c = await clickText(page, ["เก็บโค้ด","เก็บคูปอง","รับโค้ด","Claim","Collect"]);
    if (!c) break;
    await sleep(120);
  }
  await clickText(page, ["โค้ดส่วนลด","คูปอง","Shopee Voucher","Voucher"]).catch(()=>{});
  await sleep(120);
  await clickText(page, ["ใช้","ใช้โค้ด","Apply","ตกลง","ยืนยัน"]).catch(()=>{});
}

async function extractCheckoutTotal(page) {
  const lines = (await bodyText(page)).split("\n").map(s=>s.trim()).filter(Boolean);
  const anchors = [/ยอดชำระทั้งหมด/i,/ยอดรวมทั้งหมด/i,/ยอดรวมการสั่งซื้อ/i,/total payment/i,/order total/i];
  for (let i=0;i<lines.length;i++) {
    if (!anchors.some(rx=>rx.test(lines[i]))) continue;
    for (let j=i;j<Math.min(i+6,lines.length);j++) {
      const n = parseBaht(lines[j]);
      if (n !== null) return n;
    }
  }
  return null;
}

let contextPromise = null;

export async function getContext() {
  if (!contextPromise) {
    contextPromise = chromium.launchPersistentContext(PROFILE_DIR, {
      headless: true,
      locale: "th-TH",
      timezoneId: "Asia/Bangkok",
      viewport: { width: 430, height: 900 },
      userAgent: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }).catch(err => {
      contextPromise = null;
      throw err;
    });
  }
  return contextPromise;
}

export async function openLoginPage() {
  const context = await getContext();
  let page = context.pages()[0];
  if (!page) page = await context.newPage();
  await page.goto("https://shopee.co.th/buyer/login", { waitUntil:"domcontentloaded", timeout:30000 }).catch(()=>{});
  return page;
}

export async function getSessionStatus() {
  const context = await getContext();
  const p = await context.newPage();
  try {
    await p.goto("https://shopee.co.th/", { waitUntil:"domcontentloaded", timeout:20000 }).catch(()=>{});
    if (await hasCaptcha(p)) return { loggedIn:false, captcha:true };
    return { loggedIn: !(await needsLogin(p)), captcha:false };
  } finally {
    await p.close().catch(()=>{});
  }
}

export async function runJob(job, dryRun = false) {
  const context = await getContext();
  const page = await context.newPage();
  const result = {
    ok:false, dryRun, jobId:job.id, name:job.name,
    steps:[], total:null, finalButtonFound:false
  };

  const step = (name, ok, detail="") => {
    result.steps.push({ name, ok, detail });
    logEvent(ok ? "pass" : "warn", `${name}${detail ? `: ${detail}` : ""}`, job.id);
  };

  try {
    const cleanUrl = await resolveCanonical(context, job.productUrl);
    step("ล้างลิงก์", true, cleanUrl);

    await page.goto(cleanUrl, { waitUntil:"domcontentloaded", timeout:30000 }).catch(()=>{});
    await sleep(1200);

    if (await hasCaptcha(page)) {
      step("ตรวจ CAPTCHA", false, "พบ CAPTCHA / Security verification");
      result.reason = "captcha";
      return result;
    }
    step("ตรวจ CAPTCHA", true, "ไม่พบ");

    if (await needsLogin(page)) {
      step("ตรวจ Login", false, "Session ยังไม่ได้ Login");
      result.reason = "login_required";
      return result;
    }
    step("ตรวจ Login", true, "พร้อม");

    const variants = await chooseVariations(page, job.variationGroups || []);
    if (!variants.ok) {
      step("เลือกตัวเลือก", false, `ไม่พบ ${variants.missing.join(" / ")}`);
      result.reason = "variant_missing";
      return result;
    }
    step("เลือกตัวเลือก", true);

    if (job.useVoucher) {
      await claimVoucher(page);
      step("คูปอง", true, "พยายามเก็บ/ใช้คูปองแล้ว");
    }

    const buy = await clickText(page, ["ซื้อเลย","Buy Now"]);
    if (!buy) {
      step("ปุ่มซื้อเลย", false, "ยังไม่พบ");
      result.reason = "buy_button_missing";
      return result;
    }
    step("ปุ่มซื้อเลย", true, "กดแล้ว");

    await sleep(500);
    await chooseVariations(page, job.variationGroups || []);
    if (job.useVoucher) await claimVoucher(page);
    await clickText(page, ["ยืนยัน","ตกลง","Confirm"]).catch(()=>{});
    await sleep(700);

    const total = await extractCheckoutTotal(page);
    result.total = total;

    if (total === null) {
      step("ตรวจยอดรวม", false, "อ่านยอดไม่ได้ จึงหยุด");
      result.reason = "total_unknown";
      return result;
    }

    if (total > Number(job.maxTotalThb)) {
      step("ตรวจยอดรวม", false, `${total} บาท > เพดาน ${job.maxTotalThb}`);
      result.reason = "price_guard";
      return result;
    }
    step("ตรวจยอดรวม", true, `${total} บาท`);

    const finalFound = /สั่งซื้อ|ทำการสั่งซื้อ|Place Order/i.test(await bodyText(page));
    result.finalButtonFound = finalFound;

    if (dryRun) {
      step("ปุ่มสั่งซื้อสุดท้าย", finalFound, finalFound ? "พบแล้ว แต่ TEST ไม่กด" : "ไม่พบ");
      result.ok = finalFound;
      result.reason = finalFound ? "dry_run_ready" : "final_button_missing";
      return result;
    }

    if (!finalFound) {
      step("ปุ่มสั่งซื้อสุดท้าย", false, "ไม่พบ");
      result.reason = "final_button_missing";
      return result;
    }

    const placed = await clickText(page, ["สั่งซื้อ","ทำการสั่งซื้อ","Place Order"]);
    step("ส่งคำสั่งซื้อ", placed, placed ? "กดแล้ว" : "กดไม่สำเร็จ");
    result.ok = placed;
    result.reason = placed ? "order_submitted" : "submit_failed";
    return result;
  } finally {
    if (!dryRun) await page.close().catch(()=>{});
    else {
      // dry-run screenshot for audit
      try {
        const p = path.join(DATA_DIR, `dryrun-${job.id}.png`);
        await page.screenshot({ path:p, fullPage:false });
        result.screenshot = `/api/jobs/${job.id}/dryrun-screenshot`;
      } catch {}
      await page.close().catch(()=>{});
    }
  }
}

const active = new Set();

export function startScheduler() {
  setInterval(async () => {
    const jobs = getJobs();
    const now = Date.now();

    for (const job of jobs) {
      if (!job.enabled || active.has(job.id)) continue;
      const start = Date.parse(job.startAt);
      if (!Number.isFinite(start)) continue;

      const openLead = Number(job.openSecondsBefore ?? 20) * 1000;
      const end = start + Number(job.activeWindowSeconds ?? 120) * 1000;

      if (now >= start - openLead && now <= end && job.lastRunFor !== job.startAt) {
        active.add(job.id);
        try {
          logEvent("info", "เริ่มงานอัตโนมัติ", job.id);
          const wait = Math.max(0, start - Date.now());
          if (wait > 0) await sleep(wait);
          const result = await runJob(job, false);

          const latest = getJobs();
          const idx = latest.findIndex(x=>x.id===job.id);
          if (idx >= 0) {
            latest[idx].lastRunFor = job.startAt;
            latest[idx].lastResult = result;
            saveJobs(latest);
          }
          logEvent(result.ok ? "success" : "error",
            result.ok ? "งานสำเร็จ" : `งานไม่สำเร็จ: ${result.reason}`,
            job.id, { result });
        } catch (e) {
          logEvent("error", `Scheduler error: ${e.message}`, job.id);
        } finally {
          active.delete(job.id);
        }
      }
    }
  }, 500);
}
