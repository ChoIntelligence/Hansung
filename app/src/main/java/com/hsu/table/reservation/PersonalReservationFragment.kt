package com.hsu.table.reservation

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentPersonalReservationBinding
import dev.jahidhasanco.seatbookview.SeatBookView
import dev.jahidhasanco.seatbookview.SeatClickListener
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 한 번의 요청(/all_tables)으로
 * [
 *   { "table_id": 1, "content": { "timestamp":..., "type":"PERSONAL", "tableStatus":[...] } },
 *   { "table_id": 2, "content": { ... } },
 *   ...
 * ]
 * 수신.
 * 각각에 대해 SeatBookView를 생성 -> horizontalContainer (수평) 에 addView
 */
class PersonalReservationFragment : Fragment() {

    private var _binding: FragmentPersonalReservationBinding? = null
    private val binding get() = _binding!!

    // OkHttp 클라이언트 (실제로는 Singleton/DI 권장)
    private val client = OkHttpClient()
    private var userId: String? = null

    // PersonalReservationFragment.kt
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 넘어온 user_id 받기
        userId = arguments?.getString("user_id")
        println(userId)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 수평 컨테이너 참조
    private val horizontalLayout: LinearLayout by lazy {
        binding.horizontalContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // (A) 서버에서 json 목록 가져옴 (IO스레드)
        CoroutineScope(Dispatchers.IO).launch {
            val list = fetchAllTables() // JSONArray?

            // JSONArray인지 체크
            if (list == null || list.length() == 0) {
                Log.e("PersonalReservation", "No table data from server (null or length=0)")
                return@launch
            }

            // (B) "이미 이 userId로 예약된 좌석이 있는지" 먼저 체크
            val alreadyReserved = checkUserAlreadyHasSeat(list, userId)
            if (alreadyReserved) {
                // 메인스레드에서 토스트 & 홈으로
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "1인 당 하나의 좌석만 이용 가능합니다.", Toast.LENGTH_SHORT).show()

                    // 홈으로 복귀 (현재 프래그먼트를 스택에서 제거)
                    parentFragmentManager.popBackStack()
                    // or requireActivity().onBackPressed() 등
                }
                return@launch
            }

            // (C) 이미 좌석이 없다면 -> SeatBookView들 생성
            for (i in 0 until list.length()) {
                val tableObj = list.getJSONObject(i)
                val tableId = tableObj.optInt("table_id", -1)
                val contentJson = tableObj.optJSONObject("content") ?: JSONObject()

                val seatBookView = createSeatBookViewFor(tableId, contentJson)

                // UI 추가는 메인스레드
                withContext(Dispatchers.Main) {
                    binding.horizontalContainer.addView(seatBookView)
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
                Log.e("PersonalReservation", "fetchAllTables() => code=${response.code}")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            JSONArray(bodyStr)
        } catch(e: Exception) {
            Log.e("PersonalReservation", "fetchAllTables exception", e)
            null
        }
    }

    /**
     * (2) userId와 동일한 "userID"가 좌석 어디에도 존재하는지 확인
     */
    private fun checkUserAlreadyHasSeat(list: JSONArray, userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false

        // list = [ {table_id:..., content: { tableStatus:[ {userID:"xxx"}, ... ]}}, ... ]
        for (i in 0 until list.length()) {
            val tableObj = list.getJSONObject(i)
            val contentJson = tableObj.optJSONObject("content") ?: continue
            val tableStatusArr = contentJson.optJSONArray("tableStatus") ?: continue

            for (j in 0 until tableStatusArr.length()) {
                val seatObj = tableStatusArr.getJSONObject(j)
                val seatUserId = seatObj.optString("userID", "")
                if (seatUserId == userId) {
                    // 동일 userId 존재 -> 이미 예약중
                    return true
                }
            }
        }
        return false
    }


    private fun createSeatBookViewFor(tableId: Int, contentJson: JSONObject): SeatBookView {
        // 2-1) seatLayoutString
        val seatLayout = buildSeatLayoutString(contentJson)

        println(seatLayout)
        // 2-2) customTitleList
// 예: tableId = 3 이고, contentJson 은 table_3.json에 해당하는 JSONObject
        val customTitles = buildCustomTitleList(tableId, contentJson)

        println(customTitles)
        // 2-3) seatBookView 생성
        val seatBookView = SeatBookView(requireContext())

        val dp16 = dpToPx(16)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp16, 0, dp16, 0)
        seatBookView.layoutParams = params

        seatBookView
            .setSeatsLayoutString(seatLayout)
            .setSelectSeatLimit(1)
            .isCustomTitle(true)
            .setCustomTitle(customTitles)
            .setSeatLayoutPadding(8)
            .setSeatSizeBySeatsColumnAndLayoutWidth(10, -1)

        seatBookView.setSeatTextSize(30f)

        seatBookView.setAvailableSeatsBackground(R.drawable.seat_available)
        seatBookView.setBookedSeatsBackground(R.drawable.seat_booked)
        seatBookView.setReservedSeatsBackground(R.drawable.seat_reserved)
        seatBookView.setSelectedSeatsBackground(R.drawable.seat_selected)


