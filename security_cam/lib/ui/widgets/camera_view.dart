import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../core/models.dart';

typedef AsyncImageDecoder = Future<ui.Image> Function(
    Uint8List rgba, int width, int height);

Future<ui.Image> decodeFrame(Uint8List rgba, int width, int height) {
  final completer = Completer<ui.Image>();
  ui.decodeImageFromPixels(
    rgba,
    width,
    height,
    ui.PixelFormat.rgba8888,
    completer.complete,
  );
  return completer.future;
}

Uint8List grayscaleToRGBA(GrayscaleBitmap bitmap) {
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

class CameraView extends StatefulWidget {
  final Stream<AnalysisFrame> frames;
  final GrayscaleBitmap? initialFrame;
  final AsyncImageDecoder decoder;

  const CameraView({
    super.key,
    required this.frames,
    this.initialFrame,
    this.decoder = decodeFrame,
  });

  @override
  State<CameraView> createState() => _CameraViewState();
}

class _CameraViewState extends State<CameraView> {
  StreamSubscription<AnalysisFrame>? _sub;
  ui.Image? _image;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _sub = widget.frames.listen((frame) => _decode(frame.bitmap));
    final initial = widget.initialFrame;
    if (initial != null) _decode(initial);
  }

  @override
  void didUpdateWidget(covariant CameraView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.frames, widget.frames)) {
      _sub?.cancel();
      _sub = widget.frames.listen((frame) => _decode(frame.bitmap));
    }
  }

  Future<void> _decode(GrayscaleBitmap bitmap) async {
    final gen = ++_generation;
    try {
      final image = await widget.decoder(
        grayscaleToRGBA(bitmap),
        bitmap.width,
        bitmap.height,
      );
      if (!mounted || gen != _generation) {
        image.dispose();
        return;
      }
      setState(() {
        _image?.dispose();
        _image = image;
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(
        exception: e,
        stack: st,
        library: 'security_cam.camera_view',
        context: ErrorDescription('decoding analysis frame to ui.Image'),
      ));
    }
  }

  @override
  void dispose() {
    _generation++;
    _sub?.cancel();
    _image?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final image = _image;
    return AspectRatio(
      aspectRatio: 4 / 3,
      child: ClipRect(
        child: image == null
            ? const ColoredBox(
                key: ValueKey('camera-placeholder'),
                color: Color(0xFF111111),
              )
            : CustomPaint(
                size: Size.infinite,
                painter: _FramePainter(image),
              ),
      ),
    );
  }
}

class _FramePainter extends CustomPainter {
  final ui.Image image;

  _FramePainter(this.image);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      Offset.zero & size,
      Paint()
        ..filterQuality = FilterQuality.none
        ..isAntiAlias = false,
    );
  }

  @override
  bool shouldRepaint(_FramePainter oldDelegate) => oldDelegate.image != image;
}