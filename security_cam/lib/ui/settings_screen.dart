import 'package:flutter/material.dart';

import '../channels/discord_channel.dart';
import '../channels/email_channel.dart';
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
  final _cameraPathController = TextEditingController();
  final _audioPathController = TextEditingController();
  final _fieldControllers = <String, TextEditingController>{};
  final _emailTls = <String, bool>{};

  @override
  void initState() {
    super.initState();
    _draft = widget.controller.settings;
    _nameController.text = _draft.cameraName;
    _cameraPathController.text = _draft.cameraSourcePath ?? '';
    _audioPathController.text = _draft.audioSourcePath ?? '';
    for (final c in _draft.channelConfigs) {
      switch (c.type) {
        case 'telegram':
          final s = TelegramChannelSettings.fromJson(c.settingsJson);
          _field('${c.id}.token', s.botToken);
          _field('${c.id}.chat', s.chatId);
        case 'email':
          final s = EmailChannelSettings.fromJson(c.settingsJson);
          _field('${c.id}.host', s.host);
          _field('${c.id}.port', s.port.toString());
          _field('${c.id}.username', s.username);
          _field('${c.id}.password', s.password);
          _field('${c.id}.from', s.from);
          _field('${c.id}.to', s.to);
          _emailTls[c.id] = s.useTls;
        case 'discord':
          final s = DiscordChannelSettings.fromJson(c.settingsJson);
          _field('${c.id}.webhook', s.webhookUrl);
      }
    }
  }

