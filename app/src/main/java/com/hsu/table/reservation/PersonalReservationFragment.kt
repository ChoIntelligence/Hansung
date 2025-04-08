package com.hsu.table.reservation

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.jahidhasanco.seatbookview.SeatBookView
import dev.jahidhasanco.seatbookview.SeatClickListener
import com.hsu.table.reservation.databinding.FragmentPersonalReservationBinding

enum class LockType {
    FULL,      // 2명 이상 예약/예약대기 → 전체 테이블 비활성화
    DIAGONAL,  // 1명 예약/예약대기 → 대각선 좌석만 선택 가능
    NONE       // 예약/예약대기 없음 → 전체 선택 가능
}

data class TableLockInfo(
    val lockType: LockType,
    val allowedSeat: Int? = null // DIAGONAL일 경우, 허용되는 대각선 좌석 (0~3)
)

class PersonalReservationFragment : Fragment() {

    private var _binding: FragmentPersonalReservationBinding? = null
    private val binding get() = _binding!!

    // 각 테이블(0번부터)의 잠금 정보를 저장하는 Map
    private lateinit var tableLockInfoMap: Map<Int, TableLockInfo>
    // 개인 예약 종료 시간 데이터를 담는 Map (key: 좌석 id, value: 종료 시간 문자열)
    private lateinit var personalEndTimeMap: Map<Int, String>

    /**
     * assets 폴더에서 좌석 레이아웃 문자열을 불러오는 함수.
     * 파일 경로: app/src/main/assets/seat_layout.txt
     */
    private fun loadSeatLayoutFromAsset(): String {
        return requireContext().assets.open("seat_layout.txt")
            .bufferedReader().use { it.readText() }
    }

    /**
     * 주어진 좌석 위치(pos: 0~3)에서 대각선 위치를 반환하는 함수.
     * 0 → 3, 1 → 2, 2 → 1, 3 → 0
     */
    private fun diagonalOf(pos: Int): Int {
        return when (pos) {
            0 -> 3
            1 -> 2
            2 -> 1
            3 -> 0
            else -> -1
        }
    }

