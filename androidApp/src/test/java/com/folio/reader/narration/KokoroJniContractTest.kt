package com.folio.reader.narration

import kotlin.test.Test
import kotlin.test.assertEquals

class KokoroJniContractTest {
    @Test fun callbackExposesTheBoxedTypedMethodLookedUpBySherpaJni() {
        val callback = Class.forName("com.folio.reader.narration.KokoroNative\$AudioCallback")
        val method = callback.getDeclaredMethod("invoke",FloatArray::class.java)
        assertEquals(java.lang.Integer::class.java,method.returnType)
    }
}
