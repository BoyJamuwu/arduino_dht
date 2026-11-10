
package com.example.test

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Encabezado rojo y ajuste de la barra de estado
        val header = findViewById<LinearLayout>(R.id.headerSuperior)
        val altoBase = (64 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->

            val altoBarraEstado =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            view.setPadding(
                view.paddingLeft,
                altoBarraEstado,
                view.paddingRight,
                view.paddingBottom
            )

            view.layoutParams = view.layoutParams.apply {
                height = altoBase + altoBarraEstado
            }

            insets
        }

        ViewCompat.requestApplyInsets(header)

        // Botón de volver
        val btnVolverFlecha =
            findViewById<TextView>(R.id.btnVolverFlecha)

        btnVolverFlecha.setOnClickListener {
            finish()
        }

        // Campos del formulario
        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnIngresar = findViewById<Button>(R.id.btnIngresar)

        // Validación de las credenciales
        btnIngresar.setOnClickListener {

            val usuario = etUsuario.text.toString().trim()
            val password = etPassword.text.toString()

            if (usuario == "admin" && password == "Admin123") {

                Toast.makeText(
                    this,
                    "Acceso correcto",
                    Toast.LENGTH_SHORT
                ).show()

                val intent = Intent(
                    this,
                    ExplicacionActivity::class.java
                )

                startActivity(intent)

            } else {

                Toast.makeText(
                    this,
                    "Usuario o contraseña incorrectos",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
