package net.surguy.tgeo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest


class AuthRetriever(private val activity: ComponentActivity,
                    projectId: String,
                    private val clientId: String,
                    private val clientSecret: String
) {
    companion object {
        private const val TAG = "net.surguy.tgeo.Auth"
    }

    private val authorizationScope = "https://www.googleapis.com/auth/sdm.service"
    private val redirectUri = "https://android.gorgonops.com/oauth2redirect"
    private val authorizationEndpoint = Uri.parse("https://nestservices.google.com/partnerconnections/${projectId}/auth")
    private val tokenEndpoint = Uri.parse("https://www.googleapis.com/oauth2/v4/token")
    private val authServiceConfiguration: AuthorizationServiceConfiguration = AuthorizationServiceConfiguration(authorizationEndpoint, tokenEndpoint)

    fun retrieveAuth(onResult: (AuthInfo) -> Unit) {
        val authRequestBuilder = AuthorizationRequest.Builder(
            authServiceConfiguration,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri)
        )
            .setScope(authorizationScope)
            .setPrompt("consent")   // Necessary to get a refresh_token back (except for the first time)
            .setAdditionalParameters(mapOf("access_type" to "offline")) // Necessary to have it work at all

        val authRequest = authRequestBuilder.build()
        val clientAuthentication = ClientSecretBasic(clientSecret)

        val authService = AuthorizationService(activity)
        val authResultContract = AuthResultContract(authService)

        val authActivityResultLauncher = activity.registerForActivityResult(authResultContract) { authResponse ->
            if (authResponse!=null) {
                authService.performTokenRequest(
                    authResponse.createTokenExchangeRequest(), clientAuthentication
                ) { tokenResponse, _ ->
                    if (tokenResponse != null) {
                        val accessToken =  tokenResponse.accessToken!!
                        val refreshToken =  tokenResponse.refreshToken!!
                        Log.i(TAG, "Got access token of ${accessToken} and refresh token of ${refreshToken}")

                        onResult(AuthInfo(accessToken, refreshToken))
                        // @todo Might need to enumerate devices here?
                    } else {
                        Log.e(TAG, "Failed to get token response back - response was "+authResponse)
                    }
                }
            } else {
                Log.i(TAG, "No auth response returned")
            }
        }

        authActivityResultLauncher.launch(authRequest)
    }

    fun refreshAccessToken(existingAuth: AuthInfo, onResult: (AuthInfo) -> Unit, onFailure: () -> Unit) {
        val tokenRequest = TokenRequest.Builder(authServiceConfiguration, clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN)
            .setRefreshToken(existingAuth.refreshToken)
            .build()
        val clientAuthentication = ClientSecretBasic(clientSecret)
        Log.i(TAG, "Sending refresh token request "+tokenRequest.jsonSerializeString())
        val authService = AuthorizationService(activity)
        authService.performTokenRequest(tokenRequest, clientAuthentication) { response, exception ->
            if (response != null) {
                val updatedAuth = existingAuth.copy(accessToken = response.accessToken!!)
                onResult(updatedAuth)
            } else {
                Log.e("auth", "Error refreshing access token "+exception?.message)
                onFailure()
            }
        }
    }
}

class AuthResultContract(private val authService: AuthorizationService) : ActivityResultContract<AuthorizationRequest, AuthorizationResponse?>() {
    override fun createIntent(context: Context, input: AuthorizationRequest): Intent {
        return authService.getAuthorizationRequestIntent(input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): AuthorizationResponse? {
        Log.i("authResultContract", "Result code was $resultCode")
        return intent?.let { i -> AuthorizationResponse.fromIntent(i) }
    }
}
