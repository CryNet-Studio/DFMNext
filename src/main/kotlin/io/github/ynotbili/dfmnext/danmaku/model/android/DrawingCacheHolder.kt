package io.github.ynotbili.dfmnext.danmaku.model.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

class DrawingCacheHolder {

    var canvas: Canvas? = null
    var bitmap: Bitmap? = null
    var bitmapArray: Array<Array<Bitmap?>>? = null
    var extra: Any? = null
    var width: Int = 0
    var height: Int = 0
    var drawn: Boolean = false
    private var mDensity: Int = 0

    constructor()

    constructor(w: Int, h: Int) {
        buildCache(w, h, 0, true)
    }

    constructor(w: Int, h: Int, density: Int) {
        mDensity = density
        buildCache(w, h, density, true)
    }

    fun buildCache(w: Int, h: Int, density: Int, checkSizeEquals: Boolean) {
        val reuse = if (checkSizeEquals) (w == width && h == height) else (w <= width && h <= height)
        if (reuse && bitmap != null) {
            bitmap!!.eraseColor(Color.TRANSPARENT)
            canvas!!.setBitmap(bitmap)
            recycleBitmapArray()
            return
        }
        if (bitmap != null) {
            recycle()
        }
        width = w
        height = h
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (density > 0) {
            mDensity = density
            bitmap!!.density = density
        }
        if (canvas == null) {
            canvas = Canvas(bitmap!!)
            canvas!!.density = density
        } else {
            canvas!!.setBitmap(bitmap)
        }
    }

    fun erase() {
        eraseBitmap(bitmap)
        eraseBitmapArray()
    }

    @Synchronized
    fun recycle() {
        val bitmapReserve = bitmap
        bitmap = null
        width = 0
        height = 0
        bitmapReserve?.recycle()
        recycleBitmapArray()
        extra = null
    }

    fun splitWith(dispWidth: Int, dispHeight: Int, maximumCacheWidth: Int, maximumCacheHeight: Int) {
        recycleBitmapArray()
        if (width <= 0 || height <= 0 || bitmap == null) return
        if (width <= maximumCacheWidth && height <= maximumCacheHeight) return

        val effectiveMaxWidth = minOf(maximumCacheWidth, dispWidth)
        val effectiveMaxHeight = minOf(maximumCacheHeight, dispHeight)
        val xCount = width / effectiveMaxWidth + if (width % effectiveMaxWidth == 0) 0 else 1
        val yCount = height / effectiveMaxHeight + if (height % effectiveMaxHeight == 0) 0 else 1
        val averageWidth = width / xCount
        val averageHeight = height / yCount
        val bmpArray = Array(yCount) { arrayOfNulls<Bitmap>(xCount) }

        if (canvas == null) {
            canvas = Canvas()
            if (mDensity > 0) {
                canvas!!.density = mDensity
            }
        }
        val rectSrc = Rect()
        val rectDst = Rect()
        for (yIndex in 0 until yCount) {
            for (xIndex in 0 until xCount) {
                val bmp = Bitmap.createBitmap(averageWidth, averageHeight, Bitmap.Config.ARGB_8888)
                bmpArray[yIndex][xIndex] = bmp
                if (mDensity > 0) {
                    bmp.density = mDensity
                }
                canvas!!.setBitmap(bmp)
                val left = xIndex * averageWidth
                val top = yIndex * averageHeight
                rectSrc.set(left, top, left + averageWidth, top + averageHeight)
                rectDst.set(0, 0, bmp.width, bmp.height)
                canvas!!.drawBitmap(bitmap!!, rectSrc, rectDst, null)
            }
        }
        canvas!!.setBitmap(bitmap)
        bitmapArray = bmpArray
    }

    private fun eraseBitmap(bmp: Bitmap?) {
        bmp?.eraseColor(Color.TRANSPARENT)
    }

    private fun eraseBitmapArray() {
        bitmapArray?.let { arr ->
            for (bitmaps in arr) {
                for (j in bitmaps.indices) {
                    eraseBitmap(bitmaps[j])
                }
            }
        }
    }

    private fun recycleBitmapArray() {
        val bitmapArrayReserve = bitmapArray
        bitmapArray = null
        bitmapArrayReserve?.let { arr ->
            for (i in arr.indices) {
                for (j in arr[i].indices) {
                    arr[i][j]?.let {
                        it.recycle()
                        arr[i][j] = null
                    }
                }
            }
        }
    }

    @Synchronized
    fun draw(canvas: Canvas, left: Float, top: Float, paint: Paint?): Boolean {
        val arr = bitmapArray
        if (arr != null) {
            for (i in arr.indices) {
                for (j in arr[i].indices) {
                    val bmp = arr[i][j]
                    if (bmp != null) {
                        val dleft = left + j * bmp.width
                        if (dleft > canvas.width || dleft + bmp.width < 0) continue
                        val dtop = top + i * bmp.height
                        if (dtop > canvas.height || dtop + bmp.height < 0) continue
                        canvas.drawBitmap(bmp, dleft, dtop, paint)
                    }
                }
            }
            return true
        } else if (bitmap != null) {
            canvas.drawBitmap(bitmap!!, left, top, paint)
            return true
        }
        return false
    }
}
