package com.folio.reader

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.test.Test
import kotlin.test.assertTrue

class ExtensionSerializationCompatibilityTest {
    @OptIn(InternalSerializationApi::class)
    @Test
    fun runtimeProvidesDefaultMethodOmittedByModernExtensionSerializers() {
        // Precompiled extension serializers omit this method and inherit the JVM
        // default. 1.7.3 declared it abstract and crashed in descriptor.hashCode().
        val method = GeneratedSerializer::class.java.getMethod("typeParametersSerializers")
        assertTrue(method.isDefault, "Extension serializers require a JVM default implementation")
    }
}
