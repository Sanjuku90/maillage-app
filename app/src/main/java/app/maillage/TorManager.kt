package app.maillage

import android.content.Context
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Embedded Arti SOCKS proxy. The Nostr transport is not created until bootstrap succeeds. */
class TorManager(context: Context) {
    private val appContext = context.applicationContext as android.app.Application
    @Volatile private var proxy: ArtiProxy? = null
    private val stopped = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val error = AtomicBoolean(false)

    fun startAndWait(timeoutSeconds: Long = 180): Int {
        check(proxy == null) { "Tor already started" }
        stopped.set(false)
        val p = ArtiProxy.Builder(appContext)
            .setSocksPort(SOCKS_PORT)
            .setDnsPort(SOCKS_PORT + 1)
            .setLogListener(ArtiLogListener { line ->
                val text = line?.toString().orEmpty()
                if (text.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true)) ready.countDown()
                if (text.contains("fatal", ignoreCase = true) || text.contains("bootstrap failed", ignoreCase = true)) error.set(true)
            })
            .build()
        proxy = p
        try { p.start() } catch (e: Exception) { stop(); throw e }
        if (!ready.await(timeoutSeconds, TimeUnit.SECONDS) || error.get() || stopped.get()) {
            stop()
            throw IllegalStateException("Tor n’a pas terminé son démarrage, aucun relais direct ne sera utilisé")
        }
        return SOCKS_PORT
    }

    fun stop() {
        stopped.set(true)
        runCatching { proxy?.stop() }
        proxy = null
        ready.countDown()
    }

    companion object { private const val SOCKS_PORT = 39050 }
}
