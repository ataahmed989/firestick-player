package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.fireplayer.R
import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Services
import java.util.EnumMap

/**
 * The Disney+ studios' logos for their cards: the app's own pictures (res/drawable-nodpi), each read once and kept
 * (five small bitmaps). Main thread.
 */
internal object StudioLogos {

    private val logos = EnumMap<Brand, Bitmap>(Brand::class.java)

    /** The logo of the studio whose card has [id]; null for any other card. */
    fun of(context: Context, id: String): Bitmap? {
        val brand = Services.brandOf(id) ?: return null
        logos[brand]?.let { return it }
        val bitmap = BitmapFactory.decodeResource(context.resources, resOf(brand)) ?: return null
        logos[brand] = bitmap
        return bitmap
    }

    private fun resOf(brand: Brand): Int = when (brand) {
        Brand.DISNEY -> R.drawable.studio_disney
        Brand.PIXAR -> R.drawable.studio_pixar
        Brand.MARVEL -> R.drawable.studio_marvel
        Brand.STAR_WARS -> R.drawable.studio_star_wars
        Brand.NAT_GEO -> R.drawable.studio_national_geographic
    }
}
