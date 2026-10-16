package com.roadconquest.app.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Service response too large")
        output.write(buffer, 0, count)
    }
}
