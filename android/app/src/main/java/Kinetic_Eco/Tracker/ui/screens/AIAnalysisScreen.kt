package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityAnalysis
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelector
import Kinetic_Eco.Tracker.ui.components.AnalysisPeriodSelectorMode
import Kinetic_Eco.Tracker.ui.theme.*
import Kinetic_Eco.Tracker.viewmodel.AIAnalysisState
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel

@Composable
fun AIAnalysisScreen(viewModel: AnalyticsViewModel, userId: String) {
    val colorScheme = MaterialTheme.colorScheme
    val analysisState by viewModel.aiAnalysisState.collectAsStateWithLifecycle()
    val rollingAnalysisDays by viewModel.rollingAnalysisDays.collectAsStateWithLifecycle()
    val analysisSessionDateKey by viewModel.analysisSessionDateKey.collectAsStateWithLifecycle()
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.ai),
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.ai_powered_analysis),
                                style = MaterialTheme.typography.headlineSmall,
                                color = colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.ai_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onPrimary.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
        
        // Timeframe Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.select_timeframe),
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    AnalysisPeriodSelector(
                        mode = AnalysisPeriodSelectorMode.AiAnalysis,
                        rollingDays = rollingAnalysisDays,
                        allTimeSelected = false,
                        selectedSessionDateKey = analysisSessionDateKey,
                        includeAllTimeOption = false,
                        onAiRollingDaysChanged = { viewModel.setRollingAnalysisDays(it) },
                        onAllTimeSelected = null,
                        onSessionDaySelected = { viewModel.setAnalysisSessionDay(it) },
                        onClearSessionDaySelection = { viewModel.clearAnalysisSessionDay() }
                    )
                }
            }
        }
        
        // Analyze Button
        item {
            Button(
                onClick = { viewModel.analyzeActivity(userId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = analysisState !is AIAnalysisState.Loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    disabledContainerColor = colorScheme.primary.copy(alpha = 0.5f)
                )
            ) {
                if (analysisState is AIAnalysisState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.analyzing), style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.analyze_activity), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        
        // Content based on state
        when (val state = analysisState) {
            is AIAnalysisState.Success -> {
                item {
                    AnalysisResultsContent(state.analysis)
                }
            }
            is AIAnalysisState.Error -> {
                item {
                    ErrorCard(state.message)
                }
            }
            is AIAnalysisState.Idle -> {
                item {
                    IdleStateCard()
                }
            }
            is AIAnalysisState.Loading -> {
                // Loading state is shown in button
            }
        }
    }
}

@Composable
fun AnalysisResultsContent(analysis: ActivityAnalysis) {
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Cache Indicator
        if (analysis.cached) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colorScheme.tertiary.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.cached_analysis, analysis.cacheAge),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Score Card
        ScoreCard(analysis.score, analysis.scoreReasoning)
        
        // Motivation Card
        if (analysis.motivation.isNotEmpty()) {
            MotivationCard(analysis.motivation)
        }
        
        // Insights Card
        if (analysis.insights.isNotEmpty()) {
            InsightsCard(analysis.insights)
        }
        
        // Recommendations Card
        if (analysis.recommendations.isNotEmpty()) {
            RecommendationsCard(analysis.recommendations)
        }
        
        // Environmental Impact Card
        if (analysis.environmentalImpact.isNotEmpty()) {
            EnvironmentalImpactCard(analysis.environmentalImpact)
        }
        
        // Highlights Card
        analysis.highlights?.let { highlights ->
            HighlightsCard(highlights)
        }
    }
}

@Composable
fun ScoreCard(score: Double, reasoning: String) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.primary
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.activity_score),
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onPrimary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = String.format("%.1f", score),
                style = MaterialTheme.typography.displayLarge,
                color = colorScheme.onPrimary.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = stringResource(R.string.out_of_10),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onPrimary.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LinearProgressIndicator(
                progress = { (score / 10.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = colorScheme.onPrimary,
                trackColor = colorScheme.surfaceVariant,
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onPrimary.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun MotivationCard(motivation: String) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Amber500.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Amber400,
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.motivation),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = motivation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InsightsCard(insights: List<String>) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = null,
                    tint = colorScheme.tertiary
                )
                Text(
                    text = stringResource(R.string.key_insights),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            
            insights.forEachIndexed { _, insight ->
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.primary
                    )
                    Text(
                        text = insight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun RecommendationsCard(recommendations: List<String>) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.secondary.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.recommendations),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            
            recommendations.forEachIndexed { _, rec ->
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.secondary
                    )
                    Text(
                        text = rec,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun EnvironmentalImpactCard(impact: String) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_co2_carbon_neutral),
                    contentDescription = null,
                    tint = colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.environmental_impact),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = impact,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HighlightsCard(highlights: ActivityAnalysis.Highlights) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.highlights),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (highlights.bestDay.isNotEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.best_day),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = highlights.bestDay,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                if (highlights.topActivity.isNotEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.top_activity),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = highlights.topActivity,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                if (highlights.improvement.isNotEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.improve),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = highlights.improvement,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Red500.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    tint = Red400,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.analysis_error),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Red200
                    )
                }
            }
        }
    }
}

@Composable
fun IdleStateCard() {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.click_analyze),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.track_first),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
