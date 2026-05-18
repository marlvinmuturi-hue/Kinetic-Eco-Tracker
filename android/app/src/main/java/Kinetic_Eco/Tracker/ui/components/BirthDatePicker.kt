package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.services.ageInYearsFromBirthMs
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Reusable form field for picking the user's date of birth. Wraps Material 3's
 * [DatePickerDialog] in an [OutlinedCard] surface that matches the surrounding
 * profile fields (Weight, Height, Gender). Behaviour:
 *
 *  - **Tap to open**: anywhere on the card opens the date picker dialog.
 *  - **Default selection**: when [birthDateMs] is null, the dialog opens at
 *    "today − 30 years" so users only need to fine-tune rather than scroll
 *    decades back.
 *  - **Bounds**: years from 1900 to current year, future dates disabled
 *    (a birth date in the future would just produce age 0 which is useless
 *    for BMR/calorie maths).
 *  - **Display**: when set, shows a localised long-form date plus the
 *    derived whole-year age (e.g. "12 March 1995 · 29 yrs") so the user
 *    can sanity-check what the picker recorded. When unset, shows the
 *    "tap to set" placeholder hint in muted text.
 *
 * The age figure is derived via [ageInYearsFromBirthMs] — the same helper
 * the data layer / `CalorieEngine` uses — so the displayed number is always
 * consistent with what the rest of the app calculates.
 *
 * @param birthDateMs UTC milliseconds at midnight of the selected birth date,
 *                    or null when no date has been picked yet.
 * @param onBirthDateChange invoked with the newly-picked value (never null —
 *                          the caller can keep the previous value if the user
 *                          dismisses the dialog without confirming).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthDatePicker(
    birthDateMs: Long?,
    onBirthDateChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.birth_date_label)
) {
    val colorScheme = MaterialTheme.colorScheme
    var showDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember { DateFormat.getDateInstance(DateFormat.LONG) }

    val placeholder = stringResource(R.string.birth_date_placeholder)
    val displayText = if (birthDateMs == null) {
        placeholder
    } else {
        val ageYears = ageInYearsFromBirthMs(birthDateMs, System.currentTimeMillis())
        val formattedDate = dateFormatter.format(Date(birthDateMs))
        stringResource(R.string.birth_date_value_format, formattedDate, ageYears)
    }

    OutlinedCard(
        onClick = { showDialog = true },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CalendarToday,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (birthDateMs == null) colorScheme.onSurfaceVariant
                            else colorScheme.onSurface
                )
            }
        }
    }

    if (showDialog) {
        BirthDatePickerDialog(
            initialBirthDateMs = birthDateMs,
            onDismiss = { showDialog = false },
            onConfirm = { selected ->
                showDialog = false
                onBirthDateChange(selected)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDatePickerDialog(
    initialBirthDateMs: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val nowMs = remember { System.currentTimeMillis() }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialBirthDateMs ?: defaultBirthDateMs(),
        yearRange = 1900..currentYear,
        // Future dates are nonsensical for a birth date and would produce
        // age 0 in BMR maths; disable them at the picker level so the user
        // never even sees them as an option.
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis <= nowMs
            override fun isSelectableYear(year: Int): Boolean =
                year <= currentYear
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = datePickerState.selectedDateMillis
                    if (selected != null) onConfirm(selected) else onDismiss()
                },
                enabled = datePickerState.selectedDateMillis != null
            ) {
                Text(stringResource(R.string.birth_date_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.birth_date_dialog_cancel))
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

/**
 * Roughly 30 years ago — chosen as a low-friction default so first-time users
 * land near a typical adult age without having to scroll decades back from
 * "today" (Material 3's default starting position).
 */
private fun defaultBirthDateMs(): Long {
    val c = Calendar.getInstance()
    c.add(Calendar.YEAR, -30)
    // Snap to start of day so the persisted millis line up with what the
    // picker would emit (it normalises to midnight UTC of the chosen date).
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
