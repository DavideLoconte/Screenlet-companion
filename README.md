<p align="center"><img src="docs/icon.png" width="128" alt="Screenlet icon"></p>

# Screenlet Companion

*Screenlet Companion* is the Android client application for Screenlet. It transforms an Android tablet (specifically optimized for Samsung Galaxy Tab devices and the S Pen) into an ultra-low-latency secondary display for a desktop computer over Wi-Fi or a USB-C connection. The host computer runs [Screenlet for macOS](https://github.com/DavideLoconte/Screenlet-macos), [Screenlet for GNOME](https://github.com/DavideLoconte/Screenlet-linux), or [Screenlet for Windows](https://github.com/DavideLoconte/Screenlet-windows), communicating via the [Screenlet protocol](PROTOCOL.md).

Screenlet originated as a fork of [Plus One](https://github.com/GabrieleSantangelo/PlusOne).

> [!NOTE]
> The current version is in development and prone to changes. The version number is specified in [VERSION](VERSION).

## Features

- **Ultra-low latency.** Video frames are dispatched directly to the hardware decoder (H.264 or HEVC) as soon as all corresponding packets are received, avoiding intermediate jitter buffer delays. Wi-Fi power-saving mode is disabled while the display session is active.
- **Full multi-touch and S Pen support.** Captures and forwards multi-touch digitizer events and pen samples, including coordinates, pressure, tilt, hover states, and barrel button events.
- **Versatile connectivity (USB cable, local network, or Wi-Fi Direct).** Over USB (via the Android Open Accessory protocol), the application launches automatically without requiring developer options or USB debugging. Over a local network, the service is advertised via mDNS. In environments without an existing network infrastructure, the application hosts a standalone Wi-Fi Direct group for the host to join.
- **Authenticated and encrypted transport.** Mutual authentication is completed once via a six-digit verification code displayed on both screens. Control connections are secured with TLS 1.3, while video and input datagrams are encrypted end-to-end using AES-GCM.

## Latency Optimizations

- Incoming packets are processed into pre-allocated application buffers, copying video payload data directly into the target frame memory without per-packet heap allocations.
- Reconstructed frames are passed directly into available hardware decoder buffers, configured with low-latency operating parameters across supported chipsets (Android standard, Samsung Exynos, Qualcomm Snapdragon, and MediaTek) and presented at the next vertical sync (vsync).
- The display panel operates at its maximum supported refresh rate while presenting the desktop.
- Network reception, USB cable transfers, and decoder callback routines execute at display-thread priority.
- Multi-touch and stylus events are dispatched immediately as reported by the digitizer, streaming directly from the UI thread using pre-allocated buffers without thread hopping.

Once per second, the application measures five latency metrics for each frame (capture-to-decoded, capture-to-first-packet, network packet transit spread, decoder queue delay, and hardware decode duration) and reports them back to the host computer, which records them alongside host-side timings ([PROTOCOL.md](PROTOCOL.md) §3.5).

## Architecture

| Component | Implementation Details |
|---|---|
| Service discovery | `CompanionService`: A foreground service providing a TLS server and mDNS advertisement (`_screenlet._tcp`) |
| Authentication | `Pairing.kt`, `Identity.kt`: Mutual authentication backed by the Android Keystore (EC P-256) |
| Video pipeline | `DisplaySession`: AES-GCM payload decryption and `MediaCodec` decoding rendering to a `SurfaceView` (`DisplayActivity`) |
| Input dispatch | `DisplayInput`: Real-time touch and stylus event forwarding |
| USB transport | `UsbTunnel`: Multiplexes host TLS connections and display datagrams across bidirectional bulk USB endpoints |

## Building from Source

### Requirements

- JDK 21 or later.
- Android SDK (configured via `ANDROID_HOME` or `sdk.dir` in `local.properties`).

### Build and Installation

```bash
./gradlew testDebugUnitTest lint assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

> [!TIP]
> Always evaluate latency using a release build. Debuggable builds introduce verification overhead on native calls (such as decryption routines), which can add several milliseconds of latency per frame on low-power tablet processors. Use `./gradlew assembleShrunk` to generate a minified release build signed with the debug key for testing.

A production release build (`assembleRelease`) is automatically signed when the environment variables `SCREENLET_KEYSTORE`, `SCREENLET_KEYSTORE_PASSWORD`, and optionally `SCREENLET_KEY_ALIAS` are configured. Dependencies are verified against `gradle/verification-metadata.xml`.

If a local JDK or Android SDK installation is not available, the project can be built inside a container:

```bash
DOCKER=podman tools/android-docker.sh testDebugUnitTest assembleDebug
```

Unit tests validate compatibility against shared protocol test vectors (`vectors.json`), verify the receive pipeline, and measure per-packet processing costs. Pushing a `v*` tag triggers a continuous integration release workflow that publishes the built APK.

### Initial Configuration

Upon launching the application for the first time, grant the requested notification permission (required for pairing verification) and enable "Display over other apps" (allowing the application to launch automatically when connected by the computer).

## Roadmap

- **Decoder optimization across diverse SoCs:** Mitigate decoder buffer queue inflation during high-motion scenes on non-Snapdragon and non-Exynos hardware (e.g., MediaTek and Unisoc chipsets, addressing frame pacing latency on devices such as the Bigme HiBreak Pro).
- **Dynamic panel refresh rate synchronization:** Programmatically switch the display mode and `SurfaceView` refresh rate (e.g., 60 Hz, 90 Hz, or 120 Hz) to match host stream capture framerates, eliminating micro-stutters and vsync phase drift.
- **Stylus motion prediction:** Integrate the Android 14+ `MotionPredictor` API to calculate predictive coordinate vectors, minimizing pen-to-ink latency during stylus drawing and note-taking.
- **Universal stylus compatibility:** Broaden digitizer report parsing beyond Samsung S Pen vendor extensions to support Universal Stylus Initiative (USI) active pens and standard Android pointer tilt/pressure APIs.
- **Resilient packet transport:** Implement MTU detection and telemetry for network environments that drop IP fragments of 16 KiB UDP datagrams, signaling hosts to fall back to unfragmented packets.
- **Erasure coding loss recovery:** Investigate Forward Error Correction (FEC) schemes to reconstruct isolated dropped packets without generating full IDR keyframe burst requests.
- **Low-latency audio sink:** Implement high-priority synchronized audio streaming over datagram transport using AAudio or Oboe for tablet speaker and headphone output.
- **KDE Plasma compatibility:** Validate connection negotiation and pairing workflows with a future Linux host implementation for KDE Plasma.

## License

Distributed under the [MIT License](LICENSE), matching the upstream Plus One project.

## AI Disclosure

Much of this codebase was generated with the assistance of Anthropic's Claude model through Claude Code, under direct human architectural guidance, specification, and hardware verification.
