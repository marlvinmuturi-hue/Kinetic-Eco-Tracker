import 'package:flutter/material.dart';

void main() {
  runApp(const KineticEcoFlutterApp());
}

class KineticEcoFlutterApp extends StatelessWidget {
  const KineticEcoFlutterApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Kinetic Eco Tracker',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo)),
      home: const Scaffold(
        body: Center(
          child: Text('Add MobileAds.initialize() and BannerAd here when ready.'),
        ),
      ),
    );
  }
}
