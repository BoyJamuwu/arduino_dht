package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View

class ExplicacionActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_explicacion, "02 · Proyecto")

        findViewById<View>(R.id.btnSiguiente).setOnClickListener {
            startActivity(Intent(this, SensorActivity::class.java))
        }
    }
}
