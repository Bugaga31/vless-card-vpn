package com.vlesscardvpn.tor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class TorControlTest {

    /** Minimal fake ControlPort: records commands, answers 250, remembers ExitNodes. */
    private class FakeControl {
        lateinit var server: ServerSocket
        val received = StringBuilder()
        var exitNodes: String? = null
        var stopped = false

        fun start() {
            val t = Thread {
                server = ServerSocket(0)
                synchronized(this) { (this as Object).notifyAll() }
                val s = server.accept()
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val w = s.getOutputStream()
                while (!stopped) {
                    val line = r.readLine() ?: break
                    synchronized(received) { received.append(line).append('\n') }
                    when {
                        line.startsWith("AUTHENTICATE") -> w.write("250 OK\r\n".toByteArray())
                        line.startsWith("SETCONF ExitNodes=") -> {
                            exitNodes = line.removePrefix("SETCONF ExitNodes=")
                            w.write("250 OK\r\n".toByteArray())
                        }
                        line.startsWith("GETINFO status/circuit-established") ->
                            w.write("250 status/circuit-established=1\r\n".toByteArray())
                        line.startsWith("QUIT") -> { w.write("250 closing connection\r\n".toByteArray()); break }
                        else -> w.write("250 OK\r\n".toByteArray())
                    }
                    w.flush()
                }
                s.close()
            }
            t.isDaemon = true
            t.start()
        }

        fun awaitPort() {
            while (!::server.isInitialized) Thread.sleep(20)
        }
    }

    @Test
    fun `authenticates with cookie and issues newnym`() {
        val fake = FakeControl().apply { start(); awaitPort() }
        try {
            val c = TorControl("127.0.0.1", fake.server.localPort)
            c.connect("AABBCCDD")
            c.signalNewNym()
            c.setExitCountries(listOf("de", "fr"))
            val up = c.circuitEstablished()
            c.close()
            val cmds = fake.received.toString()
            assertTrue("AUTHENTICATE AABBCCDD" in cmds)
            assertTrue("SIGNAL NEWNYM" in cmds)
            assertEquals("\"{de},{fr}\"", fake.exitNodes)
            assertTrue(up)
        } finally {
            fake.stopped = true
            fake.server.close()
            Thread.sleep(100)
        }
    }
}
