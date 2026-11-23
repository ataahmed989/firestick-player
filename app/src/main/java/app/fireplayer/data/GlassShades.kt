package app.fireplayer.data

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle

/**
 * Settings › Look's three glasses, saved with the other settings, one value each wherever its row shows: App glass (the
 * windows over the app's pages) and Playback glass (windows over full-screen films and Live TV), each with its Tint and
 * its Dimness, and Rail, cards & header (the glass in the pages themselves), with its Tint and its Charcoal; 0% to 100%
 * in 5% steps ([GlassStyle.Shade]). Playback glass is the players' dimness settings' Default preset ([Dimmer.default]):
 * the same values, so a change in one shows in the other. The Playback glass in use is the preset on there
 * ([Dimmer.glass]: Default's, Look's, until a custom is picked). [apply] puts them on the glass ([Glass.useShades]). Only
 * the glass's own layers change: never text, icons or the focus highlight drawn on it.
 *
 * As the Screen dimmer's customs are: a step or Reset to defaults changes the glass at once but isn't saved ([draft],
 * Unsaved), and Save keeps all three ([save]); a change not saved stays for this run of the app only.
 */
class GlassShades(private val sp: () -> SharedPreferences) {

    constructor(context: Context) : this(settingsOf(context))

    /**
     * What a glass's dropdown opens to, its rows' names: [TINT], how strong its colour layer is; [DIMNESS], how much
     * it's darkened after that; [CHARCOAL], where its tint's colour sits between the background's own and charcoal
     * ([GlassStyle.Shade]).
     */
    enum class Control(val label: String) {
        TINT("Tint"),
        DIMNESS("Dimness"),
        CHARCOAL("Charcoal"),
        ;

        /** Its value in [shade], percent. */
        fun of(shade: GlassStyle.Shade): Int = when (this) {
            TINT -> shade.tint
            DIMNESS -> shade.dim
            CHARCOAL -> shade.charcoal
        }
    }

    /**
     * The three glasses, in Settings › Look's order: their dropdown rows' names, the control each has after its Tint
     * ([second]), their saved keys and their defaults ([GlassStyle]). The keys are their own (not the drafts'), so no
     * value saved with another meaning is read.
     */
    enum class Kind(
        val title: String,
        val second: Control,
        val tintKey: String,
        val secondKey: String,
        val default: GlassStyle.Shade,
    ) {
        APP("App glass", Control.DIMNESS, "glass_app_tint", "glass_app_dim", GlassStyle.APP_SHADE),
        RAIL("Rail, cards & header", Control.CHARCOAL, "glass_rail_tint", "glass_rail_charcoal", GlassStyle.RAIL_SHADE),
        PLAYBACK("Playback glass", Control.DIMNESS, "glass_playback_tint", "glass_playback_dim", GlassStyle.PLAYBACK_SHADE),
        ;

        /** Its dropdown's rows, in order: Tint, then [second]. */
        val controls: List<Control> = listOf(Control.TINT, second)

        /** Its dropdown in Settings › Look, open or closed ([app.fireplayer.ui.SidePanel.isPartOpen]). */
        val part: String get() = "glass_$name"

        /** [control]'s saved key for this glass. */
        fun keyOf(control: Control): String {
            require(control in controls) { "$title has no ${control.label}" }
            return if (control == Control.TINT) tintKey else secondKey
        }
    }

    /** [kind]'s tint and its second control as they show now: a change not saved yet ([hasUnsaved]), else as saved. */
    operator fun get(kind: Kind): GlassStyle.Shade = draft?.get(kind) ?: saved(kind)

    /** [kind] as saved (its defaults until saved). Only reads. */
    fun saved(kind: Kind): GlassStyle.Shade {
        val tint = read(kind.tintKey, kind.default.tint)
        val second = read(kind.secondKey, kind.second.of(kind.default))
        return shade(kind, tint, second)
    }

    /**
     * [kind]'s [control] a step up ([direction] 1) or down (-1), shown at once and kept by [save]; the shade it has now.
     * The steps are 5% apart, with the glass's own default a stop on the way where it's between two
     * ([GlassStyle.stepShade]).
     */
    fun step(kind: Kind, control: Control, direction: Int): GlassStyle.Shade {
        val now = get(kind)
        val value = GlassStyle.stepShade(control.of(now), direction, stop = control.of(kind.default))
        val tint = if (control == Control.TINT) value else now.tint
        val second = if (control == Control.TINT) kind.second.of(now) else value
        change(kind, shade(kind, tint, second))
        return get(kind)
    }

    /**
     * Reset to defaults: all three glasses back to their defaults ([Kind.default]), shown at once and Unsaved until Save,
     * so a stray press can't take the saved values away. Playback glass is the players' Default preset, so Default is
     * back to its defaults too, with no display dimness (one tried on it and not saved goes). The customs stay as they
     * are, and so does the preset on: a custom picked stays picked.
     */
    fun reset() {
        for (kind in Kind.entries) change(kind, kind.default)
        Dimmer.forgetDefaultDraft()
    }

    /** Whether a glass shows a change not saved yet (or whose Save didn't reach the disk): Save would keep it. */
    fun hasUnsaved(): Boolean = draft != null || notOnDisk.isNotEmpty()

    /** Whether [kind] shows a change not saved yet, or one whose Save didn't reach the disk ([save]). */
    fun hasUnsaved(kind: Kind): Boolean = kind in notOnDisk || (draft?.let { it[kind] != saved(kind) } ?: false)

