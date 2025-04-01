package com.example.whatsapp_monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import com.google.gson.Gson
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.ComponentName

class WhatsAppMonitorService : AccessibilityService() {
    companion object {
        var channel: MethodChannel? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastScanTime = 0L
    private val scanCooldown = 5000L // 5 seconds cooldown
    private var consecutiveScrollFails = 0
    private val maxConsecutiveFails = 10 // Stop after 10 consecutive failures

    override fun onServiceConnected() {
        Log.d("WhatsAppMonitor", "Service connected")
        // Start scanning when service is enabled
        handler.postDelayed({ startWhatsAppScan() }, 1000) // Delay to ensure service is ready
    }

    private fun startWhatsAppScan() {
        val rootNode = rootInActiveWindow
        if (rootNode != null && rootNode.packageName == "com.whatsapp") {
            Log.d("WhatsAppMonitor", "WhatsApp is already open, proceeding with scan")
            if (isChatsTab(rootNode)) {
                autoScanChatList(rootNode)
            } else {
                Log.d("WhatsAppMonitor", "Not on Chats tab, waiting for tab switch")
            }
        } else {
            Log.d("WhatsAppMonitor", "WhatsApp is not active, launching it")
            launchWhatsApp()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d("WhatsAppMonitor", "Event received: ${event?.eventType}")
        event?.let {
            if (it.packageName == "com.whatsapp" && !isScanning && (System.currentTimeMillis() - lastScanTime > scanCooldown)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    Log.d("WhatsAppMonitor", "Root node found: ${rootNode.className}")
                    dumpNodeInfo(rootNode)
                    if (isChatsTab(rootNode)) {
                        Log.d("WhatsAppMonitor", "Chats tab detected, starting auto-scan")
                        autoScanChatList(rootNode)
                        lastScanTime = System.currentTimeMillis()
                    } else {
                        Log.d("WhatsAppMonitor", "Not Chats tab")
                    }
                } else {
                    Log.d("WhatsAppMonitor", "No root node, WhatsApp might be in background")
                }
            }
        }
    }

    private fun isChatsTab(rootNode: AccessibilityNodeInfo): Boolean {
        val contactRows = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_row_container")
        val chatList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/chat_list")
        val conversations = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations")
        val tabIndicator = rootNode.findAccessibilityNodeInfosByText("Chats")
        
        Log.d("WhatsAppMonitor", "ContactRows: ${contactRows.size}, ChatList: ${chatList.size}, " +
                "Conversations: ${conversations.size}, ChatsTab: ${tabIndicator.size}")
        
        return contactRows.isNotEmpty() || chatList.isNotEmpty() || conversations.isNotEmpty() || tabIndicator.isNotEmpty()
    }

    private fun autoScanChatList(rootNode: AccessibilityNodeInfo) {
        isScanning = true
        val numbers = mutableListOf<String>()
        
        Log.d("WhatsAppMonitor", "Initial scan of Chats tab")
        findPhoneNumbers(rootNode, numbers)
        sendNumbers(numbers)

        handler.postDelayed(object : Runnable {
            var scrollAttempts = 0
            val maxScrollAttempts = 100

            override fun run() {
                val scrollableNode = findChatListScrollableNode(rootNode)
                if (scrollableNode == null) {
                    Log.d("WhatsAppMonitor", "No scrollable chat list found")
                    disableService()
                    return
                }

                scrollableNode.refresh()
                if (!scrollableNode.isFocused) {
                    scrollableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Log.d("WhatsAppMonitor", "Forced focus on RecyclerView")
                }

                if (scrollAttempts < maxScrollAttempts && performScroll(scrollableNode)) {
                    Log.d("WhatsAppMonitor", "Scrolling chat list attempt $scrollAttempts")
                    consecutiveScrollFails = 0
                    val newNumbers = mutableListOf<String>()
                    findPhoneNumbers(rootNode, newNumbers)
                    sendNumbers(newNumbers)
                    scrollAttempts++
                    handler.postDelayed(this, 500)
                } else if (scrollAttempts < maxScrollAttempts) {
                    Log.d("WhatsAppMonitor", "Scroll failed, retrying attempt $scrollAttempts")
                    consecutiveScrollFails++
                    if (consecutiveScrollFails >= maxConsecutiveFails) {
                        Log.d("WhatsAppMonitor", "Detected end of chat list after $consecutiveScrollFails consecutive failures")
                        disableService()
                        return
                    }
                    scrollAttempts++
                    handler.postDelayed(this, 1000)
                } else {
                    Log.d("WhatsAppMonitor", "Chat list scan completed after $scrollAttempts attempts, disabling service")
                    disableService()
                }
            }
        }, 2000) // Initial 2-second delay
    }

    private fun findChatListScrollableNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun searchScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.contains("RecyclerView") == true && node.isScrollable) {
                Log.d("WhatsAppMonitor", "Found chat list scrollable node: ${node.className}, ID: ${node.viewIdResourceName}")
                return node
            }
            val chatListIds = listOf(
                "com.whatsapp:id/chat_list",
                "com.whatsapp:id/conversations"
            )
            for (id in chatListIds) {
                val nodes = node.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    Log.d("WhatsAppMonitor", "Found chat list scrollable node with ID: $id")
                    return nodes.first()
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = searchScrollable(child)
                if (result != null) return result
                child.recycle()
            }
            return null
        }
        val scrollable = searchScrollable(rootNode)
        if (scrollable == null) {
            Log.d("WhatsAppMonitor", "No RecyclerView or scrollable chat list found in hierarchy")
        }
        return scrollable
    }

    private fun performScroll(node: AccessibilityNodeInfo): Boolean {
        val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        Log.d("WhatsAppMonitor", "Scroll action on ${node.className}: $scrolled, " +
                "isEnabled: ${node.isEnabled}, isVisible: ${node.isVisibleToUser}, " +
                "isScrollable: ${node.isScrollable}")
        return scrolled
    }

    private fun findPhoneNumbers(node: AccessibilityNodeInfo, numbers: MutableList<String>) {
        val text = node.text?.toString() ?: ""
        Log.d("WhatsAppMonitor", "Checking text: '$text'")
        if (isPhoneNumber(text)) {
            numbers.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findPhoneNumbers(child, numbers)
                child.recycle()
            }
        }
    }

    private fun isPhoneNumber(text: String): Boolean {
        val phoneRegex = Regex("\\+?[1-9]\\d{6,14}")
        val cleanedText = text.trim().replace(" ", "").replace("-", "").replace(".", "")
        val isMatch = phoneRegex.matches(cleanedText)
        if (isMatch) Log.d("WhatsAppMonitor", "Found number: $cleanedText")
        return isMatch
    }

    private fun sendNumbers(numbers: List<String>) {
        numbers.forEach { number ->
            val data = mapOf(
                "text" to number,
                "eventType" to "AutoScan",
                "timestamp" to System.currentTimeMillis().toString(),
                "source" to "ChatList"
            )
            Log.d("WhatsAppMonitor", "Sending number: $data")
            channel?.invokeMethod("onUIEvent", Gson().toJson(data))
        }
    }

    private fun launchWhatsApp() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.component = ComponentName("com.whatsapp", "com.whatsapp.Main")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            startActivity(intent)
            Log.d("WhatsAppMonitor", "Launched WhatsApp")
        } catch (e: Exception) {
            Log.e("WhatsAppMonitor", "Failed to launch WhatsApp: ${e.message}")
            disableService()
        }
    }

    private fun disableService() {
        isScanning = false
        disableSelf()
        Log.d("WhatsAppMonitor", "Accessibility service disabled")
    }

    private fun dumpNodeInfo(node: AccessibilityNodeInfo) {
        Log.d("WhatsAppMonitor", "Node class: ${node.className}, ID: ${node.viewIdResourceName}, " +
                "Child count: ${node.childCount}")
    }

    override fun onInterrupt() {
        Log.d("WhatsAppMonitor", "Service interrupted")
        isScanning = false
    }
}