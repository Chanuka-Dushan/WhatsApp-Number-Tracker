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

class MainActivity : FlutterActivity() {
    private val ACCESSIBILITY_CHANNEL = "com.example.whatsapp_monitor/accessibility"
    private val CONTACTS_CHANNEL = "com.example.whatsapp_monitor/contacts"
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    private val CONTACTS_PERMISSION_REQUEST_CODE = 1002

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
                    else -> {
                        result.notImplemented()
                    }
                }
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTACTS_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "getPhoneContacts") {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    val contacts = getPhoneContacts()
                    result.success(contacts)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), CONTACTS_PERMISSION_REQUEST_CODE)
                    result.error("PERMISSION_DENIED", "Contacts permission not granted", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CONTACTS_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("MainActivity", "Contacts permission granted")
                } else {
                    Log.d("MainActivity", "Contacts permission denied")
                }
            }
        }
    }

    private fun getPhoneContacts(): List<Map<String, String>> {
        val contacts = mutableListOf<Map<String, String>>()
        val contentResolver: ContentResolver = contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            null
        )
        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: "Unknown"
                val number = it.getString(numberIndex) ?: ""
                contacts.add(mapOf("name" to name, "number" to number))
            }
        }
        return contacts
    }
}