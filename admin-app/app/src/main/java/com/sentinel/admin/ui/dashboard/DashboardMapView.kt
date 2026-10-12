package com.sentinel.admin.ui.dashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Map view for the Dashboard showing all device markers using Leaflet.js and CartoDB Dark Matter.
 *
 * - Auto-fit camera to show all markers
 * - Tactical clustering with active status badges
 * - Marker click navigates to device detail via JavaScript interface
 * - Quick action to open fleet coordinates natively in Google Maps
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun DashboardMapView(
    markers: List<DeviceMarker>,
    onMarkerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Primary vector tile renderer backed by OpenFreeMap via MapLibre Native
    com.sentinel.admin.ui.map.NativeFleetMapLibreView(
        markers = markers,
        onMarkerClick = onMarkerClick,
        modifier = modifier
    )
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun LeafletDashboardMapViewFallback(
    markers: List<DeviceMarker>,
    onMarkerClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (markers.isEmpty()) {
        NoLocationState(modifier = modifier)
        return
    }

    val context = LocalContext.current

    fun launchGoogleMapsFleet() {
        if (markers.isEmpty()) return
        val centerLat = markers.map { it.latitude }.average()
        val centerLng = markers.map { it.longitude }.average()
        val geoUri = "geo:$centerLat,$centerLng?q=$centerLat,$centerLng(Sentinel+Fleet)"
        val webUri = "https://www.google.com/maps/search/?api=1&query=$centerLat,$centerLng"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                try {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUri))
                    context.startActivity(webIntent)
                } catch (ex: Exception) {
                    android.util.Log.e("Sentinel:Map", "Failed to launch maps", ex)
                }
            }
        }
    }

    val markersJson = remember(markers) {
        val array = org.json.JSONArray()
        markers.forEach { marker ->
            val obj = org.json.JSONObject().apply {
                put("deviceId", marker.deviceId)
                put("uniqueKey", marker.uniqueKey)
                put("deviceName", marker.deviceName)
                put("snippet", marker.snippet.replace(" (Charging)", ""))
                put("latitude", marker.latitude)
                put("longitude", marker.longitude)
                put("isOnline", marker.isOnline)
                put("battery", marker.battery)
                put("network", marker.network)
                put("isMoving", marker.isMoving)
                put("isEmergency", marker.isEmergency)
            }
            array.put(obj)
        }
        array.toString()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val cssContent = try {
                    ctx.assets.open("leaflet.css").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    android.util.Log.e("Sentinel:Map", "Error reading leaflet.css", e)
                    ""
                }
                val jsContent = try {
                    ctx.assets.open("leaflet.js").bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    android.util.Log.e("Sentinel:Map", "Error reading leaflet.js", e)
                    ""
                }

                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                            android.util.Log.d("Sentinel:Map", "Console [${msg?.messageLevel()}]: ${msg?.message()} (${msg?.sourceId()}:${msg?.lineNumber()})")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            android.util.Log.e("Sentinel:Map", "ResourceError: ${request?.url} -> ${error?.description}")
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            android.util.Log.d("Sentinel:Map", "onPageFinished: $url, evaluating updateMarkers with count ${markers.size}")
                            view?.evaluateJavascript(
                                "if (typeof updateMarkers === 'function') { updateMarkers($markersJson); }",
                                null
                            )
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onMarkerClick(deviceId: String) {
                            post {
                                onMarkerClick(deviceId)
                            }
                        }
                    }, "AndroidInterface")

                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                            <style>
                                $cssContent
                                html, body { height: 100%; margin: 0; padding: 0; background: #080C14; }
                                #map { position: absolute; top: 0; bottom: 0; left: 0; right: 0; background: #080C14; }
                                .leaflet-container { background: #080C14; }

                                .leaflet-div-icon {
                                    background: transparent !important;
                                    border: none !important;
                                }

                            .fleet-marker {
                                display: inline-flex;
                                flex-direction: column;
                                align-items: center;
                                cursor: pointer;
                                user-select: none;
                                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                transform: translate(-50%, -100%);
                                margin-top: -6px;
                            }

                            .fleet-chip {
                                background: rgba(24, 24, 27, 0.94);
                                border: 1px solid rgba(255, 255, 255, 0.2);
                                border-radius: 12px;
                                padding: 3px 8px;
                                display: flex;
                                align-items: center;
                                gap: 5px;
                                box-shadow: 0 4px 14px rgba(0,0,0,0.6);
                                white-space: nowrap;
                                backdrop-filter: blur(4px);
                            }

                            .chip-name {
                                color: #ffffff;
                                font-size: 11px;
                                font-weight: 700;
                                max-width: 110px;
                                overflow: hidden;
                                text-overflow: ellipsis;
                            }

                            .chip-battery {
                                font-size: 10px;
                                font-weight: 700;
                                padding: 1px 4px;
                                border-radius: 4px;
                                background: rgba(76, 175, 80, 0.25);
                                color: #81c784;
                            }

                            .chip-battery.low {
                                background: rgba(244, 67, 54, 0.3);
                                color: #ef5350;
                            }

                            .chip-status {
                                font-size: 9px;
                                font-weight: 700;
                                padding: 1px 4px;
                                border-radius: 4px;
                            }

                            .chip-status.moving {
                                background: rgba(33, 150, 243, 0.3);
                                color: #64b5f6;
                            }

                            .chip-status.stationary {
                                background: rgba(158, 158, 158, 0.2);
                                color: #bdbdbd;
                            }

                            .fleet-pin {
                                width: 14px;
                                height: 14px;
                                border-radius: 50%;
                                border: 2.5px solid #ffffff;
                                box-shadow: 0 0 8px rgba(0,0,0,0.8);
                                background-color: #2e7d32;
                                margin-top: 3px;
                            }

                            .fleet-pin.offline {
                                background-color: #757575;
                            }

                            .fleet-pin.emergency {
                                background-color: #d32f2f;
                                box-shadow: 0 0 0 4px rgba(211, 47, 47, 0.5);
                                animation: pulse-ring 1.5s infinite;
                            }

                            @keyframes pulse-ring {
                                0% { box-shadow: 0 0 0 0 rgba(211, 47, 47, 0.8); }
                                70% { box-shadow: 0 0 0 14px rgba(211, 47, 47, 0); }
                                100% { box-shadow: 0 0 0 0 rgba(211, 47, 47, 0); }
                            }

                            .cluster-badge {
                                background: linear-gradient(135deg, #1e293b, #0f172a);
                                border: 2px solid #3b82f6;
                                border-radius: 50%;
                                color: #ffffff;
                                width: 44px;
                                height: 44px;
                                display: flex;
                                flex-direction: column;
                                align-items: center;
                                justify-content: center;
                                box-shadow: 0 0 16px rgba(59, 130, 246, 0.7);
                                cursor: pointer;
                                transform: translate(-50%, -50%);
                                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            }

                            .cluster-count {
                                font-size: 13px;
                                font-weight: 800;
                                line-height: 1;
                            }

                            .cluster-label {
                                font-size: 8px;
                                font-weight: 700;
                                letter-spacing: 0.5px;
                                color: #93c5fd;
                                line-height: 1;
                                margin-top: 2px;
                            }
                        </style>
                        <script>
                            $jsContent
                        </script>
                    </head>
                    <body>
                        <div id="map"></div>
                        <script>
                            console.log("Starting map init, L defined = " + (typeof L !== 'undefined'));
                            var map;
                            try {
                                map = L.map('map', { zoomControl: false }).setView([0.0, 0.0], 2);
                                L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
                                    maxZoom: 20,
                                    subdomains: 'abcd',
                                    attribution: '© CARTO'
                                }).addTo(map);
                                console.log("Map initialized successfully");
                            } catch (e) {
                                console.error("Map init exception: " + e.message);
                            }

                            var currentMarkers = [];
                            var currentLayerGroup = L.layerGroup().addTo(map);
                            var initialFitDone = false;

                            function renderClusters() {
                                currentLayerGroup.clearLayers();
                                if (!currentMarkers || currentMarkers.length === 0) return;

                                var clusterRadius = 60; // cluster within 60 screen pixels
                                var clusters = [];
                                var visited = {};

                                for (var i = 0; i < currentMarkers.length; i++) {
                                    if (visited[i]) continue;
                                    var p1 = map.latLngToLayerPoint([currentMarkers[i].latitude, currentMarkers[i].longitude]);
                                    var cluster = [currentMarkers[i]];
                                    visited[i] = true;

                                    for (var j = i + 1; j < currentMarkers.length; j++) {
                                        if (visited[j]) continue;
                                        var p2 = map.latLngToLayerPoint([currentMarkers[j].latitude, currentMarkers[j].longitude]);
                                        var dist = Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
                                        if (dist < clusterRadius) {
                                            cluster.push(currentMarkers[j]);
                                            visited[j] = true;
                                        }
                                    }
                                    clusters.push(cluster);
                                }

                                clusters.forEach(function(cluster) {
                                    if (cluster.length === 1) {
                                        var m = cluster[0];
                                        var pinClass = m.isEmergency ? 'fleet-pin emergency' : (m.isOnline ? 'fleet-pin' : 'fleet-pin offline');
                                        var battClass = m.battery <= 20 ? 'chip-battery low' : 'chip-battery';
                                        var statusHtml = m.isMoving ? '<span class="chip-status moving">MOVING</span>' : '<span class="chip-status stationary">PARKED</span>';
                                        
                                        function escapeHtml(str) {
                                            if (!str) return 'Device';
                                            return String(str)
                                                .replace(/&/g, '&amp;')
                                                .replace(/</g, '&lt;')
                                                .replace(/>/g, '&gt;')
                                                .replace(/"/g, '&quot;')
                                                .replace(/'/g, '&#039;');
                                        }

                                        var safeName = escapeHtml(m.deviceName);
                                        var html = '<div class="fleet-marker">' +
                                                   '<div class="fleet-chip">' +
                                                   '<span class="chip-name">' + safeName + '</span>' +
                                                   '<span class="' + battClass + '">⚡' + m.battery + '%</span>' +
                                                   statusHtml +
                                                   '</div>' +
                                                   '<div class="' + pinClass + '"></div>' +
                                                   '</div>';

                                        var icon = L.divIcon({
                                             html: html,
                                             className: '',
                                             iconSize: [0, 0]
                                         });

                                        var marker = L.marker([m.latitude, m.longitude], { icon: icon });
                                        marker.on('click', function() {
                                            AndroidInterface.onMarkerClick(m.uniqueKey || m.deviceId);
                                        });
                                        marker.addTo(currentLayerGroup);
                                    } else {
                                        // Cluster of multiple units
                                        var avgLat = 0, avgLng = 0;
                                        cluster.forEach(function(item) {
                                            avgLat += item.latitude;
                                            avgLng += item.longitude;
                                        });
                                        avgLat /= cluster.length;
                                        avgLng /= cluster.length;

                                        var clusterHtml = '<div class="cluster-badge">' +
                                                          '<span class="cluster-count">' + cluster.length + '</span>' +
                                                          '<span class="cluster-label">UNITS</span>' +
                                                          '</div>';

                                        var clusterIcon = L.divIcon({
                                            html: clusterHtml,
                                            className: '',
                                            iconSize: [0, 0]
                                        });

                                        var clusterMarker = L.marker([avgLat, avgLng], { icon: clusterIcon });
                                        clusterMarker.on('click', function() {
                                            var bounds = L.latLngBounds(cluster.map(function(item) { return [item.latitude, item.longitude]; }));
                                            map.fitBounds(bounds, { padding: [60, 60], maxZoom: 17 });
                                        });
                                        clusterMarker.addTo(currentLayerGroup);
                                    }
                                });
                            }

                            map.on('zoomend moveend', function() {
                                renderClusters();
                            });

                            function updateMarkers(markersData) {
                                currentMarkers = markersData || [];
                                map.invalidateSize();
                                renderClusters();

                                if (!initialFitDone && currentMarkers.length > 0) {
                                    var groupPoints = currentMarkers.map(function(m) { return [m.latitude, m.longitude]; });
                                    if (groupPoints.length === 1) {
                                        map.setView(groupPoints[0], 15);
                                    } else if (groupPoints.length > 1) {
                                        map.fitBounds(groupPoints, { padding: [60, 60] });
                                    }
                                    initialFitDone = true;
                                }
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.evaluateJavascript(
                "if (typeof updateMarkers === 'function') { updateMarkers($markersJson); }",
                null
            )
        },
        onRelease = { webView ->
            webView.destroy()
        }
    )

        // Top-start HUD chip
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xD90F172A))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "FLEET RADAR",
                    color = Color(0xFFAAC7E8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "· ${markers.size} UNITS",
                    color = Color(0xFF9AA7B6),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            }
        }

        // Bottom-end Floating Action Pill: Open in Google Maps
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xD90F172A))
                .clickable { launchGoogleMapsFleet() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Open in Maps",
                    tint = Color(0xFFAAC7E8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Open Google Maps ↗",
                    color = Color(0xFFF0F2F4),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NoLocationState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.LocationOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No locations available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Devices with location data will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
