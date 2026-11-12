# To test before the release

On a tablet, with the release-signed APK. A tablet that has an earlier development build
(0.1.0) needs it uninstalled first, which forgets its pairings.

Changed after the first-release review, checked only as far as said:

- [ ] **Cable input.** Datagrams to the computer now leave on their own thread (`UsbTunnel`).
      Draw with the pen and touch over the cable, with each desktop app; unplug in the middle
      of a stroke. Then stop the desktop app from reading (suspend its process) and touch:
      the tablet's screen must stay responsive. Not run on a tablet yet.
- [ ] **Accessory that cannot be opened.** Unplug the cable while Android is still starting
      the app for it; start `UsbActivity` from adb with no accessory. The service must log
      "the accessory could not be opened" and go on. Not run on a tablet yet.
- [ ] **Wi-Fi Direct only while in use.** With Wi-Fi off and no cable, open the app: the
      `DIRECT-xx-Screenlet` network appears; it goes 10 to 11 minutes after the app was last
      opened or a computer left, and stays during a session. With "Always ready" on, restart
      the tablet and do not open the app: no such network. A Linux or Windows computer joins
      it within those minutes. Not run on a tablet yet.
- [ ] **Keyframe requests back off.** On a weak Wi-Fi network, the requests go from four a
      second to one every two seconds while no keyframe arrives whole, and the picture comes
      back by itself. Not run on a tablet yet.
- [x] **Handshake slots.** 60 idle TCP connections from one address: another address
      connects at once, the first again after 3 s (Tab S6 Lite). Again on the release APK.
- [x] **Oversized frame.** `ProtocolTest.aFrameLargerThanTheDecoderTakesIsRefused`; live, a
      paired computer announcing 65535 packets of 9000 bytes must not end the app.
- [ ] **Release workflow.** A `v*` tag with no `SCREENLET_KEYSTORE_BASE64` secret fails
      and publishes nothing; with it, the APK installs over the previous release.

Never covered by the review:

- [ ] A whole session of the R8 build against each desktop app, cable and Wi-Fi.
- [ ] Pen pressure, tilt, hover and the eraser; two-finger scroll; palm rejection.
- [ ] The pairing question over another app, on the lock screen, and with the tablet rotated.
- [ ] A second tablet model (the review had a Galaxy Tab S6 Lite only).
