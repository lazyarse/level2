import 'dart:typed_data';

class GrayscaleBitmap {
  final int width;
  final int height;
  final Uint8List gray;

  GrayscaleBitmap(this.width, this.height, this.gray)
      : assert(gray.length == width * height, 'gray length must be width*height');

  int pixel(int x, int y) => gray[y * width + x];
}

class AnalysisFrame {
  final DateTime timestamp;
  final GrayscaleBitmap bitmap;

  AnalysisFrame({required this.timestamp, required this.bitmap});
}

class AudioWindow {
  final DateTime timestamp;
  final Float32List samples;
  final int sampleRate;

  AudioWindow({required this.timestamp, required this.samples, required this.sampleRate});

  double get seconds => samples.length / sampleRate;
}

class Snapshot {
  final Uint8List bytes;
  final String mimeType;
  final String name;

  Snapshot({required this.bytes, required this.mimeType, required this.name});
}

class Detection {
  final String label;
  final double score;

  Detection({required this.label, required this.score});
}

class TriggerType {
  static const motion = 'motion';
  static const babyCry = 'baby_cry';
  static const glassBreak = 'glass_break';
  static const loudNoise = 'loud_noise';
  static const merged = 'merged';
  static const person = 'person';

  const TriggerType._();
}

class DetectionResult {
  final DateTime timestamp;
  final String triggerType;
  final double score;
  final bool triggered;
  final List<Detection> detections;

  DetectionResult({
    required this.timestamp,
    required this.triggerType,
    required this.score,
    required this.triggered,
    this.detections = const [],
  });
}

class TriggerEvent {
  final DateTime timestamp;
  final String triggerType;
  final double score;
  final String detectorId;

  TriggerEvent({
    required this.timestamp,
    required this.triggerType,
    required this.score,
    required this.detectorId,
  });
}

class AlertMessage {
  final DateTime timestamp;
  final String triggerType;
  final String text;
  final Snapshot? snapshot;

  AlertMessage({
    required this.timestamp,
    required this.triggerType,
    required this.text,
    this.snapshot,
  });
}
