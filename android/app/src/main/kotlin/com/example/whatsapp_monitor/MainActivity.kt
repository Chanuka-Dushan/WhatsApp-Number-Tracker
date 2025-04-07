package com.example.whatsapp_monitor

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
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

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    private val CONTACTS_PERMISSION_REQUEST_CODE = 1002
    private val STORAGE_PERMISSION_REQUEST_CODE = 1003

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d("MainActivity", "Configuring Flutter engine")

        // Accessibility Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ACCESSIBILITY_CHANNEL).apply {
            WhatsAppMonitorService.channel = this
            FloatingButtonService.channel = this
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "openAccessibilitySettings" -> {
                        Log.d("MainActivity", "Opening accessibility settings")
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }

                    "startMonitoring" -> {
                        val label = call.argument<String>("label") ?: ""
                        val userId = getSharedPref("user_id")
                        val storeId = getSharedPref("store_id")

                        Log.d("MainActivity", "Starting monitoring with label: $label, userId: $userId, storeId: $storeId")
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            WhatsAppMonitorService.startMonitoring(label)
                            startService(Intent(this@MainActivity, FloatingButtonService::class.java))
                            result.success(true)
                        } else {
                            Log.d("MainActivity", "Overlay permission not granted")
                            result.error("PERMISSION_DENIED", "Overlay permission required", null)
                        }
                    }

                    "stopMonitoring" -> {
                        Log.d("MainActivity", "Stopping monitoring")
                        WhatsAppMonitorService.stopMonitoring()
                        stopService(Intent(this@MainActivity, FloatingButtonService::class.java))
                        result.success(true)
                    }

                    "saveUserInfo" -> {
                        val userId = call.argument<String>("user_id")
                        val storeId = call.argument<String>("store_id")
                        if (userId != null && storeId != null) {
                            saveToSharedPref("user_id", userId)
                            saveToSharedPref("store_id", storeId)
                            Log.d("MainActivity", "Saved user_id: $userId, store_id: $storeId")
                            result.success(true)
                        } else {
                            result.error("INVALID_ARGUMENT", "user_id or store_id missing", null)
                        }
                    }

                    else -> result.notImplemented()
                }
            }
        }

        // Contacts Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTACTS_CHANNEL).setMethodCallHandler { call, result ->
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
                        Log.d("MainActivity", "Loaded ${contacts.size} contacts (offset: $offset, limit: $limit)")
                    }
                }

                "getTotalContactCount" -> {
                    coroutineScope.launch {
                        val count = withContext(Dispatchers.IO) { getTotalContactCount() }
                        result.success(count)
                        Log.d("MainActivity", "Total contact count: $count")
                    }
                }

                else -> result.notImplemented()
            }
        }

        // Storage Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, STORAGE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestStoragePermission" -> {
                    val sdkVersion = android.os.Build.VERSION.SDK_INT
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
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CONTACTS_PERMISSION_REQUEST_CODE -> {
                Log.d("MainActivity", if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    "Contacts permission granted" else "Contacts permission denied")
            }

            STORAGE_PERMISSION_REQUEST_CODE -> {
                Log.d("MainActivity", if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    "Storage permission granted" else "Storage permission denied")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE && android.os.Build.VERSION.SDK_INT >= 30) {
            Log.d("MainActivity", if (Environment.isExternalStorageManager())
                "MANAGE_EXTERNAL_STORAGE granted" else "MANAGE_EXTERNAL_STORAGE denied")
        }
    }

    private fun getPhoneContacts(offset: Int, limit: Int): List<Map<String, String>> {
        val contacts = mutableMapOf<String, Map<String, String>>()
        val contentResolver: ContentResolver = contentResolver
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
        return contacts.values.toList()
    }

    private fun getTotalContactCount(): Int {
        val contentResolver: ContentResolver = contentResolver
        val cursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null
        )
        val count = cursor?.use { it.count } ?: 0
        cursor?.close()
        return count
    }

    private fun saveToSharedPref(key: String, value: String) {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    private fun getSharedPref(key: String): String? {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(key, "")
    }
}
