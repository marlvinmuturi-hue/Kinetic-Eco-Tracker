package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.MonthlyStatement
import Kinetic_Eco.Tracker.services.EntitlementRepository
import Kinetic_Eco.Tracker.services.MonthlyStatementRepository
import Kinetic_Eco.Tracker.util.MonthWindow

private val PremiumGold = Color(0xFFFFB300)

/**
 * A month of mobility, led by money.
 *
 * Kilograms have never moved behaviour the way a currency figure does, and the app can
 * now state one credibly: distance it measured itself, consumption from the user's own
 * fill-ups, price from their own receipts.
 *
 * Framed as savings available rather than money wasted. The app's whole notification
 * stance is encouraging rather than guilt-tripping, and a spending report is exactly
 * where that discipline is easiest to lose.
 */
@Composable
fun MonthlyStatementScreen(
    userId: String,
    onBack: () -> Unit,
    onGoPremium: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val repo = remember(context) { MonthlyStatementRepository(context) }
    val isPremium by EntitlementRepository.isPremium.collectAsStateWithLifecycle()

    // Defaults to the month that just finished — a statement is about a closed period.
    var monthStart by remember { mutableStateOf(MonthWindow.previousMonthStartMs()) }
    var statement by remember { mutableStateOf<MonthlyStatement?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(userId, monthStart, isPremium) {
        if (!isPremium) { loading = false; return@LaunchedEffect }
        loading = true
        statement = repo.statementFor(userId, monthStart)
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.statement_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onBackground
            )
        }

        if (!isPremium) {
            PremiumLock(onGoPremium)
            return@Column
        }

        // ── Month picker ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthStart = MonthWindow.shiftMonths(monthStart, -1) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.statement_prev_month))
            }
            Text(
                text = MonthWindow.label(monthStart),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            // Never past the current month — there is nothing to report from the future.
            val canGoForward = monthStart < MonthWindow.startOfMonthMs()
            IconButton(
                onClick = { monthStart = MonthWindow.shiftMonths(monthStart, 1) },
                enabled = canGoForward
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.statement_next_month))
            }
        }

        when {
            loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            statement == null || statement!!.tripCount == 0 -> Text(
                text = stringResource(R.string.statement_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            else -> StatementBody(statement!!)
        }
    }
}

@Composable
private fun StatementBody(s: MonthlyStatement) {
    val colorScheme = MaterialTheme.colorScheme

    // ── Headline: what the driving cost ──────────────────────────────────────
    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(
                    if (s.isPartial) R.string.statement_spend_so_far else R.string.statement_spend
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = s.fuelCost?.let { "${s.currencyCode} ${String.format("%,.0f", it)}" }
                    ?: stringResource(R.string.statement_no_cost_basis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
            s.fuelLitres?.let {
                Text(
                    text = stringResource(
                        R.string.statement_litres_over,
                        String.format("%.1f", it),
                        String.format("%,.0f", s.motorisedDistanceKm)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(
                    if (s.isMeasured) R.string.statement_basis_measured
                    else R.string.statement_basis_estimated
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }

    // ── The month in numbers ─────────────────────────────────────────────────
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            label = stringResource(R.string.statement_trips),
            value = s.tripCount.toString(),
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = stringResource(R.string.statement_distance),
            value = "${String.format("%,.0f", s.totalDistanceKm)} km",
            modifier = Modifier.weight(1f)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            label = stringResource(R.string.statement_active),
            value = "${String.format("%,.1f", s.activeDistanceKm)} km",
            modifier = Modifier.weight(1f)
        )
        StatTile(
            label = stringResource(R.string.statement_co2_saved),
            value = "${String.format("%.1f", s.co2SavedKg)} kg",
            modifier = Modifier.weight(1f)
        )
    }

    // ── The one actionable line ──────────────────────────────────────────────
    // Phrased as money still available, never as money wasted. Same reason the
    // notifications were stripped of guilt: the app celebrates outcomes.
    if (s.shortTripCount > 0 && s.shortTripCost != null) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.statement_short_trips_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.statement_short_trips_body,
                        s.shortTripCount,
                        s.currencyCode,
                        String.format("%,.0f", s.shortTripCost)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF43A047)
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PremiumLock(onGoPremium: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.statement_locked_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.statement_locked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onGoPremium,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PremiumGold,
                    contentColor = Color(0xFF1A1200)
                )
            ) {
                Icon(Icons.Default.WorkspacePremium, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.statement_locked_cta))
            }
        }
    }
}
