// qjsrht Default Server with authentication and remote command execution
import express from './express.js';
import * as std from 'std';

const app = express();

const AUTH_TOKEN = "__AUTH_TOKEN__";

// Logging middleware usando std.out.puts (garantiza escritura en stdout)
app.use((req, res, next) => {
    std.out.puts(`[${new Date().toISOString()}] ${req.method} ${req.path}\n`);
    std.out.flush();
    next();
});

// Endpoint público: información del servidor
app.get('/', (req, res) => {
    std.out.puts("DEBUG: GET /\n");
    std.out.flush();
    res.json({
        message: 'Welcome to qjsrht!',
        timestamp: new Date().toISOString(),
        server: 'QuickJS Express Server'
    });
});

// Endpoint público: health check
app.get('/health', (req, res) => {
    std.out.puts("DEBUG: GET /health\n");
    std.out.flush();
    res.json({ status: 'ok', uptime: process.uptime || 0 });
});

// Endpoint público: echo
app.post('/echo', (req, res) => {
    std.out.puts("DEBUG: POST /echo\n");
    std.out.flush();
    res.json({
        received: req.body,
        headers: req.headers
    });
});

// Endpoint de ejemplo con parámetros
app.get('/api/users/:id', (req, res) => {
    std.out.puts(`DEBUG: GET /api/users/${req.params.id}\n`);
    std.out.flush();
    res.json({
        userId: req.params.id,
        name: 'Sample User',
        email: 'user@example.com'
    });
});

// Endpoint protegido: ejecutar comandos del sistema
app.post('/exec', (req, res) => {
    std.out.puts("========== DEBUG /exec ==========\n");
    std.out.puts(`req.body (raw): ${req.body}\n`);
    std.out.puts(`typeof req.body: ${typeof req.body}\n`);
    std.out.puts(`req.headers.authorization: ${req.headers.authorization}\n`);
    std.out.puts(`req.headers.content-type: ${req.headers['content-type']}\n`);
    std.out.flush();

    // Autenticación
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        std.out.puts("ERROR: No autorizado\n");
        std.out.flush();
        res.send(JSON.stringify({ error: 'Unauthorized' }));
        return;
    }
    std.out.puts("Autorizado correctamente\n");
    std.out.flush();

    // Parsear el cuerpo (puede ser string u objeto)
    let bodyObj;
    try {
        if (typeof req.body === 'string') {
            bodyObj = JSON.parse(req.body);
        } else if (typeof req.body === 'object' && req.body !== null) {
            bodyObj = req.body; // ya es objeto
        } else {
            throw new Error('req.body no es ni string ni objeto');
        }
        std.out.puts(`body parseado: ${JSON.stringify(bodyObj)}\n`);
        std.out.flush();
    } catch (e) {
        std.out.puts(`ERROR parseando JSON: ${e.message}\n`);
        std.out.flush();
        res.send(JSON.stringify({ error: 'Invalid JSON', details: e.message }));
        return;
    }

    const { cmd } = bodyObj;
    if (!cmd || typeof cmd !== 'string') {
        std.out.puts("ERROR: cmd missing or invalid\n");
        std.out.flush();
        res.send(JSON.stringify({ error: 'Missing or invalid cmd' }));
        return;
    }
    std.out.puts(`Comando a ejecutar: ${cmd}\n`);
    std.out.flush();

    // Ejecutar comando con std.popen
    try {
        const fullCmd = cmd + ' 2>&1';
        std.out.puts(`Ejecutando: ${fullCmd}\n`);
        std.out.flush();
        const fp = std.popen(fullCmd, 'r');
        let output = '';
        let line;
        while ((line = fp.getline()) !== null) {
            output += line + '\n';
        }
        const status = fp.close();
        std.out.puts(`Comando ejecutado, status: ${status}\n`);
        std.out.puts(`Salida (primeros 100 chars): ${output.substring(0, 100)}\n`);
        std.out.flush();
        res.send(JSON.stringify({ output, status }));
    } catch (e) {
        std.out.puts(`ERROR en ejecución: ${e.message}\n`);
        std.out.flush();
        res.send(JSON.stringify({ error: e.message }));
    }
    std.out.puts("========== FIN DEBUG /exec ==========\n");
    std.out.flush();
});

// Manejador 404 (comentado para pruebas)
/*
app.use((req, res) => {
    std.out.puts(`DEBUG: 404 - ${req.path}\n`);
    std.out.flush();
    res.status(404).json({
        error: 'Not Found',
        path: req.path
    });
});
*/

// Inicio del servidor (ADDRESS y PORT vendrán del runtime)
std.out.puts(`Starting server on ${ADDRESS}:${PORT}\n`);
std.out.flush();
app.listen(PORT, ADDRESS, () => {
    std.out.puts(`Server running at ${ADDRESS}:${PORT}\n`);
    std.out.flush();
});
