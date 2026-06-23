package com.exmachina.epoccamstreamer

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView

private const val TAG = "AspectSurfaceView"

class AspectRatioSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    var aspectRatio: Float = 16f / 9f  // width / height; update before requestLayout()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val measuredW: Int
        val measuredH: Int
        if (w.toFloat() / h > aspectRatio) {
            measuredW = (h * aspectRatio).toInt()
            measuredH = h
        } else {
            measuredW = w
            measuredH = (w / aspectRatio).toInt()
        }
        Log.w(TAG, "onMeasure: container=${w}x${h} ratio=$aspectRatio → ${measuredW}x${measuredH}")
        setMeasuredDimension(measuredW, measuredH)
    }
}
