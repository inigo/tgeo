package net.surguy.tgeo

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

class Thermostat(private val projectId: String, private val deviceId: String) {

    companion object {
        private const val TAG = "net.surguy.tgeo.Thermostat"
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://smartdevicemanagement.googleapis.com/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    private val apiService = retrofit.create(ApiService::class.java)

    suspend fun retrieveThermostatInfo(accessToken: String): SimpleDeviceInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d("Action", "Making request with access token ${accessToken}")
            val response = apiService.getState(projectId, deviceId, "Bearer $accessToken")
            if (response.isSuccessful) {
                response.body()?.let { toSimpleDeviceInfo(it) }
            } else {
                Log.w(TAG, "Thermostat info was not successfully retrieved "+response.message()+" and "+ response.errorBody()?.string())
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving thermostat info: ${e.message}")
            null
        }
    }

    suspend fun listDevices(accessToken: String): List<SimpleDeviceInfo>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Making request with access token ${accessToken}")
            val response = apiService.listDevices(projectId, "Bearer $accessToken")
            if (response.isSuccessful) {
                Log.i(TAG, "Retrieved list of devices!")
                response.body()?.let { b: DeviceInfos -> b.devices.map { d -> toSimpleDeviceInfo(d) } }
            } else {
                Log.w(TAG, "Thermostat info was not successfully retrieved "+response.message()+" and "+ response.errorBody()?.string())
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving list of devices: ${e.message}")
            null
        }
    }

    suspend fun changeThermostatSetting(accessToken: String, on: Boolean): Result<SimpleDeviceInfo?> {
        val mode = if (on) "OFF" else "MANUAL_ECO" // This is actually turning ECO mode on and off, hence on/off are reversed
        val payload = PayloadModel(
            command = "sdm.devices.commands.ThermostatEco.SetMode",
            params = Params(mode = mode)
        )

        return try {
            val response = apiService.postCommand(projectId, deviceId, "Bearer $accessToken", payload)
            if (response.isSuccessful) {
                val deviceInfo = retrieveThermostatInfo(accessToken)
                Result.success(deviceInfo)
            } else {
                val error = response.errorBody()?.string()
                Log.w(TAG, "Failed when setting thermostat: $error")
                Result.failure(ApiAccessException("Failure when setting thermostat: $error"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error when setting ECO state: ${e.message}")
            Result.failure(e)
        }
    }

}

class ApiAccessException(message: String) : Exception(message)

interface ApiService {
    @POST("enterprises/{projectId}/devices/{deviceId}:executeCommand")
    suspend fun postCommand(
        @Path("projectId") projectId: String,
        @Path("deviceId") deviceId: String,
        @Header("Authorization") authHeader: String,
        @Body payload: PayloadModel
    ): Response<EmptyResponse>

    @GET("enterprises/{projectId}/devices/{deviceId}")
    suspend fun getState(
        @Path("projectId") projectId: String,
        @Path("deviceId") deviceId: String,
        @Header("Authorization") authHeader: String
    ): Response<DeviceInfo>

    @GET("enterprises/{projectId}/devices")
    suspend fun listDevices(@Path("projectId") projectId: String,
                            @Header("Authorization") authHeader: String): Response<DeviceInfos>
}



/**
 * Serializes to:
 *
 * {
 *   "command": "[a command]",
 *   "params": {
 *     "mode": "[an appropriate mode]"
 *   }
 * }
 *
 */
data class PayloadModel(
    val command: String,
    val params: Params
)
data class Params(
    val mode: String
)

data class EmptyResponse(val dummy: String = "")

/** The version of the data from the thermostat that maps to the JSON wire format */
data class DeviceInfo(
    val name: String,
    val traits: Traits
)

data class DeviceInfos(
    val devices: List<DeviceInfo>
)

data class Traits(
    @SerializedName("sdm.devices.traits.Humidity")
    val humidity: HumidityTrait?,

    @SerializedName("sdm.devices.traits.ThermostatMode")
    val thermostatMode: ThermostatModeTrait?,

    @SerializedName("sdm.devices.traits.ThermostatEco")
    val thermostatEco: ThermostatEcoTrait?,

    @SerializedName("sdm.devices.traits.ThermostatTemperatureSetpoint")
    val thermostatTemperatureSetpoint: ThermostatTemperatureSetpointTrait?,

    @SerializedName("sdm.devices.traits.Temperature")
    val temperature: TemperatureTrait?
)

data class HumidityTrait(
    val ambientHumidityPercent: Int
)

data class ThermostatModeTrait(
    val mode: String
)

data class ThermostatEcoTrait(
    val mode: String
)

data class ThermostatTemperatureSetpointTrait(
    val heatCelsius: Double
)

data class TemperatureTrait(
    val ambientTemperatureCelsius: Double
)

/** A simpler version of the device info, converted from the JSON wire format in DeviceInfo */
data class SimpleDeviceInfo(
    val deviceName: String,
    val humidityPercent: Int,
    val thermostatMode: String,
    val ecoMode: Boolean,
    val setPointTemperature: Double,
    val ambientTemperature: Double
)

fun toSimpleDeviceInfo(deviceInfo: DeviceInfo): SimpleDeviceInfo {
    return SimpleDeviceInfo(
        deviceName = deviceInfo.name,
        humidityPercent = deviceInfo.traits.humidity?.ambientHumidityPercent ?: -999,
        thermostatMode = deviceInfo.traits.thermostatMode?.mode ?: "",
        ecoMode = (deviceInfo.traits.thermostatEco?.mode ?: "") == "MANUAL_ECO",
        setPointTemperature = deviceInfo.traits.thermostatTemperatureSetpoint?.heatCelsius ?: -999.0,
        ambientTemperature = deviceInfo.traits.temperature?.ambientTemperatureCelsius ?: -999.0
    )
}
