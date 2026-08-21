# Native migration parity matrix

Every Dart unit test file in `test/*` (recorded in `docs/plans/port-parity-manifest.txt`)
gets a JVM twin under `level1/android/app/src/test/kotlin/io/securitycam/level1/`.
Every Flutter integration scenario in `integration_test/*` gets an instrumentation
twin under `level1/android/app/src/androidTest/kotlin/io/securitycam/level1/`
(Task 7.1). Phase/Task columns reference where each test's subject matter is ported
in `docs/plans/2026-08-20-native-kotlin-migration-plan.md`.

## Unit tests (37)

| Dart test | JVM twin test | Phase | Task | Status |
|---|---|---|---|---|
| `analysis_dispatcher_test.dart` | `AnalysisDispatcherTest.kt` | Phase 2 | Task 2.2 | [x] |
| `analysis_frame_test.dart` | `AnalysisFrameTest.kt` | Phase 2 | Task 2.1 | [x] |
| `android_camera_session_test.dart` | `AndroidCameraSessionTest.kt` (n/a — platform-channel bridge eliminated; CameraX `ImageProxy`→BGR covered by `MonitoringInstrumentedTest`) | Phase 1 | Task 1.1 | [x] |
| `audio_detectors_test.dart` | `AudioDetectorsTest.kt` | Phase 3 | Task 3.2 | [x] |
| `audio_source_factory_test.dart` | `AudioSourceFactoryTest.kt` (n/a — desktop ffmpeg/simulated source selection dropped; native app always uses `MicCapture`) | Phase 1 | Task 1.1 | [x] |
| `bgr_frame_assembler_test.dart` | `BgrFrameAssemblerTest.kt` (n/a — no chunked transport to reassemble in-process) | Phase 1 | Task 1.1 | [x] |
| `camera_source_factory_test.dart` | `CameraSourceFactoryTest.kt` (n/a — CameraX session lifecycle exercised by `MonitoringInstrumentedTest`) | Phase 1 | Task 1.1 | [x] |
| `camera_view_test.dart` | `CameraViewTest.kt` (n/a — PreviewView rendering via CameraX; gray→RGBA path dropped with the bridge; E2E preview covered by instrumentation) | Phase 1 | Task 1.2 | [x] |
| `detection_region_test.dart` | `DetectionRegionTest.kt` | Phase 1 | Task 1.3 | [x] |
| `detector_config_test.dart` | `DetectorConfigTest.kt` | Phase 2 | Task 2.1 | [x] |
| `detector_registry_test.dart` | `DetectorRegistryTest.kt` | Phase 2 | Task 2.1 | [x] |
| `email_channel_test.dart` | `EmailChannelTest.kt` | Phase 4 | Task 4.3 | [x] |
| `event_log_test.dart` | `EventStoreTest.kt` | Phase 4 | Task 4.2 | [x] |
| `event_pipeline_test.dart` | `EventPipelineTest.kt` | Phase 2 | Task 2.4 | [x] |
| `events_screen_test.dart` | `EventsScreenTest.kt` | Phase 5 | Task 5.3 | [x] |
| `face_detector_test.dart` | `FaceEngineTest.kt` | Phase 3 | Task 3.4 | [x] |
| `ffmpeg_args_test.dart` | `FfmpegArgsTest.kt` (n/a — desktop-only) | Phase 7 | Task 7.2 | [x] |
| `ffmpeg_audio_args_test.dart` | `FfmpegAudioArgsTest.kt` (n/a — desktop-only) | Phase 7 | Task 7.2 | [x] |
| `ffmpeg_live_test.dart` | `FfmpegLiveTest.kt` (n/a — desktop-only) | Phase 7 | Task 7.2 | [x] |
| `gray_frame_assembler_test.dart` | `GrayFrameAssemblerTest.kt` | Phase 2 | Task 2.3 | [x] |
| `media_naming_test.dart` | `MediaNamingTest.kt` | Phase 2 | Task 2.4 | [x] |
| `monitor_controller_test.dart` | `MonitorViewModelTest.kt` | Phase 1 | Task 1.5 | [x] |
| `motion_detector_test.dart` | `MotionDetectorTest.kt` | Phase 2 | Task 2.3 | [x] |
| `pcm_window_accumulator_test.dart` | `PcmWindowAccumulatorTest.kt` | Phase 3 | Task 3.2 | [x] |
| `person_detector_test.dart` | `PersonDetectorTest.kt` | Phase 3 | Task 3.3 | [x] |
| `pipeline_test.dart` | `DetectorPipelineTest.kt` | Phase 2 | Task 2.2 | [x] |
| `pushover_channel_test.dart` | `PushoverChannelTest.kt` | Phase 4 | Task 4.3 | [x] |
| `region_editor_screen_test.dart` | `RegionEditorScreenTest.kt` | Phase 5 | Task 5.2 | [x] (gesture paths covered by `RegionEditorViewModelTest`) |
| `region_filter_test.dart` | `RegionFilterTest.kt` | Phase 2 | Task 2.3 | [x] |
| `settings_screen_test.dart` | `SettingsScreenTest.kt` | Phase 5 | Task 5.1 | [x] |
| `settings_store_test.dart` | `SettingsStoreTest.kt` | Phase 4 | Task 4.1 | [x] |
| `settings_test.dart` | `SettingsTest.kt` | Phase 2 | Task 2.1 | [x] |
| `shell_navigation_test.dart` | `ShellNavigationTest.kt` | Phase 5 | Task 7.2 | [x] |
| `telegram_channel_test.dart` | `TelegramChannelTest.kt` | Phase 4 | Task 4.3 | [x] |
| `trigger_batcher_test.dart` | `TriggerBatcherTest.kt` | Phase 2 | Task 2.4 | [x] |
| `webhook_channel_test.dart` | `WebhookChannelTest.kt` | Phase 4 | Task 4.3 | [x] |
| `yamnet_audio_event_classifier_test.dart` | `YamnetClassifierTest.kt` | Phase 3 | Task 3.2 | [x] |
| `yolo_person_engine_test.dart` | `YoloPostprocessTest.kt` | Phase 3 | Task 3.3 | [x] |

## Integration tests (4)

| Integration scenario | Instrumentation test | Task | Status |
|---|---|---|---|
| `face_detection_linux_test.dart` | `FaceDetectionTest.kt` | Task 7.1 | [x] (MediaPipe short-range needs center-crop approach for distant faces; see test note) |
| `monitoring_on_device_test.dart` | `MonitoringInstrumentedTest.kt` | Task 7.1 | [x] |
| `person_detection_linux_test.dart` | `PersonDetectionTest.kt` | Task 7.1 | [x] (blank-frame gate ≤5 boxes, matching native YOLO noise floor) |
| `screen_off_gate_test.dart` | `ScreenOffGateTest.kt` | Task 7.1 | [x] (recordVideo=false, `[itest]` markers via logcat) |

## Legend

- **LiteRT spike (Task 3.1, 2026-08-21):** `com.google.ai.edge.litert:litert:2.2.0`
  verified on `pixel_34_aosp` (API 34, x86_64, swiftshader) via
  `LiteRtSpikeTest.kt`: classic `InterpreterApi` loads `yamnet.tflite`, zero
  `[15600]` float32 → `[521]`; LiteRT Next `CompiledModel(Accelerator.CPU)` loads
  `yolo26n_w8a32.tflite`, zero 640×640 NCHW float32 → `[1,84,8400]`. No re-export
  of the w8a32 model needed. Note: `testInstrumentationRunner` must stay
  `androidx.test.runner.AndroidJUnitRunner` (default runner fails with "failed to attach").

- **Status `[ ]`** = twin test not yet ported (pending). **Flip to `[x]`** when the
  JVM/instrumentation twin lands — i.e. as the corresponding Phase 1/2/3/4/5 task and
  the Phase 7 sweep complete (see Task 0.5). **`n/a`** applies only to the desktop-only
  `ffmpeg`/sim twins: Task 7.2 excludes those Dart tests from porting by design
  (`./gradlew :app:testDebugUnitTest` is the parity gate; `ffmpeg`/sim files are marked
  `n/a` in the final sweep, not `[x]`).
- JVM twin names follow the plan's `FooTest.kt` convention (PascalCase, derived from the
  Dart file name); a few use the plan's named twins where the port target differs
  (`pipeline` → `DetectorPipelineTest.kt`, `event_log` → `EventStoreTest.kt`,
  `face_detector` → `FaceEngineTest.kt`, `monitor_controller` → `MonitorViewModelTest.kt`).
- **Sources:** `docs/plans/2026-08-20-native-kotlin-migration-plan.md` (Phase/Task
  assignment; Task 0.2/0.5 define this matrix) and
  `docs/plans/port-parity-manifest.txt` (the 37 Dart unit files + 4 integration files).