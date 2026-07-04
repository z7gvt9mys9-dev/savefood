package ru.savefood.app.feature.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.savefood.app.R
import ru.savefood.app.core.datastore.UserRole
import ru.savefood.app.core.designsystem.component.SaveFoodButton
import ru.savefood.app.core.designsystem.component.SaveFoodCard

/**
 * First-run onboarding: a per-role carousel (3 slides) plus a first-steps
 * checklist page. Shown once after login, before the role shell; [onFinish]
 * persists the "completed" flag (see OnboardingViewModel / OnboardingStore).
 */
@Composable
fun OnboardingScreen(role: UserRole, onFinish: () -> Unit) {
    val content = remember(role) { onboardingContent(role) }
    val pageCount = content.slides.size + 1
    val lastIndex = pageCount - 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    val onLast = pagerState.currentPage == lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AnimatedVisibility(visible = !onLast, enter = fadeIn(), exit = fadeOut()) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onb_skip)) }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            if (page < content.slides.size) {
                SlidePage(content.slides[page])
            } else {
                ChecklistPage(content.checklist)
            }
        }

        PageIndicator(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            modifier = Modifier.padding(vertical = 24.dp),
        )

        SaveFoodButton(
            text = if (onLast) stringResource(R.string.onb_start) else stringResource(R.string.onb_next),
            onClick = {
                if (onLast) onFinish()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun SlidePage(slide: OnbSlide) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = slide.icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(slide.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            text = stringResource(slide.descRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ChecklistPage(checklist: List<Int>) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onb_checklist_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        SaveFoodCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                checklist.forEach { stepRes ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = stringResource(stepRes),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (selected) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

private data class OnbSlide(val icon: ImageVector, val titleRes: Int, val descRes: Int)

private data class OnbContent(val slides: List<OnbSlide>, val checklist: List<Int>)

private fun onboardingContent(role: UserRole): OnbContent = when (role) {
    UserRole.SHOP -> OnbContent(
        slides = listOf(
            OnbSlide(Icons.Filled.AddBox, R.string.onb_shop_t1, R.string.onb_shop_d1),
            OnbSlide(Icons.Filled.QrCode2, R.string.onb_shop_t2, R.string.onb_shop_d2),
            OnbSlide(Icons.AutoMirrored.Filled.ReceiptLong, R.string.onb_shop_t3, R.string.onb_shop_d3),
        ),
        checklist = listOf(R.string.onb_shop_c1, R.string.onb_shop_c2, R.string.onb_shop_c3),
    )
    UserRole.VOLUNTEER -> OnbContent(
        slides = listOf(
            OnbSlide(Icons.Filled.Map, R.string.onb_vol_t1, R.string.onb_vol_d1),
            OnbSlide(Icons.Filled.Route, R.string.onb_vol_t2, R.string.onb_vol_d2),
            OnbSlide(Icons.Filled.EmojiEvents, R.string.onb_vol_t3, R.string.onb_vol_d3),
        ),
        checklist = listOf(R.string.onb_vol_c1, R.string.onb_vol_c2, R.string.onb_vol_c3),
    )
    // Needy + any fallback (ADMIN/UNKNOWN never reach onboarding — AppRoot gates them).
    else -> OnbContent(
        slides = listOf(
            OnbSlide(Icons.Filled.Restaurant, R.string.onb_needy_t1, R.string.onb_needy_d1),
            OnbSlide(Icons.Filled.LocalShipping, R.string.onb_needy_t2, R.string.onb_needy_d2),
            OnbSlide(Icons.Filled.Star, R.string.onb_needy_t3, R.string.onb_needy_d3),
        ),
        checklist = listOf(R.string.onb_needy_c1, R.string.onb_needy_c2, R.string.onb_needy_c3),
    )
}
