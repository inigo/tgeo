package net.surguy.tgeo
import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import net.surguy.tgeo.AuthTag.TAG

const val PREFS_FILENAME = "my_secure_prefs"
private const val ACCESS_TOKEN_KEY = "authToken"
private const val REFRESH_TOKEN_KEY = "refreshToken"
const val HOME_STATUS_KEY = "homeStatus"
private const val LATITUDE_KEY = "latitude"
private const val LONGITUDE_KEY = "longitude"
private const val ENFORCE_ECO_STATE_KEY = "enforceEcoState"

private object AuthTag {
    const val TAG = "net.surguy.tgeo.Prefs"
}

class AuthStorer(val sharedPreferences: SharedPreferences) {

    fun storeTokens(authInfo: AuthInfo) {
        sharedPreferences.edit().apply {
            putString(ACCESS_TOKEN_KEY, authInfo.accessToken)
            putString(REFRESH_TOKEN_KEY, authInfo.refreshToken)
            apply()
        }
    }

    fun clearTokens() {
        sharedPreferences.edit().apply {
            remove(ACCESS_TOKEN_KEY)
            remove(REFRESH_TOKEN_KEY)
            apply()
        }
    }

    fun getTokens(): AuthInfo? {
        val accessToken = sharedPreferences.getString(ACCESS_TOKEN_KEY, null)
        val refreshToken = sharedPreferences.getString(REFRESH_TOKEN_KEY, null)
        return if ((accessToken!=null) && (refreshToken!=null)) {
            AuthInfo(accessToken, refreshToken)
        } else {
            null
        }
    }
}

fun storeHomeStatus(isAtHome: Boolean) {
    Log.d(TAG, "Storing home status of $isAtHome")
    MainActivityPrefs.sharedPreferences.edit().apply {
        putString(HOME_STATUS_KEY, isAtHome.toString())
        apply()
    }
}

fun storeHomeLocation(location: Location) {
    Log.d(TAG, "Storing home location of latitude ${location.latitude} by longitude ${location.longitude}")
    MainActivityPrefs.sharedPreferences.edit().apply {
        putString(LATITUDE_KEY, location.latitude.toString())
        putString(LONGITUDE_KEY, location.longitude.toString())
        apply()
    }
}

fun getHomeLocation(): Location? {
    val latitude = MainActivityPrefs.sharedPreferences.getString(LATITUDE_KEY, null)?.toDoubleOrNull()
    val longitude = MainActivityPrefs.sharedPreferences.getString(LONGITUDE_KEY, null)?.toDoubleOrNull()
    return if ((latitude!=null) && (longitude!=null)) {
        val location = Location("")
        location.latitude = latitude
        location.longitude = longitude
        location
    } else {
        null
    }
}

fun storeEnforceEcoState(enforceEcoState: Boolean) {
    Log.d(TAG, "Storing enforce Eco state of $enforceEcoState")
    MainActivityPrefs.sharedPreferences.edit().apply {
        putBoolean(ENFORCE_ECO_STATE_KEY, enforceEcoState)
        apply()
    }
}

fun getEnforceEcoState(): Boolean {
    return MainActivityPrefs.sharedPreferences.getBoolean(ENFORCE_ECO_STATE_KEY, true)
}


data class AuthInfo(val accessToken: String, val refreshToken: String)
