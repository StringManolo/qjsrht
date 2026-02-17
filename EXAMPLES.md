# qjsrht Usage Examples

This file contains practical examples of customizing your QuickJS HTTP server.

## Example 1: Simple REST API

Edit `app/src/main/assets/js/server.js`:

```javascript
import express from './express.js';

const app = express();

// In-memory database
const users = [
  { id: 1, name: 'Alice', email: 'alice@example.com' },
  { id: 2, name: 'Bob', email: 'bob@example.com' }
];

// Get all users
app.get('/api/users', (req, res) => {
  res.json(users);
});

// Get user by ID
app.get('/api/users/:id', (req, res) => {
  const user = users.find(u => u.id === parseInt(req.params.id));
  if (user) {
    res.json(user);
  } else {
    res.status(404).json({ error: 'User not found' });
  }
});

// Create user
app.post('/api/users', (req, res) => {
  const data = JSON.parse(req.body);
  const newUser = {
    id: users.length + 1,
    name: data.name,
    email: data.email
  };
  users.push(newUser);
  res.status(201).json(newUser);
});

// Start server
const PORT = parseInt(std.getenv('SERVER_PORT') || '8080');
app.listen(PORT, '0.0.0.0', () => {
  console.log(`REST API running on port ${PORT}`);
});
```

## Example 2: File-based Database

```javascript
import express from './express.js';

const app = express();
const DB_FILE = '/data/local/tmp/db.json';

// Load database
function loadDB() {
  try {
    const file = std.open(DB_FILE, 'r');
    const content = file.readAsString();
    file.close();
    return JSON.parse(content);
  } catch (e) {
    return { items: [] };
  }
}

// Save database
function saveDB(data) {
  const file = std.open(DB_FILE, 'w');
  file.puts(JSON.stringify(data, null, 2));
  file.close();
}

// Get all items
app.get('/api/items', (req, res) => {
  const db = loadDB();
  res.json(db.items);
});

// Add item
app.post('/api/items', (req, res) => {
  const db = loadDB();
  const item = JSON.parse(req.body);
  item.id = Date.now();
  db.items.push(item);
  saveDB(db);
  res.status(201).json(item);
});

// Delete item
app.delete('/api/items/:id', (req, res) => {
  const db = loadDB();
  const id = parseInt(req.params.id);
  db.items = db.items.filter(i => i.id !== id);
  saveDB(db);
  res.json({ success: true });
});

app.listen(8080, '0.0.0.0');
```

## Example 3: Webhook Receiver

```javascript
import express from './express.js';

const app = express();

// Webhook endpoint
app.post('/webhook/:source', (req, res) => {
  const source = req.params.source;
  const timestamp = new Date().toISOString();
  
  // Log webhook
  console.log(`[${timestamp}] Webhook from: ${source}`);
  console.log('Headers:', JSON.stringify(req.headers));
  console.log('Body:', req.body);
  
  // Save to file
  const logFile = std.open('/data/local/tmp/webhooks.log', 'a');
  logFile.puts(`${timestamp} | ${source} | ${req.body}\n`);
  logFile.close();
  
  res.json({
    received: true,
    timestamp: timestamp,
    source: source
  });
});

// View logs
app.get('/logs', (req, res) => {
  try {
    const file = std.open('/data/local/tmp/webhooks.log', 'r');
    const logs = file.readAsString();
    file.close();
    res.text(logs);
  } catch (e) {
    res.text('No logs yet');
  }
});

app.listen(8080, '0.0.0.0');
```

## Example 4: Authentication Middleware

```javascript
import express from './express.js';

const app = express();

const API_KEY = 'your-secret-key-here';

// Authentication middleware
app.use((req, res, next) => {
  if (req.path.startsWith('/api/')) {
    const authHeader = req.get('Authorization');
    if (authHeader === `Bearer ${API_KEY}`) {
      next();
    } else {
      res.status(401).json({ error: 'Unauthorized' });
      return;
    }
  } else {
    next();
  }
});

// Protected endpoint
app.get('/api/secret', (req, res) => {
  res.json({ message: 'This is a protected endpoint!' });
});

// Public endpoint
app.get('/', (req, res) => {
  res.json({ message: 'Public endpoint' });
});

app.listen(8080, '0.0.0.0');
```

