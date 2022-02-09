/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.stk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.provider.Settings;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;

/**
 * Boot completed receiver. used to reset the app install state every time the
 * device boots.
 *
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    /*UNISOC: Feature bug for Stk Feature @{*/
    private static final String LOG_TAG = "BootCompletedReceiver";

    private boolean isUiccReady(Context context) {
        TelephonyManager tm = TelephonyManager.from(context);
        StkAppService appService = StkAppService.getInstance();

        for (int i = 0; i < tm.getPhoneCount(); i++) {
            // Check if the card is inserted.
            if (tm.hasIccCard(i)) {
                CatLog.d(LOG_TAG, " Uicc " + i + " is inserted");
                if (tm.getSimState(i) == TelephonyManager.SIM_STATE_READY
                        && appService != null
                        && appService.getStkContext(i) != null
                        && appService.getStkContext(i).mMainCmd != null) {
                    CatLog.d(LOG_TAG, "Uicc " + i + " is ready && have main cmd");
                    return true;
                }
            }
        }
        return false;
    }
    /*UNISOC: @}*/
    /*UNISOC: Feature for Cucc Feature @{*/
    private boolean isUiccReady(Context context, int slotId) {
        TelephonyManager tm = TelephonyManager.from(context);
        StkAppService appService = StkAppService.getInstance();

        if (slotId < 0) {
            return false;
        }

        if (tm.hasIccCard(slotId)) {
            CatLog.d(LOG_TAG, " Uicc " + slotId + " is inserted");
            if (tm.getSimState(slotId) == TelephonyManager.SIM_STATE_READY
                    && appService != null
                    && appService.getStkContext(slotId) != null
                    && appService.getStkContext(slotId).mMainCmd != null) {
                CatLog.d(LOG_TAG, " Uicc " + slotId + " is ready && have main cmd");
                return true;
            }
        }
        return false;
    }
    /*UNISOC: @}*/
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        /*UNISOC: Feature bug for Stk Feature @{*/
        CatLog.d(LOG_TAG, " action: " + action);
        /*UNISOC: @}*/
        if (action == null) {
            return;
        }

        // make sure the app icon is removed every time the device boots.
        if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Bundle args = new Bundle();
            args.putInt(StkAppService.OPCODE, StkAppService.OP_BOOT_COMPLETED);
            try {
            context.startService(new Intent(context, StkAppService.class)
                    .putExtras(args));
            } catch (IllegalStateException e) {
                e.printStackTrace();
                CatLog.d(LOG_TAG, "start StkAppService fail");
            }
            CatLog.d(LOG_TAG, "[ACTION_BOOT_COMPLETED]");
        } else if(action.equals(Intent.ACTION_USER_INITIALIZE)) {
            // TODO: http://b/25155491
            if (!android.os.Process.myUserHandle().isSystem()) {
                //Disable package for all secondary users. Package is only required for device
                //owner.
                context.getPackageManager().setApplicationEnabledSetting(context.getPackageName(),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
                return;
            }
        /*UNISOC: Feature for Cucc Feature  && AirPlane Feature@{*/
        } else if (action.equals(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED) ||
                action.equals(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED)) {
            StkAppService appService = StkAppService.getInstance();
            if (null == appService) {
                CatLog.d(LOG_TAG, " appService is null...");
                return;
            }
            boolean isCucc = appService.isCuccOperator();
            boolean isAirPlaneModeOn = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            CatLog.d(LOG_TAG, " isCucc: " + isCucc + " and isAirPlaneModeOn: " + isAirPlaneModeOn);
            int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY, -1);
            int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                    TelephonyManager.SIM_STATE_UNKNOWN);
            CatLog.d(LOG_TAG, " phoneId: " + slotId + " and state: " + state);
            if (slotId < 0 || isAirPlaneModeOn) {
                return;
            }
            if (isCucc) {
                if (isUiccReady(context, slotId)) {
                    StkAppInstaller.install(context, slotId);
                } else {
                    if (TelephonyManager.SIM_STATE_ABSENT == state) {
                        StkAppInstaller.unInstall(context, slotId);
                    }
                }
            } else {
                if (isUiccReady(context)) {
                    StkAppInstaller.install(context);
                } else {
                    if (TelephonyManager.SIM_STATE_ABSENT == state) {
                        if (context.getResources().getBoolean(R.bool.config_show_specific_name)){
                            StkAppInstaller.install(context);
                        } else {
                            StkAppInstaller.unInstall(context);
                        }
                    }
                }
            }
        }
        /*UNISOC: @}*/
    }
}
