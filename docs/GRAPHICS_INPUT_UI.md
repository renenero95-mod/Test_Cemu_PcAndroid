# Graphics, Input, and Android UI

## 1. App sections

Keep these sections visually distinct:
- Library / Source Games
- Conversion Jobs
- Converted Games
- Diagnostics
- Settings

The user launches a converted result only from **Converted Games**. The source image/listing must not look like a ready-to-play Android port.

## 2. Converted game page

Show:
- game title and package ID;
- conversion status;
- FCV result;
- known limitations;
- last manual test annotation;
- launch button;
- graphics/profile settings;
- controls settings;
- report download.

## 3. Graphics settings

Expose only controls actually supported by the renderer/title:
- internal resolution scale;
- texture quality or mip bias if implemented;
- shadow mode/quality if controllable;
- lighting/effect quality if controllable;
- supported post-processing/AA;
- frame cap and VSync.

Presets:
- Original
- Quality
- Balanced
- Performance
- Battery Saver
- Custom

Keep baseline assets immutable. Derived assets and shader variants belong in versioned caches. Profiles must be reversible and per-game.

## 4. Touch controls

Support configurable virtual D-pad, analog sticks, face buttons, shoulder buttons, triggers, start/back. Allow position, size, opacity, and visibility changes. Save layouts per game.

## 5. External controllers

Use Android's supported input APIs. Map controls per game and expose remapping where possible. Test reconnect, focus changes, and simultaneous touch/controller input.

## 6. FPS and performance overlay

Provide:
- FPS;
- frame time;
- optional CPU/runtime/fallback counters when measured reliably.

Clearly document measurement method. Do not display fabricated GPU utilization or temperature. Overlay must be optional and low overhead.

## 7. POCO F6 Pro reference

Use the POCO F6 Pro as a test device, but query runtime capabilities. Do not hard-code Snapdragon/Adreno assumptions into the general app. Include thermal safeguards and adjustable render scale.