## Example 5: CORS Support

```javascript
import express from './express.js';

const app = express();

// CORS middleware
app.use((req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  
  if (req.method === 'OPTIONS') {
    res.status(204).end();
    return;
  }
  
  next();
});

// Your API endpoints
app.get('/api/data', (req, res) => {
  res.json({ data: 'This can be accessed from any origin' });
});

app.listen(8080, '0.0.0.0');
```

## Example 6: Health Check + Metrics

```javascript
import express from './express.js';

const app = express();

let requestCount = 0;
let startTime = Date.now();

// Request counter middleware
app.use((req, res, next) => {
  requestCount++;
  next();
});

// Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    uptime: Date.now() - startTime,
    requests: requestCount,
    memory: std.gc()
  });
});

// Metrics endpoint
app.get('/metrics', (req, res) => {
  const uptime = Date.now() - startTime;
  const rps = requestCount / (uptime / 1000);
  
  res.json({
    total_requests: requestCount,
    uptime_ms: uptime,
    requests_per_second: rps.toFixed(2),
    memory: std.gc()
  });
});

app.listen(8080, '0.0.0.0');
```

## Example 7: HTML Template Server

```javascript
import express from './express.js';

const app = express();

function renderHTML(title, content) {
  return `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${title}</title>
  <style>
    body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }
    h1 { color: #333; }
    .card { background: #f5f5f5; padding: 20px; border-radius: 8px; margin: 20px 0; }
  </style>
</head>
<body>
  <h1>${title}</h1>
  ${content}
</body>
</html>
  `;
}

app.get('/', (req, res) => {
  const content = `
    <div class="card">
      <h2>Welcome to QuickJS Server</h2>
      <p>This page is rendered by QuickJS!</p>
      <ul>
        <li><a href="/about">About</a></li>
        <li><a href="/api/status">API Status</a></li>
      </ul>
    </div>
  `;
  res.html(renderHTML('Home', content));
});

app.get('/about', (req, res) => {
  const content = `
    <div class="card">
      <h2>About</h2>
      <p>This is a QuickJS HTTP server running on Android.</p>
      <p>It uses 60x less memory than Node.js!</p>
    </div>
  `;
  res.html(renderHTML('About', content));
});

app.listen(8080, '0.0.0.0');
```

## Example 8: JSON API with Validation

```javascript
import express from './express.js';

const app = express();

function validateUser(data) {
  if (!data.name || typeof data.name !== 'string') {
    return { valid: false, error: 'Name is required and must be a string' };
  }
  if (!data.email || !data.email.includes('@')) {
    return { valid: false, error: 'Valid email is required' };
  }
  if (!data.age || typeof data.age !== 'number' || data.age < 0) {
    return { valid: false, error: 'Age must be a positive number' };
  }
  return { valid: true };
}

app.post('/api/users', (req, res) => {
  let data;
  try {
    data = JSON.parse(req.body);
  } catch (e) {
    res.status(400).json({ error: 'Invalid JSON' });
    return;
  }
  
  const validation = validateUser(data);
  if (!validation.valid) {
    res.status(400).json({ error: validation.error });
    return;
  }
  
  // Process valid user
  res.status(201).json({
    success: true,
    user: data
  });
});

app.listen(8080, '0.0.0.0');
```

## Testing Your Server

Once your APK is built and installed:

1. **Check if server is running:**
```bash
adb shell netstat -an | grep 8080
```

2. **Test from your computer (device on same WiFi):**
```bash
curl http://DEVICE_IP:8080/
```

3. **Test from device terminal:**
```bash
curl http://127.0.0.1:8080/
```

4. **View logs (debug mode):**
```bash
adb logcat | grep qjsrht
```

5. **Test POST requests:**
```bash
curl -X POST http://DEVICE_IP:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com"}'
```

## Tips

- Keep `server.js` under 1000 lines for best performance
- Use file-based storage instead of databases
- Implement request rate limiting for public APIs
- Always validate user input
- Use CORS headers if accessing from web browsers
- Monitor memory usage with `std.gc()`
