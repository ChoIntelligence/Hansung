package com.hsu.table.reservation

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.SimpleDateFormat
import java.util.*

class DurationBottomSheetDialogFragment(
    private val durationSelectListener: (String) -> Unit,
    private val onCancelOrDismiss: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    // 시간 계산: 현재 시각 + addMinutes => "HH:mm" 형태 문자열 리턴
    private fun calculateFutureTimeStr(addMinutes: Int): String {
        val now = System.currentTimeMillis()
        val future = now + addMinutes * 60_000L
        val date = Date(future)
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(date)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_bottom_sheet_duration, container, false)

        // 각 시간별 버튼
        val btn30min = view.findViewById<Button>(R.id.btn30min)
        val btn1hour = view.findViewById<Button>(R.id.btn1hour)
        val btn1h30m = view.findViewById<Button>(R.id.btn1h30m)
        val btn2hour = view.findViewById<Button>(R.id.btn2hour)
        val btn2h30m = view.findViewById<Button>(R.id.btn2h30m)
        val btn3hour = view.findViewById<Button>(R.id.btn3hour)

        // 취소 버튼
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        // 클릭 리스너
        btn30min.setOnClickListener {
            val endTime = calculateFutureTimeStr(30)   // 30분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }
        btn1hour.setOnClickListener {
            val endTime = calculateFutureTimeStr(60)   // 60분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }
        btn1h30m.setOnClickListener {
            val endTime = calculateFutureTimeStr(90)   // 90분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }
        btn2hour.setOnClickListener {
            val endTime = calculateFutureTimeStr(120)  // 120분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }
        btn2h30m.setOnClickListener {
            val endTime = calculateFutureTimeStr(150)  // 150분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }
        btn3hour.setOnClickListener {
            val endTime = calculateFutureTimeStr(180)  // 180분 뒤
            durationSelectListener.invoke(endTime)
            dismiss()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        return view
    }

    override fun onStart() {
        super.onStart()

        // BottomSheetDialog 로 캐스팅 후 peekHeight 등 설정 가능
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            // 예) 가로 모드에서 좀 더 크게 보여주기
            this.peekHeight = 800
            // 필요 시 skipCollapsed = true, state = STATE_EXPANDED 등도 가능
            // skipCollapsed = true
            // state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // 바텀시트가 닫힐 때
        onCancelOrDismiss?.invoke()
    }
}
