package com.folio.reader.narration

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.*

class VoicePackInstallerTest {
    private fun sha(bytes: ByteArray, git: Boolean = false): String {
        val hash = MessageDigest.getInstance(if (git) "SHA-1" else "SHA-256")
        if (git) hash.update("blob ${bytes.size}\u0000".toByteArray())
        return hash.digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun fixture(test: (File,HttpServer,String) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("folio-voice-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.start()
        try { test(root,server,"http://127.0.0.1:${server.address.port}/") }
        finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun resumesOnlyVerifiedRangeAndPublishesAfterValidation() = fixture { root, server, url -> runBlocking {
        val body = "a real pinned model fixture".toByteArray()
        var range: String? = null
        server.createContext("/model") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            val remaining = body.copyOfRange(7,body.size)
            exchange.responseHeaders.add("Content-Range","bytes 7-${body.lastIndex}/${body.size}")
            exchange.sendResponseHeaders(206,remaining.size.toLong())
            exchange.responseBody.use { it.write(remaining) }
        }
        val spec = VoicePackSpec("fixture",url,listOf(VoiceAsset("model",body.size.toLong(),sha(body))))
        File(root,"fixture.partial").mkdirs()
        File(root,"fixture.partial/model").writeBytes(body.copyOfRange(0,7))
        var validated = false
        val installer = VoicePackInstaller(root,true,{
            assertContentEquals(body,File(it,"model").readBytes())
            assertFalse(File(root,"fixture/installed").exists()); validated = true
        },spec = spec)
        installer.install()
        assertEquals("bytes=7-",range)
        assertTrue(validated); assertTrue(installer.isInstalled())
        assertEquals(body.size.toLong(),installer.state.value.totalBytes)
        assertEquals(VoicePackPhase.Installed,installer.state.value.phase)
    } }

    @Test fun serverIgnoringRangeRestartsRatherThanAppending() = fixture { root,server,url -> runBlocking {
        val body = "whole replacement".toByteArray()
        server.createContext("/model") { exchange ->
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        File(root,"fixture.partial").mkdirs(); File(root,"fixture.partial/model").writeText("old")
        val installer = VoicePackInstaller(root,true,{},spec = VoicePackSpec("fixture",url,
            listOf(VoiceAsset("model",body.size.toLong(),sha(body,git = true)))))
        installer.install()
        assertTrue(installer.isInstalled())
        assertContentEquals(body,File(installer.directory,"model").readBytes())
    } }

    @Test fun corruptBytesNeverPublishMarker() = fixture { root,server,url -> runBlocking {
        val expected = "expected".toByteArray()
        server.createContext("/model") { exchange ->
            val body = "tampered".toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        val installer = VoicePackInstaller(root,true,{ error("Must not validate corrupt files") },
            spec = VoicePackSpec("fixture",url,listOf(VoiceAsset("model",8,sha(expected)))))
        installer.install()
        assertEquals(VoicePackPhase.Failed,installer.state.value.phase)
        assertFalse(installer.isInstalled()); assertFalse(File(root,"fixture/installed").exists())
        assertFalse(File(root,"fixture.partial/model").exists())
    } }

    @Test fun invalidContentRangeCannotAppendToPartialModel() = fixture { root,server,url -> runBlocking {
        val body = "expected".toByteArray()
        server.createContext("/model") { exchange ->
            exchange.responseHeaders.add("Content-Range","bytes 0-7/8")
            exchange.sendResponseHeaders(206,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        File(root,"range.partial").mkdirs()
        val partial = File(root,"range.partial/model").apply { writeText("exp") }
        val installer = VoicePackInstaller(root,true,{ error("Must reject this response") },
            spec = VoicePackSpec("range",url,listOf(VoiceAsset("model",8,sha(body)))))
        installer.install()
        assertEquals(VoicePackPhase.Failed,installer.state.value.phase)
        assertFalse(installer.isInstalled()); assertEquals("exp",partial.readText())
    } }

    @Test fun gatedPackRequiresExplicitAccessAndNeverSendsTokenToOtherHosts() = fixture { root,server,url -> runBlocking {
        val body = "model".toByteArray()
        var requests = 0
        server.createContext("/model") { exchange ->
            requests++
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(403,-1); exchange.close()
        }
        val installer = VoicePackInstaller(root,true,{},spec = VoicePackSpec("gated",url,
            listOf(VoiceAsset("model",body.size.toLong(),sha(body))),gated = true))
        installer.install()
        assertEquals(0,requests)
        assertEquals(VoicePackPhase.Failed,installer.state.value.phase)
        installer.install("hf_fixture_secret")
        assertEquals(1,requests)
        assertTrue(installer.state.value.error!!.contains("Access denied"))
        assertFalse(installer.state.value.toString().contains("hf_fixture_secret"))
    } }

    private fun archive(path: String, text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            val data = text.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(path).apply { size = data.size.toLong() })
            tar.write(data); tar.closeArchiveEntry()
        }
        return bytes.toByteArray()
    }
    @Test fun verifiedArchiveInstallsWithoutKeepingDuplicateCompressedWeights() = fixture { root,server,url -> runBlocking {
        val body = archive("pack/model","weights")
        server.createContext("/pack.tar.bz2") { exchange ->
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        val spec = VoicePackSpec("archive",url,listOf(VoiceAsset("pack.tar.bz2",body.size.toLong(),sha(body))),
            archiveRoot = "pack", installedFiles = mapOf("model" to 7L))
        val installer = VoicePackInstaller(root,true,{},spec = spec)
        installer.install()
        assertTrue(installer.isInstalled()); assertEquals("weights",File(installer.directory,"model").readText())
        assertFalse(File(root,"archive.partial").exists())
        assertFalse(File(installer.directory,"pack.tar.bz2").exists())
    } }
    @Test fun archiveTraversalCannotWriteOutsidePrivateStaging() = fixture { root,server,url -> runBlocking {
        val body = archive("pack/../../escaped","attack")
        server.createContext("/pack.tar.bz2") { exchange ->
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        val spec = VoicePackSpec("archive",url,listOf(VoiceAsset("pack.tar.bz2",body.size.toLong(),sha(body))),
            archiveRoot = "pack", installedFiles = mapOf("model" to 1L))
        val installer = VoicePackInstaller(root,true,{},spec = spec)
        installer.install()
        assertEquals(VoicePackPhase.Failed,installer.state.value.phase)
        assertFalse(File(root,"escaped").exists()); assertFalse(installer.isInstalled())
    } }
    @Test fun packsHaveIndependentDirectoriesAndCorrectDownloadSizes() {
        assertEquals(NarrationModel.Phonon40M.downloadBytes,NarrationPacks.phonon40.sizeBytes)
        assertEquals(NarrationModel.Kokoro82M.downloadBytes,NarrationPacks.kokoro.sizeBytes)
        assertEquals(3,NarrationModel.entries.map { NarrationPacks.forModel(it).revision }.toSet().size)
        assertEquals(6,KokoroVoices.all.map { it.id }.toSet().size)
    }
    @Test fun fullyDownloadedPackCanFinishVerificationOffline() = fixture { root,_,url -> runBlocking {
        val body = "cached complete weights".toByteArray()
        val spec = VoicePackSpec("cached",url,listOf(VoiceAsset("model",body.size.toLong(),sha(body))))
        File(root,"cached.partial").mkdirs(); File(root,"cached.partial/model").writeBytes(body)
        val installer = VoicePackInstaller(root,true,{},canDownload = { false },spec = spec)
        installer.install()
        assertTrue(installer.isInstalled())
    } }
}
