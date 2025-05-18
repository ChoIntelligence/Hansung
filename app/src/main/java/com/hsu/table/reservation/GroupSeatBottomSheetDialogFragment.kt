package com.hsu.table.reservation

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 좌석 클릭 시 띄워지는 BottomSheet
 *  - seatName: "C3" 등
 *  - 등록/취소 콜백
 */
class GroupSeatBottomSheetDialogFragment(
    private val seatName: String,
    private val onRegisterFace: () -> Unit,
    private val onCancelSelect: () -> Unit
) : BottomSheetDialogFragment() {

    private var isManuallyDismissed = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.layout_bottom_sheet_group_seat, container, false)

        val tvSeat = view.findViewById<TextView>(R.id.tvSeatName)
        val btnFace = view.findViewById<Button>(R.id.btnRegisterFace)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelSelect)

        // **여기서 seatName을 TextView에 세팅**
        tvSeat.text = "선택된 좌석 : $seatName"

        btnFace.setOnClickListener {
            onRegisterFace.invoke()
            dismiss() // 등록 버튼 누르면 BottomSheet 닫기 (선택사항)
        }

        btnCancel.setOnClickListener {
            onCancelSelect.invoke()
            dismiss() // 취소 버튼 누르면 BottomSheet 닫기
        }

        return view
    }

    // BottomSheet 높이 조정 예시 (가로모드 대응)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            val behavior = BottomSheetBehavior.from(bottomSheet!!)
            // BottomSheet 보여질 때 즉시 확장
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        // 외부 터치나 뒤로가기 등으로 dismiss가 일어났는데,
        // 버튼으로 인한 dismiss가 아니라면(=isManuallyDismissed=false) → 선택취소 로직 실행
        if (!isManuallyDismissed) {
            onCancelSelect.invoke()
        }
    }
}
