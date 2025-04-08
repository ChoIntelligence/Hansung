package com.hsu.table.reservation

import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentPersonalReservationBinding
import dev.jahidhasanco.seatbookview.SeatBookView
import dev.jahidhasanco.seatbookview.SeatClickListener

enum class LockType {
    FULL,      // 예약/예약대기 좌석 2개 이상 → 전체 테이블 비활성화
    DIAGONAL,  // 예약/예약대기 좌석 1개 → 대각선 자리만 선택 가능
    NONE       // 예약/예약대기 좌석 없음 → 전체 선택 가능
}

data class TableLockInfo(
    val lockType: LockType,
    val allowedSeat: Int? = null // DIAGONAL일 경우, 허용되는 대각선 좌석 (0~3)
)

@OptIn(ExperimentalStdlibApi::class)
class PersonalReservationFragment : Fragment() {

    private var _binding: FragmentPersonalReservationBinding? = null
    private val binding get() = _binding!!

    private lateinit var tableLockInfoMap: Map<Int, TableLockInfo>
    private lateinit var personalEndTimeMap: Map<Int, String>
    private lateinit var groupEndTimeMap: Map<String, String>

    private fun loadSeatLayoutFromAsset(): String {
        return requireContext().assets.open("seat_layout.txt")
            .bufferedReader().use { it.readText() }
    }

