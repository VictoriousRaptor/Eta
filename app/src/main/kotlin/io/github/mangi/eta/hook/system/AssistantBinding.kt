package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
)

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.OEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ETA -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ETA_PACKAGE,
        componentName = ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT,
        displayName = "Eta",
    )
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): Boolean = autoConfigEnabled && target != PowerAssistantTarget.OEM

internal enum class AssistantSelectionAction {
    NONE,
    CONFIGURE_MANAGED,
}

internal fun assistantSelectionAction(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): AssistantSelectionAction = when {
    shouldConfigureAssistant(autoConfigEnabled, target) ->
        AssistantSelectionAction.CONFIGURE_MANAGED
    // 系统默认或关闭自动配置时，保留用户已有的默认助理。
    else -> AssistantSelectionAction.NONE
}

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget) &&
    expectedTarget == currentTarget
