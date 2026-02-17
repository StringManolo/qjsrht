# qjsrht Deployment Guide

Complete guide for deploying your QuickJS HTTP server APK.

## Prerequisites

- GitHub account (for automated building)
- Android device (API 21-28, Android 5.0-9.0)
- Basic understanding of HTTP servers

## Initial Setup

### 1. Get the Code

**Option A: Use as Template (Recommended)**
```bash
# On GitHub:
1. Click "Use this template" button
2. Name your repository
3. Clone your new repo:
   git clone https://github.com/YOUR_USERNAME/YOUR_REPO.git
   cd YOUR_REPO
```

**Option B: Fork**
```bash
# Fork the repository on GitHub, then:
git clone https://github.com/YOUR_USERNAME/qjsrht.git
cd qjsrht
```

### 2. Configure Your Server

Edit `config.json` to set your server parameters:

```json
{
  "mode": "debug",              // "debug" or "production"
  "network": {
    "type": "local",            // "local", "normal", or "onion"
    "address": "127.0.0.1",     // Bind address
    "port": 8080                // Port number
  },
  "app": {
    "name": "qjsrht",
    "package": "com.qjsrht.app",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

#### Configuration Options

**mode:**
- `debug`: Shows UI with real-time logs
- `production`: Headless service, no UI

**network.type:**
- `local`: Binds to 127.0.0.1 (localhost only)
- `normal`: Binds to specified address (can be 0.0.0.0 for all interfaces)
- `onion`: Creates Tor hidden service

**network.address:**
- For `local`: Use "127.0.0.1"
- For `normal`: Use "0.0.0.0" for all interfaces, or specific IP
- For `onion`: Leave empty "" (ignored, uses .onion address)

### 3. Customize Your Server Logic

Edit `app/src/main/assets/server.js`:

```javascript
const express = require('./express.js');
const app = express();

// Your custom endpoints
app.get('/api/data', (req, res) => {
  res.json({ data: 'your data here' });
});

app.post('/api/create', (req, res) => {
  const body = req.body;
  // Process and save data
  res.status(201).json({ success: true });
});

// Start server (ADDRESS and PORT injected automatically)
app.listen(PORT, ADDRESS, () => {
  console.log(`Server running at ${ADDRESS}:${PORT}`);
});
```

## Building Your APK

### Automated Build (Recommended)

1. **Commit your changes:**
   ```bash
   git add .
   git commit -m "Configure my server"
   git push origin main
   ```

2. **Watch the build:**
   - Go to your repository on GitHub
   - Click the "Actions" tab
   - Watch the build progress

3. **Download your APK:**
   - Once complete, click on the workflow run
   - Scroll to "Artifacts"
   - Download `app-debug-apk` or `app-release-apk`
   - Unzip the file

### Understanding the Build Process

The GitHub Actions workflow automatically:

1. ✅ Reads your `config.json`
2. ✅ Sets up Android build environment
3. ✅ Downloads QuickJS source code
4. ✅ Compiles QuickJS for ARM32 (armeabi-v7a)
5. ✅ Compiles QuickJS for ARM64 (arm64-v8a)
6. ✅ Downloads qjsNetworkSockets
7. ✅ Compiles network module for both architectures
8. ✅ Downloads express.js
9. ✅ If onion mode: Downloads Tor binaries
10. ✅ If onion mode: Generates hidden service and keys
11. ✅ If onion mode: Outputs onion address to logs
12. ✅ Builds APK with all components
13. ✅ Uploads APK as artifact

**For onion builds**, check the build logs for:
```
DEBUG-ONION: youraddress123456.onion
```

## Installing on Android

### 1. Enable Unknown Sources

On your Android device:
- **Android 8.0+**: Settings → Apps → Special Access → Install unknown apps → Enable for your file manager
- **Android 7.0 and below**: Settings → Security → Unknown sources → Enable

### 2. Transfer and Install APK

**Method A: Direct Download**
1. On your Android device, download the APK from GitHub
2. Open the APK file
3. Tap "Install"

**Method B: Transfer from Computer**
1. Connect Android device via USB
2. Copy APK to device
3. Use file manager to open APK
4. Tap "Install"

### 3. Grant Permissions

When prompted, grant:
- Network access (automatically granted)
- Boot permission (for auto-start)

## Testing Your Server

### Debug Mode Testing

If you built in debug mode, open the app to see logs in real-time.

### Testing Locally

On the Android device, using Termux:
```bash
# Install Termux from F-Droid
# In Termux:
pkg install curl
curl http://127.0.0.1:8080/
```

### Testing from Same Network

From your computer (same WiFi):
```bash
# Find your Android device IP:
# Settings → About Phone → Status → IP Address
# Example: 192.168.1.100

