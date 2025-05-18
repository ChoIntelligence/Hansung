package com.hsu.table.reservation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat

private const val REQUEST_CAMERA_PERMISSION = 1001

class FaceCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var faceOverlayView: FaceOverlayView
    private var isPersonalReservation = true

    // 전면 카메라 여부
    private val isFrontCamera = false

    private var isCaptured = false
    private val ANGLE_THRESHOLD = 10f

    // MLKit 얼굴 인식기
    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    // YUV -> RGB 유틸 (색상 깨짐 없이 변환)
    private val yuvToRgbConverter by lazy {
        YuvToRgbConverter(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_capture)

        previewView = findViewById(R.id.previewView)
        // FIT_CENTER로 원본 비율 유지
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        faceOverlayView = findViewById(R.id.faceOverlayView)

        val isPersonalFromIntent = intent?.getBooleanExtra("IS_PERSONAL_RESERVATION", true)
        isPersonalReservation = isPersonalFromIntent ?: true

        // 카메라 권한
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()


            // ViewPort
            val viewPort = ViewPort.Builder(Rational(16, 9), Surface.ROTATION_0)
                .setScaleType(ViewPort.FIT)
                .build()

            // Preview
            val preview = Preview.Builder().build()
            // Analysis
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(analysis)
                .setViewPort(viewPort)
                .build()

            // 전면 카메라
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, useCaseGroup
            )

            preview.setSurfaceProvider(previewView.surfaceProvider)

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (!isCaptured) {
                    processImageProxy(imageProxy)
                } else {
                    imageProxy.close()
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (!isCaptured) {
                    handleFaces(faces, image, imageProxy)
                }
            }
            .addOnFailureListener { e -> e.printStackTrace() }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleFaces(faces: List<Face>, image: InputImage, imageProxy: ImageProxy) {
        faceOverlayView.updateFaces(faces, image.width, image.height)

        if (faces.isEmpty()) {
            faceOverlayView.setGuideText("얼굴이 인식되지 않았습니다.")
            return
        }

        if (isPersonalReservation) {
            // 개인 예약: 한 명만 인식되어야 함
            if (faces.size > 1) {
                // 두 명 이상
                faceOverlayView.setGuideText("1명만 인식해주세요!")
                return
            }
            // 정확히 1명
            val face = faces[0]
            checkAndCaptureSingleFace(face, imageProxy)
        } else {
            if (faces.size > 1) {
                // 두 명 이상
                faceOverlayView.setGuideText("한명씩 인식해주세요!")
                return
            }
            // 정확히 1명
            val face = faces[0]
            checkAndCaptureSingleFace(face, imageProxy)
        }
    }

    private fun startGroupFaceCapture() {
        // TODO: implement group flow
        // ex) if faces.size >= 2 -> good to proceed, else wait?
    }

    private fun checkAndCaptureSingleFace(face: Face, imageProxy: ImageProxy) {
        val yaw = face.headEulerAngleY
        val roll = face.headEulerAngleZ

        if (abs(yaw) < ANGLE_THRESHOLD && abs(roll) < ANGLE_THRESHOLD) {
            faceOverlayView.setGuideText("정면 인식 완료!")
            isCaptured = true
            captureAndCropFace(imageProxy, face)
        } else {
            faceOverlayView.setGuideText("얼굴 정면이 되도록 움직여주세요")
        }
    }

    private fun captureAndCropFace(imageProxy: ImageProxy, face: Face) {
        // 1) YUV->ARGB (no rotation) using YuvToRgbConverter
        val srcWidth = imageProxy.width
        val srcHeight = imageProxy.height

        val argbBitmap = Bitmap.createBitmap(srcWidth, srcHeight, Bitmap.Config.ARGB_8888)
        yuvToRgbConverter.yuvToRgb(imageProxy, argbBitmap)

        // 2) boundingBox 좌표 변환 (회전 + 미러) -> crop
        val rotationDeg = imageProxy.imageInfo.rotationDegrees
        val box = transformBoundingBox(face.boundingBox, srcWidth, srcHeight, rotationDeg, isFrontCamera)

        val cropped = safeCrop(argbBitmap, box)

        // (optional) 만약 최종 이미지를 “화면 방향”으로 회전하고 싶다면
        // val finalBitmap = rotateBitmapIfNeeded(cropped, rotationDeg, isFrontCamera)
        val finalBitmap = cropped // 여기서는 그대로 사용

        // 3) 서버 전송
        CoroutineScope(Dispatchers.IO).launch {
            val success = uploadFaceToServer(finalBitmap)
            withContext(Dispatchers.Main) {
                if (success) {
//                    Toast.makeText(this@FaceCaptureActivity, "얼굴 인식 성공", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FaceCaptureActivity, "인식 실패", Toast.LENGTH_SHORT).show()
                }
//                goToPersonalReservation()
            }
        }
    }

    /**
     * boundingBox + (rotation, frontCamera) => 최종 box
     * MLKit boundingBox는 "rotationDegrees"를 적용한 좌표라서
     *   - 전면카메라 미러는 자동 처리 안됨
     *   - 회전도 기기별로 테스트가 필요
     */
    private fun transformBoundingBox(
        rect: Rect,
        srcW: Int,
        srcH: Int,
        rotationDeg: Int,
        frontCam: Boolean
    ): Rect {
        // MLKit doc: "fromMediaImage(..., rotationDegrees)" => boundingBox가 "정방향"이라고 가정
        // 실제로는 전면 미러가 반영되지 않음
        var left = rect.left
        var right = rect.right
        var top = rect.top
        var bottom = rect.bottom

        if (frontCam) {
            // 좌우 반전
            val mirroredLeft = srcW - right
            val mirroredRight = srcW - left
            left = mirroredLeft
            right = mirroredRight
        }

        // MLKit 보정이 rotationDegrees를 이미 처리했다고 가정(회전된 좌표).
        // 만약 기기에서 boundingBox가 90도 어긋난다면 swap(x,y) 추가 필요
        // but let's assume it’s correct.

        return Rect(left, top, right, bottom)
    }

    /**
     * crop with bounds check
     */
    private fun safeCrop(bmp: Bitmap, rect: Rect): Bitmap {
        val x = max(0, rect.left)
        val y = max(0, rect.top)
        val w = min(rect.width(), bmp.width - x)
        val h = min(rect.height(), bmp.height - y)
        if (w <= 0 || h <= 0) {
            // fallback
            return bmp
        }
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

//    private fun goToPersonalReservation() {
//        val intent = Intent(this, MainActivity::class.java)
//        intent.putExtra("goto_fragment", "PersonalReservationFragment")
//        startActivity(intent)
//        finish()
//    }

    // FaceCaptureActivity.kt (일부 발췌)
    private fun uploadFaceToServer(faceBitmap: Bitmap): Boolean {
        return try {
            val url = "${IP}:5001/uploadFace"
            val client = OkHttpClient()

            val stream = ByteArrayOutputStream()
            faceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val faceBytes = stream.toByteArray()

            val imageRequestBody = faceBytes.toRequestBody(
                "image/jpeg".toMediaTypeOrNull(), 0, faceBytes.size
            )
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("faceImage", "face.jpg", imageRequestBody)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(multipartBody)
                .build()

            // 동기로 실행 (네트워크 통신은 메인스레드에서 하면 안 되므로
            //  실제론 코루틴 or 별도 스레드에서 수행 후 결과만 반환)
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                // JSON 파싱
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val jsonObj = org.json.JSONObject(bodyStr)
                    val success = jsonObj.optBoolean("success", false)
                    if (success) {
                        // is_new_person, is_malicious, ...
                        val isNew = jsonObj.optBoolean("is_new_person", false)
                        val isMal = jsonObj.optBoolean("is_malicious", false)
                        val userId = jsonObj.optString("user_id", "")

                        // 메인스레드에서 UI 처리
                        runOnUiThread {
                            handleServerResult(isNew, isMal, userId)
                        }
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e("FaceCapture", "Server upload failed", e)
            false
        }
    }

    // FaceCaptureActivity 내부
    private fun handleServerResult(isNewPerson: Boolean, isMalicious: Boolean, userId: String) {
        if (isMalicious) {
            Toast.makeText(this, "당일 이용 제한된 사용자입니다.", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            // malicious=false -> 개인 예약
            // => setResult로 userId 전달
            val resultData = Intent().apply {
                putExtra("user_id", userId)
            }

            println(userId)
            setResult(Activity.RESULT_OK, resultData)
            finish()
        }
    }




class YuvToRgbConverter(context: Context) {

    /**
     * Call this to convert YUV -> ARGB.
     * [image] must have format YUV_420_888 and [out] is an ARGB_8888 Bitmap with same size.
     */
    fun yuvToRgb(image: ImageProxy, out: Bitmap) {
        require(image.format == ImageFormat.YUV_420_888) {
            "Image format must be YUV_420_888"
        }
        val width = image.width
        val height = image.height
        require(out.width == width && out.height == height) {
            "Bitmap size mismatch. Expected $width x $height but got ${out.width} x ${out.height}"
        }

        // Prepare arrays/buffers
        val yPlane = image.planes[0].buffer
        val uPlane = image.planes[1].buffer
        val vPlane = image.planes[2].buffer

        // row strides
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        // We will fill out ARGB pixel by reading Y, U, V from the planes.
        val argbBuffer = IntArray(width * height) // ARGB pixels

        // Y plane is 1 byte per pixel (luminance)
        // U, V planes are interleaved by pixelStride
        for (row in 0 until height) {
            val yRowOffset = yRowStride * row
            val uvRowOffset = uvRowStride * (row / 2)

            for (col in 0 until width) {
                val yColOffset = col
                val uvColOffset = (col / 2) * uvPixelStride

                val y = (yPlane.get(yRowOffset + yColOffset).toInt() and 0xFF)

                val u = (uPlane.get(uvRowOffset + uvColOffset).toInt() and 0xFF)
                val v = (vPlane.get(uvRowOffset + uvColOffset).toInt() and 0xFF)

                val c = y - 16
                val d = u - 128
                val e = v - 128

                val r = (1.164f * c + 1.596f * e).toInt().coerceIn(0, 255)
                val g = (1.164f * c - 0.813f * e - 0.391f * d).toInt().coerceIn(0, 255)
                val b = (1.164f * c + 2.018f * d).toInt().coerceIn(0, 255)

                val pixel = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                argbBuffer[row * width + col] = pixel
            }
        }

        out.setPixels(argbBuffer, 0, width, 0, 0, width, height)
    }
}
}