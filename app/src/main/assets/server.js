// qjsrht Server - Complete version with native FIFO and JS execution

import * as std from 'std';
import express from './express.js';

// ----------------------------------------------------------------------
// Native FIFO communication (with NativeServer.kt)
// ----------------------------------------------------------------------
let nativeReqFd = null;
let nativeRespFd = null;

function openNativePipes() {
    const reqPath = './native_req';
    const respPath = './native_resp';
    try {
        nativeReqFd = std.open(reqPath, 'w');
        nativeRespFd = std.open(respPath, 'r');
        return true;
    } catch (e) {
        std.err.puts('[Native] Failed to open FIFOs: ' + e + '\n');
        return false;
    }
}

/**
 * Send a request to the native Android service and return the response.
 * @param {string} action - e.g. 'contacts', 'location', 'settings'
 * @param {object} params - optional parameters for the action
 * @returns {object} parsed JSON response
 */
function callNative(action, params) {
    if (!nativeReqFd || !nativeRespFd) {
        if (!openNativePipes()) {
            return { error: 'Native pipes not available' };
        }
    }

    const request = { action, params };
    const reqStr = JSON.stringify(request) + '\n';

    // Write request
    nativeReqFd.puts(reqStr);
    nativeReqFd.flush();

    // Read response (one line)
    const respLine = nativeRespFd.getline();
    if (respLine === null) {
        // Pipe closed – try to reopen next time
        nativeReqFd.close();
        nativeRespFd.close();
        nativeReqFd = null;
        nativeRespFd = null;
        return { error: 'Native pipe closed' };
    }

    try {
        return JSON.parse(respLine);
    } catch (e) {
        return { error: 'Invalid JSON from native', raw: respLine };
    }
}

// ----------------------------------------------------------------------
// Express app setup
// ----------------------------------------------------------------------
const app = express();

// This token is injected at build time from GitHub secret
const AUTH_TOKEN = "__AUTH_TOKEN__";

// Logging middleware using std.out (ensures output to logcat)
app.use((req, res, next) => {
    std.out.puts(`[${new Date().toISOString()}] ${req.method} ${req.path}\n`);
    std.out.flush();
    next();
});

// ----------------------------------------------------------------------
// Public endpoints
// ----------------------------------------------------------------------
app.get('/', (req, res) => {
    std.out.puts("DEBUG: GET /\n");
    std.out.flush();
    res.json({
        message: 'Welcome to qjsrht!',
        timestamp: new Date().toISOString(),
        server: 'QuickJS Express Server'
    });
});

app.get('/health', (req, res) => {
    std.out.puts("DEBUG: GET /health\n");
    std.out.flush();
    res.json({ status: 'ok', uptime: process.uptime || 0 });
});

app.post('/echo', (req, res) => {
    std.out.puts("DEBUG: POST /echo\n");
    std.out.flush();
    res.json({
        received: req.body,
        headers: req.headers
    });
});

app.get('/api/users/:id', (req, res) => {
    std.out.puts(`DEBUG: GET /api/users/${req.params.id}\n`);
    std.out.flush();
    res.json({
        userId: req.params.id,
        name: 'Sample User',
        email: 'user@example.com'
    });
});

// ----------------------------------------------------------------------
// Protected endpoint: shell command execution (original)
// ----------------------------------------------------------------------
app.post('/exec', (req, res) => {
    std.out.puts("========== DEBUG /exec ==========\n");
    std.out.puts(`req.body (raw): ${req.body}\n`);
    std.out.puts(`typeof req.body: ${typeof req.body}\n`);
    std.out.puts(`req.headers.authorization: ${req.headers.authorization}\n`);
    std.out.puts(`req.headers.content-type: ${req.headers['content-type']}\n`);
    std.out.flush();

    // Authentication
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        std.out.puts("ERROR: No autorizado\n");
        std.out.flush();
        res.send(JSON.stringify({ error: 'Unauthorized' }));
        return;
    }
    std.out.puts("Autorizado correctamente\n");
    std.out.flush();

    // Parse the body
    let bodyObj;
    try {
        if (typeof req.body === 'string') {
            bodyObj = JSON.parse(req.body);
        } else if (typeof req.body === 'object' && req.body !== null) {
            bodyObj = req.body; // already object
        } else {
            throw new Error('req.body is neither string nor object');
        }
        std.out.puts(`body parsed: ${JSON.stringify(bodyObj)}\n`);
        std.out.flush();
    } catch (e) {
        std.out.puts(`ERROR parsing JSON: ${e.message}\n`);
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
    std.out.puts(`Command to execute: ${cmd}\n`);
    std.out.flush();

    // Execute command with std.popen
    try {
        const fullCmd = cmd + ' 2>&1';
        std.out.puts(`Running: ${fullCmd}\n`);
        std.out.flush();
        const fp = std.popen(fullCmd, 'r');
        let output = '';
        let line;
        while ((line = fp.getline()) !== null) {
            output += line + '\n';
        }
        const status = fp.close();
        std.out.puts(`Command executed, status: ${status}\n`);
        std.out.puts(`Output (first 100 chars): ${output.substring(0, 100)}\n`);
        std.out.flush();
        res.send(JSON.stringify({ output, status }));
    } catch (e) {
        std.out.puts(`ERROR in execution: ${e.message}\n`);
        std.out.flush();
        res.send(JSON.stringify({ error: e.message }));
    }
    std.out.puts("========== END /exec ==========\n");
    std.out.flush();
});

