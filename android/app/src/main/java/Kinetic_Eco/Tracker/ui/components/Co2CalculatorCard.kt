package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityColors
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.AircraftCategory
import Kinetic_Eco.Tracker.data.Co2Calculator
import Kinetic_Eco.Tracker.data.Co2Estimate
import Kinetic_Eco.Tracker.data.EnergyPrices
import Kinetic_Eco.Tracker.data.MeasuredEconomy
import Kinetic_Eco.Tracker.data.MobilityCostCalculator
import Kinetic_Eco.Tracker.data.PriceSource
import Kinetic_Eco.Tracker.data.DrivingEngineCcBand
import Kinetic_Eco.Tracker.data.ElectricMotorPowerBand
import Kinetic_Eco.Tracker.data.ElectricVehicleClass
import Kinetic_Eco.Tracker.data.IceFuel
import Kinetic_Eco.Tracker.data.PrimaryFuelType
import Kinetic_Eco.Tracker.data.TrainPropulsion
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.data.VehicleBodyType
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.services.EnergyPriceRepository
import Kinetic_Eco.Tracker.services.FuelLogRepository
import Kinetic_Eco.Tracker.services.UserPreferencesManager
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
import dev.chrisbanes.haze.HazeState
import Kinetic_Eco.Tracker.ui.theme.glassTile
import kotlin.math.abs

/**
 * Manual CO₂ estimator for the Analysis tab.
 *
 * Answers "what would this trip cost me?" without tracking it: pick a mode,
 * type a distance, read the figure. Everything is computed locally through
 * [Co2Calculator], so it works offline and instantly.
 *
 * By default the estimate uses the user's saved vehicle profile, which is what
 * makes a hand-entered 12 km drive agree with a tracked one. The vehicle can be
 * overridden inline to answer the other question people actually have — "what if
 * I drove something smaller?" — without disturbing the saved profile.
 *
 * Nothing entered here is persisted. These are hypotheticals; letting them reach
 * the session store would corrupt dashboard totals and the leaderboard, so the
 * card is deliberately stateless beyond its own inputs and says so.
 */
