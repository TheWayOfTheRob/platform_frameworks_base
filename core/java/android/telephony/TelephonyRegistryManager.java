/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Annotation.CallState;
import android.telephony.Annotation.DataActivityType;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.PreciseCallStates;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SimActivationState;
import android.telephony.Annotation.SrvccState;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.DisconnectCause;
import android.telephony.PhoneCapability;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ImsReasonInfo;
import android.util.Log;

import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ITelephonyRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A centralized place to notify telephony related status changes, e.g, {@link ServiceState} update
 * or {@link PhoneCapability} changed. This might trigger callback from applications side through
 * {@link android.telephony.PhoneStateListener}
 *
 * TODO: limit API access to only carrier apps with certain permissions or apps running on
 * privileged UID.
 *
 * @hide
 */
@SystemApi
public class TelephonyRegistryManager {

    private static final String TAG = "TelephonyRegistryManager";
    private static ITelephonyRegistry sRegistry;
    private final Context mContext;

    /**
     * A mapping between {@link SubscriptionManager.OnSubscriptionsChangedListener} and
     * its callback IOnSubscriptionsChangedListener.
     */
    private final Map<SubscriptionManager.OnSubscriptionsChangedListener,
                IOnSubscriptionsChangedListener> mSubscriptionChangedListenerMap = new HashMap<>();
    /**
     * A mapping between {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener} and
     * its callback IOnSubscriptionsChangedListener.
     */
    private final Map<SubscriptionManager.OnOpportunisticSubscriptionsChangedListener,
            IOnSubscriptionsChangedListener> mOpportunisticSubscriptionChangedListenerMap
            = new HashMap<>();


