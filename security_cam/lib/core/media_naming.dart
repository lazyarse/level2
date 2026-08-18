/// Shared media filename scheme for snapshots and video clips:
/// `2026-08-18_10-30-00-123_Hallway.jpg` (colon-free, millisecond suffix for
/// uniqueness, camera name sanitized). Both Dart (snapshots) and the Android
/// native side (video clips) produce the same format so users see one scheme.
String mediaFileName({
  required DateTime timestamp,
  required String cameraName,
  required String extension,
}) {
  String two(int n) => n.toString().padLeft(2, '0');
  String three(int n) => n.toString().padLeft(3, '0');
  final t = timestamp;
  final date = '${t.year}-${two(t.month)}-${two(t.day)}';
  final time = '${two(t.hour)}-${two(t.minute)}-${two(t.second)}-${three(t.millisecond)}';
  final safe = cameraName.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
  return '${date}_${time}_$safe.$extension';
}
