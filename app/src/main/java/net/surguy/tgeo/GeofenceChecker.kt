package net.surguy.tgeo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.ComponentActivity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng


class GeofenceChecker(private val context: ComponentActivity) {
    companion object {
        private const val TAG = "net.surguy.tgeo.GeofenceChecker"
    }

    fun setupGeofence() {
        val fineLocationPermitted = (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!fineLocationPermitted) {
            Log.w(TAG, "Do not have permission to request location")
            return
        }

        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            Log.w(TAG, "Google Play services are not available - cannot retrieve location")
            return
        }

        Log.d(TAG, "Checking for current location")
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let { l ->
                    Log.i(TAG, "Currently at ${l.latitude} by ${l.longitude} ")
                    storeHomeLocation(location)
                    storeHomeStatus(true)
                    Log.d(TAG, "Now setting up geofence")
                    setupGeofence(l.latitude, l.longitude)
                }
            }.addOnFailureListener {
                Log.w("TAG", "Location lookup failed with ${it.message}", it)
            }.addOnCompleteListener{
                Log.d(TAG, "Location check task complete with result "+it.result+" and exceptioh "+it.exception)
                if (it.result==null && it.exception==null && !it.isCanceled) {
                    Log.w(TAG, "No 'last location' available - location has not recently been retrieved. ")
                }
            }
            .addOnCanceledListener {
                Log.w(TAG, "Location check was cancelled")
            }
    }

    fun tryToRenewGeofence(location: Location?) {
        location?.let {
            Log.d(TAG, "Restarted app - trying to renew geofences")
            setupGeofence(it.latitude, it.longitude)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupGeofence(homeLatitude: Double, homeLongitude: Double) {
        val backgroundLocationPermitted = (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (!backgroundLocationPermitted) {
            Log.w(TAG, "Do not have permission to request location")
            return
        }

        val homeLocation = LatLng(homeLatitude, homeLongitude)
        val awayGeofenceRadiusMetres = 5000f
        val homeGeofenceRadiusMetres = 4000f
        val homeDwellTimeMilliseconds = 1 * 60 * 1000

        val awayGeofence = Geofence.Builder()
            .setRequestId("awayFromHome")
            .setCircularRegion(homeLocation.latitude, homeLocation.longitude, awayGeofenceRadiusMetres)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val homeGeofence = Geofence.Builder()
            .setRequestId("returningHome")
            .setCircularRegion(homeLocation.latitude, homeLocation.longitude, homeGeofenceRadiusMetres)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(homeDwellTimeMilliseconds)
            .build()

        val geofencingRequest = GeofencingRequest.Builder().apply {
            setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
            addGeofence(awayGeofence)
            addGeofence(homeGeofence)
        }.build()

        // The intent needs to be MUTABLE, because otherwise its data can't be changed when the intent is sent
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val geofencePendingIntent: PendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val geofencingClient = LocationServices.getGeofencingClient(context)

        Log.i(TAG, "Sending geofence setup request: $geofencingRequest")
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Geofence added successfully")
            }
            addOnFailureListener {
                Log.w(TAG, "Geofence setup failed with "+it.message, it)
            }
        }
    }
}

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "net.surguy.tgeo.GeofenceBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent==null) {
            Log.e(TAG, "Could not get geofencing intent")
            return
        } else if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "Away from home!")
            storeHomeStatus(false)
        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            Log.i(TAG, "Back home!")
            storeHomeStatus(true)
        }
    }
}
