# AuraPlayer

**AuraPlayer** is a high-performance **cross-platform desktop media playback engine written in Kotlin**.  
It uses **JNI and JAWT** to bridge the JVM with native GPU-accelerated rendering, allowing video frames to be drawn directly to desktop UI surfaces.

AuraPlayer supports **both audio and video playback** and is designed to be embedded into JVM desktop applications while maintaining **hardware-accelerated performance**.

The project was created to fill a gap in the JVM ecosystem: there was no modern desktop media engine comparable in capability to players like ExoPlayer while still integrating cleanly with desktop UI frameworks.

---

# Features

## GPU-Accelerated Video Playback

- Hardware-accelerated decoding
- GPU rendering pipeline
- Smooth high-resolution playback
- Reduced CPU usage

## JNI Native Bridge

AuraPlayer uses **JNI** to connect the Kotlin/JVM API with a native media backend.

This bridge handles:

- Media decoding
- Frame rendering
- Surface communication
- Performance-critical operations

## JAWT Surface Rendering

AuraPlayer uses **JAWT (Java AWT Native Interface)** to obtain native drawing surfaces from Java UI components.

This enables:

- Rendering directly into `JPanel`
- Tight integration with desktop UIs
- No external windows or embedded players
- Smooth GPU-accelerated rendering

## Audio + Video Support

AuraPlayer supports:

- Video playback with synchronized audio
- Audio-only playback
- Most common media containers and codecs  
*(depending on native backend support)*

---

# Modules

AuraPlayer is split into **two modules** to allow flexibility depending on how you want to integrate it.

## `auraplayer-core`

The **core playback engine**.

This module contains:

- Media playback engine
- JNI bridge
- Native rendering integration
- Audio/video decoding pipeline

It **does not depend on any UI framework**, making it usable with:

- Swing
- JavaFX
- Compose Desktop
- LWJGL
- custom rendering environments

This is the module to use if you want **full control over UI integration**.

---

## `auraplayer-compose`

A **ready-to-use module for Compose Desktop applications**.

It includes:

- `auraplayer-core`
- Compose UI components
- media player controls
- video surface composables

This module lets you quickly build a media player UI using **Jetpack Compose for Desktop**.

---

# Architecture

