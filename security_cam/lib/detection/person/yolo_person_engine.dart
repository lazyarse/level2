import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter_litert/flutter_litert.dart';

import '../../core/models.dart';
import 'person_engine.dart';

/// Letterbox geometry: how a [width]x[height] frame is mapped into the model's
/// 640x640 input (uniform scale + centered black padding).
({double gain, int padX, int padY}) letterboxInfo(int width, int height) {
  final gain = math.min(640 / width, 640 / height);
  final newW = (width * gain).round();
  final newH = (height * gain).round();
  final padX = ((640 - newW) / 2 - 0.1).round();
  final padY = ((640 - newH) / 2 - 0.1).round();
  return (gain: gain, padX: padX, padY: padY);
}

/// Decodes YOLO26n `[1, 84, 8400]` float32 output (person class = row 4) into
/// person boxes in original frame coordinates.
///
/// Box rows are normalized [0,1]; scores are sigmoid-activated by the graph.
/// Applies the confidence gate, letterbox undo, clamping, then IoU NMS.
List<PersonBox> decodeYolo26(
  Float32List output, {
  required double conf,
  required double iou,
  required int maxDetections,
  required int frameWidth,
  required int frameHeight,
}) {
  const anchors = 8400;
  const boxRows = 4;

  final info = letterboxInfo(frameWidth, frameHeight);
  final candidates = <PersonBox>[];
  for (var i = 0; i < anchors; i++) {
    final score = output[boxRows * anchors + i];
    if (score < conf) continue;
    final cx = output[i];
    final cy = output[anchors + i];
    final w = output[2 * anchors + i];
    final h = output[3 * anchors + i];
    final x1m = (cx - w / 2) * 640;
    final y1m = (cy - h / 2) * 640;
    final x2m = (cx + w / 2) * 640;
    final y2m = (cy + h / 2) * 640;
    candidates.add((
      _clamp((x1m - info.padX) / info.gain, 0, frameWidth.toDouble()),
      _clamp((y1m - info.padY) / info.gain, 0, frameHeight.toDouble()),
      _clamp((x2m - info.padX) / info.gain, 0, frameWidth.toDouble()),
      _clamp((y2m - info.padY) / info.gain, 0, frameHeight.toDouble()),
      score,
    ));
  }
  candidates.sort((a, b) => b.$5.compareTo(a.$5));
  return nms(candidates, iou: iou, maxDetections: maxDetections);
}

/// Non-max suppression over score-descending [boxes]; keeps at most
/// [maxDetections] boxes that don't overlap a kept box beyond [iou].
List<PersonBox> nms(
  List<PersonBox> boxes, {
  required double iou,
  required int maxDetections,
}) {
  final kept = <PersonBox>[];
  for (final b in boxes) {
    var overlap = false;
    for (final k in kept) {
      if (iouOf(b, k) > iou) {
        overlap = true;
        break;
      }
    }
    if (!overlap) {
      kept.add(b);
      if (kept.length >= maxDetections) break;
    }
  }
  return kept;
}

/// Intersection-over-union of two boxes.
double iouOf(PersonBox a, PersonBox b) {
  final ix = math.max(0, math.min(a.$3, b.$3) - math.max(a.$1, b.$1));
  final iy = math.max(0, math.min(a.$4, b.$4) - math.max(a.$2, b.$2));
  final inter = ix * iy;
  final union =
      (a.$3 - a.$1) * (a.$4 - a.$2) + (b.$3 - b.$1) * (b.$4 - b.$2) - inter;
  return union <= 0 ? 0 : inter / union;
}

double _clamp(double v, double lo, double hi) => v < lo ? lo : (v > hi ? hi : v);

/// YOLO26n (`yolo26n_w8a32.tflite`) via flutter_litert's LiteRT Interpreter.
/// Preprocesses the BGR [ColorBitmap] to a 640x640 RGB NCHW float32 tensor,
/// runs inference, and decodes + NMSes person boxes (pure Dart helpers above).
class YoloPersonEngine implements PersonEngine {
  YoloPersonEngine({
    this.confThreshold = 0.25,
    this.iouThreshold = 0.7,
    this.maxDetections = 30,
  });

  static const _inputSize = 640;
  static const _anchors = 8400;
  static const _classes = 80;
  static const _modelAsset = 'assets/yolo26n_w8a32.tflite';

  final double confThreshold;
  final double iouThreshold;
  final int maxDetections;

  Interpreter? _interpreter;

  @override
  Future<void> init() async {
    _interpreter = await Interpreter.fromAsset(_modelAsset);
  }

  @override
  Future<List<PersonBox>> detectPersons(ColorBitmap frame) async {
    final interpreter = _interpreter;
    if (interpreter == null) return const [];

    final input = _buildInput(frame);
    interpreter.runInference([input]);

    final outputTensor = interpreter.getOutputTensor(0);
    final output = Float32List(_anchors * (_classes + 4));
    outputTensor.copyTo(output);

    return decodeYolo26(
      output,
      conf: confThreshold,
      iou: iouThreshold,
      maxDetections: maxDetections,
      frameWidth: frame.width,
      frameHeight: frame.height,
    );
  }

  /// Letterboxes [frame] into the 640x640 RGB NCHW float32 input tensor.
  Float32List _buildInput(ColorBitmap frame) {
    final info = letterboxInfo(frame.width, frame.height);
    final input = Float32List(3 * _inputSize * _inputSize);
    final plane = _inputSize * _inputSize;
    final bgr = frame.bgr;
    for (var y = 0; y < _inputSize; y++) {
      final sy = (y - info.padY) / info.gain;
      if (sy < 0 || sy >= frame.height) continue;
      final syi = sy.floor();
      for (var x = 0; x < _inputSize; x++) {
        final sx = (x - info.padX) / info.gain;
        if (sx < 0 || sx >= frame.width) continue;
        final src = (syi * frame.width + sx.floor()) * 3;
        final px = y * _inputSize + x;
        input[px] = bgr[src + 2] / 255;
        input[plane + px] = bgr[src + 1] / 255;
        input[2 * plane + px] = bgr[src] / 255;
      }
    }
    return input;
  }

  @override
  Future<void> dispose() async {
    _interpreter?.close();
    _interpreter = null;
  }
}