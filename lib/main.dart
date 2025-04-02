import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:convert';
import 'package:permission_handler/permission_handler.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const WhatsAppMonitorApp());
}

class WhatsAppMonitorApp extends StatelessWidget {
  const WhatsAppMonitorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WhatsApp Message Tracker',
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
  static const String apiUrl = 'http://test-server:3008/api/contacts'; // Replace with your server IP
  List<Map<String, String>> savedContacts = [];
  List<Map<String, String>> messagedContacts = [];
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
          const SnackBar(content: Text('Monitoring started. Please open WhatsApp or WhatsApp Business manually.')),
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
          if (text.isNotEmpty && !messagedContacts.any((c) => c['number'] == text || c['name'] == text)) {
            Map<String, String> newContact = {'name': 'Unknown', 'number': 'Not Available'};
            setState(() {
              if (_isPhoneNumber(text)) {
                final savedMatch = savedContacts.firstWhere(
                  (c) => c['number'] == text,
                  orElse: () => {'name': 'Unknown', 'number': text},
                );
                newContact = {
                  'name': savedMatch['name']!,
                  'number': text,
                };
              } else {
                final savedMatch = savedContacts.firstWhere(
                  (c) => c['name'] == text,
                  orElse: () => {'name': text, 'number': 'Not Available'},
                );
                newContact = {
                  'name': text,
                  'number': savedMatch['number']!,
                };
              }
              messagedContacts.add(newContact);
            });
            await _sendContactToApi(newContact);
          }
        } catch (e) {
          print('Error parsing event: $e');
        }
      }
      return null;
    });
  }

  Future<void> _sendContactToApi(Map<String, String> contact) async {
    try {
      final response = await http.post(
        Uri.parse(apiUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'contacts': [contact]}),
      );
      
      if (response.statusCode == 200) {
        print('Contact saved to server: ${contact['name']}');
      } else {
        print('Failed to save contact: ${response.body}');
      }
    } catch (e) {
      print('Error sending contact to API: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Network error: Failed to save contact')),
      );
    }
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
    final filteredMessagedContacts = messagedContacts.where((contact) => contact['number'] != 'Not Available').toList();

    return Scaffold(
      appBar: AppBar(title: const Text('WhatsApp Message Tracker'), backgroundColor: Colors.green),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Monitoring Status: ${isMonitoring ? 'Active (Open WhatsApp to scan)' : 'Inactive'}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 20),
            const Text('Messaged Contacts:',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Expanded(
              child: filteredMessagedContacts.isEmpty
                  ? const Center(child: Text('No messaged contacts with numbers tracked yet. Open WhatsApp or WhatsApp Business to start.'))
                  : ListView.builder(
                      itemCount: filteredMessagedContacts.length,
                      itemBuilder: (context, index) {
                        final contact = filteredMessagedContacts[index];
                        return Card(
                          elevation: 2,
                          margin: const EdgeInsets.symmetric(vertical: 4),
                          color: contact['name'] == 'Unknown' ? Colors.green[50] : Colors.blue[50],
                          child: ListTile(
                            title: Text(contact['name']!),
                            subtitle: Text(contact['number']!),
                          ),
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