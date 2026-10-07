package ru.outsideproarts.oassist.feature.assistant

import android.Manifest
import android.app.assist.AssistStructure
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

/**
 * Trampoline для assist-жеста: принимает AssistStructure (или готовый screen_context из VIS),
 * запрашивает спецдоступ «поверх окон» и микрофон, поднимает оверлей-сервис и закрывается —
 * пользователь остаётся в своём приложении.
 */
class AssistActivity : AppCompatActivity() {

    private var screenContext: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenContext = extractScreenContext(intent)
            ?: intent?.getStringExtra(AssistantOverlayService.EXTRA_SCREEN_CONTEXT)

        if (!Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            )
            overlayLauncher.launch(overlayIntent)
            return
        }
        ensureMicAndStart()
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(this)) {
            ensureMicAndStart()
        } else {
            Toast.makeText(this, R.string.assistant_no_overlay, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Отказ не блокирует окно: текстовый ввод остаётся, сервис покажет подсказку.
        startServiceAndStartOverlay()
    }

    private fun ensureMicAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startServiceAndStartOverlay()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startServiceAndStartOverlay() {
        val serviceIntent = Intent(this, AssistantOverlayService::class.java)
        screenContext?.let {
            serviceIntent.putExtra(AssistantOverlayService.EXTRA_SCREEN_CONTEXT, it)
        }
        startForegroundService(serviceIntent)
        finish()
    }

    private fun extractScreenContext(intent: Intent?): String? {
        return try {
            val assistStructure = intent?.getParcelableExtra<AssistStructure>(
                "android.app.assist.AssistStructure",
            )
            assistStructure?.let { structure ->
                val sb = StringBuilder()
                for (i in 0 until structure.windowNodeCount) {
                    val window = structure.getWindowNodeAt(i)
                    dumpNode(window.rootViewNode, sb, 0)
                }
                sb.toString().take(2000).ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun dumpNode(node: AssistStructure.ViewNode, sb: StringBuilder, depth: Int) {
        if (depth > 5) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotBlank() || desc.isNotBlank()) {
            sb.appendLine("${"  ".repeat(depth)}${if (text.isNotBlank()) text else desc}")
        }
        for (i in 0 until node.childCount) {
            dumpNode(node.getChildAt(i), sb, depth + 1)
        }
    }
}
