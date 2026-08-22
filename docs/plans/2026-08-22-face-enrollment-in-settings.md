# Face Enrollment in Settings Screen

**Goal:** Move face enrollment from Monitor screen to a "Face Recognition" collapsible section in Settings with enrolled-face list/delete and a dedicated camera enrollment screen with face guide overlay.

**Architecture:** Existing `FaceEnrollmentCoordinator` + `KnownFaceStore` stay unchanged. Settings gets a new `CollapsibleSection("Face Recognition")` with the enable switch, enrolled faces list with per-face delete, and an "Add" button that opens a name dialog then a fullscreen enrollment overlay. The enrollment overlay uses `PreviewSurface` (shared with monitoring service's CameraX session) + face guide + `FaceEnrollmentCoordinator.busFinder`. Monitoring must be active for enrollment. Monitor screen's inline enrollment row is removed.

**Tech Stack:** Jetpack Compose, CameraX PreviewView (shared with monitoring service), MediaPipe face detection (existing), FaceEnrollmentCoordinator + KnownFaceStore (existing).

---

## Files Modified

| File | Change |
|------|--------|
| `ui/settings/SettingsViewModel.kt` | Add `startEnrollment(label)`, `deleteFace(face, store)`, enrollment state |
| `ui/settings/SettingsScreen.kt` | New "Face Recognition" CollapsibleSection; remove old face card from Detectors; enrollment overlay with PreviewSurface + face guide |
| `ui/monitor/MonitorScreen.kt` | Remove enrollment row from MonitorStatusBar |
| `monitor/MonitorViewModel.kt` | Remove `EnrollmentUi`, `startEnrollment()`, `enrollment` state, `enrollmentFactory` |
| `test/monitor/MonitorViewModelTest.kt` | Remove enrollment tests |
| `test/ui/settings/SettingsScreenTest.kt` | Update section assertions; add face list/delete tests |

---

## Task 1: Add enrollment + delete to SettingsViewModel

Modify `SettingsViewModel.kt`:

1. Add `enrollmentFactory: () -> FaceEnrollmentCoordinator? = { null }` constructor param
2. Add `_enrollment = MutableStateFlow<String?>(null)` / `enrollment: StateFlow<String?>` 
3. Add `startEnrollment(label)` — launches coroutine, calls `coordinator.enroll(label)`, surfaces result via `_message`
4. Add `deleteFace(face, store)` — calls `store.delete(face.id)`, removes from `_draft.value.knownFaces`
5. Update `Factory` to inject `FaceEnrollmentCoordinator` (same wiring as `MonitorViewModel`)
6. Add test for `deleteFace`

## Task 2: Face Recognition section in SettingsScreen

Modify `SettingsScreen.kt`:

1. Remove the face recognition Card (lines 182-209) from inside `CollapsibleSection("Detectors")`
2. Add new `CollapsibleSection("Face Recognition", summary = ...)` between Detectors and Channels
3. Inside: Switch for "Recognise known faces" (with 's'), enrolled faces LazyColumn with delete IconButton per row, "Add" FAB/Button
4. Add button opens `AlertDialog` for name input, then sets `showEnrollment = true`

## Task 3: Enrollment overlay with face guide

Add to `SettingsScreen.kt`:

1. When `showEnrollment == true` and monitoring active: fullscreen overlay with `PreviewSurface` + rounded rect face guide + enrollment status + cancel button
2. Face guide: `Canvas` drawing a rounded rectangle centered on screen, dimming outside
3. On overlay show: call `viewModel.startEnrollment(name)`
4. On enrollment complete (enrollment state returns to null): dismiss overlay, show snackbar
5. If monitoring not active: show snackbar "Start monitoring to enrol faces"

## Task 4: Remove enrollment from MonitorScreen/MonitorViewModel

1. In `MonitorStatusBar`: remove the OutlinedTextField ("Enroll face as"), Enroll Button, and enrollment status lines (lines 194-222)
2. In `MonitorScreen`: remove `enrollment` state collection and `onEnroll` parameter
3. In `MonitorViewModel`: remove `EnrollmentUi`, `_enrollment`, `enrollment`, `startEnrollment()`, `enrollmentFactory`
4. In `MonitorViewModelTest`: remove `startEnrollment_reportsDoneAndKeepsIdleElsewhere`, `startEnrollment_failureSurfacesMessage`, `FakeEnrollmentCoordinator`
5. In `SecurityCamApp.kt`: no changes needed (settingsViewModel is shared)

## Task 5: Update tests and verify

1. `SettingsScreenTest`: update `sectionsCollapsedByDefaultHideNestedFields` to check for `section_Face_Recognition`; add test for face list rendering; add test for delete button existence
2. `MonitorViewModelTest`: verify enrollment tests removed, remaining tests pass
3. Run full suite: expect 331+ tests, 0 failures
4. `assembleStaging`, install, visual verify on emulator
