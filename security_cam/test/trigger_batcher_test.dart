import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/core/models.dart';
import 'package:security_cam/event/trigger_batcher.dart';

void main() {
  TriggerEvent trigger(String type, DateTime ts) => TriggerEvent(
        timestamp: ts,
        triggerType: type,
        score: 0.9,
        detectorId: type,
      );

  test('merges triggers within the window into one batch', () async {
    var snapshots = 0;
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 200),
      captureSnapshot: () async {
        snapshots++;
        return Snapshot(
          bytes: Uint8List(0),
          mimeType: 'image/png',
          name: 's.png',
        );
      },
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    final t0 = DateTime(2026, 1, 1, 12);
    batcher.add(trigger('motion', t0));
    batcher.add(trigger('baby_cry', t0.add(const Duration(milliseconds: 50))));
    await Future<void>.delayed(const Duration(milliseconds: 300));
    expect(batches, hasLength(1));
    expect(batches.single.triggers.map((e) => e.triggerType),
        ['motion', 'baby_cry']);
    expect(batches.single.snapshot, isNotNull);
    expect(snapshots, 1, reason: 'snapshot should be captured once per batch');
    await batcher.dispose();
  });

  test('flushes a separate batch after the window elapses', () async {
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 100),
      captureSnapshot: () async => null,
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    final t0 = DateTime(2026, 1, 1, 12);
    batcher.add(trigger('motion', t0));
    await Future<void>.delayed(const Duration(milliseconds: 180));
    batcher.add(trigger('baby_cry', t0.add(const Duration(milliseconds: 200))));
    await Future<void>.delayed(const Duration(milliseconds: 180));
    expect(batches, hasLength(2));
    expect(batches[0].triggers.map((e) => e.triggerType), ['motion']);
    expect(batches[1].triggers.map((e) => e.triggerType), ['baby_cry']);
    await batcher.dispose();
  });

  test('capture failure still emits the batch with a null snapshot', () async {
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 100),
      captureSnapshot: () async => throw StateError('no camera'),
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    batcher.add(trigger('motion', DateTime(2026, 1, 1, 12)));
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(batches, hasLength(1));
    expect(batches.single.snapshot, isNull);
    await batcher.dispose();
  });

  test('captures video on the first trigger and names it on the batch', () async {
    final received = <DateTime>[];
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 100),
      captureSnapshot: () async => null,
      captureVideo: (triggerAt) async {
        received.add(triggerAt);
        return 'clip.mp4';
      },
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    final t0 = DateTime(2026, 1, 1, 12);
    batcher.add(trigger('motion', t0));
    batcher.add(trigger('baby_cry', t0.add(const Duration(milliseconds: 40))));
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(batches, hasLength(1));
    expect(batches.single.videoName, 'clip.mp4');
    expect(received, [t0], reason: 'video capture fires once with the first trigger timestamp');
    await batcher.dispose();
  });

  test('video capture failure still emits the batch with a null videoName',
      () async {
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 100),
      captureSnapshot: () async => null,
      captureVideo: (triggerAt) async => throw StateError('not monitoring'),
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    batcher.add(trigger('motion', DateTime(2026, 1, 1, 12)));
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(batches, hasLength(1));
    expect(batches.single.videoName, isNull);
    await batcher.dispose();
  });

  test('no captureVideo hook yields a null videoName', () async {
    final batcher = TriggerBatcher(
      window: const Duration(milliseconds: 100),
      captureSnapshot: () async => null,
    );
    final batches = <TriggerBatch>[];
    batcher.batches.listen(batches.add);
    batcher.add(trigger('motion', DateTime(2026, 1, 1, 12)));
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(batches, hasLength(1));
    expect(batches.single.videoName, isNull);
    await batcher.dispose();
  });
}