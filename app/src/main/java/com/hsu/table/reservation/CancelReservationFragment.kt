package com.hsu.table.reservation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentCancelReservationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 예약 취소/이용 종료를 위한 Fragment
 */
class CancelReservationFragment : Fragment() {

    private var _binding: FragmentCancelReservationBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    // 얼굴 인식 ActivityResultLauncher
    private val faceCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult? ->
        if (result?.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val userId = data?.getStringExtra("user_id") ?: ""
            if (userId.isNotEmpty()) {
                // (1) 서버에 이 userId가 어느 좌석을 이용 중인지 요청
                checkUserUsage(userId)
            } else {
                Toast.makeText(requireContext(), "사용자 식별 실패", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCancelReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // **바로 얼굴 인식 시작** (버튼 없이)
        startFaceCapture()
    }

    private fun startFaceCapture() {
        val intent = Intent(requireContext(), FaceCaptureActivity::class.java)
        intent.putExtra("IS_CANCEL_MODE", true)
        faceCaptureLauncher.launch(intent)
    }

    /**
     * userId를 서버에 보내어, 어떤 테이블/좌석을 이용 중인지 확인
     */
    private fun checkUserUsage(userId: String) {
        // 예: http://.../checkUserUsage?user_id=xxx
        val url = "${IP}:5004/checkUserUsage?user_id=$userId"
        // (실제로는 POST + JSON 바디로 하는 게 낫지만 편의상 GET 예시)

        CoroutineScope(Dispatchers.IO).launch {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "서버 오류 발생", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)
            val success = json.optBoolean("success", false)
            val msg = json.optString("msg","")

            withContext(Dispatchers.Main) {
                if (!success) {
                    // 예: "인식된 사용자가 없습니다. 수동 취소를 해주세요."
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    // 이용 종료 프래그먼트로 이동 (껍데기만)
                    goToEndReservationFragment()
                    return@withContext
                }

                // checkUserUsage 응답 파싱 시
                val usageList = json.optJSONArray("usageList") ?: JSONArray()

                if (usageList.length() == 0) {
                    showNoUsageDialog {
                        // 확인 버튼 시 => 이용 종료 프래그먼트로 이동
                        goToEndReservationFragment()
                    }
                } else {
                    // 일단 첫 번째 자리만 사용
                    val firstObj = usageList.getJSONObject(0)
                    val usedTableId = firstObj.optInt("tableId", -1)
                    val usedSeatId  = firstObj.optInt("seatId", -1)
                    val seatType    = firstObj.optString("type", "PERSONAL")

                    handleUsageCheckResult(userId, seatType, usedTableId, usedSeatId)
                }
            }
        }
    }

    private fun handleUsageCheckResult(
        userId: String,
        usageType: String,
        tableId: Int,
        seatId: Int
    ) {
        when(usageType) {
            "NONE" -> {
                // 팝업: "이용 정보가 없습니다. 직접 선택할까요?"
                showNoUsageDialog {
                    // 확인 버튼 시 => 이용 종료 프래그먼트로 이동
                    goToEndReservationFragment()
                }
            }

            "PERSONAL" -> {
                // "개인 좌석 = ${tableId}${seatId}, 이용 종료?" 팝업
                showPersonalPopup(
                    userId = userId,
                    tableId = tableId,
                    seatId = seatId
                )
            }

            "GROUP" -> {
                // "단체 이용 중 = ${tableId}, seatId=$seatId"
                showGroupPopup(
                    userId = userId,
                    tableId = tableId,
                    seatId = seatId
                )
            }

            else -> {
                // 기타 에러
                Toast.makeText(requireContext(), "오류: usageType=$usageType", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showNoUsageDialog(onConfirm: ()->Unit) {
        // 간단 AlertDialog 예시
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage("이용 정보가 없습니다. 직접 취소할 좌석을 선택하시겠습니까?")
            .setPositiveButton("확인") { _,_ ->
                onConfirm.invoke()
            }
            .setNegativeButton("취소") { _,_ ->
                // 홈으로 돌아가기
                parentFragmentManager.popBackStack()
            }
            .create()
        dialog.show()
    }

    /**
     * PERSONAL 취소 팝업 => "다른 좌석이예요" / "이용 종료"
     */
    private fun showPersonalPopup(userId: String, tableId: Int, seatId: Int) {
        val letter = ('A'.code + (tableId - 1)).toChar()
        val message = "[$letter$seatId] 좌석을 이용 중입니다.\n\n이용을 종료하시겠습니까?"
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("개인 이용 종료")
            .setMessage(message)
            .setPositiveButton("이용 종료") { _,_ ->
                requestPersonalCancel(userId, tableId, seatId)
            }
            .setNeutralButton("다른 좌석입니다") { _,_ ->
                // 이용 취소 프래그먼트로 이동 (아직 구현 안 된 상태라고 가정)
                goToEndReservationFragment()
            }
            .setNegativeButton("취소") { _,_ ->
                // 홈으로
                parentFragmentManager.popBackStack()
            }
            .create()
        dialog.show()
    }

    private fun requestPersonalCancel(userId: String, tableId: Int, seatId: Int) {
        // 예: http://.../cancelPersonal
        // JSON body: {user_id, tableId, seatId}
        val url = "${IP}:5004/cancelPersonal"
        val jsonBody = JSONObject().apply {
            put("user_id", userId)
            put("tableId", tableId)
            put("seatId", seatId)
        }

        CoroutineScope(Dispatchers.IO).launch {
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            val resp = client.newCall(req).execute()
            val ok = resp.isSuccessful

            val bodyStr = resp.body?.string() ?: ""
            // 예시 응답 { success:true, msg:"" }
            withContext(Dispatchers.Main) {
                if (!ok) {
                    Toast.makeText(requireContext(), "서버 오류(개인 취소)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "개인 이용이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                }
                // 취소 끝 -> 홈 or 프래그먼트 닫기
                parentFragmentManager.popBackStack()
            }
        }
    }

    /**
     * GROUP => "모두", "혼자만", "취소"
     */
    private fun showGroupPopup(userId: String, tableId: Int, seatId: Int) {
        val letter = ('A'.code + (tableId - 1)).toChar()
        val msg = "${letter}번 테이블 좌석을 모두 이용 종료하시겠습니까?"
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("단체 이용 종료")
            .setMessage(msg)
            .setPositiveButton("모두") { _,_ ->
                requestGroupAllCancel(tableId)
            }
            .setNeutralButton("취소") { _,_ ->
                goToEndReservationFragment()

            }
            .setNegativeButton("혼자만") { _,_ ->
                requestGroupSingleCancel(userId, tableId, seatId)

            }
            .create()
        dialog.show()
    }

    private fun requestGroupAllCancel(tableId: Int) {
        val url = "${IP}:5004/cancelGroupAll"
        val jsonBody = JSONObject().apply {
            put("tableId", tableId)
        }
        CoroutineScope(Dispatchers.IO).launch {
            val reqBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder()
                .url(url)
                .post(reqBody)
                .build()
            val resp = client.newCall(req).execute()
            val ok = resp.isSuccessful

            withContext(Dispatchers.Main) {
                if (!ok) {
                    Toast.makeText(requireContext(),
                        "단체 전체 종료 실패", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),
                        "단체 이용이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun requestGroupSingleCancel(userId: String, tableId: Int, seatId: Int) {
        val url = "${IP}:5004/cancelGroupSingle"
        val jsonBody = JSONObject().apply {
            put("user_id", userId)
            put("tableId", tableId)
            put("seatId", seatId)
        }
        CoroutineScope(Dispatchers.IO).launch {
            val reqBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val req = Request.Builder()
                .url(url)
                .post(reqBody)
                .build()
            val resp = client.newCall(req).execute()
            val ok = resp.isSuccessful

            withContext(Dispatchers.Main) {
                if (!ok) {
                    Toast.makeText(requireContext(),
                        "단체 일부 종료 실패", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),
                        "한 좌석을 이용 종료했습니다.", Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun goToEndReservationFragment() {
        parentFragmentManager.beginTransaction()
            // layout XML에서 실제로 프래그먼트를 담고 있는 View의 ID를 사용하세요.
            .replace(R.id.fragment_container, EndReservationFragment())
            .addToBackStack(null)
            .commit()
    }

}
