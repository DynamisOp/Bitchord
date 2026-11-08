#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Building Arch Linux package for BitChord CLI..."
makepkg -f --noconfirm

echo "==> Package successfully built:"
ls -lh *.pkg.tar.zst
