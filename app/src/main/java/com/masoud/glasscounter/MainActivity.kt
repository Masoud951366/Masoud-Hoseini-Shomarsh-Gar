package com.masoud.glasscounter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var countView: TextView
    private var imageCapture: ImageCapture? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "برای استفاده، اجازه دوربین را فعال کنید."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "شمارشگر شیشه"
            textSize = 25f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "دوربین را روبه‌روی ردیف شیشه‌ها بگیرید."
            textSize = 15f
            setTextColor(0xFFE0E7EF.toInt())
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        countView = TextView(this).apply {
            text = "تعداد تخمینی: —"
            textSize = 23f
            setTextColor(0xFF7CFFB2.toInt())
            gravity = Gravity.CENTER
            setPadding(8, 16, 8, 16)
        }
        val capture = Button(this).apply {
            text = "عکس بگیر و شمارش آزمایشی کن"
            textSize = 17f
            setOnClickListener { takePhoto() }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(countView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(capture, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                status.text = "آماده؛ گوشی را روبه‌روی شیشه‌ها ثابت نگه دارید."
            } catch (e: Exception) {
                status.text = "خطا در راه‌اندازی دوربین: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "count-shot.jpg")
        status.text = "در حال ثبت تصویر…"
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap == null) {
                        status.text = "خواندن تصویر ناموفق بود."
                        return
                    }
                    val estimate = GlassEstimator.estimate(bitmap)
                    countView.text = "تعداد تخمینی: $estimate"
                    status.text = "این نسخه آزمایشی است؛ نتیجه را با شمارش دستی بررسی کنید."
                }
                override fun onError(exception: ImageCaptureException) {
                    status.text = "خطا در عکس‌برداری: ${exception.message}"
                }
            }
        )
    }
}

/**
 * Conservative visual estimate based on repeated vertical brightness transitions.
 * It is a prototype heuristic, not a trained glass detector.
 */
private object GlassEstimator {
    fun estimate(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 20 || h < 20) return 0
        val xStep = max(1, w / 700)
        val yStart = h / 5
        val yEnd = h * 4 / 5
        val profile = IntArray(w)
        var x = 1
        while (x < w - 1) {
            var score = 0L
            var samples = 0
            var y = yStart
            while (y < yEnd) {
                val left = bitmap.getPixel(x - 1, y)
                val right = bitmap.getPixel(x + 1, y)
                val l = (android.graphics.Color.red(left) * 299 + android.graphics.Color.green(left) * 587 + android.graphics.Color.blue(left) * 114) / 1000
                val r = (android.graphics.Color.red(right) * 299 + android.graphics.Color.green(right) * 587 + android.graphics.Color.blue(right) * 114) / 1000
                score += abs(r - l)
                samples++
                y += max(2, h / 180)
            }
            profile[x] = if (samples == 0) 0 else (score / samples).toInt()
            x += xStep
        }
        val smoothed = IntArray(w)
        for (i in 2 until w - 2) smoothed[i] = (profile[i-2] + profile[i-1] + profile[i] + profile[i+1] + profile[i+2]) / 5
        val sorted = smoothed.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return 0
        val threshold = max(18, sorted[sorted.size * 3 / 4] * 2 / 3)
        val peaks = ArrayList<Int>()
        for (i in 3 until w - 3) {
            if (smoothed[i] >= threshold && smoothed[i] >= smoothed[i-1] && smoothed[i] > smoothed[i+1] &&
                (peaks.isEmpty() || i - peaks.last() > w / 80)) peaks.add(i)
        }
        // A glass commonly contributes two prominent side edges; report paired edges.
        return ((peaks.size + 1) / 2).coerceIn(0, 250)
    }
}
