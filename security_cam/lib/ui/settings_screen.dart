import 'package:flutter/material.dart';

import '../channels/telegram_channel.dart';
import '../core/channel.dart';
import '../core/detector.dart';
import '../core/models.dart';
import '../core/settings.dart';
import '../state/monitor_controller.dart';

class SettingsScreen extends StatefulWidget {
  final MonitorController controller;

  const SettingsScreen({super.key, required this.controller});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late AppSettings _draft;
  final _nameController = TextEditingController();
  final _tokenControllers = <String, TextEditingController>{};
  final _chatControllers = <String, TextEditingController>{};

  @override
  void initState() {
    super.initState();
    _draft = widget.controller.settings;
    _nameController.text = _draft.cameraName;
    for (final c in _draft.channelConfigs) {
      if (c.type == 'telegram') {
        _tokenControllers[c.id] =
            TextEditingController(text: _telegram(c).botToken);
        _chatControllers[c.id] =
            TextEditingController(text: _telegram(c).chatId);
      }
    }
  }

  TelegramChannelSettings _telegram(ChannelConfig c) {
    return TelegramChannelSettings.fromJson(c.settingsJson);
  }

  @override
  void dispose() {
    _nameController.dispose();
    for (final c in _tokenControllers.values) {
      c.dispose();
    }
    for (final c in _chatControllers.values) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: _nameController,
            decoration: const InputDecoration(
              labelText: 'Camera name',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 24),
          Text('Detectors', style: Theme.of(context).textTheme.titleMedium),
          for (final type in _draft.detectorConfigs.keys)
            _DetectorCard(
              config: _draft.detectorConfigs[type]!,
              channelIds: _draft.channelConfigs.map((c) => c.id).toList(),
              onChanged: (next) => setState(() {
                _draft = _draft.copyWith(
                  detectorConfigs: {
                    ..._draft.detectorConfigs,
                    type: next,
                  },
                );
              }),
            ),
          const SizedBox(height: 24),
          Text('Channels', style: Theme.of(context).textTheme.titleMedium),
          for (final c in _draft.channelConfigs) _channelCard(c),
          const SizedBox(height: 24),
          Text('Notifications', style: Theme.of(context).textTheme.titleMedium),
          Text(
            'Merge window: ${_mergeLabel(_draft.notificationMergeWindow)}',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          Slider(
            value: _draft.notificationMergeWindow.inSeconds
                .clamp(0, 30)
                .toDouble(),
            min: 0,
            max: 30,
            divisions: 30,
            label: _mergeLabel(_draft.notificationMergeWindow),
            onChanged: (v) => setState(() {
              _draft = _draft.copyWith(
                notificationMergeWindow: Duration(seconds: v.round()),
              );
            }),
          ),
          const SizedBox(height: 24),
          Text('Events', style: Theme.of(context).textTheme.titleMedium),
          OutlinedButton.icon(
            onPressed: () => _confirmClearEvents(const Duration(hours: 24)),
            icon: const Icon(Icons.delete_sweep_outlined),
            label: const Text('Clear events older than 24h'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () => _confirmClearEvents(null),
            icon: const Icon(Icons.delete_forever_outlined),
            label: const Text('Clear all events'),
          ),
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _save,
            icon: const Icon(Icons.save),
            label: const Text('Save settings'),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmClearEvents(Duration? olderThan) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear events'),
        content: Text(olderThan == null
            ? 'Delete ALL recorded events and their snapshots?'
            : 'Delete events older than ${olderThan.inHours}h and their snapshots?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Clear'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await widget.controller.clearEvents(olderThan: olderThan);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Events cleared')),
    );
  }

  String _mergeLabel(Duration window) {
    return window == Duration.zero ? 'Off' : '${window.inSeconds}s';
  }

  Widget _channelCard(ChannelConfig config) {
    final isTelegram = config.type == 'telegram';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(config.type),
              value: config.enabled,
              onChanged: (v) => setState(() {
                _draft = _draft.copyWith(
                  channelConfigs: [
                    for (final c in _draft.channelConfigs)
                      c.id == config.id
                          ? c.copyWith(enabled: v)
                          : c,
                  ],
                );
              }),
            ),
            if (isTelegram) ...[
              TextField(
                controller: _tokenControllers[config.id],
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Bot token'),
                onChanged: (_) => setState(() {}),
              ),
              TextField(
                controller: _chatControllers[config.id],
                decoration: const InputDecoration(labelText: 'Chat ID'),
                onChanged: (_) => setState(() {}),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _save() async {
    final channels = <ChannelConfig>[];
    for (final c in _draft.channelConfigs) {
      if (c.type == 'telegram') {
        channels.add(c.copyWith(
          settingsJson: TelegramChannelSettings(
            botToken: _tokenControllers[c.id]?.text ?? '',
            chatId: _chatControllers[c.id]?.text ?? '',
          ).toJson(),
        ));
      } else {
        channels.add(c);
      }
    }
    final next = _draft.copyWith(
      cameraName: _nameController.text.trim().isEmpty
          ? _draft.cameraName
          : _nameController.text.trim(),
      channelConfigs: channels,
    );
    await widget.controller.updateSettings(next);
    setState(() => _draft = next);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Settings saved')),
    );
  }
}

class _DetectorCard extends StatelessWidget {
  final DetectorConfig config;
  final List<String> channelIds;
  final ValueChanged<DetectorConfig> onChanged;

  const _DetectorCard({
    required this.config,
    required this.channelIds,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(_label(config.type)),
              value: config.enabled,
              onChanged: (v) => onChanged(config.copyWith(enabled: v)),
            ),
            if (config.enabled) ...[
              Text('Threshold: ${config.threshold.toStringAsFixed(2)}'),
              Slider(
                value: config.threshold.clamp(0.0, 1.0),
                onChanged: (v) => onChanged(config.copyWith(threshold: v)),
              ),
              Row(
                children: [
                  Text('Persistence: ${config.persistenceFrames}'),
                  IconButton(
                    icon: const Icon(Icons.remove_circle_outline),
                    onPressed: config.persistenceFrames > 1
                        ? () => onChanged(config.copyWith(
                            persistenceFrames: config.persistenceFrames - 1))
                        : null,
                  ),
                  IconButton(
                    icon: const Icon(Icons.add_circle_outline),
                    onPressed: () => onChanged(config.copyWith(
                        persistenceFrames: config.persistenceFrames + 1)),
                  ),
                ],
              ),
              Text('Cooldown: ${config.cooldown.inSeconds}s'),
              Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.remove_circle_outline),
                    onPressed: config.cooldown.inSeconds > 0
                        ? () => onChanged(config.copyWith(
                            cooldown: Duration(
                                seconds: config.cooldown.inSeconds - 15)))
                        : null,
                  ),
                  IconButton(
                    icon: const Icon(Icons.add_circle_outline),
                    onPressed: config.cooldown.inSeconds < 600
                        ? () => onChanged(config.copyWith(
                            cooldown: Duration(
                                seconds: config.cooldown.inSeconds + 15)))
                        : null,
                  ),
                ],
              ),
              Wrap(
                children: [
                  for (final id in channelIds)
                    CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      title: Text(id),
                      value: config.routeToChannelIds.contains(id),
                      onChanged: (checked) {
                        final routes = {...config.routeToChannelIds};
                        if (checked == true) {
                          routes.add(id);
                        } else {
                          routes.remove(id);
                        }
                        onChanged(
                            config.copyWith(routeToChannelIds: routes.toList()));
                      },
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _label(String type) {
    return switch (type) {
      TriggerType.motion => 'Motion',
      TriggerType.babyCry => 'Baby cry',
      TriggerType.glassBreak => 'Glass break',
      TriggerType.loudNoise => 'Loud noise',
      _ => type,
    };
  }
}