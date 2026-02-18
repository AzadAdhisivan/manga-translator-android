package com.example.mangatranslator

import android.app.Service
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import android.app.Activity
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.graphics.Bitmap
import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException





class OverlayService : Service() {

    private val client = OkHttpClient()
    private val baseUrl = "http://10.208.162.109:8000"


    private var projectionCallback: MediaProjection.Callback? = null


    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0


    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var selectionView: SelectionOverlayView? = null
    private var translationView: TranslationOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    // ─────────────────────────────────────────────
    // Floating bubble
    // ─────────────────────────────────────────────
    private fun showBubble() {
        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setPadding(24, 24, 24, 24)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(bubble, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(event.rawX - touchX)
                    val dy = kotlin.math.abs(event.rawY - touchY)

                    // Treat it as a click only if you didn't really move
                    if (dx < 10 && dy < 10) {
                        onTranslateClicked()
                    }
                    true
                }


                else -> false
            }
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun showDebugImage(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            setBackgroundColor(0xAA000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(imageView, params)

        // Tap to dismiss debug image
        imageView.setOnClickListener {
            try { windowManager.removeView(imageView) } catch (_: Exception) {}
        }
    }


    // ─────────────────────────────────────────────
    // Selection overlay (drag rectangle)
    // ─────────────────────────────────────────────
    private fun startSelectionMode() {
        bubbleView?.apply {
            visibility = android.view.View.GONE
            isEnabled = false
        }


        val view = SelectionOverlayView(this) { rect: RectF ->
            // capture overlay size BEFORE removing it
            val viewW = selectionView?.width ?: resources.displayMetrics.widthPixels
            val viewH = selectionView?.height ?: resources.displayMetrics.heightPixels

            stopSelectionMode()
            handleSelection(rect, viewW, viewH)
        }



        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        selectionView = view
        windowManager.addView(view, params)
    }

    private fun handleSelection(rect: RectF, viewW: Int, viewH: Int) {
        captureScreenshot { fullBitmap ->
            val scaleX = fullBitmap.width.toFloat() / viewW.toFloat()
            val scaleY = fullBitmap.height.toFloat() / viewH.toFloat()

            val mapped = RectF(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY
            )

            val cropped = cropBitmap(fullBitmap, mapped)

            uploadCropToOcr(cropped) { jpText ->
                // Use original rect for where to display overlay on screen
                showTranslationOverlay(rect, jpText)
            }
        }
    }


    private fun uploadCropToOcr(cropped: Bitmap, onDone: (String) -> Unit) {
        val stream = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()

        val fileBody = bytes.toRequestBody("image/png".toMediaType())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "crop.png", fileBody)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/ocr")
            .post(multipart)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Handler(Looper.getMainLooper()).post {
                    onDone("❌ OCR failed: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()

                val resultText: String = try {
                    val obj = JSONObject(body)
                    val en = obj.optString("en_text", "")
                    if (response.isSuccessful && en.isNotBlank()) en
                    else {
                        val msg = obj.optString("error", "")
                        if (msg.isNotBlank()) "❌ Server error: $msg"
                        else "⚠️ No en_text returned (HTTP ${response.code})"
                    }

                } catch (_: Exception) {
                    if (response.isSuccessful) "⚠️ Server returned non-JSON (unexpected)"
                    else "❌ OCR failed (HTTP ${response.code})"
                }

                Handler(Looper.getMainLooper()).post {
                    onDone(resultText)
                }
            }

        })
    }


    private fun startCaptureForeground() {
        val channelId = "capture_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notif: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("Manga Translator running")
                    .setContentText("Screen capture enabled")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Manga Translator running")
                    .setContentText("Screen capture enabled")
                    .setSmallIcon(android.R.drawable.ic_menu_search)
                    .build()
            }

        startForeground(1001, notif)
    }

    private fun registerProjectionCallback() {
        if (projectionCallback != null) return

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // MediaProjection stopped by system/user → release everything safely
                Handler(Looper.getMainLooper()).post {
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    virtualDisplay = null

                    try { imageReader?.close() } catch (_: Exception) {}
                    imageReader = null

                    val cb = projectionCallback
                    if (cb != null) {
                        try { mediaProjection?.unregisterCallback(cb) } catch (_: Exception) {}
                    }
                    projectionCallback = null


                    try { mediaProjection?.stop() } catch (_: Exception) {}
                    mediaProjection = null

                    // optional: remove bubble/overlays if you want
                    // stopSelf()
                }
            }
        }

        projectionCallback?.let { cb ->
            mediaProjection?.registerCallback(cb, Handler(Looper.getMainLooper()))
        }

    }



    private fun onTranslateClicked() {
        startSelectionMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (mediaProjection == null && resultCode == Activity.RESULT_OK && data != null) {
            startCaptureForeground()
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)

// ✅ MUST do this before creating virtual display
            registerProjectionCallback()

            val metrics: DisplayMetrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            setupImageReader()

        }

        return START_STICKY
    }

    private fun setupImageReader() {
        imageReader?.close()
        virtualDisplay?.release()

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureScreenshot(onReady: (Bitmap) -> Unit) {
        val reader = imageReader ?: run {
            android.widget.Toast.makeText(this, "No screen permission yet", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val image = reader.acquireLatestImage() ?: return@postDelayed

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val padded = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            image.close()

            val finalBitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
            padded.recycle()

            onReady(finalBitmap)
        }, 80)
    }






    private fun stopSelectionMode() {
        // Remove the translucent selection overlay
        selectionView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {}
        }
        selectionView = null

        // Bring the bubble back
        bubbleView?.apply {
            visibility = android.view.View.VISIBLE
            isEnabled = true
        }
    }

    private fun cropBitmap(full: android.graphics.Bitmap, rect: RectF): android.graphics.Bitmap {
        val left = rect.left.toInt().coerceIn(0, full.width - 1)
        val top = rect.top.toInt().coerceIn(0, full.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, full.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, full.height)

        val w = right - left
        val h = bottom - top
        return android.graphics.Bitmap.createBitmap(full, left, top, w, h)
    }





    // ─────────────────────────────────────────────
    // Translation overlay (soft off-white card)
    // ─────────────────────────────────────────────
    private fun showTranslationOverlay (rect: RectF, text: String = "English translation will appear here.") {
        translationView?.let { windowManager.removeView(it) }
        translationView = null

        val overlay = TranslationOverlayView(this, rect, text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        translationView = overlay
        windowManager.addView(overlay, params)

        // Tap overlay to dismiss
        overlay.setOnClickListener {
            translationView?.let { windowManager.removeView(it) }
            translationView = null
        }
    }

    // ─────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()

        selectionView?.let { windowManager.removeView(it) }
        selectionView = null

        translationView?.let { windowManager.removeView(it) }
        translationView = null

        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        projectionCallback?.let { cb ->
            try { mediaProjection?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        projectionCallback = null


    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null
}