    /**
     * Save: the glasses as they show now ([kinds]: all three, both values each, in one change), committed on [writer]'s
     * thread so it's on the disk; then, back on the main thread ([main]), [done] says whether it is. Only then is the
     * change no longer Unsaved (a glass changed again meanwhile stays Unsaved); if it couldn't be saved it stays Unsaved.
     * A failed commit still leaves the values in the file's memory (Android puts them there before it writes), so the
     * glasses it changed are noted as not on the disk ([notOnDisk]) until a commit gets there: Save stays offered.
     */
    fun save(writer: SettingsWriter, main: (Runnable) -> Unit, kinds: List<Kind> = Kind.entries, done: (Boolean) -> Unit) {
        val now = kinds.associateWith { get(it) }
        val before = kinds.associateWith { saved(it) }
        val edit = sp().edit()
        for ((kind, shade) in now) edit.putInt(kind.tintKey, shade.tint).putInt(kind.secondKey, kind.second.of(shade))
        writer.post(Runnable {
            val onDisk = runCatching { edit.commit() }.getOrDefault(false)
            main(Runnable {
                if (onDisk) {
                    // The whole file was written: every glass's values in it are on the disk now.
                    notOnDisk = emptySet()
                    for ((kind, shade) in now) if (draft?.get(kind) == shade) change(kind, shade)
                } else {
                    notOnDisk = notOnDisk + now.keys.filter { now[it] != before[it] }
                }
                done(onDisk)
            })
        })
    }

    /** [kind] shown as [shade] until saved; no change is left once all three are as saved again. */
    private fun change(kind: Kind, shade: GlassStyle.Shade) {
        val next = (draft ?: Kind.entries.associateWith { saved(it) }) + (kind to shade)
        draft = if (Kind.entries.all { next[it] == saved(it) }) null else next
    }

    /**
     * Once, as the app starts ([Settings]): Playback glass's defaults were no tint and no dimness ([OLD_PLAYBACK]) before
     * [GlassStyle.PLAYBACK_SHADE]. Where only one of its two values was saved, the other showed as the old default
     * (none) and is saved as that. Nothing saved stays unsaved and reads as the new defaults; values saved are the
     * viewer's and are never changed or taken away, a Clear one (no tint, no dimness) included.
     */
    internal fun movePlaybackDefaultsOnce() {
        val saved = sp()
        if (saved.getBoolean(PLAYBACK_MOVED_KEY, false)) return
        val kind = Kind.PLAYBACK
        val edit = saved.edit().putBoolean(PLAYBACK_MOVED_KEY, true)
        val hasTint = saved.contains(kind.tintKey)
        val hasDim = saved.contains(kind.secondKey)
        if (hasTint && !hasDim) edit.putInt(kind.secondKey, OLD_PLAYBACK.dim)
        if (hasDim && !hasTint) edit.putInt(kind.tintKey, OLD_PLAYBACK.tint)
        edit.apply()
    }

    /**
     * Puts all three on the glass ([Glass.useShades]), Playback glass as the players' preset on has it ([Dimmer.glass]:
     * Look's own with Default on): true when that changed it.
     */
    fun apply(): Boolean = Glass.useShades(get(Kind.APP), get(Kind.RAIL), Dimmer(sp).glass())

    /** [apply], and if that changed the glass every view of [activity]'s window is drawn again with it, once. */
    fun applyTo(activity: Activity) {
        if (apply()) Glass.redraw(activity.window.decorView)
    }

    private fun read(key: String, default: Int): Int = GlassStyle.clampShade(sp().getInt(key, default))

    private fun shade(kind: Kind, tint: Int, second: Int): GlassStyle.Shade =
        if (kind.second == Control.CHARCOAL) GlassStyle.Shade(tint, charcoal = second) else GlassStyle.Shade(tint, dim = second)

    companion object {
        /**
         * The three glasses as shown, while that's not what's saved: for this run of the app only, as the Screen dimmer's
         * changes not kept ([Dimmer]). Null when nothing is unsaved.
         */
        private var draft: Map<Kind, GlassStyle.Shade>? = null

        /**
         * The glasses whose last Save didn't reach the disk: the file has their values in memory, so they read as saved,
         * but the next start would bring back the old ones. Unsaved until a commit gets there. Main thread.
         */
        private var notOnDisk = emptySet<Kind>()

        /** As a new run of the app: changes not saved are gone (the tests start each from nothing). */
        internal fun forgetUnsaved() {
            draft = null
            notOnDisk = emptySet()
        }

        /**
         * What [kind]'s dropdown row says beside its name: its two values ("58% · 15%", Tint first), or "Clear" when it
         * lays nothing over the frosted glass at all (no tint, and no dimness either).
         */
        fun badge(kind: Kind, shade: GlassStyle.Shade): String =
            if (isClear(shade)) CLEAR else "${shade.tint}% · ${kind.second.of(shade)}%"

        /** Whether [shade] lays nothing over the frosted glass: the glass as it is. */
        fun isClear(shade: GlassStyle.Shade): Boolean = shade.color ushr 24 == 0

        /** "Clear": a glass with nothing laid over it, the frosted picture as it is. */
        const val CLEAR = "Clear"

        /** Playback glass's defaults before it had a tint and a dimness of its own: none ([movePlaybackDefaultsOnce]). */
        internal val OLD_PLAYBACK = GlassStyle.Shade(0, dim = 0)

        /** Saved once Playback glass's values were looked at against its new defaults ([movePlaybackDefaultsOnce]). */
        internal const val PLAYBACK_MOVED_KEY = "glass_playback_defaults_moved"

        /** The settings file ([Settings]), shared with [Prefs] and [Dimmer]. */
        internal fun settingsOf(context: Context): () -> SharedPreferences {
            val settings = Settings.of(context)
            return { settings }
        }
    }
}