curl http://192.168.1.100:8080/
```

### Testing Tor Hidden Service

If you configured onion mode:
```bash
# Install Tor browser or use torsocks
torsocks curl http://youraddress123456.onion:8080/
```

## Common Deployment Scenarios

### Scenario 1: Local Development Server

**Use case:** Testing APIs on your device

**config.json:**
```json
{
  "mode": "debug",
  "network": {
    "type": "local",
    "address": "127.0.0.1",
    "port": 8080
  },
  "app": {
    "name": "My Dev Server",
    "package": "com.example.devserver",
    "versionCode": 1,
    "versionName": "1.0.0-dev"
  }
}
```

### Scenario 2: LAN API Server

**Use case:** Share API with other devices on your network

**config.json:**
```json
{
  "mode": "production",
  "network": {
    "type": "normal",
    "address": "0.0.0.0",
    "port": 8080
  },
  "app": {
    "name": "LAN API",
    "package": "com.example.lanapi",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

### Scenario 3: Anonymous Tor Hidden Service

**Use case:** Private, censorship-resistant API

**config.json:**
```json
{
  "mode": "production",
  "network": {
    "type": "onion",
    "address": "",
    "port": 8080
  },
  "app": {
    "name": "Hidden API",
    "package": "com.example.hiddenapi",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

## Troubleshooting

### APK Won't Install

**Problem:** Installation blocked
**Solutions:**
- Verify Android version (5.0-9.0 required)
- Enable "Install from unknown sources"
- Check device storage space

### Server Not Starting

**Problem:** App opens but server doesn't respond
**Solutions:**
- Check logs (debug mode only)
- Verify port isn't already in use
- Check firewall settings

### Can't Connect from Other Devices

**Problem:** Server works locally but not from network
**Solutions:**
- Verify `network.type` is set to "normal"
- Use `address: "0.0.0.0"` to bind all interfaces
- Check firewall on Android device
- Verify devices are on same network

### Tor Not Working

**Problem:** Onion address not accessible
**Solutions:**
- Check build logs for actual onion address
- Wait 60-120 seconds for Tor to bootstrap
- Verify Tor is running (check logs in debug mode)
- Test with Tor Browser first

### Build Fails on GitHub Actions

**Problem:** Workflow fails
**Solutions:**
- Check config.json syntax (use JSONLint)
- Verify all required files are committed
- Check workflow logs for specific errors
- Ensure repository has Actions enabled

## Advanced Configuration

### Custom Icons

Replace placeholder icons in:
- `app/src/main/res/mipmap-hdpi/`
- `app/src/main/res/mipmap-mdpi/`
- `app/src/main/res/mipmap-xhdpi/`
- `app/src/main/res/mipmap-xxhdpi/`
- `app/src/main/res/mipmap-xxxhdpi/`

Use https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html

### Custom Package Name

Change in `config.json`:
```json
"app": {
  "package": "com.yourcompany.yourapp"
}
```

### Multiple Builds

Create different branches with different configurations:
- `main` - production config
- `dev` - debug config
- `onion` - Tor config

## Security Considerations

### Production Deployments

1. **Use production mode** to disable logging
2. **Validate all inputs** in server.js
3. **Use HTTPS** via reverse proxy if exposing publicly
4. **Rate limit** endpoints to prevent abuse
5. **Monitor logs** regularly (requires adb or external logging)

### Tor Hidden Services

1. **Backup keys** from build logs or extracted APK
2. **Don't commit** hidden service keys to public repos
3. **Rotate addresses** periodically for security
4. **Monitor usage** to detect abuse

## Monitoring and Maintenance

### Viewing Logs (Debug Mode)

Open the app to see real-time logs.

### Viewing Logs (Production Mode)

Use adb:
```bash
adb logcat | grep ServerService
```

### Updating Server Code

1. Edit `server.js`
2. Increment `versionCode` in config.json
3. Commit and push
4. Download new APK
5. Install over existing app

## Performance Tips

1. **Keep server.js simple** - QuickJS is fast but not Node.js
2. **Use caching** for repeated computations
3. **Minimize JSON parsing** for large payloads
4. **Use appropriate HTTP status codes**
5. **Consider clustering** for high load (not built-in)

## FAQ

**Q: Can I use npm packages?**
A: No, QuickJS doesn't support npm. Use pure JavaScript only.

**Q: What's the maximum request size?**
A: Limited by available RAM, typically ~10MB safely.

**Q: Can I serve static files?**
A: Not built-in, but you can implement in server.js.

**Q: Does it support WebSockets?**
A: Not currently, but possible to add.

**Q: Can I use HTTPS?**
A: Use a reverse proxy like nginx for HTTPS.

**Q: How do I keep it running?**
A: Auto-starts on boot. For persistent running, avoid battery optimization.

## Support

- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Documentation**: README.md and code comments

## Next Steps

- [Customize server.js](app/src/main/assets/server.js)
- [Check examples](EXAMPLES.md)
- [Read API docs](README.md)
- Build and deploy!

---

Happy deploying! 🚀