TextEditingController _field(String key, [String? text]) =>
    _fieldControllers.putIfAbsent(
        key, () => TextEditingController(text: text ?? ''));

  @override
  void dispose() {
    _nameController.dispose();
    _cameraPathController.dispose();
    _audioPathController.dispose();
    for (final c in _fieldControllers.values) {
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
          Text('Sources', style: Theme.of(context).textTheme.titleMedium),
          const Text(
            'Dev-time only: mobile builds always use the on-device camera and ignore these.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 8),
          InputDecorator(
            decoration: const InputDecoration(
              labelText: 'Camera source',
              border: OutlineInputBorder(),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                value: _draft.cameraSource,
                isDense: true,
                items: const [
                  DropdownMenuItem(
                      value: CameraSource.simulated, child: Text('Simulated')),
                  DropdownMenuItem(
                      value: CameraSource.webcam, child: Text('Webcam')),
                  DropdownMenuItem(
                      value: CameraSource.file, child: Text('Video file')),
                ],
                onChanged: (v) {
                  if (v == null) return;
                  setState(() {
                    _draft = v == CameraSource.simulated
                        ? _draft.copyWith(
                            cameraSource: v, clearCameraSourcePath: true)
                        : _draft.copyWith(cameraSource: v);
                  });
                },
              ),
            ),
          ),
          if (_draft.cameraSource != CameraSource.simulated) ...[
            const SizedBox(height: 8),
            TextField(
              controller: _cameraPathController,
              decoration: InputDecoration(
                labelText: _draft.cameraSource == CameraSource.webcam
                    ? 'Device path'
                    : 'Video file path',
                hintText: _draft.cameraSource == CameraSource.webcam
                    ? '/dev/video0'
                    : '/path/to/clip.mp4',
                border: const OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {}),
            ),
          ],
          const SizedBox(height: 8),
          InputDecorator(
            decoration: const InputDecoration(
              labelText: 'Audio source',
              border: OutlineInputBorder(),
            ),
            child: DropdownButtonHideUnderline(
              child: DropdownButton<String>(
                value: _draft.audioSource,
                isDense: true,
                items: const [
                  DropdownMenuItem(
                      value: AudioInput.simulated, child: Text('Simulated')),
                  DropdownMenuItem(
                      value: AudioInput.mic, child: Text('Microphone')),
                  DropdownMenuItem(
                      value: AudioInput.file, child: Text('Audio file')),
                ],
                onChanged: (v) {
                  if (v == null) return;
                  setState(() {
                    _draft = v == AudioInput.simulated
                        ? _draft.copyWith(
                            audioSource: v, clearAudioSourcePath: true)
                        : _draft.copyWith(audioSource: v);
                  });
                },
              ),
            ),
          ),
          if (_draft.audioSource == AudioInput.file) ...[
            const SizedBox(height: 8),
            TextField(
              controller: _audioPathController,
              decoration: const InputDecoration(
                labelText: 'Audio file path',
                hintText: '/path/to/clip.wav',
                border: OutlineInputBorder(),
              ),
              onChanged: (_) => setState(() {}),
            ),
          ],
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
          for (final c in _draft.channelConfigs)
            if (c.type != 'log') _channelCard(c),
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
          Text('Video clips', style: Theme.of(context).textTheme.titleMedium),
          const Text(
            'Android only: each event captures footage before and after the '
            'trigger and saves it to your gallery.',
            style: TextStyle(fontSize: 12),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Record video locally'),
            subtitle: const Text(
              'Save a clip to your gallery for each event. Off saves '
              'storage and battery.',
              style: TextStyle(fontSize: 12),
            ),
            value: _draft.recordVideo,
            onChanged: (v) => setState(() {
              _draft = _draft.copyWith(recordVideo: v);
            }),
          ),
          Text('Pre-roll: ${_draft.preRollSeconds}s'),
          Slider(
            value: _draft.preRollSeconds.clamp(0, 30).toDouble(),
            min: 0,
            max: 30,
            divisions: 30,
            label: '${_draft.preRollSeconds}s',
            onChanged: (v) => setState(() {
              _draft = _draft.copyWith(preRollSeconds: v.round());
            }),
          ),
          Text('Post-roll: ${_draft.postRollSeconds}s'),
          Slider(
            value: _draft.postRollSeconds.clamp(0, 30).toDouble(),
            min: 0,
            max: 30,
            divisions: 30,
            label: '${_draft.postRollSeconds}s',
            onChanged: (v) => setState(() {
              _draft = _draft.copyWith(postRollSeconds: v.round());
            }),
          ),
          const SizedBox(height: 24),
          Text('Events', style: Theme.of(context).textTheme.titleMedium),
          Text(
            'Automatic retention: '
            '${_draft.retentionDays == 0 ? 'off' : '${_draft.retentionDays} day${_draft.retentionDays == 1 ? '' : 's'}'}',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          Slider(
            value: _draft.retentionDays.clamp(0, 30).toDouble(),
            min: 0,
            max: 30,
            divisions: 30,
            label: _draft.retentionDays == 0
                ? 'Off'
                : '${_draft.retentionDays} days',
            onChanged: (v) => setState(() {
              _draft = _draft.copyWith(retentionDays: v.round());
            }),
          ),
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
            ? 'Delete ALL recorded events and their snapshots and videos?'
            : 'Delete events older than ${olderThan.inHours}h and their '
                'snapshots and videos?'),
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
            ..._channelFields(config),
          ],
        ),
      ),
    );
  }

  List<Widget> _channelFields(ChannelConfig config) {
    switch (config.type) {
      case 'telegram':
        return [
          TextField(
            controller: _field('${config.id}.token'),
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Bot token'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.chat'),
            decoration: const InputDecoration(labelText: 'Chat ID'),
            onChanged: (_) => setState(() {}),
          ),
        ];
      case 'email':
        return [
          TextField(
            controller: _field('${config.id}.host'),
            decoration: const InputDecoration(labelText: 'SMTP host'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.port'),
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Port (587 or 465)'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.username'),
            decoration: const InputDecoration(labelText: 'Username'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.password'),
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Password / app password'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.from'),
            decoration: const InputDecoration(labelText: 'From address'),
            onChanged: (_) => setState(() {}),
          ),
          TextField(
            controller: _field('${config.id}.to'),
            decoration: const InputDecoration(labelText: 'To address'),
            onChanged: (_) => setState(() {}),
          ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Implicit TLS (SSL, port 465)'),
            value: _emailTls[config.id] ?? false,
            onChanged: (v) => setState(() => _emailTls[config.id] = v),
          ),
        ];
      case 'discord':
        return [
          TextField(
            controller: _field('${config.id}.webhook'),
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Webhook URL'),
            onChanged: (_) => setState(() {}),
          ),
        ];
      default:
        return const [];
    }
  }

  Future<void> _save() async {
    final channels = <ChannelConfig>[];
    for (final c in _draft.channelConfigs) {
      switch (c.type) {
        case 'telegram':
          channels.add(c.copyWith(
            settingsJson: TelegramChannelSettings(
              botToken: _field('${c.id}.token').text,
              chatId: _field('${c.id}.chat').text,
            ).toJson(),
          ));
        case 'email':
          channels.add(c.copyWith(
            settingsJson: EmailChannelSettings(
              host: _field('${c.id}.host').text.trim(),
              port: int.tryParse(_field('${c.id}.port').text.trim()) ?? 587,
              username: _field('${c.id}.username').text.trim(),
              password: _field('${c.id}.password').text,
              from: _field('${c.id}.from').text.trim(),
              to: _field('${c.id}.to').text.trim(),
              useTls: _emailTls[c.id] ?? false,
            ).toJson(),
          ));
        case 'discord':
          channels.add(c.copyWith(
            settingsJson: DiscordChannelSettings(
              webhookUrl: _field('${c.id}.webhook').text.trim(),
            ).toJson(),
          ));
        default:
          channels.add(c);
      }
    }
    final sourcePath = _draft.cameraSource == CameraSource.simulated
        ? null
        : _cameraPathController.text.trim();
    final audioPath = _draft.audioSource == AudioInput.file
        ? _audioPathController.text.trim()
        : null;
    final next = _draft.copyWith(
      cameraName: _nameController.text.trim().isEmpty
          ? _draft.cameraName
          : _nameController.text.trim(),
      cameraSourcePath: sourcePath,
      clearCameraSourcePath: sourcePath == null,
      audioSourcePath: audioPath,
      clearAudioSourcePath: audioPath == null,
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