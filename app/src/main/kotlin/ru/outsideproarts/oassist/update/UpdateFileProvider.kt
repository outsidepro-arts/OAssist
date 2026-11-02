package ru.outsideproarts.oassist.update

import androidx.core.content.FileProvider

// Android identifies provider components by class: keep APK grants separate from diagnostics.
class UpdateFileProvider : FileProvider()
