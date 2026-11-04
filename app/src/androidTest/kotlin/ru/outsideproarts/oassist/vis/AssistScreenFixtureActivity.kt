package ru.outsideproarts.oassist.vis

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** Runs in the test APK so the system captures a different application from the assistant. */
class AssistScreenFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("secure", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        setContentView(TextView(this).apply {
            text = intent.getStringExtra("marker")
            textSize = 26f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.MAGENTA)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
    }
}
