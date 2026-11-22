package app.fireplayer.data

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle

/**
 * Settings › Look's tint and dimness of the two glasses, saved with the other settings, one value each wherever its row
 * shows: App glass (every glass surface of the app's pages and the windows over them) and Playback glass (windows over
 * full-screen films and Live TV), each with its Tint and its Dimness, 0% to 100% in 5% steps ([GlassStyle.Shade]). The
 * players' Screen dimmer windows' Tint is Playback glass's Tint ([app.fireplayer.ui.DimmerWindow.TINT]): the same saved
 * value, so a change in one shows in the other and in Look. [apply] puts them on the glass ([Glass.useShades]).
 */
class GlassShades(private val sp: () -> SharedPreferences) {

    constructor(context: Context) : this(settingsOf(context))

    /** The two glasses: their names in Settings, their saved keys and their defaults ([GlassStyle]). */
    enum class Kind(val title: String, val tintKey: String, val dimKey: String, val default: GlassStyle.Shade) {
        APP("App glass", "app_glass_tint", "app_glass_dim", GlassStyle.APP_SHADE),
        PLAYBACK("Playback glass", "playback_glass_tint", "playback_glass_dim", GlassStyle.PLAYBACK_SHADE),
    }

    /** [kind]'s tint and dimness as saved (its defaults until changed). */
    operator fun get(kind: Kind): GlassStyle.Shade =
        GlassStyle.Shade(read(kind.tintKey, kind.default.tint), read(kind.dimKey, kind.default.dim))

    /** [kind]'s tint a step up ([direction] 1) or down (-1), saved; the shade it has now. */
    fun stepTint(kind: Kind, direction: Int): GlassStyle.Shade {
        write(kind.tintKey, GlassStyle.stepShade(get(kind).tint, direction))
        return get(kind)
    }

    /** [kind]'s dimness a step up ([direction] 1) or down (-1), saved; the shade it has now. */
    fun stepDim(kind: Kind, direction: Int): GlassStyle.Shade {
        write(kind.dimKey, GlassStyle.stepShade(get(kind).dim, direction))
        return get(kind)
    }

    /** Puts both on the glass ([Glass.useShades]): true when that changed it. */
    fun apply(): Boolean = Glass.useShades(get(Kind.APP), get(Kind.PLAYBACK))

    /** [apply], and if that changed the glass every view of [activity]'s window is drawn again with it, once. */
    fun applyTo(activity: Activity) {
        if (apply()) Glass.redraw(activity.window.decorView)
    }

    private fun read(key: String, default: Int): Int = GlassStyle.clampShade(sp().getInt(key, default))

    private fun write(key: String, value: Int) = sp().edit().putInt(key, value).apply()

    private companion object {
        /** The settings file ([Prefs], [Dimmer]'s too). */
        fun settingsOf(context: Context): () -> SharedPreferences {
            val app = context.applicationContext
            return { app.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE) }
        }
    }
}
