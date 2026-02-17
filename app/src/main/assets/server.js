// qjsrht Default Server
// Edit this file to customize your server behavior
// ADDRESS and PORT will be injected automatically from config.json

import express from './express.js';
const app = express();

// Basic logging middleware
app.use((req, res, next) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.path}`);
  next();
});

// Root endpoint
app.get('/', (req, res) => {
  res.json({
    message: 'Welcome to qjsrht!',
    timestamp: new Date().toISOString(),
    server: 'QuickJS Express Server'
  });
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime || 0 });
});

// Echo endpoint for testing
app.post('/echo', (req, res) => {
  res.json({
    received: req.body,
    headers: req.headers
  });
});

// Example API with route parameters
app.get('/api/users/:id', (req, res) => {
  res.json({
    userId: req.params.id,
    name: 'Sample User',
    email: 'user@example.com'
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    error: 'Not Found',
    path: req.path
  });
});

// Start server
// Note: ADDRESS and PORT are defined automatically by the runtime
console.log(`Starting server on ${ADDRESS}:${PORT}`);
app.listen(PORT, ADDRESS, () => {
  console.log(`Server running at ${ADDRESS}:${PORT}`);
});
