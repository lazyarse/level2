# Region Editor Live Preview Fix — Design

Date: 2026-08-24
Status: Draft (small fix)

## Goal

When drawing detection regions from Settings, the editor canvas must show the
**live camera image** so regions land on real-world features. Today it shows a
black rectangle unless monitoring/preview is already running.

## Root cause (verified from code, 2026-08-24)

- `RegionEditorScreen` already renders `PreviewSurface` when
  `showPreview = true` (`ui/regions/RegionEditorScreen.kt:294-305`) — the UI
  half is done.
- But nothing starts a camera session for the editor. `SettingsViewModel`'s
  `startCameraSession` seam is only invoked by face enrollment
  (`SettingsViewModel.kt:240`); `SecurityCamApp` just flips
  `showRegionEditor = true` (`SecurityCamApp.kt:106`). `PreviewSurface`
  registers a surface provider that no active CameraX `Preview` use case ever
  receives → black canvas.

## Design

Mirror the enrollment session lifecycle exactly:

1. **`SettingsViewModel`**: add

```kotlin
private var regionPreviewSessionLocal = false

fun beginRegionPreview() {
    if (cameraActive()) return                    // monitoring/preview already running
    val draft = _draft.value ?: return
    startCameraSession(draft.cameraId)
    regionPreviewSessionLocal = true
}

fun endRegionPreview() {
    if (!regionPreviewSessionLocal) return        // someone else owns the session
    regionPreviewSessionLocal = false
    stopCameraSession()
}
```

2. **`SecurityCamApp`**: on entering `showRegionEditor` call
   `settingsViewModel.beginRegionPreview()`; in `onClose` (and `onSave` path)
   always call `endRegionPreview()`. Also handle process-death restore:
   if recomposed with `showRegionEditor == true` after recreation,
   `beginRegionPreview()` is idempotent via the `cameraActive()` guard.

3. **Permissions**: preview-only start already no-ops safely without CAMERA
   (`MonitoringService.startPreviewOnly` exits and stops the service); the
   settings screen is only reachable after grant in practice — no new gate.

4. **Follow-up accuracy note (separate small change):** taps currently map
   viewport → normalized directly while `PreviewSurface` uses
   `ScaleType.FILL_CENTER`, so when preview aspect ≠ canvas aspect drawn regions
   drift from what's on screen. Proper fix: letterbox-aware mapping using the
   analysis resolution (same math as `letterboxInfo`). Deferred so this fix
   stays minimal; logged here so it isn't lost.

## Verification

- Robolectric: ViewModel test — `beginRegionPreview` with inactive camera calls
  `startCameraSession(cameraId)`; second call no-ops; `endRegionPreview` stops
  only when we started; with `cameraActive()=true` neither touches the session.
- Manual: Settings → Detection regions from cold app → live image visible;
  draw region over a known object → Done → verify saved coordinates match;
  repeat while monitoring is running (session must survive editor close).

## Risks

- Minimal. Reuses the proven enrollment pattern; the only subtlety is session
  ownership, handled by `regionPreviewSessionLocal`.
