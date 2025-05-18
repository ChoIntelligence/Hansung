package com.hsu.table.reservation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.hsu.table.reservation.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val REQUEST_FACE_CAPTURE_PERSONAL = 100
    private val REQUEST_FACE_CAPTURE_CANCEL   = 200


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    // HomeFragment.kt (예시)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPersonalReservation.setOnClickListener {
            // FaceCaptureActivity 실행, 개인 예약 모드
            val intent = Intent(requireContext(), FaceCaptureActivity::class.java)
            intent.putExtra("IS_PERSONAL_RESERVATION", true)
            startActivityForResult(intent, REQUEST_FACE_CAPTURE_PERSONAL)
        }

        // HomeFragment (발췌)
        binding.btnGroupReservation.setOnClickListener {
            // 1) 인원수 선택 바텀시트 띄운다
            val groupSizeBottomSheet = GroupSizeBottomSheetDialogFragment { selectedGroupSize ->
                // 2) 이용 시간 선택 바텀시트
                val durationBottomSheet = DurationBottomSheetDialogFragment({ selectedDuration ->
                    // 두 값이 정해졌으니 GroupReservationFragment로 이동
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, GroupReservationFragment().apply {
                            arguments = Bundle().apply {
                                putInt("group_size", selectedGroupSize)        // 2,3,4
                                putString("reservation_duration", selectedDuration) // "1시간", "2시간" ...
                            }
                        })
                        .addToBackStack(null)
                        .commit()
                }
                )
                durationBottomSheet.show(parentFragmentManager, "DurationBottomSheet")
            }
            groupSizeBottomSheet.show(parentFragmentManager, "GroupSizeBottomSheet")
        }

        binding.btnCancelReservation.setOnClickListener {
            val cancelFrag = CancelReservationFragment()
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, cancelFrag)
                .addToBackStack(null)
                .commit()
        }

        binding.btnCheckUsage.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, UsageStatusFragment())
                .addToBackStack(null)
                .commit()
        }



    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            val userId = data.getStringExtra("user_id") ?: return

            when (requestCode) {
                // 개인 예약 모드 → PersonalReservationFragment 로 이동
                REQUEST_FACE_CAPTURE_PERSONAL -> {
                    val frag = PersonalReservationFragment().apply {
                        arguments = Bundle().apply {
                            putString("user_id", userId)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag)
                        .addToBackStack(null)
                        .commit()
                }

                // 예약 취소 모드 → CancelReservationFragment 로 이동
                REQUEST_FACE_CAPTURE_CANCEL -> {
                    val frag = CancelReservationFragment().apply {
                        arguments = Bundle().apply {
                            putString("user_id", userId)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag)
                        .addToBackStack(null)
                        .commit()
                }
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
