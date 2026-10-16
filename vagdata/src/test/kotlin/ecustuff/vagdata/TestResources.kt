package ecustuff.vagdata

import java.io.File

private object Anchor

fun resource(path: String): ByteArray =
    (Anchor::class.java.getResourceAsStream("/$path") ?: error("missing test resource /$path")).use { it.readBytes() }

fun resourceFile(path: String): File =
    File((Anchor::class.java.getResource("/$path") ?: error("missing test resource /$path")).toURI())
