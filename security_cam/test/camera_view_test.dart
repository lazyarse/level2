import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/core/models.dart';
import 'package:security_cam/ui/widgets/camera_view.dart';

void main() {
  group('grayscaleToRGBA', () {
    test('expands each gray pixel to opaque RGBA', () {
      final bitmap = GrayscaleBitmap(
        2,
        2,
        Uint8List.fromList([10, 20, 30, 40]),
      );
      final rgba = grayscaleToRGBA(bitmap);
      expect(rgba, Uint8List.fromList([
        10, 10, 10, 255, //
        20, 20, 20, 255, //
        30, 30, 30, 255, //
        40, 40, 40, 255, //
      ]));
    });
  });

  group('CameraView', () {
    testWidgets('renders frames without throwing', (tester) async {
      final controller = StreamController<AnalysisFrame>.broadcast();
      addTearDown(controller.close);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: CameraView(frames: controller.stream),
        ),
      ));
      // No frame yet -> dark placeholder.
      expect(find.byKey(const ValueKey('camera-placeholder')), findsOneWidget);
      expect(tester.takeException(), isNull);

      controller.add(AnalysisFrame(
        timestamp: DateTime(2026),
        bitmap: GrayscaleBitmap(
          2,
          2,
          Uint8List.fromList([140, 140, 140, 140]),
        ),
      ));
      await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(CustomPaint), findsWidgets);
      expect(find.byKey(const ValueKey('camera-placeholder')), findsNothing);
    });

    testWidgets('reports decoder failures instead of crashing paint', (tester) async {
      final controller = StreamController<AnalysisFrame>.broadcast();
      addTearDown(controller.close);
      Future<ui.Image> badDecoder(
          Uint8List rgba, int width, int height) async {
        throw StateError('decode failed');
      }

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: CameraView(frames: controller.stream, decoder: badDecoder),
        ),
      ));
      controller.add(AnalysisFrame(
        timestamp: DateTime(2026),
        bitmap: GrayscaleBitmap(
          2,
          2,
          Uint8List.fromList([140, 140, 140, 140]),
        ),
      ));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNotNull);
      // Falls back to the placeholder rather than throwing during paint.
      expect(find.byKey(const ValueKey('camera-placeholder')), findsOneWidget);
    });

    Finder inCameraView() => find.descendant(
        of: find.byType(CameraView), matching: find.byType(CustomPaint));

    testWidgets('renders region overlay when showRegions is true', (tester) async {
      final controller = StreamController<AnalysisFrame>.broadcast();
      addTearDown(controller.close);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: CameraView(
            frames: controller.stream,
            regions: const [
              DetectionRegion(
                  id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
            ],
            showRegions: true,
          ),
        ),
      ));
      controller.add(AnalysisFrame(
        timestamp: DateTime(2026),
        bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
      ));
      await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(inCameraView(), findsNWidgets(2),
          reason: 'frame paint + region overlay paint');
      expect(tester.takeException(), isNull);
    });

    testWidgets('no overlay paint when showRegions is false', (tester) async {
      final controller = StreamController<AnalysisFrame>.broadcast();
      addTearDown(controller.close);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: CameraView(
            frames: controller.stream,
            regions: const [
              DetectionRegion(
                  id: 'r1', shape: 'rect', label: 'doorway', points: [0.0, 0.0, 0.5, 0.5]),
            ],
            showRegions: false,
          ),
        ),
      ));
      controller.add(AnalysisFrame(
        timestamp: DateTime(2026),
        bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
      ));
      await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 100)));
      await tester.pumpAndSettle();
      expect(inCameraView(), findsOneWidget,
          reason: 'only the frame paint when overlay is off');
      expect(tester.takeException(), isNull);
    });
  });
}