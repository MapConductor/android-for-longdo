# Longdo Map for MapConductor Android

## Description

MapConductor provides a unified API for Android Jetpack Compose.
You can use Longdo Map with Jetpack Compose, and switch to other Maps SDKs
(such as MapLibre, Mapbox, HERE, MapTiler, and so on) at any time using the same API surface.

This module wraps the official [Longdo Map API3 SDK](https://map.longdo.com/docs3/android/)
(`com.longdo.map:sdk3`, a WebView-based SDK backed by Longdo Map JS API3) behind the MapConductor
`MapViewStateInterface` / `MapViewControllerInterface` contracts.

## Setup

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

## Available designs

`LongdoDesign` exposes Longdo base layers: `Normal`, `Gray`, `Poi`, `Traffic`,
`Satellite`, `Hybrid`, `Terrain`.

## Notes

The Longdo Map API3 SDK renders Longdo Map JS API3 inside a WebView. Map display,
camera control (move / animate / fitBounds), design (base layer) switching, and tap /
camera-move events are supported through the SDK bridge (`call` / `run` and a
`@JavascriptInterface` event channel).

Overlay features that other MapConductor providers implement via native GL layers
(markers, polygons, polylines, circles, ground images, raster layers) follow a different
mechanism on this SDK and are not part of this wrapper. The `content` lambda is still
evaluated under the same `CompositionLocal`s for API compatibility, but overlays are not
rendered.

## License

Apache License 2.0
