package com.mythbrix.cinetor

import com.mythbrix.cinetor.core.data.AssetReader
import com.mythbrix.cinetor.core.data.MockCatalogSource
import com.mythbrix.cinetor.core.data.MockLatency
import java.io.File

/** Locates the monorepo `shared/` dir from the Gradle test working dir (android/app → ../../shared). */
object TestFiles {
    val sharedDir: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "shared") }
            .firstOrNull { File(it, "mock/home.json").isFile }
            ?: error("shared/mock not found from ${System.getProperty("user.dir")}")
    }

    /** Reads `mock/home.json` etc. straight from shared/ (no Android assets needed). */
    val assetReader = AssetReader { path -> File(sharedDir, path).readText() }

    fun catalog(): MockCatalogSource = MockCatalogSource(assetReader, MockLatency.None)
}
