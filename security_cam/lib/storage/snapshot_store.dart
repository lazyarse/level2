import 'dart:io';

import '../core/models.dart';

abstract class SnapshotStore {
  Future<String> save(Snapshot snapshot);

  Future<Snapshot?> load(String name);

  Future<void> delete(String name);
}

class FileSnapshotStore implements SnapshotStore {
  final String directoryPath;

  FileSnapshotStore(this.directoryPath);

  String _pathFor(String name) {
    final safe = name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    return '$directoryPath/$safe';
  }

  @override
  Future<String> save(Snapshot snapshot) async {
    final dir = Directory(directoryPath);
    if (!await dir.exists()) await dir.create(recursive: true);
    final file = File(_pathFor(snapshot.name));
    await file.writeAsBytes(snapshot.bytes, flush: true);
    return file.path;
  }

  @override
  Future<Snapshot?> load(String name) async {
    final file = File(_pathFor(name));
    if (!await file.exists()) return null;
    final bytes = await file.readAsBytes();
    final mime = name.endsWith('.png')
        ? 'image/png'
        : name.endsWith('.jpg')
            ? 'image/jpeg'
            : 'application/octet-stream';
    return Snapshot(bytes: bytes, mimeType: mime, name: name);
  }

  @override
  Future<void> delete(String name) async {
    final file = File(_pathFor(name));
    if (await file.exists()) await file.delete();
  }
}