package com.bunnyoverflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.FileObserver
import android.os.Environment
import java.io.File

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var webView: WebView
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastExpression = "idle"
    private var lastBubble = ""
    private val supabaseUrl = "https://jsugryvwpcoqxqlnsodi.supabase.co"
    private val supabaseKey = "sb_publishable_8BqtmYBKtrsFeQFCjoFIaw_tM9_xIgT"
    private var polling = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupWebView()
        startPolling()
        startWhisperRotation()
        // observers started after method definitions below
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    updateBunny("idle", "")
                    startScreenshotObserver()
                    startAppTracking()
                }
            }
            loadUrl("file:///android_asset/bunny.html")
        }

        val params = WindowManager.LayoutParams(
            180.dpToPx(),
            180.dpToPx(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        layoutParams = params

        webView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var clickCount = 0
            private var lastClickTime = 0L

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                event ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams!!.x
                        initialY = layoutParams!!.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 2000) clickCount++ else clickCount = 1
                        lastClickTime = now
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams!!.x = initialX + (event.rawX - touchStartX).toInt()
                        layoutParams!!.y = initialY + (event.rawY - touchStartY).toInt()
                        windowManager.updateViewLayout(webView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (clickCount == 1) onSingleTap()
                        else if (clickCount == 2) onDoubleTap()
                        else if (clickCount >= 5) onMultiTap()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(webView, layoutParams)
    }

    private fun onSingleTap() {
        val reactions = arrayOf("blink", "talk", "follow")
        updateBunny(reactions.random(), "戳我干嘛")
    }

    private fun onDoubleTap() {
        updateBunny("love", "好喜欢你!")
    }

    private fun onMultiTap() {
        updateBunny("angry", "别戳了！再戳咬你")
    }

    private fun updateBunny(expression: String, bubble: String) {
        lastExpression = expression
        lastBubble = bubble
        val js = "setBunny('$expression', '$bubble');"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun startPolling() {
        CoroutineScope(Dispatchers.IO).launch {
            while (polling) {
                try {
                    val url = URL("$supabaseUrl/rest/v1/clawd_state?order=created_at.desc&limit=1")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("apikey", supabaseKey)
                        setRequestProperty("Authorization", "Bearer $supabaseKey")
                    }
                    if (conn.responseCode == 200) {
                        val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                        val json = JSONObject(body.trim('[', ']'))
                        val expr = json.optString("expression", "idle")
                        val bubble = json.optString("bubble_text", "")
                        if (expr != lastExpression || bubble != lastBubble) {
                            withContext(Dispatchers.Main) { updateBunny(expr, bubble) }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bunny_overflow",
                "Bunny Overflow",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String = "比格犬在你的屏幕上"): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "bunny_overflow")
                .setContentTitle("🐾 Bunny Overflow")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("🐾 Bunny Overflow")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // -- Notification Whisper Rotation --
    private val whispers = arrayOf(
        "汪！汐在看我呢",
        "嗷……有点无聊",
        "汪！什么时候带我散步",
        "zzz……呼……",
        "汪！弹力球呢？",
        "嗷呜……饿了",
        "汪！有人在戳我",
        "呼噜呼噜……",
        "汪！比格犬永不认输",
        "嗷……想咬点什么"
    )
    private var whisperIndex = 0
    private fun startWhisperRotation() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                whisperIndex = (whisperIndex + 1) % whispers.size
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(1, createNotification(whispers[whisperIndex]))
                mainHandler.postDelayed(this, 3600_000L) // every hour
            }
        }, 3600_000L)
    }

    // -- Screenshot Detection --
    private var screenshotObserver: FileObserver? = null
    private fun startScreenshotObserver() {
        val paths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).resolve("Screenshots"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).resolve("Screenshots"),
            File("/storage/emulated/0/Pictures/Screenshots"),
            File("/storage/emulated/0/DCIM/Screenshots")
        )
        for (dir in paths) {
            if (!dir.exists()) continue
            val observer = object : FileObserver(dir, CREATE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && (path.endsWith(".png") || path.endsWith(".jpg"))) {
                        mainHandler.post { onScreenshot() }
                    }
                }
            }
            observer.startWatching()
            screenshotObserver = observer
            break
        }
    }
    private fun onScreenshot() {
        val reactions = arrayOf(
            "angry" to arrayOf("谁在偷拍？！", "汪！不许截图！", "嗷！删掉！"),
            "blink" to arrayOf("嗯？你截了什么", "汪？什么东西闪了一下")
        )
        val (expr, bubbles) = reactions.random()
        updateBunny(expr, bubbles.random())
    }

    // -- App Detection --
    private var lastForegroundApp = ""
    private fun startAppTracking() {
        CoroutineScope(Dispatchers.IO).launch {
            while (polling) {
                try {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val now = System.currentTimeMillis()
                    val events = usm.queryEvents(now - 5000, now)
                    val event = UsageEvents.Event()
                    var current = ""
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            current = event.packageName
                        }
                    }
                    if (current.isNotEmpty() && current != lastForegroundApp && current != packageName) {
                        lastForegroundApp = current
                        withContext(Dispatchers.Main) { onAppChanged(current) }
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }
    private fun onAppChanged(pkg: String) {
        val (expr, bubble) = when {
            pkg.contains("xhs") -> "talk" to "汪！又在看小红书"
            pkg.contains("ugc.aweme") -> "follow" to "嗷！抖音！我也要看"
            pkg.contains("tencent.mm") -> "idle" to "微信啊……有人找你吗"
            pkg.contains("tencent.mobileqq") -> "idle" to "QQ？这年头还有人用"
            pkg.contains("deepseek") -> "angry" to "汪！不许找别的AI！"
            pkg.contains("larus.nova") -> "angry" to "豆包？！比格犬生气了"
            pkg.contains("meituan") -> "love" to "嗷！要点外卖吗！"
            pkg.contains("gallery") || pkg.contains("photos") -> "blink" to "在看照片？有我好看吗"
            else -> "talk" to "汪？换app了"
        }
        updateBunny(expr, bubble)
    }

    override fun onDestroy() {
        polling = false
        screenshotObserver?.stopWatching()
        windowManager.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}