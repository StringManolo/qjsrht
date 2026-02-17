#!/bin/bash
# Quick configuration helper for qjsrht

echo "=== qjsrht Configuration Helper ==="
echo ""

# Read current config
CURRENT_MODE=$(jq -r '.build_mode' config.json)
CURRENT_ADDRESS=$(jq -r '.server.address' config.json)
CURRENT_PORT=$(jq -r '.server.port' config.json)
CURRENT_ONION=$(jq -r '.server.use_onion' config.json)

echo "Current Configuration:"
echo "  Build Mode: $CURRENT_MODE"
echo "  Server Address: $CURRENT_ADDRESS"
echo "  Server Port: $CURRENT_PORT"
echo "  Use Onion: $CURRENT_ONION"
echo ""

# Ask for build mode
echo "Build Mode:"
echo "  1) debug (shows UI with logs)"
echo "  2) production (headless service)"
read -p "Select mode [1/2] (current: $CURRENT_MODE): " MODE_CHOICE

if [ "$MODE_CHOICE" = "1" ]; then
    NEW_MODE="debug"
elif [ "$MODE_CHOICE" = "2" ]; then
    NEW_MODE="production"
else
    NEW_MODE=$CURRENT_MODE
fi

# Ask for address
echo ""
echo "Server Address:"
echo "  1) 127.0.0.1 (localhost only)"
echo "  2) 0.0.0.0 (accessible from network)"
echo "  3) custom"
read -p "Select address [1/2/3] (current: $CURRENT_ADDRESS): " ADDR_CHOICE

case $ADDR_CHOICE in
    1) NEW_ADDRESS="127.0.0.1" ;;
    2) NEW_ADDRESS="0.0.0.0" ;;
    3) read -p "Enter custom address: " NEW_ADDRESS ;;
    *) NEW_ADDRESS=$CURRENT_ADDRESS ;;
esac

# Ask for port
echo ""
read -p "Server Port (current: $CURRENT_PORT): " NEW_PORT
if [ -z "$NEW_PORT" ]; then
    NEW_PORT=$CURRENT_PORT
fi

# Ask for Tor
echo ""
read -p "Enable Tor hidden service? [y/n] (current: $CURRENT_ONION): " TOR_CHOICE
if [ "$TOR_CHOICE" = "y" ]; then
    NEW_ONION="true"
elif [ "$TOR_CHOICE" = "n" ]; then
    NEW_ONION="false"
else
    NEW_ONION=$CURRENT_ONION
fi

# Update config
jq --arg mode "$NEW_MODE" \
   --arg addr "$NEW_ADDRESS" \
   --argjson port "$NEW_PORT" \
   --argjson onion "$NEW_ONION" \
   '.build_mode = $mode | .server.address = $addr | .server.port = $port | .server.use_onion = $onion' \
   config.json > config.json.tmp && mv config.json.tmp config.json

# Copy to assets
cp config.json app/src/main/assets/config.json

echo ""
echo "✓ Configuration updated!"
echo ""
echo "New Configuration:"
echo "  Build Mode: $NEW_MODE"
echo "  Server Address: $NEW_ADDRESS"
echo "  Server Port: $NEW_PORT"
echo "  Use Onion: $NEW_ONION"
echo ""
echo "Now commit and push your changes to trigger GitHub Actions build."
echo "Or build locally with: ./gradlew assembleDebug"
