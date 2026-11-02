package io.github.rafaeljjd.feedstudy.task

/** URL shapes to try on a real phone. Which one opens the exact video is unverified. */
object DeepLinks {
    fun build(template: String, videoId: String): String = template.replace("{id}", videoId)

    fun candidateForms(videoId: String, originalUrl: String?): List<Pair<String, String>> =
        buildList {
            if (originalUrl != null) add("Original link" to originalUrl)
            add("tiktok.com/@/video" to "https://www.tiktok.com/@/video/$videoId")
            add("m.tiktok.com/v" to "https://m.tiktok.com/v/$videoId.html")
            add("snssdk1233 scheme" to "snssdk1233://aweme/detail/$videoId")
        }
}
