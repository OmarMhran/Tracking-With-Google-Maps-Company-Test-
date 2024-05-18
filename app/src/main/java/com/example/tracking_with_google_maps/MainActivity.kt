package com.example.tracking_with_google_maps

import android.R.attr.apiKey
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tracking_with_google_maps.utils.MapUtils
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.*
import com.google.android.gms.location.places.Place
import com.google.android.gms.location.places.Places
import com.google.android.gms.location.places.ui.PlaceAutocompleteFragment
import com.google.android.gms.location.places.ui.PlaceSelectionListener
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import com.google.maps.model.TravelMode


class MainActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var markerIcon: Bitmap
    private var startLatLng: LatLng? = null
    private var endLatLng: LatLng? = null
    private var driverMarker: Marker? = null
    private lateinit var points: MutableList<LatLng>

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 123
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        
        mapFragment.getMapAsync(this)

        // Initialize the AutocompleteSupportFragment.
        val autocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.autocomplete_fragment)
                    as AutocompleteSupportFragment




        markerIcon = MapUtils.getCarBitmap(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createLocationRequest()
        buildLocationCallback()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setOnMapClickListener(this)

        mMap.uiSettings.isZoomControlsEnabled = true

        try {
            val success = mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this@MainActivity,
                R.raw.uber_maps_style))
            if (!success)
                Log.d("map style", "error parsing json")

        } catch (e: Resources.NotFoundException) {
            Log.d("map style", e.message.toString())
        }

        // Check if user has granted location permission
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
            mMap.uiSettings.isMyLocationButtonEnabled = true
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                LOCATION_PERMISSION_REQUEST_CODE)
        }

    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 10f
        }
    }

    private fun buildLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                handleNewLocation(locationResult.lastLocation!!)
            }
        }
    }

    private fun handleNewLocation(location: Location) {
        if (startLatLng == null) {
            startLatLng = LatLng(location.latitude, location.longitude)
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLatLng!!, 15f))
        }
        if (endLatLng == null) {
            mMap.setOnMapClickListener(this)
        } else {
            mMap.setOnMapClickListener(null)
        }

        if (driverMarker == null) {
            driverMarker = mMap.addMarker(
                MarkerOptions()
                    .position(startLatLng!!)
                    .icon(BitmapDescriptorFactory.fromBitmap(markerIcon))
            )
        }

//        drawPath()
//        animateMarker(driverMarker!!, LatLng(location.latitude, location.longitude))

    }

    private fun animateMarker(marker: Marker, destination: LatLng) {
        val startPosition = marker.position
        val startRotation = marker.rotation
        val bearing = SphericalUtil.computeHeading(startPosition, destination).toFloat()
        var interpolator = LinearInterpolator()

        val startPositionLatLng = LatLng(
            startPosition.latitude,
            startPosition.longitude
        )

        val pointOnRoute = PolyUtil.isLocationOnPath(
            LatLng(
               destination.latitude,
               destination.longitude
            ), points, false, 50.0
        )

        if (pointOnRoute) {
            drawDeviatedPath()
        }

        val valueAnimator = ValueAnimator.ofFloat(0f, 1f)
        valueAnimator.apply {
            duration = 3000
            interpolator = interpolator
            addUpdateListener { animation ->
                val v = animation.animatedFraction
                val newPosition =
                    SphericalUtil.interpolate(startPositionLatLng, destination, v.toDouble())
                marker.position = newPosition
                val rotation = if (v == 0f) startRotation else bearing * v + startRotation * (1 - v)
                marker.rotation = rotation
            }
        }
        valueAnimator.start()
    }

    override fun onMapClick(latLng: LatLng) {
        if (endLatLng == null) {
            val markerOptions = MarkerOptions()
                .position(latLng)
                .title("end")
            endLatLng = latLng
            mMap.addMarker(markerOptions)
            mMap.moveCamera(CameraUpdateFactory.newLatLng(latLng))

            drawPath()
        } else {
            null
        }

    }


    @SuppressLint("MissingPermission")
    private fun drawPath() {
        if (startLatLng != null && endLatLng != null) {
            val directions = DirectionsApi.newRequest(getGeoContext())
                .origin("${startLatLng?.latitude!!},${startLatLng?.longitude!!}")
                .destination("${endLatLng?.latitude!!},${endLatLng?.longitude!!}")
                .mode(TravelMode.DRIVING)
                .await()

            // Get the first route from the directions results
            val route = directions.routes[0]

            // Get the encoded path of the polyline for the route
            val encodedPath = route.overviewPolyline.encodedPath

            // Decode the encoded path into an array of LatLng objects
            points = PolyUtil.decode(encodedPath)
            Log.d("points", points.toString())

            // Create a polyline options object and add each point to the polyline
            val polylineOptions = PolylineOptions().apply {
                width(10f)
                color(Color.BLACK)
                addAll(points)
            }


            mMap.addPolyline(polylineOptions)
        } else {
            Toast.makeText(this@MainActivity, "Please Select the destination", Toast.LENGTH_SHORT)
                .show()
        }

    }

    private fun drawDeviatedPath() {

    }


    private fun getGeoContext(): GeoApiContext {
        val geoApiContext = GeoApiContext.Builder()
            .apiKey(getString(R.string.directions_apikey))
            .build()
        return geoApiContext
    }


    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

//    override fun onPlaceSelected(p0: Place?) {
//        Toast.makeText(this, "$p0?.name!! + $p0?.latLng?.latitude!! + $p0?.latLng?.longitude!!", Toast.LENGTH_SHORT).show()
//    }
//
//    override fun onError(p0: Status?) {
//        Toast.makeText(this, p0?.statusMessage.toString(), Toast.LENGTH_SHORT).show()
//    }
}

