package com.facetestapp

import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

class FaceLandmarkerManager : SimpleViewManager<FaceLandmarkerView>() {
    override fun getName(): String {
        return "FaceLandmarkerView"
    }

    override fun createViewInstance(reactContext: ThemedReactContext): FaceLandmarkerView {
        return FaceLandmarkerView(reactContext)
    }

    override fun onDropViewInstance(view: FaceLandmarkerView) {
        super.onDropViewInstance(view)
        view.onDropViewInstance()
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
        return MapBuilder.of(
            "onFaceDetected",
            MapBuilder.of("registrationName", "onFaceDetected")
        )
    }
}
