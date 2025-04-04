import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:provider/provider.dart';

// Assuming these are your actual files; adjust as needed
import 'package:whatsapp_monitor/home.dart'; // Ensure WhatsAppMonitorApp is defined here
import 'package:whatsapp_monitor/login.dart';

class LoginStatus with ChangeNotifier {
  bool _isLoggedIn = false;

  bool get isLoggedIn => _isLoggedIn;

  set isLoggedIn(bool value) {
    _isLoggedIn = value;
    notifyListeners();
  }
}

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key}); // Added key parameter for consistency

  @override
  _SplashScreenState createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  @override
  void initState() {
    super.initState();
    _checkLoginStatus();
  }

  Future<void> _checkLoginStatus() async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    String? token = prefs.getString('token');
    int? remainingDays = prefs.getInt('remaining_days');
    int? userId = prefs.getInt('user_id'); // Moved inside the method

    // Simulate a splash screen delay
    await Future.delayed(const Duration(seconds: 2));

    if (token != null && token.isNotEmpty && remainingDays != null) {
      if (remainingDays >= 0) {
        // User is logged in and subscription is valid
        Provider.of<LoginStatus>(context, listen: false).isLoggedIn = true;
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(builder: (context) => const WhatsAppMonitorApp()), // Assuming this is your home screen
        );
      } else {
        // Subscription expired, still navigate to WhatsAppMonitorApp but logged out
        Provider.of<LoginStatus>(context, listen: false).isLoggedIn = false;
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(builder: (context) => const WhatsAppMonitorApp()), // Could redirect to a "Not Authorized" page instead
        );
      }
    } else {
      // No valid token, go to LoginPage
      Provider.of<LoginStatus>(context, listen: false).isLoggedIn = false;
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (context) =>  LoginPage()),
      );
    }

    // Log userId for debugging (optional)
    print('User ID from SharedPreferences: $userId');
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: CircularProgressIndicator(),
      ),
    );
  }
}

// Main application entry point
void main() async {
  await dotenv.load(fileName: '.env');
  WidgetsFlutterBinding.ensureInitialized();

  runApp(
    ChangeNotifierProvider(
      create: (_) => LoginStatus(),
      child: const MyApp(),
    ),
  );
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Flutter Demo',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const SplashScreen(),
    );
  }
}