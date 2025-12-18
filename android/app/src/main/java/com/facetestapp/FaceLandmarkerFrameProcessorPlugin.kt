package com.facetestapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.mrousavy.camera.frameprocessors.Frame
import com.mrousavy.camera.frameprocessors.FrameProcessorPlugin
import com.mrousavy.camera.frameprocessors.VisionCameraProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.framework.image.MPImage

class FaceLandmarkerFrameProcessorPlugin(proxy: VisionCameraProxy, options: Map<String, Any>?) : FrameProcessorPlugin() {

    private var faceLandmarker: FaceLandmarker? = null
    private val TAG = "FaceLandmarkerPlugin"

    init {
        Log.d(TAG, "Initializing FaceLandmarkerPlugin")
        setupFaceLandmarker(proxy.context)
    }

    private fun setupFaceLandmarker(context: android.content.Context) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.IMAGE) 
            .build()

        try {
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "FaceLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker", e)
        }
    }

    override fun callback(frame: Frame, arguments: Map<String, Any>?): Any? {
        val landmarker = faceLandmarker ?: return null

        try {
            val image = frame.image
            
            // MediaPipe는 android.media.Image를 직접 지원합니다.
            // Vision Camera의 frame.image는 ImageProxy와 유사하지만 내부 객체를 꺼낼 수 있습니다.
            // 하지만 여기선 안전하게 MediaImageBuilder를 사용합니다.
            val mpImage = MediaImageBuilder(image).build()
            
            val timestampMs = System.currentTimeMillis() // 또는 frame.timestamp 사용
            
            val result = landmarker.detect(mpImage) 

            return processResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            return null
        }
    }

    private fun processResult(result: FaceLandmarkerResult): Map<String, Any>? {
        if (result.faceLandmarks().isEmpty()) return null

        val firstFace = result.faceLandmarks()[0]
        if (firstFace.isEmpty()) return null

        val noseTip = firstFace[1]
        
        val map = HashMap<String, Any>()
        map["noseX"] = noseTip.x()
        map["noseY"] = noseTip.y()
        
        return map
    }
}