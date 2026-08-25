# Privacy Masking in Recordings — Design

Date: 2026-08-25
Status: Ready for implementation

## Goal

Mask (obscure) user-defined privacy/exclusion zones in exported video clips so that
sensitive areas are not visible in saved footage. The user chooses the masking effect
(solid dark, pixelation, or Gaussian blur) and toggles it independently of the
detection-level exclusion zones.

## Current state

- **Exclusion zones for detection** are fully shipped: `AppSettings.exclusionRegions`,
  `RegionFilter.pixelMaskExcluding`, editor mode toggle, overlay in red, all detectors
  honour them.
- **Clip export** (`VideoClipRecorder.completeExport`) concatenates segments, optionally
  stamps a timestamp via `ClipStamper` (Media3 `BitmapOverlay` + `OverlayEffect`), and
  writes to MediaStore. No region awareness.
- **No blur/pixelate/mask** functionality exists anywhere in the codebase.
- `BitmapOverlay.getBitmap(presentationUs)` returns a blank full-frame `Bitmap` — the
  overlay is composited **on top of** the video, not blended with it. This means solid
  overlays are trivial; pixelation/blur require access to the source video pixels.

## Design decisions

1. **Scope**: video clips only (snapshots and live RTSP deferred).
2. **Control**: explicit toggle (`privacyMasking`) + effect dropdown (`privacyMaskEffect`)
   in the "Video clips" settings section, next to the timestamp stamp controls.
3. **Effects**: three options — `solid` (semi-transparent dark), `pixelate` (mosaic blocks),
   `blur` (Gaussian). All three draw onto a full-frame `Bitmap` in `getBitmap()`.
4. **Geometry**: exclusion region coordinates are normalised (0..1) in the upright/display
   space. The exported clip may carry rotation metadata; the overlay draws in pre-rotation
   pixel space, so region coordinates must be **rotated by the clip's inverse rotation**
   before drawing (pure geometry function, unit-testable). Rect regions → `Canvas.drawRect`;
   poly regions → `Canvas.drawPath`.
5. **Chaining**: when both privacy masking and timestamp are enabled, both overlays are
   composed in a single `OverlayEffect(ImmutableList.of(privacy, stamp))` — one GL pass.
6. **Fallback**: if overlay generation fails, fall back to the unstamped/unmasked clip.
   Evidence-first: we never lose the recording; a warning is logged. This is consistent
   with the timestamp stamp fallback policy.

## Architecture

### Rotation handling

Region coordinates are normalised in the upright/display space. Video frames are stored
pre-rotation. The overlay must rotate region points before drawing:

| Clip rotation | Transform applied to normalised (x, y) |
|---------------|----------------------------------------|
| 0°            | (x, y) — no change                    |
| 90°           | (y, 1 − x) — rotate CW                |
| 180°          | (1 − x, 1 − y) — flip both axes      |
| 270°          | (1 − y, x) — rotate CCW               |

`PrivacyMaskOverlay` receives the clip rotation (from `videoSize()` in `ClipStamper`)
and applies this transform before mapping normalised coords to pixel positions.

### Effect rendering

All three effects draw onto an identical full-frame `Bitmap` (`frameWidth × frameHeight`,
`ARGB_8888`) returned by `getBitmap(presentationUs)`.

| Effect | How it works |
|--------|-------------|
| `solid` | Semi-transparent dark fill (`Color.argb(180, 0, 0, 0)`) inside each exclusion region. Rects via `drawRect`, polys via `drawPath`. No source pixels needed. |
| `pixelate` | For each exclusion region, sample the source video region, down-sample to ~8×8 blocks, draw scaled-up blocks. Requires source video pixels → **decode pass** (see below). |
| `blur` | For each exclusion region, apply a Gaussian blur kernel (radius ~15px) to the source video region, draw the blurred patch. Requires source video pixels → **decode pass**. |

### Source pixel access (pixelate + blur)

`BitmapOverlay` does not provide the source video frame. To read source pixels:

1. Before the `Transformer` export pass, decode the input file's H.264 frames via
   `MediaCodec` + `ImageReader` at quarter resolution.
2. Store the decoded frames in a ring buffer keyed by presentation timestamp.
3. `getBitmap(presentationUs)` looks up the nearest decoded frame, applies the effect
   to exclusion zones, and returns the result.
