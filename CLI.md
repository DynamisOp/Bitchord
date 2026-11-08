# BitChord CLI (`bitchord-cli`)

A lightweight, ultra-low-resource terminal audio player for YouTube Music with synced lyrics, real-time ASCII audio visualizer, and keyboard navigation.

Designed for low-end hardware, Linux servers, SSH sessions, terminal multiplexers (`tmux`, `zellij`), or anyone who wants a minimal music experience that uses negligible CPU and RAM (~20–40MB idle).

---

## Features

- **Direct YouTube Music Streaming**: High-quality audio extraction powered by InnerTube & NewPipe.
- **Synced Lyrics**: Word/line-synced lyrics with active line highlighting, automatic scrolling, and manual timing adjustment (`[` / `]`).
- **Real-Time ASCII Audio Visualizer**: 12-band RMS spectrum visualizer (` ▂▃▄▅▆▇█`) reacting directly to the output audio buffer.
- **Search & Playlists**: Search for tracks, artists, albums, playlists (`p:<query>`), or paste YouTube video and playlist URLs directly.
- **Queue Management**: Add songs to queue (`a`), view upcoming queue items (`Tab`), skip (`n` / `p`), and seek.
- **Playback Controls**: Shuffle mode (`s`), repeat modes (`r`: Off, All, One), mute (`m`), and volume adjustment.
- **Zero-Flicker TUI**: Direct terminal addressing with raw mode (JLine 3), absolute row drawing, and auto-wrap suppression for smooth rendering.

---

## Keybindings

| Key | Action |
|---|---|
| `Space` | Play / Pause playback |
| `j` / `k` or `Down` / `Up` | Navigate search results or queue items |
| `Enter` | Play selected track |
| `a` | Add selected track to queue |
| `n` / `p` | Next / Previous track in queue |
| `h` / `l` | Seek backward / forward 5 seconds |
| `H` / `L` | Seek backward / forward 15 seconds (fast seek) |
| `+` / `-` | Increase / Decrease volume |
| `m` | Toggle mute |
| `s` | Toggle shuffle mode |
| `r` | Cycle repeat mode (Off → All → One) |
| `[` / `]` | Adjust lyrics timing offset by ±500ms |
| `/` | Open search prompt (tracks, `p:playlist`, or YouTube URL) |
| `Tab` | Switch view between Search Results and Up Next Queue |
| `q` | Quit player |

---

## Installation & Running

### Arch Linux (Native Package)

A prebuilt package is available in the repository root or can be compiled locally:

```bash
# Install prebuilt package
sudo pacman -U bitchord-cli-1.9.1-1-x86_64.pkg.tar.zst

# Or build the package yourself
cd cliApp/packaging/archlinux
./build-pkg.sh
sudo pacman -U bitchord-cli-*.pkg.tar.zst
```

Once installed, simply run:
```bash
bitchord-cli
```

### Build from Source (Linux / macOS)

Requires Java 21:

```bash
# Build and install distribution binary
./gradlew :cliApp:installDist

# Run the installed binary
./cliApp/build/install/bitchord-cli/bin/bitchord-cli
```

---

## Command Line Usage

You can launch `bitchord-cli` interactively or pass search queries / URLs directly:

```bash
# Launch interactive player with default search
bitchord-cli

# Search and play a song immediately
bitchord-cli "Starboy The Weeknd"

# Play a direct YouTube video URL
bitchord-cli "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

# Enqueue and play a YouTube playlist URL
bitchord-cli "https://www.youtube.com/playlist?list=PL4fGSIFgk54Gk3h_72Z9_LgA6Q8lQ23q"

# Play YouTube Music playlist or album browse ID
bitchord-cli "VLPL4fGSIFgk54Gk3h_72Z9_LgA6Q8lQ23q"
```
