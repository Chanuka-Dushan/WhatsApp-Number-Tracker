import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:whatsapp_monitor/home.dart';

class LoginPage extends StatefulWidget {
  @override
  _LoginPageState createState() => _LoginPageState();
}

class _LoginPageState extends State<LoginPage> {
  final TextEditingController _emailController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();
  bool _isPasswordVisible = false;
  bool _isLoading = false;
  bool _rememberMe = false;
  String? _savedEmail;
  String? _savedPassword;

  @override
  void initState() {
    super.initState();
    _loadSavedEmail();
  }

  Future<void> _loadSavedEmail() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    String? email = prefs.getString('email');
    String? password = prefs.getString('password');
    setState(() {
      _savedEmail = email;
      _savedPassword = password;
      if (_savedEmail != null) {
        _rememberMe = true;
        _emailController.text = _savedEmail!;
        _passwordController.text = _savedPassword ?? '';
      }
    });
  }

  void _togglePasswordVisibility() {
    setState(() {
      _isPasswordVisible = !_isPasswordVisible;
    });
  }

  Future<void> _login() async {
    setState(() { _isLoading = true; });

    final url = Uri.parse('${dotenv.env['BASE_URL']}/api/users/login');
    final headers = {'Content-Type': 'application/json'};
    final body = jsonEncode({
      'mobile': _emailController.text,
      'email': _emailController.text,
      'password': _passwordController.text,
    });

    print('Request URL: $url');
    print('Request Body: $body');

    try {
      // Check internet connectivity
      final ping = await http.get(Uri.parse('https://www.google.com'));
      if (ping.statusCode != 200) {
        throw Exception('No internet connection');
      }

      final response = await http.post(url, headers: headers, body: body).timeout(
        const Duration(seconds: 10),
        onTimeout: () => throw Exception('Request timed out'),
      );

      print('Response Status: ${response.statusCode}');
      print('Response Body: ${response.body}');

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        SharedPreferences prefs = await SharedPreferences.getInstance();

        await prefs.setString('token', data['token']);
        await prefs.setInt('user_id', data['user']['id']);
        await prefs.setString('store_id', data['user']['store_id'].toString()); // Changed to String
        await prefs.setString('username', data['user']['username']);
        await prefs.setString('sub_end', data['user']['subscription'][0]['end']);

        print("Subscription end date: ${data['user']['subscription'][0]['end']}");

        await prefs.setString('email', _emailController.text);
        if (_rememberMe) {
          await prefs.setString('password', _passwordController.text);
        } else {
          await prefs.remove('password');
        }

        // Parse subscription end date (fixed for 'yyyy-MM-dd' format)
        String rawDate = data['user']['subscription'][0]['end'];
        DateTime? subscriptionEndDate;
        try {
          // Try parsing as 'yyyy-MM-dd' first
          subscriptionEndDate = DateFormat('yyyy-MM-dd').parse(rawDate);
        } catch (e) {
          try {
            // Fallback to original format if needed
            subscriptionEndDate =
                DateFormat('EEE MMM dd yyyy HH:mm:ss \'GMT\'Z').parse(rawDate);
          } catch (e) {
            print("Date Parsing Error: $e");
            _showErrorDialog("Failed to parse subscription date: $rawDate");
            return;
          }
        }

        DateTime today = DateTime.now();
        int remainingDays = subscriptionEndDate.difference(today).inDays;
        await prefs.setInt('remaining_days', remainingDays);
        print("Remaining days until subscription ends: $remainingDays");

        Navigator.pushReplacement(
          context,
          MaterialPageRoute(builder: (context) => WhatsAppMonitorApp()),
        );
      } else {
        _showErrorDialog('Server Error: ${response.statusCode} - ${response.body}');
      }
    } catch (e, stackTrace) {
      print('Login Error: $e');
      print('StackTrace: $stackTrace');
      String errorMessage = 'Network Error: Check your internet or server status.';
      if (e.toString().contains('No internet')) {
        errorMessage = 'No internet connection. Please connect and try again.';
      } else if (e.toString().contains('timed out')) {
        errorMessage = 'Something went wrong. Please try again in a few minutes.';
      }
      _showErrorDialog(errorMessage);
    } finally {
      setState(() { _isLoading = false; });
    }
  }

  void _showErrorDialog(String message) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Login Failed'),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  void _deleteSavedEmail() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    await prefs.remove('email');
    await prefs.remove('password');
    setState(() {
      _savedEmail = null;
      _savedPassword = null;
      _rememberMe = false;
      _emailController.clear();
      _passwordController.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        Scaffold(
          body: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24.0),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'Welcome Back 👋',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                      color: Colors.blueAccent,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Ecommex One',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.w500,
                      color: Colors.black54,
                    ),
                  ),
                  const SizedBox(height: 24),
                  if (_savedEmail != null)
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(_savedEmail!),
                            IconButton(
                              icon: const Icon(Icons.cancel),
                              onPressed: _deleteSavedEmail,
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),
                      ],
                    ),
                  TextField(
                    controller: _emailController,
                    decoration: InputDecoration(
                      labelText: 'Email or Mobile',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12.0),
                      ),
                      prefixIcon: const Icon(Icons.email),
                    ),
                    keyboardType: TextInputType.emailAddress,
                    onChanged: (value) {
                      if (_savedEmail != null) {
                        setState(() {
                          _savedEmail = null;
                          _savedPassword = null;
                          _rememberMe = false;
                        });
                      }
                    },
                  ),
                  const SizedBox(height: 16),
                  TextField(
                    controller: _passwordController,
                    obscureText: !_isPasswordVisible,
                    decoration: InputDecoration(
                      labelText: 'Password',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12.0),
                      ),
                      prefixIcon: const Icon(Icons.lock),
                      suffixIcon: IconButton(
                        icon: Icon(
                          _isPasswordVisible
                              ? Icons.visibility
                              : Icons.visibility_off,
                        ),
                        onPressed: _togglePasswordVisibility,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Checkbox(
                        value: _rememberMe,
                        onChanged: (value) {
                          setState(() { _rememberMe = value!; });
                        },
                      ),
                      const Text('Remember Me'),
                    ],
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton(
                    onPressed: _isLoading ? null : _login,
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14.0),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12.0),
                      ),
                      backgroundColor: Colors.blueAccent,
                    ),
                    child: const Text(
                      'Login Now',
                      style: TextStyle(fontSize: 16, color: Colors.white),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextButton(
                    onPressed: () => Navigator.pushNamed(context, 'register'),
                    child: const Text(
                      'New on our platform? Create an account',
                      style: TextStyle(color: Colors.blueAccent),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (_isLoading)
          Container(
            color: Colors.black.withOpacity(0.5),
            child: const Center(
              child: CircularProgressIndicator(color: Colors.white),
            ),
          ),
      ],
    );
  }
}