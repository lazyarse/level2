import 'dart:typed_data';

import '../core/models.dart';

/// Accumulates raw BGR byte chunks (as produced by `ffmpeg -f rawvideo -pix_fmt
/// bgr24`) and emits whole color frames, carrying any remainder across chunk
/// boundaries. Mirrors `GrayFrameAssembler` with a 3× frame size.
class BgrFrameAssembler {
  final int width;
  final int height;
  final int frameSize;
  final BytesBuilder _pending = BytesBuilder();

  BgrFrameAssembler(this.width, this.height)
      : assert(width > 0 && height > 0),
        frameSize = width * height * 3;

  List<ColorBitmap> add(Uint8List chunk) {
    _pending.add(chunk);
    final bytes = _pending.takeBytes();
    final frames = <ColorBitmap>[];
    var offset = 0;
    while (bytes.length - offset >= frameSize) {
      final bgr = Uint8List.fromList(bytes.sublist(offset, offset + frameSize));
      frames.add(ColorBitmap(width, height, bgr));
      offset += frameSize;
    }
    if (offset < bytes.length) {
      _pending.add(bytes.sublist(offset));
    }
    return frames;
  }

  int get buffered => _pending.length;
}
