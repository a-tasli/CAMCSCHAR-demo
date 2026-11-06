package org.example

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun AssetUtteranceImage(
    location: String?,
    index: Int?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onImageLoaded: ((ImageBitmap) -> Unit)? = null
) {
    if (location == null || index == null) return

    val context = LocalContext.current
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(location, index) {
        withContext(Dispatchers.IO) {
            try {
                val assetManager = context.assets
                val folderPath = "images/$location"
                val files = assetManager.list(folderPath) ?: emptyArray()

                // Find a file where the trailing number matches the index exactly
                val file = files.find {
                    val nameWithoutExt = it.substringBeforeLast(".")
                    val trailingNumber = nameWithoutExt.takeLastWhile { char -> char.isDigit() }.toIntOrNull()
                    trailingNumber == index
                }

                if (file != null) {
                    val inputStream = assetManager.open("$folderPath/$file")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val img = bitmap.asImageBitmap()
                    inputStream.close()
                    withContext(Dispatchers.Main) {
                        imageBitmap = img
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(imageBitmap) {
        imageBitmap?.let { onImageLoaded?.invoke(it) }
    }

    if (imageBitmap != null) {
        if (isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD6D6D6)),
                onClick = onToggle
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = "Utterance Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            TextButton(onClick = onToggle) {
                Text("Show Image", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TourScreen(
    tourManager: TourManager,
    location: android.location.Location?,
    isManualGpsMode: Boolean = false,
    manualLat: Double? = null,
    manualLon: Double? = null,
    onOpenSurvey: () -> Unit = {}
) {
    val history by tourManager.history.collectAsState()
    val currentArea by tourManager.currentArea.collectAsState()
    val resetTrigger by tourManager.manualGpsResetTrigger.collectAsState()
    val isPlaying by tourManager.isPlaying.collectAsState()
    val isAutoMode by tourManager.isAutoMode.collectAsState()
    val isLargeImageMode by tourManager.isLargeImageMode.collectAsState()
    val isEndOfTourReached by tourManager.isEndOfTourReached.collectAsState()
    val presentationMode by tourManager.presentationMode.collectAsState()
    val loadLevel by tourManager.loadLevel.collectAsState()
    val listState = rememberLazyListState()
    var latestImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var latestImageIndex by remember { mutableStateOf(-1) }
    var scrollTrigger by remember { mutableStateOf(0) }

    val isMultimodal = presentationMode == PresentationMode.MULTIMODAL
    val showLargeMap = presentationMode == PresentationMode.AUDIO_FIRST
    var transcriptExpanded by remember { mutableStateOf(true) }

    val showEnlargedMap = isAutoMode && (currentArea == "None") && isMultimodal

    LaunchedEffect(history) {
        if (history.size <= 1) {
            latestImage = null
            latestImageIndex = -1
        }
    }

    LaunchedEffect(history, isLargeImageMode, showEnlargedMap, scrollTrigger) {
        if (history.isNotEmpty()) {
            delay(150)
            val totalItems = history.size + if (isMultimodal) history.count { it.isUtterance } else 0
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val showTopMap = showEnlargedMap || showLargeMap
                val showTopBox = showTopMap || (isMultimodal && isLargeImageMode)

                if (showTopBox) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = if (showTopMap) Color.White else Color(0xFFE0E0E0)
                        )
                    ) {
                        if (showTopMap) {
                            OsmMapPreview(
                                location = location,
                                isManualGpsMode = isManualGpsMode,
                                manualLat = manualLat,
                                manualLon = manualLon,
                                resetTrigger = resetTrigger,
                                onManualLocationChange = { lat, lon -> tourManager.updateManualLocation(lat, lon) },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (latestImage != null) {
                                    Image(
                                        bitmap = latestImage!!,
                                        contentDescription = "Latest Utterance Image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        text = "Large Image Placeholder\nWaiting for image...",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                if (presentationMode == PresentationMode.AUDIO_ONLY) {
                    AudioTourStatusCard(
                        isPlaying = isPlaying,
                        currentArea = currentArea,
                        loadLevel = loadLevel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        if (presentationMode == PresentationMode.AUDIO_FIRST && !transcriptExpanded) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Transcript hidden — audio tour active.\nControls: notification / headset buttons.",
                                        color = Color.Gray,
                                        fontSize = 14.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    TextButton(onClick = { transcriptExpanded = true }) {
                                        Text("Show Transcript", fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            if (presentationMode == PresentationMode.AUDIO_FIRST) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Researcher Transcript",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    TextButton(onClick = { transcriptExpanded = false }) {
                                        Text("Hide", fontSize = 12.sp)
                                    }
                                }
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxSize()
                            ) {
                                history.forEachIndexed { index, item ->
                                    val isLast = index == history.size - 1
                                    val textColor = if (isLast) Color.Black else Color.DarkGray

                                    if (item.isUtterance && isMultimodal) {
                                        item {
                                            val isExpanded = item.showImage ?: (showEnlargedMap || !isLargeImageMode)
                                            AssetUtteranceImage(
                                                location = item.location,
                                                index = item.utteranceIndex,
                                                isExpanded = isExpanded,
                                                onToggle = {
                                                    tourManager.toggleImageForUtterance(index, isExpanded)
                                                    scrollTrigger++
                                                },
                                                onImageLoaded = { bitmap ->
                                                    if (index >= latestImageIndex) {
                                                        latestImage = bitmap
                                                        latestImageIndex = index
                                                    }
                                                    scrollTrigger++
                                                }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                    }

                                    item {
                                        Text(
                                            text = item.text,
                                            fontSize = 18.sp,
                                            fontWeight = if (isLast) FontWeight.Medium else FontWeight.Normal,
                                            color = textColor
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!showEnlargedMap && isMultimodal) {
                Card(
                    modifier = Modifier
                        .padding(24.dp)
                        .size(120.dp)
                        .align(Alignment.TopEnd),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    OsmMapPreview(
                        location = location,
                        isManualGpsMode = isManualGpsMode,
                        manualLat = manualLat,
                        manualLon = manualLon,
                        resetTrigger = resetTrigger,
                        onManualLocationChange = { lat, lon -> tourManager.updateManualLocation(lat, lon) },
                        modifier = Modifier.fillMaxSize(),
                        isMinimap = true
                    )
                }
            }
        }

        if (isEndOfTourReached) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "End of Tour Reached",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = "Take the post-tour retention & workload survey",
                            fontSize = 12.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onOpenSurvey,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Take Survey")
                    }
                }
            }
        }

        // Control Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { tourManager.prevIndex() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }

                FilledIconButton(
                    onClick = {
                        if (!isPlaying) {
                            tourManager.startTour()
                        } else {
                            tourManager.togglePlayPause()
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPlaying) Color(0xFFE91E63) else Color(0xFF4CAF50)
                    )
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Start",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = { tourManager.nextIndex() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
        }
    }
}
