// qjsrht Default Server with authentication and remote command execution
// ADDRESS and PORT are injected automatically from config.json

import express from './express.js';
import * as std from 'std';  // Importar std para popen

const app = express();

const AUTH_TOKEN = "__AUTH_TOKEN__";

// Logging middleware
app.use((req, res, next) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${req.path}`);
    next();
});

// Endpoint público: información del servidor
app.get('/', (req, res) => {
    console.log("DEBUG: GET /");
    res.json({
        message: 'Welcome to qjsrht!',
        timestamp: new Date().toISOString(),
        server: 'QuickJS Express Server'
    });
});

// Endpoint público: health check
app.get('/health', (req, res) => {
    console.log("DEBUG: GET /health");
    res.json({ status: 'ok', uptime: process.uptime || 0 });
});

// Endpoint público: echo
app.post('/echo', (req, res) => {
    console.log("DEBUG: POST /echo");
    res.json({
        received: req.body,
        headers: req.headers
    });
});

// Endpoint de ejemplo con parámetros
app.get('/api/users/:id', (req, res) => {
    console.log("DEBUG: GET /api/users/:id, id=" + req.params.id);
    res.json({
        userId: req.params.id,
        name: 'Sample User',
        email: 'user@example.com'
    });
});

// Endpoint protegido: ejecutar comandos del sistema
app.post('/exec', (req, res) => {
    console.log("DEBUG 1: Entrando a /exec");
    console.log("DEBUG: req.body =", req.body);
    console.log("DEBUG: req.headers.authorization =", req.headers.authorization);
    console.log("DEBUG: tipo de res.json =", typeof res.json);
    console.log("DEBUG: tipo de res.send =", typeof res.send);
    console.log("DEBUG: tipo de res.status =", typeof res.status);

    // Autenticación manual
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        console.log("DEBUG 1.1 - No autorizado");
        res.send(JSON.stringify({ error: 'Unauthorized' }));
        return;
    }
    console.log("DEBUG 1.2 - Autorizado");

    // Parsear el cuerpo JSON
    let bodyObj;
    try {
        bodyObj = JSON.parse(req.body);
        console.log("DEBUG: bodyObj =", bodyObj);
    } catch (e) {
        console.log("DEBUG: Error parsing JSON:", e.message);
        res.send(JSON.stringify({ error: 'Invalid JSON' }));
        return;
    }

    const { cmd } = bodyObj;
    if (!cmd || typeof cmd !== 'string') {
        console.log("DEBUG: cmd missing or not string");
        res.send(JSON.stringify({ error: 'Missing or invalid cmd' }));
        return;
    }

    console.log("DEBUG: cmd =", cmd);

    // Ejecutar comando usando std.popen
    try {
        const fullCmd = cmd + ' 2>&1';
        console.log("DEBUG: Ejecutando con std.popen:", fullCmd);
        const fp = std.popen(fullCmd, 'r');
        let output = '';
        let line;
        while ((line = fp.getline()) !== null) {
            output += line + '\n';
        }
        const status = fp.close();
        console.log("DEBUG: Comando terminado con status", status);
        console.log("DEBUG: output length =", output.length);

        res.send(JSON.stringify({ output, status }));
    } catch (e) {
        console.log("DEBUG: Excepción en ejecución:", e.message);
        res.send(JSON.stringify({ error: e.message }));
    }
});

// Manejador 404 (comentado temporalmente para pruebas)
/*
app.use((req, res) => {
    console.log("DEBUG: 404 - No encontrado:", req.path);
    res.status(404).json({
        error: 'Not Found',
        path: req.path
    });
});
*/

// Inicio del servidor (usando valores fijos para debug)
//const ADDRESS = "127.0.0.1";
//const PORT = 10000;
console.log(`Starting server on ${ADDRESS}:${PORT}`);
app.listen(PORT, ADDRESS, () => {
    console.log(`Server running at ${ADDRESS}:${PORT}`);
});
