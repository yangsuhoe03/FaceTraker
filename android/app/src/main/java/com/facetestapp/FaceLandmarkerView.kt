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

        // 해상도 안전 설정 (VGA)
        val targetResolution = android.util.Size(640, 480)

        val preview = Preview.Builder()
            .setTargetResolution(targetResolution)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(backgroundExecutor) { image ->
                    detectLivestreamFrame(image)
                }
            }

        try {
            cameraProvider.unbindAll()
            
            val currentActivity = (context as? ReactContext)?.currentActivity
            val lifecycleOwner = currentActivity as? LifecycleOwner
                ?: (context as? LifecycleOwner)
            
            Log.d(TAG, "bindCameraUseCases: lifecycleOwner = $lifecycleOwner")
            
            if (lifecycleOwner != null) {
                // 생명주기 관찰
                lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
                Log.d(TAG, "bindCameraUseCases: Current Lifecycle State = ${lifecycleOwner.lifecycle.currentState}")

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                Log.d(TAG, "bindCameraUseCases: Successfully bound lifecycle.")
            } else {
                Log.e(TAG, "bindCameraUseCases: FAILED! Could not find LifecycleOwner.")
            }

        } catch (exc: Exception) {
            Log.e(TAG, "bindCameraUseCases: FAILED! Exception: ${exc.message}")
        }
    }

    private fun detectLivestreamFrame(imageProxy: ImageProxy) {
        // 프레임 도착 로그
        Log.d(TAG, "detectLivestreamFrame: Frame arrived! Timestamp: ${imageProxy.imageInfo.timestamp}")
        
        if (faceLandmarker == null) {
            Log.w(TAG, "detectLivestreamFrame: faceLandmarker is null, skipping frame.")
            imageProxy.close()
            return
        }

        val bitmapBuffer = BitmapImageBuilder(imageProxy.toBitmap()).build()
        val timestampMs = imageProxy.imageInfo.timestamp / 1000000 // ns to ms
        
        faceLandmarker?.detectAsync(bitmapBuffer, timestampMs)
        imageProxy.close()
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