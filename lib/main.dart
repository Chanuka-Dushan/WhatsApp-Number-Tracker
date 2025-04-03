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
  static const MethodChannel _channel =
      MethodChannel('com.example.whatsapp_monitor/accessibility');
  static const MethodChannel _contactsChannel =
      MethodChannel('com.example.whatsapp_monitor/contacts');
  static const String apiUrl = 'http://test-server:3008/api/contacts';
  List<Map<String, dynamic>> savedContacts = [];
  List<Map<String, dynamic>> messagedContacts = [];
  bool isMonitoring = false;
  String currentLabel = '';

  @override
  void initState() {
    super.initState();
    print('Initializing WhatsAppMobileMonitor');
    _checkAndRequestPermissions();
    _setupChannelListener();
  }

  Future<void> _checkAndRequestPermissions() async {
    final overlayStatus = await Permission.systemAlertWindow.status;
    if (!overlayStatus.isGranted) {
      await Permission.systemAlertWindow.request();
    }

    final contactsStatus = await Permission.contacts.status;
    if (!contactsStatus.isGranted) {
      await Permission.contacts.request();
      if (await Permission.contacts.isGranted) {
        _loadPhoneContacts();
      }
    } else {
      _loadPhoneContacts();
    }

    _showAccessibilityPrompt();
  }

  void _setupChannelListener() {
    _channel.setMethodCallHandler((call) async {
      print('Received method call: ${call.method}');
      if (call.method == 'onUIEvent') {
        try {
          final eventData = jsonDecode(call.arguments as String) as Map<String, dynamic>;
          final text = eventData['text'] ?? '';
          print('UI Event received: $text');
          if (text.isNotEmpty && isMonitoring &&
              !messagedContacts.any((c) => c['number'] == text || c['name'] == text)) {
            Map<String, dynamic> newContact = {
              'name': 'Unknown', 
              'number': 'Not Available',
              'label': currentLabel,
              'timestamp': DateTime.now().toIso8601String()
            };
            setState(() {
              if (_isPhoneNumber(text)) {
                final savedMatch = savedContacts.firstWhere(
                  (c) => c['number'] == text,
                  orElse: () => {'name': 'Unknown', 'number': text},
                );
                newContact = {
                  'name': savedMatch['name']!, 
                  'number': text,
                  'label': currentLabel,
                  'timestamp': DateTime.now().toIso8601String()
                };
              } else {
                final savedMatch = savedContacts.firstWhere(
                  (c) => c['name'] == text,
                  orElse: () => {'name': text, 'number': 'Not Available'},
                );
                newContact = {
                  'name': text, 
                  'number': savedMatch['number']!,
                  'label': currentLabel,
                  'timestamp': DateTime.now().toIso8601String()
                };
              }
              messagedContacts.add(newContact);
              print('Added new contact: $newContact');
            });
            await _sendContactToApi(newContact);
          }
        } catch (e) {
          print('Error parsing event: $e');
        }
      } else if (call.method == 'monitoringStarted') {
        print('Monitoring started from native');
      } else if (call.method == 'monitoringStopped') {
        print('Monitoring stopped from native');
      }
      return null;
    });
  }

  Future<void> _startMonitoring() async {
    final label = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enter Label'),
        content: TextField(
          decoration: const InputDecoration(hintText: 'Enter label for this session'),
          onChanged: (value) => currentLabel = value,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, currentLabel),
            child: const Text('Start'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );

    if (label == null || label.isEmpty) return;

    setState(() {
      currentLabel = label;
      isMonitoring = true;
    });

    try {
      await _channel.invokeMethod('startMonitoring', {'label': label});
    } on PlatformException catch (e) {
      print('Error starting monitoring: ${e.message}');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error starting monitoring: ${e.message}')),
      );
      setState(() => isMonitoring = false);
    }
  }

  Future<void> _stopMonitoring() async {
    try {
      await _channel.invokeMethod('stopMonitoring');
      setState(() {
        isMonitoring = false;
        currentLabel = '';
      });
    } on PlatformException catch (e) {
      print('Error stopping monitoring: ${e.message}');
    }
  }

  Future<void> _sendContactToApi(Map<String, dynamic> contact) async {
    print('Sending contact to API: $contact');
    try {
      final response = await http.post(
        Uri.parse(apiUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'name': contact['name'],
          'number': contact['number'],
          'label': contact['label'],
          'timestamp': contact['timestamp']
        }),
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
        content: const Text(
            'Please enable the WhatsApp Monitor Service in Settings > Accessibility.'),
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
    print('Loading phone contacts');
    try {
      final contacts = await _contactsChannel.invokeMethod('getPhoneContacts');
      if (contacts != null) {
        setState(() {
          savedContacts = (contacts as List<dynamic>).map((contact) {
            final Map<String, dynamic> contactMap =
                Map<String, dynamic>.from(contact as Map);
            return {
              'name': contactMap['name'] ?? 'Unknown',
              'number': contactMap['number']
                      ?.trim()
                      .replaceAll(' ', '')
                      .replaceAll('-', '')
                      .replaceAll('.', '') ??
                  '',
            };
          }).where((c) => _isPhoneNumber(c['number']!)).toList();
          print('Loaded ${savedContacts.length} contacts');
        });
      }
    } on PlatformException catch (e) {
      print('Error fetching contacts: ${e.message}');
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
    final filteredMessagedContacts =
        messagedContacts.where((contact) => contact['number'] != 'Not Available').toList();

    return Scaffold(
      appBar: AppBar(
          title: const Text('WhatsApp Message Tracker'),
          backgroundColor: Colors.green),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
                'Monitoring Status: ${isMonitoring ? 'Active ($currentLabel)' : 'Inactive'}',
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton(
                  onPressed: isMonitoring ? null : _startMonitoring,
                  child: const Text('Start Monitoring'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.green,
                    disabledBackgroundColor: Colors.grey,
                  ),
                ),
                ElevatedButton(
                  onPressed: isMonitoring ? _stopMonitoring : null,
                  child: const Text('Stop Monitoring'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.red,
                    disabledBackgroundColor: Colors.grey,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            const Text('Messaged Contacts:',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Expanded(
              child: filteredMessagedContacts.isEmpty
                  ? const Center(
                      child: Text(
                          'No messaged contacts with numbers tracked yet. Start monitoring to begin.'))
                  : ListView.builder(
                      itemCount: filteredMessagedContacts.length,
                      itemBuilder: (context, index) {
                        final contact = filteredMessagedContacts[index];
                        return Card(
                          elevation: 2,
                          margin: const EdgeInsets.symmetric(vertical: 4),
                          color: contact['name'] == 'Unknown'
                              ? Colors.green[50]
                              : Colors.blue[50],
                          child: ListTile(
                            title: Text(contact['name']!),
                            subtitle: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(contact['number']!),
                                Text('Label: ${contact['label'] ?? 'None'}'),
                                Text('Time: ${contact['timestamp']?.split('.')[0] ?? ''}'),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}