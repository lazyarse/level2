import 'package:flutter/material.dart';

import '../event/event_pipeline.dart';
import '../storage/event_log.dart';

class EventsScreen extends StatefulWidget {
  final Future<List<RecordedEventRow>> Function() loader;

  const EventsScreen({super.key, required this.loader});

  @override
  State<EventsScreen> createState() => _EventsScreenState();
}

class _EventsScreenState extends State<EventsScreen> {
  late Future<List<RecordedEventRow>> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.loader();
  }

  void _reload() {
    setState(() => _future = widget.loader());
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
                      leading: Icon(_iconFor(iconType)),
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