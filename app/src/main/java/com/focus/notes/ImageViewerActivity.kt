package com.focus.notes

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.min

/** Полноэкранный просмотр картинки: щипок масштабирует, перетаскивание двигает, двойной тап приближает. */
class ImageViewerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra("path")
        val bitmap = if (path != null) BitmapFactory.decodeFile(path) else null
        if (bitmap == null) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val view = ZoomImageView(this)
        view.setBackgroundColor(Color.BLACK)
        view.setImageBitmap(bitmap)
        setContentView(view)
    }
}

class ZoomImageView(context: Context) : ImageView(context) {

    private val m = Matrix()
    private val vals = FloatArray(9)
    private var fitScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cur = currentScale()
                val target = (cur * d.scaleFactor).coerceIn(fitScale, fitScale * 6f)
                val f = target / cur
                m.postScale(f, f, d.focusX, d.focusY)
                fixBounds()
                imageMatrix = m
                return true
            }
        }
    )

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale() > fitScale * 1.05f) {
                    fit()
                } else {
                    val f = fitScale * 2.5f / currentScale()
                    m.postScale(f, f, e.x, e.y)
                    fixBounds()
                    imageMatrix = m
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        fitScale = min(width / dw, height / dh)
        m.reset()
        m.postScale(fitScale, fitScale)
        m.postTranslate((width - dw * fitScale) / 2f, (height - dh * fitScale) / 2f)
        imageMatrix = m
    }

    private fun currentScale(): Float {
        m.getValues(vals)
        return vals[Matrix.MSCALE_X]
    }

    private fun fixBounds() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) {
            dx = (width - r.width()) / 2f - r.left
        } else {
            if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        }
        if (r.height() <= height) {
            dy = (height - r.height()) / 2f - r.top
        } else {
            if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        }
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        taps.onTouchEvent(ev)
        scaler.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                panning = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> panning = false
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(idx)
                lastY = ev.getY(idx)
                panning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning && !scaler.isInProgress && ev.pointerCount == 1) {
                    m.postTranslate(ev.x - lastX, ev.y - lastY)
                    fixBounds()
                    imageMatrix = m
                    lastX = ev.x
                    lastY = ev.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }
}
