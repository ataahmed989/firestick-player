package app.fireplayer.shell

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * Fonts beyond the app's Figtree. The Top 10 rows' big rank numbers use Inter ExtraBold (weight 800), as Glass Home's
 * ranked shelf does: Inter's variable font (SIL Open Font License 1.1, assets/fonts/Inter-OFL.txt) is in the app's
 * assets. Android 8 and later make the 800 weight from it; older Android can't, and uses Figtree Bold instead.
 */
object Fonts {
    @Volatile private var rankFace: Typeface? = null

    /** Inter ExtraBold for rank numbers, or [fallback] (Figtree Bold) where Inter's weights can't be made. */
    fun rank(context: Context, fallback: Typeface): Typeface {
        rankFace?.let { return it }
        val face = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Typeface.Builder(context.applicationContext.assets, "fonts/Inter-Variable.ttf")
                    .setFontVariationSettings("'wght' 800")
                    .setFallback("sans-serif")
                    .build()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        return (face ?: fallback).also { rankFace = it }
    }
}
