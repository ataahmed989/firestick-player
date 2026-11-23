package app.fireplayer.vod.ui

import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service

/**
 * Where the viewer was on one of Films & Shows' pages, kept when the page is let go of while hidden (a film playing,
 * Live TV watched a while, the TV short of memory: [VodPages.releaseHidden]) and handed to the page made again in its
 * place the next time its tab opens, which opens there: the row chosen last ([lastRow]) and each row's chosen card,
 * by the rows' keys (so a row moved in Settings › Rows meanwhile still finds its card). Each row takes its card once,
 * as it first shows. Pure, unit-tested.
 */
class PagePlace(val lastRow: String?, chosen: Map<String, Int>) {
    private val chosen = HashMap(chosen)

    /** The card row [key] starts on as it first shows with [size] items: the one chosen before (once), else the first. */
    fun takeChosen(key: String, size: Int): Int {
        val at = chosen.remove(key) ?: return 0
        return if (size <= 0) 0 else at.coerceIn(0, size - 1)
    }

    /** The rows' cards not taken yet (rows that haven't shown since the page was made again). */
    fun untaken(): Map<String, Int> = chosen

    companion object {
        /** A page opened for the first time: its rows start on their first cards, the page at its top. */
        fun none(): PagePlace = PagePlace(null, emptyMap())

        /**
         * The place to keep for a page about to go: [lastRow] (the row chosen last, or the one still to be chosen again
         * from [before]) and the cards chosen in its [shown] rows, over those [before] still had for rows not shown.
         */
        fun keep(lastRow: String?, shown: Map<String, Int>, before: PagePlace): PagePlace =
            PagePlace(lastRow, before.untaken() + shown)

        /** Which page: a tab's (no [service]), a service's (no [studio]) or one of its studios'. */
        fun keyOf(tab: VodTab, service: Service?, studio: Brand?): String = "${tab.name}|${service?.name}|${studio?.name}"

        /**
         * Whether a hidden page ([tab]'s, [service], [studio]) goes when the hidden pages are let go of, with the page
         * of [currentService] and [currentStudio] shown (both null: a tab's, or another tab, as Live TV or Settings):
         * every page but the shown one, except the Home tab's own page (the start page, where Back from a service's page
         * returns and the live picture follows to: made again, it would show empty and fill in under the viewer), and,
         * under a studio's page, its service's page, which Back from the studio returns to as it was.
         */
        fun goes(
            isCurrent: Boolean,
            tab: VodTab,
            service: Service?,
            studio: Brand?,
            currentService: Service?,
            currentStudio: Brand?,
        ): Boolean {
            if (isCurrent) return false
            if (tab == VodTab.HOME && service == null && studio == null) return false
            val underStudio = currentStudio != null && studio == null && service != null && service == currentService
            return !underStudio
        }
    }
}