4. The decode pass runs on a background thread; the overlay runs on the GL thread.

This adds ~1× decode + 1× re-encode (via Transformer) to the export cost. On a modern
phone this is acceptable for event clips (typically 10–30 s).

**Performance note:** The solid effect has zero additional cost beyond the overlay itself.
Pixelate and blur add the decode pass. The UI should indicate this (e.g., "may be slower"
next to the pixelate/blur options).

### Data flow

The existing pattern for clip settings flows through intent extras: MonitorViewModel →
`MonitoringService.start()` → intent extras → `onStart()` → `VideoClipRecorder.configure()`.
Privacy masking follows the same route.

```
MonitorViewModel.startMonitoring lambda
  └── MonitoringService.start(..., privacyMasking, privacyMaskEffect, privacyExclusionsJson)

MonitoringService.start()
  └── intent.putExtra(EXTRA_PRIVACY_MASKING, privacyMasking)
      intent.putExtra(EXTRA_PRIVACY_EFFECT, privacyMaskEffect)
      intent.putExtra(EXTRA_PRIVACY_EXCLUSIONS_JSON, exclusionsJson)

MonitoringServiceController.onStart()
  └── reads extras → passes to VideoClipRecorder.configure()

VideoClipRecorder.configure()
  └── stores privacyMasking, privacyMaskEffect, exclusionRegions (parsed from JSON)

VideoClipRecorder.completeExport()
  └── if (privacyMasking && exclusionRegions.isNotEmpty()):
        ClipStamper.stamp(
          ..., exclusionRegions, privacyMaskEffect, clipRotation,
          startWallMs = exportTriggerMs - segmentMs,
        )

ClipStamper.stamp()
  └── reads clip rotation via videoSize()
      builds PrivacyMaskOverlay (when exclusionRegions non-empty)
      builds StampOverlay (when clipTimestamp enabled)
      composes: Effects(emptyList(), listOf(OverlayEffect(ImmutableList.of(privacy, stamp))))
```

### Settings UI

In the "Video clips" section of `SettingsScreen.kt`:

```
Video clips
  ☐ Record video clips
  ☐ Burn date/time stamp           [position ▼]  ☐ Include camera name
  ☐ Privacy mask in recording      [effect ▼]    ← NEW
```

The effect dropdown shows: "Solid dark" / "Pixelate" / "Blur". Only visible when
`privacyMasking` is on.

## New/modified files

| File | Change |
|------|--------|
| `core/Settings.kt` | Add `privacyMasking`, `privacyMaskEffect` fields + `copyWith`, `toJson`, `fromJson` |
| `camera_service/PrivacyMaskOverlay.kt` | **NEW** — `BitmapOverlay` subclass: rotation-aware mapping, draws solid/pixelate/blur inside exclusion zones |
| `camera_service/ClipStamper.kt` | Read clip rotation from `videoSize()`; accept `exclusionRegions` + `privacyMaskEffect`; build `PrivacyMaskOverlay`; chain with `StampOverlay` |
| `camera_service/VideoClipRecorder.kt` | Store `privacyMasking`, `privacyMaskEffect`, `exclusionRegions`; pass to `ClipStamper` in `completeExport()` |
| `camera_service/MonitoringService.kt` | Add `EXTRA_PRIVACY_*` constants; read/write extras; pass new params through `configure()` |
| `monitor/MonitorViewModel.kt` | Extend `startMonitoring` lambda with privacy params; pass from settings |
| `ui/settings/SettingsScreen.kt` | Add toggle + dropdown in "Video clips" section |
| `test/.../PrivacyMaskOverlayTest.kt` | **NEW** — rotation geometry, per-effect rendering, settings round-trip |
| `test/.../SettingsTest.kt` | Round-trip tests for new fields |

## Verification

1. Unit tests: `PrivacyMaskOverlay` rotation mapping for all four angles;
   solid-effect rendering (pixel check); settings round-trip; empty exclusion
   regions → no overlay created.
2. Manual: define exclusion zones, enable privacy masking, trigger motion event,
   verify exported clip has dark regions over exclusion zones.
3. Verify fallback: corrupt the overlay mid-export → unstamped clip stored (log).
4. Performance: export time with solid overlay ≈ unstamped; pixelate/blur adds
   ~1× decode time.
