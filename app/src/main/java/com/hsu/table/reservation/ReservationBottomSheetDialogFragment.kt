package com.hsu.table.reservation

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ReservationBottomSheetDialogFragment(
    private val selectedSeatId: Int,
    private val listener: ReservationListener
) : BottomSheetDialogFragment() {

    interface ReservationListener {
        fun onReservationConfirmed(duration: String, seatId: Int)
        fun onReservationCancelled(seatId: Int)
    }

    private lateinit var tvTitle: TextView
    private lateinit var radioGroup: RadioGroup
    private lateinit var btnConfirm: Button
    private lateinit var btnCancel: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // inflate the bottom sheet layout (아래 XML 파일 참조)
        return inflater.inflate(R.layout.fragment_reservation_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvTitle = view.findViewById(R.id.tvReservationTitle)
        radioGroup = view.findViewById(R.id.reservationRadioGroup)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnCancel = view.findViewById(R.id.btnCancel)

        tvTitle.text = "예약 시간을 선택해 주세요."

        btnConfirm.setOnClickListener {
            val selectedRadioId = radioGroup.checkedRadioButtonId
            if (selectedRadioId != -1) {
                val radioButton = view.findViewById<RadioButton>(selectedRadioId)
                val duration = radioButton.text.toString()
                listener.onReservationConfirmed(duration, selectedSeatId)
                dismiss()
            } else {
                Toast.makeText(requireContext(), "예약 시간을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            listener.onReservationCancelled(selectedSeatId)
            dismiss()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // 팝업 외부 터치 등으로 취소될 때 선택했던 좌석 해제
        listener.onReservationCancelled(selectedSeatId)
    }
}
