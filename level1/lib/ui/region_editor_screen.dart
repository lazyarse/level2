import 'dart:async';

import 'package:flutter/material.dart';

import '../core/models.dart';
import '../detection/regions/region_filter.dart';
import 'widgets/camera_view.dart';

/// Full-screen inclusion-region editor: draws rects/polys over a live analysis
/// preview, then reports the final list via [onSave] on Done.
class RegionEditorScreen extends StatefulWidget {
  final Stream<AnalysisFrame> frames;
  final List<DetectionRegion> initialRegions;
  final ValueChanged<List<DetectionRegion>> onSave;

  const RegionEditorScreen({
    super.key,
    required this.frames,
    required this.initialRegions,
    required this.onSave,
  });

  @override
  State<RegionEditorScreen> createState() => _RegionEditorScreenState();
}

class _RegionEditorScreenState extends State<RegionEditorScreen> {
  late List<DetectionRegion> _regions;
  String _shape = DetectionRegionShape.rect;
  int _selected = -1;
  int _nextId = 1;
  List<double>? _pendingPoly;
  Size _previewSize = Size.zero;
  Offset? _dragStart;
  Offset? _dragLast;
  List<double>? _dragRect; // normalized [x0,y0,x1,y1] while dragging a new rect
  bool _dragResizing = false;
  bool _dragMoving = false;
  final TextEditingController _labelController = TextEditingController();

  static const _palette = [
    Color(0xFF8AB4F8),
    Color(0xFF81C995),
    Color(0xFFFDD663),
    Color(0xFFF28B82),
    Color(0xFFD7AEFB),
  ];

  @override
  void initState() {
    super.initState();
    _regions = List.of(widget.initialRegions);
  }

  @override
  void dispose() {
    _labelController.dispose();
    super.dispose();
  }

