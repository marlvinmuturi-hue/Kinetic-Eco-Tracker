package Kinetic_Eco.Tracker.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import Kinetic_Eco.Tracker.models.ActivityAnalysis
import Kinetic_Eco.Tracker.services.AnalysisService
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.R

/**
 * AI Analysis Screen - Displays activity insights from Gemini AI
 * 
 * Usage in your main composable:
 * ```
 * AnalysisScreen()
 * ```
 */
@Composable
fun AnalysisScreen(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") viewModel: AnalysisViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isLoading by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<ActivityAnalysis?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTimeframe by remember { mutableStateOf(AnalysisService.Timeframe.WEEK) }
    
    fun analyzeActivity() {
        scope.launch {
            isLoading = true
            errorMessage = null
            
            val service = AnalysisService.getInstance()
            val result = service.analyzeActivity(selectedTimeframe)
            
            result.onSuccess { analysisResult ->
                analysis = analysisResult
                isLoading = false
                
                if (analysisResult.cached) {
                    Toast.makeText(
                        context, 
                        "Showing cached analysis (${analysisResult.cacheAge} min old)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                isLoading = false
                errorMessage = service.getErrorMessage(error as Exception)
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text(
            text = "📊 AI Activity Analysis",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Timeframe Selector
        TimeframeSelector(
            selected = selectedTimeframe,
            onSelect = { selectedTimeframe = it },
            enabled = !isLoading
        )
        
        // Analyze Button
        Button(
            onClick = { analyzeActivity() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Analyzing...")
            } else {
                Icon(painterResource(R.drawable.ic_analytics), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Analyze My Activity")
            }
        }
        
        // Error Message
        errorMessage?.let { error ->
            ErrorCard(message = error)
        }
        
        // Analysis Results
        AnimatedVisibility(
            visible = analysis != null && !isLoading,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            analysis?.let { analysisData ->
                AnalysisResults(analysis = analysisData)
            }
        }
    }
}

@Composable
fun TimeframeSelector(
    selected: AnalysisService.Timeframe,
    onSelect: (AnalysisService.Timeframe) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnalysisService.Timeframe.values().forEach { timeframe ->
            FilterChip(
                selected = selected == timeframe,
                onClick = { onSelect(timeframe) },
                label = { Text(timeframe.displayName) },
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun AnalysisResults(analysis: ActivityAnalysis) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Score Card
        ScoreCard(
            score = analysis.score,
            reasoning = analysis.scoreReasoning
        )
        
        // Motivation
        if (analysis.motivation.isNotEmpty()) {
            MotivationCard(message = analysis.motivation)
        }
        
        // Insights
        if (analysis.insights.isNotEmpty()) {
            InsightsCard(insights = analysis.insights)
        }
        
        // Recommendations
        if (analysis.recommendations.isNotEmpty()) {
            RecommendationsCard(recommendations = analysis.recommendations)
        }
        
        // Environmental Impact
        if (analysis.environmentalImpact.isNotEmpty()) {
            EnvironmentalCard(impact = analysis.environmentalImpact)
        }
        
        // Highlights
        analysis.highlights?.let { highlights ->
            HighlightsCard(highlights = highlights)
        }
    }
}

@Composable
fun ScoreCard(score: Double, reasoning: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "⭐ Activity Score",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = String.format("%.1f", score),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = getScoreColor(score)
            )
            
            Text(
                text = "out of 10",
                style = MaterialTheme.typography.bodyMedium
            )
            
            LinearProgressIndicator(
                progress = { (score / 10).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = getScoreColor(score),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            if (reasoning.isNotEmpty()) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun MotivationCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0) // Light orange
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFFF6F00),
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun InsightsCard(insights: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lightbulb, contentDescription = null)
                Text(
                    text = "📈 Key Insights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            insights.forEach { insight ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("•", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = insight,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun RecommendationsCard(recommendations: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE8F5E9) // Light green
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.TipsAndUpdates,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32)
                )
                Text(
                    text = "💡 Recommendations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            recommendations.forEach { recommendation ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✓", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF2E7D32))
                    Text(
                        text = recommendation,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun EnvironmentalCard(impact: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFE1F5FE) // Light blue
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_co2_carbon_neutral),
                contentDescription = null,
                tint = Color(0xFF01579B),
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = "🌍 Environmental Impact",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = impact,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun HighlightsCard(highlights: ActivityAnalysis.Highlights) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "✨ Highlights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (highlights.bestDay.isNotEmpty()) {
                HighlightRow(label = "Best Day", value = highlights.bestDay)
            }
            if (highlights.topActivity.isNotEmpty()) {
                HighlightRow(label = "Top Activity", value = highlights.topActivity)
            }
            if (highlights.improvement.isNotEmpty()) {
                HighlightRow(label = "Improvement Area", value = highlights.improvement)
            }
        }
    }
}

@Composable
fun HighlightRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun getScoreColor(score: Double): Color {
    return when {
        score >= 9.0 -> Color(0xFF2E7D32) // Excellent: Dark green
        score >= 7.0 -> Color(0xFF689F38) // Good: Green
        score >= 4.0 -> Color(0xFFFFA726) // Fair: Orange
        else -> Color(0xFFE53935) // Needs improvement: Red
    }
}

// Placeholder ViewModel (you can expand this)
class AnalysisViewModel : androidx.lifecycle.ViewModel() {
    // Add any state management here if needed
}
