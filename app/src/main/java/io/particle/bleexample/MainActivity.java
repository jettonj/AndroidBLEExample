package io.particle.bleexample;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
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
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.StrictMode;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.protobuf.InvalidProtocolBufferException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import io.particle.device.control.BleRequestChannel;
import io.particle.device.control.BleRequestChannelCallback;
import io.particle.device.control.RequestError;
import io.particle.firmwareprotos.ctrl.cloud.Cloud;
import io.particle.firmwareprotos.ctrl.wifi.WifiNew;

public class MainActivity extends AppCompatActivity {
    private String logs;

    // Device setup code. By default, 6 characters of the serial number
    private final String setupCode = "Ori";
    // Mobile secret available on the QR sticker on the device
    private final String mobileSecret = "BIGROBOTSSECRET";
    // UUIDs defined in the firmware
    private final UUID serviceUUID = UUID.fromString("6e400021-b5a3-f393-e0a9-cbedcbedcbed");
    private final UUID txCharUUID = UUID.fromString("6e400022-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID rxCharUUID = UUID.fromString("6e400023-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID versionCharUUID = UUID.fromString("6e400024-b5a3-f393-e0a9-e50e24dcca9e");
    private final UUID cccdDescriptorUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Ideally this value should be determined with requestMtu():
    // https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int)
    private static final int MAX_PACKET_SIZE = 244;
    // The entire list of control requests can be found here:
    // https://github.com/particle-iot/device-os/blob/develop/system/inc/system_control.h#L41
    private static final int ECHO_REQUEST_TYPE = 1;
    private static final int JOIN_NEW_NETWORK_TYPE = 500;
    private static final int CLEAR_KNOWN_NETWORKS_TYPE = 504;
    private static final int GET_CURRENT_NETWORK_TYPE = 505;
    private static final int SCAN_NETWORKS_TYPE = 506;
    private static final int CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE = 300;



    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private ScanCallback scanCallback;
    private ArrayList<ScanResult> foundScanResults;
    private ArrayList<BluetoothDevice> shadowBTDeviceList;
    private BluetoothDevice lastConnectedDevice;
    private String lastConnectedOriName;
    private BluetoothGattCallback bluetoothGattCallback;

    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic versionCharacteristic;

    private BleRequestChannel requestChannel;
    private BleRequestChannelCallback requestChannelCallback;

    private ArrayList<WifiNew.ScanNetworksReply.Network> wifiNetworksList = new ArrayList<>();

    private int bytesLeft = 0;
    private byte[] outgoingData = new byte[0];
    private boolean sending = false;
    private int echoRequestId;
    private int scanWifiRequestId;
    private int clearKnownWifiRequestId;
    private int wifiJoinNewNetworkRequestId;
    private int getCurrentNetworkId;
    private int getCloudConnectedStatusId;
    private int lastRequestId;
    private boolean bleInitFlag = false;
    private boolean scanning = false;
    private int currentScanDelay = 2000; //plan to increment this when a user requests to rescan ("I don't see my system, rescan")
    private final int scanDelayInc = 1000;
    private final int maxScanDelay = 5000;
    private boolean clearKnownNetworksEnable = false;
    private boolean sendEchoRequestEnable = true;
    private boolean resendRequestEnable = true;
    private SwitchCompat clrnetSwitch;
    private ProgressBar progressBar;
    private TextView statusTextView;
    private int progressStatus = 0;
    private Blereq blereq;
    class Blereq
    {
        public int lastRequestId;
        public int requestIdType;
        public byte[] lastRequestData;
        private boolean echoRequestIsComplete = false;
        private boolean scanWifiRequestIsComplete = false;
        private boolean wifiJoinNewNetworkRequestIsComplete = false;
        private boolean wifiGetCurrentNetworkIsComplete = false;
        private boolean wifiClearKnownWifiRequestIsComplete = false;
        private boolean getCloudConnectedStatusIsComplete = false;


        public void setLastRequestInfo(int id, int idType, byte[] data) {
            this.lastRequestId = id;
            this.requestIdType = idType;
            this.lastRequestData = data;
        }

        public boolean getResponseReceived(int requestId) {
            boolean retVal = false;

            if (requestId == echoRequestId) {
                retVal = echoRequestIsComplete;
            } else if (requestId == scanWifiRequestId) {
                retVal = scanWifiRequestIsComplete;
            } else if (requestId == wifiJoinNewNetworkRequestId) {
                retVal = wifiJoinNewNetworkRequestIsComplete;
            } else if (requestId == getCurrentNetworkId) {
                retVal = wifiGetCurrentNetworkIsComplete;
            } else if (requestId == clearKnownWifiRequestId) {
                retVal = wifiClearKnownWifiRequestIsComplete;
            } else if (requestId == getCloudConnectedStatusId) {
                retVal = getCloudConnectedStatusIsComplete;
            }
            return retVal;
        }
        public void setResponseReceived(int requestId) {
            if (requestId == echoRequestId) {
                echoRequestIsComplete = true;
            } else if (requestId == scanWifiRequestId) {
                scanWifiRequestIsComplete = true;
            } else if (requestId == wifiJoinNewNetworkRequestId) {
                wifiJoinNewNetworkRequestIsComplete = true;
            } else if (requestId == getCurrentNetworkId) {
                wifiGetCurrentNetworkIsComplete = true;
            } else if (requestId == clearKnownWifiRequestId) {
                wifiClearKnownWifiRequestIsComplete = true;
            } else if (requestId == getCloudConnectedStatusId) {
                getCloudConnectedStatusIsComplete = true;
            }
        }
        public void setLastResponseReceived() {
            if (this.lastRequestId == echoRequestId) {
                echoRequestIsComplete = true;
            } else if (this.lastRequestId == scanWifiRequestId) {
                scanWifiRequestIsComplete = true;
            } else if (this.lastRequestId == wifiJoinNewNetworkRequestId) {
                wifiJoinNewNetworkRequestIsComplete = true;
            } else if (this.lastRequestId == getCurrentNetworkId) {
                wifiGetCurrentNetworkIsComplete = true;
            } else if (this.lastRequestId == clearKnownWifiRequestId) {
                wifiClearKnownWifiRequestIsComplete = true;
            } else if (this.lastRequestId == getCloudConnectedStatusId) {
                getCloudConnectedStatusIsComplete = true;
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void showWifiNetworksDialog() {
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Select a WiFi network");

            ArrayList<String> listing = new ArrayList<>();

            for (WifiNew.ScanNetworksReply.Network network: wifiNetworksList)
            {
                listing.add(network.getSsid() + " (" + network.getRssi() + " dBm)");
            }

            // Convert ArrayList to CharSequence array for use in AlertDialog
            CharSequence[] wifiNetworksArray = listing.toArray(new CharSequence[listing.size()]);

            builder.setItems(wifiNetworksArray, (dialog, which) -> {
                WifiNew.ScanNetworksReply.Network selectedNetwork = wifiNetworksList.get(which);
                // Prompt for the WiFi password
                promptForWifiPassword(selectedNetwork);
            });

            AlertDialog dialog = builder.create();
            dialog.show();
        });
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void promptForWifiPassword(final WifiNew.ScanNetworksReply.Network selectedNetwork) {
        // Create an EditText to input the password
        final EditText passwordInput = new EditText(MainActivity.this);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); //todo change to TYPE_TEXT_VARIATION_PASSWORD if we don't want to see it
        passwordInput.setHint("Password");

        // todo use a layout with app:endIconMode="password_toggle" or something instead of an alert dialog
        // If the network has no security, connect to it without a password prompt
        if (selectedNetwork.getSecurity() == WifiNew.Security.NO_SECURITY) {
            log("Connecting to " + selectedNetwork.getSsid() + " with no password ");
            connectToNetwork(selectedNetwork, "");
        } else {
            // Create a dialog to enter the password
            final AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Enter Password for " + selectedNetwork.getSsid())
                    .setView(passwordInput)
                    .setPositiveButton("Connect", (dialog, whichButton) -> {
                        String password = passwordInput.getText().toString();
                        log("Connecting to " + selectedNetwork.getSsid() + " with password " + password); //todo may want to get rid of logging plain password here
                        // Connect to the selected network with the provided password
                        connectToNetwork(selectedNetwork, password);
                    })
                    .setNegativeButton("Cancel", (dialog, whichButton) -> {
                        dialog.dismiss();
                    })

                    .show();

            final Button connectButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            connectButton.setEnabled(false);
            passwordInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                    String password = passwordInput.getText().toString();
                    // Allow password to be sent if it is at least 8 characters long
                    if (password.length() >= 8) {
                        connectButton.setEnabled(true);
                    } else {
                        connectButton.setEnabled(false);
                    }
                }

                @Override
                public void afterTextChanged(Editable editable) {

                }
            });
        }
    }

    // Connect to the selected WiFi network
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void connectToNetwork(@NonNull WifiNew.ScanNetworksReply.Network netw, String password) {

        WifiNew.Credentials creds;
        WifiNew.JoinNewNetworkRequest newNetworkRequest;

        if (netw.getSecurity() == WifiNew.Security.NO_SECURITY) {
            creds = WifiNew.Credentials.newBuilder()
                    .setType(WifiNew.CredentialsType.NO_CREDENTIALS)
                    .build();
        } else {
            creds = WifiNew.Credentials.newBuilder()
                    .setType(WifiNew.CredentialsType.PASSWORD)
                    .setPassword(password)
                    .build();
        }
        newNetworkRequest = WifiNew.JoinNewNetworkRequest.newBuilder()
                .setSsid(netw.getSsid())
                .setSecurity(netw.getSecurity())
                .setCredentials(creds)
                .build();

        this.log("Security type: " + netw.getSecurity());
        createWifiJoinNewNetworkRequest(newNetworkRequest.toByteArray());
    }

    // Listener for switch change
    private void setSwitchListeners() {
        clrnetSwitch = findViewById(R.id.clearNetworksSwitch);
        clrnetSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.clearKnownNetworksEnable = isChecked;
        });
    }

    private void updateProgress(int progress, String status) {
        progressBar = findViewById(R.id.progressBar);
        statusTextView = findViewById(R.id.statusTextView);
        //I can get progress from last requestIdType?
        //this.blereq.requestIdType
        progressBar.setProgress(progress, true);
        statusTextView.setText(status);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build());

        this.setSwitchListeners();

        this.logs = "";
        this.log("Press Connect to start");
        MainActivity self = this;

    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!bleInitFlag) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                initBluetooth();
            }
        }

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

    @RequiresApi(api = Build.VERSION_CODES.R)
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
    public void onConnectionStateChange_cb(BluetoothGatt gatt, int status, int newState) {
        String deviceAddress = gatt.getDevice().getAddress();
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                this.log("Connected to " + deviceAddress + "!");
                updateProgress(10,"Connected to " + lastConnectedOriName);
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
    public void onServicesDiscovered_cb(BluetoothGatt gatt, int status) {
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
    public void onCharacteristicRead_cb(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {

            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

            Integer version = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
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

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    public void onRequestChannelOpen() {
        this.log("Control channel opened");
        updateProgress(30,"Handshake Complete");
        if(sendEchoRequestEnable) {
            this.sendEchoRequest();
        } else if (clearKnownNetworksEnable){
            this.createClearKnownNetworksRequest(null);
        } else {
            this.createScanNetworksRequest(null);
        }
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

    //Update clearKnownNetworksEnable based on the switch state
    private void updateClearKnownNetworksEnable() {
        clrnetSwitch = findViewById(R.id.clearNetworksSwitch);
        if (this.clrnetSwitch.isChecked()) {
            this.clearKnownNetworksEnable = true;
        } else {
            this.clearKnownNetworksEnable = false;
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void onClick(View view) {
        this.log("\nLooking for " + this.setupCode + "...");
        this.updateProgress(0,"Scanning for Ori systems");

        this.updateClearKnownNetworksEnable();

        this.initBluetooth();

        this.startBleScan();
    }

    private boolean hasPermission(String permissionType) {
        return ContextCompat.checkSelfPermission(this, permissionType) == PackageManager.PERMISSION_GRANTED;
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private boolean ensurePermissions() {
        boolean isPermissionGranted = this.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        // Check for API 31 permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isPermissionGranted &= this.hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                    this.hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (isPermissionGranted) {
            return true;
        } else {
            String[] permissions = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ?
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    }
                    :
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    };
            ActivityCompat.requestPermissions(
                    this,
                    permissions,
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
        System.out.println(line);
        scrollView.fullScroll(View.FOCUS_DOWN);
        //scrollView.post(() -> scrollView.scrollTo(0, scrollView.getHeight()));

    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void setupCallbacksAndListeners() {

        if (blereq != null) {
            //Set current response as received so retry function doesn't try to call it after the object has been reset
            blereq.setLastResponseReceived();
        }
        blereq = new Blereq();

        requestChannelCallback = new BleRequestChannelCallback() {
            @Override
            public void onChannelOpen() {
                onRequestChannelOpen();
            }

            @Override
            public void onChannelWrite(byte[] data) {
                //System.out.println("w>" + BleRequestChannel.bytesToHex(data));
                byte[] buf = new byte[bytesLeft + data.length];
                System.arraycopy(outgoingData, outgoingData.length - bytesLeft, buf, 0, bytesLeft);
                System.arraycopy(data, 0, buf, bytesLeft, data.length);
                outgoingData = buf;
                bytesLeft += data.length;
                if (!sending) {
                    sending = true;
                    sendChunk();
                }
            }

            @SuppressLint("MissingPermission")
            @Override
            public void onRequestResponse(int requestId, int result, byte[] data) {
                log("onRequestResponse(requestId:" + requestId + ", result:" + result + ")");

                if (requestId == echoRequestId) {
                    log("Got echo response: " + new String(data));
                    blereq.setResponseReceived(echoRequestId);
                    updateProgress(40,"Echo response received");

                    //if checkbox for clearing networks is true, otherwise do scan for wifi networks stuff
                    if (clearKnownNetworksEnable) {
                        createClearKnownNetworksRequest(null);
                    } else {
                        createScanNetworksRequest(null);
                    }
                }

                if (requestId == clearKnownWifiRequestId) {
                    blereq.setResponseReceived(clearKnownWifiRequestId);

                    WifiNew.ClearKnownNetworksReply reply = null;
                    try {
                        reply = WifiNew.ClearKnownNetworksReply.parseFrom(data);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }

                    if (reply != null) {
                        log("ClearKnownNetworksReply: " + reply);
                    } else {
                        log("ClearKnownNetworksReply error?");
                        //todo leave?
                    }

                    createScanNetworksRequest(null);
                }

                if (requestId == scanWifiRequestId) {
                    blereq.setResponseReceived(scanWifiRequestId);
                    WifiNew.ScanNetworksReply reply = null;
                    try {
                        reply = WifiNew.ScanNetworksReply.parseFrom(data);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }

                    wifiNetworksList.clear(); // Clear previous scan results

                    if (result != 0) {
                        log("ScanNetworksReply error ");
                        //todo leave?
                    } else {
                        log("Found " + Objects.requireNonNull(reply).getNetworksCount() + " total networks");

                        for (WifiNew.ScanNetworksReply.Network network: reply.getNetworksList()) {
                            String ssid = network.getSsid();
                            if (!ssid.equals("")) {
                                log("  " + ssid + " (" + network.getBssid() + ") (" + network.getRssi() + " dBm)");
                                wifiNetworksList.add(network); // Add to list for displaying in dialog
                            }
                        }
                        log("...of which " + wifiNetworksList.size() + " have ssids");

                        // Call method to show dialog with WiFi networks
                        // Also calls createWifiJoinNewNetworkRequest()
                        if (!wifiNetworksList.isEmpty()) {
                            showWifiNetworksDialog();
                        } else {
                            updateProgress(45, "Your Ori system was unable to find any wifi networks.\nPower cycle the system and try again.");
                        }
                    }
                }

                if (requestId == wifiJoinNewNetworkRequestId) {
                    blereq.setResponseReceived(wifiJoinNewNetworkRequestId);
                    if(result == 0) {
                        createGetCurrentNetworkRequest(null);
                    } else {
                        log("Error sending network to P2 (" + lastConnectedDevice.getName() + " " + lastConnectedDevice.getAddress() + "), please try again");
                    }
                }

                if (requestId == getCurrentNetworkId) {
                    blereq.setResponseReceived(getCurrentNetworkId);

                    WifiNew.GetCurrentNetworkReply reply = null;
                    try {
                        reply = WifiNew.GetCurrentNetworkReply.parseFrom(data);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }

                    if(!reply.getSsid().equals("")) {
                        log("P2 successfully connected to " + reply.getSsid());
                    } else {
                        log("Error reading network from P2 (" + lastConnectedDevice.getName() + " " + lastConnectedDevice.getAddress() + "), please try again");
                    }

                    updateProgress(85,"Getting cloud connection state...");
                    createGetCloudConnectionStatusRequest(null);

                }

                if (requestId == getCloudConnectedStatusId) {
                    if(!resendRequestEnable) {
                        blereq.setResponseReceived(getCloudConnectedStatusId);
                    }

                    Cloud.GetConnectionStatusReply reply = null;

                    try {
                        reply = Cloud.GetConnectionStatusReply.parseFrom(data);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }

                    switch(Objects.requireNonNull(reply).getStatus()) {
                        case CONNECTED:
                            if(resendRequestEnable) {
                                // Only set when connected, so that the retry can do it
                                blereq.setResponseReceived(getCloudConnectedStatusId);
                            }
                            updateProgress(100,"Your Ori system is connected!");
                            log("P2 successfully connected to the Particle Cloud!");
                            log("\nFinished. Disconnecting from " + lastConnectedDevice.getAddress());

                            requestChannel.close();
                            bluetoothGatt.disconnect();
                            bluetoothGatt.close();
                            break;
                        case CONNECTING:
                            log("P2 still trying to connect to the Particle Cloud...");
                            updateProgress(90,"Ori system is connecting to the cloud...");
                            //todo handle a timeout or number of retries for cloud connection
                            if(!resendRequestEnable) {
                                // Resend a request in 2 seconds
                                new Handler().postDelayed(() ->
                                        createGetCloudConnectionStatusRequest(null), 2000);
                            }
                            break;
                        default:
                            log("P2 isn't connecting yet (state " + reply.getStatus() + ")");
                            if(!resendRequestEnable) {
                                // Resend a request in 2 seconds
                                new Handler().postDelayed(() ->
                                        createGetCloudConnectionStatusRequest(null), 2000);
                            }
                    }
                }
            }

            @Override
            public void onRequestError(int requestId, RequestError error) {
                log("onRequestError requestId: " + requestId);
                log(error.getMessage());
                log(String.valueOf(error.getCause()));
            }
        };

        getNewControlChannel();

        scanCallback = new ScanCallback() {
            @RequiresApi(api = Build.VERSION_CODES.R)
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                //log("onScanResult() received result: " + result.toString());
                addDevice(result);
            }
            @RequiresApi(api = Build.VERSION_CODES.R)
            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                log("onScanFailed() received: "+ errorCode);
            }
        };

        bluetoothGattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                super.onConnectionStateChange(gatt, status, newState);
                System.out.println("onConnectionStateChange");
                runOnUiThread(() -> onConnectionStateChange_cb(gatt, status, newState));
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);
                System.out.println("onServicesDiscovered");
                bluetoothGatt = gatt;
                runOnUiThread(() -> onServicesDiscovered_cb(gatt, status));
            }

            @Override
            public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);
                System.out.println("onCharacteristicRead");
                runOnUiThread(() -> onCharacteristicRead_cb(gatt, characteristic, status));
            }

            @Override
            public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);
                System.out.println("onCharacteristicChanged: " + characteristic.getUuid() + "\n> " + BleRequestChannel.bytesToHex(characteristic.getValue()));

                byte[] characteristicValue = characteristic.getValue();
                runOnUiThread(() -> requestChannel.read(characteristicValue));
                //requestChannel.read(characteristic.getValue());
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicWrite(gatt, characteristic, status);
                System.out.println("onCharacteristicWrite");

                if (bytesLeft > 0) {
                    runOnUiThread(() -> sendChunk());
                } else {
                    sending = false;
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);
                System.out.println("onDescriptorWrite");
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("Failed to update CCCD descriptor");
                    return;
                }
                runOnUiThread(() -> openControlChannel());
            }
        };

    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void createGetCurrentNetworkRequest(byte[] data) {
        this.log("Requesting current network from P2...");
        this.getCurrentNetworkId = this.requestChannel.sendRequest(GET_CURRENT_NETWORK_TYPE);
        startRequestTimer(this.getCurrentNetworkId, GET_CURRENT_NETWORK_TYPE, data);
        blereq.setLastRequestInfo(this.getCurrentNetworkId, GET_CURRENT_NETWORK_TYPE, data);
        this.log("GET_CURRENT_NETWORK_TYPE request is " + this.getCurrentNetworkId);
    }
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void createClearKnownNetworksRequest(byte[] data) {
        log("Clearing known wifi networks...");
        updateProgress(40,"Sending clear networks request...");
        clearKnownWifiRequestId = requestChannel.sendRequest(CLEAR_KNOWN_NETWORKS_TYPE);
        blereq.setLastRequestInfo(clearKnownWifiRequestId, CLEAR_KNOWN_NETWORKS_TYPE, data);
        startRequestTimer(clearKnownWifiRequestId, CLEAR_KNOWN_NETWORKS_TYPE, data);
        log("CLEAR_KNOWN_NETWORKS request is " + clearKnownWifiRequestId);
    }
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void createScanNetworksRequest(byte[] data) {
        log("Scanning for WiFi networks...");
        updateProgress(45,"Sending scan networks request...");
        scanWifiRequestId = requestChannel.sendRequest(SCAN_NETWORKS_TYPE);
        blereq.setLastRequestInfo(scanWifiRequestId, SCAN_NETWORKS_TYPE, data);
        startRequestTimer(scanWifiRequestId, SCAN_NETWORKS_TYPE, data);
        log("SCAN_NETWORKS_TYPE request is " + scanWifiRequestId);
    }
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void createWifiJoinNewNetworkRequest(byte[] data) {
        log("Sending network and password to P2...");
        updateProgress(75,"Sending join network request...");
        wifiJoinNewNetworkRequestId = requestChannel.sendRequest(JOIN_NEW_NETWORK_TYPE, data);
        startRequestTimer(wifiJoinNewNetworkRequestId, JOIN_NEW_NETWORK_TYPE, data);
        blereq.setLastRequestInfo(wifiJoinNewNetworkRequestId, JOIN_NEW_NETWORK_TYPE, data);
        this.log("JOIN_NEW_NETWORK_TYPE request is " + this.wifiJoinNewNetworkRequestId);
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void createGetCloudConnectionStatusRequest(byte[] data) {
        log("Getting cloud connection state...");

        getCloudConnectedStatusId = requestChannel.sendRequest(CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE, data);
        startRequestTimer(getCloudConnectedStatusId, CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE, data);
        blereq.setLastRequestInfo(getCloudConnectedStatusId, CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE, data);
        this.log("CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE request is " + this.getCloudConnectedStatusId);
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void resendRequest(int requestId, byte[] data) {

        // resending doesn't currently work, return
        if (resendRequestEnable) {
            // Logic to resend the request
            log("Cancelling requestId " + requestId);
            requestChannel.cancelRequest(requestId);
            //log("Timeout for request " + requestId + ", opening a new request channel");
            //requestChannel.close();
            //getNewControlChannel();
            //openControlChannel();
            //connect(lastConnectedDevice);
            //log("Timeout for request " + requestId + ", Soft reset");
            //requestChannel.softReset();

            if (requestId == echoRequestId) {
                log("RESENDING echo request: " + new String(data));
                sendEchoRequest();
            }

            if (requestId == scanWifiRequestId) {
                log("RESENDING scanWifiRequestId request: ");
                createScanNetworksRequest(data);
            }

            if (requestId == clearKnownWifiRequestId) {
                log("RESENDING clearKnownWifiRequestId request: ");
                createClearKnownNetworksRequest(data);
            }

            if (requestId == wifiJoinNewNetworkRequestId) {
                log("RESENDING wifiJoinNewNetworkRequestId request: " + new String(data));
                createWifiJoinNewNetworkRequest(data);
            }

            if (requestId == getCurrentNetworkId) {
                log("RESENDING getCurrentNetworkId request: ");
                createGetCurrentNetworkRequest(data);
            }

            if (requestId == getCloudConnectedStatusId) {
                log("RESENDING getCloudConnectedStatusId request: ");
                createGetCloudConnectionStatusRequest(data);
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void startRequestTimer(int requestId, int requestType, byte[] data) {
        Handler requestTimerHandler = new Handler();
        int reqTimeout = 0;
        Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!blereq.getResponseReceived(requestId)) {
                    resendRequest(requestId, data);
                } else {

                }
            }
        };

        switch (requestType) {
            case ECHO_REQUEST_TYPE:
            {
                reqTimeout = 5000;
                break;
            }
            case JOIN_NEW_NETWORK_TYPE:
            {
                reqTimeout = 20000;
                break;
            }
            case SCAN_NETWORKS_TYPE:
            {
                reqTimeout = 10000;
                break;
            }
            case CLEAR_KNOWN_NETWORKS_TYPE:
            {
                reqTimeout = 5000;
                break;
            }
            case CTRL_REQUEST_CLOUD_GET_CONNECTION_STATUS_TYPE:
            {
                reqTimeout = 2000;
                break;
            }
            default:
            {
                reqTimeout = 5000;
                break;
            }
        }

        requestTimerHandler.postDelayed(timeoutRunnable, reqTimeout);
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void initBluetooth() {
        if (!bleInitFlag) {

            bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        } else {
            if (requestChannel.cancelRequest(blereq.lastRequestId)) {
                log("Cancelled reqId: " + blereq.lastRequestId);
            } else {
                //log("Nothing for cancelRequest()");
            }
            if (requestChannel.state() != BleRequestChannel.State.CLOSED) {
                requestChannel.close();
            }
            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            }
        }
        foundScanResults = new ArrayList<ScanResult>();
        shadowBTDeviceList = new ArrayList<BluetoothDevice>();
        // Setup the callbacks and other initialization logic here
        setupCallbacksAndListeners();

        bleInitFlag = true;

    }
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
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
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .setReportDelay(0L)
                .build();

        if (!scanning) {
            // Stops scanning after a pre-defined scan period.
            new Handler().postDelayed(() -> {
                this.scanning = false;
                bluetoothLeScanner.stopScan(scanCallback);

                if(foundScanResults.isEmpty()) {
                    if (currentScanDelay + scanDelayInc < maxScanDelay) {
                        currentScanDelay += scanDelayInc;
                    } else {
                        currentScanDelay = maxScanDelay;
                    }
                    log("BLE scanning timed out, no devices found, rescanning for " + currentScanDelay + " ms");
                    startBleScan();
                } else {
                    showDeviceSelectionDialog(); // Show the dialog here
                }

            }, currentScanDelay);

            scanning = true;
            this.bluetoothLeScanner.startScan(
                    Arrays.asList(filter),
                    scanSettings,
                    scanCallback
            );
        } else {
            scanning = false;
            this.bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    // Gets the system's nice name from the 6 character Ble DeviceName
    private String getOriDeviceName(String fullBleDeviceName) {
        if(fullBleDeviceName.length() != 6) {
            return "System"; // This should never happen
        }

        String oriSystemSubstr = fullBleDeviceName.substring(fullBleDeviceName.length() - 3);
        String oriSystemStr;

        switch (oriSystemSubstr) {
            case "001":
            {
                oriSystemStr = "Pocket Studio";
                break;
            }
            case "002":
            {
                oriSystemStr = "Pocket Closet";
                break;
            }
            case "003":
            {
                oriSystemStr = "Pocket Office";
                break;
            }
            case "004":
            {
                oriSystemStr = "Cloud Bed";
                break;
            }
            case "005":
            {
                oriSystemStr = "Cloud Bed Table";
                break;
            }
            default:
            {
                oriSystemStr = "System";
            }
        }

        return oriSystemStr;
    }
    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void showDeviceSelectionDialog() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select an Ori System");

        String[] deviceNames = new String[foundScanResults.size()];

        //sort based on rssi, higher is closer
        Collections.sort(foundScanResults, Comparator.comparingInt(ScanResult::getRssi).reversed());

        for (int i = 0; i < foundScanResults.size(); i++) {
            try {
                Objects.requireNonNull(foundScanResults.get(i).getScanRecord());
                Objects.requireNonNull(foundScanResults.get(i).getScanRecord().getDeviceName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            deviceNames[i] = "Ori " + getOriDeviceName(foundScanResults.get(i).getScanRecord().getDeviceName()) + " - " + foundScanResults.get(i).getDevice().getAddress() + " [" + foundScanResults.get(i).getRssi() + " dBm]";
        }

        builder.setItems(deviceNames, (dialog, which) -> {
            lastConnectedDevice = foundScanResults.get(which).getDevice();
            lastConnectedOriName = "Ori " + getOriDeviceName(foundScanResults.get(which).getScanRecord().getDeviceName());
            connect(lastConnectedDevice);
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void addDevice(ScanResult result) {
        try {
            // do i need these first two if the last two are subsets of them?
            Objects.requireNonNull(result);
            Objects.requireNonNull(result.getScanRecord());
            Objects.requireNonNull(result.getScanRecord().getManufacturerSpecificData());
            Objects.requireNonNull(result.getScanRecord().getDeviceName());

            BluetoothDevice device = result.getDevice();
            //System.out.println("addDevice() called: " + device + " result " + result);
            //System.out.println("device.getname " + device.getName() + " scanrecord.getdevicename " + result.getScanRecord().getDeviceName());

            SparseArray<byte[]> manufacturerDataArray = result.getScanRecord().getManufacturerSpecificData();
            String manufacturerData = "";
            for (int i = 0, size = manufacturerDataArray.size(); i < size; i++) {
                byte[] manufacturerDataBytes = manufacturerDataArray.valueAt(i);
                // Concat all data
                manufacturerData += new String(manufacturerDataBytes, StandardCharsets.UTF_8);
            }
            String deviceName = result.getScanRecord().getDeviceName();

            if (deviceName.contains(this.setupCode) && !this.shadowBTDeviceList.contains(device)) {
                this.shadowBTDeviceList.add(device);
                this.foundScanResults.add(result);
                this.log("Found " + deviceName + " - [" + device.getAddress() + "] - [" + result.getRssi() + " dBm]");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void connect(BluetoothDevice device) {
        this.log("Connecting to " + device.getAlias() + "...");
        updateProgress(5,"Connecting to " + lastConnectedOriName + "...");
        device.connectGatt(getApplicationContext(), false, this.bluetoothGattCallback);
    }

    private void openControlChannel() {
        if (this.requestChannel.state() == BleRequestChannel.State.NEW) {
            this.log("Ready to open the control channel");
            updateProgress(15,"Beginning handshake...");
            try {
                this.requestChannel.open();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            this.log("Control channel already opened, skipping");
        }
    }

    private void getNewControlChannel() {
        if(requestChannel != null) {
            log("Closing active control channel");
            requestChannel.close();
        }
        try {
            log("Trying to open a new control channel");
            requestChannel = new BleRequestChannel(mobileSecret.getBytes(), requestChannelCallback,
                    BleRequestChannel.DEFAULT_MAX_CONCURRENT_REQUESTS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @SuppressLint("MissingPermission")
    private void sendChunk() {
        if (this.bluetoothGatt != null && this.txCharacteristic != null && this.bytesLeft > 0) {
            this.txCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

            int bytesToSend = Math.min(bytesLeft, MAX_PACKET_SIZE);
            byte[] chunk = new byte[bytesToSend];
            System.arraycopy(this.outgoingData, this.outgoingData.length - bytesLeft, chunk, 0, bytesToSend);

            this.txCharacteristic.setValue(chunk);
            this.bluetoothGatt.writeCharacteristic(this.txCharacteristic);
            bytesLeft -= bytesToSend;
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void sendEchoRequest() {
        String echoStr = "I thought not. It's not a story the Jedi would tell you. It's a Sith legend. " +
                "Darth Plagueis was a Dark Lord of the Sith, so powerful and so wise he could use the Force " +
                "to influence the midichlorians to create life… He had such a knowledge of the dark side, " +
                "he could even keep the ones he cared about from dying.";
        this.log("Sending echo with: " + echoStr.length() + " bytes");
        this.echoRequestId = this.requestChannel.sendRequest(ECHO_REQUEST_TYPE, echoStr.getBytes());
        startRequestTimer(this.echoRequestId, ECHO_REQUEST_TYPE, echoStr.getBytes());
        blereq.setLastRequestInfo(lastRequestId, ECHO_REQUEST_TYPE, echoStr.getBytes());

        this.log("ECHO_REQUEST_TYPE request is " + this.echoRequestId);
        updateProgress(35,"Sending echo request...");

    }
}

