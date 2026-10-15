package dev.trellis.kernel.time

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformMonotonicTimeMs(): Long = System.nanoTime() / 1_000_000L