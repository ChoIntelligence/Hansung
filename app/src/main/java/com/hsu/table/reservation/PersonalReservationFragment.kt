package com.hsu.table.reservation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import dev.jahidhasanco.seatbookview.SeatBookView
import com.hsu.table.reservation.databinding.FragmentPersonalReservationBinding

class PersonalReservationFragment : Fragment() {

    private var _binding: FragmentPersonalReservationBinding? = null
    private val binding get() = _binding!!

    /**
     * 10개의 테이블을 표현하는 seat layout 문자열 생성
     * 각 테이블은 2행×2열 (즉 4석)로 구성되고, 테이블 간 구분을 위해 빈 행("/__")을 추가합니다.
     * 예시로, 한 테이블에 대해:
     *   "/AA"  -> 첫 번째 행, 2석 (A: 사용 가능 좌석 표시)
     *   "/AA"  -> 두 번째 행
     *   "/__"  -> 테이블 구분용 빈 행
     * 이를 10번 반복합니다.
     */
    private fun generateSeatLayoutString(): String {
        val builder = StringBuilder()
        for (i in 1..10) {
            builder.append("/AA\n")  // 첫 번째 행 (좌석 2개)
            builder.append("/AA\n")  // 두 번째 행 (좌석 2개)
            builder.append("/__\n")  // 테이블 구분용 빈 행
        }
        return builder.toString()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalReservationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SeatBookView 객체 참조
        val seatBookView: SeatBookView = binding.seatBookView

        // 생성한 seat layout 문자열 적용
        val seatLayout = generateSeatLayoutString()
        seatBookView.setSeatsLayoutString(seatLayout)
            .setSelectSeatLimit(1) // 개인 예약은 한 자리만 선택 가능

        // 만약 별도의 좌석 타이틀(테이블 번호 등)을 표시하고 싶다면,
        // SeatBookView에서 제공하는 setCustomTitle() 메서드를 활용할 수 있습니다.
        // 예를 들어, 각 테이블의 상단 좌석에 A1, A2, B1, B2, …, E1, E2 등의 라벨을 지정할 수 있습니다.
        // 여기서는 간단히 생략합니다.

        // 좌석 배치 화면 표시
        seatBookView.show()

        // SeatBookView의 좌석 클릭 이벤트 리스너 설정(라이브러리 내 토글 기능 내장)
        seatBookView.setSeatClickListener(object : SeatBookView.SeatClickListener {
            override fun onAvailableSeatClick(selectedIdList: List<Int>, view: View) {
                // 이미 선택된 좌석이 재클릭되면 자동으로 취소(토글)됩니다.
                // 필요에 따라 추가 작업(예: 선택된 좌석 정보 저장 등)을 구현할 수 있습니다.
            }
            override fun onBookedSeatClick(view: View) {
                // 예약된 좌석 클릭 시 처리 (예: "이미 예약된 좌석입니다" 메시지 표시)
            }
            override fun onReservedSeatClick(view: View) {
                // 예약 대기 중인 좌석 클릭 시 처리
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
