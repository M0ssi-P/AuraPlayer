# AuraPlayer
**Hardware-accelerated video playback for Compose Desktop, powered by libmpv.**

AuraPlayer decodes with mpv's render API on a dedicated producer thread and hands
finished frames to Compose as GPU textures — shared directly with Skia's own device
(Metal on macOS, D3D12 on Windows) via IOSurface / D3D shared-handle interop. No frame
copies, no second GPU upload, no native window embedding for the video itself. The
result draws inside an ordinary Compose `Canvas`, which means it's a normal citizen of
your UI: it participates in Compose z-order, can be composited under or behind other
content, resizes and reflows like any composable, and can appear in more than one
window at once (used for picture-in-picture) without a second decode.

```kotlin
Window(
    onCloseRequest = {
        exitApplication()
    },
    state = state,
) {
    val player = remember { AuraPlayer() }

    LaunchedEffect(player) {
        player.loadWhenReady("https://pub-0400bba999e94d4da2149a2f1d2d06a6.r2.dev/hls/master.m3u8") //gated loadFile or use loadFile()
    }

    AuraPlayerHost(window) {
        Column(Modifier.fillMaxSize()) {
            // 3. Nest the surface anywhere, any depth. First composition
            //    attaches the native surface and initializes mpv.
            CompositionLocalProvider(LocalPlayerBounds provides playerCoords) {
                AuraPlayerSurface(
                    player,
                    modifier = Modifier.onGloballyPositioned { playerCoords = it }.aspectRatio(16f / 9f)
                        .align(Alignment.CenterHorizontally),
                    controls = {
                        AuraControlBar(
                            player,
                        )
                    }
                )
            }
        }
    }
}
```

---

## Features

- **GPU texture sharing, not window embedding.** mpv renders into a triple-buffered
  set of platform-native GPU resources on its own thread (IOSurface-backed OpenGL
  framebuffers on macOS, D3D11 textures via ANGLE on Windows). Each consuming window
  opens/wraps the *current* front buffer as a Skia `BackendRenderTarget` on its own
  Metal/D3D12 device and draws it into a Compose `Canvas` — a texture hand-off, not a
  pixel copy.
- **Full Compose z-order participation, on every platform.** Because the video is
  Skia-drawn pixels rather than a positioned native window, overlays, dialogs, and
  docked controls all composite over it naturally everywhere — no platform-specific
  "docked vs. over-video" split.
- **Multiple simultaneous consumers of one decode.** A second window (e.g.
  picture-in-picture) wraps the same producer's buffers on its own GPU device instead
  of starting a second mpv instance — one decode, N on-screen views.
- **Picture-in-picture.** A small always-on-top window can show any player's video
  live, independent of its normal place in your layout; the original call site shows
  an inert placeholder while PiP owns the view.
- **Hardware decoding** — D3D11VA/DXVA on Windows, VideoToolbox on macOS
  (`hwdec=auto`/platform default).
- **Native-resolution, resizable buffers.** Buffers track the source's real decoded
  resolution once mpv reports it, and re-wire live on resize or a mid-stream quality
  change — no fixed-size scratch buffer.
- **Reactive state** — position, duration, buffering, tracks, volume, speed, and
  player state exposed as `StateFlow`s.
- **System integration, opt-in and granular:**
    - Display-sleep prevention while a player is actively playing (native power
      assertions — IOKit on macOS, `SetThreadExecutionState` on Windows).
    - OS "Now Playing" integration — Control Center / macOS media widget and Windows
      System Media Transport Controls, including a live seek bar, artwork, and
      play/pause/seek remote commands. Next/previous are independently toggleable (e.g.
      show "next episode" without "previous"), and the whole feature can be disabled
      per player — useful for background/ambient video that shouldn't claim media keys
      or a Control Center slot at all.
- **Self-contained binaries** — statically built libmpv ships inside the JAR. Users
  install nothing.
