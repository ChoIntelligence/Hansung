package com.hsu.table.reservation

import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentUsageStatusBinding  // 만약 ViewBinding 사용 시
import dev.jahidhasanco.seatbookview.SeatBookView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class UsageStatusFragment : Fragment() {

    private var _binding: FragmentUsageStatusBinding? = null
    private val binding get() = _binding!!

    private val client = OkHttpClient()

    // 수평 레이아웃: SeatBookView들을 가로로 표시
    private val horizontalLayout: LinearLayout by lazy {
        binding.horizontalContainer
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUsageStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 서버에서 테이블 목록 받아와, SeatBookView 생성
        CoroutineScope(Dispatchers.IO).launch {
            val list = fetchAllTables()
            if (list == null || list.length() == 0) {
                Log.e("UsageStatusFragment", "No table data from server (null or length=0)")
                return@launch
            }

            // 각 table_id별로 SeatBookView 생성
            for (i in 0 until list.length()) {
                val tableObj = list.getJSONObject(i)
                val tableId = tableObj.optInt("table_id", -1)
                val contentJson = tableObj.optJSONObject("content") ?: JSONObject()

                // SeatBookView 생성
                val seatBookView = createSeatBookViewFor(tableId, contentJson)

                // 메인스레드에서 레이아웃 추가
                withContext(Dispatchers.Main) {
                    horizontalLayout.addView(seatBookView)
                }
            }
        }
    }

    // (1) /all_tables 요청 → JSONArray
    private fun fetchAllTables(): JSONArray? {
        return try {
            val url = "http://192.168.50.55:5002/all_tables"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("UsageStatusFragment", "fetchAllTables() => code=${response.code}")
                return null
            }
            val bodyStr = response.body?.string() ?: return null
            JSONArray(bodyStr)
        } catch (e: Exception) {
            Log.e("UsageStatusFragment", "fetchAllTables exception", e)
            null
        }
    }

    // (2) SeatBookView 구성
    private fun createSeatBookViewFor(tableId: Int, contentJson: JSONObject): SeatBookView {
        val seatLayout = buildSeatLayoutString(contentJson)
        val customTitles = buildCustomTitleList(tableId, contentJson)

        val sbv = SeatBookView(requireContext()).apply {
            setTag(tableId)

            // 레이아웃 파라미터 (간격)
            val dp16 = dpToPx(16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp16, 0, dp16, 0)
            }

            // 좌석 배치
            setSeatsLayoutString(seatLayout)
                .setSelectSeatLimit(4) // 사실 클릭 막을거라 큰 의미 없음
                .isCustomTitle(true)
                .setCustomTitle(customTitles)
                .setSeatLayoutPadding(8)
                .setSeatSizeBySeatsColumnAndLayoutWidth(15, -1)

            setSeatTextSize(30f)
            setAvailableSeatsBackground(R.drawable.seat_available)
            setBookedSeatsBackground(R.drawable.seat_booked)
            setReservedSeatsBackground(R.drawable.seat_reserved)
            setSelectedSeatsBackground(R.drawable.seat_available)

            show()
        }

        // 여기서는 "클릭 이벤트를 발생시키지 않도록"
        // 1) setSeatClickListener를 아예 등록 안 하거나
        // 2) 등록해도 모든 콜백에서 아무 동작 안 하도록

        // 아래는 "아무 것도 안 하는" 리스너 예시
        // sbv.setSeatClickListener(object : SeatClickListener {
        //     override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {}
        //     override fun onBookedSeatClick(view: View) {}
        //     override fun onReservedSeatClick(view: View) {}
        // })

        return sbv
    }

    // -------------------------
    // 좌석 레이아웃 관련
    // -------------------------
    private fun buildSeatLayoutString(contentJson: JSONObject): String {
        val tableStatusArr = contentJson.optJSONArray("tableStatus") ?: return "/AA/AA/__/"
        val seatMap = Array(2){ Array(2){ 'A' } }
        for (i in 0 until tableStatusArr.length()) {
            val seatObj = tableStatusArr.getJSONObject(i)
            val r = seatObj.optInt("row",1)
            val c = seatObj.optInt("col",1)
            val st = seatObj.optString("seatStatus","EMPTY")
            val ch = when(st) {
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
            if (endT=="null") endT=""
            if(endT.isNotBlank()) {
                val seatIdx = (r-1)*2 + (c-1)
                val ci = seatIdxToCustomIndex(seatIdx)
                if(ci in result.indices) {
                    result[ci] = endT
                }
            }
        }
        return result
    }

    private fun dpToPx(dp:Int): Int {
        return (dp*resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
