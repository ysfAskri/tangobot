/*
 * Copyright 2015 Ekumen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ekumen.tangobot.application;


import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Toast;

import com.rosjava.tangoxros.TangoInitializationHelper;
import com.rosjava.tangoxros.TangoInitializationHelper.DefaultServiceConnection.AfterConnectionCallback;
import com.rosjava.tangoxros.TangoRosNode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ros.android.RosActivity;
import org.ros.node.ConnectedNode;
import org.ros.node.NativeNodeMainBeta;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static android.content.ContentValues.TAG;

public class MainActivity extends RosActivity implements TangoRosNode.CallbackListener {
    private static final String ACTION_USB_PERMISSION = "com.github.rosjava.android.androidp1.USB_PERMISSION";
    public final static String APP_NAME = "TangoBotApp";

    private Log mLog = LogFactory.getLog(MainActivity.class);
    private NodeMainExecutor mNodeMainExecutor = null;
    private URI mMasterUri;
    private String mHostName;
    private ImuPublisher mImuNode;
    // USB
    private UsbManager mUsbManager;
    private BroadcastReceiver mUsbAttachedReceiver;
    private BroadcastReceiver mUsbDetachedReceiver;
    private PendingIntent mUsbPermissionIntent;
    private Map<UsbDevice, NodeMain[]> mUsbNodes = new HashMap<UsbDevice, NodeMain[]>();

    private CountDownLatch mNodeMainExecutorLatch;
    private TangoRosNode mTangoRosNode;

    ServiceConnection mTangoServiceConnection = new TangoInitializationHelper.DefaultServiceConnection(
        new AfterConnectionCallback() {
            @Override
            public void execute() {
                if (TangoInitializationHelper.isTangoServiceBound()) {
                    android.util.Log.i(TAG, "Bound to Tango Service");
                } else {
                    android.util.Log.e(TAG, getString(R.string.tango_bind_error));
                    displayToastMessage(R.string.tango_bind_error);
                    onDestroy();
                }
            }
        });

    public MainActivity() {
        super(APP_NAME, APP_NAME);
        mNodeMainExecutorLatch = new CountDownLatch(1);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UI
        setContentView(R.layout.main);

        // USB handling code
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mUsbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        mUsbAttachedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mLog.info("Received USB Intent");
                if (intent.getAction() == ACTION_USB_PERMISSION &&
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    onDeviceReady((UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
                }
            }
        };
        mUsbDetachedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mLog.info("Received USB disconnection Intent");
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onDeviceDetached(device);
            }
        };
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {
        mLog.info("MainActivity init");

        // Store a reference to the NodeMainExecutor and unblock any processes that were waiting
        // for this to start ROS Nodes
        this.mNodeMainExecutor = nodeMainExecutor;
        mNodeMainExecutorLatch.countDown();

        mMasterUri = getMasterUri();
        mHostName = getRosHostname();

        mLog.info(mMasterUri);

        // Trigger asking permission to access any devices that are already connected
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : manager.getDeviceList().values()) {
            manager.requestPermission(device, mUsbPermissionIntent);
        }

        startTangoRosNode();
    }

    public void onStart(final ConnectedNode connectedNode) {

    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceiver(mUsbAttachedReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        registerReceiver(mUsbDetachedReceiver, new IntentFilter(
                UsbManager.ACTION_USB_DEVICE_DETACHED));
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mUsbAttachedReceiver);
        unregisterReceiver(mUsbDetachedReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onUsbDeviceAttached(intent);
    }

    private void onUsbDeviceAttached(Intent intent) {
        if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            UsbDevice usbDevice = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            onDeviceReady(usbDevice);
        }
    }

    /**
     * Called when permission has been granted to a device
     * It routes to the appropriate node starting code
     */
    private void onDeviceReady(final UsbDevice device) {
        new Thread() {
            @Override
            public void run() {
                mLog.info("Connected device: vendor" + device.getVendorId() + "product: " + device.getProductId());
                // Only proceed if the application is ready to start nodes
                try {
                    mNodeMainExecutorLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // See what type of device it is and start the appropriate node depending on it
                try {
                    // Start base controller
                    // Dynamically load the corresponding NodeLoader class
                    if (device.getVendorId() == 1027 && device.getProductId() == 24577) {
                        mLog.info("Kobuki device found.");
                        // Instantiate it
                        UsbDeviceNodeLoader loader = new KobukiNodeLoader(mNodeMainExecutor, getMasterUri(), getRosHostname());
                        mLog.info("Loader found and instantiated. About to start node.");

                        // Create the node, keeping a reference of created nodes to allow shutting
                        // down properly on application shutdown or when the device is disconnected
                        NodeMain[] newUsbNodes = loader.startNodes(device, mUsbManager);
                        if (newUsbNodes != null) {
                            mUsbNodes.put(device, newUsbNodes);
                            mLog.info(newUsbNodes.length + " nodes started");
                        } else {
                            mLog.info("startNodes returned null");
                        }
                    }
                } catch (Exception e) {
                    mLog.info("Couldn't start Node for connected device", e);
                }
            }
        }.start();
    }

    /**
     * Called when a USB device has been disconnected
     */
    private void onDeviceDetached(UsbDevice device) {
        NodeMain[] nodeMains = mUsbNodes.get(device);
        if (nodeMains != null) {
            for (NodeMain nodeMain : nodeMains) {
                // Shutdown this node, considering it has been unplugged
                mLog.info("Device for node unplugged, shutting down");
                mNodeMainExecutor.shutdownNodeMain(nodeMain);
            }
        } else {
            mLog.info("USB device unplugged but no corresponding node found");
        }
    }

    // Create IMU publisher node
    private void startImu() {
        mLog.info("Starting imu...");

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mHostName);
        SensorManager mSensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);

        nodeConfiguration.setMasterUri(mMasterUri);
        nodeConfiguration.setNodeName("ImuNode");

        mImuNode = new ImuPublisher(mSensorManager);

        mLog.info("About to execute ImuNode...");
        mNodeMainExecutor.execute(mImuNode, nodeConfiguration);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    public void startTangoRosNode() {
        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(mHostName);
        nodeConfiguration.setMasterUri(mMasterUri);
        nodeConfiguration.setNodeName("TangoRosNode");

        // Create and start Tango ROS Node
        nodeConfiguration.setNodeName(TangoRosNode.NODE_NAME);
        if(TangoInitializationHelper.loadTangoSharedLibrary() !=
                TangoInitializationHelper.ARCH_ERROR) {
            mTangoRosNode = new TangoRosNode();
            mTangoRosNode.attachCallbackListener(this);
            TangoInitializationHelper.bindTangoService(this, mTangoServiceConnection);
            if (TangoInitializationHelper.checkTangoVersionOk(this)) {
                mNodeMainExecutor.execute(mTangoRosNode, nodeConfiguration);
            } else {
                android.util.Log.e(TAG, getResources().getString(R.string.tango_version_error));
                displayToastMessage(R.string.tango_version_error);
            }
        } else {
            android.util.Log.e(TAG, getString(R.string.tango_lib_error));
            displayToastMessage(R.string.tango_lib_error);
        }
    }
    /**
     * Display a toast message with the given message.
     * @param messageId String id of the message to display.
     */
    private void displayToastMessage(final int messageId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), messageId, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Implements TangoRosNode.CallbackListener.
     */
    public void onNativeNodeExecutionError(int errorCode) {
        if (errorCode == NativeNodeMainBeta.ROS_CONNECTION_ERROR) {
            android.util.Log.e(TAG, getString(R.string.ros_init_error));
            displayToastMessage(R.string.ros_init_error);
        } else if (errorCode < NativeNodeMainBeta.SUCCESS) {
            displayToastMessage(R.string.tango_service_error);
            android.util.Log.e(TAG, getString(R.string.tango_service_error));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (TangoInitializationHelper.isTangoServiceBound()) {
            android.util.Log.i(TAG, "Unbind tango service");
            unbindService(mTangoServiceConnection);
        }

        super.nodeMainExecutorService.forceShutdown();
    }
}