        seatBookView.show()

        // 2-4) LockType 계산
        val lockInfo = computeLockType(contentJson)
        // 2-5) 초기 회색 처리
        lockSeatsInitially(seatBookView, lockInfo)

        // 2-6) 클릭 리스너
        seatBookView.setSeatClickListener(object : SeatClickListener {
            // PersonalReservationFragment.kt (발췌)
            override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                val seatId = selectedIdList.firstOrNull() ?: return

                // 비활성 좌석인지 재확인
                if (view.getTag(R.id.tag_disabled) as? Boolean == true) {
                    // seat 재활성화(해제)
                    view.post {
                        view.performClick()
                        view.setBackgroundResource(R.drawable.seat_disabled)
                        view.setTag(R.id.tag_disabled, true)
                    }
                    return
                }

                // 1) BottomSheet 생성
                val bottomSheet = DurationBottomSheetDialogFragment(
                    durationSelectListener = { selectedDuration ->
                        // 사용자가 "확인" 눌렀을 때 선택된 시간
                        // 2) 서버로 전송 (seatId, tableId, userId, duration 등)
                        println("user=${userId} table=$tableId seat=$seatId time=$selectedDuration")
                        sendReservationToServer(tableId, seatId, userId, selectedDuration)

                        val letter = ('A'.code + (tableId - 1)).toChar()

                        Toast.makeText(requireContext(), "[예약 완료] ${letter}${seatId}번 좌석 이용 종료 시간 : $selectedDuration", Toast.LENGTH_LONG).show()
                        parentFragmentManager.popBackStack()
                    },
                    onCancelOrDismiss = {
                        // 바텀시트가 닫힐 때 → 좌석 해제 로직
                        view.performClick()
                        view.setBackgroundResource(R.drawable.seat_available)
                        view.setTag(R.id.tag_disabled, false)
                    }
                )
                bottomSheet.show(childFragmentManager, "DurationBottomSheet")
            }


            override fun onBookedSeatClick(view: View) {
                Toast.makeText(requireContext(), "이미 사용 중인 좌석", Toast.LENGTH_SHORT).show()
            }

            override fun onReservedSeatClick(view: View) {
                Toast.makeText(requireContext(), "예약 대기 좌석", Toast.LENGTH_SHORT).show()
            }
        })

        return seatBookView
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * seatStatus: "EMPTY"->'A', "ITEM"->'R', "OCCUPIED"->'U'
     * 4좌석 => "/XX/XX/__/"
     */
    private fun buildSeatLayoutString(contentJson: JSONObject): String {
        val tableStatusArr = contentJson.optJSONArray("tableStatus") ?: return "/AA/AA/__/"
        // 2행 2열
        val seatMap = Array(2) { Array(2) { 'A' } }

        for (i in 0 until tableStatusArr.length()) {
            val seatObj = tableStatusArr.getJSONObject(i)
            val r = seatObj.optInt("row", 1)  //1~2
            val c = seatObj.optInt("col", 1)  //1~2
            val st = seatObj.optString("seatStatus","EMPTY")
            val ch = when(st) {
                "EMPTY" -> 'A'
                "ITEM" -> 'R'
                "OCCUPIED" -> 'U'
                else -> 'A'
            }
            seatMap[r-1][c-1] = ch
        }
        val row0 = "${seatMap[0][0]}${seatMap[0][1]}"
        val row1 = "${seatMap[1][0]}${seatMap[1][1]}"
        return "/$row0/$row1/__/"
    }

    /**
     * 2행x2열 좌석 → customTitle은 6개의 리스트.
     * index = [0] "/" 고정, [1] seat1, [2] seat2, [3] "/" 고정, [4] seat3, [5] seat4
     * - seatIdx=0 -> seat1
     * - seatIdx=1 -> seat2
     * - seatIdx=2 -> seat3
     * - seatIdx=3 -> seat4
     *
     * tableId=1 -> letter='A'
     *   => seat1 default="A1", seat2="A2", seat3="A3", seat4="A4"
     * endTime이 존재하면 그 좌석에만 덮어쓴다.
     */
    private fun buildCustomTitleList(tableId: Int, contentJson: JSONObject): List<String> {
        // 1) tableId => 'A', 'B', ...
        val letter = ('A'.code + (tableId - 1)).toChar()

        // 2) 6개 기본값 ["/", "", "", "/", "", ""]
        val result = mutableListOf("/", "", "", "/", "", "")

        // 도우미 함수: seatIdx -> customIndex
        fun seatIdxToCustomIndex(seatIdx: Int) = when(seatIdx) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 5
            else -> -1
        }

        // 3) 기본 레이블: seatIdx=0..3
        //    seat1 -> "A1", seat2 -> "A2", ...
        for (seatIdx in 0..3) {
            val defaultLabel = "$letter${seatIdx+1}"
            val customIndex = seatIdxToCustomIndex(seatIdx)
            if (customIndex in result.indices) {
                result[customIndex] = defaultLabel
            }
        }

        // 4) JSON tableStatus -> endTime이 존재하면 덮어쓰는 로직
        val tableStatusArr = contentJson.optJSONArray("tableStatus") ?: return result

        for (i in 0 until tableStatusArr.length()) {
            val seatObj = tableStatusArr.getJSONObject(i)
            val r = seatObj.optInt("row", 1)
            val c = seatObj.optInt("col", 1)
            var endT = seatObj.optString("endTime", "")

            // "null" 문자열로 오는 경우 -> 빈 문자열 처리
            if (endT == "null") {
                endT = ""
            }

            // 좌표 -> seatIdx
            val seatIdx = (r - 1)*2 + (c - 1)
            val customIndex = seatIdxToCustomIndex(seatIdx)

            // endT가 실제로 비어있지 않다면(공백 아님, "null" 아님)
            if (endT.isNotBlank()) {
                // 해당 좌석의 제목을 endTime으로 덮어쓰기
                if (customIndex in result.indices) {
                    result[customIndex] = endT
                }
            }
        }

        return result
    }

    /**
     * type=GROUP => FULL
     * type=PERSONAL => R/U 개수 (>=2->FULL,==1->DIAGONAL,==0->NONE)
     */
    private fun computeLockType(contentJson: JSONObject): LockTypeInfo {
        val type = contentJson.optString("type","PERSONAL")
        val arr = contentJson.optJSONArray("tableStatus") ?: return LockTypeInfo(LockType.NONE)
        if (type=="GROUP") {
            return LockTypeInfo(LockType.FULL,null)
        } else {
            // PERSONAL
            var reservedCount=0
            var reservedIndex=-1
            for (i in 0 until arr.length()) {
                val seatObj = arr.getJSONObject(i)
                val st = seatObj.optString("seatStatus","EMPTY")
                if (st=="ITEM"||st=="OCCUPIED") {
                    reservedCount++
                    if (reservedIndex<0) {
                        // row,col -> 0..3
                        val r=seatObj.optInt("row",1)
                        val c=seatObj.optInt("col",1)
                        reservedIndex=(r-1)*2+(c-1)
                    }
                }
            }
            return when {
                reservedCount>=2 -> LockTypeInfo(LockType.FULL)
                reservedCount==1 -> {
                    // DIAGONAL
                    val allowed = diagonalAllowedSeat(reservedIndex)
                    LockTypeInfo(LockType.DIAGONAL, allowed)
                }
                else -> LockTypeInfo(LockType.NONE)
            }
        }
    }

    private fun diagonalAllowedSeat(index: Int): Int {
        return when(index) {
            0->3
            1->2
            2->1
            3->0
            else->-1
        }
    }

    /**
     * seatBookView 는 내부적으로 seatViewList(4개)가 있으나
     *  seatBookView.getSeatView(id) 로 접근 (id=1..4)
     *  STATUS_AVAILABLE==1 인 것만 잠글 수 있음
     */
    private fun lockSeatsInitially(sbv: SeatBookView, lockInfo: LockTypeInfo) {
        for(id in 1..4) {
            val seatView=sbv.getSeatView(id)
            val status = seatView.tag as? Int ?: 0 // 1=available,2=booked,3=reserved
            if(status!=1) continue

            // lock 적용
            when(lockInfo.lockType) {
                LockType.FULL->{
                    seatView.setBackgroundResource(R.drawable.seat_disabled)
                    seatView.setTag(R.id.tag_disabled,true)
                }
                LockType.DIAGONAL->{
                    val localPos=(id-1)
                    if(localPos!=lockInfo.allowedSeat) {
                        seatView.setBackgroundResource(R.drawable.seat_disabled)
                        seatView.setTag(R.id.tag_disabled,true)
                    }
                }
                LockType.NONE->{
                    // do nothing
                }
            }
        }
    }

    private fun sendReservationToServer(
        tableId: Int,
        seatId: Int,
        userId: String?,
        duration: String
    ) {
        // 예: ${IP}:5002/reserve
        // POST JSON body: { "tableId":..., "seatId":..., "userId":..., "duration":"..." }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "${IP}:5003/reserve"
                val jsonBody = JSONObject()
                    .put("tableId", tableId)
                    .put("seatId", seatId)
                    .put("userId", userId ?: "")
                    .put("duration", duration)

                val body = jsonBody.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    // 예약 성공
                    Log.d("PersonalReservation", "Reservation success for table=$tableId seat=$seatId")
                } else {
                    Log.e("PersonalReservation", "Reservation failed: ${response.code}")
                }
            } catch(e: Exception) {
                Log.e("PersonalReservation", "sendReservationToServer exception", e)
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Lock 관련
data class LockTypeInfo(
    val lockType: LockType,
    val allowedSeat: Int?=null
)

enum class LockType {
    FULL,
    DIAGONAL,
    NONE
}

data class TableLockInfo(
    val lockType: LockType,
    val allowedSeat: Int? = null // DIAGONAL 일 때 대각선 허용 좌석(0~3)
)