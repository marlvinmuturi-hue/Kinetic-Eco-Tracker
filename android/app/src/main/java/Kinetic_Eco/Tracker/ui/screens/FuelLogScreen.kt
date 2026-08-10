package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.FuelEconomyCalculator
import Kinetic_Eco.Tracker.services.EnergyPriceRepository
import Kinetic_Eco.Tracker.services.FuelLogRepository
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The fuel log — two numbers off a receipt, and the app works out the rest.
 *
 * This is what turns cost from an estimate into a measurement. Every other calculator
 * has to ask the user what their fuel economy is and believe the answer; this one
 * already knows exactly how far they drove, so a litre figure is all it needs.
 *
 * Kept to litres and amount paid on purpose. Every extra field is a reason to stop
 * logging, and two entries is already enough to beat an engine-displacement average.
 */
@Composable
fun FuelLogScreen(
    userId: String,
    onBack: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { FuelLogRepository(context) }

    val economy by FuelLogRepository.economy.collectAsStateWithLifecycle()
    val prices by EnergyPriceRepository.prices.collectAsStateWithLifecycle()
    // A receipt is denominated even when the app has no price table for this region —
    // fall back to the device's currency rather than leaving the field unlabelled.
    val currencyCode = prices?.currencyCode ?: EnergyPriceRepository.currentCurrencyCode()
    val entries by repo.observeEntries(userId).collectAsStateWithLifecycle(initialValue = emptyList())

    var litresText by rememberSaveable { mutableStateOf("") }
    var amountText by rememberSaveable { mutableStateOf("") }
    var fullTank by rememberSaveable { mutableStateOf(true) }

    // Reconcile on entry: push anything logged offline, pull anything logged on another
    // device, then recompute — sessions tracked since the last visit change the distance
    // side of every interval, so a cached figure would quietly go stale.
    LaunchedEffect(userId) { if (userId.isNotBlank()) repo.sync(userId) }

    val litres = litresText.replace(',', '.').toDoubleOrNull()
    val amount = amountText.replace(',', '.').toDoubleOrNull()
    val canSave = litres != null && litres > 0.0 && amount != null && amount >= 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                text = stringResource(R.string.fuel_log_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onBackground
            )
        }

        EconomyCard(economy = economy, intervalsNeeded = FuelEconomyCalculator.MIN_INTERVALS_FOR_CONFIDENCE)

        // ── Add a fill-up ────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.fuel_log_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = litresText,
                        onValueChange = { if (it.length <= 7) litresText = it },
                        label = { Text(stringResource(R.string.fuel_log_litres)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { if (it.length <= 10) amountText = it },
                        label = { Text(stringResource(R.string.fuel_log_amount, currencyCode)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fullTank, onCheckedChange = { fullTank = it })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.fuel_log_full_tank),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface
                        )
                        // Explains why the box matters rather than leaving it as jargon:
                        // only brim-to-brim stretches can be measured at all.
                        Text(
                            text = stringResource(R.string.fuel_log_full_tank_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            repo.addFillUp(
                                userId = userId,
                                litres = litres ?: return@launch,
                                amountPaid = amount ?: 0.0,
                                currencyCode = currencyCode,
                                isFullTank = fullTank
                            )
                            litresText = ""
                            amountText = ""
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.fuel_log_save))
                }
            }
        }

        // ── History ──────────────────────────────────────────────────────────
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.fuel_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalGasStation,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        R.string.fuel_log_entry_line,
                                        String.format("%.2f", entry.litres),
                                        entry.currencyCode,
                                        String.format("%,.0f", entry.amountPaid)
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = DateFormat.getDateInstance(DateFormat.MEDIUM)
                                        .format(Date(entry.filledAtMs)) +
                                        if (!entry.isFullTank) {
                                            " · " + stringResource(R.string.fuel_log_partial)
                                        } else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch { repo.deleteEntry(userId, entry.id) }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.fuel_log_delete),
                                    tint = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EconomyCard(
    economy: Kinetic_Eco.Tracker.data.MeasuredEconomy?,
    intervalsNeeded: Int
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (economy == null) {
                Text(
                    text = stringResource(R.string.fuel_log_not_yet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.fuel_log_not_yet_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.fuel_log_economy_value,
                        String.format("%.1f", economy.lPer100Km)
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = stringResource(
                        R.string.fuel_log_economy_basis,
                        economy.intervals,
                        String.format("%,.0f", economy.totalDistanceKm)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                // One interval is an anecdote. Say so rather than implying more
                // certainty than a single tankful can carry.
                if (economy.intervals < intervalsNeeded) {
                    Text(
                        text = stringResource(R.string.fuel_log_economy_early),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
