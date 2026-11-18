package com.example.test

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton

class LoginActivity : BaseActivity() {

    override val requiereSesion = false

    private lateinit var etUsuario: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnIngresar: MaterialButton
    private lateinit var tvAviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurarPantalla(R.layout.activity_login, "01 · Acceso")

        etUsuario = findViewById(R.id.etUsuario)
        etPassword = findViewById(R.id.etPassword)
        btnIngresar = findViewById(R.id.btnIngresar)
        tvAviso = findViewById(R.id.tvAvisoLogin)

        btnIngresar.setOnClickListener { intentarIngreso() }
    }

    private fun intentarIngreso() {
        val usuario = etUsuario.text.toString().trim()

        // Se copia la contraseña a un CharArray para poder borrarla de memoria
        val editable = etPassword.text
        val password = CharArray(editable?.length ?: 0) { editable!![it] }
        etPassword.text?.clear()

        if (usuario.isEmpty() || password.isEmpty()) {
            mostrarAviso("Completa usuario y contraseña.")
            return
        }

        btnIngresar.isEnabled = false
        btnIngresar.text = "Verificando…"

        // PBKDF2 es lento a propósito: se ejecuta fuera del hilo principal
        Thread {
            val resultado = Autenticacion.validar(applicationContext, usuario, password)

            runOnUiThread {
                btnIngresar.isEnabled = true
                btnIngresar.text = "Ingresar"

                when (resultado) {
                    is Autenticacion.Resultado.Correcto -> {
                        tvAviso.visibility = View.GONE
                        Sesion.iniciar()
                        startActivity(Intent(this, ExplicacionActivity::class.java))
                    }

                    is Autenticacion.Resultado.Incorrecto ->
                        mostrarAviso(
                            "Usuario o contraseña incorrectos. " +
                                "Intentos restantes: ${resultado.intentosRestantes}."
                        )

                    is Autenticacion.Resultado.Bloqueado ->
                        mostrarAviso(
                            "Acceso bloqueado por intentos fallidos. " +
                                "Espera ${formatoEspera(resultado.segundos)}."
                        )
                }
            }
        }.start()
    }

    private fun formatoEspera(segundos: Long): String =
        if (segundos >= 60) "${(segundos + 59) / 60} min" else "$segundos s"

    private fun mostrarAviso(texto: String) {
        tvAviso.text = texto
        tvAviso.visibility = View.VISIBLE
    }
}
