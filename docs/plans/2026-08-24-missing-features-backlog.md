# Missing-features backlog

Audit date: 2026-08-24. Common security-camera features not yet implemented,
ordered by effort/value. Vehicle + animal YOLO detectors were pulled from this
list and shipped same-day (see git history).

## Designed, pending implementation (2026-08-24)

| Feature | Design doc |
|---|---|
| Offline alert outbox (queue notifications per event×channel, WorkManager-drained) | `2026-08-24-offline-alert-outbox-design.md` |
| Cloud backup of clips & snapshots (WebDAV + S3-compatible; Drive rejected for privacy) | `2026-08-24-cloud-backup-design.md` |
| Loitering detector (dwell-based person presence) | `2026-08-24-loitering-detector-design.md` |
| Region editor live preview fix | `2026-08-24-region-editor-preview-fix-design.md` |

## Low effort

| Feature | Notes |
|---|---|
| Siren/alarm channel | Play a loud alarm sound on-device when a detector fires. New channel type in `channels/`; reuses `ChannelRegistry` wiring. No network needed — useful when the phone is stolen/offline. |
| Home-screen widget | Glance widget showing monitoring state + last event thumbnail. Data already flows through Room; just needs the widget UI. |

## Medium effort

| Feature | Notes |
|---|---|
| Continuous recording mode | Today only event clips are written (`VideoClipRecorder`). Needs a rolling segment writer (e.g. 5-min files) plus storage-pressure eviction independent of `retentionDays`. |
| Motion heatmap / activity stats | Event data is already in Room with timestamps + trigger types. Aggregate into an hourly/day-of-week view or frame-overlay heatmap. |
| Two-way audio (talk-back) | `LiveViewServer` currently sends RTP one way. Would need a receive path (RTSP RECORD or back-channel) + `AudioTrack` playback of client PCM. |

*(Cloud backup, loitering, and the offline outbox moved to "Designed" above;
their original rows are covered by the design docs.)*

## High effort

| Feature | Notes |
|---|---|
| Line crossing / tripwire | Track person-box centers across frames and fire when a defined line is crossed in a set direction. Needs light tracking state; geometry lives alongside `RegionFilter`. |
| Package detection | No COCO class for parcels. Options: custom TFLite model, or heuristic (small static object near door region appearing while person leaves). |
| Privacy masking in recordings | Exclusion zones gate *detection* but saved clips/snapshots show everything. Blurring regions would need per-frame processing inside the encode path (`LiveViewEncoder`-style pipeline for clips). |

## Deliberate non-goals / notes

- **PTZ control**: only digital zoom exists by design (phone cameras have no motors).
- **SD-card storage**: `filesDir`/MediaStore covers current retention model.
- **Landscape/tablet layouts**: single-phone deployment target; Compose handles rotation acceptably today.

## Shipped since audit started

- Vehicle detector (`vehicle`) — COCO car/motorcycle/bus/truck via shared YOLO26n.
- Bird (`bird`) and livestock (`livestock`: cow/sheep/horse) detectors via shared
  YOLO26n; cat/dog are now **combined sight+sound detectors** (`HybridDetector`:
  YOLO boxes + YAMNet vocalisation scores under one toggle with separate
  visual/audio thresholds). The earlier standalone bark/growl/meow audio
  detectors and the combined `animal` visual detector were replaced by this
  design on 2026-08-24. Health detector renamed **Heartbeat** in the UI and
  grouped under a new System heading (Camera → Audio → Combined → System).
