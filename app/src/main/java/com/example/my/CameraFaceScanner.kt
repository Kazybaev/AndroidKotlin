package com.example.my

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.my.ui.theme.AuraCyan
import com.example.my.ui.theme.AuraMint
import com.example.my.ui.theme.AuraSurface
import com.example.my.ui.theme.AuraSurfaceHigh
import com.example.my.ui.theme.AuraText
import com.example.my.ui.theme.AuraTextMuted
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

internal class CameraFaceScanner(
    context: Context,
    private val onFaceDetected: (Int?) -> Unit
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.18f)
            .enableTracking()
            .build()
    )
    private val isAnalyzing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastReportedTrackingId: Int? = -1

    @androidx.camera.core.ExperimentalGetImage
    fun bind(previewView: PreviewView?, lifecycleOwner: androidx.lifecycle.LifecycleOwner, context: Context) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            val preview = previewView?.let {
                Preview.Builder().build().also { createdPreview ->
                    createdPreview.setSurfaceProvider(it.surfaceProvider)
                }
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                        if (!isAnalyzing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            isAnalyzing.set(false)
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        detector.process(image)
                            .addOnSuccessListener(mainExecutor) { faces ->
                                val face = faces.firstOrNull()
                                val trackingId = face?.trackingId
                                if (trackingId != lastReportedTrackingId) {
                                    lastReportedTrackingId = trackingId
                                    onFaceDetected(trackingId)
                                }
                            }
                            .addOnFailureListener(mainExecutor) {
                                if (lastReportedTrackingId != null) {
                                    lastReportedTrackingId = null
                                    onFaceDetected(null)
                                }
                            }
                            .addOnCompleteListener {
                                isAnalyzing.set(false)
                                imageProxy.close()
                            }
                    }
                }

            try {
                val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
                preview?.let { useCases.add(it) }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    *useCases.toTypedArray()
                )
            } catch (_: Exception) {
                try {
                    val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
                    preview?.let { useCases.add(it) }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        *useCases.toTypedArray()
                    )
                } catch (_: Throwable) {
                    if (lastReportedTrackingId != null) {
                        lastReportedTrackingId = null
                        onFaceDetected(null)
                    }
                }
            }
        }, mainExecutor)
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (lastReportedTrackingId != null) {
            lastReportedTrackingId = null
            onFaceDetected(null)
        }
    }

    fun shutdown() {
        unbind()
        detector.close()
        analyzerExecutor.shutdown()
    }
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
@androidx.camera.core.ExperimentalGetImage
fun CameraFacePanel(
    enabled: Boolean,
    faceVisible: Boolean,
    statusText: String,
    onToggleCamera: () -> Unit,
    onFaceDetected: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFaceCallback by rememberUpdatedState(onFaceDetected)
    val scanner = remember(context) {
        CameraFaceScanner(context) { latestFaceCallback(it) }
    }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(enabled, lifecycleOwner) {
        if (enabled) {
            scanner.bind(previewView, lifecycleOwner, context)
        } else {
            scanner.unbind()
        }
        onDispose { scanner.unbind() }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.shutdown() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AuraSurface.copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Камера", fontWeight = FontWeight.SemiBold, color = AuraText)
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = AuraTextMuted
        )
        Surface(
            color = if (enabled) AuraMint.copy(alpha = 0.16f) else AuraCyan.copy(alpha = 0.14f),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable(onClick = onToggleCamera)
        ) {
            Text(
                if (enabled) "Выключить" else "Включить",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                color = AuraText,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 188.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(AuraSurfaceHigh, AuraSurface, AuraSurfaceHigh)
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
        ) {
            if (enabled) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.Black.copy(alpha = 0.12f))
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                ) {
                    Text(
                        if (faceVisible) "Человек обнаружен" else "Смотрю на кадр",
                        color = AuraText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Камера активна только когда приложение открыто",
                        color = AuraTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Камера выключена", color = AuraText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Включите её, чтобы робот видел человека и приветствовал его",
                        color = AuraTextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
