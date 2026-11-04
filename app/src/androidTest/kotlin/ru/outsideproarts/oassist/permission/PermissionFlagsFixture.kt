package ru.outsideproarts.oassist.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Host-only helper for API 31/32, which lack permission-flag shell commands. */
@RunWith(AndroidJUnit4::class)
class PermissionFlagsFixture {
    @Test
    fun updateUserFlags() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("permissionFlagsFixture") == "true") { "Run through the host permission fixture" }
        check(Build.VERSION.SDK_INT in 31..32)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Only an emulator is supported" }
        val permission = arguments.getString("permission")
        require(permission in setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        val userSet = requireNotNull(arguments.getString("userSet")?.toBooleanStrictOrNull())
        val userFixed = requireNotNull(arguments.getString("userFixed")?.toBooleanStrictOrNull())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val user = Process.myUserHandle()
        // These test APIs exist on API 31 but are absent from the public SDK stubs.
        val pmClass = PackageManager::class.java
        val setFlag = pmClass.getField("FLAG_PERMISSION_USER_SET").getInt(null)
        val fixedFlag = pmClass.getField("FLAG_PERMISSION_USER_FIXED").getInt(null)
        val getFlags = pmClass.getMethod("getPermissionFlags", String::class.java, String::class.java, UserHandle::class.java)
        val updateFlags = pmClass.getMethod(
            "updatePermissionFlags", String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, UserHandle::class.java,
        )
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS")
        try {
            val context = instrumentation.targetContext
            val pm = context.packageManager
            val before = getFlags.invoke(pm, permission, context.packageName, user) as Int
            val mask = setFlag or fixedFlag
            val values = (if (userSet) setFlag else 0) or (if (userFixed) fixedFlag else 0)
            updateFlags.invoke(pm, permission, context.packageName, mask, values, user)
            val after = getFlags.invoke(pm, permission, context.packageName, user) as Int
            val expected = (before and mask.inv()) or values
            check(after == expected) { "Permission flags did not reach the requested state" }
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
