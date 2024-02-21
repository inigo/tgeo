package net.surguy.tgeo

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import net.surguy.tgeo.MainActivityPrefs.sharedPreferences
import net.surguy.tgeo.ui.theme.MyApplicationTheme


class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "net.surguy.tgeo.MainActivity"
    }

    private lateinit var authStorer: AuthStorer
    private lateinit var authRetriever: AuthRetriever
    private lateinit var thermostat: Thermostat
    private lateinit var requestMultiplePermissionsLauncher: ActivityResultLauncher<Array<String>>

    // Needs to be referenced here to avoid GC, otherwise the listener stops working after a while
    var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var auth: AuthInfo? = null
    private var lastThermostatState: SimpleDeviceInfo? = null

    // Project-specific info
    private val projectId = BuildConfig.PROJECT_ID
    private val clientId = BuildConfig.CLIENT_ID
    private val clientSecret = BuildConfig.CLIENT_SECRET
    // Could perhaps work this out via listing devices?
    private val deviceId = BuildConfig.DEVICE_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        thermostat = Thermostat(projectId, deviceId)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(PREFS_FILENAME, masterKeyAlias, this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        authStorer = AuthStorer(sharedPreferences)
        authRetriever = AuthRetriever(this, projectId, clientId, clientSecret)
        val existingAuth = authStorer.getTokens()
        if (existingAuth!=null) {
            refreshToken(existingAuth)
        } else {
            retrieveAuth()
        }

        // Originally, this was failing, because the app was already set to "Refused permission" even
        // when I had never asked for it, and needed to fix it by explicitly going into Permissions to allow it
        requestMultiplePermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(), { permissions ->
                val allGranted = permissions.containsValue(true)
                Log.d(TAG, "Permissions are "+permissions)
                for ((key, value) in permissions) {
                    Log.d(TAG, ("Key: $key, Value: $value"))
                }
                if (allGranted) {
                    Log.i(TAG, "Permissions now granted - now calling passed-in function")
                    callGeoSetup()
                } else {
                    Log.w(TAG, "Was not granted permissions - cannot find location")
                }
            })

        val geo = GeofenceChecker(this)
        geo.tryToRenewGeofence(getHomeLocation())

        val homeStatus = sharedPreferences.getString(HOME_STATUS_KEY, "unknown")
        Log.i(TAG, "Home status is "+homeStatus)

        setContent {
            MyApplicationTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }


    private fun SharedPreferences.stringFlow(key: String, defaultValue: String): Flow<String> = callbackFlow {
        prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, updatedKey: String? ->
            if (key == updatedKey) {
                Log.d(TAG, "Sending new value for $key of " + getString(key, defaultValue))
                trySend(getString(key, defaultValue) ?: defaultValue)
            }
        }
        Log.i(TAG, "Registering shared preferences change listener")
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsChangeListener)

        awaitClose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
        }
    }

    private fun callGeoSetup() {
        val geo = GeofenceChecker(this)
        geo.setupGeofence()
    }

    private fun retrieveAuth() {
        authRetriever.retrieveAuth {
            authStorer.storeTokens(it)
            auth = it
        }
    }

    private fun refreshToken(existingAuth: AuthInfo) {
        authRetriever.refreshAccessToken(existingAuth, onResult = {
            authStorer.storeTokens(it)
            auth = it
        }, onFailure = {
            authStorer.clearTokens()
            auth = null
            // Can't call retrieveAuth here, because that can only be called in setup since it registers callbacks
        })
    }

    @Composable
    fun MainScreen() {
        val initialValue = "..."
        var statusText by remember { mutableStateOf("") }
        var currentTemperature by remember { mutableStateOf(initialValue) }
        var ecoMode by remember { mutableStateOf("...") }
        var buttonText by remember { mutableStateOf("...") }
        val coroutineScope = rememberCoroutineScope()
        val initialHomeStatus = sharedPreferences.getString(HOME_STATUS_KEY, "unknown")
        val homeStatus by sharedPreferences.stringFlow(HOME_STATUS_KEY, "true").collectAsState(initial = initialHomeStatus)
        var enforceEcoWhenAway by remember { mutableStateOf(false) }
        var previousHomeStatus by remember { mutableStateOf(homeStatus) }

        LaunchedEffect(key1 = Unit) {
            enforceEcoWhenAway = getEnforceEcoState()
        }

        val contextForToast = LocalContext.current.applicationContext
        var menuExpanded by remember { mutableStateOf(false) }

        val displayState = { info: SimpleDeviceInfo ->
            currentTemperature =  String.format("%.1f", info.ambientTemperature)
            ecoMode = if (info.ecoMode) "Eco" else "Heating normal"
            buttonText = if (info.ecoMode) "Turn off Eco" else "Switch to Eco"
        }

        val setThermostat: suspend (String, Boolean) -> Unit = { token: String, newState: Boolean ->
            thermostat.changeThermostatSetting(token, newState).let { resultInfo: Result<SimpleDeviceInfo?> ->
                resultInfo.onSuccess { info ->
                    statusText = "✔"
                    info?.let {
                        displayState(it)
                    }
                }.onFailure { e ->
                    statusText = "☠ " + e.message
                }
            }
        }

        // This might be done better as a LaunchedEffect with a key of homeStatus?
        val checkEcoStatus: suspend (SimpleDeviceInfo) -> Unit = { info: SimpleDeviceInfo ->
            val token = auth?.accessToken
            if (!info.ecoMode && enforceEcoWhenAway && homeStatus=="false" && token!=null) {
                Log.i(TAG, "Enforcing ECO mode since away from home")
                setThermostat(token, false)
            } else if (info.ecoMode && homeStatus=="true" && previousHomeStatus=="false" && token!=null) {
                Log.i(TAG, "Removing ECO mode since home for the first time")
                setThermostat(token, true)
            }
            previousHomeStatus = homeStatus
        }

        Box(modifier = Modifier.fillMaxSize()) {

            Column {


                Text(
                    text = statusText,
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = currentTemperature + " °",
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = ecoMode,
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (homeStatus=="true") "Near home" else if (homeStatus=="false") "Away from home" else "Home not yet set",
                    style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )

                LaunchedEffect(Unit) {
                    while (true) {
                        val delayPeriod = if (auth?.accessToken==null || currentTemperature==initialValue) 500L else 30_000L
                        delay(delayPeriod)
                        auth?.accessToken?.let { token ->
                            thermostat.retrieveThermostatInfo(token)?.let { info ->
                                Log.d(TAG, "Successfully retrieved thermostat info: $info")
                                displayState(info)
                                checkEcoStatus(info)
                                lastThermostatState = info
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(30 * 60 * 1000)
                        Log.i(TAG, "Periodic refresh of access token")
                        auth?.let { refreshToken(it) }
                    }
                }



                Column (
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ){
                    Text(
                        text = "Enforce Eco when away",
                        modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterHorizontally), //Modifier.align(Alignment.CenterVertically)
                        style = TextStyle(textDecoration = if (enforceEcoWhenAway) TextDecoration.None else TextDecoration.LineThrough)
                    )
                    Switch(
                        checked = enforceEcoWhenAway,
                        onCheckedChange = {
                            enforceEcoWhenAway = it
                            storeEnforceEcoState(it)
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                ExtendedFloatingActionButton(
                    onClick = {
                        statusText = "⌛"
                        coroutineScope.launch {
                            auth?.accessToken?.let { token ->
                                val newState = lastThermostatState?.ecoMode ?: false
                                Log.i(TAG, "Setting ECO mode to new state $newState")
                                setThermostat(token, newState)
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.End)
                ) {
                    Text(buttonText)
                }

            }

            Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopEnd)) {

                IconButton(
                    onClick = {
                        menuExpanded = true
                    },
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Open Menu")
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text("Set home location")
                        },
                        onClick = {
                            withLocationPermission {
                                Toast.makeText(contextForToast, "Setting home", Toast.LENGTH_SHORT).show()
                                callGeoSetup()
                            }
                            menuExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Home,
                                contentDescription = null
                            )
                        }
                    )
                }
            }

        }
    }

    private fun withLocationPermission(fn: () -> Unit) {
        val fineLocationPermitted = (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val backgroundLocationPermitted = (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

        if (fineLocationPermitted && backgroundLocationPermitted) {
            Log.i(TAG, "Permissions already granted - calling passed-in function")
            fn()
        } else {
            requestMultiplePermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

}

object MainActivityPrefs {
    lateinit var sharedPreferences: SharedPreferences
}
