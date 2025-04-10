package com.example.whatsapp_monitor

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FlutterActivity() {
    private val ACCESSIBILITY_CHANNEL = "com.example.whatsapp_monitor/accessibility"
    private val CONTACTS_CHANNEL = "com.example.whatsapp_monitor/contacts"
    private val STORAGE_CHANNEL = "com.example.whatsapp_monitor/storage"
    private val PREFS_NAME = "MyPrefs"
    private val TAG = "MainActivity"

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    private val CONTACTS_PERMISSION_REQUEST_CODE = 1002
    private val STORAGE_PERMISSION_REQUEST_CODE = 1003
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1004

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "Configuring Flutter engine")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!isNotificationPermissionGranted()) {
                    Log.d(TAG, "Requesting POST_NOTIFICATIONS permission")
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                } else {
                    Log.d(TAG, "POST_NOTIFICATIONS permission already granted")
                }
            }

            val (isMonitoring, label) = WhatsAppMonitorService.loadMonitoringState(this)
            if (isMonitoring) {
                WhatsAppMonitorService.startMonitoring(label, this)
                if (!FloatingButtonService.isRunning) {
                    startService(Intent(this, FloatingButtonService::class.java))
                }
            }

            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ACCESSIBILITY_CHANNEL).apply {
                WhatsAppMonitorService.channel = this
                FloatingButtonService.channel = this
                setMethodCallHandler { call, result ->
                    try {
                        when (call.method) {
                            "openAccessibilitySettings" -> {
                                Log.d(TAG, "Opening accessibility settings")
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                result.success(null)
                            }
                            "startMonitoring" -> {
                                val userId = getSharedPref("user_id")
                                val storeId = getSharedPref("store_id")

                                Log.d(TAG, "Preparing monitoring, userId: $userId, storeId: $storeId")
                                if (!isAccessibilityServiceEnabled()) {
                                    Log.d(TAG, "Accessibility service not enabled")
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    result.error("SERVICE_DISABLED", "Please enable WhatsApp Monitor accessibility service", null)
                                    return@setMethodCallHandler
                                }
                                if (!Settings.canDrawOverlays(this@MainActivity)) {
                                    Log.d(TAG, "Overlay permission not granted, requesting...")
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                                    result.error("PERMISSION_DENIED", "Overlay permission required", null)
                                    return@setMethodCallHandler
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
                                    Log.d(TAG, "Notification permission not granted, requesting...")
                                    requestPermissions(
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                        NOTIFICATION_PERMISSION_REQUEST_CODE
                                    )
                                    result.error("NOTIFICATION_DENIED", "Notification permission required", null)
                                    return@setMethodCallHandler
                                }

                                try {
                                    if (!FloatingButtonService.isRunning) {
                                        val intent = Intent(this@MainActivity, FloatingButtonService::class.java)
                                        startService(intent)
                                        Log.d(TAG, "Started FloatingButtonService")
                                    } else {
                                        val updateIntent = Intent(this@MainActivity, FloatingButtonService::class.java)
                                        updateIntent.action = "UPDATE_STATE"
                                        startService(updateIntent)
                                    }
                                    result.success(true)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error preparing monitoring: ${e.message}", e)
                                    result.error("START_ERROR", "Failed to prepare monitoring: ${e.message}", null)
                                }
                            }
                            "stopMonitoring" -> {
                                Log.d(TAG, "Stopping monitoring")
                                WhatsAppMonitorService.stopMonitoring(this@MainActivity)
                                if (FloatingButtonService.isRunning) {
                                    val intent = Intent(this@MainActivity, FloatingButtonService::class.java)
                                    intent.action = "HIDE_FLOATING_BUTTON"
                                    startService(intent)
                                    Log.d(TAG, "Hiding floating button and stopping service")
                                }
                                result.success(true)
                            }
                            "isMonitoringActive" -> {
                                result.success(WhatsAppMonitorService.isMonitoringActive())
                            }
                            "getCurrentLabel" -> {
                                result.success(WhatsAppMonitorService.getCurrentLabel())
                            }
                            "saveUserInfo" -> {
                                val userId = call.argument<String>("user_id")
                                val storeId = call.argument<String>("store_id")
                                if (userId != null && storeId != null) {
                                    saveToSharedPref("user_id", userId)
                                    saveToSharedPref("store_id", storeId)
                                    Log.d(TAG, "Saved user_id: $userId, store_id: $storeId")
                                    result.success(true)
                                } else {
                                    result.error("INVALID_ARGUMENT", "user_id or store_id missing", null)
                                }
                            }
                            else -> result.notImplemented()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in method call ${call.method}: ${e.message}", e)
                        result.error("METHOD_ERROR", "Error processing ${call.method}: ${e.message}", null)
                    }
                }
            }

            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTACTS_CHANNEL).setMethodCallHandler { call, result ->
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
                        result.error("PERMISSION_DENIED", "Contacts permission not granted", null)
                        return@setMethodCallHandler
                    }

                    when (call.method) {
                        "getPhoneContacts" -> {
                            val offset = call.argument<Int>("offset") ?: 0
                            val limit = call.argument<Int>("limit") ?: 100
                            coroutineScope.launch {
                                val contacts = withContext(Dispatchers.IO) { getPhoneContacts(offset, limit) }
                                result.success(contacts)
                                Log.d(TAG, "Loaded ${contacts.size} contacts (offset: $offset, limit: $limit)")
                            }
                        }
                        "getTotalContactCount" -> {
                            coroutineScope.launch {
                                val count = withContext(Dispatchers.IO) { getTotalContactCount() }
                                result.success(count)
                                Log.d(TAG, "Total contact count: $count")
                            }
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in contacts method call ${call.method}: ${e.message}", e)
                    result.error("CONTACTS_ERROR", "Error processing ${call.method}: ${e.message}", null)
                }
            }

            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, STORAGE_CHANNEL).setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "requestStoragePermission" -> {
                            val sdkVersion = Build.VERSION.SDK_INT
                            if (sdkVersion >= 33) {
                                result.success(true)
                            } else if (sdkVersion >= 30) {
                                if (Environment.isExternalStorageManager()) {
                                    result.success(true)
                                } else {
                                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE)
                                    result.success(false)
                                }
                            } else {
                                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(
                                        arrayOf(
                                            Manifest.permission.READ_EXTERNAL_STORAGE,
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        ),
                                        STORAGE_PERMISSION_REQUEST_CODE
                                    )
                                    result.success(false)
                                } else {
                                    result.success(true)
                                }
                            }
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in storage method call ${call.method}: ${e.message}", e)
                    result.error("STORAGE_ERROR", "Error processing ${call.method}: ${e.message}", null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Flutter engine: ${e.message}", e)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        return accessibilityManager?.isEnabled ?: false
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Notification permission granted")
                    if (!FloatingButtonService.isRunning) {
                        startService(Intent(this, FloatingButtonService::class.java))
                    }
                } else {
                    Log.w(TAG, "Notification permission denied")
                }
            }
            CONTACTS_PERMISSION_REQUEST_CODE -> {
                Log.d(TAG, if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    "Contacts permission granted" else "Contacts permission denied")
            }
            STORAGE_PERMISSION_REQUEST_CODE -> {
                Log.d(TAG, if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    "Storage permission granted" else "Storage permission denied")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE && Build.VERSION.SDK_INT >= 30) {
            Log.d(TAG, if (Environment.isExternalStorageManager())
                "MANAGE_EXTERNAL_STORAGE granted" else "MANAGE_EXTERNAL_STORAGE denied")
        }
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            Log.d(TAG, if (Settings.canDrawOverlays(this))
                "Overlay permission granted" else "Overlay permission denied")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")
    }

    private fun getPhoneContacts(offset: Int, limit: Int): List<Map<String, String>> {
        val contacts = mutableMapOf<String, Map<String, String>>()
        val contentResolver: ContentResolver = contentResolver
        try {
            val cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $limit OFFSET $offset"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val contactId = it.getString(it.getColumnIndex(ContactsContract.Contacts._ID))
                    val name = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)) ?: "Unknown"
                    val phoneCursor = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )
                    val number = phoneCursor?.use { pCursor ->
                        if (pCursor.moveToFirst())
                            pCursor.getString(pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                        else ""
                    } ?: ""
                    contacts[contactId] = mapOf("name" to name, "number" to number)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting phone contacts: ${e.message}", e)
        }
        return contacts.values.toList()
    }

    private fun getTotalContactCount(): Int {
        val contentResolver: ContentResolver = contentResolver
        var count = 0
        try {
            val cursor = contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )
            count = cursor?.use { it.count } ?: 0
            cursor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting total contact count: ${e.message}", e)
        }
        return count
    }

    private fun saveToSharedPref(key: String, value: String) {
        try {
            val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to SharedPreferences: ${e.message}", e)
        }
    }

    private fun getSharedPref(key: String): String? {
        try {
            val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(key, "")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting from SharedPreferences: ${e.message}", e)
            return ""
        }
    }
}