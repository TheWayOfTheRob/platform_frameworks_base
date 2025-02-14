/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.List;

/**
 * HeadsetProfile handles Bluetooth HFP and Headset profiles.
 */
public class HeadsetProfile implements LocalBluetoothProfile {
    private static final String TAG = "HeadsetProfile";

    private BluetoothHeadset mService;
    private boolean mIsProfileReady;

    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;

    static final ParcelUuid[] UUIDS = {
        BluetoothUuid.HSP,
        BluetoothUuid.HFP,
    };

    static final String NAME = "HEADSET";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 0;

    // These callbacks run on the main thread.
    private final class HeadsetServiceListener
            implements BluetoothProfile.ServiceListener {

        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothHeadset) proxy;
            // We just bound to the service, so refresh the UI for any connected HFP devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    Log.w(TAG, "HeadsetProfile found new device: " + nextDevice);
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(HeadsetProfile.this,
                        BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }
            mIsProfileReady=true;
            mProfileManager.callServiceConnectedListeners();
        }

        public void onServiceDisconnected(int profile) {
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady=false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.HEADSET;
    }

    HeadsetProfile(Context context, CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context, new HeadsetServiceListener(),
                BluetoothProfile.HEADSET);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return true;
    }

    public boolean connect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.connect(device);
    }

    public boolean disconnect(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        // Downgrade priority as user is disconnecting the headset.
        if (mService.getConnectionPolicy(device) > BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            mService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        }
        return mService.disconnect(device);
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    public boolean setActiveDevice(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.setActiveDevice(device);
    }

    public BluetoothDevice getActiveDevice() {
        if (mService == null) {
            return null;
        }
        return mService.getActiveDevice();
    }

    public boolean isAudioOn() {
        if (mService == null) {
            return false;
        }
        return mService.isAudioOn();
    }

    public int getAudioState(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }
        return mService.getAudioState(device);
    }

    public boolean isPreferred(BluetoothDevice device) {
        if (mService == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
    }

    public int getPreferred(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    public void setPreferred(BluetoothDevice device, boolean preferred) {
        if (mService == null) {
            return;
        }
        if (preferred) {
            if (mService.getConnectionPolicy(device) < BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                mService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_ALLOWED);
            }
        } else {
            mService.setConnectionPolicy(device, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
              new int[] {BluetoothProfile.STATE_CONNECTED,
                         BluetoothProfile.STATE_CONNECTING,
                         BluetoothProfile.STATE_DISCONNECTING});
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_headset;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        int state = getConnectionStatus(device);
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return R.string.bluetooth_headset_profile_summary_use_for;

            case BluetoothProfile.STATE_CONNECTED:
                return R.string.bluetooth_headset_profile_summary_connected;

            default:
                return BluetoothUtils.getConnectionStateSummary(state);
        }
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_bt_headset_hfp;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HEADSET,
                                                                       mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up HID proxy", t);
            }
        }
    }
}
