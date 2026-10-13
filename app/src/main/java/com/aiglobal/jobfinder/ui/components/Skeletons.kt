package com.aiglobal.jobfinder.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiglobal.jobfinder.ui.theme.LocalSpacing

/**
 * Placeholder that mirrors the real job card's geometry. Keeping the shapes
 * aligned with the loaded state avoids a layout jump when data arrives.
 */
@Composable
fun JobCardSkeleton(modifier: Modifier = Modifier) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SkeletonBox(Modifier.size(56.dp), RoundedCornerShape(50))
            Spacer(Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.fillMaxWidth(0.72f).height(16.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.42f).height(12.dp))
            }
            Spacer(Modifier.width(spacing.xs))
            SkeletonBox(Modifier.size(38.dp), RoundedCornerShape(12.dp))
        }
        SkeletonBox(Modifier.fillMaxWidth(0.55f).height(12.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.45f).height(12.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.62f).height(24.dp), RoundedCornerShape(50))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SkeletonBox(Modifier.weight(1f).height(44.dp), RoundedCornerShape(16.dp))
            SkeletonBox(Modifier.weight(1f).height(44.dp), RoundedCornerShape(16.dp))
        }
    }
}

@Composable
fun StatCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkeletonBox(Modifier.size(26.dp), RoundedCornerShape(9.dp))
        SkeletonBox(Modifier.width(38.dp).height(20.dp))
        SkeletonBox(Modifier.fillMaxWidth(0.8f).height(10.dp))
    }
}

@Composable
fun JobListSkeleton(count: Int = 3, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) { JobCardSkeleton() }
    }
}

/** Shared illustration slot for empty and error states. */
@Composable
fun StateIllustration(emoji: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    emoji: String = "🔍",
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateIllustration(emoji)
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = title,
        message = message,
        emoji = "⚠️",
        modifier = modifier,
        action = {
            SecondaryButton(text = "Try again", onClick = onRetry)
        },
    )
}
