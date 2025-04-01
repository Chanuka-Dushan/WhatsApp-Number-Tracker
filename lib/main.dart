import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:convert';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const WhatsAppMonitorApp());
}

class WhatsAppMonitorApp extends StatelessWidget {
  const WhatsAppMonitorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WhatsApp Number Tracker',
      theme: ThemeData(primarySwatch: Colors.green),
      home: const WhatsAppMobileMonitor(),
    );
  }
}

class WhatsAppMobileMonitor extends StatefulWidget {
  const WhatsAppMobileMonitor({super.key});

  @override
  State<WhatsAppMobileMonitor> createState() => _WhatsAppMobileMonitorState();
}

class _WhatsAppMobileMonitorState extends State<WhatsAppMobileMonitor> {
  static const MethodChannel _channel = MethodChannel('com.example.whatsapp_monitor/accessibility');
  static const MethodChannel _contactsChannel = MethodChannel('com.example.whatsapp_monitor/contacts');
  List<Map<String, String>> savedContacts = []; // Phone contacts with name and number
  List<String> whatsappNumbers = []; // Unsaved WhatsApp numbers
  bool isMonitoring = false;

  @override
  void initState() {
    super.initState();
    _requestContactsPermission();
    _initializeMonitoring();
    _setupChannelListener();
  }

  Future<void> _requestContactsPermission() async {
    if (await Permission.contacts.request().isGranted) {
      _loadPhoneContacts();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Contacts permission denied. Please grant it in settings.')),
      );
      openAppSettings();
    }
  }

  Future<void> _initializeMonitoring() async {
    try {
      final result = await _channel.invokeMethod('startMonitoring');
      if (result == 'enabled') {
        setState(() => isMonitoring = true);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Monitoring started. Open WhatsApp to track numbers.')),
        );
      } else {
        _showAccessibilityPrompt();
      }
    } on PlatformException catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error initializing: ${e.message}')),
      );
    }
  }

  void _setupChannelListener() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onUIEvent') {
        try {
          final eventData = jsonDecode(call.arguments as String) as Map<String, dynamic>;
          final text = eventData['text'] ?? '';
          if (_isPhoneNumber(text)) {
            setState(() {
              if (!whatsappNumbers.contains(text) && !savedContacts.any((c) => c['number'] == text)) {
                whatsappNumbers.add(text);
              }
            });
          }
        } catch (e) {
          print('Error parsing event: $e');
        }
      }
      return null;
    });
  }

  void _showAccessibilityPrompt() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enable Accessibility Service'),
        content: const Text('Please enable the WhatsApp Monitor Service in Settings > Accessibility.'),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              _channel.invokeMethod('openAccessibilitySettings');
            },
            child: const Text('Open Settings'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );
  }

  Future<void> _loadPhoneContacts() async {
    try {
      final contacts = await _contactsChannel.invokeMethod('getPhoneContacts');
      if (contacts != null) {
        setState(() {
          savedContacts = (contacts as List<dynamic>).map((contact) {
            final Map<String, String> contactMap = Map<String, String>.from(contact as Map);
            return {
              'name': contactMap['name'] ?? 'Unknown',
              'number': contactMap['number']?.trim().replaceAll(' ', '').replaceAll('-', '').replaceAll('.', '') ?? '',
            };
          }).where((c) => _isPhoneNumber(c['number']!)).toList();
        });
      }
    } on PlatformException catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error fetching contacts: ${e.message}')),
      );
    }
  }

  bool _isPhoneNumber(String text) {
    final phoneRegex = RegExp(r'\+?[1-9]\d{6,14}');
    final cleanedText = text.trim().replaceAll(' ', '').replaceAll('-', '').replaceAll('.', '');
    return phoneRegex.hasMatch(cleanedText);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('WhatsApp Number Tracker'), backgroundColor: Colors.green),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Monitoring Status: ${isMonitoring ? 'Active' : 'Inactive'}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 20),
            const Text('Saved Contacts (Phone):',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Expanded(
              flex: 1,
              child: savedContacts.isEmpty
                  ? const Center(child: Text('No saved contacts found'))
                  : ListView.builder(
                      itemCount: savedContacts.length,
                      itemBuilder: (context, index) {
                        final contact = savedContacts[index];
                        return Card(
                          elevation: 2,
                          margin: const EdgeInsets.symmetric(vertical: 4),
                          color: Colors.blue[50],
                          child: ListTile(
                            title: Text(contact['name']!),
                            subtitle: Text(contact['number']!),
                          ),
                        );
                      },
                    ),
            ),
            const SizedBox(height: 20),
            const Text('WhatsApp Numbers (Unsaved):',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Expanded(
              flex: 1,
              child: whatsappNumbers.isEmpty
                  ? const Center(child: Text('No WhatsApp numbers tracked yet. Open WhatsApp to start.'))
                  : ListView.builder(
                      itemCount: whatsappNumbers.length,
                      itemBuilder: (context, index) {
                        final number = whatsappNumbers[index];
                        return Card(
                          elevation: 2,
                          margin: const EdgeInsets.symmetric(vertical: 4),
                          color: Colors.green[50],
                          child: ListTile(title: Text(number)),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          _initializeMonitoring();
          _loadPhoneContacts();
        },
        child: const Icon(Icons.refresh),
        tooltip: 'Restart Monitoring & Refresh Contacts',
      ),
    );
  }
}