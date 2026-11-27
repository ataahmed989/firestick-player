package app.fireplayer.history

import app.fireplayer.vod.VodRepository
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Glass Home's word that Continue Watching may have changed ([ContinueProvider]'s lists, [ContinueWatching.ITEMS], which
 * covers each title's own address too): one notice per burst of library changes. The first change of a burst posts
 * the notice ([post]: on the main thread, after whatever is queued there); the changes made before it goes out ride
 * on it, so Glass Home asks once. A change after it starts the next. Nothing else is done here: Glass Home asks for
 * the list itself, when it wants it (at once while its row is in front; while a film plays it only marks it to ask).
 * [notify] failing (FirePlayer being stopped) is let go of: the next change tries again. Any thread.
 */
class ContinueNotice(private val post: (Runnable) -> Unit, private val notify: () -> Unit) {
    private val pending = AtomicBoolean(false)

    private val send = Runnable {
        pending.set(false)
        runCatching { notify() }
    }

    /** The library changed. */
    fun changed() {
        if (pending.compareAndSet(false, true)) post(send)
    }

    companion object {
        /**
         * Glass Home told of every change of [repo]'s library ([VodRepository.addLibraryListener]: on FirePlayer's main
         * thread), once per burst: [post] puts the notice on that thread after the changes queued there, [notify] tells.
         */
        fun attach(repo: VodRepository, post: (Runnable) -> Unit, notify: () -> Unit): ContinueNotice =
            ContinueNotice(post, notify).also { notice -> repo.addLibraryListener { notice.changed() } }
    }
}
