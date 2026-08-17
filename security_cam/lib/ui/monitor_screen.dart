import 'package:flutter/material.dart';

import '../sensors/simulated_audio_source.dart';
import '../state/monitor_controller.dart';
import 'widgets/camera_view.dart';

class MonitorScreen extends StatelessWidget {
  final MonitorController controller;

  const MonitorScreen({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final monitoring = controller.state == MonitorState.monitoring;
        return SafeArea(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.videocam_outlined),
                title: Text(controller.settings.cameraName),
                subtitle: Text(switch (controller.state) {
                  MonitorState.idle => 'Idle',
                  MonitorState.starting => 'Starting…',
                  MonitorState.monitoring => 'Monitoring',
                  MonitorState.error => 'Error',
                }),
                trailing: monitoring
                    ? const Icon(Icons.circle, color: Colors.red, size: 12)
                    : null,
              ),
              Expanded(
                child: Center(
                  child: controller.analysisFrames == null
                      ? const Text('Start monitoring to view the camera')
                      : Padding(
                          padding: const EdgeInsets.all(8),
                          child: CameraView(
                            frames: controller.analysisFrames!,
                          ),
                        ),
                ),
              ),
              if (controller.state == MonitorState.error)
                Padding(
                  padding: const EdgeInsets.all(8),
                  child: Text(
                    'Error: ${controller.error}',
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Expanded(
                      child: FilledButton.icon(
                        onPressed: monitoring
                            ? () => controller.stop()
                            : () => controller.start(),
                        icon: Icon(monitoring ? Icons.stop : Icons.play_arrow),
                        label: Text(monitoring ? 'Stop' : 'Start'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: InputDecorator(
                        decoration: const InputDecoration(
                          labelText: 'Audio scene',
                          border: OutlineInputBorder(),
                        ),
                        child: DropdownButtonHideUnderline(
                          child: DropdownButton<AudioScene>(
                            value: controller.audioScene,
                            isDense: true,
                            items: AudioScene.values
                                .map((s) => DropdownMenuItem(
                                      value: s,
                                      child: Text(_sceneLabel(s)),
                                    ))
                                .toList(),
                            onChanged: (scene) {
                              if (scene != null) {
                                controller.setAudioScene(scene);
                              }
                            },
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  String _sceneLabel(AudioScene scene) {
    return switch (scene) {
      AudioScene.babyCry => 'Baby crying',
      AudioScene.glassBreak => 'Glass breaking',
      AudioScene.bang => 'Loud noise',
      AudioScene.silence => 'Silence',
    };
  }
}