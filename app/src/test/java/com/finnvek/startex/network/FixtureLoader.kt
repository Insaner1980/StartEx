package com.finnvek.startex.network

internal fun fixture(path: String): String =
    checkNotNull(object {}.javaClass.getResource("/network/$path")) { "Missing fixture: $path" }
        .readText()
