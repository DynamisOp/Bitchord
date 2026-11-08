# BitChord Desktop

BitChord has a Kotlin Multiplatform shared module and a Compose Multiplatform
desktop application for macOS, Linux, and Windows. Desktop uses the JVM target, which is
the supported Compose Multiplatform desktop model.

The desktop target uses Java 21.

> [!TIP]
> Looking for an ultra-lightweight terminal player instead? Check out [CLI.md](CLI.md) for `bitchord-cli` with synced lyrics and ASCII visualizer.

On macOS (Apple Silicon or Intel), run it with a Java 21 JDK available in your environment:

```bash
./gradlew :desktopApp:run
```

On Linux, run it with the JDK and native libraries available in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Every download on the [releases page](https://github.com/DynamisOp/Bitchord/releases) carries its own Java
runtime, the FFmpeg and ONNX natives, the Automix analyser and its models.
Nothing else has to be installed first — no JDK, no codec pack.

| Platform | Download | Notes |
|---|---|---|
| macOS | `BitChord-<version>-macos-arm64.dmg` | For Apple Silicon Macs (M1/M2/M3/M4/later). Drag to Applications. |
| macOS | `BitChord-<version>-macos-x64.dmg` | For Intel Macs. Drag to Applications. |
| macOS | `BitChord-<version>-macos-*.pkg` | Standard macOS installer package for automated or managed installations. |
| Linux | `bitchord-desktop-<version>-1-x86_64.pkg.tar.zst` | `sudo pacman -U bitchord-desktop-*.pkg.tar.zst` on Arch Linux. |
| Linux | `BitChord-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `BitChord-<version>-linux-amd64.deb` | `sudo apt install ./BitChord-*.deb` on Debian, Ubuntu and derivatives. |
| Linux | `BitChord-<version>-linux-x86_64.rpm` | `sudo dnf install ./BitChord-*.rpm` on Fedora, RHEL and openSUSE. |
| Windows | `BitChord-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `BitChord-<version>-windows-x64.msi` | The same thing for anyone who deploys by MSI. |
| Windows | `BitChord-<version>-windows-x64-portable.zip` | Unzip anywhere and run `BitChord.exe`. Writes nothing outside the folder. |

**macOS Gatekeeper.** Unsigned binaries built locally or downloaded outside the Mac App Store
may trigger a macOS security prompt ("BitChord is damaged and can't be opened" or "unidentified developer").
To clear the quarantine flag, run:

```bash
xattr -cr /Applications/BitChord.app
```

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows and the Visual C++ runtime.** The Automix analyser is linked against a
static C runtime, so on almost every machine there is nothing to install. If
BitChord starts but Automix never analyses anything, install the
[Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe)
and restart it — that is the one dependency the bundled runtime cannot carry
itself, because Windows expects it to be a system component.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On macOS:

```bash
# Builds the disk image (.dmg) installer
./gradlew :desktopApp:packageDmg

# Builds the .pkg installer
./gradlew :desktopApp:packagePkg

# Standalone BitChord.app bundle
./gradlew :desktopApp:createDistributable
# Output is at: desktopApp/build/compose/binaries/main/app/BitChord.app
```

On Linux:

```bash
# On Arch Linux (Native package via makepkg):
cd desktopApp/packaging/archlinux
./build-pkg.sh
# Install with:
sudo pacman -U bitchord-desktop-*.pkg.tar.zst

# On Debian/Ubuntu (.deb) or Fedora (.rpm):
bash ./gradlew :desktopApp:packageDeb
bash ./gradlew :desktopApp:packageRpm

# Standalone AppImage:
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/BitChord \
  dist/BitChord-linux-x86_64.AppImage
```

## Potato Laptops & Low-Resource Linux Optimization

If you are running BitChord on a low-end laptop (e.g. Intel HD/UHD integrated graphics, older dual-core/quad-core CPUs, or on battery) and want to preserve the aesthetic **liquid glass & blur** without melting your machine:

1. **Potato & Performance Glass Mode (Enabled by default):**
   - Head into **Settings -> Appearance -> Potato & Performance Glass mode**.
   - Instead of running a full-resolution 1080p/4K Skia blur pass with per-pixel GPU procedural noise shaders on every frame, the backdrop is sampled at 1/4 resolution (1/16th total pixels) and noise synthesis is bypassed.
   - Because a 24–30dp blur inherently washes out high-frequency detail, the upscale is seamlessly hidden by the blur itself. You keep **100% of the frosted liquid glass, hairline borders, and translucent highlights** with **>93% less GPU fill-rate and memory bandwidth**.
2. **Hardware OpenGL Acceleration (`-Dskiko.renderApi=OPENGL`):**
   - Ensured out of the box on Linux to prevent Skiko from falling back to Mesa's `llvmpipe` CPU software rasterizer, which pegs CPU cores at 100%.
3. **Adaptive JVM Memory & Idle GC:**
   - Bundled JVM arguments include `-Xms64m -Xmx512m`, `-XX:+UseG1GC`, `-XX:G1PeriodicGCInterval=10000`, and `-XX:G1PeriodicGCSystemLoadThreshold=0`. When idle, the JVM automatically compacts and returns unused memory to the Linux kernel.
   - `-XX:+UseStringDeduplication` reduces memory consumption from duplicated metadata strings.
4. **Automix & Audio Analysis Tuning:**
   - Background audio analysis runs on a single low-priority worker thread to avoid UI lag. When running on low-spec hardware, analysis uses `AutomixPerformanceMode.BATTERY_SAVER` (single inference thread).

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds both the `.exe` and the `.msi` with:

```bat
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

Pass `-Pbitchord.version=1.2.3` to stamp a version other than the one in
`desktopApp/build.gradle.kts`; a release build takes it from the tag.
