package com.masoud.glasscounter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var countView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var aim: AimOverlay
    private lateinit var sensorManager: SensorManager
    private var imageCapture: ImageCapture? = null
    private var cameraStarted = false
    private var stableSince = 0L
    private var lastCapture = 0L
    private var stable = false
    private val samples = ArrayDeque<FloatArray>()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("glass_counter", Context.MODE_PRIVATE) }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showMessage("برای شمارش با دوربین، مجوز دوربین لازم است.")
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) showResult(bitmap) else showMessage("خواندن عکس ممکن نشد.")
            } catch (e: Exception) { showMessage("خطا در باز کردن عکس: ${e.localizedMessage}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        showHome()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun basePage(titleText: String): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 26, 22, 22)
            setBackgroundResource(R.drawable.app_background)
        }
        val title = TextView(this).apply {
            text = titleText; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(8, 10, 8, 18)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        return root
    }

    private fun addButton(label: String, action: () -> Unit) {
        val b = Button(this).apply { text = label; textSize = 16f; setOnClickListener { action() } }
        root.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
    }

    private fun addText(text: String, size: Float = 16f) {
        root.addView(TextView(this).apply {
            this.text = text; textSize = size; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(8, 8, 8, 14)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showHome() {
        basePage("شمارشگر شیشه\nMASOUD HOSEINI")
        addText("سامانه کمکی تشخیص و شمارش جام‌های شیشه‌ای", 16f)
        addButton("شروع شمارش با دوربین") { openCamera() }
        addButton("شمارش از روی عکس گالری") { gallery.launch("image/*") }
        addButton("آخرین نتایج") { showHistory() }
        addButton("تنظیمات شمارش") { showSettings() }
        addButton("ارتباط با ما") { showContact() }
        addText("نسخه آزمایشی — نتیجه باید با شمارش واقعی کنترل شود.", 12f)
    }

    private fun openCamera() {
        basePage("دوربین و نشانگر هدف")
        status = TextView(this).apply {
            text = "گوشی را آرام حرکت بدهید و نشانگر را روی بخش موردنظر قرار دهید."
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(6, 8, 6, 8)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        aim = AimOverlay(this)
        val cameraFrame = FrameLayout(this)
        cameraFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(aim, FrameLayout.LayoutParams(-1, -1))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(cameraFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        countView = TextView(this).apply {
            text = "شمارش پس از ثبت تصویر نمایش داده می‌شود."
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.GREEN); setPadding(8, 12, 8, 12)
        }
        root.addView(countView, LinearLayout.LayoutParams(-1, -2))
        addButton("ثبت عکس دستی") { takePhoto() }
        addButton("بازگشت به منو") { showHome() }
        cameraStarted = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                cameraStarted = true
                status.text = "نشانگر وسط تصویر است. با ثابت ماندن گوشی، عکس خودکار ثبت می‌شود."
            } catch (e: Exception) { status.text = "خطای دوربین: ${e.localizedMessage}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || !::aim.isInitialized || !cameraStarted) return
        val v = floatArrayOf(event.values[0], event.values[1], event.values[2])
        samples.addLast(v)
        while (samples.size > 18) samples.removeFirst()
        if (samples.size < 15) return
        val means = FloatArray(3)
        samples.forEach { for (i in 0..2) means[i] += it[i] / samples.size }
        var variance = 0f
        samples.forEach { for (i in 0..2) variance += (it[i] - means[i]) * (it[i] - means[i]) / samples.size }
        val gravity = sqrt(means.sumOf { (it * it).toDouble() }).toFloat()
        val now = System.currentTimeMillis()
        stable = variance < 0.055f && gravity in 8.4f..11.2f
        aim.setStable(stable)
        if (stable) {
            if (stableSince == 0L) stableSince = now
            val elapsed = now - stableSince
            if (elapsed >= 1800 && now - lastCapture > 5000) {
                lastCapture = now
                stableSince = 0L
                handler.post { if (cameraStarted) takePhoto() }
            } else {
                status.text = "گوشی ثابت است؛ ثبت خودکار تا ${((1800 - elapsed).coerceAtLeast(0) / 1000f)} ثانیه دیگر."
            }
        } else {
            stableSince = 0L
            status.text = "حرکت آرام؛ وقتی گوشی ثابت شود نشانگر سبز و عکس خودکار ثبت می‌شود."
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
                    if (bitmap != null) showResult(bitmap) else status.text = "خواندن عکس ناموفق بود."
                }
                override fun onError(exception: ImageCaptureException) { status.text = "خطا در عکس‌برداری: ${exception.message}" }
            }
        )
    }

    private fun showResult(bitmap: Bitmap) {
        val estimate = GlassEstimator.estimate(bitmap)
        val old = prefs.getString("history", "") ?: ""
        val entry = "${java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} — تخمین: $estimate"
        prefs.edit().putString("history", (entry + "\n" + old).take(4000)).apply()
        basePage("نتیجه تحلیل تصویر")
        addText("تعداد تخمینی لبه/جام: $estimate", 23f)
        addText("این عدد شمارش قطعی جام‌ها نیست. انعکاس، شیارهای سطح و جام‌های عقب‌وجلو ممکن است خطا ایجاد کنند.", 15f)
        addText("برای نتیجه معتبر، تصویر باید در شرایط نوری و زاویه‌های مختلف با تعداد واقعی برچسب‌گذاری و الگوریتم اختصاصی آموزش داده شود.", 13f)
        addButton("شمارش دوباره با دوربین") { openCamera() }
        addButton("انتخاب عکس دیگر") { gallery.launch("image/*") }
        addButton("بازگشت به منو") { showHome() }
    }

    private fun showHistory() {
        basePage("سوابق شمارش")
        addText(prefs.getString("history", "هنوز نتیجه‌ای ثبت نشده است.") ?: "هنوز نتیجه‌ای ثبت نشده است.", 14f)
        addButton("پاک کردن سوابق") { prefs.edit().remove("history").apply(); showHistory() }
        addButton("بازگشت") { showHome() }
    }

    private fun showContact() {
        basePage("ارتباط با ما")
        addText("طراح و توسعه‌دهنده\nمهندس مسعود حسینی", 21f)
        addText("واحد بازرسی شیشه\nکارخانه شیشه فلوت کاویان\nگروه صنعتی کاوه")
        addText("همراه: 09152191509\nهمراه: 09352705301\nایمیل: masoudhoseini.9565@gmail.com", 16f)
        addButton("ارسال ایمیل") {
            val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                data = android.net.Uri.parse("mailto:masoudhoseini.9565@gmail.com")
                putExtra(android.content.Intent.EXTRA_SUBJECT, "شمارشگر شیشه")
            }
            try { startActivity(intent) } catch (_: Exception) { showMessage("برنامه ایمیل در گوشی پیدا نشد.") }
        }
        addButton("بازگشت") { showHome() }
    }

    private fun showSettings() {
        basePage("تنظیمات")
        addText("ثبت خودکار پس از حدود ۱٫۸ ثانیه ثبات حسگر حرکتی انجام می‌شود. نشانگر سبز یعنی حرکت گوشی کم است؛ این نشانگر تشخیص محل جام نیست.")
        addText("حساسیت تشخیص جام در این نسخه هنوز قابل کالیبره‌کردن صنعتی نیست.")
        addButton("بازگشت") { showHome() }
    }

    private fun showMessage(message: String) {
        android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("باشه", null).show()
    }
}

private class AimOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
    private var steady = false
    fun setStable(value: Boolean) { steady = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (steady) Color.GREEN else Color.YELLOW
        val x = width / 2f; val y = height / 2f; val r = 34f
        canvas.drawCircle(x, y, r, paint)
        canvas.drawLine(x - 58, y, x - 15, y, paint)
        canvas.drawLine(x + 15, y, x + 58, y, paint)
        canvas.drawLine(x, y - 58, x, y - 15, paint)
        canvas.drawLine(x, y + 15, x, y + 58, paint)
    }
}

