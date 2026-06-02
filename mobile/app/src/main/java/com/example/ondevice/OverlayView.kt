package com.example.ondevice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    data class BoxInfo(
        val rect: RectF,
        val color: Int,
        val text: String
    )

    private var boundingBox: RectF? = null
    private var boxColor: Int = Color.GREEN
    private var boxText: String = ""
    private var boxes: List<BoxInfo> = emptyList()

    // 네모 박스 테두리 펜 설정
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 10f // 테두리 두께
    }

    // 박스 위 글씨 펜 설정
    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 60f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }

    // AI가 좌표를 주면 이 함수를 통해 화면을 갱신합니다.
    fun setBoxInfo(rect: RectF, color: Int, text: String) {
        boundingBox = rect
        boxColor = color
        boxText = text
        boxes = emptyList()
        boxPaint.color = color
        textPaint.color = color
        invalidate() // 💡 화면에 즉시 다시 그리라는 명령어
    }

    fun setBoxesInfo(items: List<BoxInfo>) {
        boxes = items
        boundingBox = null
        invalidate()
    }

    // 화면에서 사물이 사라지면 박스 지우기
    fun clear() {
        boundingBox = null
        boxes = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // boundingBox에 값이 있으면 사각형과 글씨를 그립니다.
        boundingBox?.let { rect ->
            canvas.drawRect(rect, boxPaint)
            canvas.drawText(boxText, rect.left, rect.top - 15f, textPaint)
        }

        if (boxes.isNotEmpty()) {
            for (item in boxes) {
                boxPaint.color = item.color
                textPaint.color = item.color
                canvas.drawRect(item.rect, boxPaint)
                canvas.drawText(item.text, item.rect.left, item.rect.top - 15f, textPaint)
            }
        }
    }
}