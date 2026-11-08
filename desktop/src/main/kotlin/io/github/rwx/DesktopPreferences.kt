package io.github.rwx

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Personal settings live outside the macOS app bundle so replacing it keeps them intact. */
internal object DesktopPreferences {
    fun resolveFile(
        rootDir: File,
        userDataDir: File,
        osName: String = System.getProperty("os.name", ""),
        launchDir: String? = System.getProperty("launch.dir"),
    ): File {
        val legacyFile = File(rootDir, "${PREFERENCE_NAME}.toml")
        // An explicit launch.dir keeps portable launches and diagnostic runs isolated.
        if (!osName.contains("mac", ignoreCase = true) || launchDir != null) return legacyFile

        val file = File(userDataDir, "${PREFERENCE_NAME}.toml")
        if (!file.exists() && legacyFile.isFile) {
            Files.createDirectories(userDataDir.toPath())
            val temporaryFile = Files.createTempFile(userDataDir.toPath(), "preferences-", ".tmp")
            try {
                // Publish only a complete copy; a failed copy must not leave partial settings.
                Files.copy(legacyFile.toPath(), temporaryFile, StandardCopyOption.REPLACE_EXISTING)
                // Keep the original and never replace settings saved by another installation.
                Files.move(temporaryFile, file.toPath())
            } catch (_: FileAlreadyExistsException) {
                // Another instance completed the migration first.
            } finally {
                Files.deleteIfExists(temporaryFile)
            }
        }
        return file
    }
}
