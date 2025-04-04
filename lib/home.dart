import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:convert';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

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
  static const String apiUrl = 'http://test-server:3008/api/contacts';

  List<Map<String, dynamic>> savedContacts = [];
  List<Map<String, dynamic>> messagedContacts = [];
  bool isMonitoring = false;
  String currentLabel = '';
  int contactOffset = 0;
  final int contactLimit = 400;
  bool isLoadingContacts = false;
  int? totalContactCount;
  bool allContactsLoaded = false;

  String? userId;
  String? storeId;
  StreamController<bool> _monitoringStatusController = StreamController<bool>.broadcast();

  @override
  void initState() {
    super.initState();
    _loadUserAndStoreId();
    _checkAndRequestPermissions();
    _setupChannelListener();
  }

  @override
  void dispose() {
    _monitoringStatusController.close();
    super.dispose();
  }

  Future<void> _loadUserAndStoreId() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      userId = prefs.getString('user_id');
      storeId = prefs.getString('store_id');
    });
  }

  Future<void> _checkAndRequestPermissions() async {
    final status = await Permission.systemAlertWindow.request();
    if (!status.isGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Overlay permission is required for floating button'))
      );
    }
    
    final contactsStatus = await Permission.contacts.request();
    if (contactsStatus.isGranted) {
      await _getTotalContactCount();
      _loadAllContacts();
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Contacts permission is required'))
      );
    }
    
    _showAccessibilityPrompt();
  }

  void _setupChannelListener() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onUIEvent':
          _handleUIEvent(call.arguments);
          break;
        case 'requestStartMonitoring':
          if (!isMonitoring) {
            await _startMonitoring();
          }
          break;
        case 'monitoringStatusChanged':
          final isActive = call.arguments as bool;
          setState(() => isMonitoring = isActive);
          _monitoringStatusController.add(isActive);
          break;
      }
      return null;
    });
  }

  void _handleUIEvent(dynamic arguments) {
    try {
      final eventData = jsonDecode(arguments as String) as Map<String, dynamic>;
      final text = eventData['text']?.toString() ?? '';
      
      if (text.isNotEmpty && isMonitoring && !_contactExists(text)) {
        final newContact = _createContactFromEvent(text, eventData);
        
        setState(() => messagedContacts.add(newContact));
        _sendContactToApi(newContact);
      }
    } catch (e) {
      print('Error handling UI event: $e');
    }
  }

  bool _contactExists(String text) {
    return messagedContacts.any((c) => 
      c['number'] == text || c['name'] == text
    );
  }

  Map<String, dynamic> _createContactFromEvent(String text, Map<String, dynamic> eventData) {
    if (_isPhoneNumber(text)) {
      final savedMatch = savedContacts.firstWhere(
        (c) => c['number'] == text,
        orElse: () => {'name': 'Unknown', 'number': text},
      );
      return {
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
      return {
        'name': text,
        'number': savedMatch['number']!,
        'label': currentLabel,
        'timestamp': DateTime.now().toIso8601String()
      };
    }
  }

  Future<void> _startMonitoring() async {
    final label = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Enter Label'),
        content: TextField(
          autofocus: true,
          decoration: const InputDecoration(
            hintText: 'Enter label for this session',
            border: OutlineInputBorder(),
          ),
          onChanged: (value) => currentLabel = value,
        ),
        actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, currentLabel),
          child: const Text('Start', style: TextStyle(color: Colors.green)),
        ), // Added closing parenthesis here
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel', style: TextStyle(color: Colors.red)),
        ),
      ],
      ),
    );

    if (label == null || label.isEmpty) return;

    try {
      final result = await _channel.invokeMethod<bool>('startMonitoring', {'label': label});
      if (result == true) {
        setState(() {
          currentLabel = label;
          isMonitoring = true;
        });
        _monitoringStatusController.add(true);
      }
    } on PlatformException catch (e) {
      _showError('Failed to start monitoring: ${e.message}');
      setState(() => isMonitoring = false);
    }
  }

  Future<void> _stopMonitoring() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopMonitoring');
      if (result == true) {
        setState(() {
          isMonitoring = false;
          currentLabel = '';
        });
        _monitoringStatusController.add(false);
      }
    } on PlatformException catch (e) {
      _showError('Failed to stop monitoring: ${e.message}');
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Colors.red,
      )
    );
  }

  Future<void> _sendContactToApi(Map<String, dynamic> contact) async {
    if (userId == null || storeId == null) return;

    final payload = {
      'name': contact['name'],
      'number': contact['number'],
      'label': contact['label'],
      'timestamp': contact['timestamp'],
      'user_id': userId,
      'store_id': storeId,
      'list_length': messagedContacts.length,
      'list_capacity': totalContactCount ?? 0,
    };

    try {
      final response = await http.post(
        Uri.parse(apiUrl),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode(payload),
      );
      if (response.statusCode != 200) {
        print('Failed to save contact: ${response.body}');
      }
    } catch (e) {
      print('Error sending contact: $e');
    }
  }

  void _showAccessibilityPrompt() {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('Enable Accessibility Service'),
        content: const Text(
          'Please enable the WhatsApp Monitor Service in Accessibility Settings '
          'for the app to function properly.'
        ),
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

  Future<void> _getTotalContactCount() async {
    try {
      final count = await _contactsChannel.invokeMethod<int>('getTotalContactCount');
      setState(() => totalContactCount = count);
    } on PlatformException catch (e) {
      _showError('Failed to get contact count: ${e.message}');
    }
  }

  Future<void> _loadAllContacts() async {
    if (isLoadingContacts || allContactsLoaded) return;
    setState(() => isLoadingContacts = true);

    try {
      while (savedContacts.length < (totalContactCount ?? 0)) {
        final contacts = await _contactsChannel.invokeMethod<List<dynamic>>(
          'getPhoneContacts',
          {'offset': contactOffset, 'limit': contactLimit},
        );
        
        if (contacts == null || contacts.isEmpty) break;
        
        setState(() {
          savedContacts.addAll(contacts.map((c) => Map<String, dynamic>.from(c)));
          contactOffset += contactLimit;
        });
        
        if (contacts.length < contactLimit) break;
      }
      
      setState(() {
        isLoadingContacts = false;
        allContactsLoaded = true;
      });
    } on PlatformException catch (e) {
      setState(() => isLoadingContacts = false);
      _showError('Failed to load contacts: ${e.message}');
    }
  }

  bool _isPhoneNumber(String text) {
    final phoneRegex = RegExp(r'^\+?[0-9]{6,14}$');
    final cleanedText = text.replaceAll(RegExp(r'[\s\-\.\(\)]'), '');
    return phoneRegex.hasMatch(cleanedText);
  }

  @override
  Widget build(BuildContext context) {
    if (!allContactsLoaded) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const CircularProgressIndicator(),
              const SizedBox(height: 20),
              Text(
                'Loading Contacts: ${savedContacts.length} / ${totalContactCount ?? '...'}',
                style: const TextStyle(fontSize: 18),
              ),
            ],
          ),
        ),
      );
    }

    final filteredContacts = messagedContacts.where((c) => c['number'] != 'Not Available').toList();

    return Scaffold(
      appBar: AppBar(
        title: const Text('WhatsApp Message Tracker'),
        backgroundColor: Colors.green,
        actions: [
          StreamBuilder<bool>(
            stream: _monitoringStatusController.stream,
            initialData: isMonitoring,
            builder: (context, snapshot) {
              return IconButton(
                icon: Icon(snapshot.data! ? Icons.stop : Icons.play_arrow),
                onPressed: snapshot.data! ? _stopMonitoring : _startMonitoring,
                tooltip: snapshot.data! ? 'Stop Monitoring' : 'Start Monitoring',
              );
            },
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Monitoring Status:',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    StreamBuilder<bool>(
                      stream: _monitoringStatusController.stream,
                      initialData: isMonitoring,
                      builder: (context, snapshot) {
                        return Text(
                          snapshot.data! 
                            ? 'ACTIVE (${currentLabel.toUpperCase()})' 
                            : 'INACTIVE',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: snapshot.data! ? Colors.green : Colors.red,
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 16),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        ElevatedButton.icon(
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('Start'),
                          onPressed: isMonitoring ? null : _startMonitoring,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.green,
                            foregroundColor: Colors.white,
                          ),
                        ),
                        ElevatedButton.icon(
                          icon: const Icon(Icons.stop),
                          label: const Text('Stop'),
                          onPressed: isMonitoring ? _stopMonitoring : null,
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.red,
                            foregroundColor: Colors.white,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Messaged Contacts:',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: ListView.builder(
                      itemCount: filteredContacts.length,
                      itemBuilder: (context, index) {
                        final contact = filteredContacts[index];
                        return ContactCard(contact: contact);
                      },
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
}

class ContactCard extends StatelessWidget {
  final Map<String, dynamic> contact;

  const ContactCard({super.key, required this.contact});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        title: Text(
          contact['name']!,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(contact['number']!),
            const SizedBox(height: 4),
            Row(
              children: [
                Chip(
                  label: Text(contact['label'] ?? 'None'),
                  backgroundColor: Colors.green[100],
                ),
                const SizedBox(width: 8),
                Text(
                  contact['timestamp']?.split('.')[0] ?? '',
                  style: const TextStyle(fontSize: 12),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}