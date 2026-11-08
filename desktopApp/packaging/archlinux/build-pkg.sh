#!/usr/bin/env bash
#
# Builds the Arch Linux package for BitChord using makepkg
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Building BitChord Desktop Arch Linux package..."
makepkg -f

echo "==> Package build complete!"
echo "To install on your system, run:"
echo "    sudo pacman -U bitchord-desktop-*.pkg.tar.zst"
