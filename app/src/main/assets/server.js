// qjsrht Default Server with authentication and remote command execution
// ADDRESS and PORT are injected automatically from config.json

import express from './express.js';
import * as os from 'os';  // Módulo nativo para ejecutar comandos

const app = express();

// Token de autenticación (¡cámbialo por uno seguro!)
const AUTH_TOKEN = "secreto123";

// Middleware de autenticación
function authenticate(req, res, next) {
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        res.status(401).json({ error: 'Unauthorized' });
        return;
    }
    next();
}

// Logging middleware (imprime en consola cada petición)
app.use((req, res, next) => {
    console.log(`[${new Date().toISOString()}] ${req.method} ${req.path}`);
    next();
});

// Endpoint público: información del servidor
app.get('/', (req, res) => {
    res.json({
        message: 'Welcome to qjsrht!',
        timestamp: new Date().toISOString(),
        server: 'QuickJS Express Server'
    });
});

// Endpoint público: health check
app.get('/health', (req, res) => {
    res.json({ status: 'ok', uptime: process.uptime || 0 });
});

// Endpoint público: echo (para probar POST)
app.post('/echo', (req, res) => {
    res.json({
        received: req.body,
        headers: req.headers
    });
});

// Endpoint público de ejemplo (parámetros en ruta)
app.get('/api/users/:id', (req, res) => {
    res.json({
        userId: req.params.id,
        name: 'Sample User',
        email: 'user@example.com'
    });
});

// Endpoint protegido: ejecutar comandos del sistema
app.post('/exec', authenticate, (req, res) => {
    const { cmd } = req.body;
    if (!cmd || typeof cmd !== 'string') {
        res.status(400).json({ error: 'Missing or invalid cmd' });
        return;
    }

    try {
        // Redirigir stderr a stdout para capturar todo
        const fullCmd = cmd + ' 2>&1';
        const fp = os.popen(fullCmd, 'r');  // Abre un pipe para leer la salida
        let output = '';
        while (true) {
            const line = fp.getline();  // Lee línea por línea
            if (line === null) break;
            output += line + '\n';
        }
        const status = fp.close();  // Código de salida del comando

        res.json({ output, status });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

// Manejador para rutas no encontradas
app.use((req, res) => {
    res.status(404).json({
        error: 'Not Found',
        path: req.path
    });
});

// Inicio del servidor (ADDRESS y PORT vienen del runtime)
console.log(`Starting server on ${ADDRESS}:${PORT}`);
app.listen(PORT, ADDRESS, () => {
    console.log(`Server running at ${ADDRESS}:${PORT}`);
});
