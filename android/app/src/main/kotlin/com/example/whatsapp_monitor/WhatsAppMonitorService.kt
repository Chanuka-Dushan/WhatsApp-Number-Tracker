package com.example.whatsapp_monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import com.google.gson.Gson
import android.os.Handler
import android.os.Looper

class WhatsAppMonitorService : AccessibilityService() {
    companion object {
        var channel: MethodChannel? = null
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b") // Regular and Business WhatsApp
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastScanTime = 0L
    private val scanCooldown = 5000L // 5 seconds cooldown
    private var consecutiveScrollFails = 0
    private val maxConsecutiveFails = 10 // Stop after 10 consecutive failures

    override fun onServiceConnected() {
        Log.d("WhatsAppMonitor", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.d("WhatsAppMonitor", "Event received: ${event?.eventType}")
        event?.let {
            if (WHATSAPP_PACKAGES.contains(it.packageName) && !isScanning && (System.currentTimeMillis() - lastScanTime > scanCooldown)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    Log.d("WhatsAppMonitor", "Root node found: ${rootNode.className}")
                    dumpNodeInfo(rootNode)
                    if (isChatsTab(rootNode)) {
                        Log.d("WhatsAppMonitor", "Chats tab detected in ${it.packageName}, starting auto-scan")
                        autoScanChatList(rootNode)
                        lastScanTime = System.currentTimeMillis()
                    } else {
                        Log.d("WhatsAppMonitor", "Not on Chats tab in ${it.packageName}")
                    }
                }
            }
        }
    }

    private fun isChatsTab(rootNode: AccessibilityNodeInfo): Boolean {
        val contactRows = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/contact_row_container")
        val chatList = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/chat_list")
        val conversations = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/conversations")
        val tabIndicator = rootNode.findAccessibilityNodeInfosByText("Chats")
        
        return contactRows.isNotEmpty() || chatList.isNotEmpty() || conversations.isNotEmpty() || tabIndicator.isNotEmpty()
    }

    private fun autoScanChatList(rootNode: AccessibilityNodeInfo) {
        isScanning = true
        val chatEntries = mutableListOf<String>()
        
        Log.d("WhatsAppMonitor", "Initial scan of Chats tab")
        findChatEntries(rootNode, chatEntries)
        sendChatEntries(chatEntries)

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
                }

                if (scrollAttempts < maxScrollAttempts && performScroll(scrollableNode)) {
                    consecutiveScrollFails = 0
                    val newEntries = mutableListOf<String>()
                    findChatEntries(rootNode, newEntries)
                    sendChatEntries(newEntries)
                    scrollAttempts++
                    handler.postDelayed(this, 500)
                } else if (scrollAttempts < maxScrollAttempts) {
                    consecutiveScrollFails++
                    if (consecutiveScrollFails >= maxConsecutiveFails) {
                        Log.d("WhatsAppMonitor", "Detected end of chat list")
                        disableService()
                        return
                    }
                    scrollAttempts++
                    handler.postDelayed(this, 1000)
                } else {
                    Log.d("WhatsAppMonitor", "Chat list scan completed")
                    disableService()
                }
            }
        }, 2000)
    }

    private fun findChatListScrollableNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun searchScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.contains("RecyclerView") == true && node.isScrollable) {
                return node
            }
            val chatListIds = listOf(
                "${rootNode.packageName}:id/chat_list",
                "${rootNode.packageName}:id/conversations"
            )
            for (id in chatListIds) {
                val nodes = node.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) return nodes.first()
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = searchScrollable(child)
                if (result != null) return result
                child.recycle()
            }
            return null
        }
        return searchScrollable(rootNode)
    }

    private fun performScroll(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findChatEntries(node: AccessibilityNodeInfo, entries: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.isNotBlank() && !text.contains("\n") && text.length > 1) {
            // Filter out irrelevant text (e.g., timestamps, message previews)
            val isContactRow = node.viewIdResourceName?.contains("contact_row_container") == true
            if (isContactRow || (!isPhoneNumber(text) && text.length < 20) || isPhoneNumber(text)) {
                if (!entries.contains(text)) {
                    Log.d("WhatsAppMonitor", "Found chat entry: '$text'")
                    entries.add(text)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findChatEntries(child, entries)
                child.recycle()
            }
        }
    }

    private fun isPhoneNumber(text: String): Boolean {
        val phoneRegex = Regex("\\+?[1-9]\\d{6,14}")
        val cleanedText = text.trim().replace(" ", "").replace("-", "").replace(".", "")
        return phoneRegex.matches(cleanedText)
    }

    private fun sendChatEntries(entries: List<String>) {
        entries.forEach { entry ->
            val data = mapOf(
                "text" to entry,
                "eventType" to "AutoScan",
                "timestamp" to System.currentTimeMillis().toString(),
                "source" to "ChatList"
            )
            Log.d("WhatsAppMonitor", "Sending entry: $data")
            channel?.invokeMethod("onUIEvent", Gson().toJson(data))
        }
    }

    private fun disableService() {
        isScanning = false
        disableSelf()
    }

    private fun dumpNodeInfo(node: AccessibilityNodeInfo) {
        Log.d("WhatsAppMonitor", "Node class: ${node.className}, ID: ${node.viewIdResourceName}, Child count: ${node.childCount}")
    }

    override fun onInterrupt() {
        Log.d("WhatsAppMonitor", "Service interrupted")
        isScanning = false
    }
}