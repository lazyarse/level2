import 'dart:typed_data';

class GrayscaleBitmap {
  final int width;
  final int height;
  final Uint8List gray;

  GrayscaleBitmap(this.width, this.height, this.gray)
      : assert(gray.length == width * height, 'gray length must be width*height');

  int pixel(int x, int y) => gray[y * width + x];
}

/// Raw interleaved BGR pixel buffer (as produced by ffmpeg `bgr24`, CameraX
/// YUV→BGR and the simulated camera). Fed directly to BlazeFace via
/// `detectFacesFromMatBytes` (which expects BGR).
class ColorBitmap {
  final int width;
  final int height;
  final Uint8List bgr;

  ColorBitmap(this.width, this.height, this.bgr)
      : assert(bgr.length == width * height * 3, 'bgr length must be width*height*3');

  int b(int x, int y) => bgr[(y * width + x) * 3];
  int g(int x, int y) => bgr[(y * width + x) * 3 + 1];
  int r(int x, int y) => bgr[(y * width + x) * 3 + 2];

  /// Derives a luminance bitmap (BT.601) from the interleaved BGR bytes.
  GrayscaleBitmap toGrayscale() {
    final gray = Uint8List(width * height);
    for (var i = 0; i < width * height; i++) {
      final b = bgr[i * 3];
      final g = bgr[i * 3 + 1];
      final r = bgr[i * 3 + 2];
      gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0, 255);
    }
    return GrayscaleBitmap(width, height, gray);
  }
}

class AnalysisFrame {
  final DateTime timestamp;
  final GrayscaleBitmap bitmap;
  final ColorBitmap? color;

  AnalysisFrame({required this.timestamp, required this.bitmap, this.color});
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
  static const face = 'face';

  const TriggerType._();
}

class DetectionRegion {
  /// Stable id for editing/delete targeting.
  final String id;

  /// 'rect' | 'poly' (see [DetectionRegionShape]).
  final String shape;

  /// User-friendly name shown in the editor list.
  final String label;

  /// Normalized 0..1 relative to the analysis frame, flattened. Rect:
  /// [x0,y0,x1,y1]. Poly: [x0,y0,x1,y1,...] vertex pairs.
  final List<double> points;

  const DetectionRegion({
    required this.id,
    required this.shape,
    required this.label,
    required this.points,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'shape': shape,
        'label': label,
        'points': points,
      };

  factory DetectionRegion.fromJson(Map<String, dynamic> json) =>
      DetectionRegion(
        id: json['id'] as String,
        shape: json['shape'] as String,
        label: json['label'] as String,
        points: (json['points'] as List).cast<double>(),
      );
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
