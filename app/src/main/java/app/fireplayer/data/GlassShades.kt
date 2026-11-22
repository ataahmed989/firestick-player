package app.fireplayer.data

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle

/**
 * Settings › Look's three glasses, saved with the other settings, one value each wherever its row shows: App glass (the
 * windows over the app's pages), Rail, cards & header (the glass in the pages themselves) and Playback glass (windows
 * over full-screen films and Live TV), each with its Tint and its Dimness, 0% to 100% in 5% steps ([GlassStyle.Shade]).
 * The players' Screen dimmer windows' Tint is Playback glass's Tint ([app.fireplayer.ui.DimmerWindow.TINT_GLASS]): the same
 * saved value, so a change in one shows in the other and in Look. [apply] puts them on the glass ([Glass.useShades]).
 */
class GlassShades(private val sp: () -> SharedPreferences) {

    constructor(context: Context) : this(settingsOf(context))

    /**
     * The three glasses, in Settings › Look's order: their dropdown rows' names, their saved keys and their defaults
     * ([GlassStyle]). The keys are their own (not the first draft's), so no value saved with another meaning is read.
     */
    enum class Kind(val title: String, val tintKey: String, val dimKey: String, val default: GlassStyle.Shade) {
        APP("App glass", "glass_app_tint", "glass_app_dimness", GlassStyle.APP_SHADE),
        RAIL("Rail, cards & header", "glass_rail_tint", "glass_rail_dimness", GlassStyle.RAIL_SHADE),
        PLAYBACK("Playback glass", "glass_playback_tint", "glass_playback_dimness", GlassStyle.PLAYBACK_SHADE),
        ;

        /** Its dropdown in Settings › Look, open or closed ([app.fireplayer.ui.SidePanel.isPartOpen]). */
        val part: String get() = "glass_$name"
    }

    /**
     * What each glass's dropdown opens to, in order, its rows' names: [TINT], how strong its colour layer is, and
     * [DIMNESS], where that colour sits between the background's own and charcoal ([GlassStyle.Shade]).
     */
    enum class Control(val label: String) {
        TINT("Tint"),
        DIMNESS("Dimness"),
        ;

        /** Its value in [shade], percent. */
        fun of(shade: GlassStyle.Shade): Int = if (this == TINT) shade.tint else shade.dim

        /** Its saved key for [kind]. */
        fun keyOf(kind: Kind): String = if (this == TINT) kind.tintKey else kind.dimKey
    }

    /** [kind]'s tint and dimness as saved (its defaults until changed). */
    operator fun get(kind: Kind): GlassStyle.Shade =
        GlassStyle.Shade(read(kind.tintKey, kind.default.tint), read(kind.dimKey, kind.default.dim))

    /**
     * [kind]'s [control] a step up ([direction] 1) or down (-1), saved; the shade it has now. The steps are 5% apart,
     * with the glass's own default a stop on the way where it's between two ([GlassStyle.stepShade]).
     */
    fun step(kind: Kind, control: Control, direction: Int): GlassStyle.Shade {
        write(control.keyOf(kind), GlassStyle.stepShade(control.of(get(kind)), direction, stop = control.of(kind.default)))
        return get(kind)
    }

    /** Puts all three on the glass ([Glass.useShades]): true when that changed it. */
    fun apply(): Boolean = Glass.useShades(get(Kind.APP), get(Kind.RAIL), get(Kind.PLAYBACK))

    /** [apply], and if that changed the glass every view of [activity]'s window is drawn again with it, once. */
    fun applyTo(activity: Activity) {
        if (apply()) Glass.redraw(activity.window.decorView)
    }

    private fun read(key: String, default: Int): Int = GlassStyle.clampShade(sp().getInt(key, default))

    private fun write(key: String, value: Int) = sp().edit().putInt(key, value).apply()

    companion object {
        /**
         * What a glass's dropdown row says beside its name: its tint and its dimness ("58% · 30%"), or "Clear" with no
         * tint (no colour layer at all, whatever the dimness).
         */
        fun badge(shade: GlassStyle.Shade): String = if (shade.tint == 0) CLEAR else "${shade.tint}% · ${shade.dim}%"

        /** "Clear": a glass with no tint, the frosted picture as it is. */
        const val CLEAR = "Clear"

        /** The settings file ([Prefs], [Dimmer]'s too). */
        private fun settingsOf(context: Context): () -> SharedPreferences {
            val app = context.applicationContext
            return { app.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE) }
        }
    }
}
