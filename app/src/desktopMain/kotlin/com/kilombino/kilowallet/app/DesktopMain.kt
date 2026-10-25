package com.kilombino.kilowallet.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kilombino.kilowallet.app.ui.App
import com.kilombino.kilowallet.core.chain.JvmTransport
import com.kilombino.kilowallet.core.chain.TransportFactory

/**
 * Desktop preview of the shared UI (developers only, never shipped): words kept in memory,
 * settings in memory. `./gradlew :app:run`, optionally with -Dkw.demo=home.
 */
fun main() = application {
    val platform = object : AppPlatform {
        private val mem = HashMap<String, String>()
        private val secrets = HashMap<String, String>()
        override val seeds = object : SeedStore {
            override fun hasSeed(walletId: String) = secrets.containsKey(walletId)
            override fun save(walletId: String, secret: String, done: (String?) -> Unit) { secrets[walletId] = secret; done(null) }
            override fun load(walletId: String, reason: String, done: (String?, String?) -> Unit) = done(secrets[walletId], if (secrets[walletId] == null) "no words" else null)
            override fun delete(walletId: String): String? { secrets.remove(walletId); return null }
        }
        override val prefs = object : Prefs {
            override fun get(key: String) = mem[key]
            override fun put(key: String, value: String?) { if (value == null) mem.remove(key) else mem[key] = value }
        }
        override val transports: TransportFactory = JvmTransport
        override val sockets: com.kilombino.kilowallet.core.kilojoin.WebSocketFactory = com.kilombino.kilowallet.core.kilojoin.JvmWebSocket
        override val unlockName = "password"
        override fun qrImage(text: String): ImageBitmap? = null
        override val canScanQr = false
        override fun scanQr(done: (String?) -> Unit) = done(null)
        override val demoScreen: String? = System.getProperty("kw.demo")
        override val appVersion = "desktop-preview"
        override fun httpGet(url: String, done: (String?) -> Unit) {
            Thread {
                done(runCatching {
                    val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    c.setRequestProperty("User-Agent", "KilowalletIOS")
                    try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
                }.getOrNull())
            }.start()
        }
    }
    Window(onCloseRequest = ::exitApplication, title = "Kilowallet (preview)",
        state = rememberWindowState(position = androidx.compose.ui.window.WindowPosition(0.dp, 0.dp), width = 430.dp, height = 900.dp), undecorated = true) { App(platform) }
}
