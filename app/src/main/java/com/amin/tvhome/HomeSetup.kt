package com.amin.tvhome

import android.content.Context
import android.util.Base64
import android.util.Log
import dadb.AdbAuthException
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

// Same list as bloat-packages.txt; packages missing on a given Fire OS build are skipped.
private val BLOAT = listOf(
    "com.amazon.tv.launcher",
    "com.amazon.tv.mshop.android.app",
    "com.amazon.ftv.adsystem",
)
private const val STOCK_LAUNCHER = "com.amazon.tv.launcher"

/**
 * Swaps the Home screen from the TV itself, for when no computer can reach the TV over the network.
 * Only runs when the user picks "Use as Home Screen" / "Restore Amazon Home" in the Settings tab.
 * With ADB debugging on, Fire OS's adbd also listens on localhost:5555, so this app connects to it
 * like a desktop adb would (the TV shows its "Allow USB debugging?" prompt for our key the first
 * time) and runs the same shell commands as debloat.sh / restore-stock.sh.
 */
class HomeSetup(private val context: Context) {

    suspend fun makeHome(status: (String) -> Unit): String = withShell(status) { sh ->
        val report = StringBuilder()
        for (p in BLOAT) {
            if (!sh.installed(p)) continue
            report.append("$p: ${sh.run("pm disable-user --user 0 $p").lastLine()}\n")
        }
        sh.run("cmd package set-home-activity ${context.packageName}/.MainActivity")
        val home = sh.homeTarget()
        Log.i(TAG, "makeHome:\n$report-> home resolves to $home")
        if (home.startsWith("${context.packageName}/")) {
            "Done. The Home button now opens this screen.\n$report".trim()
        } else {
            // Fire OS refused or something else still claims Home: put Amazon's back rather than
            // leave the remote's Home button in an unknown state.
            restore(sh)
            "Fire OS kept its own Home screen (Home -> $home), so nothing was changed.\n$report".trim()
        }
    }

    suspend fun restoreStock(status: (String) -> Unit): String = withShell(status) { sh ->
        restore(sh)
        "Amazon's Home screen is back. Press Home to check."
    }

    private fun restore(sh: Shell) {
        for (p in BLOAT) if (sh.installed(p)) sh.run("pm enable $p")
        val stock = sh.run("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$STOCK_LAUNCHER/") }
        if (stock != null) sh.run("cmd package set-home-activity $stock")
    }

    private suspend fun withShell(status: (String) -> Unit, block: (Shell) -> String): String =
        withContext(Dispatchers.IO) {
            status("Connecting… if the TV asks \"Allow USB debugging?\", tick \"Always allow\" and choose Allow (or OK).")
            val key = loadOrCreateKey()
            val failures = mutableListOf<String>()
            // Some Fire OS builds drop adb connections that come from 127.0.0.1 mid-handshake, but
            // accept the same device on its own Wi-Fi address, so try each in turn.
            for (host in adbHosts()) {
                // adbd sends nothing back when its prompt is declined or ignored, so bound every read.
                val dadb = Dadb.create(host, 5555, key, connectTimeout = 3000, socketTimeout = 45_000)
                val sh = try {
                    Shell(dadb) // connects (and triggers the TV's approval prompt) here
                } catch (e: Exception) {
                    dadb.close()
                    Log.e(TAG, "adb via $host failed", e)
                    if ((e.cause ?: e) is SocketTimeoutException) {
                        return@withContext "No answer to the TV's \"Allow USB debugging?\" prompt. Press the button again and choose Allow (or OK)."
                    }
                    if (e is AdbAuthException) {
                        return@withContext "The TV declined the request. Press the button again and choose Allow (or OK)."
                    }
                    failures += "$host: ${describe(e)}"
                    continue
                }
                return@withContext try {
                    status("Working…")
                    block(sh)
                } catch (e: Exception) {
                    Log.e(TAG, "home setup failed via $host", e)
                    "Didn't work: ${describe(e)}"
                } finally {
                    dadb.close()
                }
            }
            "Couldn't connect to ADB on this TV:\n${failures.joinToString("\n")}\n" +
                "Check Settings > My Fire TV > Developer Options > ADB Debugging is on."
        }

    private fun adbHosts(): List<String> {
        val own = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
        return (listOf("127.0.0.1") + own).distinct()
    }

    // dadb's own key helpers use java.util.Base64 (API 26+); Fire OS 6 is API 25, so build the
    // key pair with platform APIs and hand it over directly.
    private fun loadOrCreateKey(): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
            priv.writeBytes(kp.private.encoded)
            pub.writeText(Base64.encodeToString(adbPublicKey(kp.public as RSAPublicKey), Base64.NO_WRAP) + " tvhome@firetv")
        }
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
        return AdbKeyPair(privateKey, pub.readBytes() + 0) // adbd expects the text key NUL-terminated
    }

    private class Shell(private val dadb: Dadb) {
        // Must not swallow errors: this is the first call, so it's where a declined prompt surfaces.
        private val v2 = dadb.supportsFeature("shell_v2")

        // shell_v2 arrived in Android 7; older Fire OS only has the raw shell stream.
        fun run(cmd: String): String =
            if (v2) dadb.shell(cmd).allOutput.trim()
            else dadb.open("shell:$cmd").use { it.source.readUtf8() }.trim()

        fun installed(pkg: String) = run("pm path $pkg").contains("package:")

        fun homeTarget(): String =
            run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().map { it.trim() }.lastOrNull { "/" in it } ?: "unknown"
    }

    companion object {
        private const val TAG = "TvHomeSetup"

        private fun String.lastLine() = trim().lineSequence().lastOrNull().orEmpty()

        // "Connection handshake failed (EOFException)" says far more than the wrapper message alone.
        private fun describe(e: Throwable): String {
            val cause = e.cause?.takeIf { it !== e }
            val base = e.message ?: e.javaClass.simpleName
            return if (cause == null) base else "$base (${cause.javaClass.simpleName}${cause.message?.let { ": $it" }.orEmpty()})"
        }

        // adbd's RSAPublicKey struct (see adb's adb_auth_host.cpp): word count, n0inv, modulus and
        // R^2 as little-endian 32-bit words, then the exponent.
        private fun adbPublicKey(key: RSAPublicKey): ByteArray {
            val words = 2048 / 32
            val r32 = BigInteger.ONE.shiftLeft(32)
            var n = key.modulus
            var rr = BigInteger.ONE.shiftLeft(words * 32).modPow(BigInteger.valueOf(2), n)
            val n0inv = n.remainder(r32).modInverse(r32)
            val buf = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(words)
            buf.putInt(n0inv.negate().toInt())
            val nWords = IntArray(words)
            val rrWords = IntArray(words)
            for (i in 0 until words) {
                rrWords[i] = rr.remainder(r32).toInt(); rr = rr.shiftRight(32)
                nWords[i] = n.remainder(r32).toInt(); n = n.shiftRight(32)
            }
            nWords.forEach(buf::putInt)
            rrWords.forEach(buf::putInt)
            buf.putInt(key.publicExponent.toInt())
            return buf.array()
        }
    }
}
