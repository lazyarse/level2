# Live View — RTSP Server + Push Stream

**GOAL:** Allow users to live-stream their camera feed via RTSP, either as an on-device server (local network / port-forwarded remote) or by pushing to the user's own relay server. Includes authentication, configurable quality, and audio.

**ARCHITECTURE:** Pure Kotlin RTSP server + RTP packetizer. MediaCodec H.264 encoder fed from CameraX frames. Push mode: RTSP ANNOUNCE/RECORD client. Stream runs only while monitoring is active. New "Live View" collapsible section in Settings.

## Requirements

| Requirement | Details |
|---|---|
| Streaming protocol | RTSP (RTP over UDP, TCP interleaved fallback) |
| Server modes | On-device server + push to relay (user's own) |
| Quality | User-configurable: resolution (480p/720p/1080p), FPS (5-30) |
| Authentication | Username + password (required, stored via SecretStore) |
| Audio | User-configurable toggle (AAC from existing MicCapture PCM) |
| Monitoring dependency | Stream only active while monitoring is active |
| Settings UI | New "Live View" CollapsibleSection between Video clips and Events |
| Dependencies | Zero external — pure Kotlin implementation |

## Files Modified

| File | Changes |
|---|---|
| `core/Settings.kt` | Add `LiveViewSettings` data class, add `liveView` field to `AppSettings` |
| `storage/SettingsStore.kt` | Add `liveView` to JSON serialization; route password through SecretStore |
| `ui/settings/SettingsScreen.kt` | New `CollapsibleSection("Live View")` with all controls |
| `camera_service/LiveViewServer.kt` | **NEW** — RTSP TCP listener, DESCRIBE/SETUP/PLAY/TEARDOWN handler |
| `camera_service/RtpPacketizer.kt` | **NEW** — H.264 NAL to RTP packetizer (FU-A fragmentation) |
| `camera_service/LiveViewEncoder.kt` | **NEW** — MediaCodec H.264 encoder wrapper, AAC encoder for audio |
| `camera_service/LiveViewPushClient.kt` | **NEW** — RTSP ANNOUNCE/RECORD push to relay |
| `camera_service/MonitoringService.kt` | Start/stop `LiveViewServer` when monitoring starts/stops |
| `monitor/MonitorViewModel.kt` | Expose `liveViewActive` state for UI indicator |

## Design

### 1. Data Model

`LiveViewSettings` data class:

```kotlin
data class LiveViewSettings(
    val enabled: Boolean = false,
    val mode: String = "server",       // "server" | "push"
    val port: Int = 554,
    val username: String = "",
    val password: String = "",          // routed through SecretStore
    val relayUrl: String = "",
    val resolution: String = "720p",    // "480p" | "720p" | "1080p"
    val fps: Int = 15,
    val audioEnabled: Boolean = true,
)
```

Resolution mapping: `480p` → 854×480, `720p` → 1280×720, `1080p` → 1920×1080.

`AppSettings` gains `val liveView: LiveViewSettings = LiveViewSettings()`.

### 2. Settings UI

New `CollapsibleSection("Live View")` between Video clips and Events with rows for:

- Master toggle switch
- Mode segmented button (Server / Push)
- Port number field (server only, default 554)
- Auth switch (server only)
- Username text field (shown when auth enabled)
- Password text field with visual transform (routed through SecretStore)
- Relay URL text field (push only)
- Resolution dropdown (480p / 720p / 1080p)
- FPS slider (5–30 step 1)
- Audio switch

Summary line: `"off"` / `"server :554 auth"` / `"push -> relay.example.com"`

### 3. RTSP Server (LiveViewServer)

Minimal RTSP/1.0 server over TCP. Single-session (one viewer at a time).

**Protocol flow:**

1. Client `DESCRIBE` → Server 200 OK with SDP
2. Client `SETUP trackID=0` → Server 200 OK with Transport
3. Client `SETUP trackID=1` (audio) → Server 200 OK
4. Client `PLAY` → Server 200 OK → RTP data flows
5. Client `TEARDOWN` → Server 200 OK

**SDP:**

```
v=0
o=- <session-id> IN IP4 <local-ip>
s=Live View
t=0 0
m=video 0 RTP/AVP 96
a=rtpmap:96 H264/90000
a=fmtp:96 profile-level-id=<SPS profile>; sprop-parameter-sets=<SPS>,<PPS>
a=control:trackID=0
m=audio 0 RTP/AVP 97
a=rtpmap:97 MPEG4-GENERIC/16000/1
a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; sizelength=13; indexlength=3; indexdeltalength=3
a=control:trackID=1
```

**Authentication:** HTTP Basic auth on DESCRIBE/SETUP/PLAY. Return `401` + `WWW-Authenticate` header on failure.

**TCP interleaved RTP:** When client requests `RTP/AVP/TCP` interleaved, send RTP over RTSP TCP connection using dollar-sign (`$`) framing.

### 4. RTP Packetizer (RtpPacketizer)

H.264 RTP payload per RFC 6184:

- **Single NAL:** if NAL ≤ MTU (1400 bytes), send as single RTP packet
- **FU-A fragmentation:** if NAL > MTU, split into FU-A packets with Start/End flags
- **SPS/PPS:** sent as STAP-A packet before first I-frame or on first PLAY
- **Sequence numbers:** increment per packet per RFC 3550
- **Timestamps:** 90kHz clock, one frame = `90000/fps` timestamp increment

### 5. Encoder (LiveViewEncoder)

**Video — MediaCodec H.264:**

- `createEncoderByType(MIMETYPE_VIDEO_AVC)`
- Target resolution, bitrate ~2–4 Mbps (720p) or ~4–8 Mbps (1080p)
- `COLOR_FormatSurface` input
- `BITRATE_MODE_CBR`, `KEY_FRAME_RATE` = target fps, `KEY_I_FRAME_INTERVAL` = 2 seconds
- Extract SPS/PPS from `BUFFER_FLAG_CODEC_CONFIG` output buffer

**Audio — MediaCodec AAC:**

- `createEncoderByType(MIMETYPE_AUDIO_AAC)`
- `audio/mp4a-latm` 16kHz mono ~64kbps
- Input from MicCapture PCM (16kHz mono s16le)
- AAC frames packetized into RTP with AU headers per RFC 3640

### 6. Push Client (LiveViewPushClient)

RTSP ANNOUNCE/RECORD push:

1. `ANNOUNCE` with SDP body → 200 OK
2. `SETUP trackID=0` → 200 OK with ports
3. `RECORD` → 200 OK
4. RTP data flows
5. `TEARDOWN`

Uses same `RtpPacketizer`. Sends RTP via UDP to negotiated ports. Relay URL from settings.

### 7. Integration with MonitoringService

**On monitoring start:** if `liveView.enabled` and `mode="server"`, create `LiveViewServer` on port; if `mode="push"`, create `LiveViewPushClient` to relay URL. `LiveViewEncoder` starts MediaCodec. Encoded frames distributed to active output.

**On monitoring stop:** server sends TEARDOWN, stops accepting; push sends TEARDOWN, disconnects; encoder stops and releases.

### 8. Frame Distribution

`LiveViewEncoder` produces encoded H.264 NAL units and AAC frames via `LiveViewOutput` interface:

```kotlin
interface LiveViewOutput {
    fun onVideoFrame(nalUnits: List<ByteArray>, timestampUs: Long, isKeyFrame: Boolean)
    fun onAudioFrame(aacFrame: ByteArray, timestampUs: Long)
}
```

Both `LiveViewServer` and `LiveViewPushClient` implement `LiveViewOutput`.

### 9. Testing

- **RtpPacketizerTest** (JVM unit): single NAL, FU-A fragmentation, sequence numbers, timestamps
- **LiveViewEncoderTest** (JVM unit/Robolectric): encoder config, SPS/PPS extraction, start/stop lifecycle
- **LiveViewServerTest** (JVM unit/Robolectric): RTSP request parsing, auth, SDP generation
- **LiveViewSettingsTest** (JVM unit): serialization roundtrip, SecretStore password routing
- **SettingsScreenTest** (JVM unit/Robolectric): Live View section renders, toggle interactions
- **LiveViewIntegrationTest** (Instrumented): monitoring start → server starts → VLC connects → receives stream

### 10. Task Breakdown

| # | Task | Scope |
|---|---|---|
| 1 | Data Model + Settings | `LiveViewSettings` class, `AppSettings` field, `SettingsStore` serialization, unit tests |
| 2 | Settings UI | `CollapsibleSection`, all controls, summary line, test updates |
| 3 | RTP Packetizer | Single NAL, FU-A, STAP-A, sequence numbers, timestamps, unit tests |
| 4 | MediaCodec Encoder | H.264 + AAC, Surface input, SPS/PPS extraction, lifecycle |
| 5 | RTSP Server | TCP listener, request parser, SDP generation, auth, UDP+TCP interleaved, single-session |
| 6 | Push Client | ANNOUNCE/RECORD, UDP output, TEARDOWN |
| 7 | Service Integration | Wire into `MonitoringServiceController`, start/stop, `MonitorViewModel` state |
| 8 | Testing + Verification | Full test suite, debug APK, emulator install, VLC connect test |
