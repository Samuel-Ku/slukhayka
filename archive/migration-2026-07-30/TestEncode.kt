import java.net.URLEncoder

fun main() {
    val url = "https://s1.reasd.org/7611/аудіокнига ВКРАДИ МЕНЕ ЗАРАЗ.m4a"
    // Since android.net.Uri is not easily mocked here, let's just use what android does.
    // android.net.Uri.encode uses a custom implementation, but we can test if replacing {v1} worked.
}
