package com.hsu.table.reservation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentGroupReservationBinding
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

class GroupReservationFragment : Fragment() {

    private var _binding: FragmentGroupReservationBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    // 인원 수(2,3,4) / 예약 시간("30분","1시간"...)
    private var groupSize: Int = 0
    private var reservationDuration: String? = null

    // 현재 선택된 테이블/좌석
    private var selectedTableId: Int? = null
    private var selectedSeatId: Int? = null

    private var restoredTableId: Int? = null
    private var restoredSeatId: Int? = null

    // 임시 테이블 JSON (등록 진행 중)
    private var tableTmpJson: JSONObject? = null

    // 테이블에 이미 "사람 있는지" 상태 저장
    private val tableHasPersonMap = mutableMapOf<Int, Boolean>()

    // 인원 등록을 몇 명 했는지 카운트
    private var registeredCount = 0

    // FaceCaptureActivity → ActivityResultLauncher
    private val faceCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult? ->
        if (result?.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val userId = data?.getStringExtra("user_id") ?: ""
            if (userId.isNotEmpty()) {
                // (1) 이미 이용 중인지 체크 + table_tmp.json 중복 확인 → 등록
                checkUserAndUpdateSeat(userId)
            } else {
                Toast.makeText(requireContext(), "사용자 식별 실패", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 부정사용자 등으로 CANCEL
            Toast.makeText(requireContext(), "부정사용 또는 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        println("destroyed")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 선택한 테이블, 좌석 정보 저장
        if (selectedTableId != null) outState.putInt("key_tableId", selectedTableId!!)
        if (selectedSeatId != null) outState.putInt("key_seatId", selectedSeatId!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupSize = arguments?.getInt("group_size",0) ?:0
        reservationDuration = arguments?.getString("reservation_duration","1시간")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    // (1) SeatBookView들을 담을 레이아웃 (수평)
    private val horizontalLayout by lazy {
        binding.horizontalContainer
    }

    // (2) tableId -> SeatBookView
    private val seatBookViewsMap = mutableMapOf<Int, SeatBookView>()

    // (A) 뷰 생성 시 → 서버에서 테이블 목록 가져와 SeatBookView 생성
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        restoredTableId = savedInstanceState?.getInt("key_tableId", -1)?.takeIf { it != -1 }
        restoredSeatId = savedInstanceState?.getInt("key_seatId", -1)?.takeIf { it != -1 }

        CoroutineScope(Dispatchers.IO).launch {
            val list = fetchAllTables()
            if (list == null || list.length() == 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "테이블 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                return@launch
            }

            // 테이블별로 SeatBookView 만들어서 화면에 추가
            for (i in 0 until list.length()) {
                val tableObj = list.getJSONObject(i)
                val tableId = tableObj.optInt("table_id",-1)
                val contentJson = tableObj.optJSONObject("content") ?: JSONObject()

                // 사람 있는 테이블?
                val hasPerson = checkHasAnyPerson(contentJson)
                tableHasPersonMap[tableId] = hasPerson

                val sbv = createSeatBookViewForGroup(tableId, contentJson, hasPerson)
                seatBookViewsMap[tableId] = sbv

                withContext(Dispatchers.Main) {
                    horizontalLayout.addView(sbv)
                    if (restoredTableId != null && restoredSeatId != null) {
                        if (tableId == restoredTableId) {
                            // 같은 테이블 -> 해당 seatId를 selected 표식
                            val seatView = sbv.getSeatView(restoredSeatId!!)
                            seatView.setBackgroundResource(R.drawable.seat_selected)
                            selectedTableId = tableId
                            selectedSeatId = restoredSeatId
                        }
                    }
                }
            }
        }
    }

    // --------------------------
    // 서버 테이블 목록 가져오기
    // --------------------------
    private fun fetchAllTables(): JSONArray? {
        return try {
            val url = "http://192.168.50.55:5002/all_tables"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("GroupReservation", "fetchAllTables() => code=${response.code}")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            JSONArray(bodyStr)
        } catch (e: Exception) {
            Log.e("GroupReservation", "fetchAllTables exception", e)
            null
        }
    }

    private fun checkHasAnyPerson(contentJson: JSONObject): Boolean {
        val arr = contentJson.optJSONArray("tableStatus") ?: return false
        for (i in 0 until arr.length()) {
            val seatObj = arr.getJSONObject(i)
            if (seatObj.optString("seatStatus", "EMPTY") == "OCCUPIED") {
                return true
            }
        }
        return false
    }

    // --------------------------
    // SeatBookView 생성
    // --------------------------
    private fun createSeatBookViewForGroup(
        tableId: Int,
        contentJson: JSONObject,
        hasPerson: Boolean
    ): SeatBookView {
        val seatLayout = buildSeatLayoutString(contentJson)
        val customTitles = buildCustomTitleList(tableId, contentJson)

        val sbv = SeatBookView(requireContext()).apply {
            // tableId를 tag로 저장
            setTag(tableId)

            // 레이아웃 파라미터
            val dp16 = dpToPx(16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp16, 0, dp16, 0)
            }

            setSeatsLayoutString(seatLayout)
                .setSelectSeatLimit(4)
                .isCustomTitle(true)
                .setCustomTitle(customTitles)
                .setSeatLayoutPadding(8)
                .setSeatSizeBySeatsColumnAndLayoutWidth(10, -1)

            setSeatTextSize(30f)
            setAvailableSeatsBackground(R.drawable.seat_available)
            setBookedSeatsBackground(R.drawable.seat_booked)
            setReservedSeatsBackground(R.drawable.seat_reserved)
            setSelectedSeatsBackground(R.drawable.seat_selected)

            show()
        }

        // 이미 사람 있는 테이블이면 전체 disable
        if (hasPerson) {
            disableAllSeats(sbv)
        } else {
            // 클릭 리스너 등록
            sbv.setSeatClickListener(object : SeatClickListener {
                override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                    val seatId = selectedIdList.firstOrNull() ?: return
                    // disable 상태인지 체크
                    if (view.getTag(R.id.tag_disabled) as? Boolean == true) {
                        view.post {
                            view.performClick()
                            view.setBackgroundResource(R.drawable.seat_disabled)
                            view.setTag(R.id.tag_disabled, true)
                        }
                        return
                    }

                    // 다른 테이블 전체 disable
                    disableOtherTables(tableId)

                    // 현재 선택
                    selectedTableId = tableId
                    selectedSeatId = seatId

                    val seatName = calcSeatName(tableId, seatId)

                    // BottomSheet 호출
                    showGroupSeatBottomSheet(sbv, tableId, seatId, seatName)
                }

                override fun onBookedSeatClick(view: View) {
                    Toast.makeText(requireContext(),
                        "이미 사람 있는 좌석입니다.", Toast.LENGTH_SHORT).show()
                }

                override fun onReservedSeatClick(view: View) {
                    Toast.makeText(requireContext(),
                        "예약 대기 중인 좌석입니다.", Toast.LENGTH_SHORT).show()
                }
            })
        }

        return sbv
    }

    // --------------------------
    // BottomSheet: 좌석선택
    // --------------------------
    private fun showGroupSeatBottomSheet(
        sbv: SeatBookView,
        tableId: Int,
        seatId: Int,
        seatName: String
    ) {
        val bottomSheet = GroupSeatBottomSheetDialogFragment(
            seatName = seatName,
            onRegisterFace = {
                // "얼굴 등록" 눌렀을 때 할 동작
                onRegisterFaceClicked()
            },
            onCancelSelect = {
                // "선택 취소" 눌렀을 때 할 동작
                // ...
                val seatView = sbv.getSeatView(seatId)
                seatView.performClick()  // '선택'상태 해제
                seatView.setBackgroundResource(R.drawable.seat_available)
                seatView.setTag(R.id.tag_disabled, false)

                enableOtherTables()
                disableOtherTables(tableId)
            }
        )

        // 기존 BottomSheet가 혹시 열려있다면 dismiss() (필요한 경우)
        // val prev = childFragmentManager.findFragmentByTag("GroupSeatBottomSheet")
        // if (prev is BottomSheetDialogFragment) prev.dismiss()

        // 새 BottomSheet 표시
        bottomSheet.show(childFragmentManager, "GroupSeatBottomSheet")
    }



    // --------------------------
    // 얼굴 등록 버튼 → 실제 FaceCaptureActivity로 이동
    // --------------------------
    private fun onRegisterFaceClicked() {
        // 현재 로직: 어떤 테이블/좌석인지는 selectedTableId/selectedSeatId 로 관리
        val intent = Intent(requireContext(), FaceCaptureActivity::class.java)
        // 단체 예약 모드
        intent.putExtra("IS_PERSONAL_RESERVATION", false)
        faceCaptureLauncher.launch(intent)
    }

    // --------------------------
    // ActivityResult에서 userId => 중복검사 + 좌석등록
    // --------------------------
    private fun checkUserAndUpdateSeat(userId: String) {
        // 1) 서버 테이블 목록 재로딩
        CoroutineScope(Dispatchers.IO).launch {
            val tableList = fetchAllTables()
            if (tableList == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "서버오류: 체크 실패", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // 2) 다른 테이블 중복 여부
            val alreadyReservedInOtherTable = checkUserAlreadyHasSeat(tableList, userId)
            // 3) 현재 table_tmp.json 중복
            val alreadyInThisTemp = checkUserAlreadyInThisTemp(tableTmpJson, userId)

            withContext(Dispatchers.Main) {
                // ── 중복 처리 ──
                if (alreadyReservedInOtherTable) {
                    Toast.makeText(
                        requireContext(),
                        "이미 다른 테이블을 이용중인 사용자입니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                    clearSelectionAndEnableOthers()
                    return@withContext
                }
                if (alreadyInThisTemp) {
                    Toast.makeText(
                        requireContext(),
                        "이 테이블에 이미 등록된 사용자입니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                    clearSelectionAndEnableOthers()
                    return@withContext
                }

                // ── 등록 가능 ──
                if (selectedTableId == null || selectedSeatId == null) {
                    Toast.makeText(requireContext(),"좌석이 선택되지 않았습니다.",Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val tId = selectedTableId!!
                val sId = selectedSeatId!!
                val seatName = calcSeatName(tId, sId)

                // table_tmp.json 준비
                if (tableTmpJson == null) {
                    tableTmpJson = createInitialTableTmp(tId)
                }
                // 업데이트
                updateTableTmpJson(tableTmpJson!!, sId, userId, reservationDuration)

                // (A) UI 표시 => seat_booked
                val sbv = seatBookViewsMap[tId]
                val seatView = sbv?.getSeatView(sId)
                seatView?.tag = 2  // BOOKED
                seatView?.setBackgroundResource(R.drawable.seat_booked)

                registeredCount++

                // (B) 만약 아직 groupSize에 도달 안 했다면 => "같은 테이블만" 다시 활성화하고,
                //     다른 테이블은 계속 disable 상태로 유지
                if (registeredCount < groupSize) {
                    // "선택 해제"
                    selectedTableId = null
                    selectedSeatId = null

                    // (B-1) 다른 테이블 disable 그대로 두고,
                    //       *현재 테이블*은 "예약 안된 좌석"만 다시 enable
                    disableOtherTables(tId)   // 이미 disable이긴 하지만 확실하게
                    enableAllSeatsButOccupied(sbv!!)
                    Toast.makeText(requireContext(),
                        "다음 좌석을 선택해주세요.",
                        Toast.LENGTH_SHORT
                    ).show()

                } else {
                    // (C) 그룹 인원 전부 등록 완료 => finalize
                    finalizeGroupReservation(tId)
                }
            }
        }
    }

    private fun clearSelectionAndEnableOthers() {
        enableOtherTables()
        disableOtherTables(selectedTableId)
        selectedTableId = null
        selectedSeatId = null
    }

    // --------------------------
    // 최종 예약 완료
    // --------------------------
    private fun finalizeGroupReservation(tableId: Int) {
        Log.d("GroupReservation","finalizeGroupReservation tableId=$tableId. tmpJson=$tableTmpJson")

        // (1) table_tmp.json 을 서버에 POST → /updateTable
        if (tableTmpJson != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val success = postTableTmpToServer(tableId, tableTmpJson!!)
                withContext(Dispatchers.Main) {
                    if (success) {
                        val letter = ('A'.code + (tableId - 1)).toChar()

                        Toast.makeText(requireContext(),
                            "[예약 완료] $letter 테이블 이용 종료 시간 : $reservationDuration",
                            Toast.LENGTH_LONG
                        ).show()

                        // (2) 프래그먼트 종료(홈으로)
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(),
                            "테이블 $tableId 업데이트 실패",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            // table_tmp가 null인 경우 → 이미 완료되었거나 오류
            Toast.makeText(requireContext(),
                "table_tmp.json이 없습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun postTableTmpToServer(tableId: Int, tableTmpJson: JSONObject): Boolean {
        return try {
            val url = "${IP}:5003/updateTable"
            val jsonBody = JSONObject().apply {
                put("tableId", tableId)
                put("tableContent", tableTmpJson)
                // ex) { "tableId": 3, "tableContent": { "timestamp":..., "tableStatus":[...] } }
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("GroupReservation","postTableTmpToServer error", e)
            false
        }
    }

    // --------------------------
    // table_tmp.json 관련
    // --------------------------
    private fun createInitialTableTmp(tableId: Int): JSONObject {
        val jsonStr = """
            {
              "timestamp": 1746617833,
              "type": "GROUP",
              "tableStatus": [
                {"row":1,"col":1,"userID":null,"seatStatus":"EMPTY","endTime":null,"empty_count":null},
                {"row":1,"col":2,"userID":null,"seatStatus":"EMPTY","endTime":null,"empty_count":null},
                {"row":2,"col":1,"userID":null,"seatStatus":"EMPTY","endTime":null,"empty_count":null},
                {"row":2,"col":2,"userID":null,"seatStatus":"EMPTY","endTime":null,"empty_count":null}
              ]
            }
        """.trimIndent()
        return JSONObject(jsonStr)
    }

    private fun updateTableTmpJson(
        tmpJson: JSONObject,
        seatId: Int,
        userId: String,
        duration: String?
    ) {
        val arr = tmpJson.optJSONArray("tableStatus") ?: return
        // seatId->0..3 index
        val idx = seatId - 1
        if (idx < 0 || idx >= arr.length()) return
        val seatObj = arr.getJSONObject(idx)

        seatObj.put("userID", userId)
        seatObj.put("seatStatus", "OCCUPIED")
        seatObj.put("endTime", duration ?: "1시간")

        arr.put(idx, seatObj)
    }

    /**
     * 이미 다른 테이블에서 좌석 이용중인지 체크
     */
    private fun checkUserAlreadyHasSeat(list: JSONArray, userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false
        for (i in 0 until list.length()) {
            val tableObj = list.getJSONObject(i)
            val contentJson = tableObj.optJSONObject("content") ?: continue
            val arr = contentJson.optJSONArray("tableStatus") ?: continue
            for (j in 0 until arr.length()) {
                val seatObj = arr.getJSONObject(j)
                if (seatObj.optString("userID", "") == userId) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 현재 table_tmp.json 에 이미 userId가 등록된 좌석이 있는지
     */
    private fun checkUserAlreadyInThisTemp(tmpJson: JSONObject?, userId: String): Boolean {
        if (tmpJson == null) return false
        val arr = tmpJson.optJSONArray("tableStatus") ?: return false
        for (i in 0 until arr.length()) {
            val seatObj = arr.getJSONObject(i)
            if (seatObj.optString("userID","") == userId) {
                return true
            }
        }
        return false
    }

    // --------------------------
    // SeatLayoutString, CustomTitle
    // --------------------------
    private fun buildSeatLayoutString(contentJson: JSONObject): String {
        val arr = contentJson.optJSONArray("tableStatus") ?: return "/AA/AA/__/"
        val seatMap = Array(2){ Array(2){ 'A' } }
        for (i in 0 until arr.length()) {
            val seatObj = arr.getJSONObject(i)
            val r = seatObj.optInt("row",1)
            val c = seatObj.optInt("col",1)
            val st= seatObj.optString("seatStatus","EMPTY")
            val ch= when(st) {
                "EMPTY"-> 'A'
                "ITEM"-> 'R'
                "OCCUPIED"-> 'U'
                else-> 'A'
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
            val endT= seatObj.optString("endTime","")
            if (endT.isNotBlank() && endT!="null") {
                val seatIdx = (r-1)*2 + (c-1)
                val ci = seatIdxToCustomIndex(seatIdx)
                if(ci in result.indices) result[ci] = endT
            }
        }
        return result
    }

    // --------------------------
    // 자잘한 도우미들
    // --------------------------
    private fun disableAllSeats(sbv: SeatBookView) {
        for (id in 1..4) {
            val seatView = sbv.getSeatView(id)
            seatView.setBackgroundResource(R.drawable.seat_disabled)
            seatView.setTag(R.id.tag_disabled,true)
        }
    }

    private fun disableOtherTables(exceptTableId:Int?) {
        val count = horizontalLayout.childCount
        for (i in 0 until count) {
            val sbv = horizontalLayout.getChildAt(i) as SeatBookView
            val tId = sbv.tag as? Int ?: -1
            if (tId != exceptTableId) {
                disableAllSeats(sbv)
            }
        }
    }

    private fun enableOtherTables() {
        seatBookViewsMap.forEach { (tId, sbv) ->
            val hasPerson = tableHasPersonMap[tId] ?: false
            if (hasPerson) {
                disableAllSeats(sbv)
            } else {
                enableAllSeatsButOccupied(sbv)
            }
        }
    }

    private fun enableAllSeatsButOccupied(sbv: SeatBookView) {
        for (id in 1..4) {
            val seatView = sbv.getSeatView(id)
            val status = seatView.tag as? Int ?: 0 // 1=AVAILABLE,2=BOOKED,3=RESERVED
            if (status == 1) {
                seatView.setBackgroundResource(R.drawable.seat_available)
                seatView.setTag(R.id.tag_disabled,false)
            }
        }
    }

    private fun calcSeatName(tableId: Int, seatId: Int): String {
        val letter = ('A'.code + (tableId -1)).toChar()
        val row = if(seatId<=2) 1 else 2
        val col = if(seatId%2==1) 1 else 2
        val seatIndex = (row-1)*2 + col
        return "$letter$seatIndex"
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}


