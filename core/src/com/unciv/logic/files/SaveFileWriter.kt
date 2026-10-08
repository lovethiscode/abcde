package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle

/** Same-directory publication. Callers keep the slot lock through their save/snapshot transaction. */
object SaveFileWriter {
    private const val TEMP_SUFFIX = ".unciv-tmp"
    private const val BACKUP_SUFFIX = ".unciv-backup"

    fun write(file: FileHandle, writeTemporary: (FileHandle) -> Unit) = SaveFileLocks.withLock(file) {
        check(!file.isDirectory) { "Cannot save over a directory" }
        recover(file)
        val temporary = file.sibling(".${file.name()}$TEMP_SUFFIX")
        val backup = file.sibling(".${file.name()}$BACKUP_SUFFIX")
        try {
            writeTemporary(temporary)
            // POSIX platforms, including iOS, replace an existing target in one rename.
            if (!temporary.file().renameTo(file.file())) {
                // Some platforms cannot rename over an existing file. Keep the previous bytes
                // recoverable, and let slot-locked readers wait through this fallback.
                if (file.exists() && !file.file().renameTo(backup.file()))
                    error("Could not protect the previous save")
                if (!temporary.file().renameTo(file.file())) {
                    recover(file)
                    error("Could not replace the save")
                }
                backup.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    fun recover(file: FileHandle) {
        val backup = file.sibling(".${file.name()}$BACKUP_SUFFIX")
        if (!backup.exists()) return
        SaveFileLocks.withLock(file) {
            if (!backup.exists()) return@withLock
            if (!file.exists() && !backup.file().renameTo(file.file()))
                error("Could not recover the previous save")
            if (file.exists() && backup.exists() && !backup.delete())
                error("Could not remove the previous save backup")
        }
    }

    fun recoverDirectory(directory: FileHandle) {
        for (artifact in directory.list()) {
            if (!artifact.file().isFile || !artifact.name().startsWith('.') || !artifact.name().endsWith(BACKUP_SUFFIX)) continue
            val name = artifact.name().removePrefix(".").removeSuffix(BACKUP_SUFFIX)
            if (name.isNotEmpty()) recover(directory.child(name))
        }
    }

    fun isTemporary(file: FileHandle) = file.name().startsWith('.')
        && (file.name().endsWith(TEMP_SUFFIX) || file.name().endsWith(BACKUP_SUFFIX))
}