// ----------------------------------------------------------------------
// NEW: Protected endpoint for native Android actions
// ----------------------------------------------------------------------
app.post('/api/native', (req, res) => {
    std.out.puts("========== /api/native ==========\n");
    std.out.flush();

    // Authentication
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        res.status(401).json({ error: 'Unauthorized' });
        return;
    }

    // Parse body
    let body = req.body;
    if (typeof body === 'string') {
        try {
            body = JSON.parse(body);
        } catch (e) {
            res.status(400).json({ error: 'Invalid JSON' });
            return;
        }
    }

    const { action, params } = body;
    if (!action) {
        res.status(400).json({ error: 'Missing action' });
        return;
    }

    std.out.puts(`Native action: ${action}, params: ${JSON.stringify(params)}\n`);
    std.out.flush();

    const result = callNative(action, params);
    res.json(result);
    std.out.puts("========== END /api/native ==========\n");
    std.out.flush();
});

// ----------------------------------------------------------------------
// NEW: Protected endpoint to execute JavaScript remotely using qjs binary
// ----------------------------------------------------------------------
app.post('/api/js', (req, res) => {
    std.out.puts("========== /api/js ==========\n");
    std.out.flush();

    // Authentication
    const auth = req.headers.authorization;
    if (!auth || !auth.startsWith('Bearer ') || auth.slice(7) !== AUTH_TOKEN) {
        res.status(401).json({ error: 'Unauthorized' });
        return;
    }

    // Parse body
    let body = req.body;
    if (typeof body === 'string') {
        try {
            body = JSON.parse(body);
        } catch (e) {
            res.status(400).json({ error: 'Invalid JSON' });
            return;
        }
    }

    const { code } = body;
    if (!code || typeof code !== 'string') {
        res.status(400).json({ error: 'Missing or invalid code' });
        return;
    }

    std.out.puts(`Executing JS code (length ${code.length})\n`);
    std.out.flush();

    // Write code to a temporary file
    const tmpFile = './tmp_js_' + Date.now() + '.js';
    try {
        std.fs.writeFile(tmpFile, code);
    } catch (e) {
        res.status(500).json({ error: 'Failed to write temp file', details: e.message });
        return;
    }

    // Run qjs on the temp file
    const cmd = `./qjs ${tmpFile} 2>&1`;
    const fp = std.popen(cmd, 'r');
    let output = '';
    let line;
    while ((line = fp.getline()) !== null) {
        output += line + '\n';
    }
    const status = fp.close();

    // Clean up
    try {
        std.fs.remove(tmpFile);
    } catch (e) {
        std.err.puts(`Warning: could not remove ${tmpFile}\n`);
    }

    res.json({ output, exitCode: status });
    std.out.puts(`JS execution finished, exit code: ${status}\n`);
    std.out.puts("========== END /api/js ==========\n");
    std.out.flush();
});

// ----------------------------------------------------------------------
// Optional 404 handler (commented by default)
// ----------------------------------------------------------------------
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

// ----------------------------------------------------------------------
// Start the server
// ----------------------------------------------------------------------
// ADDRESS and PORT are injected at runtime by ServerService.kt
std.out.puts(`Starting server on ${ADDRESS}:${PORT}\n`);
std.out.flush();
app.listen(PORT, ADDRESS, () => {
    std.out.puts(`Server running at ${ADDRESS}:${PORT}\n`);
    std.out.flush();
});
