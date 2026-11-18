package com.example.test

import android.app.Application

class MonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val db = LecturasDb.get(this)

        // Bitácora de eventos del enlace Bluetooth
        BluetoothManager.registroEventos = { tipo, detalle ->
            db.registrarEvento(tipo, detalle)
        }

        // Guardado automático cada 5 minutos
        RegistroPeriodico.iniciar(db)

        // Aplicar la política de retención al iniciar
        db.depurarAhora()
    }
}
