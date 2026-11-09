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
- **Zero-Flicker Rounded Card TUI**: Modern rounded panel boxes (`╭───╮`, `╰───╯`), direct JLine 3 terminal addressing, raw mode, and auto-wrap suppression for butter-smooth rendering.
- **Solid Block Progress Bar**: High-density block progress bar (`█` / `░`) with explicit percentage indicator.
- **Favorites & Starred Library**: Quick star/favorite system (`f`), persistent storage, and dedicated Starred Library tab (`3`).
- **Queue Minimization & Zen Mode**: Minimize the playback queue (`z` or `v`) to expand synced lyrics to full width.
- **8 Built-in Color Themes**: Switch themes live (`t`) between Neon Cyberpunk, Catppuccin Mocha, Tokyo Night, Dracula, Nord Arctic, Emerald Matrix, Amber Sunset, and Classic 16-color ANSI with automatic preference persistence.

---

## Keybindings

| Key | Action |
|---|---|
| `Space` | Play / Pause playback |
| `f` | Toggle favorite / star on current track |
| `z` | Toggle Queue Minimization / Zen Fullscreen Lyrics mode |
| `v` | Cycle layout modes (Split View ↔ Zen Lyrics ↔ Full Queue) |
| `t` | Cycle color themes (Cyberpunk, Catppuccin, Tokyo Night, Dracula, Nord, Matrix, Amber, ANSI) |
| `j` / `k` or `Down` / `Up` | Navigate search results, queue items, or favorites |
| `Enter` | Play selected track |
| `a` | Add selected track to queue |
| `d` / `x` | Remove selected track from Favorites (in Favorites view) |
| `n` / `p` | Next / Previous track in queue |
| `h` / `l` | Seek backward / forward 5 seconds |
| `H` / `L` | Seek backward / forward 15 seconds (fast seek) |
| `+` / `-` | Increase / Decrease volume |
| `m` | Toggle mute |
| `s` | Toggle shuffle mode |
| `r` | Cycle repeat mode (Off → All → One) |
| `[` / `]` | Adjust lyrics timing offset by ±500ms |
| `/` | Open search prompt (tracks, `p:playlist`, or YouTube URL) |
| `1` / `2` / `3` | Direct tab switch (`1`: Search, `2`: Queue, `3`: Favorites) |
| `Tab` | Cycle view between Search, Queue, and Starred Favorites |
| `q` | Quit player |

---

## Installation & Running

### Arch Linux (Native Package)

A prebuilt package is available in the repository root or can be compiled locally:

```bash
# Install prebuilt package
sudo pacman -U bitchord-cli-1.9.2-1-x86_64.pkg.tar.zst

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
