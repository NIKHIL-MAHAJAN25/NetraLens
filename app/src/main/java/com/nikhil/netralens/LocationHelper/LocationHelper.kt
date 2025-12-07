package com.nikhil.netralens.LocationHelper

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class LocationHelper(context: Context) {
    // The entry point to Google's Location Services
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // We check permissions in the UI before calling this
    fun getCurrentLocation(onResult: (String) -> Unit) {
        // "Priority.PRIORITY_HIGH_ACCURACY" asks the GPS chip to wake up
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                // Success! Create a clickable Google Maps link
                val link = "http://maps.google.com/?q=${location.latitude},${location.longitude}"
                onResult(link)
            } else {
                onResult("Location not found")
            }
        }.addOnFailureListener {
            onResult("Location error: ${it.message}")
        }
    }
}