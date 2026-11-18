package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View

class MainActivity : BaseActivity() {

    override val requiereSesion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_main)

        findViewById<View>(R.id.btnContinuar).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
    }
}
