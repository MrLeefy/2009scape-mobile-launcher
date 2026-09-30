<h1 align="center">HD/SD Launcher - 2009Scape Mobile 2.4</h1>

<p align="center">
  <a href="https://github.com/2009scape/2009Scape-mobile/releases"><img src="https://i.imgur.com/7wtvknj.png" alt="2009scape Mobile Logo"/></a>
  <a href="https://github.com/2009scape/2009Scape-mobile/releases"><img src="https://i.imgur.com/o8OwASv.png" alt="2009scape Mobile splash"/></a>
  <a href="https://github.com/2009scape/2009Scape-mobile/releases"><img src="https://i.imgur.com/nSC3O2Q.png" alt="2009scape Mobile login screen"/></a>
  <a href="https://github.com/2009scape/2009Scape-mobile/releases"><img src="https://i.imgur.com/KUXPn6P.png" alt="2009scape Mobile play screen"/></a>
</p>

Unofficial, unsupported launcher for running 2009Scape on Android. Based on Pojav/Boardwalk.

## Install on Android

Download the single [SD + HD APK from the latest GitHub release](https://github.com/MrLeefy/2009scape-mobile-launcher/releases/latest) and install it on your Android phone. It includes the 2009Scape client, Java runtime, and bundled mobile plugins, and defaults to `play.2009scape.org`. Android may ask you to allow installs from your browser or file manager.

This install is based on upstream Mobile 2.4 (released June 9, 2024). It includes both SD and HD modes; it does not include later client or plugin updates. The Java 25 and touch/camera changes described in the repository's earlier description are not included.

## Source baseline

This repository contains the upstream Mobile 2.4 source snapshot plus this repository's Android build workflow. The app supports ARMv7, ARM64, x86, and x86_64 Android devices.

## Default touch controls

The bundled control layout is set up for 2009Scape: left and right click, Escape, Tab, the Android keyboard, virtual mouse, Shift, and a drag-click toggle are available on screen. The `CAM` drawer opens camera direction and zoom buttons. `GUI` hides or restores the overlay, and the layout can be changed in the control editor. An update refreshes the untouched stock layout and preserves customized layouts.

Touch gestures include camera panning, pinch zoom, two-finger scrolling, long-press right click, and a configurable stationary hold for left click. The touch settings describe these actions and can disable gesture input.

# Download
  
[All GitHub releases](https://github.com/MrLeefy/2009scape-mobile-launcher/releases)

<hr>

[Controls](https://github.com/2009scape/2009Scape-mobile/wiki/Controls)

Tip: Use [SwiftKey](https://play.google.com/store/apps/details?id=com.touchtype.swiftkey&hl=en_CA&gl=US) keyboard and disable battery optimization to get the best experience

Client Source
 - https://gitlab.com/downthecrop/rt4-client/-/tree/lwjgl-mobile-callbacks?ref_type=heads
