package com.hsu.table.reservation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face
import kotlin.math.max
import kotlin.math.min

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val faceRects = mutableListOf<Rect>()
    private var guideText = ""

    // MLKit에서 분석한 원본 이미지(Analysis) 크기
    private var imageWidth = 0
    private var imageHeight = 0

    // 호출: updateFaces(faces, imageWidth, imageHeight)
    fun updateFaces(faces: List<Face>, imgW: Int, imgH: Int) {
        faceRects.clear()
        for (face in faces) {
            faceRects.add(face.boundingBox)
        }
        imageWidth = imgW
        imageHeight = imgH
        invalidate()
    }

    fun setGuideText(txt: String) {
        guideText = txt
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 전면 카메라 여부 (임시 true)
        val isFrontCamera = true

        // previewView.scaleType = FIT_CENTER 일 때,
        // 뷰 전체(width, height) 중 원본 비율(imageWidth, imageHeight)에 맞춰
        // 아래와 같은 방식으로 그려야 정확히 중앙 정렬됨.

        // 1) 실제 그릴 이미지의 "scale" 계산
        //    FIT_CENTER → 이미지가 뷰 내부에 완전히 맞으면서 비율 유지
        if (imageWidth <= 0 || imageHeight <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // scale 은 "둘 중 작은 쪽" (가로/세로)
        val scale = min(viewW / imageWidth, viewH / imageHeight)

        // 2) 이미지가 가운데 오도록 offset 계산
        //    남는 여백을 2로 나눈 값
        val dx = (viewW - imageWidth * scale) / 2f
        val dy = (viewH - imageHeight * scale) / 2f

        // 페인트
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.GREEN
        }

        for (rect in faceRects) {
            // MLKit 원본 좌표
            var left = rect.left
            var right = rect.right
            var top = rect.top
            var bottom = rect.bottom

            // 전면 카메라면 좌우 반전(미러링)
            if (isFrontCamera) {
                val mirroredLeft = imageWidth - right
                val mirroredRight = imageWidth - left
                left = mirroredLeft
                right = mirroredRight
            }

            // scale + 중앙 offset 반영
            val scaledLeft = dx + left * scale
            val scaledTop = dy + top * scale
            val scaledRight = dx + right * scale
            val scaledBottom = dy + bottom * scale

            canvas.drawRect(scaledLeft, scaledTop, scaledRight, scaledBottom, paint)
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 50f
            textAlign = Paint.Align.CENTER  // 중앙 정렬
        }

        val xPos = width / 2f           // 가로 중앙
        val yPos = height - 100f        // 바닥에서 100px 위
        canvas.drawText(guideText, xPos, yPos, textPaint)
    }
}
