import 'dart:async';

import 'models.dart';

/// Produces a stream of 1-second analysis audio windows.
abstract class AudioSource {
  Stream<AudioWindow> get windows;

  void start();

  void stop();

  Future<void> dispose();
}