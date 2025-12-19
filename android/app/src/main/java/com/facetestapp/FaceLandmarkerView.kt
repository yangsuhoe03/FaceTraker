package com.facetestapp

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import android.view.ViewGroup.LayoutParams
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceLandmarkerView(context: Context) : FrameLayout(context) {

    private var previewView: PreviewView = PreviewView(context)
    private var overlayView: OverlayView = OverlayView(context, null)
    private var faceLandmarker: FaceLandmarker? = null
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    // 생명주기 감시자
    private val lifecycleObserver = LifecycleEventObserver { source, event ->
        Log.d(TAG, "Lifecycle Event: $event (Current State: ${source.lifecycle.currentState})")
    }

    init {
        Log.d(TAG, "FaceLandmarkerView: Initializing...")
        
        val layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        previewView.layoutParams = layoutParams
        overlayView.layoutParams = layoutParams
        
        // 검은 화면 방지 설정
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        
        addView(previewView)
        addView(overlayView)

        previewView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            Log.d(TAG, "PreviewView Layout Changed: width=$width, height=$height")
        }
        
        // 초기화 시점에는 setup만 하고 카메라는 attached 시점에 시작
        setupFaceLandmarker()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow: View attached to window.")
        // UI 안정화 후 카메라 시작
        post {
            startCamera()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow: View detached from window.")
        backgroundExecutor.shutdown()
        faceLandmarker?.close()
    }

    private fun setupFaceLandmarker() {
        Log.d(TAG, "setupFaceLandmarker: Starting setup...")
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)
            .build()

        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "setupFaceLandmarker: Success!")
        } catch (e: Exception) {
            Log.e(TAG, "setupFaceLandmarker: Failed! Error: ${e.message}")
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startCamera: CRITICAL ERROR - Camera permission is NOT granted in Native layer!")
            return
        }

        Log.d(TAG, "startCamera: Requesting ProcessCameraProvider...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            Log.d(TAG, "startCamera: CameraProvider initialized.")
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        Log.d(TAG, "bindCameraUseCases: Binding start...")
        val cameraProvider = cameraProvider ?: return
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // [중요] 호환성이 낮은 RGBA_8888 포맷 강제 설정을 제거합니다.
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    detectLivestreamFrame(image)
                }
            }

        try {
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Unbind failed", e)
        }

        // [단계적 바인딩] 먼저 Preview만 연결하여 화면을 띄웁니다.
        previewView.postDelayed({
            try {
                val currentActivity = (context as? ReactContext)?.currentActivity
                val lifecycleOwner = currentActivity as? LifecycleOwner ?: (context as? LifecycleOwner)
                
                if (lifecycleOwner != null && cameraProvider != null) {
                    Log.d(TAG, "bindCameraUseCases: Step 1 - Binding PREVIEW")
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                    
                    // 다시 1초 뒤에 Analyzer를 추가로 연결합니다. (하드웨어 부하 분산)
                    previewView.postDelayed({
                        try {
                            Log.d(TAG, "bindCameraUseCases: Step 2 - Binding ANALYZER")
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalyzer)
                            Log.d(TAG, "bindCameraUseCases: All cases bound successfully.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Step 2 failed", e)
                        }
                    }, 1000)

                }
            } catch (exc: Exception) {
                Log.e(TAG, "Step 1 failed", exc)
            }
        }, 500)
    }

    private fun detectLivestreamFrame(imageProxy: ImageProxy) {
        if (faceLandmarker == null) {
            imageProxy.close()
            return
        }

        try {
            // imageProxy.toBitmap()은 내부적으로 YUV를 변환해주므로 안전합니다.
            val bitmap = imageProxy.toBitmap()
            val bitmapBuffer = BitmapImageBuilder(bitmap).build()
            val timestampMs = imageProxy.imageInfo.timestamp / 1000000 
            
            faceLandmarker?.detectAsync(bitmapBuffer, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun returnLivestreamResult(result: FaceLandmarkerResult, input: MPImage) {
        post {
            overlayView.setResults(
                result,
                input.height,
                input.width,
                RunningMode.LIVE_STREAM
            )
        }

        if (result.faceLandmarks().isNotEmpty()) {
            val params = Arguments.createMap()
            val firstFace = result.faceLandmarks()[0]
            if (firstFace.isNotEmpty()) {
                val noseTip = firstFace[1]
                params.putDouble("noseX", noseTip.x().toDouble())
                params.putDouble("noseY", noseTip.y().toDouble())

                // Iris landmarks
                // 468: Left Iris Center
                // 473: Right Iris Center
                if (firstFace.size > 473) {
                    val leftIris = firstFace[468]
                    val rightIris = firstFace[473]

                    params.putDouble("leftIrisX", leftIris.x().toDouble())
                    params.putDouble("leftIrisY", leftIris.y().toDouble())
                    params.putDouble("rightIrisX", rightIris.x().toDouble())
                    params.putDouble("rightIrisY", rightIris.y().toDouble())
                }
                
                val reactContext = context as? ReactContext
                reactContext?.getJSModule(RCTEventEmitter::class.java)
                    ?.receiveEvent(id, "onFaceDetected", params)
            }
        }
    }

    private fun returnLivestreamError(error: RuntimeException) {
        Log.e(TAG, "Face Landmarker error: ${error.message}")
    }
    
    fun onDropViewInstance() {
        backgroundExecutor.shutdown()
        faceLandmarker?.close()
    }

    companion object {
        private const val TAG = "FaceLandmarkerView"
    }
}