  /// Selects a region and syncs the label field with its name.
  void _select(int index) {
    setState(() {
      _selected = index;
      if (index >= 0 && index < _regions.length) {
        _labelController.text = _regions[index].label;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Detection regions'),
        actions: [
          TextButton(
            onPressed: () {
              widget.onSave(List.of(_regions));
              Navigator.of(context).maybePop();
            },
            child: const Text('Done'),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    _previewSize = constraints.biggest;
                    return ClipRect(
                      child: GestureDetector(
                        onTapUp: _onTap,
                        onPanStart: _onPanStart,
                        onPanUpdate: _onPanUpdate,
                        onPanEnd: _onPanEnd,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            CameraView(frames: widget.frames),
                            CustomPaint(
                              size: Size.infinite,
                              painter: _RegionPainter(
                                regions: _regions,
                                pendingPoly: _pendingPoly,
                                dragRect: _dragRect,
                                selected: _selected,
                                palette: _palette,
                              ),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                children: [
                  Row(
                    children: [
                      _toolButton('Rectangle', _shape == DetectionRegionShape.rect,
                          () => setState(() {
                                _shape = DetectionRegionShape.rect;
                                _pendingPoly = null;
                              })),
                      const SizedBox(width: 8),
                      _toolButton('Polygon', _shape == DetectionRegionShape.poly,
                          () => setState(() => _shape = DetectionRegionShape.poly)),
                      const SizedBox(width: 8),
                      if (_pendingPoly != null) ...[
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _commitPoly,
                            icon: const Icon(Icons.check),
                            label: const Text('Close poly'),
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                      OutlinedButton.icon(
                        onPressed: () => _addRegion(),
                        icon: const Icon(Icons.add),
                        label: const Text('Add'),
                      ),
                      const SizedBox(width: 8),
                      OutlinedButton.icon(
                        onPressed: () => _confirmClear(),
                        icon: const Icon(Icons.delete_forever),
                        label: const Text('Clear'),
                      ),
                    ],
                  ),
                  if (_regions.isNotEmpty)
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxHeight: 140),
                      child: ListView.separated(
                        shrinkWrap: true,
                        itemCount: _regions.length,
                        separatorBuilder: (_, _) =>
                            const Divider(height: 1),
                        itemBuilder: (context, i) => ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          leading: Icon(Icons.crop_square,
                              color: _palette[i % _palette.length]),
                          title: Text(_regions[i].label),
                          subtitle: Text(_regions[i].shape),
                          selected: i == _selected,
                          onTap: () => _select(i),
                          trailing: IconButton(
                            icon: const Icon(Icons.close, size: 18),
                            tooltip: 'Delete region',
                            onPressed: () => setState(() {
                              _regions.removeAt(i);
                              if (_selected == i) {
                                _selected = -1;
                                _labelController.clear();
                              } else if (_selected > i) {
                                _selected--;
                              }
                            }),
                          ),
                        ),
                      ),
                    ),
                  if (_selected >= 0 && _selected < _regions.length)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _labelController,
                              decoration: const InputDecoration(
                                labelText: 'Region name',
                                isDense: true,
                                border: OutlineInputBorder(),
                              ),
                              onChanged: (v) => setState(() {
                                final r = _regions[_selected];
                                _regions[_selected] = DetectionRegion(
                                  id: r.id,
                                  shape: r.shape,
                                  label: v.trim().isEmpty ? r.label : v.trim(),
                                  points: r.points,
                                );
                              }),
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline),
                            tooltip: 'Delete region',
                            onPressed: () => setState(() {
                              _regions.removeAt(_selected);
                              _selected = -1;
                              _labelController.clear();
                            }),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _toolButton(String label, bool active, VoidCallback onTap) {
    return OutlinedButton(
      style: OutlinedButton.styleFrom(
        backgroundColor: active ? Theme.of(context).colorScheme.primaryContainer : null,
      ),
      onPressed: onTap,
      child: Text(label),
    );
  }

  Offset _toNorm(Offset p) {
    final s = _previewSize;
    return Offset(
      (p.dx / s.width).clamp(0.0, 1.0),
      (p.dy / s.height).clamp(0.0, 1.0),
    );
  }

  void _onTapUpGlobal(Offset pos) {
    if (_shape == DetectionRegionShape.poly) {
      final n = _toNorm(pos);
      // First tap in poly mode STARTS the pending polygon.
      _pendingPoly ??= <double>[];
      _pendingPoly!.addAll([n.dx, n.dy]);
      setState(() {});
      return;
    }
    // Select region under tap (or deselect).
    _select(_hitRegion(_toNorm(pos)));
  }

  void _onTap(TapUpDetails d) => _onTapUpGlobal(d.localPosition);

  int _hitRegion(Offset n) {
    for (var i = _regions.length - 1; i >= 0; i--) {
      if (pointInRegion(_regions[i], n.dx, n.dy)) return i;
    }
    return -1;
  }

  void _onPanStart(DragStartDetails d) {
    final n = _toNorm(d.localPosition);
    final hit = _hitRegion(n);
    if (hit >= 0) {
      _select(hit);
      final r = _regions[hit];
      if (r.shape == DetectionRegionShape.rect && _nearCorner(r, n.dx, n.dy)) {
        _dragResizing = true;
      } else {
        _dragMoving = true;
      }
    } else if (_shape == DetectionRegionShape.rect) {
      // Start a new rectangle at the drag origin.
      setState(() {
        _selected = -1;
        _pendingPoly = null;
        _dragStart = Offset(n.dx, n.dy);
        _dragLast = Offset(n.dx, n.dy);
        _dragRect = [n.dx, n.dy, n.dx, n.dy];
      });
    }
  }

  void _onPanUpdate(DragUpdateDetails d) {
    final n = _toNorm(d.localPosition);
    setState(() {
      if (_dragResizing) {
        final r = _regions[_selected];
        _regions[_selected] = DetectionRegion(
          id: r.id,
          shape: r.shape,
          label: r.label,
          points: [r.points[0], r.points[1], n.dx, n.dy],
        );
      } else if (_dragMoving) {
        final r = _regions[_selected];
        final dx = n.dx - _dragLast!.dx;
        final dy = n.dy - _dragLast!.dy;
        _regions[_selected] = DetectionRegion(
          id: r.id,
          shape: r.shape,
          label: r.label,
          points: _translate(r.points, dx, dy),
        );
        _dragLast = n;
      } else if (_dragRect != null) {
        _dragLast = n;
        final (x0, y0) = (_dragStart!.dx, _dragStart!.dy);
        _dragRect = [
          x0 < n.dx ? x0 : n.dx,
          y0 < n.dy ? y0 : n.dy,
          x0 > n.dx ? x0 : n.dx,
          y0 > n.dy ? y0 : n.dy,
        ];
      }
    });
  }

  void _onPanEnd(DragEndDetails d) {
    final rect = _dragRect;
    setState(() {
      _dragStart = null;
      _dragLast = null;
      _dragResizing = false;
      _dragMoving = false;
      _dragRect = null;
    });
    // Commit the newly drawn rectangle (skipped for tiny drags).
    if (rect != null && (rect[2] - rect[0]).abs() >= 0.02 &&
        (rect[3] - rect[1]).abs() >= 0.02) {
      _regions.add(DetectionRegion(
        id: 'r${_nextId++}',
        shape: DetectionRegionShape.rect,
        label: 'Region ${_nextId - 1}',
        points: rect,
      ));
      _select(_regions.length - 1);
    }
  }

  List<double> _translate(List<double> pts, double dx, double dy) {
    final out = <double>[];
    for (var i = 0; i < pts.length; i += 2) {
      out
        ..add((pts[i] + dx).clamp(0.0, 1.0))
        ..add((pts[i + 1] + dy).clamp(0.0, 1.0));
    }
    return out;
  }

  bool _nearCorner(DetectionRegion r, double x, double y) {
    const tol = 0.06;
    final x0 = r.points[0], y0 = r.points[1];
    final x1 = r.points[2], y1 = r.points[3];
    return ((x - x0).abs() <= tol && (y - y0).abs() <= tol) ||
        ((x - x1).abs() <= tol && (y - y1).abs() <= tol);
  }

  void _addRegion() {
    _pendingPoly = null;
    _regions.add(DetectionRegion(
      id: 'r${_nextId++}',
      shape: DetectionRegionShape.rect,
      label: 'Region ${_nextId - 1}',
      points: const [0.2, 0.2, 0.8, 0.8],
    ));
    _select(_regions.length - 1);
  }

  void _commitPoly() {
    final p = _pendingPoly;
    _pendingPoly = null;
    if (p == null || p.length < 6) return;
    _regions.add(DetectionRegion(
      id: 'r${_nextId++}',
      shape: DetectionRegionShape.poly,
      label: 'Region ${_nextId - 1}',
      points: p,
    ));
    _select(_regions.length - 1);
  }

  Future<void> _confirmClear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Clear all regions?'),
        content: const Text('This removes every inclusion region. Detection '
            'will apply to the whole frame.'),
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
    if (confirmed == true) {
      setState(() {
        _regions.clear();
        _selected = -1;
        _pendingPoly = null;
      });
    }
  }
}

class _RegionPainter extends CustomPainter {
  final List<DetectionRegion> regions;
  final List<double>? pendingPoly;
  final List<double>? dragRect;
  final int selected;
  final List<Color> palette;

  _RegionPainter({
    required this.regions,
    required this.pendingPoly,
    required this.dragRect,
    required this.selected,
    required this.palette,
  });

  @override
  void paint(Canvas canvas, Size size) {
    for (var i = 0; i < regions.length; i++) {
      final r = regions[i];
      final paint = Paint()
        ..color = palette[i % palette.length].withValues(alpha: 0.18)
        ..style = PaintingStyle.fill;
      final stroke = Paint()
        ..color = palette[i % palette.length]
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2
        ..isAntiAlias = false;
      final isSelected = i == selected;
      if (r.shape == DetectionRegionShape.rect) {
        final x0 = r.points[0] * size.width, y0 = r.points[1] * size.height;
        final x1 = r.points[2] * size.width, y1 = r.points[3] * size.height;
        final rect = Rect.fromLTRB(x0, y0, x1, y1);
        canvas.drawRect(rect, paint);
        canvas.drawRect(rect, stroke);
        if (isSelected) {
          final handle = Paint()..color = palette[i % palette.length];
          for (final c in [rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight]) {
            canvas.drawCircle(c, 5, handle);
          }
        }
      } else {
        final path = Path();
        for (var k = 0; k < r.points.length; k += 2) {
          final p = Offset(r.points[k] * size.width, r.points[k + 1] * size.height);
          k == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
        }
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawPath(path, stroke);
        if (isSelected) {
          final handle = Paint()..color = palette[i % palette.length];
          for (var k = 0; k < r.points.length; k += 2) {
            canvas.drawCircle(
                Offset(r.points[k] * size.width, r.points[k + 1] * size.height), 5, handle);
          }
        }
      }
    }
    final p = pendingPoly;
    if (p != null && p.length >= 2) {
      final stroke = Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5;
      final path = Path();
      for (var k = 0; k < p.length; k += 2) {
        final pt = Offset(p[k] * size.width, p[k + 1] * size.height);
        k == 0 ? path.moveTo(pt.dx, pt.dy) : path.lineTo(pt.dx, pt.dy);
      }
      canvas.drawPath(path, stroke);
    }
    final dr = dragRect;
    if (dr != null) {
      final stroke = Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5
        ..isAntiAlias = false;
      canvas.drawRect(
        Rect.fromLTRB(dr[0] * size.width, dr[1] * size.height,
            dr[2] * size.width, dr[3] * size.height),
        stroke,
      );
    }
  }

  @override
  bool shouldRepaint(_RegionPainter oldDelegate) =>
      oldDelegate.regions != regions ||
      oldDelegate.pendingPoly != pendingPoly ||
      oldDelegate.dragRect != dragRect ||
      oldDelegate.selected != selected;
}