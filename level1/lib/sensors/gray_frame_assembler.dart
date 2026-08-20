import 'dart:typed_data';

import '../core/models.dart';

/// Accumulates raw grayscale byte chunks (as produced by `ffmpeg
/// -f rawvideo -pix_fmt gray`) and emits whole frames, carrying any remainder
/// across chunk boundaries.
class GrayFrameAssembler {
  final int width;
  final int height;
  final int frameSize;
  final BytesBuilder _pending = BytesBuilder();

  GrayFrameAssembler(this.width, this.height)
      : assert(width > 0 && height > 0),
        frameSize = width * height;

  List<GrayscaleBitmap> add(Uint8List chunk) {
    _pending.add(chunk);
    final bytes = _pending.takeBytes();
    final frames = <GrayscaleBitmap>[];
    var offset = 0;
    while (bytes.length - offset >= frameSize) {
      final gray = Uint8List.fromList(
          bytes.sublist(offset, offset + frameSize));
      frames.add(GrayscaleBitmap(width, height, gray));
      offset += frameSize;
    }
    if (offset < bytes.length) {
      _pending.add(bytes.sublist(offset));
    }
    return frames;
  }

  int get buffered => _pending.length;
}
