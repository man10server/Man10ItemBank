package red.man10.man10itembank.testsupport

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * MySQLの手前に挟むTCPプロキシ。
 *
 * 「起動後にDBが落ちる」状況を作るために使う。
 * 到達できないホストを設定しただけでは connect() の時点で失敗してプールが作られず、
 * 実行時の経路(プールはあるがコネクションが取れない)を再現できない。
 *
 * 接続を受け付けた回数を数えているので、
 * サーキットブレーカーが「本当に接続を試みなくなったか」を時間ではなく回数で確認できる。
 */
class MySqlProxy(
    private val targetHost: String = MySqlSupport.host,
    private val targetPort: Int = MySqlSupport.port
) : AutoCloseable {

    private val server = ServerSocket(0)

    private val live = Collections.synchronizedList(mutableListOf<Socket>())

    private val acceptedCount = AtomicInteger(0)

    /** falseにすると、接続は受け付けるが即座に切る(MySQLが死んだ状態) */
    @Volatile
    var forwarding = true

    val port: Int get() = server.localPort

    /** これまでに受け付けた接続数 */
    val accepted: Int get() = acceptedCount.get()

    init {
        Thread({ acceptLoop() }, "MySqlProxy-accept").apply { isDaemon = true }.start()
    }

    /** 確立済みのコネクションを全て切る(DBが落ちた瞬間の再現) */
    fun cutLiveConnections() {
        synchronized(live) {
            live.forEach { runCatching { it.close() } }
            live.clear()
        }
    }

    override fun close() {
        forwarding = false
        cutLiveConnections()
        runCatching { server.close() }
    }

    private fun acceptLoop() {

        while (!server.isClosed) {

            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }

            acceptedCount.incrementAndGet()

            if (!forwarding) {
                runCatching { client.close() }
                continue
            }

            val upstream = try {
                Socket(targetHost, targetPort)
            } catch (e: IOException) {
                runCatching { client.close() }
                continue
            }

            live.add(client)
            live.add(upstream)

            pipe(client, upstream)
            pipe(upstream, client)
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        Thread({
            runCatching {
                val buffer = ByteArray(8192)
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
            runCatching { from.close() }
            runCatching { to.close() }
        }, "MySqlProxy-pipe").apply { isDaemon = true }.start()
    }
}
