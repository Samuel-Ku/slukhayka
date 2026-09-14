package com.slukhayka.audiobooks.data.privacy

import okhttp3.HttpUrl

/** Legacy Archive links have a working HTTPS endpoint. Never enable cleartext globally. */
object KnownSourceHttps {
    fun upgrade(url: HttpUrl): HttpUrl =
        if (url.scheme == "http" && url.host == "archive.org" && url.port == 80) {
            url.newBuilder().scheme("https").port(443).build()
        } else url
}
