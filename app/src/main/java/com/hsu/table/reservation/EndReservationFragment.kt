package com.hsu.table.reservation

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.hsu.table.reservation.databinding.FragmentEndReservationBinding
import dev.jahidhasanco.seatbookview.SeatBookView
import dev.jahidhasanco.seatbookview.SeatClickListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.OnBackPressedCallback

class EndReservationFragment : Fragment() {

    private var _binding: FragmentEndReservationBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    // 수평 레이아웃
    private val horizontalLayout: LinearLayout by lazy {
        binding.horizontalContainer
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEndReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 프래그먼트 시작 시 서버에서 table_*.json 목록 불러오기
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 1) 스택 비우기
                    parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    // 2) HomeFragment로 교체
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, HomeFragment())
                        .commit()
                }
            }
        )

        // (A) 서버에서 json 목록 가져옴 (IO스레드)
        CoroutineScope(Dispatchers.IO).launch {
            val list = fetchAllTables() // JSONArray?

            if (list == null || list.length() == 0) {
                Log.e("EndReservationFragment", "No table data from server (null or length=0)")
                return@launch
            }

            // (B) 각 table_id 별로 SeatBookView 생성
            for (i in 0 until list.length()) {
                val tableObj = list.getJSONObject(i)
                val tableId = tableObj.optInt("table_id", -1)
                val contentJson = tableObj.optJSONObject("content") ?: JSONObject()

                // SeatBookView 구성
                val seatBookView = createSeatBookViewForEnd(tableId, contentJson)

                // UI 추가는 메인스레드
                withContext(Dispatchers.Main) {
                    horizontalLayout.addView(seatBookView)
                }
            }
        }
    }

    /**
     * (1) 서버에서 /all_tables 요청 -> JSONArray 반환
     */
    private fun fetchAllTables(): JSONArray? {
        return try {
            val url = "${IP}:5002/all_tables"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("EndReservationFragment", "fetchAllTables() => code=${response.code}")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            JSONArray(bodyStr)
        } catch(e: Exception) {
            Log.e("EndReservationFragment", "fetchAllTables exception", e)
            null
        }
    }

    /**
     * SeatBookView 생성
     * -> EMPTY 좌석은 disable
     * -> OCCUPIED/ITEM 좌석만 클릭 가능
     */
    private fun createSeatBookViewForEnd(tableId: Int, contentJson: JSONObject): SeatBookView {
        val seatLayout = buildSeatLayoutString(contentJson)
        val customTitles = buildCustomTitleList(tableId, contentJson)

        val sbv = SeatBookView(requireContext())
        sbv.setTag(tableId)

        val dp16 = dpToPx(16)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp16, 0, dp16, 0)
        sbv.layoutParams = lp

        sbv.setSeatsLayoutString(seatLayout)
            .setSelectSeatLimit(4)
            .isCustomTitle(true)
            .setCustomTitle(customTitles)
            .setSeatLayoutPadding(8)
            .setSeatSizeBySeatsColumnAndLayoutWidth(10, -1)

        sbv.setSeatTextSize(30f)
        sbv.setAvailableSeatsBackground(R.drawable.seat_available)
        sbv.setBookedSeatsBackground(R.drawable.seat_booked)
        sbv.setReservedSeatsBackground(R.drawable.seat_reserved)
        sbv.setSelectedSeatsBackground(R.drawable.seat_selected)

        sbv.show()

        // (A) 각 seatView.tag 초기화 + EMPTY 좌석은 disable 처리
        for (id in 1..4) {
            val seatView = sbv.getSeatView(id)
            // seatLayoutString에서 A=EMPTY, U=OCCUPIED, R=ITEM
            // seatView.tag (endReservation에서는 1=AVAILABLE,2=BOOKED,3=RESERVED)
            val status = seatView.tag as? Int ?: 0
            if (status == 1) {
                // EMPTY 좌석 disable
                seatView.setBackgroundResource(R.drawable.seat_disabled)
                seatView.setTag(R.id.tag_disabled, true)
            } else {
                // OCCUPIED or ITEM => 클릭 가능
                seatView.setTag(R.id.tag_disabled, false)
            }
        }

        // (B) 클릭 리스너 => 이용중인 좌석(OCCUPIED/ITEM)만 처리
        sbv.setSeatClickListener(object : SeatClickListener {

            /**
             * EMPTY 좌석('A') → onAvailableSeatClick
             *  : 아무 동작도 안 하거나, "빈 좌석입니다" 정도의 Toast 후 다시 disabled로 유지
             */
            override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                val disabled = (view.getTag(R.id.tag_disabled) as? Boolean) ?: true
                if (disabled) {
                    view.setBackgroundResource(R.drawable.seat_disabled)
                    return
                }

                // 혹시나 클릭됐을 때 -> 다시 disabled(회색) 처리
                view.setBackgroundResource(R.drawable.seat_disabled)
                view.setTag(R.id.tag_disabled, true)

                Toast.makeText(requireContext(), "빈 좌석입니다.", Toast.LENGTH_SHORT).show()
            }

            /**
             * BOOKED 좌석('U') → onBookedSeatClick
             *   : 실제 사용 중인 좌석(개인) -> 취소 팝업 띄우고 확인 시 cancelPersonal()
             *   : 팝업에서 "취소" 누르면 다시 seat_booked 로 복원
             */
            override fun onBookedSeatClick(view: View) {
                val seatId = view.id
                val tableType = contentJson.optString("type", "PERSONAL")

                // 개인 or 단체에 따라 다른 팝업 호출
                if (tableType == "PERSONAL") {
                    // 개인 예약 → showPersonalEndPopup
                    showPersonalEndPopup(tableId, seatId, sbv)
                } else {
                    // 단체 예약 → showGroupEndPopup
                    showGroupEndPopup(tableId, seatId, sbv, contentJson)
                }
                // 팝업 띄우기 전, 잠시 선택 색깔로 표시 가능(선택 사항)
                view.setBackgroundResource(R.drawable.seat_selected)
            }

            /**
             * RESERVED 좌석('R') → onReservedSeatClick
             *   : 단체 좌석이라고 가정 → "모두 / 혼자만 / 취소" 팝업
             *   : 팝업 닫히면 다시 seat_reserved 로 복원
             */
            override fun onReservedSeatClick(view: View) {
                val seatId = view.id
                // 예: 그룹 취소 팝업
                val dialog = android.app.AlertDialog.Builder(requireContext())
                    .setTitle("단체 이용 종료")
                    .setMessage("모두 종료할까요? 혼자만 나갈까요?")
                    .setPositiveButton("모두") { _, _ ->
                        requestGroupAllCancel(tableId)
                    }
                    .setNeutralButton("취소") { _, _ ->
                        view.setBackgroundResource(R.drawable.seat_reserved)
                    }
                    .setNegativeButton("혼자만") { _, _ ->
                        requestGroupSingleCancel(tableId, seatId, contentJson)
                    }
                    .setOnCancelListener {
                        // 바깥 터치 등으로 닫혀도 복원
                        view.setBackgroundResource(R.drawable.seat_reserved)
                    }
                    .create()

                // 클릭 시 잠깐 선택 표시
                view.setBackgroundResource(R.drawable.seat_selected)
                dialog.show()
            }
        })


        return sbv
    }

    /**
     * 개인 예약 => "${tableId}${seatId} 좌석 이용 종료?"
     * 확인 시 => 서버에 "개인취소" => userID.json 삭제(조건), tableId.json -> seat EMPTY
     */
    private fun showPersonalEndPopup(tableId: Int, seatId: Int, sbv: SeatBookView) {
        val letter = ('A'.code + (tableId - 1)).toChar()
        val msg = "[$letter$seatId] 좌석 이용을 종료하시겠습니까?"
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("개인 이용 종료")
            .setMessage(msg)
            .setPositiveButton("확인") { _,_ ->
                requestPersonalCancel(tableId, seatId)
            }
            .setNegativeButton("취소") { _,_ ->
                // 좌석 해제
                val seatView = sbv.getSeatView(seatId)
                seatView.performClick()
                seatView.setBackgroundResource(R.drawable.seat_booked) // or seat_reserved
            }
            .create()
        dialog.show()
    }

    private fun requestPersonalCancel(tableId: Int, seatId: Int) {
        // POST => /cancelPersonal
        // Body => { "tableId":..., "seatId":..., "user_id":??? }
        // → 서버단에서 seatId->userID 를 찾고 userID.json 처리
        val url = "${IP}:5004/cancelPersonal"
        val jsonBody = JSONObject().apply {
            put("tableId", tableId)
            put("seatId", seatId)
            // 서버에서 seatId->userID를 찾아 is_malicious 체크
            // 이 예시에서는 client단에서 userID를 모를 수 있음 => 서버에서 seatId->userID 찾는 로직
            // 필요하면 seatId->userID를 미리 contentJson에서 추출 가능
        }

        CoroutineScope(Dispatchers.IO).launch {
            val reqBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(reqBody)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val bodyStr = response.body?.string() ?: ""

            withContext(Dispatchers.Main) {
                if (!ok) {
                    Toast.makeText(requireContext(), "개인 이용 종료 실패", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "개인 이용이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                }
                // 홈으로 복귀
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, HomeFragment())
                    .commit()
            }
        }
    }

    /**
     * 그룹 예약 => "모두", "혼자만", "취소"
     */
    private fun showGroupEndPopup(tableId: Int, seatId: Int, sbv: SeatBookView, contentJson: JSONObject) {
        val letter = ('A'.code + (tableId - 1)).toChar()
        val msg = "${letter}번 테이블 좌석을 모두 이용 종료하시겠습니까?"
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("단체 이용 종료")
            .setMessage(msg)
            .setPositiveButton("모두") { _,_ ->
                requestGroupAllCancel(tableId)
            }
            .setNeutralButton("취소") { _,_ ->
                val seatView = sbv.getSeatView(seatId)
                // OCCUPIED => seat_booked, ITEM => seat_reserved
                seatView.performClick()
                seatView.setBackgroundResource(R.drawable.seat_booked)
            }
            .setNegativeButton("혼자만") { _,_ ->
                requestGroupSingleCancel(tableId, seatId, contentJson)

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
                    Toast.makeText(requireContext(),"단체 전체 종료 실패",Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),"단체 전체 이용 종료 완료",Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, HomeFragment())
                    .commit()
            }
        }
    }

    private fun requestGroupSingleCancel(tableId: Int, seatId: Int, contentJson: JSONObject) {
        val url = "${IP}:5004/cancelGroupSingle"
        val jsonBody = JSONObject().apply {
            put("tableId", tableId)
            put("seatId", seatId)
            // seatId->userID => 서버에서 userID 찾기 + is_malicious=false=>삭제
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
                    Toast.makeText(requireContext(),"단체 일부 종료 실패",Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),"단체 일부 이용 종료 완료",Toast.LENGTH_SHORT).show()
                }
                // 스택 전체 비우기
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, HomeFragment())
                    .commit()

            }
        }
    }

    // ----------------------------
    // seatLayoutString / customTitleList
    // ----------------------------
    private fun buildSeatLayoutString(contentJson: JSONObject): String {
        val arr = contentJson.optJSONArray("tableStatus") ?: return "/AA/AA/__/"
        val seatMap = Array(2){ Array(2){ 'A' } }
        for (i in 0 until arr.length()) {
            val seatObj = arr.getJSONObject(i)
            val r = seatObj.optInt("row",1)
            val c = seatObj.optInt("col",1)
            val st= seatObj.optString("seatStatus","EMPTY")
            val ch= when(st) {
                "EMPTY" -> 'A'
                "ITEM"  -> 'R'
                "OCCUPIED" -> 'U'
                else -> 'A'
            }
            seatMap[r-1][c-1] = ch
        }
        val row0 = "${seatMap[0][0]}${seatMap[0][1]}"
        val row1 = "${seatMap[1][0]}${seatMap[1][1]}"
        return "/$row0/$row1/__/"
    }

    private fun buildCustomTitleList(tableId: Int, contentJson: JSONObject): List<String> {
        val letter = ('A'.code + (tableId -1)).toChar()
        val result = mutableListOf("/", "", "", "/", "", "")

        fun seatIdxToCustomIndex(idx:Int) = when(idx){
            0->1
            1->2
            2->4
            3->5
            else->-1
        }

        // 기본 레이블
        for (idx in 0..3) {
            val defaultLabel= "$letter${idx+1}"
            val ci = seatIdxToCustomIndex(idx)
            if(ci in result.indices) result[ci] = defaultLabel
        }

        val arr = contentJson.optJSONArray("tableStatus") ?: return result
        for (i in 0 until arr.length()) {
            val seatObj = arr.getJSONObject(i)
            val r = seatObj.optInt("row",1)
            val c = seatObj.optInt("col",1)
            var endT= seatObj.optString("endTime","")
            if(endT=="null") endT = ""
            if (endT.isNotBlank()) {
                val seatIdx = (r-1)*2 + (c-1)
                val ci = seatIdxToCustomIndex(seatIdx)
                if(ci in result.indices) result[ci] = endT
            }
        }
        return result
    }

    private fun dpToPx(dp:Int): Int {
        return (dp*resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding=null
    }
}

// LockTypeInfo, LockType 등은 재사용(생략)