    private fun loadPersonalEndTimeMapFromAsset(): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        try {
            requireContext().assets.open("end_time_personal.txt")
                .bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split(":")
                        if (parts.size >= 2) {
                            val seatId = parts[0].trim().toIntOrNull()
                            val endTime = parts.drop(1).joinToString(":").trim()
                            if (seatId != null) {
                                map[seatId] = endTime
                                Log.d("EndTimeData", "seatId: $seatId -> endTime: $endTime")
                            }
                        }
                    }
                }
        } catch (e: Exception) { e.printStackTrace() }
        return map
    }

    private fun loadGroupEndTimeMapFromAsset(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            requireContext().assets.open("end_time_group.txt")
                .bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split(":")
                        if (parts.size >= 2) {
                            val tableLetter = parts[0].trim().uppercase()
                            val endTime = parts.drop(1).joinToString(":").trim()
                            if (tableLetter.isNotEmpty()) {
                                map[tableLetter] = endTime
                                Log.d("GroupEndTimeData", "Table $tableLetter -> endTime: $endTime")
                            }
                        }
                    }
                }
        } catch (e: Exception) { e.printStackTrace() }
        return map
    }

    private fun diagonalOf(pos: Int): Int {
        return when (pos) {
            0 -> 3
            1 -> 2
            2 -> 1
            3 -> 0
            else -> -1
        }
    }

    private fun computeTableLockInfoMap(seatLayout: String): Map<Int, TableLockInfo> {
        val tableMap = mutableMapOf<Int, TableLockInfo>()
        val tableLines = seatLayout.lines().filter { it.isNotBlank() }
        for ((index, tableLine) in tableLines.withIndex()) {
            val parts = tableLine.split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts[0].length >= 2 && parts[1].length >= 2) {
                val grid = listOf(
                    listOf(parts[0][0], parts[0][1]),
                    listOf(parts[1][0], parts[1][1])
                )
                val reservedPositions = mutableListOf<Int>()
                for (i in 0 until 2) {
                    for (j in 0 until 2) {
                        val pos = i * 2 + j
                        if (grid[i][j] == 'U' || grid[i][j] == 'R') {
                            reservedPositions.add(pos)
                        }
                    }
                }
                tableMap[index] = when {
                    reservedPositions.size >= 2 -> TableLockInfo(LockType.FULL, null)
                    reservedPositions.size == 1 -> {
                        val reservedPos = reservedPositions.first()
                        val allowedSeat = when (reservedPos) {
                            0 -> 3
                            1 -> 2
                            2 -> 1
                            3 -> 0
                            else -> -1
                        }
                        TableLockInfo(LockType.DIAGONAL, allowedSeat)
                    }
                    else -> TableLockInfo(LockType.NONE, null)
                }
            } else {
                tableMap[index] = TableLockInfo(LockType.NONE, null)
            }
            Log.d("TableLockInfo", "Table $index -> ${tableMap[index]}")
        }
        return tableMap
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonalReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val seatBookView: SeatBookView = binding.seatBookView
        val seatLayout = loadSeatLayoutFromAsset()
        Log.d("SeatLayout", "Seat layout:\n$seatLayout")
        tableLockInfoMap = computeTableLockInfoMap(seatLayout)
        personalEndTimeMap = loadPersonalEndTimeMapFromAsset()
        groupEndTimeMap = loadGroupEndTimeMapFromAsset()

        seatBookView.setSeatsLayoutString(seatLayout)
            .setSelectSeatLimit(1)
        seatBookView.show()

        // 개인 예약 오버레이 (텍스트박스 높이 128dp, y 좌표는 그대로)
        val totalRows = 20
        val overlayContainer = binding.seatContainer.findViewById<FrameLayout>(R.id.overlayContainer)
        overlayContainer.post {
            val containerWidth = overlayContainer.width
            val xPos = containerWidth / 2
            Log.d("SeatOverlay", "overlayContainer width: $containerWidth, center x position: $xPos")
            for (row in 0 until totalRows) {
                val tableIndex = row / 2
                val desiredY = if (row % 2 == 0) tableIndex * 384 + 32 else tableIndex * 384 + 160
                val yPos = desiredY
                val seatIdOdd = row * 2 + 1
                val seatIdEven = row * 2 + 2
                val endTimeData = personalEndTimeMap[seatIdOdd] ?: personalEndTimeMap[seatIdEven]
                val displayText = if (!endTimeData.isNullOrBlank())
                    "                     개인 이용 종료 시간 : $endTimeData" else ""
                val textBoxHeight = dpToPx(128)
                val endTimeText = TextView(requireContext()).apply {
                    textSize = 14f
                    setTextColor(resources.getColor(android.R.color.black, null))
                    text = displayText
                }
                val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, textBoxHeight)
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.topMargin = yPos
                endTimeText.layoutParams = params
                overlayContainer.addView(endTimeText)
                Log.d("SeatOverlay", "Row $row: Personal TextView added at (x=center, y=$yPos) with text '$displayText'")
            }

            // 단체 예약 오버레이: 각 테이블별 y 위치는 384 * tableIndex + 124
            groupEndTimeMap.forEach { (tableLetter, groupTime) ->
                val tableIndex = tableLetter[0] - 'A'  // 예: A -> 0, B -> 1, etc.
                val desiredGroupY = 384 * tableIndex + 124
                val groupYPos = desiredGroupY
                val groupDisplayText = "                     단체 이용 종료 시간 : $groupTime"
                val groupTextBoxHeight = dpToPx(128)
                val groupTextView = TextView(requireContext()).apply {
                    textSize = 14f
                    setTextColor(resources.getColor(android.R.color.black, null))
                    text = groupDisplayText
                }
                val groupParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, groupTextBoxHeight)
                groupParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                groupParams.topMargin = groupYPos
                groupTextView.layoutParams = groupParams
                overlayContainer.addView(groupTextView)
                Log.d("SeatOverlay", "Group Table $tableLetter: TextView added at (x=center, y=$groupYPos) with text '$groupDisplayText'")
            }
        }

        // 초기 회색 처리: 모든 좌석을 순회하여, available 상태인 좌석 중 LockType 조건에 따라 회색 처리 및 "비활성" 태그 설정
        val seatCount = seatLayout.count { it in listOf('A', 'U', 'R', 'S') }
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
                            seatView.setTag(R.id.tag_disabled, true)
                        }
                        LockType.DIAGONAL -> {
                            if (localPos != tableInfo.allowedSeat) {
                                seatView.setBackgroundResource(R.drawable.seat_disabled)
                                seatView.setTag(R.id.tag_disabled, true)
                            }
                        }
                        LockType.NONE -> { /* 정상 상태 */ }
                    }
                }
            }
        }

        // 좌석 클릭 이벤트 리스너: 유효한 좌석 선택 시 바텀 팝업 띄움; 비활성 좌석은 선택 해제 처리
        seatBookView.setSeatClickListener(object : SeatClickListener {
            override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                // 선택된 좌석 리스트에서 첫 번째 좌석만 사용
                val seatId = selectedIdList.firstOrNull()
                if (seatId != null) {
                    val seatView = seatBookView.getSeatView(seatId) as? TextView
                    // 비활성 좌석이면 팝업 노출 금지 및 좌석 선택 해제 및 회색 재적용
                    if (seatView != null && seatView.getTag(R.id.tag_disabled) as? Boolean == true) {
                        seatView.post {
                            seatView.performClick()
                            seatView.setBackgroundResource(R.drawable.seat_disabled)
                            seatView.setTag(R.id.tag_disabled, true)
                        }
                        return
                    }
                    // 유효한 좌석인 경우 바텀 팝업 띄움
                    val bottomSheet = ReservationBottomSheetDialogFragment(seatId, object : ReservationBottomSheetDialogFragment.ReservationListener {
                        override fun onReservationConfirmed(duration: String, seatId: Int) {
                            Toast.makeText(requireContext(), "예약 완료: $duration", Toast.LENGTH_SHORT).show()
                            // 예약 완료 후 추가 처리 가능
                        }
                        override fun onReservationCancelled(seatId: Int) {
                            // 선택된 좌석 해제 처리
                            val seatView = seatBookView.getSeatView(seatId) as? TextView
                            seatView?.post {
                                seatView.performClick()
                                seatView.setBackgroundResource(R.drawable.seat_available)
                            }
                            Toast.makeText(requireContext(), "예약 취소", Toast.LENGTH_SHORT).show()
                        }
                    })
                    bottomSheet.show(childFragmentManager, "ReservationBottomSheet")
                }
            }

            override fun onBookedSeatClick(view: View) {
                Toast.makeText(requireContext(), "이미 사용 중인 좌석입니다.", Toast.LENGTH_SHORT).show()
            }

            override fun onReservedSeatClick(view: View) {
                Toast.makeText(requireContext(), "예약 대기 중인 좌석입니다.", Toast.LENGTH_SHORT).show()
            }
        })

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
