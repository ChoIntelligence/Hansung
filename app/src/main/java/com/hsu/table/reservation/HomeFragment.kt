package com.hsu.table.reservation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    // ViewBinding 객체(메모리 누수를 방지하기 위해 _binding 사용)
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // 프래그먼트의 뷰를 생성하는 부분
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // fragment_home.xml 파일을 바인딩
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 뷰가 생성된 후 버튼 클릭 이벤트 등 초기화 작업 수행
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 로그인 버튼 클릭 리스너 설정
        binding.btnLogin.setOnClickListener {
            // 로그인 화면으로 전환하는 코드 또는 로직 추가
        }

        // 개인 예약 버튼 클릭 리스너 설정
        binding.btnPersonalReservation.setOnClickListener {
            // 예를 들어, MainActivity의 프래그먼트 컨테이너에 PersonalReservationFragment로 교체
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, PersonalReservationFragment())
                .addToBackStack(null)
                .commit()
        }
        // 단체 예약 버튼 클릭 리스너 설정
        binding.btnGroupReservation.setOnClickListener {
            // 단체 예약 화면으로 전환하는 코드 또는 로직 추가
        }
    }

    // 프래그먼트 뷰가 파괴될 때 바인딩 해제
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
