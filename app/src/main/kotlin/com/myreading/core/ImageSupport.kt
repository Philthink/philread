package com.myreading.core

import java.net.URLDecoder

fun imageSampleSize(width: Int, height: Int, limit: Int = 2048): Int {
    require(width > 0 && height > 0 && limit > 0)
    var sample = 1
    while (maxOf(width, height) / sample > limit) sample *= 2
    return sample
}

fun Book.imageResource(chapterHref: String, source: String): Resource? {
    val resolved = PathSupport.resolve(chapterHref, source)
    return resourcesByHref[resolved] ?: runCatching {
        resourcesByHref[URLDecoder.decode(resolved.replace("+", "%2B"), "UTF-8")]
    }.getOrNull()
}
