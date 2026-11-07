package me.sorihiko.dango.music

import com.google.gson.Gson
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.party.ListenParty
import java.io.BufferedWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Publishes Windows SMTC independently of the game's audio backend. Media commands run on the client tick. */
object WindowsMediaControls {
    private const val RESOURCE = "/native/windows-x64/DangoMusicMedia.exe"
    val supported: Boolean by lazy {
        System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true) &&
            System.getProperty("os.arch", "") in setOf("amd64", "x86_64") &&
            WindowsMediaControls::class.java.getResource(RESOURCE) != null
    }

    private data class Snapshot(
        val title: String, val artist: String, val state: String, val positionMs: Long, val durationMs: Long,
        val canControl: Boolean, val canSkip: Boolean, val canSeek: Boolean, val canStop: Boolean,
    )
    private class Session(val process: Process, val input: BufferedWriter) {
        var lastSnapshot: Snapshot? = null
    }
    private class Command(val session: Session, val text: String)

    private val gson = Gson()
    private val latest = AtomicReference<Snapshot?>()
    private val commands = ArrayBlockingQueue<Command>(32)
    @Volatile private var session: Session? = null
    private var workerStarted = false
    private var retryAt = 0L
    @Volatile internal var enabledForPlayback = false
        private set

    fun tick(enabled: Boolean) {
        if (!supported) return
        enabledForPlayback = enabled
        if (!enabled) {
            clear()
            return
        }
        repeat(8) {
            val command = commands.poll() ?: return@repeat
            if (command.session === session) handle(command.text)
        }
        val track = MusicPlayer.current
        val playbackState = MusicPlayer.state
        val state = when (playbackState) {
            MusicPlayer.State.PLAYING -> "playing"
            MusicPlayer.State.PAUSED -> "paused"
            MusicPlayer.State.LOADING -> "loading"
            else -> null
        }
        if (track == null || state == null) {
            clear()
            return
        }
        val allowed = ListenParty.role != ListenParty.Role.MEMBER || ListenParty.canControl
        val duration = track.durationMs.coerceAtLeast(0L)
        val position = MusicPlayer.positionMs.coerceIn(0L, duration)
        if (MusicPlayer.current !== track || MusicPlayer.state != playbackState) return
        latest.set(Snapshot(track.name, track.artists.joinToString(" / "), state,
            position, duration, allowed && state != "loading",
            allowed && (MusicPlayer.queue.size > 1 || MusicPlayer.radio != null),
            allowed && duration > 0 && state != "loading", ListenParty.role == ListenParty.Role.NONE))
        if (!workerStarted) {
            workerStarted = true
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "Dango-SMTC").apply { isDaemon = true }
            }.scheduleWithFixedDelay(::publish, 0L, 500L, TimeUnit.MILLISECONDS)
        }
    }

    private fun clear() {
        latest.set(null)
        commands.clear()
    }

    private fun handle(command: String) {
        if (!MusicPlayer.active || (ListenParty.role == ListenParty.Role.MEMBER && !ListenParty.canControl)) return
        when (command) {
            "play" -> if (MusicPlayer.state == MusicPlayer.State.PAUSED) MusicPlayer.togglePause()
            "pause" -> if (MusicPlayer.state == MusicPlayer.State.PLAYING) MusicPlayer.togglePause()
            "next" -> MusicPlayer.next()
            "previous" -> MusicPlayer.prev()
            "stop" -> if (ListenParty.role == ListenParty.Role.NONE) MusicPlayer.stop()
            else -> if (command.startsWith("seek\t")) {
                command.substringAfter('\t').toLongOrNull()?.takeIf { it >= 0 }?.let(MusicPlayer::seek)
            }
        }
    }

    private fun publish() {
        val snapshot = latest.get()
        if (snapshot == null) {
            closeSession()
            return
        }
        try {
            if (session?.process?.isAlive == false) {
                closeSession()
                retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            }
            val current = session ?: if (retryAt == 0L || System.nanoTime() - retryAt >= 0L) start() else return
            if (snapshot != current.lastSnapshot) {
                current.input.write(gson.toJson(snapshot))
                current.input.newLine()
                current.input.flush()
                current.lastSnapshot = snapshot
            }
        } catch (e: Exception) {
            DangoLogger.warn("Windows media controls unavailable: {}", e.javaClass.simpleName)
            closeSession()
            retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        }
    }

    private fun start(): Session {
        val process = ProcessBuilder(extractHelper().toString()).redirectErrorStream(true).start()
        val started = Session(process, process.outputStream.bufferedWriter(Charsets.UTF_8))
        session = started
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line == "ready" -> DangoLogger.info("Windows media controls connected")
                            line.startsWith("error\t") -> DangoLogger.warn("Windows media controls: {}", line.substringAfter('\t'))
                            line in setOf("play", "pause", "next", "previous", "stop") || line.startsWith("seek\t") ->
                                commands.offer(Command(started, line))
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // The pipe closes when the session is disabled or the helper exits.
            }
        }, "Dango-SMTC-Input").apply { isDaemon = true }.start()
        return started
    }

    private fun closeSession() {
        val closing = session ?: return
        session = null
        try {
            closing.input.close()
            if (!closing.process.waitFor(500L, TimeUnit.MILLISECONDS)) closing.process.destroyForcibly()
        } catch (_: Exception) {
            closing.process.destroyForcibly()
        }
    }

    private fun extractHelper(): Path {
        val bytes = WindowsMediaControls::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val dir = DangoMusic.instance.clientDir.resolve("smtc").resolve(hash)
        Files.createDirectories(dir)
        val target = dir.resolve("DangoMusicMedia.exe").toAbsolutePath()
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.size.toLong() || !Files.readAllBytes(target).contentEquals(bytes)) {
            val temp = Files.createTempFile(dir, "DangoMusicMedia-", ".tmp")
            try {
                Files.write(temp, bytes)
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        return target
    }
}
