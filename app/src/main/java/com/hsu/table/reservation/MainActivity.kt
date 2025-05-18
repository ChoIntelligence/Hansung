package com.hsu.table.reservation

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val gotoFrag = intent?.getStringExtra("goto_fragment")
        val userIdFromIntent = intent?.getStringExtra("user_id")

        if (gotoFrag == "PersonalReservationFragment") {
            // (A) PersonalReservationFragment 로 교체
            val fragment = PersonalReservationFragment().apply {
                arguments = Bundle().apply {
                    putString("user_id", userIdFromIntent)
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        } else {
            // (B) 기본 HomeFragment
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }
}