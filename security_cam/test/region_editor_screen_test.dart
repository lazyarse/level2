import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:security_cam/core/models.dart';
import 'package:security_cam/ui/region_editor_screen.dart';

void main() {
  Stream<AnalysisFrame> frames() {
    final c = StreamController<AnalysisFrame>.broadcast();
    c.add(AnalysisFrame(
      timestamp: DateTime(2026),
      bitmap: GrayscaleBitmap(2, 2, Uint8List.fromList([140, 140, 140, 140])),
    ));
    return c.stream;
  }

  testWidgets('renders tool bar and region list', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (_) {},
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Detection regions'), findsOneWidget);
    expect(find.text('Rectangle'), findsOneWidget);
    expect(find.text('Polygon'), findsOneWidget);
    expect(find.text('doorway'), findsOneWidget);
  });

  testWidgets('Done saves the region list', (tester) async {
    List<DetectionRegion>? saved;
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (r) => saved = r,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(saved, isNotNull);
    expect(saved!.single.label, 'doorway');
  });

  testWidgets('Clear all removes regions (with confirm)', (tester) async {
    List<DetectionRegion>? saved;
    await tester.pumpWidget(MaterialApp(
      home: RegionEditorScreen(
        frames: frames(),
        initialRegions: const [
          DetectionRegion(
              id: 'r1', shape: 'rect', label: 'doorway', points: [0.1, 0.2, 0.5, 0.8]),
        ],
        onSave: (r) => saved = r,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clear').last); // confirm dialog button
    await tester.pumpAndSettle();
    expect(find.text('doorway'), findsNothing);
    expect(saved, isNull); // not saved until Done
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(saved, isEmpty);
  });
}