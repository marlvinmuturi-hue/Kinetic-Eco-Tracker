package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R
import android.os.Build
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.RoutePoint
import Kinetic_Eco.Tracker.data.ActivityColors
import Kinetic_Eco.Tracker.ui.theme.Green500
import Kinetic_Eco.Tracker.ui.theme.Slate400
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** Default color for legacy route points (no activity stored). */
private val LEGACY_ROUTE_COLOR = Green500

/** Color for IDLE segments (gray). */
private val IDLE_COLOR = Slate400

private fun routeColorForActivity(activity: ActivityType?): Color = when (activity) {
    null -> LEGACY_ROUTE_COLOR
    ActivityType.IDLE -> IDLE_COLOR
    else -> ActivityColors.getColor(activity)
}

private fun ActivityType.toActivityStringResId(): Int = when (this) {
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

/**
 * Splits route into contiguous segments by activity.
 * Each segment is a list of GeoPoints to draw as one polyline.
 */
private fun splitRouteIntoSegments(routePath: List<RoutePoint>): List<Pair<List<GeoPoint>, ActivityType?>> {
    if (routePath.size < 2) return emptyList()
    val segments = mutableListOf<Pair<List<GeoPoint>, ActivityType?>>()
    var currentActivity: ActivityType? = routePath[0].activity
    var currentPoints = mutableListOf(
        GeoPoint(routePath[0].latitude, routePath[0].longitude)
    )
    for (i in 1 until routePath.size) {
        val pt = routePath[i]
        if (pt.activity == currentActivity) {
            currentPoints.add(GeoPoint(pt.latitude, pt.longitude))
        } else {
            if (currentPoints.size >= 2) {
                segments.add(currentPoints.toList() to currentActivity)
            }
            currentActivity = pt.activity
            currentPoints = mutableListOf(GeoPoint(pt.latitude, pt.longitude))
        }
    }
    if (currentPoints.size >= 2) {
        segments.add(currentPoints.toList() to currentActivity)
    }
    return segments
}

/** Single horizontal line per activity; scales down slightly when font scale is large so e.g. "Idle" stays one word wide. */
@Composable
private fun RouteMapLegendLabel(text: String, color: Color) {
    val fs = LocalDensity.current.fontScale
    val factor = if (fs > 1.1f) (1.15f / fs).coerceIn(0.45f, 1f) else 1f
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 11.sp * factor,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip
    )
}

/**
 * Signature for [LaunchedEffect] so the map updates when any path's points change
 * without depending on unstable list identity.
 */
private fun routePathsContentSignature(paths: List<List<RoutePoint>>): String =
    paths.joinToString("|") { path ->
        "${path.size}_${path.firstOrNull()?.latitude}_${path.firstOrNull()?.longitude}_" +
            "${path.lastOrNull()?.latitude}_${path.lastOrNull()?.longitude}"
    }

/**
 * Displays an OSM map with route polylines colored by activity.
 * Pass one inner list per session so gaps between sessions are not drawn as lines.
 *
 * @param routePaths Non-empty inner lists only; each list is one session's path.
 * @param showElevationProfile If false, hides [RouteElevationProfile] (e.g. multiple sessions).
 */
@Composable
fun RouteMapMultiSessionView(
    routePaths: List<List<RoutePoint>>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    heightDp: Int = 250,
    showElevationProfile: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorScheme = MaterialTheme.colorScheme

    val pathsForDraw = remember(routePaths) {
        routePaths.filter { it.size >= 2 }
    }
    val routeSignature = remember(routePaths) { routePathsContentSignature(routePaths) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                setHasTransientState(true)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(routeSignature) {
        try {
            mapView.overlays.removeAll { it is Polyline }
            val allLats = mutableListOf<Double>()
            val allLons = mutableListOf<Double>()

            for (routePath in pathsForDraw) {
                val segments = splitRouteIntoSegments(routePath)
                for ((points, activity) in segments) {
                    val polyline = Polyline(mapView).apply {
                        setPoints(ArrayList(points))
                        outlinePaint.color = routeColorForActivity(activity).toArgb()
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.style = android.graphics.Paint.Style.STROKE
                        outlinePaint.isAntiAlias = true
                    }
                    mapView.overlays.add(0, polyline)
                }
                allLats.addAll(routePath.map { it.latitude })
                allLons.addAll(routePath.map { it.longitude })
            }

            for (single in routePaths.filter { it.size == 1 }) {
                allLats.add(single[0].latitude)
                allLons.add(single[0].longitude)
            }

            when {
                allLats.size >= 2 -> {
                    val bounds = BoundingBox(
                        allLats.max(),
                        allLons.max(),
                        allLats.min(),
                        allLons.min()
                    )
                    mapView.zoomToBoundingBox(bounds, false, 48)
                }
                allLats.size == 1 -> {
                    val controller = mapView.controller
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(allLats[0], allLons[0]))
                }
            }
            mapView.post { mapView.invalidate() }
        } catch (_: Throwable) {
            // MapView may be detaching while LazyColumn recycles; ignore
        }
    }

    val elevationPath = remember(routePaths, showElevationProfile) {
        if (!showElevationProfile || routePaths.size != 1) return@remember emptyList()
        routePaths.firstOrNull() ?: emptyList()
    }

    val activitiesInRoute = remember(routePaths) {
        routePaths.flatMap { it.map { p -> p.activity } }.distinct()
    }

    val mapEmpty = pathsForDraw.isEmpty() && routePaths.none { it.size == 1 }
    
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    try {
                        view.onPause()
                        view.onDetach()
                    } catch (_: Throwable) {
                        // View may already be torn down when LazyColumn recycles this item
                    }
                }
            )
            if (mapEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_route_recorded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        if (elevationPath.isNotEmpty()) {
            RouteElevationProfile(
                routePath = elevationPath,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        
        if (activitiesInRoute.isNotEmpty()) {
            val legendScroll = rememberScrollState()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.22f))
            ) {
                // Inner width = intrinsic sum of chips; horizontalScroll when content is wider than the map.
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(legendScroll)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        activitiesInRoute.forEach { activity ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Canvas(modifier = Modifier.size(10.dp).padding(end = 4.dp)) {
                                    drawCircle(
                                        color = routeColorForActivity(activity),
                                        radius = size.minDimension / 2f
                                    )
                                }
                                RouteMapLegendLabel(
                                    text = if (activity == null) stringResource(R.string.route) else stringResource(activity.toActivityStringResId()),
                                    color = colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single-session map: one route path.
 */
@Composable
fun RouteMapView(
    routePath: List<RoutePoint>,
    modifier: Modifier = Modifier.fillMaxWidth(),
    heightDp: Int = 250
) {
    val paths = remember(routePath) {
        if (routePath.isEmpty()) emptyList() else listOf(routePath)
    }
    RouteMapMultiSessionView(
        routePaths = paths,
        modifier = modifier,
        heightDp = heightDp,
        showElevationProfile = true
    )
}
