# WhatsApp Number Tracker

A Flutter application that monitors and extracts phone numbers from your WhatsApp chat list (both regular WhatsApp and WhatsApp Business), displaying them in the app UI. It fetches saved contacts from your phone and matches them with WhatsApp chats, showing names for saved contacts and "unknown" for unsaved numbers, while preserving the exact order of your WhatsApp Chats tab.

## Features
- **Tracks WhatsApp Chats:** Extracts phone numbers and names from your WhatsApp chat list when you manually open WhatsApp.
- **Supports Both WhatsApp Variants:** Works with regular WhatsApp (`com.whatsapp`) and WhatsApp Business (`com.whatsapp.w4b`).
- **Matches Saved Contacts:** Retrieves phone contacts and matches them with WhatsApp chat entries to display names (e.g., `name=Alice, number=0771xxxxx, label=null`).
- **Handles Unsaved Numbers:** Displays unsaved WhatsApp numbers as `name=unknown, number=947723xxxxxx, label=null`.
- **Preserves Chat Order:** Shows the chat list in the exact order as it appears in your WhatsApp Chats tab.
- **Manual WhatsApp Launch:** You open WhatsApp manually; the app monitors the chat list via an accessibility service.

## Project Structure
- **`lib/main.dart`**: The Flutter frontend that handles UI, permissions, and data processing.
- **`android/app/src/main/kotlin/com/example/whatsapp_monitor/WhatsAppMonitorService.kt`**: The Android accessibility service that scans WhatsApp chats.
- **`android/app/src/main/kotlin/com/example/whatsapp_monitor/MainActivity.kt`**: The Android entry point that bridges Flutter with native Android functionality.

## Dependencies
The app uses the following Dart packages in `lib/main.dart`:

- **`flutter/material.dart`**: Provides Flutter's Material Design widgets for building the UI (e.g., `Scaffold`, `AppBar`, `ListView`).
- **`flutter/services.dart`**: Enables platform channel communication between Flutter and Android (e.g., `MethodChannel` for accessibility and contacts).
- **`dart:convert`**: Handles JSON encoding/decoding for data passed between Android and Flutter.
- **`permission_handler/permission_handler.dart`**: Manages runtime permissions (e.g., contacts permission).

Add these to your `pubspec.yaml`:
```yaml
dependencies:
  flutter:
    sdk: flutter
  permission_handler: ^10.2.0
```

## How It Works
1. **Initialization**:
   - The app requests contacts permission and loads all phone contacts into `savedContacts`.
   - It sets up an accessibility service listener to monitor WhatsApp.

2. **WhatsApp Monitoring**:
   - When you manually open WhatsApp (regular or Business) and navigate to the Chats tab, the `WhatsAppMonitorService` scans the UI.
   - It captures names (e.g., "Alice") and numbers (e.g., "+94771234567") from chat rows (`com.whatsapp:id/contact_row_container`).
   - Data is sent to Flutter via a `MethodChannel`.

3. **Data Processing**:
   - For saved contacts (e.g., "Alice"), it matches the name with `savedContacts` to fetch the number (e.g., "0771234567").
   - For unsaved numbers, it uses "unknown" as the name (e.g., `name=unknown, number=94772xxxxxxx`).
   - Entries are added to `whatsappNumbers` or processed into a chat list in the order received from WhatsApp.

4. **UI Display**:
   - Shows only WhatsApp chat entries in the format: `name=<name>,number=<number>,label=null`.
   - Preserves the exact order of your WhatsApp Chats tab.

## Example Output
If your WhatsApp Chats tab looks like this:
1. A saved contact named "Alice"
2. An unsaved number "+94772345678"
3. A saved contact named "Bob"

The app UI will display:
```
name=Alice,number=07712xxxx ,label=null
name=unknown,number=9477xxxxx,label=null
name=Bob,number=077xxxxxxx ,label=null
```

## Setup Instructions
### Prerequisites
- Flutter SDK (latest stable version)
- Android Studio or VS Code with Flutter/Dart plugins
- An Android device/emulator (API 21+)

### Steps
1. **Clone the Repository**:
   ```bash
   git clone <repository-url>
   cd whatsapp-number-tracker
   ```

2. **Install Dependencies**:
   ```bash
   flutter pub get
   ```

3. **Configure Android**:
   - Open `android/app/src/main/AndroidManifest.xml` and add:
     ```xml
     <uses-permission android:name="android.permission.READ_CONTACTS" />
     <service
         android:name=".WhatsAppMonitorService"
         android:label="WhatsApp Monitor Service"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
         <intent-filter>
             <action android:name="android.accessibilityservice.AccessibilityService" />
         </intent-filter>
     </service>
     ```

4. **Run the App**:
   ```bash
   flutter run
   ```

5. **Grant Permissions**:
   - **Contacts Permission**: Allow when prompted to fetch phone contacts.
   - **Accessibility Service**: Go to Settings > Accessibility, enable "WhatsApp Monitor Service".

6. **Use the App**:
   - Launch the app.
   - Manually open WhatsApp (regular or Business) and go to the Chats tab.
   - The app will display your chat list as described.

## Code Details
### `lib/main.dart`
- **Entry Point**: `main()` runs `WhatsAppMonitorApp`, a stateless widget setting up the MaterialApp.
- **State Management**: `WhatsAppMobileMonitor` uses a stateful widget to manage `savedContacts` (phone contacts) and `whatsappNumbers` (unsaved WhatsApp numbers).
- **Permissions**: `_requestContactsPermission()` uses `permission_handler` to request contacts access.
- **Monitoring**: `_initializeMonitoring()` and `_setupChannelListener()` connect to the Android accessibility service via `MethodChannel`.
- **UI**: Displays two sections:
  - Saved contacts from the phone (for reference).
  - Unsaved WhatsApp numbers detected from chats.

### `WhatsAppMonitorService.kt`
- **Accessibility Service**: Scans WhatsApp UI when the Chats tab is active.
- **Chat Detection**: Recursively searches for phone numbers in `contact_row_container` nodes.
- **Scrolling**: Automatically scrolls the chat list to capture all entries, stopping after 10 failed attempts or 100 scrolls.
- **Data Sending**: Sends detected numbers to Flutter via `MethodChannel`.

### `MainActivity.kt`
- **Flutter-Native Bridge**: Sets up `MethodChannel`s for accessibility and contacts.
- **Contacts Fetching**: Queries `ContactsContract` to retrieve phone contacts.

## Modifications Made
- **Chat Order**: Preserves WhatsApp chat order by adding entries to the list as received.
- **Saved Contacts**: Matches names (e.g., "Alice") with `savedContacts` to fetch numbers (e.g., "0771xxxxxx").
- **Unsaved Numbers**: Labels unsaved numbers as "unknown" (e.g., `name=unknown,number=947xxxxxx,label=null`).
- **Manual WhatsApp Opening**: Requires you to open WhatsApp manually; the service monitors once the Chats tab is active.

## Troubleshooting
- **No Chats Displayed**:
  - Ensure the accessibility service is enabled in Settings > Accessibility.
  - Open WhatsApp manually and scroll through the Chats tab.
  - Check logs (`flutter run -v`) for errors.
- **Contacts Not Matching**:
  - Verify phone contact names match WhatsApp chat names exactly (case-sensitive).
- **Permission Issues**:
  - Grant contacts permission in app settings if denied.


## License
This project is open-source under the MIT License.

