package com.folio.reader.manga

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.FolioApplication
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalSerializationApi::class)
@RunWith(AndroidJUnit4::class)
class ExtensionSerializationTest {
    @Test fun backendRegistersProtobufBeforeAnExtensionInitializesItsCodec() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Use the real bootstrap entry point, before a loaded source can request its codec.
        (context.applicationContext as FolioApplication).graph.mangaBackend

        // Extensions may resolve dependencies from a static initializer, not just on the
        // request coroutine. Missing ProtoBuf here used to terminate the whole process.
        val codec = ExtensionCodec.protobuf
        val payload = byteArrayOf(0x0a, 0x03, 0x66, 0x6f, 0x6f, 0x10, 0x2a)
        val decoded = codec.decodeFromByteArray(SourceResponse.serializer(), payload)
        assertEquals(SourceResponse("foo", 42), decoded)
        assertArrayEquals(payload, codec.encodeToByteArray(SourceResponse.serializer(), decoded))

        // Reusing the host bootstrap must keep the existing codecs and network helper.
        val json = Injekt.get<Json>()
        val network = Injekt.get<NetworkHelper>()
        FolioInjektSetup.register(context, network)
        assertSame(codec, Injekt.get<ProtoBuf>())
        assertSame(json, Injekt.get<Json>())
        assertSame(network, Injekt.get<NetworkHelper>())
    }

    private object ExtensionCodec {
        val protobuf: ProtoBuf = Injekt.get()
    }

    @Serializable
    private data class SourceResponse(
        @ProtoNumber(1) val title: String,
        @ProtoNumber(2) val id: Int,
    )
}
