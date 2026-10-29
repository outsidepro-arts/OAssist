package ru.outsideproarts.oassist.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.detector.api.CURRENT_API

class DiagnosticIssueRegistry : IssueRegistry() {
    override val issues = listOf(DiagnosticBoundaryDetector.ISSUE)
    override val api = CURRENT_API
    override val minApi = CURRENT_API
}
