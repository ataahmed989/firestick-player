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
 * the same saved values, so a change in one shows in the other. The Playback glass in use is the preset on there
 * ([Dimmer.glass]: Default's, Look's, until a custom is picked). [apply] puts them on the glass ([Glass.useShades]). Only
 * the glass's own layers change: never text, icons or the focus highlight drawn on it.
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

    /** [kind]'s tint and its second control as saved (its defaults until changed). */
    operator fun get(kind: Kind): GlassStyle.Shade {
        if (kind == Kind.PLAYBACK) movePlaybackDefaults()
        val tint = read(kind.tintKey, kind.default.tint)
        val second = read(kind.secondKey, kind.second.of(kind.default))
        return if (kind.second == Control.CHARCOAL) GlassStyle.Shade(tint, charcoal = second) else GlassStyle.Shade(tint, dim = second)
    }

    /**
     * [kind]'s [control] a step up ([direction] 1) or down (-1), saved; the shade it has now. The steps are 5% apart,
     * with the glass's own default a stop on the way where it's between two ([GlassStyle.stepShade]).
     */
    fun step(kind: Kind, control: Control, direction: Int): GlassStyle.Shade {
        write(kind.keyOf(control), GlassStyle.stepShade(control.of(get(kind)), direction, stop = control.of(kind.default)))
        return get(kind)
    }

    /**
     * Reset to defaults: all three glasses back to their defaults ([Kind.default]), as if never changed. Playback glass is
     * the players' Default preset, so Default is back to its defaults too, with no display dimness (one tried on it and
     * not saved goes). The customs stay as they are, and so does the preset on: a custom picked stays picked.
     */
    fun reset() {
        movePlaybackDefaults()
        val edit = sp().edit()
        for (kind in Kind.entries) edit.remove(kind.tintKey).remove(kind.secondKey)
        edit.apply()
        Dimmer.forgetDefaultDraft()
    }

    /**
     * Once: Playback glass's defaults were no tint and no dimness ([OLD_PLAYBACK]) before [GlassStyle.PLAYBACK_SHADE]. A
     * Playback glass never changed (nothing saved, or still those) takes the new defaults; one the viewer changed keeps
     * their values, each as it showed (one never saved was the old default).
     */
    private fun movePlaybackDefaults() {
        if (playbackMoved) return
        playbackMoved = true
        val saved = sp()
        if (saved.getBoolean(PLAYBACK_MOVED_KEY, false)) return
        val kind = Kind.PLAYBACK
        val tint = GlassStyle.clampShade(saved.getInt(kind.tintKey, OLD_PLAYBACK.tint))
        val dim = GlassStyle.clampShade(saved.getInt(kind.secondKey, OLD_PLAYBACK.dim))
        val edit = saved.edit().putBoolean(PLAYBACK_MOVED_KEY, true)
        if (tint == OLD_PLAYBACK.tint && dim == OLD_PLAYBACK.dim) edit.remove(kind.tintKey).remove(kind.secondKey)
        else edit.putInt(kind.tintKey, tint).putInt(kind.secondKey, dim)
        edit.apply()
    }

    /** [movePlaybackDefaults] done, by this reader. */
    private var playbackMoved = false

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

    private fun write(key: String, value: Int) = sp().edit().putInt(key, value).apply()

    companion object {
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

        /** Playback glass's defaults before it had a tint and a dimness of its own: none ([movePlaybackDefaults]). */
        internal val OLD_PLAYBACK = GlassStyle.Shade(0, dim = 0)

        /** Saved once Playback glass's values were looked at against its new defaults ([movePlaybackDefaults]). */
        internal const val PLAYBACK_MOVED_KEY = "glass_playback_defaults_moved"

        /** The settings file ([Prefs], [Dimmer]'s too). */
        internal fun settingsOf(context: Context): () -> SharedPreferences {
            val app = context.applicationContext
            return { app.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE) }
        }
    }
}
