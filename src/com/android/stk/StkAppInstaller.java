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

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.res.Resources;
import android.text.TextUtils;
import android.telephony.TelephonyManager;
import android.os.SystemProperties;

import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.util.List;

/**
 * Application installer for SIM Toolkit.
 *
 */
abstract class StkAppInstaller {
    private static final String STK_MAIN_ACTIVITY = "com.android.stk.StkMain";
    private static final String LOG_TAG = "StkAppInstaller";
    /*UNISOC: Feature bug for Stk Feature @{*/
    private static final String STK_MAIN_ALIAS_ACTIVITY = "com.android.stk.StkMainAlias";
    public static final int NOT_INSTALLED = 0;
    public static final int INSTALLED = 1;
    private static int mInstalled = -1;
    /*UNISOC: @}*/
    private StkAppInstaller() {
        CatLog.d(LOG_TAG, "init");
    }

    public static void install(Context context) {
        setAppState(context, true);
    }

    public static void unInstall(Context context) {
        setAppState(context, false);
    }

    private static void setAppState(Context context, boolean install) {
        CatLog.d(LOG_TAG, "[setAppState]+");
        if (context == null) {
            CatLog.d(LOG_TAG, "[setAppState]- no context, just return.");
            return;
        }

        /*UNISOC: Feature bug for Stk Feature @{*/
        if (install) {
            CatLog.d(LOG_TAG, "[setAppState] == install");
        } else {
            CatLog.d(LOG_TAG, "[setAppState] == uninstall");
        }
        /*UNISOC: @}*/

        PackageManager pm = context.getPackageManager();
        List<SubscriptionInfo> subList = SubscriptionManager.from(context)
                .getActiveSubscriptionInfoList();
        if (pm == null) {
            CatLog.d(LOG_TAG, "[setAppState]- no package manager, just return.");
            return;
        }
        ComponentName cName = new ComponentName("com.android.stk", STK_MAIN_ACTIVITY);
        /*UNISOC: Feature bug for Stk Feature @{*/
        ComponentName cNameAlias = new ComponentName("com.android.stk", STK_MAIN_ALIAS_ACTIVITY);
        /*UNISOC: @}*/

        int state = install ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        /*UNISOC: Feature bug for Telcel Feature @{ */
        CatLog.d(LOG_TAG, "state: " + state + " SettingState: " + pm.getComponentEnabledSetting(cName));
        if (context.getResources().getBoolean(R.bool.config_show_specific_name)) {
            if ((mInstalled == NOT_INSTALLED && state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    || (mInstalled == INSTALLED && state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
                CatLog.d(LOG_TAG, "Do not need to change STK app state");
            } else {
                mInstalled = install ? INSTALLED : NOT_INSTALLED;
                try {
                    ComponentName mName = new ComponentName("com.android.stk", "com.android.stk.StkMenuActivity");
                    if (mInstalled == NOT_INSTALLED) {
                        CatLog.d(LOG_TAG, "mInstalled == NOT_INSTALLED");
                        if (pm.getComponentEnabledSetting(cNameAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                            pm.setComponentEnabledSetting(cNameAlias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                    PackageManager.DONT_KILL_APP);
                        }
                        if (pm.getComponentEnabledSetting(cName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                            pm.setComponentEnabledSetting(cName,
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                        }
                        return;
                    }
                    StkAppService stkService = StkAppService.getInstance();
                    String labelName = null;
                    if (stkService != null && stkService.isShowSetupMenuTitle()) {
                        if (stkService.getMenu(0) != null) {
                            labelName = stkService.getMainMenu(0).title;
                        } else if (stkService.getMenu(1) != null) {
                            labelName = stkService.getMainMenu(1).title;
                        } else {
                            labelName = context.getResources().getString(R.string.app_name_alias);
                        }
                    } else {
                        labelName = context.getResources().getString(R.string.app_name_alias);
                    }
                    CatLog.d(LOG_TAG, "Set launcher name to : " + labelName);
                    Intent intent = new Intent();
                    intent.putExtra("setup.menu.labelName", labelName);
                    pm.setComponentEnabledSettingForSetupMenu(cNameAlias, PackageManager.DONT_KILL_APP, intent);
                    pm.setComponentEnabledSettingForSetupMenu(mName, PackageManager.DONT_KILL_APP, intent);
                    pm.setComponentEnabledSetting(cNameAlias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP);
                } catch (Exception e) {
                    CatLog.d(LOG_TAG, "Could not change STK app state");
                }
            }
            return;
        }
        /*UNISOC: @}*/
        if (((PackageManager.COMPONENT_ENABLED_STATE_ENABLED == state) &&
                (PackageManager.COMPONENT_ENABLED_STATE_ENABLED ==
                pm.getComponentEnabledSetting(cName))) ||
                ((PackageManager.COMPONENT_ENABLED_STATE_DISABLED == state) &&
                (PackageManager.COMPONENT_ENABLED_STATE_DISABLED ==
                pm.getComponentEnabledSetting(cName)))) {
            CatLog.d(LOG_TAG, "Need not change app state!!");
        } else {
            CatLog.d(LOG_TAG, "Change app state[" + install + "]");
            try {
                /*UNISOC: Feature bug for Telcel vodafone Feature @{*/
                // Set STK launcher name with insert one SIM card
                StkAppService appService = StkAppService.getInstance();
                String showName = null;
                if (install && appService != null && appService.isShowSetupMenuTitle()) {
                    if (appService.getMenu(0) != null) {
                        showName = appService.getMainMenu(0).title;
                     } else if (appService.getMenu(1) != null) {
                        showName = appService.getMainMenu(1).title;
                     }
                }
                if (subList != null && subList.size() == 1) {
                    final int subId = subList.get(0).getSubscriptionId();
                    Resources res = SubscriptionManager.getResourcesForSubId(context, subId);
                    if (res.getBoolean(com.android.internal.R.bool.force_set_stk_name)) {
                        showName = res.getString(com.android.internal.R.string.stk_name_string);
                    }
                }
                CatLog.d(LOG_TAG, "Set launcher name to showName:" + showName);
                if (!TextUtils.isEmpty(showName)) {
                    Intent intent = new Intent();
                    intent.putExtra("setup.menu.labelName", showName);
                    pm.setComponentEnabledSettingForSetupMenu(cName, PackageManager.DONT_KILL_APP, intent);
                }
                CatLog.d(LOG_TAG, "setComponentEnabledSetting: " + state);
                if (pm.getComponentEnabledSetting(cNameAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    pm.setComponentEnabledSetting(cNameAlias,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                }
                /*UNISOC: @}*/
                pm.setComponentEnabledSetting(cName, state, PackageManager.DONT_KILL_APP);
            } catch (Exception e) {
                CatLog.d(LOG_TAG, "Could not change STK app state e:" + e);
            }
        }
        CatLog.d(LOG_TAG, "[setAppState]-");
    }


    /*UNISOC: Feature bug for Cucc Feature @{*/
    public static void install(Context context, int slotId) {
        setAppState(context, true, slotId);
    }

    public static void unInstall(Context context, int slotId) {
        setAppState(context, false, slotId);
    }

    private static void setAppState(Context context, boolean install, int slotId) {
        CatLog.d(LOG_TAG, " install: " + install + " slotId: " + slotId);

        if ( null == context || slotId < 0) {
            return;
        } else {
            PackageManager pm = context.getPackageManager();

            if (pm == null) {
                CatLog.d(LOG_TAG, "setAppState()- no package manager, just return.");
                return;
            }

            String[] launcherActivity = {
                    "com.android.stk.StkMain1",
                    "com.android.stk.StkMain2"
            };

            ComponentName cName = new ComponentName("com.android.stk", launcherActivity[slotId]);
            int state = install ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            CatLog.d(LOG_TAG, " cName: " + cName + " SettingState: " + pm.getComponentEnabledSetting(cName));
            if (((PackageManager.COMPONENT_ENABLED_STATE_ENABLED == state) &&
                    (PackageManager.COMPONENT_ENABLED_STATE_ENABLED ==
                    pm.getComponentEnabledSetting(cName))) ||
                    ((PackageManager.COMPONENT_ENABLED_STATE_DISABLED == state) &&
                    (PackageManager.COMPONENT_ENABLED_STATE_DISABLED ==
                    pm.getComponentEnabledSetting(cName)))) {
                CatLog.d(LOG_TAG, "Need not change app state!!");
            } else {
                try {
                    CatLog.d(LOG_TAG, " setComponentEnabledSetting: " + state);
                    pm.setComponentEnabledSetting(cName, state, PackageManager.DONT_KILL_APP);
                } catch (Exception e) {
                    CatLog.d(LOG_TAG, " Could not change STK app state e: " + e);
                }
            }
        }
    }
    /*UNISOC: @}*/
}
