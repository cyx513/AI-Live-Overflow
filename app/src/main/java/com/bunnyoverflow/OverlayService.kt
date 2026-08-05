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

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "bunny_overflow")
                .setContentTitle("🐾 Bunny Overflow")
                .setContentText("比格犬在你的屏幕上")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("🐾 Bunny Overflow")
                .setContentText("比格犬在你的屏幕上")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        polling = false
        windowManager.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}