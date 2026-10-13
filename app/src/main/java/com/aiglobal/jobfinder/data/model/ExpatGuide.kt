package com.aiglobal.jobfinder.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Grouping used to lay the guide out in sections. */
@Serializable
enum class BenefitCategory(val label: String, val emoji: String) {
    WORK_VISA("Work permit & visa", "🛂"),
    RELOCATION("Relocation support", "📦"),
    FAMILY("Family & dependants", "👨‍👩‍👧"),
    HEALTH("Health & insurance", "🏥"),
    TAX("Tax & social security", "🧾"),
    SETTLEMENT("Settlement & language", "🏠"),
    CITIZENSHIP("Long-term residence", "🛡️"),
}

/**
 * A single settlement benefit, always tied to an official source. [sourceName] and
 * [sourceUrl] are required so the user can verify the claim on the government's own
 * page before relying on it.
 */
@Immutable
@Serializable
data class BenefitItem(
    val category: BenefitCategory,
    val title: String,
    val detail: String,
    val sourceName: String,
    val sourceUrl: String,
)

/**
 * What an employer or a country typically offers an incoming worker. Deliberately
 * conservative: each country lists only items that are true for that country's normal
 * legal route, and the guide tells the user to confirm with the employer.
 */
@Immutable
@Serializable
data class ExpatGuide(
    val country: String,
    val countryFlag: String,
    val summary: String,
    val benefits: List<BenefitItem>,
    val practicalNotes: List<String>,
) {
    val groupedBenefits: Map<BenefitCategory, List<BenefitItem>>
        get() = benefits.groupBy { it.category }
            .toSortedMap(compareBy { it.ordinal })
}
