# AuraPlayer

**AuraPlayer** is a high-performance **cross-platform desktop media playback engine written in Kotlin** that uses **JNI and JAWT** to bridge the JVM with native GPU-accelerated rendering.

It allows **hardware-accelerated audio and video playback directly inside Java/Swing components** by rendering frames to a native drawing surface obtained through the **Java AWT Native Interface (JAWT)**.

AuraPlayer was created to address a gap in the JVM ecosystem: there was no modern desktop media engine comparable in capability to mobile players like ExoPlayer while still integrating cleanly with traditional desktop UI frameworks.

---

# Features

## GPU-Accelerated Video Rendering

- Hardware-accelerated decoding
- GPU frame rendering
- Smooth high-resolution playback
- Reduced CPU overhead

## JNI Native Bridge

AuraPlayer uses **JNI** to communicate between:

- Kotlin/JVM playback API
- Native media pipeline
- GPU rendering backend

This architecture enables native performance while keeping a clean Kotlin API.

## Direct JAWT Surface Rendering

Using **JAWT**, AuraPlayer can:

- Access the **native drawing surface of AWT/Swing components**
- Render video frames directly to `Nativee Drawing Surface`
- Avoid heavyweight window embedding or external players
- Maintain seamless integration with existing Java desktop apps

## Audio + Video Playback

Supports both:

- Video playback with synchronized audio
- Audio-only media playback
- Most common media container formats

*(Exact format support depends on the native codec layer used.)*

---

# Architecture

AuraPlayer is structured as three main layers:
