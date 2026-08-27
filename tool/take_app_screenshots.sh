#!/usr/bin/env bash
# Captures the app screenshots published in docs/gallery.md.
#
# Usage:
#   tool/take_app_screenshots.sh [device_serial] [target ...]
#   tool/take_app_screenshots.sh [target ...]
#
# Targets (default: all):
#   monitor  events  settings
#   detectors  regions  face_recognition  channels  schedule
#   video_clips  live_view  cloud_backup  events  advanced
#
# Env overrides:
#   BUILD_TYPE   debug (default) | staging   APK to install
#   AVD_NAME     pixel_34_aosp (default)    emulator launched when none attached
#   SERIAL       emulator-5554 (default)    or pass as $1
#
# Behaviour:
#   - Reuses an already-attached device; otherwise boots a HEADLESS AOSP
#     emulator cold (-no-snapshot, per AGENTS.md) and kills it on exit.
#   - Builds + installs the app APK, pm-grants CAMERA/RECORD_AUDIO/
#     POST_NOTIFICATIONS, launches MainActivity and drives the UI through
#     `uiautomator dump` + `input tap` (no instrumentation needed).
#   - Camera preview: when v4l2loopback + ffmpeg is feeding scene.jpg into a
#     Dummy device, the goldfish HAL exposes it as "webcam1" (ID 10).
#     Level2App.kt overrides the CameraX availableCamerasLimiter via
#     CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig()) so
#     CameraX accepts cameras regardless of LENS_FACING.  The Monitor tab
#     preview area will show the live camera feed (not black).
#   - Shots: monitor / events / settings tabs plus every Settings
#     CollapsibleSection unfolded, cropped to the section's region on screen.
#   - Rewrites docs/gallery.md deterministically (no timestamps).
#
# docs/images/ and docs/gallery.md are git-tracked; re-run this script
# whenever the Monitor/Events/Settings layouts change (AGENTS.md).
set -uo pipefail

ALL_TARGETS=(monitor events settings detectors regions face_recognition
  channels schedule video_clips live_view cloud_backup events advanced)

SDK="${ANDROID_HOME:-/home/tpa/code/android-env/android-sdk}"
ADB="$SDK/platform-tools/adb"
BUILD_TYPE="${BUILD_TYPE:-debug}"
AVD_NAME="${AVD_NAME:-pixel_34_aosp}"

# Parse args: first arg may be serial (emulator-*) or a target name.
TARGETS=()
if [ $# -gt 0 ] && [[ "$1" =~ ^(monitor|events|settings|detectors|regions|face_recognition|channels|schedule|video_clips|live_view|cloud_backup|advanced|all)$ ]]; then
  TARGETS=("$@")
else
  SERIAL="${1:-${SERIAL:-emulator-5554}}"
  shift 2>/dev/null || true
  TARGETS=("$@")
fi
[ "${#TARGETS[@]}" -eq 0 ] && TARGETS=(all)

want() { # target_name
  for t in "${TARGETS[@]}"; do
    [ "$t" = "all" ] && return 0
    [ "$t" = "$1" ] && return 0
  done
  return 1
}
SERIAL="${SERIAL:-emulator-5554}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG_DIR="$REPO_ROOT/docs/images"
GALLERY="$REPO_ROOT/docs/gallery.md"
WORK="/tmp/opencode/appshots"
XML="$WORK/ui.xml"
PKG="io.securitycam.level2"

case "$BUILD_TYPE" in
  debug) ;;
  staging) PKG="$PKG.staging" ;;
  *) echo "unsupported BUILD_TYPE=$BUILD_TYPE (debug|staging)"; exit 1 ;;
esac

# This tool taps/swipes/screencaps real UI on shared hardware — emulators only.
case "$SERIAL" in
  emulator-*) ;;
  *) die "refusing non-emulator serial '$SERIAL' (screenshots drive emulators only)" ;;
esac

mkdir -p "$WORK" "$IMG_DIR"
rm -f "$XML" "$WORK/full.png" "$WORK/emulator.log"

adb() { "$ADB" -s "$SERIAL" "$@"; }
die() { echo "ERROR: $*" >&2; exit 1; }

OWNED_EMU=0
FFMPEG_PID=""
ANIM_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ANIM_SAVE=()

cleanup() {
  if [ -n "$FFMPEG_PID" ] && kill -0 "$FFMPEG_PID" 2>/dev/null; then
    kill "$FFMPEG_PID" 2>/dev/null; wait "$FFMPEG_PID" 2>/dev/null
  fi
  pkill -9 -f "ffmpeg.*v4l2.*/dev/video" 2>/dev/null || true
  if [ "${#ANIM_SAVE[@]}" -gt 0 ]; then
    for i in 0 1 2; do
      adb shell settings put global "${ANIM_KEYS[$i]}" "${ANIM_SAVE[$i]}" >/dev/null 2>&1
    done
  fi
  if [ "$OWNED_EMU" = 1 ]; then
    echo "== stopping emulator =="
    adb emu kill >/dev/null 2>&1
    for _ in $(seq 1 15); do
      pgrep -c qemu-system >/dev/null 2>&1 || break
      sleep 1
    done
    if pgrep -c qemu-system >/dev/null 2>&1; then
      pkill -9 -f qemu-system >/dev/null 2>&1 || true
      sleep 2
    fi
    pgrep -c qemu-system >/dev/null 2>&1 && echo "WARNING: qemu still running" >&2
  fi
}
trap cleanup EXIT

device_attached() {
  "$ADB" devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$SERIAL"
}

if device_attached; then
  echo "== using attached device $SERIAL =="
else
  OWNED_EMU=1
  echo "== booting headless emulator $AVD_NAME (cold) =="

  # Virtual camera: feed a recognisable image into the camera preview via
  # v4l2loopback.  Falls back to "emulated" (black frames) if unavailable.
  # The goldfish camera HAL requires the loopback device to advertise a
  # fixed YUYV format for CameraX to open the device without crashing.
  CAMERA_FLAG="-camera-back emulated"
  SCENE_IMG="$REPO_ROOT/tool/virtual-scene/scene.jpg"
  if [ -f "$SCENE_IMG" ]; then
    # Kill stale ffmpeg processes from prior runs that still hold /dev/video*.
    pkill -9 -f "ffmpeg.*v4l2.*/dev/video" 2>/dev/null || true
    sleep 1
    for dev in /dev/video*; do
      name="$(cat /sys/class/video4linux/$(basename "$dev")/name 2>/dev/null)"
      case "$name" in
        *Loop*|*Dummy*)
          echo "== v4l2loopback found: $dev =="
          # Force YUYV format so goldfish HAL can negotiate with CameraX.
          # Goldfish HAL only recognises "webcam1" — ignores higher indices.
          # Caps MUST match exactly what ffmpeg writes (distortion + green bars
          # + screencap hang if format/size disagree).
          v4l2loopback-ctl set-caps "$dev" "YUYV:1280x720" 2>/dev/null || true
          ffmpeg -re -loop 1 -i "$SCENE_IMG" \
            -vf "scale=1280:720,format=yuyv422" \
            -f v4l2 "$dev" </dev/null >/dev/null 2>&1 &
          FFMPEG_PID=$!
          sleep 2
          if kill -0 "$FFMPEG_PID" 2>/dev/null; then
            CAM_CAPS="$(v4l2loopback-ctl get-caps "$dev" 2>/dev/null)"
            if [ "$CAM_CAPS" = "YUYV:1280x720@30/1" ]; then
              CAMERA_FLAG="-camera-back webcam1"
              echo "== feeding $SCENE_IMG → $dev (webcam1) YUYV:1280x720 =="
            else
              echo "== WARNING: caps are '$CAM_CAPS' (expected YUYV:1280x720); falling back to emulated =="
              kill "$FFMPEG_PID" 2>/dev/null; wait "$FFMPEG_PID" 2>/dev/null; FFMPEG_PID=""
            fi
          else
            echo "== WARNING: ffmpeg failed to start; falling back to emulated =="
            FFMPEG_PID=""
          fi
          break
          ;;
      esac
    done
  fi

  nohup "$SDK/emulator/emulator" -avd "$AVD_NAME" -no-snapshot -no-window \
    -no-audio -no-boot-anim -gpu swiftshader_indirect \
    $CAMERA_FLAG \
    >"$WORK/emulator.log" 2>&1 &
  for _ in $(seq 1 60); do
    sleep 5
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  done
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] ||
    die "emulator did not boot in time (log: $WORK/emulator.log)"
fi

echo "== building + installing APK ($BUILD_TYPE) =="
ANDROID_ROOT="$REPO_ROOT/android"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
(cd "$ANDROID_ROOT" && timeout 300 ./gradlew ":app:assemble${BUILD_TYPE^}") ||
  die "gradle build failed"
# A stale install signed with another key (e.g. the release smoke test on
# this AVD) would reject the debug APK; fresh state is fine for screenshots.
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r "$ANDROID_ROOT/app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk" ||
  die "apk install failed"

for p in android.permission.CAMERA android.permission.RECORD_AUDIO \
         android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "$p" >/dev/null 2>&1
done

# Deterministic UI driving: no system animations (values restored on exit).
for k in "${ANIM_KEYS[@]}"; do ANIM_SAVE+=("$(adb shell settings get global "$k" | tr -d '\r')"); done
for k in "${ANIM_KEYS[@]}"; do adb shell settings put global "$k" 0 >/dev/null; done

adb shell input keyevent KEYCODE_WAKEUP
for _ in $(seq 1 10); do
  focus="$(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')"
  case "$focus" in
    *Keyguard*|*keyguard*|*NotificationShade*|*null*)
      adb shell wm dismiss-keyguard >/dev/null 2>&1; sleep 1 ;;
    *) break ;;
  esac
done

echo "== launching app =="
adb logcat -c >/dev/null 2>&1 || true
adb shell am start -W -n "$PKG/.MainActivity" >"$WORK/am_start.log" 2>&1 ||
  { cat "$WORK/am_start.log"; die "am start failed"; }
grep -q "Status: ok" "$WORK/am_start.log" ||
  { cat "$WORK/am_start.log"; die "am start did not report Status: ok"; }

launch_diagnostics() {
  echo "--- current focus ---"
  adb shell dumpsys window 2>/dev/null | grep -m2 -E "mCurrentFocus|mFocusedApp"
  echo "--- app process ---"
  adb shell pidof "$PKG" || echo "(not running)"
  echo "--- logcat tail ---"
  adb logcat -d -t 300 2>/dev/null | grep -iE "FATAL|AndroidRuntime|$PKG" | tail -40
}

dump_ui() {
  local i
  for i in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1
    adb shell cat /sdcard/uidump.xml >"$XML" 2>/dev/null
    [ -s "$XML" ] && grep -q '</hierarchy>' "$XML" && return 0
    sleep 1
  done
  return 1
}

bounds_of() { # attr value [lowest] -> "x1 y1 x2 y2" or rc=1
  # "lowest" picks the bottom-most match: nav-bar labels share their text
  # with Settings section titles, and the nav bar always wins on y.
  [ -s "$XML" ] || return 1
  python3 - "$XML" "$1" "$2" "${3:-}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, attr, want, mode = sys.argv[1:5]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
best = None
for n in root.iter("node"):
    if n.get(attr) != want:
        continue
    m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m:
        continue
    b = tuple(int(x) for x in m.groups())
    if mode == "lowest":
        if best is None or b[1] > best[1]:
            best = b
    else:
        print(*b)
        sys.exit(0)
if best is not None:
    print(*best)
    sys.exit(0)
sys.exit(1)
PY
}

tap_bounds() { # "x1 y1 x2 y2"
  # shellcheck disable=SC2206
  local xy=($1)
  adb shell input tap "$(( (xy[0] + xy[2]) / 2 ))" "$(( (xy[1] + xy[3]) / 2 ))"
}

tap_node() { # attr value
  local b
  b="$(bounds_of "$1" "$2")" || return 1
  tap_bounds "$b"
}

tap_lowest() { # attr value
  local b
  b="$(bounds_of "$1" "$2" lowest)" || return 1
  tap_bounds "$b"
}

screen_size() {
  local s; s="$(adb shell wm size | tr -d '\r')"
  s="${s##*: }"; echo "${s//[^0-9x]/}"
}

scroll_down() {
  local dim w h
  dim="$(screen_size)"; w="${dim%x*}"; h="${dim#*x}"
  adb shell input swipe "$((w / 2))" "$((h * 70 / 100))" "$((w / 2))" "$((h * 35 / 100))" 250
}

scroll_to_top() { # attr value — scrolls until node is near the top (y in 40..120)
  local dim w
  dim="$(screen_size)"; w="${dim%x*}"
  for _ in $(seq 1 8); do
    dump_ui || return 1
    b="$(bounds_of "$1" "$2")" || return 1
    # shellcheck disable=SC2206
    local xy=($b)
    if [ "${xy[1]}" -ge 40 ] && [ "${xy[1]}" -le 120 ]; then return 0; fi
    local target=80
    local from_y=$(( xy[1] + 60 ))
    [ "$from_y" -lt 100 ] && from_y=100
    adb shell input swipe $((w / 2)) "$from_y" $((w / 2)) "$target" 150
    sleep 0.2
  done
  return 1
}

nav_bar_top() { # reads last $XML, prints y-pixel where the app nav bar starts
  python3 - "$XML" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ys = []
for n in root.iter("node"):
    if n.get("text") in ("Monitor", "Events", "Settings"):
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            ys.append(int(m.group(2)))
if ys:
    # nav label text sits ~130 px below the bar's top edge
    print(min(ys) - 130)
else:
    print(1584)  # fallback for pixel_34_aosp
PY
}

shot() { adb exec-out screencap -p >"$1"; [ -s "$1" ] || die "empty screenshot: $1"; }

wait_for() { # attr value [tries]
  local tries="${3:-30}" i
  for i in $(seq 1 "$tries"); do
    dump_ui && bounds_of "$1" "$2" >/dev/null && return 0
    if [ "$i" = "$tries" ]; then
      echo "== UI never showed $1='$2'; diagnostics =="
      launch_diagnostics
    fi
    sleep 2
  done
  return 1
}

wait_for text "Monitor" || die "app did not render its navigation bar"
sleep 5 # camera preview warm-up (goldfish HAL needs time to enumerate + open device)

# Tap "Preview" to activate the camera (shows the live feed in the preview area).
tap_node text "Preview" 2>/dev/null && sleep 5

# Clean old captures for requested targets so gallery only lists this run.
for f in monitor events settings; do
  want "$f" && rm -f "$IMG_DIR/$f.png"
done
for entry in "${SECTIONS[@]}"; do
  slug="${entry##*|}"
  want "$slug" && rm -f "$IMG_DIR/settings_${slug}.png"
done

if want monitor; then
  shot "$IMG_DIR/monitor.png"
  echo "captured monitor.png"
fi

if want events; then
  tap_lowest text "Events" || die "cannot tap Events tab"
  wait_for content-desc "Reload" 10 || die "Events tab did not appear"
  sleep 0.5
  dump_ui
  shot "$IMG_DIR/events.png"
  echo "captured events.png"
fi

if want settings || [ "${#TARGETS[@]}" -gt 1 ]; then
  tap_lowest text "Settings" || die "cannot tap Settings tab"
  wait_for content-desc "expand_Detectors" 15 || die "Settings tab did not appear"
  sleep 0.5
  dump_ui
  shot "$IMG_DIR/settings.png"
  echo "captured settings.png"
fi

NAV_TOP="$(nav_bar_top)"
W="$(adb shell wm size | tr -d '\r' | sed 's/Physical size: //; s/x.*//')"
echo "computed NAV_TOP=$NAV_TOP W=$W"

SECTIONS=(
  "Detectors|detectors"
  "Regions|regions"
  "Face Recognition|face_recognition"
  "Channels|channels"
  "Schedule|schedule"
  "Video clips|video_clips"
  "Live View|live_view"
  "Cloud backup|cloud_backup"
  "Events|events"
  "Advanced|advanced"
)

crop_png() { # src dst l t r b
  python3 - "$@" <<'PY'
import sys
from PIL import Image
src, dst, l, t, r, b = sys.argv[1:7]
Image.open(src).crop((int(l), int(t), int(r), int(b))).save(dst)
PY
}

