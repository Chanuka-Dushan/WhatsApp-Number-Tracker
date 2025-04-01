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
        // Wait for manual WhatsApp opening; no automatic launch
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
                } else {
                    Log.d("WhatsAppMonitor", "No root node, ${it.packageName} might be in background")
                }
            }
        }
    }

    private fun isChatsTab(rootNode: AccessibilityNodeInfo): Boolean {
        val contactRows = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/contact_row_container")
        val chatList = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/chat_list")
        val conversations = rootNode.findAccessibilityNodeInfosByViewId("${rootNode.packageName}:id/conversations")
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
                "${rootNode.packageName}:id/chat_list",
                "${rootNode.packageName}:id/conversations"
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