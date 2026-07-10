# AuraPlayer

**Hardware-accelerated video playback for Compose Desktop, powered by libmpv.**

AuraPlayer embeds [mpv](https://mpv.io)'s `gpu-next` renderer directly inside your
Compose for Desktop window — real Vulkan (Metal via MoltenVK on macOS) presentation,
hardware decoding, and an API that behaves like the HTML `<video>` element: nest the
player anywhere in your UI, and it can break out to fullscreen from any depth.

```kotlin
AuraPlayerHost(window) {
    // ...anywhere, any nesting depth...
    AuraPlayerSurface(player, Modifier.aspectRatio(16f / 9f)) {
        MyControls(player)   // your Compose controls
    }
}
player.loadFile("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
```

---

## Features

- **True GPU playback** — `vo=gpu-next` with Vulkan (Windows/Linux) and Vulkan-over-Metal
  (macOS, via a custom `macembed` context patched into the bundled libmpv). No frame
  copies, no software rendering.
- **Hardware decoding** — D3D11/Vulkan on Windows, VideoToolbox on macOS, VA-API/VDPAU
  on Linux (`hwdec=auto`).
- **`<video>`-style layout** — `AuraPlayerSurface` is a normal composable. Nest it in
  scrollables, cards, dialogs; call `player.toggleFullscreen()` from anywhere and it
  erupts to cover the window and the screen (Chrome-style borderless fullscreen — same
  window, no flicker, decorations and shadow restored on exit).
- **Multiple players** — every `AuraPlayer` is an independent instance (own mpv core,
  own surface, own event thread). Up to 16 simultaneous surfaces per window.
- **Self-contained binaries** — statically built libmpv (ffmpeg, libass, libplacebo,
  MoltenVK baked in) ships inside the JAR. Users install nothing.
- **Reactive state** — position, duration, buffering, tracks, volume, speed, and
  player state exposed as `StateFlow`s.
- **Format support** — everything mpv/ffmpeg plays: HLS, DASH, MP4, MKV, live streams,
  plus `ytdl` integration.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.mossip:auraplayer-core:<version>")     // engine (no Compose dep)
    implementation("com.mossip:auraplayer-compose:<version>")  // Compose integration
}
```

Native libraries (Windows x64, macOS universal, Linux x64) are bundled in
`auraplayer-core` and extracted at runtime. Load order (libmpv → jawt →
native_render) is handled by `AuraPlayerLoader`.

## Quick start

```kotlin
fun main() = application {
    val player = remember { AuraPlayer() }

    Window(
        onCloseRequest = { player.release(); exitApplication() },
        title = "Demo",
        onPreviewKeyEvent = { e ->
            when {
                e.type == KeyEventType.KeyDown && e.key == Key.F11 -> {
                    player.toggleFullscreen(); true
                }
                e.type == KeyEventType.KeyDown && e.key == Key.Escape &&
                    player.fullscreen.value -> { player.exitFullscreen(); true }
                else -> false
            }
        },
    ) {
        AuraPlayerHost(window) {                       // wrap once per window
            Column {
                AuraPlayerSurface(
                    player,
                    Modifier.aspectRatio(16f / 9f).padding(24.dp),
                ) { PlayerControls(player) }           // controls slot
            }
        }

        LaunchedEffect(player) {
            player.isInitialized.collect { if (it) player.loadFile(VIDEO_URL) }
        }
    }
}
```

Rules of thumb:

1. **One `AuraPlayerHost` per window**, at the root, given the `ComposeWindow`
   (`window` inside `Window {}`).
2. **One `AuraPlayer` per simultaneous video.** Create with `remember {}` or hold it
   in your state layer; call `release()` when done.
3. **Load after init.** The surface initializes asynchronously on first composition;
   gate `loadFile` on `player.isInitialized` (see example).

## API overview

### `AuraPlayer` (auraplayer-core)

| Member | Description |
| --- | --- |
| `loadFile(url)` | Play a URL or file path (anything mpv accepts). |
| `setPause(Boolean)` / `seek(seconds)` / `setVolume(0..100)` / `setSpeed(x)` / `setMute(Boolean)` | Playback control. |
| `setTrack(type, id)` / `tracks: StateFlow<List<MediaTrack>>` | Audio/sub/video track selection. |
| `audioDevices()` / `setAudioDevice(device)` | Output device enumeration & selection (runtime-switchable). |
| `currentTime, duration, bufferDuration, playerState, volume, isMuted, speed, fullscreen, isInitialized` | `StateFlow`s for UI. |
| `enterFullscreen()` / `exitFullscreen()` / `toggleFullscreen()` | Surface fills the window **and** the window goes borderless-fullscreen. |
| `overlayMode: OverlayMode` | `OVER_VIDEO` (controls composited on the video) or `DOCKED` (place controls beside/below). |
| `release()` | Tear down mpv and the native surface. |

### Compose layer (auraplayer-compose)

| Composable | Description |
| --- | --- |
| `AuraPlayerHost(window) { content }` | Root wrapper; owns the native surfaces, geometry sync, fullscreen arbitration, and the "active player" (F11 target with multiple videos). |
| `AuraPlayerSurface(player, modifier) { controls }` | The video element. A lightweight placeholder — the real GPU surface is hosted at the window root and tracks this composable's bounds, so it can live at any depth and expand to fullscreen without re-creating native resources. |

## Platform notes

| | Windows | macOS | Linux (X11) |
| --- | --- | --- | --- |
| Renderer | Vulkan (`winvk`) | Vulkan→Metal (MoltenVK, custom `macembed` context) | Vulkan (`x11vk`) |
| Decode | `hwdec=auto` (D3D11VA/Vulkan) | VideoToolbox | VA-API/VDPAU |
| Controls over video | Docked by default¹ | Composited over video (underlay mode) | Docked |
| Fullscreen | Borderless (`SetWindowPos`, Chrome-style) | Native (`toggleFullScreen:`) | EWMH `_NET_WM_STATE_FULLSCREEN` |

¹ The video surface is a native window and composites above Compose content on
Windows/Linux; check `player.overlayMode` and dock your controls at the surface's
edges there. mpv's own OSD/subtitles always render inside the video. Wayland users:
run under XWayland (the default for JVM apps).

**HDR:** content plays everywhere (tone-mapped to SDR by default). Set
`target-colorspace-hint` for HDR passthrough on Windows/Linux with an HDR display.

**Audio:** output defaults to stereo downmix (predictable on odd endpoints — looking
at you, DualSense controller speakers); use `audioDevices()` for a device picker.

## Building from source

Two CI pipelines produce the shipped artifacts:

1. **`mpv-macbuild`** — builds universal (arm64+x86_64) static libmpv for macOS with
   MoltenVK linked in, and injects the `macembed` Vulkan context
   (`patches/context_mac_embed.m`) that renders into a caller-supplied `CAMetalLayer`
   — the piece that makes embedding possible on modern JDKs, where JAWT only exposes
   a `CALayer` attachment point.
2. **`build-natives`** — compiles the JNI layer per platform
   (`native_render.c` + `jawt_win_{win,x11}.c` / `jawt_macos.m`),
   bundles libmpv, and publishes to Maven Central.

Architecture, briefly: a portable C core owns per-instance mpv handles and event
threads; thin per-platform surface files obtain a native handle from the AWT canvas
via JAWT (HWND / CAMetalLayer / X11 Window) and hand it to mpv as `--wid`. The Compose
layer never moves the native surface — `AuraPlayerSurface` is a measured placeholder,
and the host repositions the root-owned surface to match, which is what makes
fullscreen-from-any-depth safe (no AWT peer destruction, ever).

## Known limitations

- Compose content cannot be composited *over* the video on Windows/Linux
  (`OverlayMode.DOCKED`). On-video UI there: mpv OSD/OSC, `overlay-add` bitmaps,
  or docked layouts.
- Moving a playing video between OS windows is not supported (one host per window).
- Wayland requires XWayland.
- Max 16 surfaces per window.

## License

AuraPlayer is <your license>. Bundled components: mpv & ffmpeg (LGPL v2.1+ as built),
libass, libplacebo, MoltenVK (Apache-2.0). Review LGPL obligations if you ship
statically-modified builds.