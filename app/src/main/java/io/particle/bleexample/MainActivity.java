package io.particle.bleexample;

import static android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import io.particle.device.control.BleRequestChannel;
import io.particle.device.control.BleRequestChannelCallback;
import io.particle.device.control.RequestError;
import io.particle.firmwareprotos.ctrl.wifi.WifiNew;

public class MainActivity extends AppCompatActivity {
    private String logs;

    // Device setup code. By default, 6 characters of the serial number
    private final String setupCode = "HMCS78";
    // Mobile secret available on the QR sticker on the device
    private final String mobileSecret = "U6RWB9YCSHKV5V9";
    // UUIDs defined in the firmware
    private final UUID serviceUUID = UUID.fromString("6e400021-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID txCharUUID = UUID.fromString("6e400022-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID rxCharUUID = UUID.fromString("6e400023-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID versionCharUUID = UUID.fromString("6e400024-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID cccdDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int MAX_PACKET_SIZE = 244;
    // The entire list of control requests can be found here:
    // https://github.com/particle-iot/device-os/blob/develop/system/inc/system_control.h#L41
    private static final int ECHO_REQUEST_TYPE = 1;
    private static final int SCAN_NETWORKS_TYPE = 506;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private ScanCallback scanCallback;
    private ArrayList<BluetoothDevice> foundDevices;
    private BluetoothGattCallback bluetoothGattCallback;

    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic versionCharacteristic;

    private BleRequestChannel requestChannel;
    private BleRequestChannelCallback requestChannelCallback;

    private int bytesLeft = 0;
    private byte[] outgoingData = new byte[0];
    private boolean sending = false;
    private int echoRequestId;
    private int scanWifiRequestId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        foundDevices = new ArrayList<BluetoothDevice>();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.logs = "";
        this.log("Press Connect to start");
        MainActivity self = this;

        this.requestChannelCallback = new BleRequestChannelCallback() {
            @Override
            public void onChannelOpen() {
                self.onRequestChannelOpen();
            }

            @Override
            @RequiresApi(api = 33)
            @SuppressLint("MissingPermission")
            public void onChannelWrite(byte[] data) {
                byte[] buf = new byte[self.bytesLeft + data.length];
                System.arraycopy(self.outgoingData, self.outgoingData.length - self.bytesLeft, buf, 0, self.bytesLeft);
                System.arraycopy(data, 0, buf, self.bytesLeft, data.length);
                self.outgoingData = buf;
                self.bytesLeft += data.length;
                if (!self.sending) {
                    self.sending = true;
                    self.sendChunk();
                }
            }

            @Override
            public void onRequestResponse(int requestId, int result, byte[] data) {
                if (requestId == self.echoRequestId) {
                    self.log("Got echo response: " + new String(data));

                    self.log("Scanning for WiFi networks...");
                    self.scanWifiRequestId = self.requestChannel.sendRequest(self.SCAN_NETWORKS_TYPE);
                }

                if (requestId == self.scanWifiRequestId) {
                    WifiNew.ScanNetworksReply reply = null;
                    try {
                        reply = WifiNew.ScanNetworksReply.parseFrom(data);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }

                    self.log("Found " + reply.getNetworksCount() + " networks");
                    for (WifiNew.ScanNetworksReply.Network network: reply.getNetworksList()) {
                        String ssid = network.getSsid().toString();
                        if (ssid != "") {
                            self.log("  " + ssid + " (" + network.getRssi() + "dB)");
                        }
                    }
                }
            }

            @Override
            public void onRequestError(int requestId, RequestError error) {
                self.log("onRequestError requestId: " + requestId);
            }
        };
        try {
            this.requestChannel = new BleRequestChannel(mobileSecret.getBytes(), requestChannelCallback,
                    BleRequestChannel.DEFAULT_MAX_CONCURRENT_REQUESTS);
        } catch (Exception e) {
            e.printStackTrace();
        }

        scanCallback = new ScanCallback() {
            @RequiresApi(api = Build.VERSION_CODES.R)
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);

                self.addDevice(result);
            }
        };

        bluetoothGattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                super.onConnectionStateChange(gatt, status, newState);

                runOnUiThread(() -> self.onConnectionStateChange(gatt, status, newState));
            }

            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);

                self.bluetoothGatt = gatt;
                runOnUiThread(() -> self.onServicesDiscovered(gatt, status));
            }

            @Override
            public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);

                runOnUiThread(() -> self.onCharacteristicRead(gatt, characteristic, status));
            }

            @Override
            public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);

                runOnUiThread(() -> self.requestChannel.read(characteristic.getValue()));
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicWrite(gatt, characteristic, status);

                if (self.bytesLeft > 0) {
                    runOnUiThread(() -> self.sendChunk());
                } else {
                    self.sending = false;
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    self.log("Failed to update CCCD descriptor");
                    return;
                }
                runOnUiThread(() -> self.openControlChannel());
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!bluetoothAdapter.isEnabled()) {
            this.log("BLE not enabled");
            Intent enableBLEIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                this.log("Not enough permissions to use BLE");
                return;
            }
            startActivityForResult(enableBLEIntent, 1);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1) {
            if (resultCode != Activity.RESULT_OK) {
                this.log("Failed to request the BLE permissions");
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 2) {
            if (!Arrays.asList(grantResults).contains(PackageManager.PERMISSION_DENIED)) {
                this.ensurePermissions();
            } else {
                this.startBleScan();
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        String deviceAddress = gatt.getDevice().getAddress();
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                this.log("Connected to " + deviceAddress + "!");
                gatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                this.log("Disconnected from " + deviceAddress + "!");
            }
        } else {
            this.log("Error " + status + " occurred for " + deviceAddress);
            gatt.close();
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        this.log("Finished discovering services");

        if (gatt.getServices().isEmpty()) {
            this.log("No services found!");
            return;
        }

        gatt.getServices().forEach(bluetoothGattService -> {
            this.log(bluetoothGattService.getUuid() + "\n  Has " + bluetoothGattService.getCharacteristics().size() + " characteristics");

            if (bluetoothGattService.getUuid().equals(serviceUUID)) {
                this.log("Found the communication service!");
                if (this.ensureCharacteristics(bluetoothGattService)) {
                    this.log("Checking the protocol version...");
                    if (!gatt.readCharacteristic(this.versionCharacteristic)) {
                        this.log("Failed to read the version");
                    }
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Integer version = characteristic.getIntValue(FORMAT_UINT8, 0);

            if (version != 2) {
                this.log("Protocol version " + version + " is not supported");
                return;
            }

            if (!gatt.setCharacteristicNotification(this.rxCharacteristic, true)) {
                this.log("Failed to subscribe to incoming data");
                return;
            }

            BluetoothGattDescriptor descriptor = this.rxCharacteristic.getDescriptor(this.cccdDescriptorUUID);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(descriptor)) {
                this.log("Failed to update CCCD descriptor");
                return;
            }
        } else if (status == BluetoothGatt.GATT_READ_NOT_PERMITTED) {
            this.log("Read not permitted for " + characteristic.getUuid());
        } else {
            this.log("Characteristic read failed for " + characteristic.getUuid() + ": " + status);
        }
    }

    public void onRequestChannelOpen() {
        this.log("Request channel opened");
        this.sendEchoRequest();
    }

    public boolean ensureCharacteristics(BluetoothGattService service) {
        this.txCharacteristic = service.getCharacteristic(this.txCharUUID);
        this.rxCharacteristic = service.getCharacteristic(this.rxCharUUID);
        this.versionCharacteristic = service.getCharacteristic(this.versionCharUUID);

        if (this.txCharacteristic == null ||
                (((this.txCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) &&
                ((this.txCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0))) {
            this.log("The write characteristic was not found or is not writable");
            return false;
        }

        if (this.rxCharacteristic == null ||
                ((this.rxCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0)) {
            this.log("The read characteristic was not found or does not notify");
            return false;
        }

        if (this.versionCharacteristic == null ||
                ((this.versionCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0)) {
            this.log("The write characteristic was not found or is not readable");
            return false;
        }

        this.log("All necessary characteristics were found!");
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void onClick(View view) {
        this.log("\nLooking for " + this.setupCode + "...");

        this.startBleScan();
    }

    private boolean hasPermission(String permissionType) {
        return ContextCompat.checkSelfPermission(this, permissionType) == PackageManager.PERMISSION_GRANTED;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean ensurePermissions() {
        boolean isPermissionGranted =
                this.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                this.hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                this.hasPermission(Manifest.permission.BLUETOOTH_CONNECT);

        if (isPermissionGranted) {
            return true;
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    2
            );
            return false;
        }
    }

    private void log(String line) {
        this.logs += line + "\n";
        TextView textView = findViewById(R.id.textView);
        textView.setText(this.logs);
        ScrollView scrollView = findViewById(R.id.scrollView);
        scrollView.fullScroll(View.FOCUS_DOWN);
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.S)
    private void startBleScan() {
        if (!this.ensurePermissions()) {
            return;
        }

        if (this.bluetoothLeScanner == null) {
            this.log("BLE scanner unavailable");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(serviceUUID))
                .build();

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .setReportDelay(0L)
                .build();

        this.bluetoothLeScanner.startScan(
                Arrays.asList(filter),
                scanSettings,
                scanCallback
        );
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void addDevice(ScanResult result) {
        BluetoothDevice device = result.getDevice();

        SparseArray<byte[]> manufacturerDataArray = result.getScanRecord().getManufacturerSpecificData();
        String manufacturerData = "";
        for(int i = 0, size = manufacturerDataArray.size(); i < size; i++) {
            byte[] manufacturerDataBytes = manufacturerDataArray.valueAt(i);
            // Concat all data
            manufacturerData += new String(manufacturerDataBytes, StandardCharsets.UTF_8);
        }
        String deviceName = result.getScanRecord().getDeviceName();
        if (!deviceName.endsWith(this.setupCode) && !manufacturerData.endsWith(this.setupCode)) {
            this.log("Found " + deviceName + " but it's not matching the setup code");
            return;
        }

        if (!this.foundDevices.contains(device)) {
            this.foundDevices.add(device);
            this.log("Found " + deviceName + " [" + device.getAddress() + "]");

            // We're connecting to the first found device, no need to scan further
            this.bluetoothLeScanner.stopScan(this.scanCallback);
            this.connect(device);
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void connect(BluetoothDevice device) {
        this.log("Connecting to " + device.getAlias() + "...");
        device.connectGatt(getApplicationContext(), false, this.bluetoothGattCallback);
    }

    private void openControlChannel() {
        this.log("Ready to open the control channel");
        try {
            this.requestChannel.open();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    private void sendChunk() {
        if (this.bluetoothGatt != null && this.txCharacteristic != null && this.bytesLeft > 0) {
            this.txCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

            int bytesToSend = bytesLeft > this.MAX_PACKET_SIZE ? this.MAX_PACKET_SIZE : bytesLeft;
            byte[] chunk = new byte[bytesToSend];
            System.arraycopy(this.outgoingData, this.outgoingData.length - bytesLeft, chunk, 0, bytesToSend);

            this.txCharacteristic.setValue(chunk);
            this.bluetoothGatt.writeCharacteristic(this.txCharacteristic);
            bytesLeft -= bytesToSend;
        }
    }

    private void sendEchoRequest() {
        this.log("Sending echo with: HELLO");
        this.echoRequestId = this.requestChannel.sendRequest(this.ECHO_REQUEST_TYPE, "HELLO".getBytes());
    }
}