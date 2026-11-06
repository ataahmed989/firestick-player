package app.fireplayer.titles

import java.util.Locale

/**
 * What Settings shows of TiviMate's titles under its switch: how much is done, for the bar (renamed and kept as they
 * are, of everything listed), and a short line for each provider, "32,643 renamed · 2,879 to check". With several
 * providers each line starts with the provider's short name.
 */
object TitleStatus {

    /** One provider's line: its short name (null when it's the only one) and its counts. */
    class Line(val provider: String?, val counts: String)

    /** Done, from 0 to 1; 0 while nothing is listed. */
    fun fraction(progress: List<TitleBook.Progress>): Float {
        val total = progress.sumOf { it.renamed.toLong() + it.kept + it.waiting }
        if (total <= 0) return 0f
        val done = progress.sumOf { it.renamed.toLong() + it.kept }
        return (done.toDouble() / total).toFloat()
    }

    fun lines(progress: List<TitleBook.Progress>): List<Line> {
        val names = shortNames(progress.map { it.server })
        return progress.mapIndexed { i, p -> Line(if (progress.size > 1) names[i] else null, counts(p)) }
    }

    /** "32,643 renamed · 2,879 to check", or "· all checked" once nothing waits. */
    fun counts(p: TitleBook.Progress): String =
        "${count(p.renamed)} renamed · " + if (p.waiting > 0) "${count(p.waiting)} to check" else "all checked"

    /**
     * A name for each provider's server that's short enough for the line: its main name ("example.tv:8080" gives
     * "example"), its address when that's an IP, and the whole server when two would read the same.
     */
    fun shortNames(servers: List<String>): List<String> {
        val short = servers.map { server ->
            val host = server.substringBefore(':')
            val labels = host.split('.').filter { it.isNotEmpty() }
            when {
                host.all { it.isDigit() || it == '.' } -> host
                labels.size >= 2 -> labels[labels.size - 2]
                else -> host
            }
        }
        return short.mapIndexed { i, name -> if (short.count { it == name } > 1) servers[i] else name }
    }

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)
}
