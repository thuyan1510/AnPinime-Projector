package com.anpinime.projector

internal object SourceIsolation {
    fun items(source: ContentSource, items: List<Anime>) = items.filter { it.source == source }
    fun sections(source: ContentSource, sections: List<AnimeSection>) =
        sections.map { it.copy(items = items(source, it.items)) }.filter { it.items.isNotEmpty() }
    fun historyKey(source: ContentSource) = "search_history_${source.id}_json"
}
