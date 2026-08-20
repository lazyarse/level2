import 'package:flutter/material.dart';

import '../core/models.dart';
import '../sensors/simulated_audio_source.dart';
import '../sensors/android_camera_session.dart' show PreviewInfo;
import '../state/monitor_controller.dart';
import 'widgets/camera_view.dart';

class MonitorScreen extends StatefulWidget {
  final MonitorController controller;

  const MonitorScreen({super.key, required this.controller});

  @override
  State<MonitorScreen> createState() => _MonitorScreenState();
}

class _MonitorScreenState extends State<MonitorScreen> {
  bool _showRegions = false;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.controller,
      builder: (context, _) {
        final controller = widget.controller;
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
                  child: _buildView(controller),
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
                child: Column(
                  children: [
                    Row(
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
                        if (widget.controller.supportsDevSources) ...[
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
                      ],
                    ),
                    if (monitoring)
                      SwitchListTile(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Show regions'),
                        subtitle: const Text(
                          'Display the inclusion zones on the live feed.',
                          style: TextStyle(fontSize: 12),
                        ),
                        value: _showRegions,
                        onChanged: (v) => setState(() => _showRegions = v),
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

  Widget _buildView(MonitorController controller) {
    final previewId = controller.previewTextureId;
    final monitoring = controller.state == MonitorState.monitoring;
    if (monitoring && previewId != null) {
      return _PreviewTexture(
        controller: controller,
        textureId: previewId,
        regions: controller.settings.detectionRegions,
        showRegions: _showRegions,
      );
    }
    if (controller.analysisFrames == null) {
      return const Text('Start monitoring to view the camera');
    }
    return Padding(
      padding: const EdgeInsets.all(8),
      child: CameraView(
        frames: controller.analysisFrames!,
        regions: controller.settings.detectionRegions,
        showRegions: _showRegions,
      ),
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

/// Full-color live camera passthrough via the native CameraX Preview use case,
/// rendered through a Flutter texture. The engine applies the SurfaceTexture
/// transform matrix (set by CameraX on a directly-connected surface), which
/// already rotates the sensor output to the natural orientation, so no extra
/// rotation is applied here. Detection regions are overlaid in preview-space
/// coordinates when [showRegions] is set.
class _PreviewTexture extends StatelessWidget {
  final MonitorController controller;
  final int textureId;
  final List<DetectionRegion> regions;
  final bool showRegions;

  const _PreviewTexture({
    required this.controller,
    required this.textureId,
    required this.regions,
    required this.showRegions,
  });

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<PreviewInfo?>(
      future: controller.getPreviewInfo(),
      builder: (context, snapshot) {
        final info = snapshot.data;
        final size = info?.size;
        final ratio = size != null ? size.width / size.height : 3 / 4;
        return AspectRatio(
          aspectRatio: ratio,
          child: ClipRect(
            child: Stack(
              fit: StackFit.expand,
              children: [
                Texture(textureId: textureId),
                if (showRegions && regions.isNotEmpty)
                  CustomPaint(
                    painter: _PreviewRegionOverlayPainter(
                      regions,
                      rotationDegrees: info?.rotationDegrees ?? 0,
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// Draws the inclusion regions over the live preview. Regions are normalized
/// 0..1 in analysis-frame (sensor) space. The preview shows the same sensor
/// content in its natural orientation; to land the outlines on the right spot,
/// each normalized point is rotated clockwise by [rotationDegrees] before
/// scaling to the widget size.
class _PreviewRegionOverlayPainter extends CustomPainter {
  final List<DetectionRegion> regions;
  final int rotationDegrees;

  _PreviewRegionOverlayPainter(this.regions, {this.rotationDegrees = 0});

  static const _palette = [
    Color(0xCC8AB4F8),
    Color(0xCC81C995),
    Color(0xCCFDD663),
    Color(0xCCF28B82),
    Color(0xCCD7AEFB),
  ];

  /// Maps a normalized analysis-frame point (x, y) through the clockwise
  /// sensor rotation into preview space.
  Offset _map(double x, double y) {
    return switch (rotationDegrees) {
      90 => Offset(1 - y, x),
      180 => Offset(1 - x, 1 - y),
      270 => Offset(y, 1 - x),
      _ => Offset(x, y),
    };
  }

  @override
  void paint(Canvas canvas, Size size) {
    for (var i = 0; i < regions.length; i++) {
      final r = regions[i];
      final stroke = Paint()
        ..color = _palette[i % _palette.length]
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..isAntiAlias = false;
      if (r.shape == DetectionRegionShape.rect) {
        final p0 = _map(r.points[0], r.points[1]);
        final p1 = _map(r.points[2], r.points[3]);
        canvas.drawRect(
          Rect.fromPoints(
            Offset(p0.dx * size.width, p0.dy * size.height),
            Offset(p1.dx * size.width, p1.dy * size.height),
          ),
          stroke,
        );
      } else {
        final path = Path();
        for (var k = 0; k < r.points.length; k += 2) {
          final p = _map(r.points[k], r.points[k + 1]);
          final o = Offset(p.dx * size.width, p.dy * size.height);
          k == 0 ? path.moveTo(o.dx, o.dy) : path.lineTo(o.dx, o.dy);
        }
        path.close();
        canvas.drawPath(path, stroke);
      }
    }
  }

  @override
  bool shouldRepaint(_PreviewRegionOverlayPainter oldDelegate) =>
      oldDelegate.regions != regions ||
      oldDelegate.rotationDegrees != rotationDegrees;
}
