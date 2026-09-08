import express from "express";
import fs from "fs";
import path from "path";
import { WebSocketServer } from "ws";
import {
  getJobs, saveJobs, getLogs, logEvent,
  runJob, startScheduler, openLoginPage, getSessionStatus, getContext
} from "./automation.mjs";

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || "./data";
const CONTROL_PIN = process.env.CONTROL_PIN || "";

const app = express();
app.use(express.json({ limit:"1mb" }));

function auth(req,res,next) {
  if (!CONTROL_PIN) return next();
  const pin = req.get("x-control-pin") || req.query.pin || "";
  if (pin !== CONTROL_PIN) return res.status(401).json({ error:"PIN_REQUIRED" });
  next();
}

app.use("/api", auth);
app.use(express.static("public"));

app.get("/health", (_,res)=>res.json({ok:true, at:new Date().toISOString()}));

app.get("/api/config", async (_,res)=>{
  res.json({
    pinRequired: !!CONTROL_PIN,
    session: await getSessionStatus().catch(e=>({loggedIn:false,error:e.message}))
  });
});

app.get("/api/jobs", (_,res)=>res.json(getJobs()));

app.post("/api/jobs", (req,res)=>{
  const jobs = getJobs();
  const j = {
    id: crypto.randomUUID(),
    name: String(req.body.name || "สินค้า"),
    productUrl: String(req.body.productUrl || ""),
    startAt: String(req.body.startAt || ""),
    maxTotalThb: Number(req.body.maxTotalThb || 150),
    quantity: Number(req.body.quantity || 1),
    variationGroups: Array.isArray(req.body.variationGroups) ? req.body.variationGroups : [],
    useVoucher: req.body.useVoucher !== false,
    enabled: req.body.enabled !== false,
    openSecondsBefore: Number(req.body.openSecondsBefore ?? 20),
    activeWindowSeconds: Number(req.body.activeWindowSeconds ?? 120)
  };
  jobs.push(j);
  saveJobs(jobs);
  logEvent("info","เพิ่มงานใหม่",j.id);
  res.json(j);
});

app.put("/api/jobs/:id", (req,res)=>{
  const jobs = getJobs();
  const i = jobs.findIndex(x=>x.id===req.params.id);
  if (i<0) return res.status(404).json({error:"NOT_FOUND"});
  jobs[i] = { ...jobs[i], ...req.body, id:jobs[i].id };
  saveJobs(jobs);
  res.json(jobs[i]);
});

app.delete("/api/jobs/:id", (req,res)=>{
  const jobs = getJobs().filter(x=>x.id!==req.params.id);
  saveJobs(jobs);
  res.json({ok:true});
});

app.post("/api/jobs/:id/test", async (req,res)=>{
  const job = getJobs().find(x=>x.id===req.params.id);
  if (!job) return res.status(404).json({error:"NOT_FOUND"});
  try {
    const result = await runJob(job, true);
    res.json(result);
  } catch(e) {
    logEvent("error",`TEST error: ${e.message}`,job.id);
    res.status(500).json({error:e.message});
  }
});

app.post("/api/jobs/:id/run", async (req,res)=>{
  const job = getJobs().find(x=>x.id===req.params.id);
  if (!job) return res.status(404).json({error:"NOT_FOUND"});
  try {
    const result = await runJob(job, false);
    res.json(result);
  } catch(e) {
    logEvent("error",`RUN error: ${e.message}`,job.id);
    res.status(500).json({error:e.message});
  }
});

app.get("/api/jobs/:id/dryrun-screenshot", (req,res)=>{
  const p = path.join(DATA_DIR, `dryrun-${req.params.id}.png`);
  if (!fs.existsSync(p)) return res.status(404).end();
  res.sendFile(path.resolve(p));
});

app.get("/api/logs", (req,res)=>res.json(getLogs(Number(req.query.limit||100))));

app.post("/api/session/open-login", async (_,res)=>{
  await openLoginPage();
  res.json({ok:true});
});

app.get("/api/session/status", async (_,res)=>{
  res.json(await getSessionStatus());
});

app.post("/api/presets", (_,res)=>{
  const jobs = [
    {
      id: crypto.randomUUID(),
      name:"Samsung Galaxy Z Flip8 — สีดำ",
      productUrl:"https://s.shopee.co.th/8fS4AtohXU",
      startAt:"2026-09-09T00:00:00+07:00",
      maxTotalThb:150,
      quantity:1,
      variationGroups:[["สีดำ","ดำ","Black"]],
      useVoucher:true, enabled:true, openSecondsBefore:20, activeWindowSeconds:120
    },
    {
      id: crypto.randomUUID(),
      name:"adidas Adizero Evo SL White — Size 45",
      productUrl:"https://s.shopee.co.th/9fKbNzpTfL",
      startAt:"2026-09-09T14:00:00+07:00",
      maxTotalThb:150,
      quantity:1,
      variationGroups:[["45","EU 45","45 EU"]],
      useVoucher:true, enabled:true, openSecondsBefore:20, activeWindowSeconds:120
    }
  ];
  saveJobs(jobs);
  res.json(jobs);
});

const server = app.listen(PORT, ()=>{
  console.log(`Shopee Auto Cloud listening on :${PORT}`);
  startScheduler();
});

// Remote browser control websocket.
// Password/OTP are not persisted or logged; they only pass through RAM to the focused page.
const wss = new WebSocketServer({ noServer:true });

server.on("upgrade", (req, socket, head)=>{
  const u = new URL(req.url, `http://${req.headers.host}`);
  if (u.pathname !== "/ws/browser") return socket.destroy();
  if (CONTROL_PIN && u.searchParams.get("pin") !== CONTROL_PIN) return socket.destroy();

  wss.handleUpgrade(req,socket,head,ws=>wss.emit("connection",ws,req));
});

wss.on("connection", async ws=>{
  let page;
  let timer;
  try {
    page = await openLoginPage();

    const sendShot = async ()=>{
      if (ws.readyState !== 1) return;
      try {
        const img = await page.screenshot({ type:"jpeg", quality:60 });
        ws.send(JSON.stringify({
          type:"frame",
          data:img.toString("base64"),
          url:page.url()
        }));
      } catch {}
    };

    timer = setInterval(sendShot, 700);
    await sendShot();

    ws.on("message", async raw=>{
      try {
        const m = JSON.parse(String(raw));
        if (m.type === "click") {
          await page.mouse.click(Number(m.x), Number(m.y));
        } else if (m.type === "type") {
          await page.keyboard.type(String(m.text || ""), { delay:20 });
        } else if (m.type === "key") {
          await page.keyboard.press(String(m.key || "Enter"));
        } else if (m.type === "goto") {
          await page.goto(String(m.url), { waitUntil:"domcontentloaded", timeout:30000 }).catch(()=>{});
        } else if (m.type === "reload") {
          await page.reload({ waitUntil:"domcontentloaded", timeout:30000 }).catch(()=>{});
        }
        await sendShot();
      } catch {}
    });

    ws.on("close", ()=> clearInterval(timer));
  } catch(e) {
    try { ws.send(JSON.stringify({type:"error",message:e.message})); } catch {}
    if (timer) clearInterval(timer);
  }
});
