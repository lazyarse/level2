import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../../core/models.dart';

class CameraView extends StatelessWidget {
  final Stream<AnalysisFrame> frames;
  final GrayscaleBitmap? initialFrame;

  const CameraView({super.key, required this.frames, this.initialFrame});

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<AnalysisFrame>(
      stream: frames,
      initialData: initialFrame == null
          ? null
          : AnalysisFrame(timestamp: DateTime.now(), bitmap: initialFrame!),
      builder: (context, snapshot) {
        final frame = snapshot.data;
        return AspectRatio(
          aspectRatio: 4 / 3,
          child: frame == null
              ? const ColoredBox(color: Color(0xFF111111))
              : CustomPaint(
                  size: Size.infinite,
                  painter: _FramePainter(frame.bitmap),
                ),
        );
      },
    );
  }
}

class _FramePainter extends CustomPainter {
  final GrayscaleBitmap bitmap;

  _FramePainter(this.bitmap);

  @override
  void paint(Canvas canvas, Size size) {
    final ui.Image image = ui.decodeImageFromPixelsSync(
      _rgba(bitmap),
      bitmap.width,
      bitmap.height,
      ui.PixelFormat.rgba8888,
    );
    final paint = Paint()
      ..filterQuality = FilterQuality.none
      ..isAntiAlias = false;
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, bitmap.width.toDouble(), bitmap.height.toDouble()),
      Offset.zero & size,
      paint,
    );
    image.dispose();
  }

  Uint8List _rgba(GrayscaleBitmap bitmap) {
    final rgba = Uint8List(bitmap.width * bitmap.height * 4);
    for (var i = 0; i < bitmap.gray.length; i++) {
      final v = bitmap.gray[i];
      rgba[i * 4] = v;
      rgba[i * 4 + 1] = v;
      rgba[i * 4 + 2] = v;
      rgba[i * 4 + 3] = 255;
    }
    return rgba;
  }

  @override
  bool shouldRepaint(_FramePainter oldDelegate) => oldDelegate.bitmap != bitmap;
}