@Composable
fun Co2CalculatorCard(
    unitSystem: UnitSystem,
    vehicleProfile: VehicleProfile,
    modifier: Modifier = Modifier,
    onLogFuel: (() -> Unit)? = null,
    hazeState: HazeState? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    var selectedActivity by rememberSaveable { mutableStateOf(ActivityType.DRIVING) }
    var distanceText by rememberSaveable { mutableStateOf("") }

    // null = follow the saved profile. Only becomes non-null once the user
    // actually changes something, so editing the profile in Settings still flows
    // through for anyone who never touched these controls.
    var overrideProfile by rememberSaveable(stateSaver = NullableVehicleProfileSaver) {
        mutableStateOf<VehicleProfile?>(null)
    }
    var vehicleExpanded by rememberSaveable { mutableStateOf(false) }

    val effectiveProfile = overrideProfile ?: vehicleProfile
    val prices by EnergyPriceRepository.prices.collectAsStateWithLifecycle()
    // Null until the user has logged two brim-full fill-ups. When present it replaces
    // the engine-displacement average outright — see MobilityCostCalculator.
    val measuredEconomy by FuelLogRepository.economy.collectAsStateWithLifecycle()
    val receiptPrice by FuelLogRepository.observedPrice.collectAsStateWithLifecycle()
    val distanceUnit = if (unitSystem.usesMetricDistance()) "km" else "mi"
    val distanceKm = Co2Calculator.parseDistanceToKm(distanceText, unitSystem)
    val estimate = distanceKm?.let {
        Co2Calculator.estimate(selectedActivity, it, effectiveProfile)
    }

    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (hazeState != null) Modifier.glassTile(hazeState, shape) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else colorScheme.surfaceVariant
        ),
        shape = shape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Calculate,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.co2_calc_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.co2_calc_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                if (distanceText.isNotEmpty()) {
                    TextButton(onClick = { distanceText = "" }) {
                        Text(stringResource(R.string.co2_calc_clear))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            ModeSelector(
                selected = selectedActivity,
                onSelect = { selectedActivity = it }
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = distanceText,
                onValueChange = { raw: String ->
                    // Keep the field to a single decimal number. Both separators are
                    // allowed because the numeric keyboard's decimal key is locale
                    // dependent; Co2Calculator normalises them.
                    if (raw.length <= 8 && raw.all { it.isDigit() || it == '.' || it == ',' }) {
                        distanceText = raw
                    }
                },
                label = { Text(stringResource(R.string.co2_calc_distance_label, distanceUnit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Only offer the override for modes whose factor actually depends on
            // the profile. Walking, running and cycling are fixed constants —
            // showing vehicle controls there would imply a choice that does nothing.
            if (selectedActivity.usesVehicleProfile()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { vehicleExpanded = !vehicleExpanded }) {
                        Text(stringResource(R.string.co2_calc_customise_vehicle))
                    }
                    Spacer(Modifier.weight(1f))
                    if (overrideProfile != null) {
                        TextButton(onClick = { overrideProfile = null }) {
                            Text(stringResource(R.string.co2_calc_reset_vehicle))
                        }
                    }
                }
                AnimatedVisibility(visible = vehicleExpanded) {
                    VehicleOverrideSection(
                        activity = selectedActivity,
                        profile = effectiveProfile,
                        onProfileChange = { overrideProfile = it }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (estimate == null) {
                Text(
                    text = stringResource(R.string.co2_calc_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            } else {
                // Fuel volume and money lead the card, because it is the Mobility Cost
                // Calculator and burying the cost under the CO₂ headline would contradict
                // its own name.
                //
                // This is still the least certain number here — the litres come from a
                // class average until the user logs a fill-up (see MobilityCostCalculator)
                // — so leading with it raises the stakes on the hedging directly beneath
                // it. That caption is load-bearing now, not decoration: it is the only
                // thing separating "estimated" from "measured" at a glance.
                MobilityCostRow(
                    estimate = estimate,
                    profile = effectiveProfile,
                    prices = prices,
                    measured = measuredEconomy,
                    receiptPrice = receiptPrice,
                    onLogFuel = onLogFuel
                )

                EstimateResult(estimate = estimate, distanceUnit = distanceUnit)

                Spacer(Modifier.height(12.dp))
                ModeComparison(
                    distanceKm = estimate.distanceKm,
                    profile = effectiveProfile,
                    selected = selectedActivity,
                    onSelect = { selectedActivity = it }
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))

            // Two things the user needs to trust the number: which vehicle it used,
            // and that experimenting here is consequence-free.
            Text(
                text = stringResource(
                    if (overrideProfile != null && selectedActivity.usesVehicleProfile()) {
                        R.string.co2_calc_profile_note_custom
                    } else {
                        R.string.co2_calc_profile_note
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.co2_calc_not_saved),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Vehicle controls for the selected mode.
 *
 * Each mode exposes exactly the fields [Kinetic_Eco.Tracker.data.CO2Factors.getFactor]
 * reads for it, so every control on screen demonstrably moves the number and no
 * field that matters is hidden.
 */
@Composable
private fun VehicleOverrideSection(
    activity: ActivityType,
    profile: VehicleProfile,
    onProfileChange: (VehicleProfile) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (activity == ActivityType.DRIVING || activity == ActivityType.MOTORCYCLE) {
            FuelEconomyField(profile = profile, onProfileChange = onProfileChange)
        }
        when (activity) {
            ActivityType.DRIVING -> {
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_ice_fuel,
                    options = IceFuel.entries,
                    selected = profile.iceFuel,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(iceFuel = it)) }
                )
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_driving_cc,
                    options = DrivingEngineCcBand.entries,
                    selected = profile.drivingCcBand,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(drivingCcBand = it)) }
                )
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_body_type,
                    options = VehicleBodyType.entries,
                    selected = profile.bodyType,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(bodyType = it)) }
                )
            }

            ActivityType.ELECTRIC_VEHICLE -> {
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_ev_class,
                    options = ElectricVehicleClass.entries,
                    selected = profile.electricVehicleClass,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(electricVehicleClass = it)) }
                )
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_ev_motor_power,
                    options = ElectricMotorPowerBand.entries,
                    selected = profile.electricMotorPower,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(electricMotorPower = it)) }
                )
            }

            // Motorcycle branches on primary fuel: electric reads the motor band,
            // combustion reads engine size and fuel (body type is pinned to
            // hatchback inside getFactor, so it is deliberately not offered).
            ActivityType.MOTORCYCLE -> {
                EnumChipRow(
                    labelRes = R.string.vehicle_profile_primary_fuel,
                    options = PrimaryFuelType.entries,
                    selected = profile.primaryFuelType,
                    labelOf = { it.labelResId() },
                    onSelect = { onProfileChange(profile.copy(primaryFuelType = it)) }
                )
                if (profile.primaryFuelType == PrimaryFuelType.ELECTRIC) {
                    EnumChipRow(
                        labelRes = R.string.vehicle_profile_ev_motor_power,
                        options = ElectricMotorPowerBand.entries,
                        selected = profile.electricMotorPower,
                        labelOf = { it.labelResId() },
                        onSelect = { onProfileChange(profile.copy(electricMotorPower = it)) }
                    )
                } else {
                    EnumChipRow(
                        labelRes = R.string.vehicle_profile_driving_cc,
                        options = DrivingEngineCcBand.entries,
                        selected = profile.drivingCcBand,
                        labelOf = { it.labelResId() },
                        onSelect = { onProfileChange(profile.copy(drivingCcBand = it)) }
                    )
                }
            }

            ActivityType.TRAIN -> EnumChipRow(
                labelRes = R.string.vehicle_profile_train,
                options = TrainPropulsion.entries,
                selected = profile.trainPropulsion,
                labelOf = { it.labelResId() },
                onSelect = { onProfileChange(profile.copy(trainPropulsion = it)) }
            )

            ActivityType.FLYING -> EnumChipRow(
                labelRes = R.string.vehicle_profile_aircraft,
                options = AircraftCategory.entries,
                selected = profile.aircraftCategory,
                labelOf = { it.labelResId() },
                onSelect = { onProfileChange(profile.copy(aircraftCategory = it)) }
            )

            // Fixed factors — no vehicle to choose.
            ActivityType.IDLE,
            ActivityType.WALKING,
            ActivityType.RUNNING,
            ActivityType.CYCLING -> Unit
        }
    }
}

