package ru.outsideproarts.oassist.core.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService

data class RecognitionServiceInfo(
    val componentName: String,
    val label: String,
)

class RecognitionServiceCatalog(
    private val context: Context,
    private val excludedComponents: Set<ComponentName> = emptySet(),
) {
    private val packageManager: PackageManager
        get() = context.packageManager

    fun availableServices(): List<RecognitionServiceInfo> {
        val query = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = packageManager.queryIntentServices(query, 0)
            .orEmpty()
            .mapNotNull { resolveInfo ->
                val service = resolveInfo.serviceInfo ?: return@mapNotNull null
                val component = ComponentName(service.packageName, service.name)
                if (!service.enabled || !service.exported || component in excludedComponents) {
                    return@mapNotNull null
                }

                val appInfo = service.applicationInfo ?: return@mapNotNull null
                val serviceLabel = service.loadLabel(packageManager)?.toString().orEmpty()
                val appLabel = packageManager.getApplicationLabel(appInfo)?.toString().orEmpty()
                RecognitionServiceCatalogPolicy.Candidate(
                    componentName = component.flattenToString(),
                    serviceLabel = serviceLabel,
                    appLabel = appLabel,
                    appEnabled = appInfo.enabled,
                )
            }
        return RecognitionServiceCatalogPolicy.toInfos(services)
    }

    fun resolveComponent(savedComponent: String?): ComponentName? {
        if (savedComponent.isNullOrBlank()) return null
        val component = ComponentName.unflattenFromString(savedComponent) ?: return null
        val flattened = RecognitionServiceCatalogPolicy.availableComponent(
            component.flattenToString(),
            availableServices().mapTo(mutableSetOf(), RecognitionServiceInfo::componentName),
        ) ?: return null
        return ComponentName.unflattenFromString(flattened)
    }
}

internal object RecognitionServiceCatalogPolicy {
    internal data class Candidate(
        val componentName: String,
        val serviceLabel: String,
        val appLabel: String,
        val appEnabled: Boolean,
    )

    fun toInfos(candidates: List<Candidate>): List<RecognitionServiceInfo> {
        val usable = candidates
            .asSequence()
            .filter { it.appEnabled && it.componentName.isNotBlank() }
            .distinctBy(Candidate::componentName)
            .toList()

        fun Candidate.baseLabel(): String {
            val appLabel = appLabel.ifBlank { componentName.substringBefore('/') }
            val serviceLabel = serviceLabel.ifBlank { appLabel }
            return if (serviceLabel == appLabel) serviceLabel else "$serviceLabel — $appLabel"
        }

        val baseLabelCounts = usable.groupingBy(Candidate::baseLabel).eachCount()
        val packageCounts = usable.groupingBy { it.componentName.substringBefore('/') }.eachCount()

        return usable.map { candidate ->
            val baseLabel = candidate.baseLabel()
            val packageName = candidate.componentName.substringBefore('/')
            val label = buildString {
                append(baseLabel)
                if (baseLabelCounts.getValue(baseLabel) > 1) {
                    append(" (").append(packageName)
                    if (packageCounts.getValue(packageName) > 1) {
                        append('/').append(candidate.componentName.substringAfter('/'))
                    }
                    append(')')
                }
            }
            RecognitionServiceInfo(candidate.componentName, label)
        }
    }

    fun availableComponent(savedComponent: String?, availableComponents: Set<String>): String? {
        val saved = savedComponent?.takeIf(String::isNotBlank) ?: return null
        return saved.takeIf(availableComponents::contains)
    }
}
