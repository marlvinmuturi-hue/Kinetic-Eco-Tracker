package Kinetic_Eco.Tracker.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.services.BillingManager
import Kinetic_Eco.Tracker.services.EntitlementRepository
import java.text.DateFormat
import java.util.Date

/** Same gold as the Settings upsell card, so the two read as one feature. */
private val PremiumGold = Color(0xFFFFB300)

/**
 * The paywall.
 *
 * Shows whatever Play says the subscription costs — never a hardcoded price, which
 * would be wrong in every currency but one — and reflects entitlement from
 * [EntitlementRepository], so a subscriber who lands here sees their plan instead
 * of a pitch to buy what they already own.
 */
@Composable
fun PremiumScreen(onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val isPremium by EntitlementRepository.isPremium.collectAsStateWithLifecycle()
    val entitlement by EntitlementRepository.entitlement.collectAsStateWithLifecycle()
    val offer by BillingManager.offer.collectAsStateWithLifecycle()
    val status by BillingManager.status.collectAsStateWithLifecycle()
    val refreshing by BillingManager.refreshing.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var purchaseInFlight by remember { mutableStateOf(false) }

    // Purchases can complete while this screen is closed (slow payment methods,
    // a purchase finished on another device), so re-sync on entry rather than
    // trusting whatever was cached when the app started.
    LaunchedEffect(Unit) { BillingManager.refresh() }

    val verifyingMessage = stringResource(R.string.premium_verifying)
    val successMessage = stringResource(R.string.premium_success)
    val pendingMessage = stringResource(R.string.premium_pending)
    val nothingToRestoreMessage = stringResource(R.string.premium_nothing_to_restore)

    LaunchedEffect(Unit) {
        BillingManager.events.collect { event ->
            when (event) {
                is BillingManager.PurchaseEvent.Verifying -> {
                    purchaseInFlight = true
                    snackbarHostState.showSnackbar(verifyingMessage)
                }
                is BillingManager.PurchaseEvent.Success -> {
                    purchaseInFlight = false
                    snackbarHostState.showSnackbar(successMessage)
                }
                is BillingManager.PurchaseEvent.Pending -> {
                    purchaseInFlight = false
                    snackbarHostState.showSnackbar(pendingMessage)
                }
                is BillingManager.PurchaseEvent.NothingToRestore ->
                    snackbarHostState.showSnackbar(nothingToRestoreMessage)
                is BillingManager.PurchaseEvent.Cancelled -> purchaseInFlight = false
                is BillingManager.PurchaseEvent.Failed -> {
                    purchaseInFlight = false
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    text = stringResource(R.string.go_premium),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colorScheme.onBackground
                )
            }

            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = PremiumGold,
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.CenterHorizontally)
            )

            Text(
                text = stringResource(R.string.premium_headline),
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            BenefitRow(Icons.Filled.CheckCircle, stringResource(R.string.premium_benefit_no_ads))
            BenefitRow(Icons.Filled.Insights, stringResource(R.string.premium_benefit_insights))
            BenefitRow(Icons.Filled.Favorite, stringResource(R.string.premium_benefit_support))

            Spacer(Modifier.height(4.dp))

            if (isPremium) {
                ActivePlanCard(
                    expiryMs = entitlement.expiryMs,
                    willRenew = entitlement.willRenew,
                    onManage = { context.openPlaySubscriptions() }
                )
            } else {
                PurchaseCard(
                    offer = offer,
                    status = status,
                    purchaseInFlight = purchaseInFlight,
                    refreshing = refreshing,
                    onSubscribe = { activity?.let { BillingManager.launchPurchase(it) } },
                    onRetry = { BillingManager.refresh(userInitiated = true) }
                )

                TextButton(
                    onClick = {
                        BillingManager.refresh(userInitiated = true, announceEmptyRestore = true)
                    },
                    enabled = !refreshing,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.premium_restore), color = colorScheme.primary)
                }

                // Play policy: auto-renewal and how to cancel must be stated on the
                // screen that sells the subscription, not buried in a settings page.
                Text(
                    text = stringResource(R.string.premium_renewal_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PremiumGold,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun PurchaseCard(
    offer: BillingManager.PremiumOffer?,
    status: BillingManager.Status,
    purchaseInFlight: Boolean,
    refreshing: Boolean,
    onSubscribe: () -> Unit,
    onRetry: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, PremiumGold.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                offer != null -> {
                    offer.freeTrialIso?.let { trial ->
                        Text(
                            text = stringResource(R.string.premium_trial_prefix, trialLabel(trial)),
                            style = MaterialTheme.typography.titleMedium,
                            color = PremiumGold,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = offer.formattedPrice,
                        style = MaterialTheme.typography.headlineMedium,
                        color = colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = billingPeriodLabel(offer.billingPeriodIso),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onSubscribe,
                        enabled = !purchaseInFlight,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PremiumGold,
                            contentColor = Color(0xFF1A1200)
                        )
                    ) {
                        if (purchaseInFlight) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1A1200)
                            )
                        } else {
                            Text(stringResource(R.string.premium_subscribe))
                        }
                    }
                }

                status is BillingManager.Status.Connecting -> {
                    CircularProgressIndicator(color = PremiumGold)
                    Text(
                        text = stringResource(R.string.premium_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    val reason = (status as? BillingManager.Status.Unavailable)?.reason
                        ?: stringResource(R.string.premium_unavailable)
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(onClick = onRetry, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = PremiumGold
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.premium_try_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivePlanCard(expiryMs: Long, willRenew: Boolean, onManage: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val date = remember(expiryMs) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiryMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, PremiumGold.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.premium_active_title),
                style = MaterialTheme.typography.titleMedium,
                color = PremiumGold,
                fontWeight = FontWeight.Bold
            )
            Text(
                // A cancelled subscriber keeps access to the end of the period they
                // paid for, so the copy has to distinguish "renews" from "ends".
                text = if (willRenew) {
                    stringResource(R.string.premium_renews_on, date)
                } else {
                    stringResource(R.string.premium_ends_on, date)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = onManage) {
                Text(stringResource(R.string.premium_manage))
            }
        }
    }
}

// ── Formatting helpers ───────────────────────────────────────────────────────

/** Human label for an ISO-8601 recurrence such as "P1M". */
@Composable
private fun billingPeriodLabel(iso: String): String = when (iso.uppercase()) {
    "P1W" -> stringResource(R.string.premium_billed_weekly)
    "P1M" -> stringResource(R.string.premium_billed_monthly)
    "P3M" -> stringResource(R.string.premium_billed_quarterly)
    "P6M" -> stringResource(R.string.premium_billed_biannually)
    "P1Y" -> stringResource(R.string.premium_billed_yearly)
    else -> stringResource(R.string.premium_billed_other)
}

/**
 * Free-trial length as text, e.g. "7 days". Parses only the single-unit forms Play
 * emits for trials (`P7D`, `P2W`, `P1M`); anything else falls back to a neutral
 * label rather than showing a raw ISO string to the user.
 */
@Composable
private fun trialLabel(iso: String): String {
    val match = Regex("^P(\\d+)([DWMY])$").find(iso.uppercase())
        ?: return stringResource(R.string.premium_trial_generic)
    val count = match.groupValues[1].toIntOrNull()
        ?: return stringResource(R.string.premium_trial_generic)

    return when (match.groupValues[2]) {
        "D" -> pluralStringResource(R.plurals.premium_trial_days, count, count)
        "W" -> pluralStringResource(R.plurals.premium_trial_weeks, count, count)
        "M" -> pluralStringResource(R.plurals.premium_trial_months, count, count)
        else -> pluralStringResource(R.plurals.premium_trial_years, count, count)
    }
}

// ── Context helpers ──────────────────────────────────────────────────────────

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Play's subscription centre, deep-linked to this product where possible. */
private fun Context.openPlaySubscriptions() {
    val uri = Uri.parse(
        "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingManager.PREMIUM_PRODUCT_ID}&package=$packageName"
    )
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // No browser and no Play Store — nothing sensible left to open.
    }
}