- **Format support** — everything mpv/ffmpeg plays: HLS, DASH, MP4, MKV, live streams,
  plus `ytdl` integration.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.m0ssi-p:auraplayer-core:<version>")     // engine (no Compose dep) - good for other frameworks
    implementation("io.github.m0ssi-p:auraplayer-compose:<version>")  // Compose integration - this comes with core
}
```

Native libraries (Windows x64, macOS) are bundled in `auraplayer-core` and extracted
at runtime.

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
                ) { AuraControlBar(player) }           // controls slot
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
| `setTrack(type, id)` / `tracks: StateFlow<List<MediaTrack>>` / `currentTrack` | Audio/sub/video track selection. |
| `audioDevices()` / `setAudioDevice(device)` | Output device enumeration & selection (runtime-switchable). |
| `currentTime, duration, bufferDuration, playerState, volume, isMuted, speed, fullscreen, isInitialized, videoSize` | `StateFlow`s for UI. |
| `enterFullscreen()` / `exitFullscreen()` / `toggleFullscreen()` | Coordinated through `AuraPlaybackCoordinator` — exactly one player owns the fullscreen slot at a time, across windows. |
| `nowPlayingConfig` | `NowPlayingConfig(nowPlayingEnabled, showNext, showPrevious)` — enable/disable OS media integration and its next/previous buttons independently, live. |
| `release()` | Tear down mpv, the producer thread, and native GPU resources. |

### Compose layer (auraplayer-compose)

| Composable | Description |
| --- | --- |
| `AuraPlayerHost(window) { content }` | Root wrapper for a window; provides `LocalAuraHost`/`LocalComposeWindow` context. |
| `AuraPlayerSurface(player, modifier) { controls }` | The video element — a Compose `Canvas` drawing the player's current frame each recomposition. Nest it anywhere; it shows an inert placeholder while its player is promoted to fullscreen or PiP elsewhere. |
| `AuraControlBar(player, ...)` | Default seek bar / transport controls / settings / fullscreen button; every slot (`leading`, `trailing`, `seekBar`) is overridable. |
| `AuraPiP.open(player)` / `AuraPiP.close(player)` | Opens a floating always-on-top window showing `player`'s video, wired to the same decode as the main view. |
| `AuraPlaybackCoordinator` | Process-wide singleton arbitrating the two exclusive presentation modes (fullscreen, PiP) so at most one player occupies each at a time, even across multiple top-level windows. |

## Platform notes

| | Windows | macOS |
| --- | --- | --- |
| Producer | mpv render API (OpenGL) over D3D11 textures via ANGLE, triple-buffered | mpv render API (OpenGL) over CGL + IOSurface-backed FBOs, triple-buffered |
| Cross-device sharing | D3D11 shared NT handle → opened as `ID3D12Resource` on Skiko's device | IOSurface (natively cross-context) → wrapped as `MTLTexture` on Skiko's device |
| Decode | `hwdec=auto` (D3D11VA) | VideoToolbox |
| Now Playing | `SystemMediaTransportControls` (WinRT), bound to the window's `HWND` | `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` |
| Sleep prevention | `SetThreadExecutionState(ES_DISPLAY_REQUIRED)` | `IOPMAssertionCreateWithName` |

Because both platforms draw the video as ordinary Skia-rendered pixels inside a
Compose `Canvas`, there's no platform split for control placement — Compose overlays,
dialogs, and docked layouts all composite correctly over the video everywhere.

**Audio:** output defaults to stereo downmix (predictable on odd endpoints); use
`audioDevices()` for a device picker.

## Building from source

Architecture, briefly:

- A portable C/Objective-C/C++ core (`Model2` JNI surface) owns per-instance mpv
  render contexts and a dedicated producer thread per player. The producer renders
  each new frame into whichever of three GPU buffers is neither currently published
  nor currently being read by a consumer, then atomically publishes it — consumers
  never observe a torn or partially-rendered frame.
- Per platform, a thin native layer (`jawt_macos.m` on macOS, the Windows D3D11/ANGLE
  equivalent) turns those buffers into resources a *different* GPU device (Skiko's)
  can open: an IOSurface wrap on macOS, an opened D3D12 shared handle on Windows.
- The Compose layer (`WindowsSkiaSurfaceStrategy` / `MacSkiaSurfaceStrategy`) wraps
  whichever buffer is currently published as a Skia `BackendRenderTarget` on the
  *consuming window's* own device, takes an image snapshot, and draws it in a
  `Canvas` each frame — one registry (`WindowsVideoRegistry` / `MacVideoRegistry`) per
  platform tracks the producer and any number of per-window consumers, so a second
  window (PiP) just re-wraps the same live buffers rather than triggering a second
  decode.
- Resize is producer-driven: `requestResize` asks the producer thread to recreate its
  three buffers at a new size on its own schedule; consumers poll `getCurrentSize` and
  re-wire themselves whenever it changes, so multiple windows can be mid-transition
  independently without stepping on each other.

## Known limitations

- Moving a *playing* video's producer between fundamentally different GPU devices
  (e.g. an external eGPU hot-swap) isn't handled specially — treat it as you would any
  other GPU-device-loss scenario.
- PiP and in-app fullscreen are each single-owner, process-wide: only one player may
  occupy PiP, and only one may be fullscreen, at any given moment (by design — see
  `AuraPlaybackCoordinator`).
- OS Now Playing integration is a singleton per process on each platform: only the
  most recently active player with `nowPlayingConfig.nowPlayingEnabled = true` owns
  the system media widget at a time.

## License

AuraPlayer is `will do this later`. Bundled components: mpv & ffmpeg (LGPL v2.1+ as
built). Review LGPL obligations if you ship statically-modified builds.