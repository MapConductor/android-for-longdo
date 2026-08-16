# Longdo Map for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use Longdo Map with Jetpack Compose, and switch to other Maps SDKs
(such as MapLibre, Mapbox, HERE, MapTiler, and so on) at any time using the same API surface.

This module wraps the official [Longdo Map API3 SDK](https://map.longdo.com/docs3/android/)
(`com.longdo.map:sdk3`, a WebView-based SDK backed by Longdo Map JS API3) behind the MapConductor
`MapViewStateInterface` / `MapViewControllerInterface` contracts.

## Setup

https://mapconductor.com/setup/android/longdo/

The Longdo Map SDK is distributed from Longdo's Maven repository. Add it to your
`settings.gradle.kts` (already configured in this monorepo's root `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.longdo.com/artifactory/libs-release-public") }
    }
}
```

Add your Longdo Map API key. This module reads it from an `AndroidManifest.xml`
`<meta-data>` entry (injected by the Secrets Gradle Plugin from `secrets.properties` /
`LONGDO_API_KEY`):

```xml
<meta-data android:name="longdo.map.key" android:value="${LONGDO_API_KEY}" />
```

Alternatively, set it directly before showing a map:

```kotlin
com.longdo.sdk3.LongdoMap.API_KEY = "YOUR_LONGDO_API_KEY"
```

The key must be registered for your application package name in the
[Longdo Map console](https://map.longdo.com/console); the SDK sends the package name for
validation.

## Usage

```kotlin
@Composable
fun MapView(modifier: Modifier = Modifier) {
    // Bangkok
    val center = GeoPoint(latitude = 13.7563, longitude = 100.5018)

    val mapViewState =
        rememberLongdoMapViewState(
            mapDesign = LongdoDesign.Normal,
            cameraPosition = MapCameraPosition(position = center, zoom = 12.0),
        )

    LongdoMapView(
        state = mapViewState,
        modifier = modifier,
        onMapLoaded = { /* map ready */ },
        onMapClick = { point -> /* tapped at point */ },
        onCameraMove = { camera -> /* camera moved */ },
    )
}
```

## Components

### LongdoMapView [[docs]](https://mapconductor.com/mapview/)

```kotlin
@Composable
fun MapExample() {
    val initCameraPosition = MapCameraPosition(
        position = GeoPoint(
            latitude = 34.091,
            longitude = -117.886,
        ),
        zoom = 9.0,
        tilt = 60.0,
        bearing = 30.0,
    )

    val mapViewState = rememberLongdoMapViewState(
        cameraPosition = initCameraPosition,
    )

    LongdoMapView(mapViewState)
}
```

------------------------------------------------------------------------

### Marker [[docs]](https://mapconductor.com/markers/)

```kotlin
@Composable
fun MarkerExample() {
    val markerState = remember { MarkerState(
        position = GeoPoint(...),
        icon = DefaultMarkerIcon().copy(
            label = "Longdo",
        ),
        onClick = {
            it.animate(MarkerAnimation.Bounce)
        },
    ) }

    LongdoMapView(...) {
        Marker(markerState)
    }
}
```

------------------------------------------------------------------------

### InfoBubble [[docs]](https://mapconductor.com/info-bubble/)

```kotlin
@Composable
fun InfoBubbleExample() {
    var selectedMarker by remember { mutableStateOf<MarkerState?>(null) }

    val markerState = remember { MarkerState(
        ...,
        onClick = {
            selectedMarker = it
        },
    ) }

    LongdoMapView(...) {
        Marker(markerState)
        selectedMarker?.let {
            InfoBubble(
                marker = it,
            ) {
                Text("Hello, world!")
            }
        }
    }
}
```

------------------------------------------------------------------------

### Circle [[docs]](https://mapconductor.com/circle/)

```kotlin
@Composable
fun CircleExample() {

    val circleState = remember { CircleState(
        center = GeoPoint(...),
        radiusMeters = 50.0,
        fillColor = Color.Blue.copy(alpha = 0.5f),
        onClick = {
            it.state.fillColor = Color.Red.copy(alpha = 0.5f)
        }
    ) }

    LongdoMapView(...) {
        Circle(circleState)
    }
}
```

------------------------------------------------------------------------

### Polyline [[docs]](https://mapconductor.com/polyline/)

```kotlin
@Composable
fun PolylineExample() {

    val polylineState = remember { PolylineState(
            points = airports,
            strokeColor = Color.Blue.copy(alpha = 0.5f),
            strokeWidth = 4.dp,
            geodesic = true,
        ) }

    LongdoMapView(...) {
        Polyline(polylineState)
    }
}
```

------------------------------------------------------------------------

### Polygon [[docs]](https://mapconductor.com/polygon/)

```kotlin
@Composable
fun PolygonExample() {

    val polygonState = remember { PolygonState(
        points = goryokaku,
        strokeColor = Color.Blue.copy(alpha = 0.5f),
        fillColor =  Color.Red.copy(alpha = 0.7f),
    ) }

    LongdoMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### Polygon Hole

```kotlin
@Composable
fun PolygonHoleExample() {

    val polygonState =
        remember {
            PolygonState(
                points = listOf(...),
                holes = listOf(
                            listOf(...),
                            listOf(...),
                        ),
                fillColor = Color(0xCC787880),
                strokeColor = Color.Red,
                strokeWidth = 2.dp,
            )
        }

    LongdoMapView(...) {
        Polygon(polygonState)
    }
}
```

------------------------------------------------------------------------

### GroundImage [[docs]](https://mapconductor.com/ground-image/)

```kotlin
@Composable
fun GroundImageExample() {
    val groundImageState = remember { GroundImageState(
        bounds = GeoRectBounds(
            southWest = GeoPoint.fromLatLong(...),
            northEast = GeoPoint.fromLatLong(...),
        ),
        image = image,
        opacity = 0.5f,
    ) }

    LongdoMapView(state = mapViewState) {
        GroundImage(groundImageState)
    }
}
```

## Available designs

`LongdoDesign` exposes Longdo base layers: `Normal`, `Easy`, `Pastel`, `PastelGray`,
`Hard`, `Gray`, `Light`, `Night`, `Dark`, `Political`, `Osm`, `Satellite`, `Hybrid`.

## Supported overlays

Marker (including clustering and tile-rendered large marker sets), Polyline, Polygon
(holes supported), Circle, GroundImage, RasterLayer and InfoBubble — the same unified API
as the native-GL providers.

## Notes

The Longdo Map API3 SDK renders Longdo Map JS API3 inside a WebView, so overlays reach the
map as style layers on the SDK's internal MapLibre GL rather than as native annotations:
each shape adds one GeoJSON source plus a `fill` / `line` layer pair. Geodesic
interpolation, antimeridian splitting and hole unioning reuse the shared core utilities
(`buildUnwrappedPolygonRings`, `unionHoles`), so the resulting geometry matches the other
providers. Hit-testing is done by the core managers — `PolygonManager` uses a geodesic
winding test — not by the SDK.

Map display, camera control (move / animate / fitBounds), design (base layer) switching,
and tap / camera-move events go through the SDK bridge (`call` / `run` and a
`@JavascriptInterface` event channel).

## License

Apache License 2.0