    /**
     * 각 줄(테이블)을 파싱하여 테이블 잠금 정보를 계산합니다.
     *
     * 각 테이블은 "/"로 시작하는 한 줄로 표현되며,
     * 첫 두 행은 각각 2개의 좌석 상태 문자("A", "U", "R", 또는 "S")를 포함한다고 가정합니다.
     *
     * - 예약/예약대기('U' 또는 'R') 좌석 수가 2 이상이면 LockType.FULL
     * - 좌석 수가 1이면 LockType.DIAGONAL이며, 비활성 좌석의 대각선 좌석만 선택 가능
     * - 좌석 수가 0이면 LockType.NONE
     */
    private fun computeTableLockInfoMap(seatLayout: String): Map<Int, TableLockInfo> {
        val tableMap = mutableMapOf<Int, TableLockInfo>()
        val tableLines = seatLayout.lines().filter { it.isNotBlank() }
        for ((index, tableLine) in tableLines.withIndex()) {
            // 예: "/AA/AU/__/" → 분리 후 ["AA", "AU", "__"]
            val parts = tableLine.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts[0].length >= 2 && parts[1].length >= 2) {
                val grid = listOf(
                    listOf(parts[0][0], parts[0][1]),
                    listOf(parts[1][0], parts[1][1])
                )
                var nonAvailableCount = 0
                var nonAvailablePos = -1
                for (i in 0 until 2) {
                    for (j in 0 until 2) {
                        val pos = i * 2 + j
                        if (grid[i][j] == 'U' || grid[i][j] == 'R') {
                            nonAvailableCount++
                            nonAvailablePos = pos
                        }
                    }
                }
                when {
                    nonAvailableCount >= 2 -> {
                        tableMap[index] = TableLockInfo(LockType.FULL, null)
                    }
                    nonAvailableCount == 1 -> {
                        tableMap[index] = TableLockInfo(LockType.DIAGONAL, diagonalOf(nonAvailablePos))
                    }
                    else -> {
                        tableMap[index] = TableLockInfo(LockType.NONE, null)
                    }
                }
            } else {
                tableMap[index] = TableLockInfo(LockType.NONE, null)
            }
        }
        return tableMap
    }

    /**
     * 개인 예약 종료 시간 더미 데이터를 생성하는 함수.
     * 실제로는 서버나 로컬 DB 등에서 데이터를 받아와야 함.
     * 여기서는 좌석 id(1부터)를 키로, 홀수면 "종료 시간 : 10시 00분", 짝수면 "종료 시간 : 10시 15분"으로 설정.
     */
    private fun generatePersonalEndTimeMap(seatLayout: String): Map<Int, String> {
        val seatCount = seatLayout.count { it == 'A' || it == 'U' || it == 'R' || it == 'S' }
        val map = mutableMapOf<Int, String>()
        for (id in 1..seatCount) {
            map[id] = if (id % 2 == 1) "종료 시간 : 10시 00분" else "종료 시간 : 10시 15분"
        }
        return map
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val seatBookView: SeatBookView = binding.seatBookView

        // 좌석 레이아웃 문자열 불러오기
        val seatLayout = loadSeatLayoutFromAsset()
        // 테이블 잠금 정보 계산
        tableLockInfoMap = computeTableLockInfoMap(seatLayout)
        // 개인 예약 종료 시간 데이터를 생성 (실제 데이터는 외부 소스에서 받아와야 함)
        personalEndTimeMap = generatePersonalEndTimeMap(seatLayout)

        // 좌석 레이아웃 적용 (개인 예약은 한 좌석만 선택)
        seatBookView.setSeatsLayoutString(seatLayout)
            .setSelectSeatLimit(1)

        // 좌석 배치 화면 표시
        seatBookView.show()

        // 개인 예약 모드: 각 가로 행에 종료 시간 텍스트뷰 추가
        // SeatBookView의 최상위 컨테이너(레이아웃)는 내부에 한 개의 자식 컨테이너를 가짐.
        // 이 자식 컨테이너의 각 자식은 각 행(가로 LinearLayout)입니다.
        val layoutSeat = seatBookView.getChildAt(0) as? ViewGroup
        layoutSeat?.let { parent ->
            for (i in 0 until parent.childCount) {
                val rowLayout = parent.getChildAt(i) as? LinearLayout
                rowLayout?.let { row ->
                    // 각 행에 새로운 TextView 추가 (오른쪽 정렬)
                    val endTimeText = TextView(requireContext())
                    endTimeText.textSize = 14f
                    endTimeText.setTextColor(resources.getColor(android.R.color.black, null))
                    // 행에 포함된 좌석 중 첫 번째 좌석의 id를 기준으로 종료 시간 결정
                    if (row.childCount > 0) {
                        val firstSeat = row.getChildAt(0)
                        val seatId = firstSeat.id
                        endTimeText.text = personalEndTimeMap[seatId] ?: ""
                    }
                    // LayoutParams: WRAP_CONTENT, 정렬을 오른쪽에 위치시키기 위해 weight 0, marginStart 추가
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginStart = 8
                    lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    endTimeText.layoutParams = lp
                    row.addView(endTimeText)
                }
            }
        }

        // 좌석 클릭 이벤트 리스너 설정
        seatBookView.setSeatClickListener(object : SeatClickListener {
            override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                // 각 선택된 좌석에 대해 테이블 번호와 로컬 좌석 위치 계산
                for (seatId in selectedIdList) {
                    val tableIndex = (seatId - 1) / 4
                    val localPos = (seatId - 1) % 4
                    val tableInfo = tableLockInfoMap[tableIndex]
                    if (tableInfo != null) {
                        when (tableInfo.lockType) {
                            LockType.FULL -> {
                                Toast.makeText(
                                    requireContext(),
                                    "테이블 ${tableIndex + 1}은 이미 2명 이상 예약되어 선택할 수 없습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                // 좌석 선택 취소: 해당 좌석 뷰를 가져와서 다시 performClick() 호출
                                val seatView = seatBookView.getSeatView(seatId) as? TextView
                                seatView?.post {
                                    seatView.performClick()
                                    seatView.setBackgroundResource(R.drawable.seat_disabled)
                                }
                            }
                            LockType.DIAGONAL -> {
                                if (localPos != tableInfo.allowedSeat) {
                                    Toast.makeText(
                                        requireContext(),
                                        "테이블 ${tableIndex + 1}은 대각선 좌석만 예약 가능합니다.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    val seatView = seatBookView.getSeatView(seatId) as? TextView
                                    seatView?.post {
                                        seatView.performClick()
                                        seatView.setBackgroundResource(R.drawable.seat_disabled)
                                    }
                                }
                            }
                            LockType.NONE -> {
                                // 별 문제 없음.
                            }
                        }
                    }
                }
            }

            override fun onBookedSeatClick(view: View) {
                Toast.makeText(requireContext(), "이미 사용 중인 좌석입니다.", Toast.LENGTH_SHORT).show()
            }

            override fun onReservedSeatClick(view: View) {
                Toast.makeText(requireContext(), "예약 대기 중인 좌석입니다.", Toast.LENGTH_SHORT).show()
            }
        })

        // 추가: 모든 좌석을 순회하여, available 상태이면서 해당 테이블의 조건에 따라 비활성(회색) 처리
        val seatCount = seatLayout.count { it == 'A' || it == 'U' || it == 'R' || it == 'S' }
        for (id in 1..seatCount) {
            val seatView = seatBookView.getSeatView(id) as? TextView
            if (seatView != null && (seatView.tag as? Int) == 1) { // STATUS_AVAILABLE == 1
                val tableIndex = (id - 1) / 4
                val localPos = (id - 1) % 4
                val tableInfo = tableLockInfoMap[tableIndex]
                if (tableInfo != null) {
                    when (tableInfo.lockType) {
                        LockType.FULL -> {
                            seatView.setBackgroundResource(R.drawable.seat_disabled)
                        }
                        LockType.DIAGONAL -> {
                            if (localPos != tableInfo.allowedSeat) {
                                seatView.setBackgroundResource(R.drawable.seat_disabled)
                            }
                        }
                        LockType.NONE -> {
                            // 아무것도 안 함.
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
