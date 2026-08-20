import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:security_cam/detection/analysis_dispatcher.dart';

void main() {
  test('processes inputs in order when fast', () async {
    final processed = <int>[];
    final dispatcher =
        AnalysisDispatcher<int>(process: (i) async => processed.add(i));
    for (var i = 0; i < 5; i++) {
      dispatcher.add(i);
      await Future<void>.delayed(Duration.zero);
    }
    await dispatcher.dispose();
    expect(processed, [0, 1, 2, 3, 4]);
  });

  test('latest-wins: pending slot is replaced while busy', () async {
    final processed = <int>[];
    final gate = Completer<void>();
    final dispatcher = AnalysisDispatcher<int>(process: (i) async {
      if (i == 0) {
        processed.add(i);
        await gate.future;
      } else {
        processed.add(i);
      }
    });
    dispatcher.add(0);
    await Future<void>.delayed(Duration.zero);
    dispatcher.add(1);
    dispatcher.add(2);
    gate.complete();
    await Future<void>.delayed(Duration.zero);
    await dispatcher.dispose();
    expect(processed, [0, 2]);
  });

  test('a burst of adds yields max concurrency 1', () async {
    var concurrent = 0;
    var maxConcurrent = 0;
    final dispatcher = AnalysisDispatcher<int>(process: (i) async {
      concurrent++;
      if (concurrent > maxConcurrent) maxConcurrent = concurrent;
      await Future<void>.delayed(const Duration(milliseconds: 1));
      concurrent--;
    });
    for (var i = 0; i < 50; i++) {
      dispatcher.add(i);
    }
    await dispatcher.dispose();
    expect(maxConcurrent, 1);
  });

  test('a throwing process is caught, onError fires, loop continues', () async {
    final processed = <int>[];
    final errors = <Object>[];
    final dispatcher = AnalysisDispatcher<int>(
      process: (i) async {
        if (i == 1) throw StateError('boom');
        processed.add(i);
      },
      onError: (error, _) => errors.add(error),
    );
    dispatcher.add(0);
    await Future<void>.delayed(Duration.zero);
    dispatcher.add(1);
    await Future<void>.delayed(Duration.zero);
    dispatcher.add(2);
    await Future<void>.delayed(Duration.zero);
    await dispatcher.dispose();
    expect(errors.map((e) => (e as StateError).message), ['boom']);
    expect(processed, [0, 2]);
  });

  test('dispose clears the pending slot and stops the loop', () async {
    final processed = <int>[];
    final gate = Completer<void>();
    final dispatcher = AnalysisDispatcher<int>(process: (i) async {
      if (i == 0) {
        processed.add(i);
        await gate.future;
      } else {
        processed.add(i);
      }
    });
    dispatcher.add(0);
    await Future<void>.delayed(Duration.zero);
    dispatcher.add(1);
    dispatcher.add(2);
    final disposeFuture = dispatcher.dispose();
    gate.complete();
    await disposeFuture;
    expect(processed, [0]);
    dispatcher.add(3);
    await dispatcher.dispose();
    expect(processed, [0]);
  });
}