    /** @hide **/
    public TelephonyRegistryManager(@NonNull Context context) {
        mContext = context;
        if (sRegistry == null) {
            sRegistry = ITelephonyRegistry.Stub.asInterface(
                ServiceManager.getService("telephony.registry"));
        }
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification. The
     * onSubscriptionChanged method will also be triggered once initially when calling this
     * function.
     *
     * @param listener an instance of {@link SubscriptionManager.OnSubscriptionsChangedListener}
     *                 with onSubscriptionsChanged overridden.
     * @param executor the executor that will execute callbacks.
     */
    public void addOnSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnSubscriptionsChangedListener listener,
            @NonNull Executor executor) {
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged () {
                Log.d(TAG, "onSubscriptionsChangedListener callback received.");
                executor.execute(() -> listener.onSubscriptionsChanged());
            }
        };
        mSubscriptionChangedListenerMap.put(listener, callback);
        try {
            sRegistry.addOnSubscriptionsChangedListener(mContext.getOpPackageName(), callback);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Unregister the {@link SubscriptionManager.OnSubscriptionsChangedListener}. This is not
     * strictly necessary as the listener will automatically be unregistered if an attempt to
     * invoke the listener fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnSubscriptionsChangedListener listener) {
        if (mSubscriptionChangedListenerMap.get(listener) == null) {
            return;
        }
        try {
            sRegistry.removeOnSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mSubscriptionChangedListenerMap.get(listener));
            mSubscriptionChangedListenerMap.remove(listener);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Register for changes to the list of opportunistic subscription records or to the
     * individual records themselves. When a change occurs the onOpportunisticSubscriptionsChanged
     * method of the listener will be invoked immediately if there has been a notification.
     *
     * @param listener an instance of
     * {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener} with
     *                 onOpportunisticSubscriptionsChanged overridden.
     * @param executor an Executor that will execute callbacks.
     */
    public void addOnOpportunisticSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnOpportunisticSubscriptionsChangedListener listener,
            @NonNull Executor executor) {
        /**
         * The callback methods need to be called on the executor thread where
         * this object was created.  If the binder did that for us it'd be nice.
         */
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged() {
                final long identity = Binder.clearCallingIdentity();
                try {
                    Log.d(TAG, "onOpportunisticSubscriptionsChanged callback received.");
                    executor.execute(() -> listener.onOpportunisticSubscriptionsChanged());
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };
        mOpportunisticSubscriptionChangedListenerMap.put(listener, callback);
        try {
            sRegistry.addOnOpportunisticSubscriptionsChangedListener(mContext.getOpPackageName(),
                    callback);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Unregister the {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener}
     * that is currently listening opportunistic subscriptions change. This is not strictly
     * necessary as the listener will automatically be unregistered if an attempt to invoke the
     * listener fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnOpportunisticSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnOpportunisticSubscriptionsChangedListener listener) {
        try {
            sRegistry.removeOnSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mOpportunisticSubscriptionChangedListenerMap.get(listener));
            mOpportunisticSubscriptionChangedListenerMap.remove(listener);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Informs the system of an intentional upcoming carrier network change by a carrier app.
     * This call only used to allow the system to provide alternative UI while telephony is
     * performing an action that may result in intentional, temporary network lack of connectivity.
     * <p>
     * Based on the active parameter passed in, this method will either show or hide the alternative
     * UI. There is no timeout associated with showing this UX, so a carrier app must be sure to
     * call with active set to false sometime after calling with it set to {@code true}.
     * <p>
     * Requires Permission: calling app has carrier privileges.
     *
     * @param active Whether the carrier network change is or shortly will be
     * active. Set this value to true to begin showing alternative UI and false to stop.
     * @see TelephonyManager#hasCarrierPrivileges
     */
    public void notifyCarrierNetworkChange(boolean active) {
        try {
            sRegistry.notifyCarrierNetworkChange(active);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Notify call state changed on certain subscription.
     *
     * @param subId for which call state changed.
     * @param slotIndex for which call state changed. Can be derived from subId except when subId is
     * invalid.
     * @param state latest call state. e.g, offhook, ringing
     * @param incomingNumber incoming phone number.
     *
     * @hide
     */
    public void notifyCallStateChanged(int subId, int slotIndex, @CallState int state,
            String incomingNumber) {
        try {
            sRegistry.notifyCallState(slotIndex, subId, state, incomingNumber);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Notify call state changed on all subscriptions.
     *
     * @param state latest call state. e.g, offhook, ringing
     * @param incomingNumber incoming phone number.
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void notifyCallStateChangedForAllSubscriptions(@CallState int state,
            @Nullable String incomingNumber) {
        try {
            sRegistry.notifyCallStateForAllSubs(state, incomingNumber);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Notify {@link ServiceState} update on certain subscription.
     *
     * @param subId for which the service state changed.
     * @param slotIndex for which the service state changed. Can be derived from subId except
     * subId is invalid.
     * @param state service state e.g, in service, out of service or roaming status.
     *
     * @hide
     */
    public void notifyServiceStateChanged(int subId, int slotIndex, ServiceState state) {
        try {
            sRegistry.notifyServiceStateForPhoneId(slotIndex, subId, state);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Notify {@link SignalStrength} update on certain subscription.
     *
     * @param subId for which the signalstrength changed.
     * @param slotIndex for which the signalstrength changed. Can be derived from subId except when
     * subId is invalid.
     * @param signalStrength e.g, signalstrength level {@see SignalStrength#getLevel()}
     *
     * @hide
     */
    public void notifySignalStrengthChanged(int subId, int slotIndex,
        SignalStrength signalStrength) {
        try {
            sRegistry.notifySignalStrengthForPhoneId(slotIndex, subId, signalStrength);
        } catch (RemoteException ex) {
            // system server crash
        }
    }

    /**
     * Notify changes to the message-waiting indicator on certain subscription. e.g, The status bar
     * uses message waiting indicator to determine when to display the voicemail icon.
     *
     * @param subId for which message waiting indicator changed.
     * @param slotIndex for which message waiting indicator changed. Can be derived from subId
     * except when subId is invalid.
     * @param msgWaitingInd {@code true} indicates there is message-waiting indicator, {@code false}
     * otherwise.
     *
     * @hide
     */
    public void notifyMessageWaitingChanged(int subId, int slotIndex, boolean msgWaitingInd) {
        try {
            sRegistry.notifyMessageWaitingChangedForPhoneId(slotIndex, subId, msgWaitingInd);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify changes to the call-forwarding status on certain subscription.
     *
     * @param subId for which call forwarding status changed.
     * @param callForwardInd {@code true} indicates there is call forwarding, {@code false}
     * otherwise.
     *
     * @hide
     */
    public void notifyCallForwardingChanged(int subId, boolean callForwardInd) {
        try {
            sRegistry.notifyCallForwardingChangedForSubscriber(subId, callForwardInd);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify changes to activity state changes on certain subscription.
     *
     * @param subId for which data activity state changed.
     * @param dataActivityType indicates the latest data activity type e.g, {@link
     * TelephonyManager#DATA_ACTIVITY_IN}
     *
     * @hide
     */
    public void notifyDataActivityChanged(int subId, @DataActivityType int dataActivityType) {
        try {
            sRegistry.notifyDataActivityForSubscriber(subId, dataActivityType);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify changes to default (Internet) data connection state on certain subscription.
     *
     * @param subId for which data connection state changed.
     * @param slotIndex for which data connections state changed. Can be derived from subId except
     * when subId is invalid.
     * @param apnType the APN type that triggered this update
     * @param preciseState the PreciseDataConnectionState
     *
     * @see android.telephony.PreciseDataConnection
     * @see TelephonyManager#DATA_DISCONNECTED
     * @hide
     */
    public void notifyDataConnectionForSubscriber(int slotIndex, int subId,
            String apnType, PreciseDataConnectionState preciseState) {
        try {
            sRegistry.notifyDataConnectionForSubscriber(
                    slotIndex, subId, apnType, preciseState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify {@link CallQuality} change on certain subscription.
     *
     * @param subId for which call quality state changed.
     * @param slotIndex for which call quality state changed. Can be derived from subId except when
     * subId is invalid.
     * @param callQuality Information about call quality e.g, call quality level
     * @param networkType associated with this data connection. e.g, LTE
     *
     * @hide
     */
    public void notifyCallQualityChanged(int subId, int slotIndex, CallQuality callQuality,
        @NetworkType int networkType) {
        try {
            sRegistry.notifyCallQualityChanged(callQuality, slotIndex, subId, networkType);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify emergency number list changed on certain subscription.
     *
     * @param subId for which emergency number list changed.
     * @param slotIndex for which emergency number list changed. Can be derived from subId except
     * when subId is invalid.
     *
     * @hide
     */
    public void notifyEmergencyNumberList(int subId, int slotIndex) {
        try {
            sRegistry.notifyEmergencyNumberList(slotIndex, subId);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify radio power state changed on certain subscription.
     *
     * @param subId for which radio power state changed.
     * @param slotIndex for which radio power state changed. Can be derived from subId except when
     * subId is invalid.
     * @param radioPowerState the current modem radio state.
     *
     * @hide
     */
    public void notifyRadioPowerStateChanged(int subId, int slotIndex,
        @RadioPowerState int radioPowerState) {
        try {
            sRegistry.notifyRadioPowerStateChanged(slotIndex, subId, radioPowerState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify {@link PhoneCapability} changed.
     *
     * @param phoneCapability the capability of the modem group.
     *
     * @hide
     */
    public void notifyPhoneCapabilityChanged(PhoneCapability phoneCapability) {
        try {
            sRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Sim activation type: voice
     * @see #notifyVoiceActivationStateChanged
     * @hide
     */
    public static final int SIM_ACTIVATION_TYPE_VOICE = 0;
    /**
     * Sim activation type: data
     * @see #notifyDataActivationStateChanged
     * @hide
     */
    public static final int SIM_ACTIVATION_TYPE_DATA = 1;

    /**
     * Notify data activation state changed on certain subscription.
     * @see TelephonyManager#getDataActivationState()
     *
     * @param subId for which data activation state changed.
     * @param slotIndex for which data activation state changed. Can be derived from subId except
     * when subId is invalid.
     * @param activationState sim activation state e.g, activated.
     *
     * @hide
     */
    public void notifyDataActivationStateChanged(int subId, int slotIndex,
        @SimActivationState int activationState) {
        try {
            sRegistry.notifySimActivationStateChangedForPhoneId(slotIndex, subId,
                    SIM_ACTIVATION_TYPE_DATA, activationState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify voice activation state changed on certain subscription.
     * @see TelephonyManager#getVoiceActivationState()
     *
     * @param subId for which voice activation state changed.
     * @param slotIndex for which voice activation state changed. Can be derived from subId except
     * subId is invalid.
     * @param activationState sim activation state e.g, activated.
     *
     * @hide
     */
    public void notifyVoiceActivationStateChanged(int subId, int slotIndex,
        @SimActivationState int activationState) {
        try {
            sRegistry.notifySimActivationStateChangedForPhoneId(slotIndex, subId,
                    SIM_ACTIVATION_TYPE_VOICE, activationState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify User mobile data state changed on certain subscription. e.g, mobile data is enabled
     * or disabled.
     *
     * @param subId for which mobile data state has changed.
     * @param slotIndex for which mobile data state has changed. Can be derived from subId except
     * when subId is invalid.
     * @param state {@code true} indicates mobile data is enabled/on. {@code false} otherwise.
     *
     * @hide
     */
    public void notifyUserMobileDataStateChanged(int slotIndex, int subId, boolean state) {
        try {
            sRegistry.notifyUserMobileDataStateChangedForPhoneId(slotIndex, subId, state);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * TODO: this is marked as deprecated, can we move this one safely?
     *
     * @param subId
     * @param slotIndex
     * @param rawData
     *
     * @hide
     */
    public void notifyOemHookRawEventForSubscriber(int subId, int slotIndex, byte[] rawData) {
        try {
            sRegistry.notifyOemHookRawEventForSubscriber(slotIndex, subId, rawData);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify IMS call disconnect causes which contains {@link android.telephony.ims.ImsReasonInfo}.
     *
     * @param subId for which ims call disconnect.
     * @param imsReasonInfo the reason for ims call disconnect.
     *
     * @hide
     */
    public void notifyImsDisconnectCause(int subId, ImsReasonInfo imsReasonInfo) {
        try {
            sRegistry.notifyImsDisconnectCause(subId, imsReasonInfo);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify precise data connection failed cause on certain subscription.
     *
     * @param subId for which data connection failed.
     * @param slotIndex for which data conenction failed. Can be derived from subId except when
     * subId is invalid.
     * @param apnType the apnType, "ims" for IMS APN, "emergency" for EMERGENCY APN.
     * @param apn the APN {@link ApnSetting#getApnName()} of this data connection.
     * @param failCause data fail cause.
     *
     * @hide
     */
    public void notifyPreciseDataConnectionFailed(int subId, int slotIndex, String apnType,
        String apn, @DataFailureCause int failCause) {
        try {
            sRegistry.notifyPreciseDataConnectionFailed(slotIndex, subId, apnType, apn, failCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify single Radio Voice Call Continuity (SRVCC) state change for the currently active call
     * on certain subscription.
     *
     * @param subId for which srvcc state changed.
     * @param state srvcc state
     *
     * @hide
     */
    public void notifySrvccStateChanged(int subId, @SrvccState int state) {
        try {
            sRegistry.notifySrvccStateChanged(subId, state);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify precise call state changed on certain subscription, including foreground, background
     * and ringcall states.
     *
     * @param subId for which precise call state changed.
     * @param slotIndex for which precise call state changed. Can be derived from subId except when
     * subId is invalid.
     * @param ringCallPreciseState ringCall state.
     * @param foregroundCallPreciseState foreground call state.
     * @param backgroundCallPreciseState background call state.
     *
     * @hide
     */
    public void notifyPreciseCallState(int subId, int slotIndex,
        @PreciseCallStates int ringCallPreciseState,
        @PreciseCallStates int foregroundCallPreciseState,
        @PreciseCallStates int backgroundCallPreciseState) {
        try {
            sRegistry.notifyPreciseCallState(slotIndex, subId, ringCallPreciseState,
                foregroundCallPreciseState, backgroundCallPreciseState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify call disconnect causes which contains {@link DisconnectCause} and {@link
     * android.telephony.PreciseDisconnectCause}.
     *
     * @param subId for which call disconnected.
     * @param slotIndex for which call disconnected. Can be derived from subId except when subId is
     * invalid.
     * @param cause {@link DisconnectCause} for the disconnected call.
     * @param preciseCause {@link android.telephony.PreciseDisconnectCause} for the disconnected
     * call.
     *
     * @hide
     */
    public void notifyDisconnectCause(int slotIndex, int subId, int cause, int preciseCause) {
        try {
            sRegistry.notifyDisconnectCause(slotIndex, subId, cause, preciseCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * TODO change from bundle to CellLocation?
     * @hide
     */
    public void notifyCellLocation(int subId, Bundle cellLocation) {
        try {
            sRegistry.notifyCellLocationForSubscriber(subId, cellLocation);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Notify {@link CellInfo} changed on certain subscription. e.g, when an observed cell info has
     * changed or new cells have been added or removed on the given subscription.
     *
     * @param subId for which cellinfo changed.
     * @param cellInfo A list of cellInfo associated with the given subscription.
     *
     * @hide
     */
    public void notifyCellInfoChanged(int subId, List<CellInfo> cellInfo) {
        try {
            sRegistry.notifyCellInfoForSubscriber(subId, cellInfo);
        } catch (RemoteException ex) {

        }
    }

    /**
     * @param activeDataSubId
     * @hide
     */
    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        try {
            sRegistry.notifyActiveDataSubIdChanged(activeDataSubId);
        } catch (RemoteException ex) {

        }
    }
}
