package app.fireplayer.vod.ui

import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.Kind
import app.fireplayer.vod.TitleDetails

/**
 * The title page's Details window, drawn in the app's settings window ([SidePanel], wide) from its shared parts, so it
 * looks like every other window: the whole story, the facts (year, length, rating, genres, country, IMDb rating,
 * awards), all the cast, the directors and writers (a show: its creators), and where each came from ("from Cinemeta",
 * "from TMDB").
 */
internal fun SidePanel.titleDetails(d: TitleDetails) {
    val story = TitleRules.story(d)
    if (story.isNotEmpty()) {
        header("Story")
        textBlock(story)
    }
    val facts = TitleRules.detailsFacts(d)
    if (facts.isNotEmpty()) {
        header("Facts")
        facts(facts.map { (name, value) -> name to fact(value) })
    }
    val cast = d.cast?.value.orEmpty().filter { it.isNotBlank() }
    if (cast.isNotEmpty()) {
        header("Starring")
        textBlock(cast.joinToString(", "))
    }
    val crew = ArrayList<Pair<String, List<String>>>()
    if (d.title.kind == Kind.MOVIE) {
        d.directors?.value?.takeIf { it.isNotEmpty() }?.let { crew += (if (it.size == 1) "Director" else "Directors") to it }
        d.writers?.value?.takeIf { it.isNotEmpty() }?.let { crew += (if (it.size == 1) "Writer" else "Writers") to it }
    } else {
        d.creators?.value?.takeIf { it.isNotEmpty() }?.let { crew += "Created by" to it }
    }
    if (crew.isNotEmpty()) {
        header("Crew")
        facts(crew.map { (name, people) -> name to fact(people.joinToString(", ")) })
    }
    for (note in TitleRules.sourceNotes(d)) message(note)
}
