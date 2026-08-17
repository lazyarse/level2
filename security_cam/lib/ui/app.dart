import 'package:flutter/material.dart';

import '../state/monitor_controller.dart';
import '../storage/event_log.dart';
import 'events_screen.dart';
import 'monitor_screen.dart';
import 'settings_screen.dart';

class SecurityCamApp extends StatelessWidget {
  final MonitorController controller;
  final Future<List<RecordedEventRow>> Function() eventLoader;

  const SecurityCamApp({
    super.key,
    required this.controller,
    required this.eventLoader,
  });

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Security Cam',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: _Shell(
        controller: controller,
        eventLoader: eventLoader,
      ),
    );
  }
}

class _Shell extends StatefulWidget {
  final MonitorController controller;
  final Future<List<RecordedEventRow>> Function() eventLoader;

  const _Shell({required this.controller, required this.eventLoader});

  @override
  State<_Shell> createState() => _ShellState();
}

class _ShellState extends State<_Shell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final screens = [
      MonitorScreen(controller: widget.controller),
      EventsScreen(loader: widget.eventLoader),
      SettingsScreen(controller: widget.controller),
    ];
    return Scaffold(
      body: IndexedStack(index: _index, children: screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.videocam_outlined),
            selectedIcon: Icon(Icons.videocam),
            label: 'Monitor',
          ),
          NavigationDestination(
            icon: Icon(Icons.history),
            label: 'Events',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'Settings',
          ),
        ],
      ),
    );
  }
}