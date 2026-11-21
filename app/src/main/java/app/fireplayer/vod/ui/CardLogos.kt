package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.fireplayer.R
import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services

/**
 * The logo cards' logos (Home's five streaming services, Disney+'s five studios): the app's own pictures
 * (res/drawable-nodpi/card_logo_*.png, each a mark in its own colours with clear room round it), so nothing is
 * fetched for them. Each is read once, near the size it's drawn at, and kept (ten small bitmaps). Main thread.
 */
internal object CardLogos {

    /** A card's logo: the whole picture, and how much of the card's width it takes ([VodSizes.logoShare]). */
    class Logo(val bitmap: Bitmap, val share: Float)

    private val logos = HashMap<String, Logo>()

    /** The logo of the service or studio whose card has [id], for a card [cardW] px wide; null for any other card. */
    fun of(context: Context, id: String, cardW: Int): Logo? {
        logos[id]?.let { return it }
        val service = Services.serviceOf(id)
        val brand = Services.brandOf(id)
        val res = service?.let { resOf(it) } ?: brand?.let { resOf(it) } ?: return null
        val share = service?.let { VodSizes.logoShare(it) } ?: VodSizes.logoShare(brand!!)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, res, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = VodSizes.logoSample(bounds.outWidth, cardW * share) }
        val bitmap = BitmapFactory.decodeResource(context.resources, res, options) ?: return null
        return Logo(bitmap, share).also { logos[id] = it }
    }

    private fun resOf(service: Service): Int = when (service) {
        Service.DISNEY -> R.drawable.card_logo_disney_plus
        Service.HULU -> R.drawable.card_logo_hulu
        Service.NETFLIX -> R.drawable.card_logo_netflix
        Service.HBO_MAX -> R.drawable.card_logo_hbo_max
        Service.APPLE_TV -> R.drawable.card_logo_apple_tv
    }

    private fun resOf(brand: Brand): Int = when (brand) {
        Brand.DISNEY -> R.drawable.card_logo_disney
        Brand.PIXAR -> R.drawable.card_logo_pixar
        Brand.MARVEL -> R.drawable.card_logo_marvel
        Brand.STAR_WARS -> R.drawable.card_logo_star_wars
        Brand.NAT_GEO -> R.drawable.card_logo_national_geographic
    }
}
