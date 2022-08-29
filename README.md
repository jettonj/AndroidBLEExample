# Android BLE Requests example

## Getting started

1. Clone this repo
2. Flash the app from [`firmware`](firmware/) directory to an Argon or P2 against at least Device OS 3.3.0 (previous versions didn't include the provisioning mode).
3. Scan the QR code from the device. It will contain two lines of text
    * first line contains the serial number. Take the last six characters and update the [`setupCode`](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L57) constant
    * second line contains the mobile secret. Take it and update the [`mobileSecret`](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L59) constant
4. Make sure you have [Android Studio](https://developer.android.com/studio/install) installed and configured
5. Make sure your Android device [is set up for development](https://developer.android.com/studio/run/device). The app requires at least Android 10 (API version 30)
6. Open this repo in Android Studio and run the app on the device

**Note:** this app uses [custom BLE UUIDs](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L59) meaning it won't work with stock Listening Mode without flashing the firmware above. You can update this app to use the default UUIDs but it's recommended to use unique UUIDs for your products to avoid picking up Particle dev kits when setting up your devices.

## Development

### App logic flow

The app goes through following steps:

1. Sets up and ensures all necessary BLE permissions
1. Looks for devices with `name` or `manufacturerData` ending with [hardcoded `setupCode`](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L57)
1. Connects to the first device found
1. Looks for exposed [service with hardcoded UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L61)
1. Lists its characteristics and ensures that it contains all the necessary ones
    1. Checks if version characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L64) *AND* is readable
    1. Checks if read characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L63) *AND* is notifying
    1. Checks if write characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L62) *AND* is writable
1. Checks if the protocol version equals `2`
1. Send echo request
1. Send scan wifi networks request

### Generated `protobuf` module

The [`firmwareprotos`](firmwareprotos) directory contains generated Java definitions for the [`device-os-protobuf`](https://github.com/particle-iot/device-os-protobuf) declarations. It allows easy encoding/decoding of the request/reply messages.

#### Encoding

```java
import io.particle.firmwareprotos.ctrl.wifi.WifiNew;

void scanWiFiNetworks() {
    WifiNew.ScanNetworksRequest request = WifiNew.ScanNetworksRequest.newBuilder().build();
    sendRequest(request.toByteArray());
}
```

#### Decoding

```java
import io.particle.firmwareprotos.ctrl.wifi.WifiNew;

void receivedScanWiFiNetworksReply(byte[] replyData) {
    WifiNew.ScanNetworksReply reply = WifiNew.ScanNetworksReply.parseFrom(replyData);

    log("Found " + reply.getNetworksCount() + " networks");
    for (WifiNew.ScanNetworksReply.Network network: reply.getNetworksList()) {
        log("  " + network.getSsid() + " [" + network.getBssid() + "] " + network.getRssi() + "dB");
    }
}
```
