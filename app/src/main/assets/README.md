# Assets Directory

This directory contains all the binary files and JavaScript libraries needed by the app.

## Directory Structure

```
assets/
├── arm32/           # ARM32 (armeabi-v7a) binaries
│   ├── qjs          # QuickJS binary
│   ├── qjsnet.so    # Network sockets module
│   └── tor          # Tor binary (if onion mode)
├── arm64/           # ARM64 (arm64-v8a) binaries
│   ├── qjs
│   ├── qjsnet.so
│   └── tor
├── tor/             # Tor configuration (if onion mode)
│   ├── torrc
│   └── hidden_service/
│       ├── hostname
│       ├── hs_ed25519_public_key
│       └── hs_ed25519_secret_key
├── express.js       # Express-like HTTP framework
└── server.js        # Your server code (EDIT THIS!)
```

## Build Process

The GitHub Actions workflow automatically:

1. Downloads QuickJS source
2. Compiles QuickJS for ARM32 and ARM64
3. Compiles qjsNetworkSockets module
4. Downloads/compiles Tor binaries
5. Downloads express.js from qjsNetworkSockets repo
6. If onion mode: creates hidden service and extracts keys
7. Packages everything into the APK

## Manual Build

If building locally, you need to:

1. Compile QuickJS for Android ARM32/ARM64
2. Compile qjsNetworkSockets for both architectures
3. Get Tor binaries for Android
4. Download express.js from the qjsNetworkSockets repo

See the GitHub Actions workflow for exact commands.
