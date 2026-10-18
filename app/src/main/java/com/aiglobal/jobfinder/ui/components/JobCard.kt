package com.aiglobal.jobfinder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.data.model.Job
import com.aiglobal.jobfinder.data.model.MatchTier
import com.aiglobal.jobfinder.ui.theme.extendedColors

/**
 * Primary job card. Layout is deliberately two-tier: an identity row that can be
 * scanned in one glance, then the decision signals (match, eligibility, visa),
 * then the salary and the call to action.
 */
@Composable
fun JobCard(
    job: Job,
    onViewJob: () -> Unit,
    onApply: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    /**
     * Expat Benefits Score for this posting, or null when it has not been computed.
     * Kept as a separate parameter so the card can never accidentally show it as the
     * CV match score.
     */
    expatScore: Int? = null,
    expatEvidenceCoverage: Int = 0,
    expatLowEvidence: Boolean = false,
    /**
     * International Fit for this posting, or null when it has not been computed. Shown
     * beside the CV match and the Expat Benefits Score so the three are never conflated.
     */
    internationalFit: com.aiglobal.jobfinder.data.model.InternationalFitAssessment? = null,
    /**
     * Whether to show the "NEW" badge. Defaults to the posting's own flag, but the Jobs screen
     * passes the ledger-backed answer so "new" means "first seen since the last review" rather
     * than "posted recently" — the two are different questions.
     */
    showNewBadge: Boolean = job.isNew,
    /**
     * When the app first saw this posting, or null when no first sighting is recorded. Shown
     * as "Discovered …" so the incremental result is dated from the ledger, not guessed.
     */
    discoveredAtEpochMillis: Long? = null,
) {
    val spacing = com.aiglobal.jobfinder.ui.theme.LocalSpacing.current
    val visible = remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { visible.value = true }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(140), label = "cardPress")

    // Staggered entry so a list of cards reveals rather than snapping in at once.
    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(tween(320, delayMillis = index * 60)) +
            slideInVertically(tween(380, delayMillis = index * 60)) { it / 6 },
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(interactionSource = interaction, indication = null, onClick = onViewJob)
                .padding(spacing.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {

                // Row 1 — match gauge, title, company, save
                Row(verticalAlignment = Alignment.Top) {
                    MatchScoreRing(score = job.matchScore, diameter = 56.dp, strokeWidth = 5.dp)
                    Spacer(Modifier.width(spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = job.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (showNewBadge) {
                                Spacer(Modifier.width(6.dp))
                                Pill(
                                    text = "NEW",
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(job.accentColor.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = job.company.firstOrNull()?.uppercase() ?: "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = job.accentColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = job.company,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(spacing.xs))
                    IconChipButton(
                        icon = if (job.isSaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        onClick = onToggleSave,
                        contentDescription = if (job.isSaved) "Remove from saved" else "Save job",
                        tint = if (job.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Row 2 — location, salary, international applicants
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = job.countryFlag, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        IconLabelRow(
                            icon = Icons.Filled.Place,
                            text = locationLabel(job),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    IconLabelRow(
                        icon = Icons.Filled.Payments,
                        text = salaryLabel(job),
                    )
                    if (job.salaryMin > 0 && !job.salaryUnitUncertain && !job.salaryPeriodInferred) {
                        formatSalaryInEur(job.salaryMin, job.salaryMax, job.currencyCode, job.salaryPeriod)?.let { eur ->
                            IconLabelRow(icon = Icons.Filled.CurrencyExchange, text = eur)
                        }
                    }
                    IconLabelRow(
                        icon = Icons.Filled.Public,
                        text = internationalApplicantsLabel(job),
                        tint = if (job.applyableFromMadagascar) extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Row 3 — decision badges (wrapped to two lines on narrow screens)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EligibilityBadge(job.eligibility)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VisaBadge(job.visaSupport, modifier = Modifier.widthIn(max = 220.dp))
                    }
                    if (expatScore != null) {
                        ExpatScoreBadge(
                            score = expatScore,
                            evidenceCoverage = expatEvidenceCoverage,
                            lowEvidence = expatLowEvidence,
                        )
                    }
                    if (internationalFit != null) {
                        InternationalFitBadge(assessment = internationalFit)
                    }
                    if (!job.verification.isEntirelyUnknown) {
                        OfferVerificationBadge(verification = job.verification)
                    }
                    if (job.scamRisk != com.aiglobal.jobfinder.data.model.ScamRisk.LOW) {
                        ScamRiskBadge(job.scamRisk)
                    }
                }

                // Row 4 — attribution: which source supplied this posting, and when the
                // employer published it. Three separate facts are kept apart on this card:
                // "Published" (the source's own date), "Fetched" (this app's last pull) and
                // "Discovered" (the first time this app saw the posting).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        text = job.source.ifBlank { "Unknown source" },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    FreshnessLabel(
                        text = publishedLabel(job.postedAtEpochMillis),
                        modifier = Modifier.weight(1f),
                    )
                }

                // The app's own last fetch, shown only when it adds information the publication
                // date does not: an undated posting, or one the source has kept returning.
                val retrieved = job.retrievedAtEpochMillis
                if (retrieved != null &&
                    (job.postedAtEpochMillis == null || retrieved - job.postedAtEpochMillis > DAY_MS)
                ) {
                    IconLabelRow(
                        icon = Icons.Filled.Verified,
                        text = retrievedLabel(retrieved),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Only when the ledger recorded a first sighting: "Discovered 2h ago" is a fact
                // about this app's search, distinct from when the employer published the role.
                if (discoveredAtEpochMillis != null) {
                    IconLabelRow(
                        icon = Icons.Filled.NewReleases,
                        text = "Discovered ${relativeTime(discoveredAtEpochMillis)}",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    SecondaryButton(
                        text = "View Job",
                        onClick = onViewJob,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                    PrimaryButton(
                        text = "Apply",
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                    )
                }
            }
        }
    }
}

/** "Berlin, Germany" for a normal posting, or just the country for a worldwide remote role. */
private fun locationLabel(job: Job): String =
    if (job.city.isBlank() || job.city.equals(job.country, ignoreCase = true)) job.country
    else "${job.city}, ${job.country}"

private fun internationalApplicantsLabel(job: Job): String = when {
    job.applyableFromMadagascar && job.visaSupport == com.aiglobal.jobfinder.data.model.VisaSupport.FULL_SPONSORSHIP ->
        "Open to international applicants"
    job.applyableFromMadagascar -> "International applicants accepted"
    job.eligibility == com.aiglobal.jobfinder.data.model.MadagascarEligibility.UNCLEAR -> "International status unclear"
    else -> "International applicants restricted"
}

/**
 * Compact horizontal card for the "new opportunities" rail. Same signals, half
 * the height, so it does not compete with the primary list.
 */
@Composable
fun JobCardCompact(
    job: Job,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = com.aiglobal.jobfinder.ui.theme.LocalSpacing.current
    Row(
        modifier = modifier
            .width(268.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(job.accentColor.copy(alpha = 0.9f), job.accentColor.copy(alpha = 0.55f)),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = job.company.take(2).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${job.countryFlag} ${job.country}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MatchChip(job.matchScore)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = postedAgeLabel(job.postedAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Small dot indicating match strength, used in dense lists. */
@Composable
fun MatchDot(tier: MatchTier, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(matchTierColor(tier)),
    )
}

/** One day in milliseconds; the threshold above which a fetch date adds real information. */
private const val DAY_MS = 86_400_000L
