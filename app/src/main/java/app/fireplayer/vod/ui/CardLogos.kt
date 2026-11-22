package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.fireplayer.R
import app.fireplayer.ui.ImageFit
import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services

/**
 * The logo cards' logos (Home's six streaming services, Disney+'s five studios): the app's own pictures
 * (res/drawable-nodpi/card_logo_*.png, each a mark in its own colours with clear room round it), so nothing is
 * fetched for them. Each is read once, near the size it's drawn at, and kept (eleven small bitmaps). Main thread.
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

    private val headers = HashMap<Any, Bitmap>()

    /**
     * [service]'s logo for its page's name line: its card's own picture, cut to its visible part (the clear room
     * round it is the card's, and would set it off the line) and fitted inside [maxW] x [maxH] px, never enlarged.
     * Made once per service and kept (six small bitmaps); null if it can't be read.
     */
    fun header(context: Context, service: Service, maxW: Int, maxH: Int): Bitmap? =
        header(context, service, resOf(service), maxW, maxH)

    /**
     * The same for one of Disney+'s studios ([brand]): its card's logo in its page's name line, no taller than gives
     * it the room of a logo [evenW] px wide at the full height, so a long wordmark comes out no larger to the eye than
     * a compact mark ([VodSizes.nameLogoH]).
     */
    fun header(context: Context, brand: Brand, maxW: Int, maxH: Int, evenW: Int): Bitmap? =
        header(context, brand, resOf(brand), maxW, maxH, evenW)

    private fun header(context: Context, key: Any, res: Int, maxW: Int, maxH: Int, evenW: Int = 0): Bitmap? {
        headers[key]?.let { return it }
        val src = BitmapFactory.decodeResource(context.resources, res) ?: return null
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val b = LogoBounds.of(px, w, h)
        var part = Bitmap.createBitmap(src, b[0], b[1], b[2] - b[0], b[3] - b[1])
        if (part !== src) src.recycle()
        val fitH = if (evenW > 0) VodSizes.nameLogoH(part.width, part.height, maxH, evenW) else maxH
        val (ow, oh) = ImageFit.inside(part.width, part.height, maxW, fitH)
        // Halved while it's over twice the size it's shown at, so the small picture is smooth, then fitted.
        while (part.width / 2 >= ow && part.height / 2 >= oh && part.width >= 2 && part.height >= 2) part = scaled(part, part.width / 2, part.height / 2)
        if (part.width != ow || part.height != oh) part = scaled(part, ow, oh)
        return part.also { headers[key] = it }
    }

    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap =
        Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }

    private fun resOf(service: Service): Int = when (service) {
        Service.DISNEY -> R.drawable.card_logo_disney_plus
        Service.HULU -> R.drawable.card_logo_hulu
        Service.NETFLIX -> R.drawable.card_logo_netflix
        Service.HBO_MAX -> R.drawable.card_logo_hbo_max
        Service.APPLE_TV -> R.drawable.card_logo_apple_tv
        Service.PRIME_VIDEO -> R.drawable.card_logo_prime_video
    }

    private fun resOf(brand: Brand): Int = when (brand) {
        Brand.DISNEY -> R.drawable.card_logo_disney
        Brand.PIXAR -> R.drawable.card_logo_pixar
        Brand.MARVEL -> R.drawable.card_logo_marvel
        Brand.STAR_WARS -> R.drawable.card_logo_star_wars
        Brand.NAT_GEO -> R.drawable.card_logo_national_geographic
    }
}