for entry in "${SECTIONS[@]}"; do
  title="${entry%%|*}"; slug="${entry##*|}"
  want "$slug" || continue
  out="$IMG_DIR/settings_${slug}.png"
  found=""
  for _ in $(seq 1 25); do
    dump_ui || { scroll_down; sleep 0.5; continue; }
    if bounds_of content-desc "expand_$title" >/dev/null ||
       bounds_of content-desc "collapse_$title" >/dev/null; then
      found=1; break
    fi
    scroll_down; sleep 0.5
  done
  [ -n "$found" ] || die "section '$title' never became visible"

  if bounds_of content-desc "expand_$title" >/dev/null; then
    exp_b="$(bounds_of content-desc "expand_$title")" || die "cannot find expand_$title"
    echo "  expand $title bounds=$exp_b"
    tap_bounds "$exp_b" || die "cannot expand '$title'"
    sleep 0.5
    dump_ui || die "dump failed after expanding '$title'"
    bounds_of content-desc "collapse_$title" >/dev/null ||
      die "'$title' did not report expanded state"
  fi

  scroll_to_top content-desc "collapse_$title" || true
  sleep 0.3

  shot "$WORK/full.png"
  crop_png "$WORK/full.png" "$out" 0 0 "$W" "$NAV_TOP" || die "crop failed for '$title'"
  echo "captured settings_${slug}.png"

  dump_ui
  if bounds_of content-desc "collapse_$title" >/dev/null; then
    tap_node content-desc "collapse_$title" || true
    sleep 0.3
  fi
done

generate_gallery() {
  cat >"$GALLERY" <<'EOF'
# App gallery

Screenshots generated by `tool/take_app_screenshots.sh` — re-run that script
(after any layout change) rather than editing these files by hand.

## Screens

EOF
  local entry title slug cap
  # Only include screens that were captured this run (check by mtime).
  for f in monitor events settings; do
    [ -f "$IMG_DIR/$f.png" ] || continue
    case "$f" in
      monitor)  printf '### Monitor\n![Monitor tab](images/monitor.png)\n\nLive camera preview with detector status and the monitoring start control.\n\n' >>"$GALLERY" ;;
      events)   printf '### Events\n![Events tab](images/events.png)\n\nRecorded events with snapshot thumbnails, list/grid toggle and manual reload.\n\n' >>"$GALLERY" ;;
      settings) printf '### Settings\n![Settings tab](images/settings.png)\n\nCamera selection and the collapsible configuration sections captured below.\n\n' >>"$GALLERY" ;;
    esac
  done
  printf '## Settings sections\n\nEach section is shown expanded, cropped to its own region.\n\n' >>"$GALLERY"
  for entry in "${SECTIONS[@]}"; do
    title="${entry%%|*}"; slug="${entry##*|}"
    [ -f "$IMG_DIR/settings_${slug}.png" ] || continue
    case "$slug" in
      detectors)       cap="Detector groups (camera/audio/combined/system) with per-detector mode and thresholds." ;;
      regions)         cap="Inclusion/exclusion detection regions and the editor entry point." ;;
      face_recognition) cap="Known-face enrolment and recognition toggle." ;;
      channels)        cap="Notification channels (Pushover, webhook, e-mail, log) with test-send controls." ;;
      schedule)        cap="Monitoring schedule windows with pre-/post-roll." ;;
      video_clips)     cap="Local clip recording, date/time stamping and privacy masking." ;;
      live_view)       cap="RTSP live stream, authentication and audio options." ;;
      cloud_backup)    cap="Self-hosted backup of clips and snapshots." ;;
      events)          cap="Event retention and clearing." ;;
      advanced)        cap="Advanced diagnostics and maintenance actions." ;;
    esac
    printf '### %s\n![%s section](images/settings_%s.png)\n\n%s\n\n' "$title" "$title" "$slug" "$cap" >>"$GALLERY"
  done
}
generate_gallery
echo "== gallery written to ${GALLERY#$REPO_ROOT/} =="

# Resize all screenshots to half size for faster loading.
for f in "$IMG_DIR"/*.png; do
  [ -f "$f" ] || continue
  python3 - "$f" <<'PY'
import sys
from PIL import Image
path = sys.argv[1]
img = Image.open(path)
w, h = img.size
img.resize((w // 2, h // 2), Image.LANCZOS).save(path)
PY
done
echo "== resized screenshots to 50% =="

ls -la "$IMG_DIR"
