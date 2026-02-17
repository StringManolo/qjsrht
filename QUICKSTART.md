# Quick Start Guide

Get your QuickJS HTTP server APK in 5 minutes!

## Step 1: Get the Template

**Option A: Use as Template**
1. Click "Use this template" on GitHub
2. Name your repository
3. Clone your new repo

**Option B: Fork**
1. Fork this repository
2. Clone your fork

```bash
git clone https://github.com/YOUR_USERNAME/qjsrht.git
cd qjsrht
```

## Step 2: Configure (Optional)

**Quick Config** (Linux/Mac):
```bash
./configure.sh
```

**Manual Config**:
Edit `config.json`:
```json
{
  "mode": "debug",
  "network": {
    "type": "local",
    "address": "127.0.0.1",
    "port": 8080
  },
  "app": {
    "name": "qjsrht",
    "package": "com.qjsrht.app",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

## Step 3: Customize Server (Optional)

Edit `app/src/main/assets/js/server.js`:

```javascript
import express from './express.js';

const app = express();

// Add your endpoints
app.get('/hello', (req, res) => {
  res.json({ message: 'Hello World!' });
});

const PORT = parseInt(std.getenv('SERVER_PORT') || '8080');
app.listen(PORT, '0.0.0.0');
```

## Step 4: Build APK

**Push to GitHub**:
```bash
git add .
git commit -m "Configure server"
git push
```

GitHub Actions will automatically:
- ✅ Compile QuickJS for ARM32 & ARM64
- ✅ Compile network sockets module
- ✅ Prepare Tor binaries
- ✅ Generate Tor hidden service (if enabled)
- ✅ Build APK

## Step 5: Download & Install

1. Go to **Actions** tab on GitHub
2. Click latest workflow run
3. Download **qjsrht-apk** artifact
4. Unzip and install APK on Android device

## Step 6: Test

**From device:**
```bash
# Install Termux from F-Droid
# In Termux:
curl http://127.0.0.1:8080/
```

**From computer (same WiFi):**
```bash
curl http://DEVICE_IP:8080/
```

**If using Tor:**
Check GitHub Actions logs for `DEBUG-ONION: xxxxxxxx.onion`

## Troubleshooting

### APK won't install
- Check Android version (must be 5.0-9.0, API 21-28)
- Enable "Unknown sources"

### Can't connect to server
- Check app is running (debug mode shows logs)
- Verify port isn't blocked
- If using `0.0.0.0`, check firewall

### Tor not working
- Check build logs for onion address
- Wait 60 seconds for Tor bootstrap
- Verify in debug mode logs

## Next Steps

- [Read full README](README.md) for detailed docs
- [Check examples](EXAMPLES.md) for API patterns
- [Customize server.js](app/src/main/assets/js/server.js)
- Test different configurations

## Common Configurations

### Public API Server
```json
{
  "mode": "production",
  "network": {
    "type": "normal",
    "address": "0.0.0.0",
    "port": 8080
  },
  "app": {
    "name": "qjsrht",
    "package": "com.qjsrht.app",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

### Tor Hidden Service
```json
{
  "mode": "production",
  "network": {
    "type": "onion",
    "address": "",
    "port": 8080
  },
  "app": {
    "name": "qjsrht-onion",
    "package": "com.qjsrht.onion",
    "versionCode": 1,
    "versionName": "1.0.0"
  }
}
```

### Development Mode
```json
{
  "mode": "debug",
  "network": {
    "type": "local",
    "address": "127.0.0.1",
    "port": 8080
  },
  "app": {
    "name": "qjsrht-debug",
    "package": "com.qjsrht.debug",
    "versionCode": 1,
    "versionName": "1.0.0-debug"
  }
}
```

---

**That's it! You're ready to go! 🚀**

For questions, open an issue on GitHub.
