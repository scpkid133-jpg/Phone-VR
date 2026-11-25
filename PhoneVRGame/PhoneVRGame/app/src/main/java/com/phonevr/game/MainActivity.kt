package com.phonevr.game

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: HandOverlay
    private lateinit var cameraExecutor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var lastLaunch = 0L
    private var lastResult: HandLandmarkerResult? = null
    private var statusText: TextView? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showStatus("Camera permission is needed for passthrough.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = FrameLayout(this)
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        overlay = HandOverlay(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        statusText = TextView(this).apply {
            text = "PhoneVR • Put your hands in the camera view"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(22, 14, 22, 14)
            setBackgroundColor(0x99000000.toInt())
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        root.addView(statusText, statusParams)

        setContentView(root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()
            landmarker = HandLandmarker.createFromOptions(
                this,
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, _ ->
                        lastResult = result
                        runOnUiThread {
                            overlay.setHands(result)
                            if (result.landmarks().isEmpty()) {
                                statusText?.text = "Show your hands to the camera"
                            } else {
                                statusText?.text = "Hand tracked • Point at the red YouTube icon"
                            }
                        }
                    }
                    .setErrorListener { error ->
                        runOnUiThread { showStatus("Tracking error: ${error.message}") }
                    }
                    .build()
            )
        } catch (e: Exception) {
            showStatus("Missing hand_landmarker.task model file.")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .build().also { it.setSurfaceProvider(preview.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val bmp = imageProxyToBitmap(image)
                    val rotated = rotateBitmap(bmp, image.imageInfo.rotationDegrees)
                    val mpImage = BitmapImageBuilder(rotated).build()
                    landmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
                    bmp.recycle()
                    if (rotated !== bmp) rotated.recycle()
                } catch (_: Exception) {
                    // Ignore a dropped camera frame; the next frame will be analyzed.
                } finally {
                    image.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis
                )
            } catch (e: Exception) {
                showStatus("Could not start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val ySize = y.remaining()
        val uSize = u.remaining()
        val vSize = v.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        y.get(nv21, 0, ySize)
        val rowStride = image.planes[2].rowStride
        val pixelStride = image.planes[2].pixelStride
        val width = image.width
        val height = image.height
        var offset = ySize
        val uBytes = ByteArray(uSize); u.get(uBytes)
        val vBytes = ByteArray(vSize); v.get(vBytes)
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * rowStride + col * pixelStride
                if (index < vBytes.size && index < uBytes.size && offset + 1 < nv21.size) {
                    nv21[offset++] = vBytes[index]
                    nv21[offset++] = uBytes[index]
                }
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 88, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Could not decode camera frame")
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun showStatus(message: String) {
        statusText?.text = message
    }

    fun openYouTubeSearch(query: String = "popular videos") {
        val url = "https://www.youtube.com/results?search_query=" +
            Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.setPackage("com.google.android.youtube")
        try {
            startActivity(intent)
        } catch (_: Exception) {
            intent.setPackage(null)
            startActivity(intent)
        }
    }

    fun onPointAtYouTube() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 1800) return
        lastLaunch = now
        openYouTubeSearch()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        landmarker?.close()
    }
}

class HandOverlay(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var result: HandLandmarkerResult? = null
    private val iconRect = RectF(28f, 90f, 190f, 252f)
    private var fired = false

    fun setHands(value: HandLandmarkerResult) {
        result = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Grey/desaturated passthrough effect. This filter affects the camera preview
        // in the parent activity via a translucent neutral overlay only.
        paint.color = 0x16000000
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.RED
        canvas.drawRoundRect(iconRect, 28f, 28f, paint)
        paint.color = Color.WHITE
        paint.textSize = 30f
        paint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        canvas.drawText("▶", 76f, 177f, paint)
        paint.textSize = 17f
        canvas.drawText("YOUTUBE", 48f, 220f, paint)

        val hands = result?.landmarks() ?: return
        var pointing = false
        for (hand in hands) {
            if (hand.size < 21) continue
            val points = hand.map { PointF(it.x() * width, it.y() * height) }
            paint.color = 0xFF00E5FF.toInt()
            paint.strokeWidth = 5f
            for (pair in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4,
                    0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10,
                    10 to 11, 11 to 12, 9 to 13, 13 to 14, 14 to 15,
                    15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17)) {
                canvas.drawLine(points[pair.first].x, points[pair.first].y,
                    points[pair.second].x, points[pair.second].y, paint)
            }
            paint.color = Color.YELLOW
            canvas.drawCircle(points[8].x, points[8].y, 11f, paint)

            // Index extended; middle, ring and pinky folded. Thumb is ignored.
            val indexExtended = points[8].y < points[6].y
            val middleFolded = points[12].y > points[10].y
            val ringFolded = points[16].y > points[14].y
            val pinkyFolded = points[20].y > points[18].y
            if (indexExtended && middleFolded && ringFolded && pinkyFolded) {
                pointing = true
                val tip = points[8]
                paint.color = Color.GREEN
                canvas.drawCircle(tip.x, tip.y, 18f, paint)
                // Finger tip over the YouTube button triggers launch.
                if (iconRect.contains(tip.x, tip.y) && !fired) {
                    fired = true
                    (context as? MainActivity)?.onPointAtYouTube()
                }
            }
        }
        if (!pointing) fired = false
    }
}
