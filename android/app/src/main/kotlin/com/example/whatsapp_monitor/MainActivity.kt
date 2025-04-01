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

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ACCESSIBILITY_CHANNEL).apply {
            WhatsAppMonitorService.channel = this
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "startMonitoring" -> {
                        Log.d("WhatsAppMonitor", "Starting monitoring")
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                        result.success("enabled")
                    }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CONTACTS_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "getPhoneContacts") {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                    val contacts = getPhoneContacts()
                    result.success(contacts)
                } else {
                    result.error("PERMISSION_DENIED", "Contacts permission not granted", null)
                }
            } else {
                result.notImplemented()
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