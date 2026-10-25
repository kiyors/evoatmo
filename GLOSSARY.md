---
id: glossary
aliases: ["EvoAtmo Glossary"]
tags: [android, rom, evo-atmo]
---

# EvoAtmo Glossary

Vocabulary for the EvoAtmo workbench — the Nothing-style Atmosphere work on a Nothing Phone (2) running Evolution X. Terms here are project-specific; implementation detail lives in the docs suite, not here.

## Language

**Atmosphere unlock effect**:
The Nothing-OS-style animated wallpaper change played at unlock — animated Gaussian blur plus an AGSL color-mesh morph, rendered on the live wallpaper surface by the engine.
_Avoid_: wallpaper blur, atmosphere blur (that confusion names a different, builtin Evolution X feature)

**Selection surface**:
The one place where the Atmosphere unlock effect is enabled, disabled, and tuned. Locked (2026-10-25) to be the module screen; not a picker entry, not an Evolver panel.
_Avoid_: entry point, control surface, config page

**Module screen**:
The Atmosphere app screen owned by the Xposed module APK — also the project's selection surface. It lives with the module app, never inside another app's UI.
_Avoid_: in-app settings, module UI

**Wallpaper & style**:
The system's wallpaper-choice application on this device (Google's prebuilt picker on the GMS build path). It chooses wallpapers; it does not host the Atmosphere selection.
_Avoid_: ThemePicker, wallpaper picker (ambiguous around the fork)

**Wallpaper hub**:
The Settings → Wallpaper page that dynamically lists every installed app answering the system set-wallpaper action — how the module screen appears among system entries without any hook.
_Avoid_: wallpaper settings (collides with the Evolver wallpaper section)

**EvoX wallpaper blur**:
Evolution X's own builtin wallpaper blur feature (Stream-A board: FIX-001) — deliberately unrelated to the Atmosphere unlock effect; the two stay independent when both are configured.
_Avoid_: the blur, blur effect

**Engine**:
The pure-SDK library that renders the morph on the wallpaper surface; its host (either the Stage-1 Xposed module or a future in-tree integration) drives it, and it never learns which host.
_Avoid_: shader, renderer
