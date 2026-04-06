package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import Kinetic_Eco.Tracker.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.ui.utils.EnergyUnit
import Kinetic_Eco.Tracker.ui.utils.format
import Kinetic_Eco.Tracker.ui.utils.formatEnergy
import Kinetic_Eco.Tracker.ui.components.SingleLineValueText
import Kinetic_Eco.Tracker.ui.utils.formatTime
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import java.text.SimpleDateFormat
import java.util.*

fun formatSessionDate(dateStr: String, locale: Locale = Locale.getDefault()): String {
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outputFormat = SimpleDateFormat("EEE, MMM d, yyyy", locale)
        val date = inputFormat.parse(dateStr)
        date?.let { outputFormat.format(it) } ?: dateStr
    } catch (e: Exception) {
        dateStr
    }
}

@Composable
fun SessionsListScreen(
    viewModel: AnalyticsViewModel,
    userId: String,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    onBack: () -> Unit,
    onSessionClick: (SessionStats) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.getDefault() else (locales.get(0) ?: Locale.getDefault())
    val allSessions by viewModel.getAllSessions(userId).collectAsStateWithLifecycle(initialValue = emptyList())
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = colorScheme.onBackground
                    )
                }
                Text(
                    text = stringResource(R.string.all_sessions),
                    style = MaterialTheme.typography.headlineLarge,
                    color = colorScheme.onBackground
                )
            }
        }
        
        item {
            Text(
                text = stringResource(R.string.sessions_count, allSessions.size),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(allSessions) { session ->
            SessionCard(
                session = session,
                locale = locale,
                unitSystem = unitSystem,
                energyUnit = energyUnit,
                colorScheme = colorScheme,
                onClick = { onSessionClick(session) }
            )
        }
    }
}

@Composable
fun SessionCard(
    session: SessionStats,
    locale: Locale,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    energyUnit: EnergyUnit = EnergyUnit.KCAL,
    colorScheme: androidx.compose.material3.ColorScheme = MaterialTheme.colorScheme,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = stringResource(R.string.date),
                            tint = colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatSessionDate(session.date, locale),
                            style = MaterialTheme.typography.titleMedium,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.completed_session),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SingleLineValueText(
                        text = formatTime(session.totalDuration),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    SingleLineValueText(
                        text = if (unitSystem.usesMetricDistance()) {
                            "${(session.totalDistance / 1000.0).format(2)} km"
                        } else {
                            "${(session.totalDistance / 1609.344).format(2)} mi"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                    SingleLineValueText(
                        text = formatEnergy(session.caloriesBurned, energyUnit),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.co2_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    SingleLineValueText(
                        text = "${session.co2Conserved.format(2)} kg",
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                }
                if (session.co2Emissions > 0) {
                    Column {
                        Text(
                            text = stringResource(R.string.co2_emitted),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        SingleLineValueText(
                            text = "${session.co2Emissions.format(2)} kg",
                            style = MaterialTheme.typography.bodySmall,
                            color = Red500,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }
    }
}


