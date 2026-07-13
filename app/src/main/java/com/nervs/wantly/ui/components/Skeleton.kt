package com.nervs.wantly.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Skeleton-загрузчики для списков и карточек.
 *
 * Реализация без внешних shimmer-библиотек: [rememberInfiniteTransition] +
 * горизонтальный [Brush.linearGradient] поверх [surfaceVariant] (этот цвет уже
 * используется в проекте как muted-фон — см. WishCard / StatusChip). Скелетоны
 * совпадают по размерам с реальными карточками, чтобы при загрузке данных не
 * происходило визуального скачка макета.
 */

private val SHIMMER_ANIM_DURATION = 1200 // ms

/** Линейный градиент, анимированно скользящий слева направо. */
@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_ANIM_DURATION),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = androidx.compose.ui.geometry.Offset(offset, 0f),
        end = androidx.compose.ui.geometry.Offset(offset + 300f, 0f),
    )
}

/** Один скелетон-блок с shimmer-фоном. */
@Composable
fun Modifier.skeleton(): Modifier = this
    .clip(RoundedCornerShape(4.dp))
    .background(shimmerBrush())

/**
 * Карточка-скелетон строки списка желаний.
 * Матчит [com.nervs.wantly.ui.screens.home.HomeScreen.WishlistRow]:
 * Card (12dp), padding(16dp), иконка-круг 48dp, title + description.
 */
@Composable
fun WishlistRowSkeleton(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush()),
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(18.dp)
                        .skeleton(),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                        .skeleton(),
                )
            }
        }
    }
}

/**
 * Карточка-скелетон желания.
 * Матчит [WishCard]: Card (12dp), padding(12dp), картинка 72dp, 3 текстовые полосы.
 */
@Composable
fun WishCardSkeleton(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(shimmerBrush()),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(18.dp)
                        .skeleton(),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.4f)
                        .height(14.dp)
                        .skeleton(),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.3f)
                        .height(16.dp)
                        .skeleton(),
                )
            }
        }
    }
}

/**
 * Список скелетон-карточек для первого экрана загрузки.
 * [count] — сколько плейсхолдеров отрисовать.
 */
@Composable
fun SkeletonList(
    count: Int,
    item: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) { item() }
    }
}
