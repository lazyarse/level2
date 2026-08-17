import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:tflite_flutter/tflite_flutter.dart';

import '../../core/models.dart';
import 'audio_classifier.dart';

/// YAMNet audio classifier via `tflite_flutter` (LiteRT).
///
/// Runs the bundled `lite-model/yamnet/classification/tflite` model once per
/// window. That checkpoint has the audio front-end fused in-graph: its input
/// is the raw 16 kHz waveform (`[15600]` samples = 0.975 s), not a log-mel
/// patch, so no Dart-side DSP is needed. Tensor quantization
/// (scale/zero_point) is read from the model at init, so the same code serves
/// int8 and float32 checkpoints. Per-type audio detectors consume the shared
/// 521-class AudioSet score vector.
class YamnetAudioEventClassifier implements AudioEventClassifier {
  static const String modelAsset = 'assets/yamnet.tflite';
  static const String labelsAsset = 'assets/yamnet_labels.txt';

  /// YAMNet AudioSet class indices used for alert types.
  static const int babyCryClass = 20;
  static const List<int> glassClasses = [435, 437, 463, 464];

  /// Expected input sample count for a 0.975 s patch at 16 kHz.
  static const int inputSamples = 15600;

  final Interpreter _interpreter;

  late final List<int> _inputShape;
  late final List<int> _outputShape;
  late final int _inputElements;
  late final int _outputElements;
  late final bool _inputIsInt8;
  late final bool _outputIsInt8;
  late final double _inScale;
  late final int _inZeroPoint;
  late final double _outScale;
  late final int _outZeroPoint;
  late final Uint8List _inputBytes;
  late final Uint8List _outputBytes;
  int _classifyCount = 0;

  YamnetAudioEventClassifier(this._interpreter);

  /// Loads the bundled YAMNet model from the Flutter asset bundle.
  static Future<YamnetAudioEventClassifier> load() async {
    final interpreter = await Interpreter.fromAsset(modelAsset);
    return YamnetAudioEventClassifier(interpreter);
  }

  /// 521 AudioSet display labels (index-aligned), for logging/debugging.
  static Future<List<String>> loadLabels() async {
    final data = await rootBundle.load(labelsAsset);
    final text = String.fromCharCodes(data.buffer.asUint8List());
    return text.split('\n').where((l) => l.trim().isNotEmpty).toList();
  }

  @override
  String get id => 'yamnet';

  @override
  Future<void> init() async {
    final inTensor = _interpreter.getInputTensor(0);
    final outTensor = _interpreter.getOutputTensor(0);
    _inputShape = inTensor.shape;
    _outputShape = outTensor.shape;
    if (Tensor.computeNumElements(_inputShape) != inputSamples) {
      throw StateError('Unexpected YAMNet input shape $_inputShape '
          '(expected $inputSamples samples)');
    }
    if (Tensor.computeNumElements(_outputShape) != 521) {
      throw StateError('Unexpected YAMNet output shape $_outputShape '
          '(expected 521 classes)');
    }
    _inputElements = inputSamples;
    _outputElements = 521;
    _inputIsInt8 = inTensor.type == TensorType.int8;
    _outputIsInt8 = outTensor.type == TensorType.int8;
    _inScale = inTensor.params.scale;
    _inZeroPoint = inTensor.params.zeroPoint;
    _outScale = outTensor.params.scale;
    _outZeroPoint = outTensor.params.zeroPoint;
    _inputBytes =
        Uint8List(_inputElements * (_inputIsInt8 ? 1 : Float32List.bytesPerElement));
    _outputBytes =
        Uint8List(_outputElements * (_outputIsInt8 ? 1 : Float32List.bytesPerElement));
    if (kDebugMode) {
      debugPrint('YAMNet ready: in=$_inputShape '
          '${_inputIsInt8 ? "int8 q($_inScale,$_inZeroPoint)" : "float32"} '
          'out=$_outputShape '
          '${_outputIsInt8 ? "int8 q($_outScale,$_outZeroPoint)" : "float32"}');
    }
  }

  @override
  Future<AudioEventScores> classify(AudioWindow window) async {
    writeInput(
      _inputBytes,
      window.samples,
      int8: _inputIsInt8,
      scale: _inScale,
      zeroPoint: _inZeroPoint,
    );
    _interpreter.run(_inputBytes, _outputBytes);
    final classScores = readOutput(
      _outputBytes,
      int8: _outputIsInt8,
      scale: _outScale,
      zeroPoint: _outZeroPoint,
    );
    _classifyCount++;
    if (kDebugMode && _classifyCount % 10 == 0) {
      final scores = scoresFromClasses(classScores, window.samples);
      debugPrint('yamnet scores baby_cry='
          '${scores['baby_cry']!.toStringAsFixed(3)} '
          'glass=${scores['glass']!.toStringAsFixed(3)} '
          'loud_noise=${scores['loud_noise']!.toStringAsFixed(3)}');
      return AudioEventScores(
        timestamp: window.timestamp,
        classScores: scores,
      );
    }
    return AudioEventScores(
      timestamp: window.timestamp,
      classScores: scoresFromClasses(classScores, window.samples),
    );
  }

  /// Maps the 521 class-score vector to per-type alert scores, independent of
  /// the model runtime (pure, unit-testable).
  static Map<String, double> scoresFromClasses(
      Float32List classScores, Float32List windowSamples) {
    var glass = 0.0;
    for (final c in glassClasses) {
      if (c < classScores.length && classScores[c] > glass) {
        glass = classScores[c];
      }
    }
    var rms = 0.0;
    for (final s in windowSamples) {
      rms += s * s;
    }
    rms = math.sqrt(rms / windowSamples.length);
    final loudNoise = (rms - 0.3) / 0.3;
    return {
      'baby_cry': babyCryClass < classScores.length
          ? classScores[babyCryClass]
          : 0.0,
      'glass': glass,
      'loud_noise': loudNoise.clamp(0.0, 1.0).toDouble(),
    };
  }

  /// Serializes a log-mel patch into raw tensor bytes for [target] (int8 or
  /// float32), applying the model's input quantization. Pure/testable.
  static void writeInput(
    Uint8List target,
    Float32List logMel, {
    required bool int8,
    required double scale,
    required int zeroPoint,
  }) {
    final view = target.buffer.asByteData();
    if (int8) {
      for (var i = 0; i < logMel.length; i++) {
        final q = (logMel[i] / scale + zeroPoint).round();
        target[i] = q.clamp(-128, 127) & 0xFF;
      }
    } else {
      for (var i = 0; i < logMel.length; i++) {
        view.setFloat32(i * 4, logMel[i], Endian.little);
      }
    }
  }

  /// Deserializes raw output tensor bytes into float class scores, applying
  /// the model's output dequantization. Pure/testable.
  static Float32List readOutput(
    Uint8List bytes, {
    required bool int8,
    required double scale,
    required int zeroPoint,
  }) {
    final out = Float32List(bytes.length ~/ (int8 ? 1 : 4));
    if (int8) {
      for (var i = 0; i < out.length; i++) {
        out[i] = bytes[i].toSigned(8) * scale + zeroPoint;
      }
    } else {
      final view = bytes.buffer.asByteData();
      for (var i = 0; i < out.length; i++) {
        out[i] = view.getFloat32(i * 4, Endian.little);
      }
    }
    return out;
  }

  @override
  Future<void> dispose() async {
    _interpreter.close();
  }
}