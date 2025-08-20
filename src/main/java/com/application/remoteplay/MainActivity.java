package com.application.remoteplay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;

    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;

    private long lastSendTime = 0;
    private final int MOVE_SEND_INTERVAL_MS = 30;
    private Button playgroundButton;
    private Button bluetoothButton;
    private TextView AppTextView, playground;
    private GestureDetector gestureDetector;

    private final String[] bluetoothPermissions = new String[]{
            android.Manifest.permission.BLUETOOTH,
            android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        bluetoothButton = findViewById(R.id.actmain_button);
        AppTextView = findViewById(R.id.textView);
        playgroundButton = findViewById(R.id.actmain_gotoplay);
        playground = findViewById(R.id.playground);
        gestureDetector = new GestureDetector(this, new GestureHandler());

        playground.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() == 2 &&
                    event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                sendCommand("CLICK:RIGHT\n");  // Two-finger touch = right click
                return true;
            }
            gestureDetector.onTouchEvent(event);
            return true;
        });

        bluetoothButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkBluetoothPermissions();
            }
        });
        playgroundButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    bluetoothSocket.close();
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(MainActivity.this, "Socket Closed", Toast.LENGTH_SHORT).show();
                playgroundButton.setVisibility(View.GONE);
                bluetoothButton.setVisibility(View.VISIBLE);
                AppTextView.setVisibility(View.VISIBLE);
                playground.setVisibility(View.GONE);
            }
        });
    }

    private void sendCommand(String command) {
        if (outputStream == null) return;

        new Thread(() -> {
            try {
                outputStream.write(command.getBytes());
                outputStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
    private class GestureHandler extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            sendCommand("CLICK:LEFT\n");
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            sendCommand("CLICK:RIGHT\n");
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            int dx = (int) -distanceX;
            int dy = (int) -distanceY;
            long now = System.currentTimeMillis();
            if (now - lastSendTime > MOVE_SEND_INTERVAL_MS) {
                sendCommand("MOVE:" + dx + "," + dy + "\n");
                lastSendTime = now;
            }
            return true;
        }
    }

    private void checkBluetoothPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        Boolean someDeniedForever = false;
        for (String perm : bluetoothPermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(perm);
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    someDeniedForever = true;
                }
            }
        }
        if (!neededPermissions.isEmpty()) {
            if (someDeniedForever) {
                showPermissionSettingsDialog();
            } else {
                ActivityCompat.requestPermissions(this,
                        neededPermissions.toArray(new String[0]),
                        REQUEST_BLUETOOTH_PERMISSIONS);
            }
        } else {
            // Permissions already granted – safe to use BluetoothAdapter
            fetchBluetoothConnections();
        }
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Bluetooth permissions are required for this feature. Please enable them in app settings.")
                .setPositiveButton("Go to Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                fetchBluetoothConnections();
            } else {
                Toast.makeText(this, "Bluetooth permissions are required, cannot proceed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void fetchBluetoothConnections() {
        Toast.makeText(MainActivity.this, "Fetching Bluetooth connections", Toast.LENGTH_SHORT).show();
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(MainActivity.this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(MainActivity.this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ActivityCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(MainActivity.this, "Grant Bluetooth Adapter permission", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        if (pairedDevices.size() > 0) {
            ArrayList<BluetoothDevice> desktopDevices = new ArrayList<>();
            for (BluetoothDevice device : pairedDevices) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(MainActivity.this, "Grant Bluetooth permission", Toast.LENGTH_SHORT).show();
                    return;
                }
                String deviceName = device.getName();
                String deviceAddress = device.getAddress(); // MAC address
                desktopDevices.add(device);
                Log.d("BT_DEVICE", "Paired: " + deviceName + " [" + deviceAddress + "]");
            }
            List<String> deviceNameList = new ArrayList<>();
            if (desktopDevices.size() > 0) {
                for (BluetoothDevice device : desktopDevices) {
                    deviceNameList.add(device.getName());
                }
                String[] deviceArray = deviceNameList.toArray(new String[0]);

                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("Select Bluetooth Device");

                builder.setItems(deviceArray, (dialog, which) -> {
                    BluetoothDevice selectedDevice = desktopDevices.get(which);
                    Toast.makeText(this, "Connecting to " + selectedDevice.getName(), Toast.LENGTH_LONG).show();
                    connectToDevice(selectedDevice);
                });
                builder.setCancelable(true);
                builder.show();
            } else {
                Toast.makeText(MainActivity.this, "No paired Desktop devices found", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(MainActivity.this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show();
        }
    }

    private void connectToDevice(BluetoothDevice device) {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

        new Thread(() -> {
            try {
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid);
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                socket.connect();
                OutputStream outputStream = socket.getOutputStream();

                // Store these globally if needed
                this.bluetoothSocket = socket;
                this.outputStream = outputStream;

                runOnUiThread(() -> {
                        Toast.makeText(this, "Connected to " + device.getName(), Toast.LENGTH_SHORT).show();
                        playgroundButton.setVisibility(View.VISIBLE);
                        bluetoothButton.setVisibility(View.GONE);
                        AppTextView.setVisibility(View.GONE);
                        playground.setVisibility(View.VISIBLE);
                    }
                );
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                        Toast.makeText(this, "Connection failed!", Toast.LENGTH_SHORT).show();
                    }
                );
            }
        }).start();
    }
}