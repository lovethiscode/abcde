package com.unciv.logic.campaign

import com.unciv.UncivGame
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.getPlaceholderText
import com.unciv.models.translations.tr

/** Older results stored rendered Chinese text instead of translation placeholders. */
fun String.trCampaign(): String {
    val icons = takeWhile { it in '\ue000'..'\uf8ff' }
    if (icons.isNotEmpty()) return icons + drop(icons.length).trCampaign()
    val translated = tr(hideIcons = true)
    if (none { it in '\u4e00'..'\u9fff' } || '{' in this ||
        UncivGame.Current.translations[getPlaceholderText()]?.containsKey(UncivGame.Current.settings.language) == true)
        return translated
    val placeholder = Regex("\\[[^]]*]")
    val templates = UncivGame.Current.translations.values.map { it.entry }
        .filter { '[' in it && (it.any { char -> char in '\u4e00'..'\u9fff' } || '：' in it) }
        .sortedByDescending { placeholder.replace(it, "").length }
    for (template in templates) {
        val pattern = placeholder.split(template).joinToString("(.*?)") { Regex.escape(it) }
        val options = if ('\n' in template) setOf(RegexOption.DOT_MATCHES_ALL) else emptySet()
        val match = Regex(pattern, options).matchEntire(this) ?: continue
        return template.fillPlaceholders(*match.groupValues.drop(1).map { it.trCampaign() }.toTypedArray()).tr(hideIcons = true)
    }
    if ('\n' in this) return lines().joinToString("\n") { it.trCampaign() }
    if (": " in this) return split(": ", limit = 2).joinToString(": ") { it.trCampaign() }
    if (" · " in this) return split(" · ").joinToString(" · ") { it.trCampaign() }
    for (reason in CampaignLossReason.entries)
        if (startsWith("${reason.label} ")) return reason.label.tr(hideIcons = true) + removePrefix(reason.label)
    return translated
}
