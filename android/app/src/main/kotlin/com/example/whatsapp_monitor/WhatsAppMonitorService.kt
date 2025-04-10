package com.example.whatsapp_monitor

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import io.flutter.plugin.common.MethodChannel

class WhatsAppMonitorService : AccessibilityService() {
    companion object {
        var channel: MethodChannel? = null
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private var isMonitoring = false
        private var currentLabel: String = "All"
        private const val PREFS_NAME = "WhatsAppMonitorPrefs"
        private const val KEY_IS_MONITORING = "is_monitoring"
        private const val KEY_CURRENT_LABEL = "current_label"
        private const val TAG = "WhatsAppMonitorService"

        fun startMonitoring(label: String?, context: Context) {
            isMonitoring = true
            currentLabel = label?.takeIf { it.isNotEmpty() } ?: "All"
            saveMonitoringState(context, true, currentLabel)
            Log.d(TAG, "Monitoring started with label: $currentLabel")
        }

        fun stopMonitoring(context: Context) {
            isMonitoring = false
            saveMonitoringState(context, false, currentLabel)
            Log.d(TAG, "Monitoring stopped, retaining label: $currentLabel")
        }

        fun isMonitoringActive(): Boolean = isMonitoring
        fun getCurrentLabel(): String = currentLabel
        fun setCurrentLabel(label: String) { 
            currentLabel = label 
            Log.d(TAG, "Label set to: $currentLabel")
        }

        private fun saveMonitoringState(context: Context, isActive: Boolean, label: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_IS_MONITORING, isActive)
                putString(KEY_CURRENT_LABEL, label)
                apply()
            }
        }

        fun loadMonitoringState(context: Context): Pair<Boolean, String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return Pair(
                prefs.getBoolean(KEY_IS_MONITORING, false),
                prefs.getString(KEY_CURRENT_LABEL, "All") ?: "All"
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastScanTime = 0L
    private val scanCooldown = 5000L
    private var consecutiveScrollFails = 0
    private val maxConsecutiveFails = 10

    override fun onCreate() {
        super.onCreate()
        val (isActive, label) = loadMonitoringState(this)
        isMonitoring = isActive
        currentLabel = label
        Log.d(TAG, "Service created with monitoring state: $isMonitoring, label: '$label'")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            if (WHATSAPP_PACKAGES.contains(it.packageName) && 
                isMonitoringActive() && 
                !isScanning &&
                (System.currentTimeMillis() - lastScanTime > scanCooldown)) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    Log.d(TAG, "Root node found: ${rootNode.className}")
                    if (isChatsTab(rootNode)) {
                        Log.d(TAG, "Chats tab detected in ${it.packageName}, starting scan")
                        autoScanChatList(rootNode)
                        lastScanTime = System.currentTimeMillis()
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
        val allTextNodes = mutableListOf<String>()

        Log.d(TAG, "Starting auto-scan of chat list")
        findChatEntries(rootNode, chatEntries, allTextNodes)
        detectLabelFromPattern(allTextNodes)
        sendChatEntries(chatEntries)

        val scrollRunnable = object : Runnable {
            var scrollAttempts = 0
            val maxScrollAttempts = 100
            var lastVisibleItemCount = 0

            override fun run() {
                if (!isMonitoring) {
                    Log.d(TAG, "Monitoring stopped during scan")
                    isScanning = false
                    handler.removeCallbacks(this)
                    return
                }

                val scrollableNode = findChatListScrollableNode(rootNode)
                if (scrollableNode == null && scrollAttempts == 0) {
                    Log.d(TAG, "No scrollable chat list found on first attempt, retrying with broader search")
                    val fallbackNode = findAnyScrollableNode(rootNode)
                    if (fallbackNode != null) {
                        performScroll(fallbackNode)
                        handler.postDelayed(this, 750)
                        return
                    }
                }

                val nodeToScroll = scrollableNode ?: rootNode
                nodeToScroll.refresh()
                if (!nodeToScroll.isFocused) {
                    nodeToScroll.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }

                val currentVisibleItemCount = countVisibleItems(nodeToScroll)
                val scrolled = performScroll(nodeToScroll)

                if (scrolled || scrollAttempts == 0) {
                    consecutiveScrollFails = 0
                    lastVisibleItemCount = currentVisibleItemCount
                    val newEntries = mutableListOf<String>()
                    val newTextNodes = mutableListOf<String>()
                    findChatEntries(rootNode, newEntries, newTextNodes)
                    if (scrollAttempts == 0) detectLabelFromPattern(newTextNodes)
                    sendChatEntries(newEntries)
                    chatEntries.addAll(newEntries.filter { it !in chatEntries })
                    scrollAttempts++
                    if (scrollAttempts < maxScrollAttempts) {
                        handler.postDelayed(this, 750)
                    } else {
                        Log.d(TAG, "Max scroll attempts reached ($scrollAttempts)")
                        isScanning = false
                        sendFinalChatEntries(chatEntries)
                    }
                } else {
                    consecutiveScrollFails++
                    if (consecutiveScrollFails >= maxConsecutiveFails) {
                        Log.d(TAG, "Reached end of chat list or max fails (attempts: $scrollAttempts, fails: $consecutiveScrollFails)")
                        isScanning = false
                        sendFinalChatEntries(chatEntries)
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
        handler.postDelayed(scrollRunnable, 1000)
    }

    private fun countVisibleItems(node: AccessibilityNodeInfo): Int {
        var count = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isVisibleToUser) count++
            child.recycle()
        }
        return count
    }

    private fun detectLabelFromPattern(allTextNodes: List<String>) {
        if (allTextNodes.isEmpty()) return

        val knownLabels = listOf("All", "Unread", "Favourites", "Chats", "Groups")
        val firstFewNodes = allTextNodes.take(3)

        if (firstFewNodes.size >= 2) {
            val firstText = firstFewNodes[0]
            val secondText = firstFewNodes[1]
            if (secondText.matches(Regex("\\d+\\s+items"))) {
                setCurrentLabel(firstText)
                Log.d(TAG, "Detected label from pattern: '$firstText', followed by '$secondText'")
                return
            }
        }

        firstFewNodes.forEach { text ->
            if (knownLabels.contains(text)) {
                setCurrentLabel(text)
                Log.d(TAG, "Detected label from known labels: $currentLabel")
                return
            }
        }

        if (currentLabel.isEmpty() || currentLabel == "Chats") {
            setCurrentLabel("All")
            Log.d(TAG, "No specific label detected, defaulting to: $currentLabel")
        }
    }

    private fun findChatListScrollableNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun searchScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable && node.className?.contains("RecyclerView") == true) {
                return node
            }
            val chatListIds = listOf(
                "${rootNode.packageName}:id/chat_list",
                "${rootNode.packageName}:id/conversations"
            )
            for (id in chatListIds) {
                val nodes = node.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty() && nodes.first().isScrollable) return nodes.first()
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

    private fun findAnyScrollableNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun searchAnyScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = searchAnyScrollable(child)
                if (result != null) return result
                child.recycle()
            }
            return null
        }
        return searchAnyScrollable(rootNode)
    }

    private fun performScroll(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        Log.d(TAG, "Scroll attempt result: $result")
        return result
    }

    private fun findChatEntries(node: AccessibilityNodeInfo, entries: MutableList<String>, allTextNodes: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.isNotBlank() && !text.contains("\n") && text.length > 1) {
            Log.d(TAG, "Found text node: '$text' (viewId: ${node.viewIdResourceName})")
            allTextNodes.add(text)
            if (node.isVisibleToUser && isValidContact(text)) { // Relaxed to visible nodes
                if (!entries.contains(text)) {
                    Log.d(TAG, "Found valid contact: '$text'")
                    entries.add(text)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findChatEntries(child, entries, allTextNodes)
                child.recycle()
            }
        }
    }

    private fun isValidContact(text: String): Boolean {
        val invalidKeywords = listOf(
            "Status", "My status", "Recent updates", "Viewed updates", "Channels",
            "All", "Unread", "Favourites", "Chats", "Groups", "items", "Video"
        )
        val isTimestamp = text.matches(Regex("\\d{1,2}:\\d{2}\\s*(am|pm)?")) ||
                          text.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{4}")) ||
                          text.matches(Regex("\\d+:\\d{2}"))
        val isEmojiOnly = text.matches(Regex("[\\p{So}\\s]+"))
        val isPhone = isPhoneNumber(text)

        val isValid = (!invalidKeywords.any { text.contains(it) } && !isTimestamp && !isEmojiOnly) || isPhone
        if (!isValid) Log.d(TAG, "Text '$text' rejected as invalid contact")
        return isValid
    }

    private fun isPhoneNumber(text: String): Boolean {
        val phoneRegex = Regex("\\+?[1-9]\\d{6,14}")
        val cleanedText = text.trim().replace(" ", "").replace("-", "").replace(".", "")
        return phoneRegex.matches(cleanedText)
    }

    private fun sendChatEntries(entries: List<String>) {
        if (channel == null) {
            Log.e(TAG, "MethodChannel is null, cannot send entries")
            return
        }
        entries.forEach { entry ->
            val data = mapOf(
                "text" to entry,
                "eventType" to "AutoScan",
                "timestamp" to System.currentTimeMillis().toString(),
                "source" to "ChatList",
                "label" to currentLabel
            )
            Log.d(TAG, "Sending entry: $data")
            handler.post {
                try {
                    channel?.invokeMethod("onUIEvent", Gson().toJson(data))
                    Log.d(TAG, "Entry sent successfully: $entry")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending to Flutter: ${e.message}", e)
                }
            }
        }
    }

    private fun sendFinalChatEntries(entries: List<String>) {
        sendChatEntries(entries.distinct())
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        isScanning = false
    }
}