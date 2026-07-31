package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import Kinetic_Eco.Tracker.data.DrivingEngineCcBand
import Kinetic_Eco.Tracker.data.ElectricMotorPowerBand
import Kinetic_Eco.Tracker.data.ElectricVehicleClass
import Kinetic_Eco.Tracker.data.IceFuel
import Kinetic_Eco.Tracker.data.PrimaryFuelType
import Kinetic_Eco.Tracker.data.TrainPropulsion
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.data.VehicleBodyType
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.ui.utils.usesMetricDistance
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
    modifier: Modifier = Modifier
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
    val distanceUnit = if (unitSystem.usesMetricDistance()) "km" else "mi"
    val distanceKm = Co2Calculator.parseDistanceToKm(distanceText, unitSystem)
    val estimate = distanceKm?.let {
        Co2Calculator.estimate(selectedActivity, it, effectiveProfile)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
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
                EstimateResult(estimate = estimate, distanceUnit = distanceUnit)
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

/** Horizontally wrapping chip row of every trackable mode. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSelector(
    selected: ActivityType,
    onSelect: (ActivityType) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.co2_calc_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CALCULATOR_MODES.forEach { activity ->
                val isSelected = activity == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(activity) },
                    label = { Text(stringResource(activity.labelResId())) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
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
private fun formatWh(wh: Double): String = when {
    wh < 1000.0 -> "${Math.round(wh)} Wh"
    wh < 10_000.0 -> "${String.format("%.2f", wh / 1000.0)} kWh"
    else -> "${String.format("%.1f", wh / 1000.0)} kWh"
}