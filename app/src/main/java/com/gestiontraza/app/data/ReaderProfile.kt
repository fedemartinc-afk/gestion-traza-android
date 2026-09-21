package com.gestiontraza.app.data

/**
 * La conexión Bluetooth SPP es genérica (ver BtManager) — no depende de la
 * marca del lector, así que no hace falta un "perfil" por modelo para poder
 * conectarse. Esto es solo información de ayuda para dejar cada marca en
 * modo SPP (o Bluetooth clásico con emparejamiento, según el lector).
 */
data class TipConfiguracion(val marca: String, val instrucciones: String)

object ReaderTips {
    val tips = listOf(
        TipConfiguracion(
            marca = "Agrident AS420",
            instrucciones = "Menú → Setup → Interface Setup → Bluetooth → BT Profile → SPP. Confirmado y probado."
        ),
        TipConfiguracion(
            marca = "Tru-Test SRS2 / XRS2",
            instrucciones = "Settings → Bluetooth → Search for Devices, elegir el celular y esperar el emparejamiento. Usa Bluetooth clásico con pairing (no requiere SDK aparte)."
        ),
        TipConfiguracion(
            marca = "Gallagher HR5",
            instrucciones = "Ícono de Wireless en el menú principal → buscar dispositivos → emparejar con passkey. También usa Bluetooth clásico con pairing."
        ),
        TipConfiguracion(
            marca = "Otras marcas",
            instrucciones = "Buscá en el menú del lector una opción de Bluetooth con perfil \"SPP\" o \"Serial\", o simplemente \"emparejar\"/\"buscar dispositivos\" (a veces aparece \"Modo teclado\", que es HID y no sirve para esta pantalla)."
        )
    )
}
