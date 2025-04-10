import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:convert';
import 'package:csv/csv.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:device_info_plus/device_info_plus.dart';

void main() {
  runApp(const WhatsAppMonitorApp());
}

class WhatsAppMonitorApp extends StatelessWidget {
  const WhatsAppMonitorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'WhatsApp Monitor',
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
  String currentLabel = 'All';
  int contactOffset = 0;
  final int contactLimit = 400;
  bool isLoadingContacts = false;
  int? totalContactCount;
  bool allContactsLoaded = false;

  String? userId;
  String? storeId;
  final StreamController<bool> _monitoringStatusController =
      StreamController<bool>.broadcast();

  @override
  void initState() {
    super.initState();
    _loadUserAndStoreId();
    _checkAndRequestPermissions();
    _setupChannelListener();
    _checkMonitoringStatus();
  }

  @override
  void dispose() {
    _monitoringStatusController.close();
    super.dispose();
  }

  Future<void> _loadUserAndStoreId() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      userId = prefs.getInt('user_id')?.toString() ?? 'Unknown';
      storeId = prefs.getString('store_id') ?? 'Unknown';
    });
    print('userId: $userId');
    print('storeId: $storeId');
  }

  Future<void> _checkMonitoringStatus() async {
    try {
      final isActive = await _channel.invokeMethod<bool>('isMonitoringActive');
      setState(() {
        isMonitoring = isActive ?? false;
      });
      final label = await _channel.invokeMethod<String>('getCurrentLabel');
      setState(() {
        currentLabel = label ?? 'All';
      });
      _monitoringStatusController.add(isMonitoring);
      print('Monitoring status checked: $isMonitoring, label: $currentLabel');
    } catch (e) {
      print('Error checking monitoring status: $e');
    }
  }

  Future<void> _checkAndRequestPermissions() async {
    final deviceInfo = DeviceInfoPlugin();
    final androidInfo = await deviceInfo.androidInfo;
    final sdkVersion = androidInfo.version.sdkInt;

    if (sdkVersion >= 33) {
      await [Permission.storage, Permission.photos, Permission.notification].request();
      if (await Permission.notification.isDenied) {
        _showError("Notification permission is required for monitoring controls.");
      }
    } else if (sdkVersion >= 30) {
      if (await Permission.manageExternalStorage.isDenied) {
        await Permission.manageExternalStorage.request();
      }
      await Permission.storage.request();
    } else {
      await Permission.storage.request();
    }

    if (await Permission.storage.isDenied &&
        await Permission.manageExternalStorage.isDenied &&
        sdkVersion < 33) {
      _showError('Storage permission is required to export files.');
      return;
    }

    await Permission.systemAlertWindow.request();
    await Permission.contacts.request();
    await _getTotalContactCount();
    await _loadAllContacts();
    _showAccessibilityPrompt();
  }

  void _setupChannelListener() {
    _channel.setMethodCallHandler((call) async {
      print('MethodChannel call received: ${call.method} with arguments: ${call.arguments}');
      switch (call.method) {
        case 'onUIEvent':
          _handleUIEvent(call.arguments);
          break;
        case 'monitoringStatusChanged':
          final isActive = call.arguments as bool;
          print('Monitoring status changed: $isActive');
          setState(() {
            isMonitoring = isActive;
            if (!isActive) currentLabel = 'All';
          });
          _monitoringStatusController.add(isActive);
          break;
        default:
          print('Unhandled method: ${call.method}');
      }
      return null;
    });
  }

  void _handleUIEvent(dynamic arguments) {
    try {
      print('Raw UI event data received: $arguments');
      final eventData = jsonDecode(arguments as String) as Map<String, dynamic>;
      final text = eventData['text']?.toString() ?? '';
      final label = eventData['label']?.toString() ?? 'All';

      print('Parsed event - text: $text, label: $label, isMonitoring: $isMonitoring');
      if (text.isNotEmpty && isMonitoring) {
        setState(() {
          currentLabel = label;
          print('Updated currentLabel to: $currentLabel');
        });
        final existingIndex = messagedContacts.indexWhere((c) => c['text'] == text);
        if (existingIndex != -1) {
          final labels = List<String>.from(messagedContacts[existingIndex]['labels'] ?? []);
          if (!labels.contains(label)) {
            labels.add(label);
            setState(() {
              messagedContacts[existingIndex]['labels'] = labels;
              print('Updated existing contact: $text with labels: $labels');
            });
            _sendContactToApi(messagedContacts[existingIndex]);
          }
        } else {
          final newContact = _createContactFromEvent(text, label);
          setState(() {
            messagedContacts.add(newContact);
            print('Added new contact: ${newContact['text']} with label: $label');
          });
          _sendContactToApi(newContact);
        }
      } else {
        print('Skipping event - text empty or monitoring off');
      }
    } catch (e) {
      print('Error handling UI event: $e');
    }
  }

  Map<String, dynamic> _createContactFromEvent(String text, String label) {
    final isPhone = _isPhoneNumber(text);
    final savedMatch = savedContacts.firstWhere(
      (c) => (isPhone && c['number'] == text) || (!isPhone && c['name'] == text),
      orElse: () => isPhone ? {'name': 'Unknown', 'number': text} : {'name': text, 'number': 'Unsaved'},
    );
    return {
      'text': text,
      'name': savedMatch['name'],
      'number': savedMatch['number'],
      'labels': [label],
      'timestamp': DateTime.now().toIso8601String(),
      'isSaved': savedMatch['number'] != 'Unsaved' && savedMatch['name'] != 'Unsaved',
    };
  }

  Future<void> _startMonitoring() async {
    try {
      final result = await _channel.invokeMethod<bool>('startMonitoring');
      if (result == true) {
        _monitoringStatusController.add(true);
        setState(() {
          isMonitoring = true;
        });
        print('Monitoring started');
      } else {
        _showError("Failed to prepare monitoring");
      }
    } on PlatformException catch (e) {
      _showError('Failed to prepare monitoring: ${e.message}');
    }
  }

  Future<void> _stopMonitoring() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopMonitoring');
      if (result == true) {
        setState(() {
          isMonitoring = false;
          currentLabel = 'All';
        });
        _monitoringStatusController.add(false);
        print('Monitoring stopped');
      }
    } on PlatformException catch (e) {
      _showError('Failed to stop monitoring: ${e.message}');
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.red),
    );
  }

  Future<void> _sendContactToApi(Map<String, dynamic> contact) async {
    if (userId == null || storeId == null) return;

    final payload = {
      'name': contact['name'],
      'number': contact['number'],
      'label': contact['labels'].join(', '),
      'timestamp': contact['timestamp'],
      'store_id': storeId ?? 'Unknown',
      'user_id': userId ?? 'Unknown',
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
      } else {
        print('Contact sent to API: ${contact['text']}');
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
            'Please enable the WhatsApp Monitor Service in Accessibility Settings.'),
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
      print('Total contact count: $count');
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
      print('Loaded ${savedContacts.length} contacts');
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

  Future<void> _exportContactsToCsv() async {
    final deviceInfo = DeviceInfoPlugin();
    final androidInfo = await deviceInfo.androidInfo;
    final sdkVersion = androidInfo.version.sdkInt;

    bool hasPermission = false;
    if (sdkVersion >= 33) {
      hasPermission = await Permission.photos.isGranted || await Permission.storage.isGranted;
      if (!hasPermission) {
        await [Permission.photos, Permission.storage].request();
        hasPermission = await Permission.photos.isGranted || await Permission.storage.isGranted;
      }
    } else if (sdkVersion >= 30) {
      hasPermission = await Permission.manageExternalStorage.isGranted;
      if (!hasPermission) {
        await Permission.manageExternalStorage.request();
        hasPermission = await Permission.manageExternalStorage.isGranted;
      }
    } else {
      hasPermission = await Permission.storage.isGranted;
      if (!hasPermission) {
        await Permission.storage.request();
        hasPermission = await Permission.storage.isGranted;
      }
    }

    if (!hasPermission) {
      _showError('Storage permission denied. Cannot export CSV.');
      return;
    }

    List<List<String>> rows = [
      ['store_id', 'user_id', 'name', 'phonenumber', 'label', 'is_saved']
    ];

    for (var contact in messagedContacts) {
      if (contact['number'] != 'Unsaved' && contact['number'].isNotEmpty) { // Only export contacts with numbers
        final labelString = (contact['labels'] as List).join(', ');
        rows.add([
          storeId ?? 'Unknown',
          userId ?? 'Unknown',
          contact['name'],
          contact['number'],
          labelString,
          contact['isSaved'].toString(),
        ]);
      }
    }

    String csvData = const ListToCsvConverter().convert(rows);
    final timestamp = DateTime.now().toIso8601String().replaceAll(':', '-').split('.')[0];
    final path = '/storage/emulated/0/Download/whatsapp_contacts_$timestamp.csv';
    final file = File(path);

    await file.writeAsString(csvData);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Contacts exported to $path')),
    );
    print('CSV exported to $path');
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

    // Filter contacts to only show those with valid numbers
    final displayContacts = messagedContacts.where((contact) => 
      contact['number'] != 'Unsaved' && contact['number'].isNotEmpty).toList();

    print('Building UI with ${displayContacts.length} contacts (filtered from ${messagedContacts.length})');
    return Scaffold(
      appBar: AppBar(
        title: Text('WhatsApp Message Tracker - Label: $currentLabel'),
        backgroundColor: Colors.green,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => setState(() {}),
            tooltip: 'Refresh UI',
          ),
          IconButton(
            icon: const Icon(Icons.download),
            onPressed: _exportContactsToCsv,
            tooltip: 'Export as CSV',
          ),
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
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Monitoring: ${isMonitoring ? "Active" : "Inactive"}',
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: displayContacts.isEmpty
                  ? const Center(child: Text('No contacts with numbers detected yet.'))
                  : ListView.builder(
                      itemCount: displayContacts.length,
                      itemBuilder: (context, index) {
                        final contact = displayContacts[index];
                        print('Rendering contact: ${contact['text']}');
                        return ContactCard(contact: contact);
                      },
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
    final labels = (contact['labels'] as List<dynamic>).join(', ');
    final isSaved = contact['isSaved'] as bool;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        title: Text(
          contact['name'],
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Number: ${contact['number']}'),
            const SizedBox(height: 4),
            Text('Labels: $labels'),
            const SizedBox(height: 4),
            Text('Status: ${isSaved ? "Saved" : "Unsaved"}'),
            const SizedBox(height: 4),
            Text(
              'Last Seen: ${contact['timestamp'].split('.')[0]}',
              style: const TextStyle(fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}