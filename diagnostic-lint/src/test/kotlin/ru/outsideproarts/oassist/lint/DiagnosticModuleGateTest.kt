package ru.outsideproarts.oassist.lint

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertTrue

class DiagnosticModuleGateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newAndroidModuleFailsClassification() {
        val root = temporary.newFolder("project")
        val modules = listOf("app", "core:model", "core:a11y", "core:ui", "core:data", "core:net",
            "core:provider", "core:agent", "core:device", "core:speech", "feature:chat",
            "feature:assistant", "feature:share", "feature:settings", "feature:newcapability")
        File(root, "settings.gradle").writeText(
            "rootProject.name = 'fixture'\n" + (modules + "diagnostic-lint").joinToString("\n") { "include ':$it'" },
        )
        File(root, "build.gradle.kts").writeText("apply(from = \"diagnostics.gradle.kts\")\n")
        File(System.getProperty("oassist.root"), "diagnostics.gradle.kts")
            .copyTo(File(root, "diagnostics.gradle.kts"))
        File(root, "buildSrc").mkdirs()
        File(root, "buildSrc/build.gradle").writeText("""
            plugins { id 'java-gradle-plugin' }
            gradlePlugin { plugins { fakeAndroid {
                id = 'com.android.library'
                implementationClass = 'FakeAndroidPlugin'
            } } }
        """.trimIndent())
        val source = File(root, "buildSrc/src/main/java/FakeAndroidPlugin.java")
        source.parentFile.mkdirs()
        source.writeText("""
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            public class FakeAndroidPlugin implements Plugin<Project> {
                public void apply(Project p) { p.getConfigurations().create("lintChecks"); }
            }
        """.trimIndent())
        modules.forEach { module ->
            val dir = File(root, module.replace(':', '/')).apply { mkdirs() }
            File(dir, "build.gradle").writeText("plugins { id 'com.android.library' }\n")
        }
        File(root, "diagnostic-lint").mkdirs()
        File(root, "diagnostic-lint/build.gradle").writeText("plugins { id 'com.android.library' }\n")
        val result = GradleRunner.create().withProjectDir(root)
            .withArguments("diagnosticModuleClassification", "--stacktrace")
            .buildAndFail()
        assertTrue(result.output.contains(":feature:newcapability"))
        assertTrue(result.output.contains("must be classified for diagnostic coverage"))
        assertTrue(result.output.contains("stale: []"))
    }
}
