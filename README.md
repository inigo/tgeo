Android app to fix the Nest thermostat's tendency to turn on for no reason when away from home.

## Purpose

In theory, Google Home should use presence-sensing to tell whether you are at home or away, and should
turn off your heating when you aren't at home. In practice, this works some of the time, but it sometimes
thinks you are at home when you're definitely not, turns on the heating, and then takes several hours to
realise that you're not around and turn it off again. This can mean that the heating is on for about
a quarter of the time you are away, wasting energy.

While you are away from home (i.e. your phone is more than 5km from what you have set as your home location
in the app), this app will regularly check if the thermostat is in Eco mode, and set it to Eco mode if it is
not. Once you return (i.e. you have been within 4km of home for several minutes) then it will disable Eco mode.
Hence, even if Google presence sensing triggers, the heating will only be on for a very short period
until the app turns it off again.

## Setting up credentials

To build the app successfully requires:

* OAuth 2 client credentials from https://console.cloud.google.com/apis/credentials. At the moment, the code is
  using Web Client credentials. This isn't ideal - because the client secret can be extracted from the app and 
  reused by an attacker - but I couldn't get the Android credentials type to work. On the credentials, you need  
  to set an authorised redirect URI matching the `redirectUri` in `AuthRetriever` (including the full path e.g. 
  `https://example.com/oauth2redirect`). It really does take a long time for changes here to take effect - 
  sometimes more than an hour.
* A Nest project ID - from https://console.nest.google.com/device-access/project-list. The project needs to have 
  the `https://www.googleapis.com/auth/sdm.service` scope, and the OAuth client ID from the Google credentials link above.
* The device ID of the thermostat. This could be worked out directly within the app, but the code doesn't currently 
  do so. To get it manually, you need:
  * Get an auth code: in a browser, go to `https://nestservices.google.com/partnerconnections/[project_id]/auth?redirect_uri=[an_allowed_redirect_uri]&access_type=offline&prompt=consent&client_id=[client_id]&response_type=code&scope=https://www.googleapis.com/auth/sdm.service`
    and follow through the prompts
  * The browser should finally redirect you to your specified redirect_uri, and there will be a "code" parameter in the URL. Copy that code.
  * Use that code to get an access token: `curl -L -X POST 'https://www.googleapis.com/oauth2/v4/token?client_id=[client_id]&client_secret=[client_secret]&code=[the_code]&grant_type=authorization_code&redirect_uri=[redirect_uri]'`
  * This gives a JSON response containing an `access_token`. It will last for an hour.
  * Now use that access token to list devices: `curl -X GET "https://smartdevicemanagement.googleapis.com/v1/enterprises/[project_id]/devices" -H "Content-Type: application/json" -H "Authorization: Bearer [access_token]"`
  * This will provide a JSON list of devices. The thermostat has type `sdm.devices.types.THERMOSTAT`. It has a `name` of `enterprises/[project_id]/devices/[device_id]`
  * Copy the device ID. This doesn't change.

Add these values to a `secrets.properties` file in the root.

    projectId = "..." (from the Nest project - a uuid)
    clientId = "..." (from the OAuth 2 credentials - something like xxxx.apps.googleusercontent.com)
    clientSecret = "..." (from the OAuth 2 credentials - a long random string)
    deviceId = "..." (from the device list - a very long random string)

The `redirectUri` set up in `AuthRetriever` (and referenced as the `android:host` in `AndroidManifest.xml`) must
have a `/.well-known/assetlinks.json` file including an entry for this app. See the example_assetlinks.json
file in this repo. The sha256_cert_fingerprints entry is the fingerprint of the app's signing certificate, 
for me found by `keytool -keystore C:\Users\Inigo\.android\debug.keystore -list -v` and the default password
is `android`. (docs at https://developer.android.com/training/app-links/verify-android-applinks)

Having done this, and built the app, then the first time you use it, it will ask for permission to access your home.
It will ask again subsequently if the refresh token expires.

## License

Copyright (C) 2024 Inigo Surguy. Licensed under the GNU General Public License v3 - see LICENSE.txt for details.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.