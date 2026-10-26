package fr.arichard.whooz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {

    private fun release(
        tag: String? = "v1.2",
        assets: String = asset("Whooz.apk", "https://github.com/Purgator/Whooz/releases/download/v1.2/Whooz.apk", 2_500_000),
    ): String {
        val tagField = if (tag == null) "" else "\"tag_name\": \"$tag\","
        return """{ "id": 1, $tagField "name": "Whooz 1.2", "draft": false, "assets": [ $assets ] }"""
    }

    private fun asset(name: String, url: String?, size: Long?): String {
        val fields = ArrayList<String>()
        fields.add("\"name\": \"$name\"")
        if (url != null) fields.add("\"browser_download_url\": \"$url\"")
        if (size != null) fields.add("\"size\": $size")
        fields.add("\"content_type\": \"application/vnd.android.package-archive\"")
        return "{ ${fields.joinToString(", ")} }"
    }

    @Test
    fun picksTheApkAsset() {
        val info = ReleaseParser.parse(release())
        assertNotNull(info)
        assertEquals("v1.2", info!!.tagName)
        assertEquals("1.2", info.version)
        assertEquals("https://github.com/Purgator/Whooz/releases/download/v1.2/Whooz.apk", info.assetUrl)
        assertEquals(2_500_000L, info.assetSize)
    }

    @Test
    fun ignoresOtherAssets() {
        val assets = listOf(
            asset("Whooz-sources.zip", "https://example.com/src.zip", 10),
            asset("whooz.apk", "https://example.com/lower.apk", 20),
            asset("Whooz.apk", "https://example.com/Whooz.apk", 30),
            asset("Whooz.apk.sha256", "https://example.com/sum", 40),
        ).joinToString(", ")
        val info = ReleaseParser.parse(release(assets = assets))
        assertNotNull(info)
        assertEquals("https://example.com/Whooz.apk", info!!.assetUrl)
        assertEquals(30L, info.assetSize)
    }

    @Test
    fun missingAssetYieldsNull() {
        assertNull(ReleaseParser.parse(release(assets = asset("Other.apk", "https://example.com/o.apk", 5))))
        assertNull(ReleaseParser.parse(release(assets = "")))
        assertNull(ReleaseParser.parse("""{ "tag_name": "v1.2" }"""))
    }

    @Test
    fun rejectsNonHttpsUrl() {
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "http://example.com/Whooz.apk", 5))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "ftp://example.com/Whooz.apk", 5))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "", 5))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", null, 5))))
    }

    @Test
    fun rejectsBadSizes() {
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", 0))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", -1))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", ReleaseParser.MAX_APK_BYTES + 1))))
        assertNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", null))))
        assertNotNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", 1))))
        assertNotNull(ReleaseParser.parse(release(assets = asset("Whooz.apk", "https://e.com/a.apk", ReleaseParser.MAX_APK_BYTES))))
    }

    @Test
    fun toleratesMissingOrMalformedInput() {
        assertNull(ReleaseParser.parse(null))
        assertNull(ReleaseParser.parse(""))
        assertNull(ReleaseParser.parse("   "))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("[1, 2, 3]"))
        assertNull(ReleaseParser.parse("{}"))
        assertNull(ReleaseParser.parse(release(tag = null)))
        assertNull(ReleaseParser.parse(release(tag = "")))
        assertNull(ReleaseParser.parse(release(tag = "v")))
        assertNull(ReleaseParser.parse("""{ "tag_name": "v1.2", "assets": "nope" }"""))
        assertNull(ReleaseParser.parse("""{ "tag_name": "v1.2", "assets": [ 1, "two", null ] }"""))
        assertNull(ReleaseParser.parse("""{ "tag_name": "v1.2", "assets": [ { "name": "Whooz.apk", "size": "big" } ] }"""))
    }

    @Test
    fun tagWithoutPrefixAndExtraFieldsStillParse() {
        val json = """{ "tag_name": "2.0", "body": "notes", "assets": [ { "name": "Whooz.apk",
            "browser_download_url": "https://e.com/Whooz.apk", "size": 42, "uploader": { "login": "x" } } ] }"""
        val info = ReleaseParser.parse(json)
        assertNotNull(info)
        assertEquals("2.0", info!!.tagName)
        assertEquals("2.0", info.version)
    }
}
