import 'dart:io';

import '../detection/audio/audio_classifier.dart';
import '../detection/audio/yamnet_audio_event_classifier.dart';

/// Audio classifier factory.
///
/// On mobile the real YAMNet model is used ([YamnetAudioEventClassifier]);
/// on desktop the [MockAudioEventClassifier] keeps the pipeline exercisable
/// without bundling native LiteRT + a model into dev-time builds.
Future<AudioEventClassifier> buildAudioClassifier() async {
  if (Platform.isAndroid || Platform.isIOS) {
    return YamnetAudioEventClassifier.load();
  }
  return MockAudioEventClassifier();
}