/** Labelled row of single-choice chips over an enum's values. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> EnumChipRow(
    labelRes: Int,
    options: List<T>,
    selected: T,
    labelOf: (T) -> Int,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(stringResource(labelOf(option))) }
                )
            }
        }
    }
}


/**
 * "What does your car actually do?", in km/L.
 *
 * One optional field instead of a make/model/year lookup. It works for every used
 * import that no manufacturer dataset lists, needs no data to source or maintain, and
 * is a claim about *this* car rather than about cars of its engine size. Blank falls
 * back to the engine-displacement average; a logged fill-up overrides it either way.
 */
@Composable
private fun FuelEconomyField(
    profile: VehicleProfile,
    onProfileChange: (VehicleProfile) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var text by rememberSaveable(profile.fuelEconomyKmPerL) {
        mutableStateOf(profile.fuelEconomyKmPerL?.let { String.format("%.1f", it) } ?: "")
    }

    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                if (raw.length <= 5 && raw.all { it.isDigit() || it == '.' || it == ',' }) {
                    text = raw
                    // Blank clears back to the class average rather than persisting a
                    // zero, which would read as infinite consumption.
                    onProfileChange(
                        profile.copy(
                            fuelEconomyKmPerL = raw.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
                        )
                    )
                }
            },
            label = { Text(stringResource(R.string.vehicle_profile_km_per_l)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.vehicle_profile_km_per_l_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Mode picker as a single dropdown row.
 *
 * Was a wrapping chip row over every trackable mode, which ran to three lines on a
 * narrow screen and pushed the distance field and result below the fold. A dropdown
 * costs one tap to change a value that most users set once per estimate, and gives the
 * rest of the card its vertical space back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(
    selected: ActivityType,
    onSelect: (ActivityType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.co2_calc_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = stringResource(selected.labelResId()),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Circle,
                        contentDescription = null,
                        tint = ActivityColors.getColor(selected),
                        modifier = Modifier.size(12.dp)
                    )
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                CALCULATOR_MODES.forEach { activity ->
                    DropdownMenuItem(
                        text = { Text(stringResource(activity.labelResId())) },
                        onClick = {
                            onSelect(activity)
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = null,
                                tint = ActivityColors.getColor(activity),
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        trailingIcon = if (activity == selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }
}

/**
 * The same distance costed across every mode, best first.
 *
 * Turns the card from a lookup ("what does 12 km by car cost?") into a decision
 * ("what should I have done?"). Everything here is derived from
 * [Co2Calculator.compareModes] against the *same* vehicle profile as the headline
 * estimate, so the comparison can never disagree with the number above it.
 *
 * Collapsed by default — eight rows would push the result off screen.
 *
 * Deliberately states no verdict. Ranking by CO₂ alone made walking "best" at any
 * distance, including 100 km, which is true arithmetic and useless advice. The card
 * presents the comparison and lets the reader judge; picking a winner needs a notion of
 * what is actually practical, which this card does not have.
 */
@Composable
private fun ModeComparison(
    distanceKm: Double,
    profile: VehicleProfile,
    selected: ActivityType,
    onSelect: (ActivityType) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }

    val estimates = remember(distanceKm, profile) {
        Co2Calculator.compareModes(distanceKm, profile, CALCULATOR_MODES)
    }
    if (estimates.isEmpty()) return
    // Deltas are measured against the user's current pick, so every row answers
    // "compared to what I chose" rather than against an arbitrary baseline.
    val selectedNet = estimates.firstOrNull { it.activity == selected }?.netKg ?: 0.0

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.co2_calc_compare_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.co2_calc_compare_collapse
                    else R.string.co2_calc_compare_expand
                ),
                tint = colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                estimates.forEach { item ->
                    ModeComparisonRow(
                        estimate = item,
                        isSelected = item.activity == selected,
                        deltaVsSelected = item.netKg - selectedNet,
                        onClick = { onSelect(item.activity) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeComparisonRow(
    estimate: Co2Estimate,
    isSelected: Boolean,
    deltaVsSelected: Double,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val saving = Color(0xFF43A047)
    val emitting = Color(0xFFE53935)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) {
            colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = null,
                tint = ActivityColors.getColor(estimate.activity),
                modifier = Modifier.size(10.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(estimate.activity.labelResId()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (estimate.magnitudeKg <= 0.0) {
                        stringResource(R.string.co2_calc_compare_neutral)
                    } else {
                        formatKg(estimate.magnitudeKg)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (estimate.isSaving) saving else colorScheme.onSurface
                )
                Text(
                    text = if (isSelected) {
                        stringResource(R.string.co2_calc_compare_selected)
                    } else {
                        stringResource(
                            R.string.co2_calc_compare_delta,
                            // Explicit sign: a bare "1.40" next to another number is
                            // ambiguous about which direction it moves the user.
                            (if (deltaVsSelected > 0) "+" else "−") +
                                formatKg(abs(deltaVsSelected))
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isSelected -> colorScheme.onSurfaceVariant
                        deltaVsSelected < 0 -> saving
                        deltaVsSelected > 0 -> emitting
                        else -> colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * Fuel volume and cost, with an inline way to correct the price.
 *
 * Shows nothing at all for modes where the user does not buy the energy — walking,
 * and notably train and flying, where the real cost is a fare. Inventing a ticket
 * price from a share of the vehicle's energy bill would be wrong by an order of
 * magnitude and is worse than silence.
 *
 * The "estimate" caption is not boilerplate. Driving consumption comes from an engine
 * displacement band, which is a class average; a CO₂ figure that is 30% out goes
 * unnoticed, while a shilling figure that is 30% out gets checked against a fuel
 * receipt and takes the credibility of every other number with it.
 */
@Composable
private fun MobilityCostRow(
    estimate: Co2Estimate,
    profile: VehicleProfile,
    prices: EnergyPrices?,
    measured: MeasuredEconomy?,
    receiptPrice: Double?,
    onLogFuel: (() -> Unit)?
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val cost = remember(estimate, profile, prices, measured) {
        prices?.let { MobilityCostCalculator.costOf(estimate, profile, it, measured) }
    }

    // No price for this region, but the trip does burn something the user pays for.
    // Ask for the price instead of inventing one — a figure in the wrong currency is
    // the failure mode this whole feature was supposed to avoid.
    if (cost == null) {
        if (estimate.hasVehicleEnergy && prices == null) {
            UnknownPricePrompt(profile = profile)
            CostSectionDivider()
        }
        return
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    var priceText by rememberSaveable(cost.unitPrice) {
        mutableStateOf(String.format("%.2f", cost.unitPrice))
    }
    val isElectric = cost.kWh != null
    val fuelKind = when {
        isElectric -> UserPreferencesManager.FuelKind.ELECTRICITY
        profile.iceFuel == IceFuel.DIESEL -> UserPreferencesManager.FuelKind.DIESEL
        else -> UserPreferencesManager.FuelKind.PETROL
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = stringResource(
                if (isElectric) R.string.co2_calc_energy_needed else R.string.co2_calc_fuel_needed
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isElectric) {
                stringResource(R.string.co2_calc_kwh, String.format("%.2f", cost.kWh ?: 0.0))
            } else {
                stringResource(R.string.co2_calc_litres, String.format("%.2f", cost.litres ?: 0.0))
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface
        )
    }

    Spacer(Modifier.height(4.dp))

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = stringResource(R.string.co2_calc_cost_label),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${cost.currencyCode} ${String.format("%,.0f", cost.amount)}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface
        )
    }

    Spacer(Modifier.height(6.dp))

    // Provenance, always. A price with no source and no date is indistinguishable
    // from a guess, and a stale one produces confidently wrong money.
    Text(
        text = stringResource(
            R.string.co2_calc_price_provenance,
            "${cost.currencyCode} ${String.format("%.2f", cost.unitPrice)}",
            stringResource(
                if (isElectric) R.string.co2_calc_per_kwh else R.string.co2_calc_per_litre
            ),
            when (cost.source) {
                PriceSource.USER -> stringResource(R.string.co2_calc_price_source_user)
                // Whoever actually publishes prices in this country. Previously this
                // read "EPRA" for everyone, so a French user was told the Kenyan
                // regulator had set their fuel price.
                PriceSource.REMOTE -> stringResource(
                    R.string.co2_calc_price_source_remote,
                    cost.sourceName ?: stringResource(R.string.co2_calc_price_source_generic),
                    cost.effectiveMonth
                )
                PriceSource.BUNDLED -> stringResource(
                    R.string.co2_calc_price_source_bundled,
                    cost.sourceName ?: stringResource(R.string.co2_calc_price_source_generic),
                    cost.effectiveMonth
                )
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = colorScheme.onSurfaceVariant
    )

    // The app stops hedging the moment it has evidence. Leaving the estimate caption
    // up after the user has done the work of logging fill-ups would undersell the one
    // thing that makes these figures better than any generic calculator's.
    Text(
        text = when {
            cost.isMeasured && measured != null ->
                stringResource(R.string.co2_calc_cost_is_measured, measured.intervals)
            // Their own figure for their own car — better than a class average, but
            // still a claim rather than a measurement, so it keeps the nudge to log.
            cost.isOwnerStated -> stringResource(R.string.co2_calc_cost_is_owner_stated)
            else -> stringResource(R.string.co2_calc_cost_is_estimate)
        },
        style = MaterialTheme.typography.bodySmall,
        color = colorScheme.onSurfaceVariant
    )

    // Only offered while still estimating — once measured, the prompt is just noise.
    if (!cost.isMeasured && onLogFuel != null && cost.litres != null) {
        TextButton(onClick = onLogFuel) {
            Text(stringResource(R.string.co2_calc_log_fillup))
        }
    }

    // A receipt beats a national cap and a compiled-in seed alike — it is what this
    // person actually paid at their pump. Offered, never applied silently: quietly
    // rewriting someone's numbers is how a helpful figure becomes a suspicious one.
    // Fuel only; receipts say nothing about an electricity tariff.
    val suggestion = receiptPrice?.takeIf {
        !isElectric &&
            cost.source != PriceSource.USER &&
            kotlin.math.abs(it - cost.unitPrice) / cost.unitPrice > RECEIPT_PRICE_TOLERANCE
    }
    if (suggestion != null && !editing) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.co2_calc_receipt_price,
                    "${cost.currencyCode} ${String.format("%.2f", suggestion)}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                EnergyPriceRepository.setOverride(context, fuelKind, suggestion)
            }) {
                Text(stringResource(R.string.co2_calc_receipt_price_use))
            }
        }
    }

    if (editing) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = priceText,
                onValueChange = { raw ->
                    if (raw.length <= 8 && raw.all { it.isDigit() || it == '.' || it == ',' }) {
                        priceText = raw
                    }
                },
                label = {
                    Text(
                        stringResource(
                            R.string.co2_calc_your_price_label,
                            cost.currencyCode,
                            stringResource(
                                if (isElectric) R.string.co2_calc_per_kwh else R.string.co2_calc_per_litre
                            )
                        )
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val parsed = priceText.replace(',', '.').toDoubleOrNull()
                EnergyPriceRepository.setOverride(context, fuelKind, parsed)
                editing = false
            }) {
                Text(stringResource(R.string.co2_calc_price_save))
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { editing = !editing }) {
            Text(
                stringResource(
                    if (editing) R.string.co2_calc_price_cancel else R.string.co2_calc_use_my_price
                )
            )
        }
        if (cost.source == PriceSource.USER) {
            TextButton(onClick = {
                EnergyPriceRepository.setOverride(context, fuelKind, null)
                editing = false
            }) {
                Text(stringResource(R.string.co2_calc_price_reset))
            }
        }
    }

    CostSectionDivider()
}

/**
 * Separator between the cost block and the CO₂ figure beneath it.
 *
 * Trailing rather than leading. The cost block used to sit under the CO₂ headline and
 * opened with its own divider; now that it leads the card, a leading rule would draw a
 * line directly beneath the distance field with nothing above it. Emitted only by the
 * paths that actually rendered something, so a mode with no purchased energy — walking,
 * cycling, a train fare — leaves no orphan rule behind.
 */
@Composable
private fun CostSectionDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    Spacer(Modifier.height(10.dp))
}

/**
 * Shown when the app has no price basis for the user's region.
 *
 * Deliberately not a zero and not another country's figures: the app ships a price
 * seed for Kenya only, and quoting Kenyan shillings to someone elsewhere would be a
 * confidently wrong number in the wrong currency. One typed price fixes it, in
 * whatever currency the device uses.
 */
@Composable
private fun UnknownPricePrompt(profile: VehicleProfile) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var priceText by rememberSaveable { mutableStateOf("") }

    val currency = remember { EnergyPriceRepository.currentCurrencyCode() }
    val isElectric = profile.primaryFuelType == PrimaryFuelType.ELECTRIC
    val fuelKind = when {
        isElectric -> UserPreferencesManager.FuelKind.ELECTRICITY
        profile.iceFuel == IceFuel.DIESEL -> UserPreferencesManager.FuelKind.DIESEL
        else -> UserPreferencesManager.FuelKind.PETROL
    }
    val unitLabel = stringResource(
        if (isElectric) R.string.co2_calc_per_kwh else R.string.co2_calc_per_litre
    )

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
    Spacer(Modifier.height(10.dp))

    Text(
        text = stringResource(R.string.co2_calc_price_unknown),
        style = MaterialTheme.typography.bodySmall,
        color = colorScheme.onSurfaceVariant
    )

    if (editing) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = priceText,
                onValueChange = { raw ->
                    if (raw.length <= 8 && raw.all { it.isDigit() || it == '.' || it == ',' }) {
                        priceText = raw
                    }
                },
                label = { Text(stringResource(R.string.co2_calc_your_price_label, currency, unitLabel)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val parsed = priceText.replace(',', '.').toDoubleOrNull()
                // The currency travels with the amount, so the figure is never shown
                // under a unit the user did not choose.
                EnergyPriceRepository.setOverride(context, fuelKind, parsed, currency)
                editing = false
            }) {
                Text(stringResource(R.string.co2_calc_price_save))
            }
        }
    } else {
        TextButton(onClick = { editing = true }) {
            Text(stringResource(R.string.co2_calc_set_price))
        }
    }
}

@Composable
private fun EstimateResult(estimate: Co2Estimate, distanceUnit: String) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = ActivityColors.getColor(estimate.activity)
    val isNeutral = estimate.magnitudeKg <= 0.0

    Column {
        if (isNeutral) {
            Text(
                text = stringResource(R.string.co2_calc_neutral),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(
                        if (estimate.isSaving) R.string.co2_calc_saves else R.string.co2_calc_emits
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatKg(estimate.magnitudeKg),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
            Spacer(Modifier.height(4.dp))
            // The per-km rate is what makes the number checkable — it exposes the
            // factor the vehicle selection actually resolved to.
            Text(
                text = stringResource(
                    R.string.co2_calc_rate,
                    formatKg(abs(estimate.factorKgPerKm)),
                    distanceUnit
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }

        // Vehicle energy, shown only when there is a vehicle. Human-powered modes
        // report 0 Wh by design — the calories the walker burns are a different
        // quantity and belong to the tracked-session stats, not here.
        if (estimate.hasVehicleEnergy) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.co2_calc_energy_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatWh(estimate.energyWh),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.co2_calc_rate,
                    formatWh(estimate.energyWhPerKm),
                    distanceUnit
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // Fuel energy and battery energy are not comparable at face value —
            // most of the former leaves as heat. Say which one this is.
            Text(
                text = stringResource(
                    if (estimate.activity.isBatteryPowered()) {
                        R.string.co2_calc_energy_battery_note
                    } else {
                        R.string.co2_calc_energy_fuel_note
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Whether this mode's energy figure is battery draw rather than fuel energy.
 *
 * Electric trains run on traction electricity, so they belong on the battery
 * side of the wording even though nothing is stored on board. Motorcycles fall
 * either way depending on the profile's fuel, and are treated as fuel here
 * because the combustion case is overwhelmingly the common one.
 */
private fun ActivityType.isBatteryPowered(): Boolean =
    this == ActivityType.ELECTRIC_VEHICLE || this == ActivityType.TRAIN

/** Modes worth offering in a manual estimate — IDLE is not a trip. */
private val CALCULATOR_MODES = listOf(
    ActivityType.DRIVING,
    ActivityType.ELECTRIC_VEHICLE,
    ActivityType.MOTORCYCLE,
    ActivityType.TRAIN,
    ActivityType.FLYING,
    ActivityType.CYCLING,
    ActivityType.WALKING,
    ActivityType.RUNNING
)

/** True when this mode's factor reads anything from the vehicle profile. */
private fun ActivityType.usesVehicleProfile(): Boolean = when (this) {
    ActivityType.DRIVING,
    ActivityType.ELECTRIC_VEHICLE,
    ActivityType.MOTORCYCLE,
    ActivityType.TRAIN,
    ActivityType.FLYING -> true
    ActivityType.IDLE,
    ActivityType.WALKING,
    ActivityType.RUNNING,
    ActivityType.CYCLING -> false
}

/**
 * Saver for the nullable override so a rotation doesn't silently drop the user
 * back to their saved profile mid-comparison. Every field is an enum, and each
 * enum already ships a lenient `fromStoredName`, so restore cannot throw on a
 * value that was renamed between versions.
 */
private val NullableVehicleProfileSaver = listSaver<VehicleProfile?, String>(
    save = { profile ->
        if (profile == null) emptyList() else listOf(
            profile.primaryFuelType.name,
            profile.iceFuel.name,
            profile.drivingCcBand.name,
            profile.bodyType.name,
            profile.electricVehicleClass.name,
            profile.electricMotorPower.name,
            profile.trainPropulsion.name,
            profile.aircraftCategory.name
        )
    },
    restore = { stored ->
        if (stored.size < 8) null else VehicleProfile(
            primaryFuelType = PrimaryFuelType.fromStoredName(stored[0]),
            iceFuel = IceFuel.fromStoredName(stored[1]),
            drivingCcBand = DrivingEngineCcBand.fromStoredName(stored[2]),
            bodyType = VehicleBodyType.fromStoredName(stored[3]),
            electricVehicleClass = ElectricVehicleClass.fromStoredName(stored[4]),
            electricMotorPower = ElectricMotorPowerBand.fromStoredName(stored[5]),
            trainPropulsion = TrainPropulsion.fromStoredName(stored[6]),
            aircraftCategory = AircraftCategory.fromStoredName(stored[7])
        )
    }
)

private fun ActivityType.labelResId(): Int = when (this) {
    ActivityType.IDLE -> R.string.activity_idle
    ActivityType.WALKING -> R.string.walking
    ActivityType.RUNNING -> R.string.running
    ActivityType.CYCLING -> R.string.cycling
    ActivityType.MOTORCYCLE -> R.string.motorcycle
    ActivityType.TRAIN -> R.string.train
    ActivityType.DRIVING -> R.string.driving
    ActivityType.ELECTRIC_VEHICLE -> R.string.electric_vehicle
    ActivityType.FLYING -> R.string.flying
}

private fun IceFuel.labelResId(): Int = when (this) {
    IceFuel.PETROL -> R.string.ice_fuel_petrol
    IceFuel.DIESEL -> R.string.ice_fuel_diesel
}

private fun PrimaryFuelType.labelResId(): Int = when (this) {
    PrimaryFuelType.PETROL -> R.string.primary_fuel_petrol
    PrimaryFuelType.DIESEL -> R.string.primary_fuel_diesel
    PrimaryFuelType.ELECTRIC -> R.string.primary_fuel_electric
}

private fun DrivingEngineCcBand.labelResId(): Int = when (this) {
    DrivingEngineCcBand.UP_TO_1000 -> R.string.driving_cc_up_to_1000
    DrivingEngineCcBand.CC_1001_1400 -> R.string.driving_cc_1001_1400
    DrivingEngineCcBand.CC_1401_1800 -> R.string.driving_cc_1401_1800
    DrivingEngineCcBand.CC_1801_2500 -> R.string.driving_cc_1801_2500
    DrivingEngineCcBand.OVER_2500 -> R.string.driving_cc_over_2500
}

private fun VehicleBodyType.labelResId(): Int = when (this) {
    VehicleBodyType.HATCHBACK -> R.string.body_type_hatchback
    VehicleBodyType.SEDAN -> R.string.body_type_sedan
    VehicleBodyType.SUV_CROSSOVER -> R.string.body_type_suv
    VehicleBodyType.PICKUP -> R.string.body_type_pickup
    VehicleBodyType.MINIVAN_MPV -> R.string.body_type_minivan
}

private fun ElectricVehicleClass.labelResId(): Int = when (this) {
    ElectricVehicleClass.TWO_WHEELER -> R.string.ev_class_two_wheeler
    ElectricVehicleClass.THREE_WHEELER -> R.string.ev_class_three_wheeler
    ElectricVehicleClass.CAR -> R.string.ev_class_car
}

private fun ElectricMotorPowerBand.labelResId(): Int = when (this) {
    ElectricMotorPowerBand.UP_TO_3_KW -> R.string.ev_motor_up_to_3_kw
    ElectricMotorPowerBand.KW_3_TO_10 -> R.string.ev_motor_3_to_10_kw
    ElectricMotorPowerBand.KW_10_TO_60 -> R.string.ev_motor_10_to_60_kw
    ElectricMotorPowerBand.KW_60_TO_150 -> R.string.ev_motor_60_to_150_kw
    ElectricMotorPowerBand.OVER_150_KW -> R.string.ev_motor_over_150_kw
}

private fun TrainPropulsion.labelResId(): Int = when (this) {
    TrainPropulsion.ELECTRIC -> R.string.train_propulsion_electric
    TrainPropulsion.DIESEL_ELECTRIC -> R.string.train_propulsion_diesel_electric
}

private fun AircraftCategory.labelResId(): Int = when (this) {
    AircraftCategory.REGIONAL_TURBOPROP -> R.string.aircraft_regional_turboprop
    AircraftCategory.NARROW_BODY_JET -> R.string.aircraft_narrow_body_jet
    AircraftCategory.WIDE_BODY_LONG_HAUL -> R.string.aircraft_wide_body_long_haul
}

/**
 * Sub-kilogram figures read better in grams — "84 g" beats "0.08 kg", and the
 * per-km factors are all well under 1 kg.
 */
private fun formatKg(kg: Double): String = when {
    kg < 1.0 -> "${Math.round(kg * 1000)} g"
    kg < 10.0 -> "${String.format("%.2f", kg)} kg"
    else -> "${String.format("%.1f", kg)} kg"
}

/**
 * Watt-hours below a kilowatt-hour, kWh above it. Matches the threshold and
 * precision `formatEnergy` uses for human energy so the two read consistently,
 * even though they measure different things.
 */
/**
 * How far a receipt must diverge from the price in use before it is worth mentioning.
 * Pump prices vary by a shilling or two between stations; nagging about that would
 * train people to ignore the prompt that matters when the table is genuinely stale.
 */
private const val RECEIPT_PRICE_TOLERANCE = 0.02

private fun formatWh(wh: Double): String = when {
    wh < 1000.0 -> "${Math.round(wh)} Wh"
    wh < 10_000.0 -> "${String.format("%.2f", wh / 1000.0)} kWh"
    else -> "${String.format("%.1f", wh / 1000.0)} kWh"
}