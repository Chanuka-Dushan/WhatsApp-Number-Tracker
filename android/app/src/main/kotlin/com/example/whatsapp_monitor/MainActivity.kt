package com.example.whatsapp_monitor

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.content.ContentResolver
import android.provider.ContactsContract
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FlutterActivity() {
    private val ACCESSIBILITY_CHANNEL = "com.example.whatsapp_monitor/accessibility"
    private val CONTACTS_CHANNEL = "com.example.whatsapp_monitor/contacts"
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    private val CONTACTS_PERMISSION_REQUEST_CODE = 1002
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d("MainActivity", "Configuring Flutter engine")

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
                        Log.d("MainActivity", "Starting monitoring with label: $label")
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
                    else -> result.notImplemented()
                }
            }
        }

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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CONTACTS_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Contacts permission granted")
        } else {
            Log.d("MainActivity", "Contacts permission denied")
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
                    if (pCursor.moveToFirst()) pCursor.getString(pCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
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
}