private object GlassEstimator {
    // Experimental multi-band vertical-edge detector. A line must remain visible
    // across several image bands to reduce errors from isolated reflections.
    fun estimate(bitmap: Bitmap): Int {
        val w0 = bitmap.width
        val h0 = bitmap.height
        if (w0 < 40 || h0 < 80) return 0
        val w = minOf(800, w0)
        val h = maxOf(120, (h0 * w / w0))
        val image = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val bands = 5
        val band = Array(bands) { FloatArray(w) }
        val y0 = h / 12
        val y1 = h * 11 / 12
        for (b in 0 until bands) {
            val top = y0 + (y1 - y0) * b / bands
            val bottom = y0 + (y1 - y0) * (b + 1) / bands
            val step = maxOf(2, (bottom - top) / 160)
            for (x in 2 until w - 2) {
                var total = 0f
                var n = 0
                var y = top
                while (y < bottom) {
                    val p = image.getPixel(x - 1, y)
                    val q = image.getPixel(x + 1, y)
                    val lp = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000f
                    val lq = (Color.red(q) * 299 + Color.green(q) * 587 + Color.blue(q) * 114) / 1000f
                    total += kotlin.math.abs(lq - lp)
                    n++
                    y += step
                }
                band[b][x] = if (n == 0) 0f else total / n
            }
        }
        val profile = FloatArray(w)
        for (x in 3 until w - 3) {
            val v = FloatArray(bands) { band[it][x] }.sorted()
            profile[x] = (v[2] + band.indices.sumOf { 0.0 }.toFloat()) / 1f
            profile[x] = v[2]
        }
        val smooth = FloatArray(w)
        for (x in 3 until w - 3) {
            smooth[x] = (profile[x-2] + 2f*profile[x-1] + 3f*profile[x] + 2f*profile[x+1] + profile[x+2]) / 9f
        }
        val values = smooth.drop(3).dropLast(3).sorted()
        if (values.isEmpty()) { image.recycle(); return 0 }
        val median = values[values.size / 2]
        val dev = values.map { kotlin.math.abs(it - median) }.sorted()
        val threshold = maxOf(median + dev[dev.size / 2] * 1.25f, values[values.size * 65 / 100])
        val minGap = maxOf(3, w / 220)
        val candidates = (4 until w - 4).filter {
            smooth[it] >= threshold && smooth[it] >= smooth[it-1] && smooth[it] > smooth[it+1]
        }.sortedByDescending { smooth[it] }
        val chosen = ArrayList<Int>()
        for (x in candidates) if (chosen.none { kotlin.math.abs(it - x) < minGap }) chosen.add(x)
        image.recycle()
        return chosen.size.coerceAtMost(200)
    }
}
