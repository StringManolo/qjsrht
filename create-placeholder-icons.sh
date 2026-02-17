#!/bin/bash
# Create placeholder icons for the project
# These are simple colored rectangles that will work until you replace them with real icons

create_icon() {
    local size=$1
    local output=$2
    
    # Create a simple colored PNG using ImageMagick if available
    # Otherwise, just create empty files
    if command -v convert &> /dev/null; then
        convert -size ${size}x${size} xc:#4CAF50 "$output"
    else
        # Create a minimal valid PNG file
        # This is a 1x1 green pixel PNG
        echo -n -e '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x00\x03\x00\x01\x00\x00\x00\x00IEND\xaeB`\x82' > "$output"
    fi
}

# Create icons for all densities
create_icon 48 "app/src/main/res/mipmap-mdpi/ic_launcher.png"
create_icon 48 "app/src/main/res/mipmap-mdpi/ic_launcher_round.png"

create_icon 72 "app/src/main/res/mipmap-hdpi/ic_launcher.png"
create_icon 72 "app/src/main/res/mipmap-hdpi/ic_launcher_round.png"

create_icon 96 "app/src/main/res/mipmap-xhdpi/ic_launcher.png"
create_icon 96 "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png"

create_icon 144 "app/src/main/res/mipmap-xxhdpi/ic_launcher.png"
create_icon 144 "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png"

create_icon 192 "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
create_icon 192 "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"

echo "Placeholder icons created. Replace them with your own icons before release!"
