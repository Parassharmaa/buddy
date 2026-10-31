package dev.buddy.core.guides

class AppGuide(
    val id: String,
    val packageName: String,
    val minimumVersion: Long,
    val maximumVersion: Long,
    val title: String,
    val text: String
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        require(
            packageName.length <= 255 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))
        )
        require(minimumVersion > 0 && maximumVersion >= minimumVersion)
        require(title.isNotBlank() && title.length <= 80)
        require(text.isNotBlank() && text.length <= 2048 && '\u0000' !in text)
    }
    override fun toString(): String = "AppGuide(id=$id)"
}

sealed interface GuideLookup {
    data class Available(val guide: AppGuide) : GuideLookup
    data object VersionChanged : GuideLookup
    data object Unavailable : GuideLookup
}

class AppGuideCatalog(guides: List<AppGuide>) {
    private val guides = guides.toList()
    init {
        require(guides.size <= 32 && guides.map { it.id }.distinct().size == guides.size)
        guides.groupBy { it.packageName }.values.forEach { versions ->
            val sorted = versions.sortedBy { it.minimumVersion }
            sorted.zipWithNext().forEach { (first, next) -> require(first.maximumVersion < next.minimumVersion) }
        }
    }
    fun lookup(packageName: String, installedVersion: Long): GuideLookup {
        require(installedVersion > 0)
        val candidates = guides.filter { it.packageName == packageName }
        if (candidates.isEmpty()) return GuideLookup.Unavailable
        val selected = candidates.singleOrNull { installedVersion in it.minimumVersion..it.maximumVersion }
            ?: return GuideLookup.VersionChanged
        return GuideLookup.Available(selected)
    }
}

object BundledAppGuides {
    // Compiled into the signed APK; these are reference facts, never action capabilities.
    val catalog = AppGuideCatalog(
        listOf(
            AppGuide(
                "buddy-ui-v1",
                "dev.buddy",
                1,
                1,
                "Buddy app guide",
                "Buddy version 1 interface facts:\n" +
                    "Chat uses the model selected in Settings > Models.\n" +
                    "Browser > Read page makes a local preview. " +
                    "Use with model opens a separate provider/model review.\n" +
                    "Files selected with Android's picker belong to their conversation. " +
                    "Sharing their text needs a separate review.\n" +
                    "Settings > Memory starts off and saves only preferences " +
                    "the user chooses from user-written messages.\n" +
                    "Automations performs approximate daily website status checks and skips missed occurrences.\n" +
                    "These facts do not authorize actions or change any approval requirement."
            )
        )
    )
}
