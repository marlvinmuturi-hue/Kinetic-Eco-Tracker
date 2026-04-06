package Kinetic_Eco.Tracker.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.services.FeedbackService
import Kinetic_Eco.Tracker.ui.theme.*

enum class FeedbackCategory(@StringRes val labelResId: Int) {
    GENERAL(R.string.general),
    BUG(R.string.bug_report),
    FEATURE(R.string.feature_request),
    UI(R.string.ui_ux),
    PERFORMANCE(R.string.performance)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    userId: String = "",
    userEmail: String = ""
) {
    val colorScheme = MaterialTheme.colorScheme
    val pleaseEnterFeedbackStr = stringResource(R.string.please_enter_feedback)
    val feedbackFailedStr = stringResource(R.string.feedback_failed)
    var feedbackText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(FeedbackCategory.GENERAL) }
    var isSubmitting by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val feedbackService = remember { FeedbackService() }
    val coroutineScope = rememberCoroutineScope()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .background(colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp)
    ) {
        // Header
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
                text = stringResource(R.string.send_feedback),
                style = MaterialTheme.typography.headlineMedium,
                color = colorScheme.onBackground
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Category Selection
        Text(
            text = stringResource(R.string.category),
            style = MaterialTheme.typography.titleSmall,
            color = Slate400,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeedbackCategory.values().take(3).forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(stringResource(category.labelResId), style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorScheme.primary,
                        selectedLabelColor = colorScheme.onPrimary,
                        containerColor = colorScheme.surface,
                        labelColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeedbackCategory.values().drop(3).forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(stringResource(category.labelResId), style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colorScheme.primary,
                        selectedLabelColor = colorScheme.onPrimary,
                        containerColor = colorScheme.surface,
                        labelColor = colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            // Spacer to balance the row
            Spacer(modifier = Modifier.weight(1f))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Feedback input
        Text(
            text = stringResource(R.string.your_feedback),
            style = MaterialTheme.typography.titleSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        OutlinedTextField(
            value = feedbackText,
            onValueChange = { feedbackText = it },
            placeholder = { Text(stringResource(R.string.feedback_hint), color = colorScheme.onSurfaceVariant) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 360.dp),
            minLines = 8,
            maxLines = 15,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colorScheme.onSurface,
                unfocusedTextColor = colorScheme.onSurfaceVariant,
                focusedBorderColor = colorScheme.primary,
                unfocusedBorderColor = colorScheme.outline,
                cursorColor = colorScheme.primary,
                focusedContainerColor = colorScheme.surface.copy(alpha = 0.5f),
                unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        
        // Error message
        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.2f))
            ) {
                Text(
                    text = error,
                    color = Red500,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Submit button
        Button(
            onClick = {
                if (feedbackText.isBlank()) {
                    errorMessage = pleaseEnterFeedbackStr
                    return@Button
                }
                
                isSubmitting = true
                errorMessage = null
                
                coroutineScope.launch {
                    val result = feedbackService.submitQuickFeedback(
                        userId = userId.ifEmpty { "anonymous" },
                        userEmail = userEmail.ifEmpty { "anonymous@kinetic.app" },
                        message = feedbackText,
                        category = selectedCategory.name.lowercase()
                    )
                    
                    isSubmitting = false
                    
                    result.fold(
                        onSuccess = {
                            feedbackText = ""
                            showSuccessDialog = true
                        },
                        onFailure = { exception ->
                            errorMessage = exception.message ?: feedbackFailedStr
                        }
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = feedbackText.isNotBlank() && !isSubmitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary,
                disabledContainerColor = colorScheme.primary.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.send_feedback), style = MaterialTheme.typography.labelLarge)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Info text
        Text(
            text = stringResource(R.string.feedback_helps),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
    
    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSuccessDialog = false
                onBack()
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSuccessDialog = false
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.done), color = colorScheme.primary)
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = colorScheme.secondary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(stringResource(R.string.thank_you), color = Slate50)
            },
            text = {
                Text(
                    stringResource(R.string.feedback_success),
                    color = colorScheme.onSurfaceVariant
                )
            },
            containerColor = colorScheme.surface,
            titleContentColor = colorScheme.onSurface,
            textContentColor = colorScheme.onSurfaceVariant
        )
    }
}
