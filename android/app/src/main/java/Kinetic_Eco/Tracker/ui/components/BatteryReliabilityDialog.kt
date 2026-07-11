package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.util.BatteryOptimizationHelper

/**
 * A gentle, one-off nudge that helps the user keep auto-start working when the app is closed.
 *
 * Shown when one or more of the background-reliability settings is still missing — either
 * contextually (the moment the user turns auto-start on) or proactively on the main screen.
 * The tone is "here's how to make this work well," never guilt.
 *
 * The dialog is **adaptive**: it inspects the current state and only mentions / offers deep-links
 * for the settings that are actually still missing, so the user is never sent to a screen that has
 * nothing left to fix:
 *  - **Background location** ("Allow all the time") — lets the monitor take GPS fixes while closed.
 *  - **Battery — Unrestricted** — exempts Kinetic from Doze/optimization killing the monitor.
 *  - **Autostart** — only on OEMs (Xiaomi/Oppo/Vivo/…) that add a proprietary allow-list.
 *
 * @param onDismiss called when the user taps "Not now", an action button, or dismisses the dialog.
 */
@Composable
fun BatteryReliabilityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Snapshot the gaps once when the dialog appears; each action dismisses, so re-evaluation isn't needed.
    val needsBackgroundLocation = remember { !BatteryOptimizationHelper.hasBackgroundLocationAccess(context) }
    val batteryOptimized = remember { !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }
    val showAutoStart = remember { BatteryOptimizationHelper.hasOemAutoStartRestriction() }

    // Build the "…keeps this working" clause from only the settings that are still missing, joined
    // into natural English ("A", "A and B", "A, B, and C").
    val fixes = buildList {
        if (needsBackgroundLocation) add("allowing location “All the time”")
        if (batteryOptimized) add("setting the battery to Unrestricted")
        if (showAutoStart) add("turning on Autostart")
    }
    val fixesClause = when (fixes.size) {
        0 -> "Allowing background access"
        1 -> fixes[0]
        2 -> "${fixes[0]} and ${fixes[1]}"
        else -> "${fixes.dropLast(1).joinToString(", ")}, and ${fixes.last()}"
    }.replaceFirstChar { it.uppercase() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Keep auto-start reliable") },
        text = {
            Text(
                "To start tracking your walks and drives on its own — even when Kinetic is closed — " +
                    "Android needs to let it run in the background.\n\n" +
                    "$fixesClause keeps this working. It has a negligible effect on your battery — " +
                    "Kinetic only wakes up when you actually start moving."
            )
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (needsBackgroundLocation) {
                    TextButton(onClick = {
                        // No standard intent targets the per-permission page; App info → Permissions →
                        // Location → "Allow all the time" is the reliable path on every OEM build.
                        BatteryOptimizationHelper.openAppDetailsSettings(context)
                        onDismiss()
                    }) {
                        Text("Location access")
                    }
                }
                if (batteryOptimized) {
                    TextButton(onClick = {
                        BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
                        onDismiss()
                    }) {
                        Text("Battery settings")
                    }
                }
                if (showAutoStart) {
                    TextButton(onClick = {
                        // If the OEM screen can't be resolved on this build, land on app-details instead
                        // of doing nothing.
                        if (!BatteryOptimizationHelper.openOemAutoStartSettings(context)) {
                            BatteryOptimizationHelper.openAppDetailsSettings(context)
                        }
                        onDismiss()
                    }) {
                        Text("Autostart settings")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        }
    )
}