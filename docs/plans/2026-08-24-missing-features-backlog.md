# Missing-features backlog

Audit date: 2026-08-24. Common security-camera features not yet implemented,
ordered by effort/value. Vehicle + animal YOLO detectors were pulled from this
list and shipped same-day (see git history).

## Low effort

| Feature | Notes |
|---|---|
| Siren/alarm channel | Play a loud alarm sound on-device when a detector fires. New channel type in `channels/`; reuses `ChannelRegistry` wiring. No network needed — useful when the phone is stolen/offline. |
| Home-screen widget | Glance widget showing monitoring state + last event thumbnail. Data already flows through Room; just needs the widget UI. |

## Medium effort

| Feature | Notes |
|---|---|
| Loitering alert | Person present > N seconds inside an inclusion region. Extend `PersonDetector` with per-region dwell timers reset when boxes leave. |
| Continuous recording mode | Today only event clips are written (`VideoClipRecorder`). Needs a rolling segment writer (e.g. 5-min files) plus storage-pressure eviction independent of `retentionDays`. |
| Motion heatmap / activity stats | Event data is already in Room with timestamps + trigger types. Aggregate into an hourly/day-of-week view or frame-overlay heatmap. |
| Two-way audio (talk-back) | `LiveViewServer` currently sends RTP one way. Would need a receive path (RTSP RECORD or back-channel) + `AudioTrack` playback of client PCM. |
| Cloud clip backup | Auto-upload event clips/snapshots to WebDAV/S3/Drive after export. Natural extension of the webhook channel; credentials via `SecretStore`. |

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
- Animal detector (`animal`) — COCO bird/horse/sheep/cow via shared YOLO26n
  (cat/dog keep their own dedicated detectors to avoid double-firing).
