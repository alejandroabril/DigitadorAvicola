package com.digitador.avicola.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.digitador.avicola.domain.EstadoSemana
import com.digitador.avicola.ui.theme.*

@Composable
fun ProgressBar(
    pct: Int,
    modifier: Modifier = Modifier,
    color: Color = Accent
) {
    val barColor = if (pct >= 100) OkGreen else color
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(CircleShape)
            .background(Line)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = (pct / 100f).coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(barColor.copy(alpha = 0.8f), barColor)
                    )
                )
        )
    }
}

@Composable
fun StatusDot(estado: EstadoSemana, modifier: Modifier = Modifier) {
    val color = when (estado) {
        EstadoSemana.COMPLETA -> OkGreen
        EstadoSemana.PARCIAL  -> WarnAmber
        EstadoSemana.VACIA    -> Line
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (estado != EstadoSemana.VACIA) Modifier.shadow(2.dp, CircleShape) else Modifier)
    )
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg    = if (selected) Accent else Color.White
    val fg    = if (selected) Color.White else Ink2
    val brd   = if (selected) Accent else Line

    Surface(
        onClick   = onClick,
        modifier  = modifier.height(34.dp),
        shape     = RoundedCornerShape(10.dp),
        color     = bg,
        border    = androidx.compose.foundation.BorderStroke(1.dp, brd),
        shadowElevation = if (selected) 4.dp else 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text  = label,
                color = fg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AppPanel(
    title: String,
    modifier: Modifier = Modifier,
    meta: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2.copy(alpha = 0.5f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title, 
                    style = MaterialTheme.typography.labelLarge, 
                    color = AccentInk, 
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = AccentSoft,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            meta, 
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 10.sp, 
                            color = Accent, 
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
            HorizontalDivider(color = Line2, thickness = 1.dp)
            Column(modifier = Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
fun AlertBanner(
    message: String,
    type: AlertType = AlertType.WARN,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (type) {
        AlertType.WARN  -> WarnSoft to WarnAmber
        AlertType.ERROR -> DangerSoft to DangerRed
        AlertType.OK    -> OkSoft to OkGreen
        AlertType.INFO  -> AccentSoft to Accent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(message, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

enum class AlertType { WARN, ERROR, OK, INFO }

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text     = text,
        modifier = modifier.padding(bottom = 8.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        color    = Ink3,
        letterSpacing = 1.sp
    )
}
