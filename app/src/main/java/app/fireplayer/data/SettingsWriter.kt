package app.fireplayer.data

import android.content.SharedPreferences
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * Puts the settings on the disk at once. A change is applied first (in memory, so every read has it straight away),
 * then [flush] has one background thread commit the file: an empty commit writes whatever is waiting now, rather than
 * after Android's own wait, and returns once it's on the disk. Pressing Home, a low-memory kill, an update or turning the
 * TV off a moment later can then no longer take the change away. Flushes asked for while one waits are one write.
 *
 * Its thread also does the title server's small file jobs ([post]): never the main thread's work.
 */
class SettingsWriter(private val run: (Runnable) -> Unit) {

    /** Files with a flush waiting to start (by identity: each file has one instance in the app). */
    private val waiting = IdentityHashMap<SharedPreferences, Boolean>()

    /** [file]'s changes made so far, on the disk as soon as can be; repeats while one waits are one write. */
    fun flush(file: SharedPreferences) {
        synchronized(waiting) {
            if (waiting.put(file, true) != null) return
        }
        run(Runnable {
            // Changes made after this point need a flush of their own, so it's taken off before the commit.
            synchronized(waiting) { waiting.remove(file) }
            commit(file)
        })
    }

    /**
     * [file]'s changes so far put on the disk, then [done] told whether they are (on this writer's thread): for an
     * explicit Save, which only shows as saved once it is.
     */
    fun flush(file: SharedPreferences, done: (Boolean) -> Unit) {
        run(Runnable { done(commit(file)) })
    }

    /** [task] on this writer's thread, after what's waiting there. */
    fun post(task: Runnable) = run(task)

    private fun commit(file: SharedPreferences): Boolean = runCatching { file.edit().commit() }.getOrDefault(false)

    companion object {
        /** The app's one writer: a single thread of its own, which never keeps the app running. */
        val shared: SettingsWriter by lazy {
            val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerSettings").apply { isDaemon = true } }
            SettingsWriter { task -> runCatching { thread.execute { runCatching { task.run() } } } }
        }
    }
}
