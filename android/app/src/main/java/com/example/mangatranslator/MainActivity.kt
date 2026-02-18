package com.example.mangatranslator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.mangatranslator.ui.theme.MangaTranslatorTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat



class MainActivity : ComponentActivity() {

    private val client = OkHttpClient()

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", result.data)
                }
                ContextCompat.startForegroundService(this, intent)
                setResultText?.invoke("🫧 Overlay + screen capture enabled (go Home to see bubble)")
            } else {
                setResultText?.invoke("⚠️ Screen permission denied")
            }
        }

    // ✅ Use your CURRENT hotspot / PC IPv4 here
    private val baseUrl = "http://10.208.162.109:8000"

    private var setResultText: ((String) -> Unit)? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // ok
            }

            uploadToOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager


        setContent {
            MangaTranslatorTheme {
                MainScreen(
                    onBindResultSetter = { setter -> setResultText = setter },

                    onPing = { pingBackend() },
                    onPickImage = { pickImage.launch(arrayOf("image/*")) },

                    onRequestOverlayPermission = { requestOverlayPermission() },
                    canDrawOverlays = { canDrawOverlays() },
                    onStartOverlay = { startOverlay() },
                    onStopOverlay = { stopOverlay() }
                )
            }
        }
    }

    private fun pingBackend() {
        val request = Request.Builder().url("$baseUrl/").get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { setResultText?.invoke("❌ Ping failed: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                runOnUiThread { setResultText?.invoke("✅ Ping ${response.code}: $body") }
            }
        })
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startOverlay() {
        // Ask for screen capture permission first
        val captureIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }


    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        setResultText?.invoke("🧹 Overlay stopped")
    }

    private fun uploadToOcr(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }

            val fileBody = bytes.toRequestBody("image/*".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "image.jpg", fileBody)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/ocr")
                .post(multipart)
                .build()

            runOnUiThread { setResultText?.invoke("⏳ Uploading + OCR...") }

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { setResultText?.invoke("❌ OCR failed: ${e.message}") }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        runOnUiThread { setResultText?.invoke("❌ OCR ${response.code}: $body") }
                        return
                    }

                    val jpText = try {
                        JSONObject(body).optString("jp_text")
                    } catch (_: Exception) {
                        ""
                    }

                    runOnUiThread {
                        setResultText?.invoke(
                            if (jpText.isNotBlank()) "✅ JP Text:\n$jpText"
                            else "⚠️ Couldn't parse jp_text:\n$body"
                        )
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { setResultText?.invoke("❌ Could not read image: ${e.message}") }
        }
    }
}

@Composable
fun MainScreen(
    onBindResultSetter: ((String) -> Unit) -> Unit,

    onPing: () -> Unit,
    onPickImage: () -> Unit,

    onRequestOverlayPermission: () -> Unit,
    canDrawOverlays: () -> Boolean,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit
) {
    var result by remember { mutableStateOf("Ready.") }

    LaunchedEffect(Unit) { onBindResultSetter { result = it } }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Button(onClick = onRequestOverlayPermission) {
                Text("Enable Overlay Permission")
            }

            Spacer(Modifier.height(10.dp))

            Button(onClick = {
                if (canDrawOverlays()) onStartOverlay()
                else result = "⚠️ Overlay permission not granted yet"
            }) {
                Text("Start Overlay")
            }

            Spacer(Modifier.height(10.dp))

            Button(onClick = onStopOverlay) {
                Text("Stop Overlay")
            }

            Spacer(Modifier.height(18.dp))

            Button(onClick = onPing) { Text("Ping Backend") }

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = onPickImage) { Text("Pick Image & OCR") }

            Spacer(modifier = Modifier.height(18.dp))

            Text(result)
        }
    }
}
