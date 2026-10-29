<p align="center"><img src="docs/icon.png" width="128" alt="Screenlet icon"></p>

<h1 align="center">Screenlet companion</h1>

<p align="center">Your Android tablet as a second screen for your computer,<br>
with touch and pen, over a USB cable first and Wi-Fi when there is none.</p>

---

This is the tablet's half of Screenlet. The computer's half is
[Screenlet for macOS](https://github.com/DavideLoconte/Screenlet-macos) or
[Screenlet for GNOME](https://github.com/DavideLoconte/Screenlet-gnome).
Windows and KDE will follow.

## What it does

- **Extend or mirror** the computer's desktop on the tablet, at the panel's
  native resolution and its fastest refresh rate.
- **Touch and pen.** Fingers reach the computer as touches. The S Pen (or any
  stylus) reaches it as a pen with pressure, tilt, hover and its button.
- **Cable first.** Plug in USB-C: no developer options, no tethering
  (Android Open Accessory). On the same Wi-Fi it works without the cable. With
  no network at all, the tablet runs its own Wi-Fi Direct group and the
  computer joins it.
- **Nothing to set.** Install it, allow what it asks for once, and pair from
  the computer by comparing a six-digit code. After that the computer's
  screen opens on the tablet when you start it on the computer.

## Latency

Everything between the network and the screen is built for latency:

- Packets are decrypted into buffers the app keeps, and each video packet
  is copied once, straight to its place in the frame. Nothing is allocated
  per packet.
- A frame goes to the hardware decoder the moment it is whole, configured
  for low latency (Android's flag, the vendors' own, and the highest
  operating rate). It is shown at the next vsync, with no jitter buffer.
- The panel runs at its fastest refresh while the desktop is on screen.
- Receiving, decoding and the cable run on threads at display priority.
- Touch and pen events go out as the digitizer reports them (unbuffered
  dispatch), straight from the UI thread, with no allocation and no thread hop.
- Wi-Fi power saving is off while the desktop is shown.

Each second the app measures five stages of every frame: capture to
decoded, capture to first packet, first to last packet, waiting for the
decoder, and decoding. It sends them to the computer, which logs them with
its own. See [PROTOCOL.md](PROTOCOL.md) §3.5.

## Install

Download `screenlet-companion-<version>.apk` from the
[Releases](../../releases) page and open it on the tablet. Android 13 or later.

## Build and test

Gradle, the JDK and the Android SDK all live in a container:

```bash
tools/android-docker.sh testDebugUnitTest lint assembleDebug   # DOCKER=podman to use podman
```

The unit tests check the shared protocol vectors (`vectors.json`) and each
step of the receive path. One of them prints the cost per packet of
decrypting and reassembling.

## License

MIT. Screenlet started as a fork of
[Plus One](https://github.com/GabrieleSantangelo/PlusOne) by Gabriele Santangelo.
