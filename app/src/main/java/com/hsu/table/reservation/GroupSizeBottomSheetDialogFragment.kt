package com.hsu.table.reservation

import android.app.Dialog
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 단체 예약 인원을 선택하는 BottomSheetDialog
 */
class GroupSizeBottomSheetDialogFragment(
    private val onSizeSelected: (Int) -> Unit
) : BottomSheetDialogFragment() {

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


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(
            R.layout.fragment_group_size_bottom_sheet,
            container,
            false
        )

        // UI
        val btnSize2 = rootView.findViewById<Button>(R.id.btnSize2)
        val btnSize3 = rootView.findViewById<Button>(R.id.btnSize3)
        val btnSize4 = rootView.findViewById<Button>(R.id.btnSize4)

        btnSize2.setOnClickListener {
            onSizeSelected.invoke(2)
            dismiss()
        }
        btnSize3.setOnClickListener {
            onSizeSelected.invoke(3)
            dismiss()
        }
        btnSize4.setOnClickListener {
            onSizeSelected.invoke(4)
            dismiss()
        }

        return rootView
    }


}
