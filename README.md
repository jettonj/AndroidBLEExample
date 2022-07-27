# Android BLE Requests example

This repo contains a very basic app that:

1. Sets up and ensures all necessary BLE permissions
1. Looks for devices with `name` or `manufacturerData` ending with [hardcoded `setupCode`](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L50)
1. Connects to the first device found
1. Looks for exposed [service with hardcoded UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L54)
1. Lists its characteristics and ensures that it contains all the necessary ones
    1. Checks if version characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L57) *AND* is readable
    1. Checks if read characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L56) *AND* is notifying
    1. Checks if write characteristic has [matching UUID](https://github.com/particle-iot/AndroidBLEExample/blob/main/app/src/main/java/io/particle/bleexample/MainActivity.java#L55) *AND* is writable
1. Checks if the protocol version equals `2`

**At this moment, the app is not doing the ECJPAKE handshake or the encryption yet.**

### Generated protobuf module

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