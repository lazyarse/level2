import 'dart:io';

import 'package:flutter/foundation.dart';

import '../detection/audio/audio_classifier.dart';
import '../detection/audio/yamnet_audio_event_classifier.dart';

/// Audio classifier factory.
///
/// On mobile the real YAMNet model is used ([YamnetAudioEventClassifier]);
/// on desktop the [MockAudioEventClassifier] keeps the pipeline exercisable
/// without bundling native LiteRT + a model into dev-time builds.
Future<AudioEventClassifier> buildAudioClassifier() async {
  if (Platform.isAndroid || Platform.isIOS) {
    try {
      return await YamnetAudioEventClassifier.load();
    } catch (e) {
      // e.g. tflite_flutter's prebuilt lib requires `strtod_l` (bionic API 24),
      // which some AOSP 7.0 x86_64 images don't ship. Degrade to the mock
      // classifier so monitoring (camera + mic) keeps working.
      debugPrint('YAMNet load failed ($e); falling back to mock classifier');
      return MockAudioEventClassifier();
    }
  }
  return MockAudioEventClassifier();
}