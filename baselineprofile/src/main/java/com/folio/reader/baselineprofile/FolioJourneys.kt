package com.folio.reader.baselineprofile

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises actual import, shelves, stats and the HTML reader; no production test entrypoint. */
object FolioJourneys {
    const val PACKAGE = "com.folio.reader"
    const val TITLE = "Folio Profile Journey"
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun enterReader() {
        if (device.findObject(By.clazz("android.webkit.WebView")) == null) {
            // The dock's accessibility label includes the title; its visible label
            // changes after the first reading session.
            val read = device.wait(Until.findObject(By.desc("Read $TITLE")),15_000)
            checkNotNull(read) { "The book's reading action was not found" }.click()
        }
        check(device.wait(Until.hasObject(By.clazz("android.webkit.WebView")),30_000)) {
            "The actual HTML reader did not open"
        }
    }
    fun importFixture() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val uris = (0 until 16).map { index ->
        val title = if (index == 0) TITLE else "$TITLE $index"
        val book = File(context.cacheDir,"profiles/journey-$index.epub").apply { parentFile!!.mkdirs() }
        ZipOutputStream(book.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                val bytes = text.toByteArray()
                val item = ZipEntry(path).apply {
                    time = 0L
                    if (path == "mimetype") {
                        method = ZipEntry.STORED; size = bytes.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(item); zip.write(bytes); zip.closeEntry()
            }
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("content.opf","""<package version="3.0" unique-identifier="id" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">folio-profile-v1-$index</dc:identifier><dc:title>$title</dc:title><dc:creator>Folio</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            zip.putNextEntry(ZipEntry("cover.png").apply { time = 0L }); context.assets.open("profile-cover.png").use { it.copyTo(zip) }; zip.closeEntry()
            entry("chapter.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$TITLE</title></head><body><h1>$TITLE</h1>" +
                (1..100).joinToString("") { "<p>The garden path turned beneath the trees. We walked slowly, watching the first light cross the old stone wall. Beyond it, the morning opened into fields.</p>" } + "</body></html>")
        }
        FileProvider.getUriForFile(context,"com.folio.reader.baselineprofile.fixture",book)
        }
        val incoming = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/epub+zip").setPackage(PACKAGE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        incoming.clipData = android.content.ClipData.newRawUri("Folio fixtures",uris.first()).apply {
            uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        context.startActivity(incoming)
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)),30_000)) { "Folio did not open" }
        // First import asks where to file the book. A duplicate opens the existing book.
        val confirm = device.wait(Until.findObject(By.text("Confirm")),15_000)
        confirm?.click()
        check(device.wait(Until.hasObject(By.text(TITLE)),30_000)) { "Fixture import did not complete" }
        // Import opens book details; enter the actual reader.
        enterReader()
    }
    fun navigationAndReader() {
        // Return through the real reader/detail stack to the navigation shell.
        repeat(3) {
            if (device.findObject(By.desc("Library")) == null && device.findObject(By.text("Library")) == null) device.pressBack()
        }
        fun tab(label: String) {
            val item = device.wait(Until.findObject(By.desc(label)),5000) ?: device.findObject(By.text(label))
            checkNotNull(item) { "Navigation item $label not found" }.click()
            device.waitForIdle()
        }
        tab("Stats"); tab("Home"); tab("Library")
        repeat(4) { device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,20) }
        repeat(4) { device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*3/4,20) }
        val title = device.wait(Until.findObject(By.desc(TITLE)),10_000)
        checkNotNull(title) { "Fixture book is missing from the library" }.click()
        device.waitForIdle()
        enterReader()
        repeat(6) { device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,24) }
    }
}
