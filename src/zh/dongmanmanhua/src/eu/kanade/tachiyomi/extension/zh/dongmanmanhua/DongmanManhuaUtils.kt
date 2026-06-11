package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.util.Log
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

// ══════════════════════════════════════════════════════════════════════
// Cookie 工具
// ══════════════════════════════════════════════════════════════════════

internal fun extractCookieValue(cookieStr: String, key: String): String =
    cookieStr.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.removePrefix("$key=")
        ?.trim() ?: ""

// ══════════════════════════════════════════════════════════════════════
// RSA 加密
// ══════════════════════════════════════════════════════════════════════

internal fun rsaEncryptToHex(data: String, modulusHex: String, exponentHex: String): String {
    val modulus = BigInteger(modulusHex, 16)
    val exponent = BigInteger(exponentHex, 16)
    val keySpec = RSAPublicKeySpec(modulus, exponent)
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    return encrypted.joinToString("") { "%02x".format(it) }
}

// ══════════════════════════════════════════════════════════════════════
// 漫画解析工具
// ══════════════════════════════════════════════════════════════════════

internal fun extractSerialStatus(html: String): String {
    val regex = Regex("""serial_status['":\s]+([A-Z]+)""")
    return regex.find(html)?.groupValues?.get(1) ?: ""
}

internal fun extractUpdateTag(html: String): String {
    val regex = Regex("""在(周[一二三四五六七日天])更新""")
    val match = regex.find(html) ?: return ""
    return "每${match.groupValues[1]}更新"
}

internal fun buildThumbnailUrl(raw: String, cdnBase: String): String {
    if (raw.isEmpty()) return ""
    return when {
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$cdnBase$raw"
        else -> raw
    }
}



internal fun extractThumbnailUrl(element: Element): String {
    val img = element.selectFirst(".pic img, img, a img")
    if (img != null) {
        img.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
        img.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
        img.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
        img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
        img.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
        img.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
        extractUrlFromStyle(img.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val style = element.attr("style")
    if (style.isNotEmpty()) {
        extractUrlFromStyle(style).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val picDiv = element.selectFirst(".pic, .thmb, .chapter-img-c")
    if (picDiv != null) {
        val picStyle = picDiv.attr("style")
        if (picStyle.isNotEmpty()) {
            extractUrlFromStyle(picStyle).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val picImg = picDiv.selectFirst("img")
        if (picImg != null) {
            picImg.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
            picImg.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
            picImg.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
            picImg.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
            picImg.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
            picImg.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
            extractUrlFromStyle(picImg.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        }
    }
    element.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
    element.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
    element.attr("data-thumbnail").takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

internal fun extractUrlFromStyle(style: String): String {
    if (style.isEmpty()) return ""
    val regex = Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'"()]+)['"]?\)""")
    val match = regex.find(style)
    if (match != null) return match.groupValues[1].trim()
    val from = style.indexOf("url(").takeIf { it != -1 }?.plus(4) ?: return ""
    val end = style.indexOf(")", from).takeIf { it != -1 } ?: return ""
    return style.substring(from, end).trim().removeSurrounding("\"").removeSurrounding("'")
}

internal fun extractUrlParam(url: String, key: String): String {
    val regex = Regex("""[?&]$key=([^&]+)""")
    return regex.find(url)?.groupValues?.get(1) ?: ""
}

internal fun extractKeywordFromBody(body: okhttp3.RequestBody?): String {
    if (body is okhttp3.FormBody) {
        for (i in 0 until body.size) {
            if (body.name(i) == "keyword") return body.value(i)
        }
    }
    return ""
}
