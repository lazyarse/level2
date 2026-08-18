import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:security_cam/core/models.dart';
import 'package:security_cam/storage/event_log.dart';
import 'package:security_cam/storage/snapshot_store.dart';
import 'package:security_cam/ui/events_screen.dart';

class _MemoryStore implements SnapshotStore {
  final Map<String, Snapshot> _items = {};

  @override
  Future<String> save(Snapshot snapshot) async {
    _items[snapshot.name] = snapshot;
    return snapshot.name;
  }

  @override
  Future<Snapshot?> load(String name) async => _items[name];

  @override
  Future<void> delete(String name) async {
    _items.remove(name);
  }
}

void main() {
  RecordedEventRow row({String? snapshotName, String? videoName}) =>
      RecordedEventRow(
        id: 1,
        timestamp: DateTime(2026, 1, 1, 12),
        cameraName: 'Hallway',
        triggerType: 'motion',
        score: 0.8,
        snapshotName: snapshotName,
        videoName: videoName,
      );

  Uint8List tinyPng() {
    final image = img.Image(width: 2, height: 2);
    return Uint8List.fromList(img.encodePng(image));
  }

  testWidgets('renders snapshot thumbnail for an event with a snapshot',
      (tester) async {
    final store = _MemoryStore();
    await store.save(Snapshot(
      bytes: tinyPng(),
      mimeType: 'image/png',
      name: 'snap-1.png',
    ));

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: EventsScreen(
          loader: () async => [row(snapshotName: 'snap-1.png')],
          snapshotStore: store,
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(Image), findsOneWidget);
    expect(find.textContaining('Motion'), findsOneWidget);

    await tester.tap(find.byType(Image));
    await tester.pumpAndSettle();
    expect(find.text('Close'), findsOneWidget);
  });

  testWidgets('falls back to icon when the snapshot is missing', (tester) async {
    final store = _MemoryStore();

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: EventsScreen(
          loader: () async => [row(snapshotName: 'missing.png')],
          snapshotStore: store,
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(Image), findsNothing);
    expect(find.byIcon(Icons.directions_run), findsOneWidget);
  });

  testWidgets('shows a video button only when a video is attached and an opener '
      'is provided', (tester) async {
    final store = _MemoryStore();
    final opened = <String>[];

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: EventsScreen(
          loader: () async => [
            row(videoName: 'clip.mp4'),
            row(videoName: null),
          ],
          snapshotStore: store,
          openVideo: (name) async => opened.add(name),
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.play_circle_outline), findsOneWidget,
        reason: 'only the event with a video gets the play button');

    await tester.tap(find.byIcon(Icons.play_circle_outline));
    await tester.pumpAndSettle();
    expect(opened, ['clip.mp4']);
  });

  testWidgets('no play button when openVideo is not provided (desktop)',
      (tester) async {
    final store = _MemoryStore();

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: EventsScreen(
          loader: () async => [row(videoName: 'clip.mp4')],
          snapshotStore: store,
        ),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byIcon(Icons.play_circle_outline), findsNothing);
  });
}