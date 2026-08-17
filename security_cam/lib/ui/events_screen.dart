import 'package:flutter/material.dart';

import '../core/models.dart';
import '../event/event_pipeline.dart';
import '../storage/event_log.dart';
import '../storage/snapshot_store.dart';

class EventsScreen extends StatefulWidget {
  final Future<List<RecordedEventRow>> Function() loader;
  final SnapshotStore snapshotStore;
  final int reloadTick;

  const EventsScreen({
    super.key,
    required this.loader,
    required this.snapshotStore,
    this.reloadTick = 0,
  });

  @override
  State<EventsScreen> createState() => _EventsScreenState();
}

class _EventsScreenState extends State<EventsScreen> {
  late Future<List<RecordedEventRow>> _future;

  void _reload() {
    setState(() {
      _future = widget.loader();
    });
  }

  @override
  void initState() {
    super.initState();
    _future = widget.loader();
  }

  @override
  void didUpdateWidget(EventsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.reloadTick != widget.reloadTick) {
      _reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Row(
              children: [
                Text('Trigger events', style: Theme.of(context).textTheme.titleMedium),
                const Spacer(),
                IconButton(
                  onPressed: _reload,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
          ),
          Expanded(
            child: FutureBuilder<List<RecordedEventRow>>(
              future: _future,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                final events = snapshot.data ?? [];
                if (events.isEmpty) {
                  return const Center(child: Text('No events yet'));
                }
                return ListView.separated(
                  itemCount: events.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, index) {
                    final e = events[index];
                    final statuses = e.channelStatuses.entries
                        .map((s) => '${s.key}=${s.value}')
                        .join(', ');
                    final typeLabel = e.triggerTypes.isEmpty
                        ? triggerLabel(e.triggerType)
                        : e.triggerTypes.map(triggerLabel).join(' + ');
                    final iconType = e.triggerTypes.isNotEmpty
                        ? e.triggerTypes.first
                        : e.triggerType;
                    return ListTile(
                      leading: e.snapshotName == null
                          ? Icon(_iconFor(iconType))
                          : _SnapshotThumb(
                              snapshotStore: widget.snapshotStore,
                              name: e.snapshotName!,
                              icon: _iconFor(iconType),
                              title: typeLabel,
                            ),
                      title: Text(
                          '$typeLabel · score ${e.score.toStringAsFixed(2)}'),
                      subtitle: Text(
                        '${e.timestamp.toLocal()} — ${e.cameraName}${statuses.isEmpty ? '' : ' — $statuses'}',
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  IconData _iconFor(String type) {
    return switch (type) {
      'motion' => Icons.directions_run,
      'baby_cry' => Icons.child_care,
      'glass_break' => Icons.broken_image,
      'loud_noise' => Icons.volume_up,
      _ => Icons.notification_important,
    };
  }
}

class _SnapshotThumb extends StatefulWidget {
  final SnapshotStore snapshotStore;
  final String name;
  final IconData icon;
  final String title;

  const _SnapshotThumb({
    required this.snapshotStore,
    required this.name,
    required this.icon,
    required this.title,
  });

  @override
  State<_SnapshotThumb> createState() => _SnapshotThumbState();
}

class _SnapshotThumbState extends State<_SnapshotThumb> {
  late final Future<Snapshot?> _future = widget.snapshotStore.load(widget.name);

  void _showFull(Snapshot snapshot) {
    showDialog<void>(
      context: context,
      builder: (context) => Dialog(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(8),
              child: Text(widget.title),
            ),
            InteractiveViewer(
              maxScale: 8,
              child: Image.memory(
                snapshot.bytes,
                width: 480,
                height: 360,
                fit: BoxFit.contain,
                gaplessPlayback: true,
              ),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Close'),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 48,
      height: 48,
      child: FutureBuilder<Snapshot?>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(
              child: SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            );
          }
          final data = snapshot.data;
          if (data == null) {
            return Icon(widget.icon);
          }
          return ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: GestureDetector(
              onTap: () => _showFull(data),
              child: Image.memory(
                data.bytes,
                width: 48,
                height: 48,
                fit: BoxFit.cover,
                gaplessPlayback: true,
              ),
            ),
          );
        },
      ),
    );
  }
}