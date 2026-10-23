package org.xnet.readb.terminal.view.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** A combination of [GestureDetector] and [ScaleGestureDetector].  */
internal class GestureAndScaleRecognizer(context: Context, val mListener: Listener) {

  interface Listener {
    fun onSingleTapUp(e: MotionEvent): Boolean

    fun onDoubleTap(e: MotionEvent): Boolean

    // For treating double tap as MOUSE_LEFT_BUTTON_MOVED event
    // e.g in vim, we can change window size with fingers moving.
    fun onDoubleTapEvent(e: MotionEvent): Boolean

    fun onScroll(e2: MotionEvent, dx: Float, dy: Float): Boolean

    fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean

    fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean

    fun onDown(x: Float, y: Float): Boolean

    fun onUp(e: MotionEvent): Boolean

    fun onLongPress(e: MotionEvent)
  }

  private val mGestureDetector: GestureDetector
  private val mScaleDetector: ScaleGestureDetector
  var isAfterLongPress: Boolean = false

  init {

    mGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
      override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        return mListener.onScroll(e2, dx, dy)
      }

      override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        return mListener.onFling(e2, velocityX, velocityY)
      }

      override fun onDown(e: MotionEvent): Boolean {
        return mListener.onDown(e.x, e.y)
      }

      override fun onLongPress(e: MotionEvent) {
        mListener.onLongPress(e)
        isAfterLongPress = true
      }
    }, null, true /* ignoreMultitouch */)

    mGestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
      override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        // For treating double tap as MOUSE_LEFT_BUTTON_MOVED event
        // e.g in vim, we can change window size with fingers moving.
        mListener.onUp(e)

        return mListener.onSingleTapUp(e)
      }

      override fun onDoubleTap(e: MotionEvent): Boolean {
        return mListener.onDoubleTap(e)
      }

      override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        // For treating double tap as MOUSE_LEFT_BUTTON_MOVED event
        // e.g in vim, we can change window size with fingers moving.

        // Disable triggering long press which will prevent further double tap motion from
        // receiving, e.g. when you double tap and drag downwards slowly.
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) {
          when (e.action) {
            MotionEvent.ACTION_DOWN ->
              mGestureDetector.setIsLongpressEnabled(false)
            MotionEvent.ACTION_UP ->
              mGestureDetector.setIsLongpressEnabled(true)
          }
          return mListener.onDoubleTapEvent(e)
        }
        return false
      }
    })

    mScaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        return mListener.onScale(detector.focusX, detector.focusY, detector.scaleFactor)
      }
    })

    // For treating double tap as MOUSE_LEFT_BUTTON_MOVED event
    // e.g in vim, we can change window size with fingers moving.
    mScaleDetector.isQuickScaleEnabled = false
  }

  fun onTouchEvent(event: MotionEvent) {
    mGestureDetector.onTouchEvent(event)
    mScaleDetector.onTouchEvent(event)
  }

  val isInProgress: Boolean
    get() = mScaleDetector.isInProgress

}
