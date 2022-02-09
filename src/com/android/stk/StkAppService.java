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

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.IProcessObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.os.UserManager;
import android.os.UserHandle;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.IntentFilter;

import com.android.internal.telephony.cat.AppInterface;
import com.android.internal.telephony.cat.Input;
import com.android.internal.telephony.cat.LaunchBrowserMode;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.ResultCode;
import com.android.internal.telephony.cat.CatCmdMessage;
import com.android.internal.telephony.cat.CatCmdMessage.BrowserSettings;
import com.android.internal.telephony.cat.CatCmdMessage.SetupEventListSettings;
import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.CatResponseMessage;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.cat.ToneSettings;
import com.android.internal.telephony.uicc.IccRefreshResponse;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.cat.CatService;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.CommandsInterface;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.IDLE_SCREEN_AVAILABLE_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.LANGUAGE_SELECTION_EVENT;
import static com.android.internal.telephony.cat.CatCmdMessage.
                   SetupEventListConstants.USER_ACTIVITY_EVENT;

/**
 * SIM toolkit application level service. Interacts with Telephopny messages,
 * application's launch and user input from STK UI elements.
 *
 */
public class StkAppService extends Service implements Runnable {

    // members
    protected class StkContext {
        protected CatCmdMessage mMainCmd = null;
        protected CatCmdMessage mCurrentCmd = null;
        protected CatCmdMessage mCurrentMenuCmd = null;
        protected Menu mCurrentMenu = null;
        protected String lastSelectedItem = null;
        protected boolean mMenuIsVisible = false;
        protected boolean mIsInputPending = false;
        protected boolean mIsMenuPending = false;
        protected boolean mIsDialogPending = false;
        protected boolean mNotificationOnKeyguard = false;
        protected boolean mNoResponseFromUser = false;
        protected boolean launchBrowser = false;
        protected BrowserSettings mBrowserSettings = null;
        protected LinkedList<DelayedCmd> mCmdsQ = null;
        protected boolean mCmdInProgress = false;
        protected int mStkServiceState = STATE_UNKNOWN;
        protected int mSetupMenuState = STATE_NOT_EXIST;
        protected int mMenuState = StkMenuActivity.STATE_INIT;
        protected int mOpCode = -1;
        private Activity mActivityInstance = null;
        private Activity mDialogInstance = null;
        private Activity mImmediateDialogInstance = null;
        private int mSlotId = 0;
        private SetupEventListSettings mSetupEventListSettings = null;
        private boolean mClearSelectItem = false;
        private boolean mDisplayTextDlgIsVisibile = false;
        private CatCmdMessage mCurrentSetupEventCmd = null;
        private CatCmdMessage mIdleModeTextCmd = null;
        private boolean mIdleModeTextVisible = false;
        // Determins whether the current session was initiated by user operation.
        protected boolean mIsSessionFromUser = false;
        /*UNISOC: Feature for SET_UP_CALL @{ */
        protected boolean mSetupCallInProcess = false; // true means in process.
        /*UNISOC: @}*/
        /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
        protected boolean mDisplayTextResponsed = false; // true means have send TR.
        private int mDelayToCheckTime = 0;
        /*UNISOC: @}*/
        final synchronized void setPendingActivityInstance(Activity act) {
            CatLog.d(this, "setPendingActivityInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_ACT_INST, mSlotId, act);
        }
        final synchronized Activity getPendingActivityInstance() {
            CatLog.d(this, "getPendingActivityInstance act : " + mSlotId + ", " +
                    mActivityInstance);
            return mActivityInstance;
        }
        final synchronized void setPendingDialogInstance(Activity act) {
            CatLog.d(this, "setPendingDialogInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_DAL_INST, mSlotId, act);
        }
        final synchronized Activity getPendingDialogInstance() {
            CatLog.d(this, "getPendingDialogInstance act : " + mSlotId + ", " +
                    mDialogInstance);
            return mDialogInstance;
        }
        final synchronized void setImmediateDialogInstance(Activity act) {
            CatLog.d(this, "setImmediateDialogInstance act : " + mSlotId + ", " + act);
            callSetActivityInstMsg(OP_SET_IMMED_DAL_INST, mSlotId, act);
        }
        final synchronized Activity getImmediateDialogInstance() {
            CatLog.d(this, "getImmediateDialogInstance act : " + mSlotId + ", " +
                    mImmediateDialogInstance);
            return mImmediateDialogInstance;
        }
        /*UNISOC: Feature bug for Stk Feature @{*/
        final synchronized void reset(){
            mCurrentMenuCmd = null;
            mIdleModeTextCmd = null;
        }
        /*UNISOC: @}*/
    }

    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;
    private Context mContext = null;
    private NotificationManager mNotificationManager = null;
    static StkAppService sInstance = null;
    private AppInterface[] mStkService = null;
    private StkContext[] mStkContext = null;
    private int mSimCount = 0;
    private IProcessObserver.Stub mProcessObserver = null;
    private BroadcastReceiver mLocaleChangeReceiver = null;
    private TonePlayer mTonePlayer = null;
    private Vibrator mVibrator = null;
    private BroadcastReceiver mUserActivityReceiver = null;

    // Used for setting FLAG_ACTIVITY_NO_USER_ACTION when
    // creating an intent.
    private enum InitiatedByUserAction {
        yes,            // The action was started via a user initiated action
        unknown,        // Not known for sure if user initated the action
    }

    // constants
    static final String OPCODE = "op";
    static final String CMD_MSG = "cmd message";
    static final String RES_ID = "response id";
    static final String MENU_SELECTION = "menu selection";
    static final String INPUT = "input";
    static final String HELP = "help";
    static final String CONFIRMATION = "confirm";
    static final String CHOICE = "choice";
    static final String SLOT_ID = "SLOT_ID";
    static final String STK_CMD = "STK CMD";
    static final String STK_DIALOG_URI = "stk://com.android.stk/dialog/";
    static final String STK_MENU_URI = "stk://com.android.stk/menu/";
    static final String STK_INPUT_URI = "stk://com.android.stk/input/";
    static final String STK_TONE_URI = "stk://com.android.stk/tone/";
    static final String FINISH_TONE_ACTIVITY_ACTION =
                                "android.intent.action.stk.finish_activity";

    // These below constants are used for SETUP_EVENT_LIST
    static final String SETUP_EVENT_TYPE = "event";
    static final String SETUP_EVENT_CAUSE = "cause";

    // operations ids for different service functionality.
    static final int OP_CMD = 1;
    static final int OP_RESPONSE = 2;
    static final int OP_LAUNCH_APP = 3;
    static final int OP_END_SESSION = 4;
    static final int OP_BOOT_COMPLETED = 5;
    private static final int OP_DELAYED_MSG = 6;
    static final int OP_CARD_STATUS_CHANGED = 7;
    static final int OP_SET_ACT_INST = 8;
    static final int OP_SET_DAL_INST = 9;
    static final int OP_LOCALE_CHANGED = 10;
    static final int OP_ALPHA_NOTIFY = 11;
    static final int OP_IDLE_SCREEN = 12;
    static final int OP_SET_IMMED_DAL_INST = 13;

    //Invalid SetupEvent
    static final int INVALID_SETUP_EVENT = 0xFF;

    // Message id to signal stop tone due to play tone timeout.
    private static final int OP_STOP_TONE = 16;

    // Message id to signal stop tone on user keyback.
    static final int OP_STOP_TONE_USER = 17;

    // Message id to remove stop tone message from queue.
    private static final int STOP_TONE_WHAT = 100;

    // Message id to send user activity event to card.
    private static final int OP_USER_ACTIVITY = 20;

    // Response ids
    static final int RES_ID_MENU_SELECTION = 11;
    static final int RES_ID_INPUT = 12;
    static final int RES_ID_CONFIRM = 13;
    static final int RES_ID_DONE = 14;
    static final int RES_ID_CHOICE = 15;

    static final int RES_ID_TIMEOUT = 20;
    static final int RES_ID_BACKWARD = 21;
    static final int RES_ID_END_SESSION = 22;
    static final int RES_ID_EXIT = 23;
    static final int RES_ID_ERROR = 24;

    static final int YES = 1;
    static final int NO = 0;

    static final int STATE_UNKNOWN = -1;
    static final int STATE_NOT_EXIST = 0;
    static final int STATE_EXIST = 1;

    private static final String PACKAGE_NAME = "com.android.stk";
    private static final String STK_MENU_ACTIVITY_NAME = PACKAGE_NAME + ".StkMenuActivity";
    private static final String STK_INPUT_ACTIVITY_NAME = PACKAGE_NAME + ".StkInputActivity";
    private static final String STK_DIALOG_ACTIVITY_NAME = PACKAGE_NAME + ".StkDialogActivity";
    // Notification id used to display Idle Mode text in NotificationManager.
    private static final int STK_NOTIFICATION_ID = 333;
    // Notification channel containing all mobile service messages notifications.
    private static final String STK_NOTIFICATION_CHANNEL_ID = "mobileServiceMessages";

    private static final String LOG_TAG = "StkAppService";

    static final String SESSION_ENDED = "session_ended";

    // For bug 1466545
    static final String CARD_ABSENT = "card_absent";
    /*UNISOC: Feature for orange Feature patch SPCSS00430242 @{*/
    static final String CLOSE_DIALOG_ACTIVITY =
            "android.intent.action.CLOSE_DIALOG_ACTIVITY";
    /*UNISOC: @}*/

    /*UNISOC: bug for black screen @{*/
    static final int OP_DELAY_TO_CHECK_USER_UNLOCK = 500;
    private static final int DELAY_TO_CHECK_USER_UNLOCK_TIME = 3 * 1000;
    private static final int DELAY_TO_CHECK_NUM = 5;
    private UserManager mUserManager;
    /*UNISOC: @}*/

    /*UNISOC: Feature for SET_UP_CALL @{ */
    static final int SETUP_CALL_NO_CALL_1   = 0x00;
    static final int SETUP_CALL_NO_CALL_2   = 0x01;
    static final int SETUP_CALL_HOLD_CALL_1 = 0x02;
    static final int SETUP_CALL_HOLD_CALL_2 = 0x03;
    static final int SETUP_CALL_END_CALL_1  = 0x04;
    static final int SETUP_CALL_END_CALL_2  = 0x05;
    /*UNISOC: @}*/

    /*UNISOC: Feature for Idle Mode(case 27.22.4.22.2/4) @{ */
    // notification can not display long text, so we start activity to support
    static String idleModeText = "";
    static Bitmap idleModeIcon = null;
    static boolean idleModeIconSelfExplanatory = false;
    private static final String STK_MESSAGE_ACTIVITY_NAME =
            PACKAGE_NAME + ".StkMessageActivity";
    /*UNISOC: @}*/

    /*UNISOC: Feature for REFRESH function @{*/
    private static final int REFRESH_UICC_RESET = 0x04;
    public Toast mToast = null;
    /*UNISOC: @}*/

    /*UNISOC: Feature for Cucc function @{*/
    static final String HOMEPRESSEDFLAG = "homepressed";
    /*UNISOC: @}*/

    /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
    private int mCurrentUserId = UserHandle.USER_OWNER;
    /*UNISOC: @}*/

    /*UNISOC: Feature for query call forward when send ss @{*/
    private Phone mPhone;
    /*UNISOC: @}*/

    /*UNISOC: Feature bug for LaunchBrowser @{*/
    private boolean mCustomLaunchBrowserTR = false;
    /*UNISOC: @}*/

    /*UNISOC: Feature for ModemAseert not display text Feature @{*/
    private final static String ACTION_MODEM_CHANGE =
            "com.android.modemassert.MODEM_STAT_CHANGE";
    private final static String MODEM_STAT = "modem_stat";
    private final static String MODEM_ASSERT = "modem_assert";
    /*UNISOC: @}*/

    /*UNISOC: Feature bug for home key @{*/
    public static boolean mHomePressedFlg = false;
    static final String SYSTEM_REASON = "reason";
    static final String SYSTEM_HOME_KEY = "homekey";
    /*UNISOC: @}*/

    /*UNISOC: Feature bug @{*/
    private static final String CALL_PACKAGE_NAME =
            "com.android.dialer";
    /*UNISOC: @}*/
    static final String REFRESH_UNINSTALL = "refresh_uninstall";

    // Inner class used for queuing telephony messages (proactive commands,
    // session end) while the service is busy processing a previous message.
    private class DelayedCmd {
        // members
        int id;
        CatCmdMessage msg;
        int slotId;

        DelayedCmd(int id, CatCmdMessage msg, int slotId) {
            this.id = id;
            this.msg = msg;
            this.slotId = slotId;
        }
    }

    // system property to set the STK specific default url for launch browser proactive cmds
    private static final String STK_BROWSER_DEFAULT_URL_SYSPROP = "persist.radio.stk.default_url";

    private static final int NOTIFICATION_ON_KEYGUARD = 1;
    private static final long[] VIBRATION_PATTERN = new long[] { 0, 350, 250, 350 };
    private BroadcastReceiver mUserPresentReceiver = null;

    @Override
    public void onCreate() {
        CatLog.d(LOG_TAG, "onCreate()+");
        // Initialize members
        int i = 0;
        mContext = getBaseContext();
        mSimCount = TelephonyManager.from(mContext).getSimCount();
        CatLog.d(LOG_TAG, "simCount: " + mSimCount);
        mStkService = new AppInterface[mSimCount];
        mStkContext = new StkContext[mSimCount];
        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        IntentFilter intent = new IntentFilter();
        intent.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        /* UNISOC: @}*/
        /*UNISOC: Feature for ModemAseert not display text Feature @{*/
        intent.addAction(ACTION_MODEM_CHANGE);
        /*UNISOC: @}*/
        /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
        intent.addAction(Intent.ACTION_USER_SWITCHED);
        /*UNISOC: @}*/
        /*UNISOC: Feature bug for home key @{ */
        intent.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        /*UNISOC: @}*/
        registerReceiver(mReceiver, intent);
        /*UNISOC: Feature for ModemAseert not display text Feature @{*/
        String bootmode = SystemProperties.get("ro.bootmode");
        CatLog.d(LOG_TAG, "bootmode: " + bootmode);
        /*UNISOC: @}*/

        /*UNISOC: bug for black screen @{*/
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        /*UNISOC: @}*/

        for (i = 0; i < mSimCount; i++) {
            CatLog.d(LOG_TAG, "slotId: " + i);
            mStkService[i] = CatService.getInstance(i);
            mStkContext[i] = new StkContext();
            mStkContext[i].mSlotId = i;
            mStkContext[i].mCmdsQ = new LinkedList<DelayedCmd>();
            /*UNISOC: Feature for ModemAseert not display text Feature @{*/
            if(bootmode.equalsIgnoreCase("panic") || bootmode.equalsIgnoreCase("wdgreboot")
                    || bootmode.equalsIgnoreCase("apwdgreboot")
                    || bootmode.equalsIgnoreCase("special")
                    || bootmode.equalsIgnoreCase("unknowreboot")) {
                System.setProperty("gsm.stk.modem.recovery" + i, "1");
            }
            /*UNISOC: @}*/
        }

        Thread serviceThread = new Thread(null, this, "Stk App Service");
        serviceThread.start();
        mNotificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        sInstance = this;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        if (intent == null) {
            CatLog.d(LOG_TAG, "StkAppService onStart intent is null so return");
            return;
        }

        Bundle args = intent.getExtras();
        if (args == null) {
            CatLog.d(LOG_TAG, "StkAppService onStart args is null so return");
            return;
        }
        /*UNISOC: Feature for Cucc function @{*/
        CatLog.d(LOG_TAG, "StkAppService onStart is Cucc: " + isCuccOperator());
        /*UNISOC: @}*/
        int op = args.getInt(OPCODE);
        int slotId = 0;
        int i = 0;
        if (op != OP_BOOT_COMPLETED) {
            slotId = args.getInt(SLOT_ID);
        }
        CatLog.d(LOG_TAG, "onStart sim id: " + slotId + ", op: " + op + ", *****");
        if ((slotId >= 0 && slotId < mSimCount) && mStkService[slotId] == null) {
            mStkService[slotId] = CatService.getInstance(slotId);
            if (mStkService[slotId] == null) {
                CatLog.d(LOG_TAG, "mStkService is: " + mStkContext[slotId].mStkServiceState);
                mStkContext[slotId].mStkServiceState = STATE_NOT_EXIST;
                 /* UNISOC: Feature for Cucc function @{ */
                if(isCuccOperator()){
                    StkAppInstaller.unInstall(mContext,slotId);
                }
                /*UNISOC: @}*/
                //Check other StkService state.
                //If all StkServices are not available, stop itself and uninstall apk.
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                    if (i != slotId
                            && (mStkService[i] != null)
                            && (mStkContext[i].mStkServiceState == STATE_UNKNOWN
                            || mStkContext[i].mStkServiceState == STATE_EXIST)) {
                       break;
                   }
                }
            } else {
                mStkContext[slotId].mStkServiceState = STATE_EXIST;
            }
            if (i == mSimCount) {
                /* UNISOC: Feature for Cucc function @{ */
                if(!isCuccOperator() &&
                        !(mContext.getResources().getBoolean(R.bool.config_show_specific_name))){
                    StkAppInstaller.unInstall(mContext);
                }
                /*UNISOC: @}*/
                stopSelf();
                //StkAppInstaller.unInstall(mContext);
                return;
            }
        }

        waitForLooper();

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = op;
        msg.arg2 = slotId;
        /*UNISOC: Feature for query call forward when send ss @{*/
        if(msg.arg1 == OP_CMD){
            mPhone = PhoneFactory.getPhone(slotId);
            CatLog.d(this, "get mPhone");
        }
        /*UNISOC: @}*/

        switch(msg.arg1) {
        case OP_CMD:
            msg.obj = args.getParcelable(CMD_MSG);
            break;
        case OP_RESPONSE:
        case OP_CARD_STATUS_CHANGED:
        case OP_LOCALE_CHANGED:
        case OP_ALPHA_NOTIFY:
        case OP_IDLE_SCREEN:
            msg.obj = args;
            /* falls through */
        case OP_LAUNCH_APP:
        case OP_END_SESSION:
        case OP_BOOT_COMPLETED:
            break;
        case OP_STOP_TONE_USER:
            msg.obj = args;
            msg.what = STOP_TONE_WHAT;
            break;
        default:
            return;
        }
        mServiceHandler.sendMessage(msg);
    }

    @Override
    public void onDestroy() {
        CatLog.d(LOG_TAG, "onDestroy()");
        /*UNISOC: Feature for REFRESH function @{*/
        if(mToast != null){
            mToast.cancel();
        }
        /*UNISOC: @}*/
        unregisterUserActivityReceiver();
        unregisterProcessObserver();
        unregisterLocaleChangeReceiver();
        sInstance = null;
        waitForLooper();
        mServiceLooper.quit();
        /*UNISOC: Feature porting for Stk Feature @{*/
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        /*UNISOC: @}*/
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void run() {
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }

    /*
     * Package api used by StkMenuActivity to indicate if its on the foreground.
     */
    void indicateMenuVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mMenuIsVisible = visibility;
        }
    }

    /*
     * Package api used by StkDialogActivity to indicate if its on the foreground.
     */
    void setDisplayTextDlgVisibility(boolean visibility, int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            mStkContext[slotId].mDisplayTextDlgIsVisibile = visibility;
        }
    }

    boolean isInputPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isInputFinishBySrv: " + mStkContext[slotId].mIsInputPending);
            return mStkContext[slotId].mIsInputPending;
        }
        return false;
    }

    boolean isMenuPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isMenuPending: " + mStkContext[slotId].mIsMenuPending);
            return mStkContext[slotId].mIsMenuPending;
        }
        return false;
    }

    boolean isDialogPending(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            CatLog.d(LOG_TAG, "isDialogPending: " + mStkContext[slotId].mIsDialogPending);
            return mStkContext[slotId].mIsDialogPending;
        }
        return false;
    }

    boolean isMainMenuAvailable(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            // The main menu can handle the next user operation if the previous session finished.
            return (mStkContext[slotId].lastSelectedItem == null) ? true : false;
        }
        return false;
    }

    /*
     * Package api used by StkMenuActivity to get its Menu parameter.
     */
    Menu getMenu(int slotId) {
        CatLog.d(LOG_TAG, "StkAppService, getMenu, sim id: " + slotId);
        if (slotId >=0 && slotId < mSimCount) {
            return mStkContext[slotId].mCurrentMenu;
        } else {
            return null;
        }
    }

    /*
     * Package api used by StkMenuActivity to get its Main Menu parameter.
     */
    Menu getMainMenu(int slotId) {
        CatLog.d(LOG_TAG, "StkAppService, getMainMenu, sim id: " + slotId);
        if (slotId >=0 && slotId < mSimCount && (mStkContext[slotId].mMainCmd != null)) {
            Menu menu = mStkContext[slotId].mMainCmd.getMenu();
            if (menu != null && mSimCount > PhoneConstants.MAX_PHONE_COUNT_SINGLE_SIM) {
                // If alpha identifier or icon identifier with the self-explanatory qualifier is
                // specified in SET-UP MENU command, it should be more prioritized than preset ones.
                if (menu.title == null
                        && (menu.titleIcon == null || !menu.titleIconSelfExplanatory)) {
                    StkMenuConfig config = StkMenuConfig.getInstance(getApplicationContext());
                    String label = config.getLabel(slotId);
                    Bitmap icon = config.getIcon(slotId);
                    if (label != null || icon != null) {
                        Parcel parcel = Parcel.obtain();
                        menu.writeToParcel(parcel, 0);
                        parcel.setDataPosition(0);
                        menu = Menu.CREATOR.createFromParcel(parcel);
                        parcel.recycle();
                        menu.title = label;
                        menu.titleIcon = icon;
                        menu.titleIconSelfExplanatory = false;
                    }
                }
            }
            return menu;
        } else {
            return null;
        }
    }

    /*
     * Package api used by UI Activities and Dialogs to communicate directly
     * with the service to deliver state information and parameters.
     */
    static StkAppService getInstance() {
        return sInstance;
    }

    private void waitForLooper() {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    private final class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if(null == msg) {
                CatLog.d(LOG_TAG, "ServiceHandler handleMessage msg is null");
                return;
            }
            int opcode = msg.arg1;
            int slotId = msg.arg2;

            CatLog.d(LOG_TAG, "handleMessage opcode[" + opcode + "], sim id[" + slotId + "]");
            if (opcode == OP_CMD && msg.obj != null &&
                    ((CatCmdMessage)msg.obj).getCmdType()!= null) {
                CatLog.d(LOG_TAG, "cmdName[" + ((CatCmdMessage)msg.obj).getCmdType().name() + "]");
            }
            mStkContext[slotId].mOpCode = opcode;
            switch (opcode) {
            case OP_LAUNCH_APP:
                if (mStkContext[slotId].mMainCmd == null) {
                    CatLog.d(LOG_TAG, "mMainCmd is null");
                    // nothing todo when no SET UP MENU command didn't arrive.
                    return;
                }
                CatLog.d(LOG_TAG, "handleMessage OP_LAUNCH_APP - mCmdInProgress[" +
                        mStkContext[slotId].mCmdInProgress + "]");
                /*UNISOC: Feature bug for home key @{*/
                mHomePressedFlg = false;
                /*UNISOC: @}*/
                //If there is a pending activity for the slot id,
                //just finish it and create a new one to handle the pending command.
                cleanUpInstanceStackBySlot(slotId);

                CatLog.d(LOG_TAG, "Current cmd type: " +
                        mStkContext[slotId].mCurrentCmd.getCmdType());
                //Restore the last command from stack by slot id.
                restoreInstanceFromStackBySlot(slotId);
                break;
            case OP_CMD:
                CatLog.d(LOG_TAG, "[OP_CMD]");
                CatCmdMessage cmdMsg = (CatCmdMessage) msg.obj;
                /*UNISOC: Feature for orange Feature @{*/
                //unisoc patch SPCSS00430242 begin
                if (mContext.getResources().getBoolean(R.bool.config_support_authentification)) {
                    if (isStkDialogActivated() && cmdMsg.getCmdType().value()
                            == AppInterface.CommandType.DISPLAY_TEXT.value()) {
                        CatLog.d(LOG_TAG, "[OP_CMD] the second display_text slotId: " + slotId);
                        Intent intent = new Intent(CLOSE_DIALOG_ACTIVITY);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        intent.putExtra(SLOT_ID, slotId);
                        mContext.sendBroadcast(intent);
                    }
                }
                //unisoc patch SPCSS00430242 end
                /*UNISOC: @}*/
                // There are two types of commands:
                // 1. Interactive - user's response is required.
                // 2. Informative - display a message, no interaction with the user.
                //
                // Informative commands can be handled immediately without any delay.
                // Interactive commands can't override each other. So if a command
                // is already in progress, we need to queue the next command until
                // the user has responded or a timeout expired.
                if (!isCmdInteractive(cmdMsg)) {
                    handleCmd(cmdMsg, slotId);
                } else {
                    if (!mStkContext[slotId].mCmdInProgress) {
                        mStkContext[slotId].mCmdInProgress = true;
                        handleCmd((CatCmdMessage) msg.obj, slotId);
                    } else {
                        CatLog.d(LOG_TAG, "[Interactive][in progress]");
                        mStkContext[slotId].mCmdsQ.addLast(new DelayedCmd(OP_CMD,
                                (CatCmdMessage) msg.obj, slotId));
                    }
                }
                break;
            case OP_RESPONSE:
                handleCmdResponse((Bundle) msg.obj, slotId);
                // call delayed commands if needed.
                if (mStkContext[slotId].mCmdsQ.size() != 0) {
                    callDelayedMsg(slotId);
                } else {
                    mStkContext[slotId].mCmdInProgress = false;
                }
                break;
            case OP_END_SESSION:
                if (!mStkContext[slotId].mCmdInProgress) {
                    mStkContext[slotId].mCmdInProgress = true;
                    handleSessionEnd(slotId);
                } else {
                    mStkContext[slotId].mCmdsQ.addLast(
                            new DelayedCmd(OP_END_SESSION, null, slotId));
                }
                break;
            case OP_BOOT_COMPLETED:
                CatLog.d(LOG_TAG, " OP_BOOT_COMPLETED");
                int i = 0;
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                     /* UNISOC: Feature for Cucc function @{ */
                    if(isCuccOperator() && mStkContext[i].mMainCmd == null){
                        StkAppInstaller.unInstall(mContext,i);
                    }
                    /*UNISOC: @}*/
                    if (mStkContext[i].mMainCmd != null) {
                        break;
                    }
                }
                if (i == mSimCount) {
                     /* UNISOC: Feature for Cucc function @{ */
                    //StkAppInstaller.unInstall(mContext);
                    if(!isCuccOperator()
                            && !(mContext.getResources().getBoolean(R.bool.config_show_specific_name))){
                        StkAppInstaller.unInstall(mContext);
                    }
                    /*UNISOC: @}*/
                }
                break;
            case OP_DELAYED_MSG:
                handleDelayedCmd(slotId);
                break;
            case OP_CARD_STATUS_CHANGED:
                CatLog.d(LOG_TAG, "Card/Icc Status change received");
                handleCardStatusChangeAndIccRefresh((Bundle) msg.obj, slotId);
                break;
            case OP_SET_ACT_INST:
                Activity act = (Activity) msg.obj;
                if (mStkContext[slotId].mActivityInstance != act) {
                    CatLog.d(LOG_TAG, "Set pending activity instance - " + act);
                    Activity previous = mStkContext[slotId].mActivityInstance;
                    mStkContext[slotId].mActivityInstance = act;
                    // Finish the previous one if it was replaced with new one
                    // but it has not been finished yet somehow.
                    if (act != null && previous != null && !previous.isDestroyed()
                            && !previous.isFinishing()) {
                        CatLog.d(LOG_TAG, "Finish the previous pending activity - " + previous);
                        previous.finish();
                    }
                    // Pending activity is registered in the following 2 scnarios;
                    // A. TERMINAL RESPONSE was sent to the card.
                    // B. Activity was moved to the background before TR is sent to the card.
                    // No need to observe idle screen for the pending activity in the scenario A.
                    if (act != null && mStkContext[slotId].mCmdInProgress) {
                        startToObserveIdleScreen(slotId);
                    } else {
                        if (mStkContext[slotId].mCurrentCmd != null) {
                            unregisterProcessObserver(
                                    mStkContext[slotId].mCurrentCmd.getCmdType(), slotId);
                        }
                    }
                }
                break;
            case OP_SET_DAL_INST:
                Activity dal = (Activity) msg.obj;
                if (mStkContext[slotId].mDialogInstance != dal) {
                    CatLog.d(LOG_TAG, "Set pending dialog instance - " + dal);
                    mStkContext[slotId].mDialogInstance = dal;
                    if (dal != null) {
                        startToObserveIdleScreen(slotId);
                    } else {
                        if (mStkContext[slotId].mCurrentCmd != null) {
                            unregisterProcessObserver(
                                    mStkContext[slotId].mCurrentCmd.getCmdType(), slotId);
                        }
                    }
                }
                break;
            case OP_SET_IMMED_DAL_INST:
                Activity immedDal = (Activity) msg.obj;
                CatLog.d(LOG_TAG, "Set dialog instance for immediate response. " + immedDal);
                mStkContext[slotId].mImmediateDialogInstance = immedDal;
                break;
            case OP_LOCALE_CHANGED:
                CatLog.d(this, "Locale Changed");
                for (int slot = PhoneConstants.SIM_ID_1; slot < mSimCount; slot++) {
                    checkForSetupEvent(LANGUAGE_SELECTION_EVENT, (Bundle) msg.obj, slot);
                }
                // rename all registered notification channels on locale change
                createAllChannels();
                break;
            case OP_ALPHA_NOTIFY:
                handleAlphaNotify((Bundle) msg.obj);
                break;
            case OP_IDLE_SCREEN:
               for (int slot = 0; slot < mSimCount; slot++) {
                    if (mStkContext[slot] != null) {
                        handleIdleScreen(slot);
                    }
                }
                break;
            case OP_STOP_TONE_USER:
            case OP_STOP_TONE:
                CatLog.d(this, "Stop tone");
                handleStopTone(msg, slotId);
                break;
            case OP_USER_ACTIVITY:
                for (int slot = PhoneConstants.SIM_ID_1; slot < mSimCount; slot++) {
                    checkForSetupEvent(USER_ACTIVITY_EVENT, null, slot);
                }
                break;
            /*UNISOC: bug for black screen @{*/
            case OP_DELAY_TO_CHECK_USER_UNLOCK:
                launchTextDialog(slotId);
                break;
            /*UNISOC: @}*/
            }
        }

        private void handleCardStatusChangeAndIccRefresh(Bundle args, int slotId) {
            boolean cardStatus = args.getBoolean(AppInterface.CARD_STATUS);

            CatLog.d(LOG_TAG, "CardStatus: " + cardStatus);
            if (cardStatus == false) {
                CatLog.d(LOG_TAG, "CARD is ABSENT");
                // Uninstall STKAPP, Clear Idle text, Stop StkAppService
                cancelIdleText(slotId);
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
                /*UNISOC: Feature bug for Stk Feature @{*/
                mNotificationManager.cancel(getNotificationId(slotId));
                /*UNISOC: @}*/
                /*UNISOC: Feature for Cucc function @{*/
                if(isCuccOperator()){
                    StkAppInstaller.unInstall(mContext,slotId);
                }
                /*UNISOC: @}*/

                /*UNISOC: Feature bug611551 for Stk Feature @{*/
                /* Turn off/on sim card, StkService in FW will be recreate but App handle the old object */
                mStkService[slotId] = null;
                CatLog.d(LOG_TAG, "mStkContext[slotId]: " + mStkContext[slotId]);
                mStkContext[slotId].reset();
                /*UNISOC: @}*/

                /*UNISOC: Bug for 1395367 @{*/
                Activity dialog = mStkContext[slotId].getPendingDialogInstance();
                Activity activity = mStkContext[slotId].getPendingActivityInstance();
                if (dialog != null) {
                    dialog.finish();
                    CatLog.d(LOG_TAG, "dialog.finish");
                    mStkContext[slotId].mDialogInstance = null;
                } else if (activity != null) {
                    activity.finish();
                    CatLog.d(LOG_TAG, "activity.finish");
                    mStkContext[slotId].mActivityInstance = null;
                }
                /*UNISOC: @}*/
                /* UNISOC: For bug 1466545 @{ */
                Intent intent = new Intent(CARD_ABSENT);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(SLOT_ID, slotId);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                /* UNISOC: For bug 1466545 @} */

            } else {
                IccRefreshResponse state = new IccRefreshResponse();
                state.refreshResult = args.getInt(AppInterface.REFRESH_RESULT);

                CatLog.d(LOG_TAG, "Icc Refresh Result: "+ state.refreshResult);
                /*UNISOC: Feature for REFRESH function @{*/
                launchRefreshMsg(slotId);
                /*UNISOC: @}*/
                if ((state.refreshResult == IccRefreshResponse.REFRESH_RESULT_INIT) ||
                    (state.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET)) {
                    // Clear Idle Text
                    cancelIdleText(slotId);
                }
            }
        }
    }
    /*
     * Check if all SIMs are absent except the id of slot equals "slotId".
     */
     boolean isAllOtherCardsAbsent(int slotId) {
        TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        int i = 0;

        for (i = 0; i < mSimCount; i++) {
            if (i != slotId && mTm.hasIccCard(i)) {
                break;
            }
        }
        if (i == mSimCount) {
            return true;
        } else {
            return false;
        }
    }

    /* package */ boolean isScreenIdle() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<RunningTaskInfo> tasks = am.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }

        String top = tasks.get(0).topActivity.getPackageName();
        if (top == null) {
            return false;
        }

        // We can assume that the screen is idle if the home application is in the foreground.
        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_HOME);

        ResolveInfo info = getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (info != null) {
            if (top.equals(info.activityInfo.packageName)) {
                return true;
            }
        }

        return false;
    }

    private void startToObserveIdleScreen(int slotId) {
        if (!mStkContext[slotId].mIsSessionFromUser) {
            if (!isScreenIdle()) {
                synchronized (this) {
                    if (mProcessObserver == null && !mServiceHandler.hasMessages(OP_IDLE_SCREEN)) {
                        registerProcessObserver();
                    }
                }
            } else {
                handleIdleScreen(slotId);
            }
        }
    }

    private void handleIdleScreen(int slotId) {
        // It might be hard for user to recognize that the dialog or screens belong to SIM Toolkit
        // application if the current session was not initiated by user but by the SIM card,
        // so it is recommended to send TERMINAL RESPONSE if user goes to the idle screen.
        if (!mStkContext[slotId].mIsSessionFromUser) {
            Activity dialog = mStkContext[slotId].getPendingDialogInstance();
            if (dialog != null) {
                dialog.finish();
                mStkContext[slotId].mDialogInstance = null;
            }
            Activity activity = mStkContext[slotId].getPendingActivityInstance();
            if (activity != null) {
                activity.finish();
                mStkContext[slotId].mActivityInstance = null;
            }
        }
        // If the idle screen event is present in the list need to send the
        // response to SIM.
        CatLog.d(this, "Need to send IDLE SCREEN Available event to SIM");
        checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null, slotId);

        if (mStkContext[slotId].mIdleModeTextCmd != null
                && !mStkContext[slotId].mIdleModeTextVisible) {
            launchIdleText(slotId);
        }
    }

    private void sendScreenBusyResponse(int slotId) {
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }
        /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
        mStkContext[slotId].mDisplayTextResponsed = true;
        /*UNISOC: @}*/
        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);
        CatLog.d(this, "SCREEN_BUSY");
        resMsg.setResultCode(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        mStkService[slotId].onCmdResponse(resMsg);
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            callDelayedMsg(slotId);
        } else {
            mStkContext[slotId].mCmdInProgress = false;
        }
    }

    /**
     * Sends TERMINAL RESPONSE or ENVELOPE
     *
     * @param args detailed parameters of the response
     * @param slotId slot identifier
     */
    public void sendResponse(Bundle args, int slotId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_RESPONSE;
        msg.arg2 = slotId;
        msg.obj = args;
        mServiceHandler.sendMessage(msg);
    }

    private void sendResponse(int resId, int slotId, boolean confirm) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_RESPONSE;
        msg.arg2 = slotId;
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        args.putBoolean(StkAppService.CONFIRMATION, confirm);
        msg.obj = args;
        mServiceHandler.sendMessage(msg);
    }

    private boolean isCmdInteractive(CatCmdMessage cmd) {
        switch (cmd.getCmdType()) {
        case SEND_DTMF:
        case SEND_SMS:
        case REFRESH:
        case RUN_AT:
        case SEND_SS:
        case SEND_USSD:
        case SET_UP_IDLE_MODE_TEXT:
        case SET_UP_MENU:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
        case SET_UP_EVENT_LIST:
            return false;
        }

        return true;
    }

    private void handleDelayedCmd(int slotId) {
        CatLog.d(LOG_TAG, "handleDelayedCmd, slotId: " + slotId);
        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            DelayedCmd cmd = mStkContext[slotId].mCmdsQ.poll();
            if (cmd != null) {
                CatLog.d(LOG_TAG, "handleDelayedCmd - queue size: " +
                        mStkContext[slotId].mCmdsQ.size() +
                        " id: " + cmd.id + "sim id: " + cmd.slotId);
                switch (cmd.id) {
                case OP_CMD:
                    handleCmd(cmd.msg, cmd.slotId);
                    break;
                case OP_END_SESSION:
                    handleSessionEnd(cmd.slotId);
                    break;
                }
            }
        }
    }

    private void callDelayedMsg(int slotId) {
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_DELAYED_MSG;
        msg.arg2 = slotId;
        mServiceHandler.sendMessage(msg);
    }

    private void callSetActivityInstMsg(int inst_type, int slotId, Object obj) {
        Message msg = mServiceHandler.obtainMessage();
        msg.obj = obj;
        msg.arg1 = inst_type;
        msg.arg2 = slotId;
        mServiceHandler.sendMessage(msg);
    }

    private void handleSessionEnd(int slotId) {
        // We should finish all pending activity if receiving END SESSION command.
        cleanUpInstanceStackBySlot(slotId);

        mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
        CatLog.d(LOG_TAG, "[handleSessionEnd] - mCurrentCmd changed to mMainCmd!.");
        mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mMainCmd;
        CatLog.d(LOG_TAG, "slotId: " + slotId + ", mMenuState: " +
                mStkContext[slotId].mMenuState);

        mStkContext[slotId].mIsInputPending = false;
        mStkContext[slotId].mIsMenuPending = false;
        mStkContext[slotId].mIsDialogPending = false;
        mStkContext[slotId].mNoResponseFromUser = false;
        /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
        mStkContext[slotId].mDisplayTextResponsed = false;
        /*UNISOC: @}*/

        if (mStkContext[slotId].mMainCmd == null) {
            CatLog.d(LOG_TAG, "[handleSessionEnd][mMainCmd is null!]");
        }
        mStkContext[slotId].lastSelectedItem = null;
        mStkContext[slotId].mIsSessionFromUser = false;
        // In case of SET UP MENU command which removed the app, don't
        // update the current menu member.
        if (mStkContext[slotId].mCurrentMenu != null && mStkContext[slotId].mMainCmd != null) {
            mStkContext[slotId].mCurrentMenu = mStkContext[slotId].mMainCmd.getMenu();
        }
        CatLog.d(LOG_TAG, "[handleSessionEnd][mMenuState]" + mStkContext[slotId].mMenuIsVisible);

        if (StkMenuActivity.STATE_SECONDARY == mStkContext[slotId].mMenuState) {
            mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
        }

        // Send a local broadcast as a notice that this service handled the session end event.
        Intent intent = new Intent(SESSION_ENDED);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(SLOT_ID, slotId);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        if (mStkContext[slotId].mCmdsQ.size() != 0) {
            callDelayedMsg(slotId);
        } else {
            mStkContext[slotId].mCmdInProgress = false;
        }
//        // In case a launch browser command was just confirmed, launch that url.
//        if (mStkContext[slotId].launchBrowser) {
//            mStkContext[slotId].launchBrowser = false;
//            launchBrowser(mStkContext[slotId].mBrowserSettings);
//        }
    }

    // returns true if any Stk related activity already has focus on the screen
    boolean isTopOfStack() {
        ActivityManager mActivityManager = (ActivityManager) mContext
                .getSystemService(ACTIVITY_SERVICE);
        String currentPackageName = null;
        List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks == null || tasks.isEmpty() || tasks.get(0).topActivity == null) {
            return false;
        }
        currentPackageName = tasks.get(0).topActivity.getPackageName();
        CatLog.d(LOG_TAG, "currentPackageName : " + currentPackageName);
        if (null != currentPackageName) {
            return currentPackageName.equals(PACKAGE_NAME);
        }
        return false;
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @param slotId slot ID.
     * @return boolean value of corresponding key.
     */
    private boolean getBooleanCarrierConfig(String key, int slotId) {
        CarrierConfigManager ccm = (CarrierConfigManager) getSystemService(CARRIER_CONFIG_SERVICE);
        SubscriptionManager sm = (SubscriptionManager) getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        PersistableBundle b = null;
        if (ccm != null && sm != null) {
            SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(slotId);
            if (info != null) {
                b = ccm.getConfigForSubId(info.getSubscriptionId());
            }
        }
        if (b != null) {
            return b.getBoolean(key);
        }
        // Return static default defined in CarrierConfigManager.
        return CarrierConfigManager.getDefaultConfig().getBoolean(key);
    }

    private void handleCmd(CatCmdMessage cmdMsg, int slotId) {

        if (cmdMsg == null) {
            return;
        }
        // save local reference for state tracking.
        mStkContext[slotId].mCurrentCmd = cmdMsg;
        boolean waitForUsersResponse = true;

        mStkContext[slotId].mIsInputPending = false;
        mStkContext[slotId].mIsMenuPending = false;
        mStkContext[slotId].mIsDialogPending = false;

        CatLog.d(LOG_TAG,"[handleCmd]" + cmdMsg.getCmdType().name());
        switch (cmdMsg.getCmdType()) {
        case DISPLAY_TEXT:
            TextMessage msg = cmdMsg.geTextMessage();
            waitForUsersResponse = msg.responseNeeded;
            /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
            mStkContext[slotId].mDisplayTextResponsed = false;
            if( mCurrentUserId != UserHandle.USER_OWNER){
                CatLog.d(LOG_TAG, "secondary users,need send TR");
                sendResponse(RES_ID_CONFIRM, slotId, true);
                break;
            }
             /*UNISOC: @}*/
            if (mStkContext[slotId].lastSelectedItem != null) {
                msg.title = mStkContext[slotId].lastSelectedItem;
            } else if (mStkContext[slotId].mMainCmd != null){
                if (getResources().getBoolean(R.bool.show_menu_title_only_on_menu)) {
                    msg.title = mStkContext[slotId].mMainCmd.getMenu().title;
                }
            }
            /*UNISOC: Feature for ModemAseert not display text Feature @{*/
            CatLog.d(LOG_TAG, " Is Modem Assert Happen: " +
                    System.getProperty("gsm.stk.modem.recovery" + slotId));
            if("1".equals(System.getProperty("gsm.stk.modem.recovery" + slotId))) {
                sendResponse(RES_ID_CONFIRM, slotId, true);
                break;
            }

            /*UNISOC: Bug for 1390516 @{ */
            boolean isOtherCardReady = false;
            TelephonyManager tm = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
            for (int i = 0; i < mSimCount; i++) {
                if ((i != slotId) && (tm.getSimState(i) != TelephonyManager.SIM_STATE_ABSENT)
                        && (!isCardReady(mContext, i))) {
                    CatLog.d(LOG_TAG, "The other card is still locked by pin code");
                    isOtherCardReady = true;
                }
            }
            if (isOtherCardReady) {
                sendResponse(RES_ID_CONFIRM, slotId, true);
                break;
            }
            /*UNISOC: @}*/

            /*UNISOC: @}*/
            /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
            if (isAirPlaneModeOn()) {
                CatLog.d(LOG_TAG, "Air Plane Mode On");
                sendResponse(RES_ID_CONFIRM, slotId, false);
                break;
            }
            /*UNISOC: @}*/
            /*UNISOC: Feature bug @{*/
            if (isBusyOnCall() && isCallInStack()){
                sendResponse(RES_ID_CONFIRM, slotId, false);
                break;
            }
            /*UNISOC: @}*/
            //If we receive a low priority Display Text and the device is
            // not displaying any STK related activity and the screen is not idle
            // ( that is, device is in an interactive state), then send a screen busy
            // terminal response. Otherwise display the message. The existing
            // displayed message shall be updated with the new display text
            // proactive command (Refer to ETSI TS 102 384 section 27.22.4.1.4.4.2).
            if (!(msg.isHighPriority || mStkContext[slotId].mMenuIsVisible
                    || mStkContext[slotId].mDisplayTextDlgIsVisibile || isTopOfStack())) {
                /*UNISOC: Feature for Idle Mode(case 27.22.4.22.2/4) @{ */
                if(isBusyOnCall()) {
                    CatLog.d(LOG_TAG, "Screen is not idle");
                    sendScreenBusyResponse(slotId);
                } else {
                    launchTextDialog(slotId);
                }
            } else {
                launchTextDialog(slotId);
            }
            break;
        case SELECT_ITEM:
            CatLog.d(LOG_TAG, "SELECT_ITEM +");
            /*UNISOC: Feature bug @{*/
            waitForUsersResponse = false;
            /*UNISOC: @}*/
            mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenu = cmdMsg.getMenu();
            launchMenuActivity(cmdMsg.getMenu(), slotId);
            break;
        case SET_UP_MENU:
            mStkContext[slotId].mCmdInProgress = false;
            mStkContext[slotId].mMainCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenuCmd = mStkContext[slotId].mCurrentCmd;
            mStkContext[slotId].mCurrentMenu = cmdMsg.getMenu();
            CatLog.d(LOG_TAG, "SET_UP_MENU [" + removeMenu(slotId) + "]");

            if (removeMenu(slotId)) {
                int i = 0;
                CatLog.d(LOG_TAG, "removeMenu() - Uninstall App");
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
                /* UNISOC: Feature for Cucc function @{ */
                if (isCuccOperator()) {
                    StkAppInstaller.unInstall(mContext,slotId);
                }
                /*UNISOC: @}*/
                //Check other setup menu state. If all setup menu are removed, uninstall apk.
                for (i = PhoneConstants.SIM_ID_1; i < mSimCount; i++) {
                    /* UNISOC: Feature bug @{ */
                    if (i != slotId
                            && (mStkContext[i].mSetupMenuState == STATE_UNKNOWN
                            || mStkContext[i].mSetupMenuState == STATE_EXIST) && mStkContext[i].mCurrentMenu != null) {
                        /*UNISOC: @}*/
                        CatLog.d(LOG_TAG, "Not Uninstall App:" + i + ","
                                + mStkContext[i].mSetupMenuState);
                        break;
                    }
                }
                if (i == mSimCount) {
                    //StkAppInstaller.unInstall(mContext);
                    /* UNISOC: Feature for Cucc function @{ */
                    if(!isCuccOperator()){
                        CatLog.d(LOG_TAG, "unstall App");
                        StkAppInstaller.unInstall(mContext);
                    }
                    /*UNISOC: @}*/
                }
            } else {
                /* UNISOC: Feature for Cucc function @{ */
                //StkAppInstaller.install(mContext);
                if (!isAirPlaneModeOn()) {
                    if (isCuccOperator()) {
                        if (isCardReady(mContext, slotId)) {
                            StkAppInstaller.install(mContext, slotId);
                        }
                    } else {
                        if (isCardReady(mContext)) {
                            if (mContext.getResources().getBoolean(R.bool.config_show_specific_name)) {
                                StkAppInstaller.unInstall(mContext);
                                StkAppInstaller.install(mContext);
                            } else {
                                StkAppInstaller.install(mContext);
                            }
                        }
                    }
                }
                /*UNISOC: @}*/
            }
            if (mStkContext[slotId].mMenuIsVisible) {
                launchMenuActivity(null, slotId);
            }
            break;
        case GET_INPUT:
        case GET_INKEY:
            launchInputActivity(slotId);
            break;
        case SET_UP_IDLE_MODE_TEXT:
            waitForUsersResponse = false;
            mStkContext[slotId].mIdleModeTextCmd = mStkContext[slotId].mCurrentCmd;
            TextMessage idleModeText = mStkContext[slotId].mCurrentCmd.geTextMessage();
            if (idleModeText == null || TextUtils.isEmpty(idleModeText.text)) {
                cancelIdleText(slotId);
            }
            mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
            if (mStkContext[slotId].mIdleModeTextCmd != null) {
                /*UNISOC: Feature for Idle Mode(case 27.22.4.22.2/4) @{*/
                if ((mStkContext[slotId].mIdleModeTextCmd != null) && /*isScreenIdle()*/!isBusyOnCall()) {
                /*UNISOC: @}*/
                    CatLog.d(this, "set up idle mode");
                    launchIdleText(slotId);
                } else {
                    registerProcessObserver();
                }
            }
            break;
        case SEND_DTMF:
        case SEND_SMS:
        //case REFRESH:
        case RUN_AT:
        //case SEND_SS:
        case SEND_USSD:
        case GET_CHANNEL_STATUS:
            waitForUsersResponse = false;
            launchEventMessage(slotId);
            break;
        /*UNISOC: Feature for query call forward when send ss @{*/
        case SEND_SS:
            waitForUsersResponse = false;
            launchEventMessage(slotId);
            mPhone.getCallForwardingOption(CommandsInterface.CF_REASON_UNCONDITIONAL,null);
            break;
        /*UNISOC: @}*/
        case LAUNCH_BROWSER:
            /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
            mStkContext[slotId].mDisplayTextResponsed = false;
            /*UNISOC: @}*/

            // The device setup process should not be interrupted by launching browser.
            if (Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
                CatLog.d(this, "The command is not performed if the setup has not been completed.");
                sendScreenBusyResponse(slotId);
                break;
            }

            /* Check if Carrier would not want to launch browser */
            if (getBooleanCarrierConfig(CarrierConfigManager.KEY_STK_DISABLE_LAUNCH_BROWSER_BOOL,
                    slotId)) {
                CatLog.d(this, "Browser is not launched as per carrier.");
                sendResponse(RES_ID_DONE, slotId, true);
                break;
            }

            mStkContext[slotId].mBrowserSettings =
                    mStkContext[slotId].mCurrentCmd.getBrowserSettings();
            /*UNISOC: Feature for LAUNCH_BROWSER, all secondary users @{ */
            if (!isUrlAvailableToLaunchBrowser(mStkContext[slotId].mBrowserSettings) &&
                    mStkContext[slotId].mBrowserSettings.mode != LaunchBrowserMode.LAUNCH_NEW_BROWSER) {
            /*UNISOC: @}*/
                CatLog.d(this, "Browser url property is not set - send error");
                sendResponse(RES_ID_ERROR, slotId, true);
            } else {
                TextMessage alphaId = mStkContext[slotId].mCurrentCmd.geTextMessage();
                CatLog.d(this, "alphaId: " + alphaId);
                if ((alphaId == null) || TextUtils.isEmpty(alphaId.text)) {
                    // don't need user confirmation in this case
                    // just launch the browser or spawn a new tab
                    CatLog.d(this, "user confirmation is not currently needed.\n" +
                            "supressing confirmation dialogue and confirming silently...");
                    mStkContext[slotId].launchBrowser = true;
                    sendResponse(RES_ID_CONFIRM, slotId, true);
                } else {
                    launchConfirmationDialog(alphaId, slotId);
                }
            }
            break;
        case SET_UP_CALL:
            TextMessage mesg = mStkContext[slotId].mCurrentCmd.getCallSettings().confirmMsg;
            if((mesg != null) && (mesg.text == null || mesg.text.length() == 0)) {
                mesg.text = getResources().getString(R.string.default_setup_call_msg);
            }
            CatLog.d(this, "SET_UP_CALL mesg.text " + mesg.text);
            /*UNISOC: Feature for SET_UP_CALL @{ */
            //launchConfirmationDialog(mesg, slotId);
            processSetupCall(slotId);
            /*UNISOC: @}*/
            break;
        case PLAY_TONE:
            handlePlayTone(slotId);
            break;
        case OPEN_CHANNEL:
            launchOpenChannelDialog(slotId);
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            TextMessage m = mStkContext[slotId].mCurrentCmd.geTextMessage();

            if ((m != null) && (m.text == null)) {
                switch(cmdMsg.getCmdType()) {
//                case CLOSE_CHANNEL:
//                    m.text = getResources().getString(R.string.default_close_channel_msg);
//                    break;
                case RECEIVE_DATA:
                    m.text = getResources().getString(R.string.default_receive_data_msg);
                    break;
                case SEND_DATA:
                    m.text = getResources().getString(R.string.default_send_data_msg);
                    break;
                }
            }
            /*
             * Display indication in the form of a toast to the user if required.
             */
            launchEventMessage(slotId, m);
            break;
        case SET_UP_EVENT_LIST:
            replaceEventList(slotId);
            if (isScreenIdle()) {
                CatLog.d(this," Check if IDLE_SCREEN_AVAILABLE_EVENT is present in List");
                checkForSetupEvent(IDLE_SCREEN_AVAILABLE_EVENT, null, slotId);
            }
            break;
        /*UNISOC: Feature for REFRESH function @{*/
        case REFRESH:
            int cmdQualifier = mStkContext[slotId].mCurrentCmd.getCommandQualifier();
            CatLog.d(LOG_TAG, "REFRESH cmdQualifier: " + cmdQualifier);
            if (cmdQualifier == REFRESH_UICC_RESET) {
                /* UNISOC: Feature for Cucc function @{ */
                Intent intent = new Intent(REFRESH_UNINSTALL);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                LocalBroadcastManager.getInstance(mContext).sendBroadcast(intent);
                if(isCuccOperator()){
                    StkAppInstaller.unInstall(mContext,slotId);
                }
                /*UNISOC: @}*/
                if (isAllOtherCardsAbsent(slotId)) {
                    if(!isCuccOperator()){
                        StkAppInstaller.unInstall(mContext);
                    }
                }
                mStkContext[slotId].mCurrentMenu = null;
                mStkContext[slotId].mMainCmd = null;
            }
            break;
        /*UNISOC: @}*/
        }

        if (!waitForUsersResponse) {
            if (mStkContext[slotId].mCmdsQ.size() != 0) {
                callDelayedMsg(slotId);
            } else {
                mStkContext[slotId].mCmdInProgress = false;
            }
        }
    }

    @SuppressWarnings("FallThrough")
    private void handleCmdResponse(Bundle args, int slotId) {
        CatLog.d(LOG_TAG, "handleCmdResponse, sim id: " + slotId);
        if (mStkContext[slotId].mCurrentCmd == null) {
            return;
        }

        if (mStkService[slotId] == null) {
            mStkService[slotId] = CatService.getInstance(slotId);
            if (mStkService[slotId] == null) {
                // This should never happen (we should be responding only to a message
                // that arrived from StkService). It has to exist by this time
                CatLog.d(LOG_TAG, "Exception! mStkService is null when we need to send response.");
                //throw new RuntimeException("mStkService is null when we need to send response");
                return;
            }
        }

        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);

        // set result code
        boolean helpRequired = args.getBoolean(HELP, false);
        boolean confirmed    = false;

        switch(args.getInt(RES_ID)) {
        case RES_ID_MENU_SELECTION:
            CatLog.d(LOG_TAG, "MENU_SELECTION=" + mStkContext[slotId].
                    mCurrentMenuCmd.getCmdType());
            int menuSelection = args.getInt(MENU_SELECTION);
            switch(mStkContext[slotId].mCurrentMenuCmd.getCmdType()) {
            case SET_UP_MENU:
                mStkContext[slotId].mIsSessionFromUser = true;
                // Fall through
            case SELECT_ITEM:
                mStkContext[slotId].lastSelectedItem = getItemName(menuSelection, slotId);
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                }
                resMsg.setMenuSelection(menuSelection);
                break;
            }
            break;
        case RES_ID_INPUT:
            CatLog.d(LOG_TAG, "RES_ID_INPUT");
            String input = args.getString(INPUT);
            if (input != null && (null != mStkContext[slotId].mCurrentCmd.geInput()) &&
                    (mStkContext[slotId].mCurrentCmd.geInput().yesNo)) {
                boolean yesNoSelection = input
                        .equals(StkInputActivity.YES_STR_RESPONSE);
                resMsg.setYesNo(yesNoSelection);
            } else {
                if (helpRequired) {
                    resMsg.setResultCode(ResultCode.HELP_INFO_REQUIRED);
                } else {
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                    resMsg.setInput(input);
                }
            }
            break;
        case RES_ID_CONFIRM:
            CatLog.d(this, "RES_ID_CONFIRM");
            confirmed = args.getBoolean(CONFIRMATION);
            switch (mStkContext[slotId].mCurrentCmd.getCmdType()) {
            case DISPLAY_TEXT:
                if (confirmed) {
                    resMsg.setResultCode(mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ?
                            ResultCode.PRFRMD_ICON_NOT_DISPLAYED : ResultCode.OK);
                } else {
                    resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
                }
                /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
                mStkContext[slotId].mDisplayTextResponsed = true;
                /*UNISOC: @}*/
                break;
            case LAUNCH_BROWSER:
                /*UNISOC: Feature bug for LaunchBrowser @{*/
                mCustomLaunchBrowserTR = getBooleanCarrierConfig(CarrierConfigManagerEx.
                        KEY_STK_DIFFERENT_LAUNCH_BROWSER_TR, slotId);
                if (confirmed) {
                    resMsg.setResultCode(ResultCode.OK);
                    mStkContext[slotId].launchBrowser = true;
                    mStkContext[slotId].mBrowserSettings =
                            mStkContext[slotId].mCurrentCmd.getBrowserSettings();
                } else {
                    if (mCustomLaunchBrowserTR) {
                        resMsg.setResultCode(ResultCode.USER_NOT_ACCEPT);
                    } else {
                        resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
                    }
                }
                /*UNISOC: @}*/
                break;
            case SET_UP_CALL:
                /*UNISOC: Feature for SET_UP_CALL @{ */
                if (confirmed) {
                    processSetupCallResponse(slotId, true);
                    return;
                }
                // Cancel
                mStkContext[slotId].mSetupCallInProcess = false;
                /*UNISOC: @}*/
                resMsg.setResultCode(ResultCode.OK);
                resMsg.setConfirmation(confirmed);
//              if (confirmed) {
//                  launchEventMessage(slotId,
//                          mStkContext[slotId].mCurrentCmd.getCallSettings().callMsg);
//              }
                break;
            }
            break;
        case RES_ID_DONE:
            resMsg.setResultCode(ResultCode.OK);
            break;
        case RES_ID_BACKWARD:
            CatLog.d(LOG_TAG, "RES_ID_BACKWARD");
            resMsg.setResultCode(ResultCode.BACKWARD_MOVE_BY_USER);
            break;
        case RES_ID_END_SESSION:
            CatLog.d(LOG_TAG, "RES_ID_END_SESSION");
            resMsg.setResultCode(ResultCode.UICC_SESSION_TERM_BY_USER);
            break;
        case RES_ID_TIMEOUT:
            CatLog.d(LOG_TAG, "RES_ID_TIMEOUT");
            // GCF test-case 27.22.4.1.1 Expected Sequence 1.5 (DISPLAY TEXT,
            // Clear message after delay, successful) expects result code OK.
            // If the command qualifier specifies no user response is required
            // then send OK instead of NO_RESPONSE_FROM_USER
            /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
            if (mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.DISPLAY_TEXT.value()) {
                if (mStkContext[slotId].mCurrentCmd.geTextMessage().userClear == false) {
                    resMsg.setResultCode(ResultCode.OK);
                } else {
                    resMsg.setResultCode(ResultCode.NO_RESPONSE_FROM_USER);
                }
                mStkContext[slotId].mDisplayTextResponsed = true;
            /*UNISOC: @}*/
            } else {
                resMsg.setResultCode(ResultCode.NO_RESPONSE_FROM_USER);
            }
            break;
        case RES_ID_CHOICE:
            int choice = args.getInt(CHOICE);
            CatLog.d(this, "User Choice=" + choice);
            switch (choice) {
                case YES:
                    resMsg.setResultCode(ResultCode.OK);
                    confirmed = true;
                    break;
                case NO:
                    resMsg.setResultCode(ResultCode.USER_NOT_ACCEPT);
                    break;
            }

            if (mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                    AppInterface.CommandType.OPEN_CHANNEL.value()) {
                resMsg.setConfirmation(confirmed);
            }
            break;
        case RES_ID_ERROR:
            CatLog.d(LOG_TAG, "RES_ID_ERROR");
            switch (mStkContext[slotId].mCurrentCmd.getCmdType()) {
            case LAUNCH_BROWSER:
                resMsg.setResultCode(ResultCode.LAUNCH_BROWSER_ERROR);
                break;
            }
            break;
        default:
            CatLog.d(LOG_TAG, "Unknown result id");
            return;
        }

        switch (args.getInt(RES_ID)) {
            case RES_ID_MENU_SELECTION:
            case RES_ID_INPUT:
            case RES_ID_CONFIRM:
            case RES_ID_CHOICE:
            case RES_ID_BACKWARD:
            case RES_ID_END_SESSION:
                mStkContext[slotId].mNoResponseFromUser = false;
                break;
            case RES_ID_TIMEOUT:
                cancelNotificationOnKeyguard(slotId);
                mStkContext[slotId].mNoResponseFromUser = true;
                break;
            default:
                // The other IDs cannot be used to judge if there is no response from user.
                break;
        }

        if (null != mStkContext[slotId].mCurrentCmd &&
                null != mStkContext[slotId].mCurrentCmd.getCmdType()) {
            CatLog.d(LOG_TAG, "handleCmdResponse- cmdName[" +
                    mStkContext[slotId].mCurrentCmd.getCmdType().name() + "]");
        }
        mStkService[slotId].onCmdResponse(resMsg);
        /*UNISOC: Feature for LAUNCH_BROWSER @{*/
        //In case a launch browser command was just after send terminal response, launch that url
        if ((mStkContext[slotId].mCurrentCmd.getCmdType().value() ==
                AppInterface.CommandType.LAUNCH_BROWSER.value()) && mStkContext[slotId].launchBrowser) {
            mStkContext[slotId].launchBrowser = false;
            launchBrowser(mStkContext[slotId].mBrowserSettings);
        }
        /*UNISOC: @}*/
    }

    /**
     * Returns 0 or FLAG_ACTIVITY_NO_USER_ACTION, 0 means the user initiated the action.
     *
     * @param userAction If the userAction is yes then we always return 0 otherwise
     * mMenuIsVisible is used to determine what to return. If mMenuIsVisible is true
     * then we are the foreground app and we'll return 0 as from our perspective a
     * user action did cause. If it's false than we aren't the foreground app and
     * FLAG_ACTIVITY_NO_USER_ACTION is returned.
     *
     * @return 0 or FLAG_ACTIVITY_NO_USER_ACTION
     */
    private int getFlagActivityNoUserAction(InitiatedByUserAction userAction, int slotId) {
        return ((userAction == InitiatedByUserAction.yes) | mStkContext[slotId].mMenuIsVisible)
                ? 0 : Intent.FLAG_ACTIVITY_NO_USER_ACTION;
    }
    /**
     * This method is used for cleaning up pending instances in stack.
     * No terminal response will be sent for pending instances.
     */
    private void cleanUpInstanceStackBySlot(int slotId) {
        Activity activity = mStkContext[slotId].getPendingActivityInstance();
        Activity dialog = mStkContext[slotId].getPendingDialogInstance();
        CatLog.d(LOG_TAG, "cleanUpInstanceStackBySlot slotId: " + slotId);
        if (activity != null) {
            if (mStkContext[slotId].mCurrentCmd != null) {
                CatLog.d(LOG_TAG, "current cmd type: " +
                        mStkContext[slotId].mCurrentCmd.getCmdType());
                if (mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.GET_INPUT.value()
                        || mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.GET_INKEY.value()) {
                    mStkContext[slotId].mIsInputPending = true;
                } else if (mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.SET_UP_MENU.value()
                        || mStkContext[slotId].mCurrentCmd.getCmdType().value()
                        == AppInterface.CommandType.SELECT_ITEM.value()) {
                    mStkContext[slotId].mIsMenuPending = true;
                }
            }
            CatLog.d(LOG_TAG, "finish pending activity.");
            activity.finish();
            mStkContext[slotId].mActivityInstance = null;
        }
        if (dialog != null) {
            CatLog.d(LOG_TAG, "finish pending dialog.");
            mStkContext[slotId].mIsDialogPending = true;
            dialog.finish();
            mStkContext[slotId].mDialogInstance = null;
        }
    }
    /**
     * This method is used for restoring pending instances from stack.
     */
    private void restoreInstanceFromStackBySlot(int slotId) {
        AppInterface.CommandType cmdType = mStkContext[slotId].mCurrentCmd.getCmdType();

        CatLog.d(LOG_TAG, "restoreInstanceFromStackBySlot cmdType : " + cmdType);
        switch(cmdType) {
            case GET_INPUT:
            case GET_INKEY:
                launchInputActivity(slotId);
                //Set mMenuIsVisible to true for showing main menu for
                //following session end command.
                mStkContext[slotId].mMenuIsVisible = true;
            break;
            case DISPLAY_TEXT:
                launchTextDialog(slotId);
            break;
            case LAUNCH_BROWSER:
                launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.geTextMessage(),
                        slotId);
            break;
            case OPEN_CHANNEL:
                launchOpenChannelDialog(slotId);
            break;
            case SET_UP_CALL:
                launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.getCallSettings().
                        confirmMsg, slotId);
            break;
            case SET_UP_MENU:
            case SELECT_ITEM:
                launchMenuActivity(null, slotId);
            break;
        default:
            break;
        }
    }

    @Override
    public void startActivity(Intent intent) {
        int slotId = intent.getIntExtra(SLOT_ID, SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        // Close the dialog displayed for DISPLAY TEXT command with an immediate response object
        // before new dialog is displayed.
        if (SubscriptionManager.isValidSlotIndex(slotId)) {
            Activity dialog = mStkContext[slotId].getImmediateDialogInstance();
            if (dialog != null) {
                CatLog.d(LOG_TAG, "finish dialog for immediate response.");
                dialog.finish();
            }
        }
        super.startActivity(intent);
    }

    private void launchMenuActivity(Menu menu, int slotId) {
        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        boolean isAirPlaneModeOn = Settings.Global.getInt(getBaseContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        CatLog.d(LOG_TAG, "launchMenuActivity isAirPlaneModeOn = " + isAirPlaneModeOn);
        if(isAirPlaneModeOn){
            mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
            return;
        }
        /*UNISOC: @}*/
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        String targetActivity = STK_MENU_ACTIVITY_NAME;
        String uriString = STK_MENU_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);

        CatLog.d(LOG_TAG, "launchMenuActivity, slotId: " + slotId + " , " +
                uriData.toString() + " , " + mStkContext[slotId].mOpCode + ", "
                + mStkContext[slotId].mMenuState);
        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        int intentFlags = Intent.FLAG_ACTIVITY_NEW_TASK;

        if (menu == null) {
            // We assume this was initiated by the user pressing the tool kit icon
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.yes, slotId);
            //If the last pending menu is secondary menu, "STATE" should be "STATE_SECONDARY".
            //Otherwise, it should be "STATE_MAIN".
            if (mStkContext[slotId].mOpCode == OP_LAUNCH_APP &&
                    mStkContext[slotId].mMenuState == StkMenuActivity.STATE_SECONDARY) {
                newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
            } else {
                newIntent.putExtra("STATE", StkMenuActivity.STATE_MAIN);
                mStkContext[slotId].mMenuState = StkMenuActivity.STATE_MAIN;
            }
        } else {
            // We don't know and we'll let getFlagActivityNoUserAction decide.
            intentFlags |= getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId);
            newIntent.putExtra("STATE", StkMenuActivity.STATE_SECONDARY);
            mStkContext[slotId].mMenuState = StkMenuActivity.STATE_SECONDARY;
            /* UNISOC: Feature bug @{ */
            if ((mHomePressedFlg && !isTopOfStack())
                    || (isBusyOnCall() && isCallInStack())) {
                CatLog.d(LOG_TAG, "Home key Pressed and current activity is not stk menu,no need to launch");
                return;
            }
            /*UNISOC: @}*/
        }
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        newIntent.setFlags(intentFlags);
        startActivity(newIntent);
    }

    private void launchInputActivity(int slotId) {
        Intent newIntent = new Intent(Intent.ACTION_VIEW);
        String targetActivity = STK_INPUT_ACTIVITY_NAME;
        String uriString = STK_INPUT_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);
        /*UNISOC: Feature for orange Feature patch SPCSS00430239 @{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification)) {
            mNotificationManager.cancel(getNotificationId(slotId));
        }
        Input input = mStkContext[slotId].mCurrentCmd.geInput();
        String title = null;
        if (input != null) {
            title = input.text;
        }
        /*UNISOC: @}*/

        CatLog.d(LOG_TAG, "launchInputActivity, slotId: " + slotId);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        newIntent.putExtra("INPUT", input);
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);

        /*UNISOC: Feature for orange Feature patch SPCSS00430239@{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification) &&
                isScreenLocked() && isScreenSecure()) {
            CatLog.d(LOG_TAG, "isScreenLocked : " + isScreenLocked()
                    + " isScreenSecure :" + isScreenSecure());
            wakeUp();
            buildNotification(newIntent, title, slotId);
        } else {
            if (input != null) {
                notifyUserIfNecessary(newIntent, slotId, input.text);
            }
        }
        /*UNISOC: @}*/
        startActivity(newIntent);
    }

    private void launchTextDialog(int slotId) {
        CatLog.d(LOG_TAG, "launchTextDialog, slotId: " + slotId);
        /*UNISOC: bug for black screen @{*/
        if (!(mContext.getResources().getBoolean(R.bool.config_support_authentification))) {
            if (!mUserManager.isUserUnlocked()) {
                mStkContext[slotId].mDelayToCheckTime++;
                CatLog.d(LOG_TAG, "launchTextDialog, mDelayToCheckTime = "
                        + mStkContext[slotId].mDelayToCheckTime);
                if(mStkContext[slotId].mDelayToCheckTime >= DELAY_TO_CHECK_NUM){
                    mStkContext[slotId].mDelayToCheckTime = 0;
                    sendResponse(RES_ID_CONFIRM, slotId, false);
                } else {
                    delayToCheckUserUnlock(slotId);
                }
                return;
            }
        }
        /*UNISOC: @}*/
        mStkContext[slotId].mDelayToCheckTime = 0;
        Intent newIntent = new Intent();
        String targetActivity = STK_DIALOG_ACTIVITY_NAME;
        int action = getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId);
        String uriString = STK_DIALOG_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);
        /*UNISOC: Feature for orange Feature patch SPCSS00430239 @{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification)) {
            mNotificationManager.cancel(getNotificationId(slotId));
        }
        TextMessage textMessage = mStkContext[slotId].mCurrentCmd.geTextMessage();
        String title = null;
        if (textMessage != null) {
            title = textMessage.text;
        }
        /*UNISOC: @}*/

        newIntent.setClassName(PACKAGE_NAME, targetActivity);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.setData(uriData);
        newIntent.putExtra("TEXT", textMessage);
        newIntent.putExtra(SLOT_ID, slotId);

        /*UNISOC: Feature for orange Feature patch SPCSS00430239 @{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification)
                && isScreenLocked() && isScreenSecure()) {
            CatLog.d(LOG_TAG, "isScreenLocked : " + isScreenLocked()
                    + " isScreenSecure : " + isScreenSecure());
            wakeUp();
            buildNotification(newIntent, title, slotId);
        } else {
            if (textMessage != null) {
                notifyUserIfNecessary(newIntent, slotId, textMessage.text);
            }
        }
        /*UNISOC: @}*/
        startActivity(newIntent);
        /*UNISOC: @}*/
        // For display texts with immediate response, send the terminal response
        // immediately. responseNeeded will be false, if display text command has
        // the immediate response tlv.
        if (!mStkContext[slotId].mCurrentCmd.geTextMessage().responseNeeded) {
            sendResponse(RES_ID_CONFIRM, slotId, true);
        }
    }

    private void notifyUserIfNecessary(Intent embededIntent, int slotId, String message) {
        createAllChannels();

        if (mStkContext[slotId].mNoResponseFromUser) {
            // No response from user was observed in the current session.
            // Do nothing in that case in order to avoid turning on the screen again and again
            // when the card repeatedly sends the same command in its retry procedure.
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);

        if (((KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()) {
            // Display the notification on the keyguard screen
            // if user cannot see the message from the card right now because of it.
            // The notification can be dismissed if user removed the keyguard screen.
            launchNotificationOnKeyguard(embededIntent, slotId, message);
        } else if (!(pm.isInteractive() && isTopOfStack())) {
            // User might be doing something but it is not related to the SIM Toolkit.
            // Play the tone and do vibration in order to attract user's attention.
            // User will see the input screen or the dialog soon in this case.
            NotificationChannel channel = mNotificationManager
                    .getNotificationChannel(STK_NOTIFICATION_CHANNEL_ID);
            Uri uri = channel.getSound();
            if (uri != null && !Uri.EMPTY.equals(uri)
                    && (NotificationManager.IMPORTANCE_LOW) < channel.getImportance()) {
                RingtoneManager.getRingtone(getApplicationContext(), uri).play();
            }
            long[] pattern = channel.getVibrationPattern();
            if (pattern != null && channel.shouldVibrate()) {
                ((Vibrator) this.getSystemService(Context.VIBRATOR_SERVICE))
                        .vibrate(pattern, -1);
            }
        }

        // Turn on the screen.
        PowerManager.WakeLock wakelock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        wakelock.acquire();
        wakelock.release();
    }

    private void launchNotificationOnKeyguard(Intent embededIntent, int slotId, String message) {
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, embededIntent, 0);
        Notification.Builder builder = new Notification.Builder(this, STK_NOTIFICATION_CHANNEL_ID);

        builder.setStyle(new Notification.BigTextStyle(builder).bigText(message));
        builder.setContentText(message);

        Menu menu = getMainMenu(slotId);
        if (menu == null || TextUtils.isEmpty(menu.title)) {
            builder.setContentTitle(getResources().getString(R.string.app_name));
        } else {
            builder.setContentTitle(menu.title);
        }

        builder.setContentIntent(pendingIntent);
        builder.setSmallIcon(com.android.internal.R.drawable.stat_notify_sim_toolkit);
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setColor(getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color));

        registerUserPresentReceiver();
        mNotificationManager.notify(getNotificationId(NOTIFICATION_ON_KEYGUARD, slotId),
                builder.build());
        mStkContext[slotId].mNotificationOnKeyguard = true;
    }

    private void cancelNotificationOnKeyguard(int slotId) {
        mNotificationManager.cancel(getNotificationId(NOTIFICATION_ON_KEYGUARD, slotId));
        mStkContext[slotId].mNotificationOnKeyguard = false;
        unregisterUserPresentReceiver(slotId);
    }

    private synchronized void registerUserPresentReceiver() {
        if (mUserPresentReceiver == null) {
            mUserPresentReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        for (int slot = 0; slot < mSimCount; slot++) {
                            cancelNotificationOnKeyguard(slot);
                        }
                    }
                }
            };
            registerReceiver(mUserPresentReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        }
    }

    private synchronized void unregisterUserPresentReceiver(int slotId) {
        if (mUserPresentReceiver != null) {
            for (int slot = PhoneConstants.SIM_ID_1; slot < mSimCount; slot++) {
                if (slot != slotId) {
                    if (mStkContext[slot].mNotificationOnKeyguard) {
                        // The broadcast receiver is still necessary for other SIM card.
                        return;
                    }
                }
            }
            unregisterReceiver(mUserPresentReceiver);
            mUserPresentReceiver = null;
        }
    }

    private int getNotificationId(int notificationType, int slotId) {
        return getNotificationId(slotId) + (notificationType * mSimCount);
    }

    /**
     * Checks whether the dialog exists as the top activity of this task.
     *
     * @return true if the top activity of this task is the dialog.
     */
    public boolean isStkDialogActivated() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.getAppTasks() != null && am.getAppTasks().size() > 1) {
            ComponentName componentName = am.getAppTasks().get(0).getTaskInfo().topActivity;
            if (componentName != null) {
                String[] split = componentName.getClassName().split(Pattern.quote("."));
                String topActivity = split[split.length - 1];
                CatLog.d(LOG_TAG, "Top activity: " + topActivity);
                if (TextUtils.equals(topActivity, StkDialogActivity.class.getSimpleName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void replaceEventList(int slotId) {
        if (mStkContext[slotId].mSetupEventListSettings != null) {
            for (int current : mStkContext[slotId].mSetupEventListSettings.eventList) {
                if (current != INVALID_SETUP_EVENT) {
                    // Cancel the event notification if it is not listed in the new event list.
                    if ((mStkContext[slotId].mCurrentCmd.getSetEventList() == null)
                            || !findEvent(current, mStkContext[slotId].mCurrentCmd
                            .getSetEventList().eventList)) {
                        unregisterEvent(current, slotId);
                    }
                }
            }
        }
        mStkContext[slotId].mSetupEventListSettings
                = mStkContext[slotId].mCurrentCmd.getSetEventList();
        mStkContext[slotId].mCurrentSetupEventCmd = mStkContext[slotId].mCurrentCmd;
        mStkContext[slotId].mCurrentCmd = mStkContext[slotId].mMainCmd;
        registerEvents(slotId);
    }

    private boolean findEvent(int event, int[] eventList) {
        for (int content : eventList) {
            if (content == event) return true;
        }
        return false;
    }

    private void unregisterEvent(int event, int slotId) {
        for (int slot = PhoneConstants.SIM_ID_1; slot < mSimCount; slot++) {
            if (slot != slotId) {
                if (mStkContext[slot].mSetupEventListSettings != null) {
                    if (findEvent(event, mStkContext[slot].mSetupEventListSettings.eventList)) {
                        // The specified event shall never be canceled
                        // if there is any other SIM card which requests the event.
                        return;
                    }
                }
            }
        }

        switch (event) {
            case USER_ACTIVITY_EVENT:
                unregisterUserActivityReceiver();
                break;
            case IDLE_SCREEN_AVAILABLE_EVENT:
                unregisterProcessObserver(AppInterface.CommandType.SET_UP_EVENT_LIST, slotId);
                break;
            case LANGUAGE_SELECTION_EVENT:
                unregisterLocaleChangeReceiver();
                break;
            default:
                break;
        }
    }

    private void registerEvents(int slotId) {
        if (mStkContext[slotId].mSetupEventListSettings == null) {
            return;
        }
        for (int event : mStkContext[slotId].mSetupEventListSettings.eventList) {
            switch (event) {
                case USER_ACTIVITY_EVENT:
                    registerUserActivityReceiver();
                    break;
                case IDLE_SCREEN_AVAILABLE_EVENT:
                    registerProcessObserver();
                    break;
                case LANGUAGE_SELECTION_EVENT:
                    registerLocaleChangeReceiver();
                    break;
                default:
                    break;
            }
        }
    }

    private synchronized void registerUserActivityReceiver() {
        if (mUserActivityReceiver == null) {
            mUserActivityReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (WindowManagerPolicyConstants.ACTION_USER_ACTIVITY_NOTIFICATION.equals(
                            intent.getAction())) {
                        Message message = mServiceHandler.obtainMessage();
                        message.arg1 = OP_USER_ACTIVITY;
                        mServiceHandler.sendMessage(message);
                        unregisterUserActivityReceiver();
                    }
                }
            };
            registerReceiver(mUserActivityReceiver, new IntentFilter(
                    WindowManagerPolicyConstants.ACTION_USER_ACTIVITY_NOTIFICATION));
            try {
                IWindowManager wm = IWindowManager.Stub.asInterface(
                        ServiceManager.getService(Context.WINDOW_SERVICE));
                wm.requestUserActivityNotification();
            } catch (RemoteException e) {
                CatLog.e(this, "failed to init WindowManager:" + e);
            }
        }
    }

    private synchronized void unregisterUserActivityReceiver() {
        if (mUserActivityReceiver != null) {
            unregisterReceiver(mUserActivityReceiver);
            mUserActivityReceiver = null;
        }
    }

    private synchronized void registerProcessObserver() {
        if (mProcessObserver == null) {
            try {
                IProcessObserver.Stub observer = new IProcessObserver.Stub() {
                    @Override
                    public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
                        if (isScreenIdle()) {
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_IDLE_SCREEN;
                            mServiceHandler.sendMessage(message);
                            unregisterProcessObserver();
                        }
                    }

                    @Override
                    public void onForegroundServicesChanged(int pid, int uid, int fgServiceTypes) {
                    }

                    @Override
                    public void onProcessDied(int pid, int uid) {
                    }
                };
                ActivityManagerNative.getDefault().registerProcessObserver(observer);
                CatLog.d(this, "Started to observe the foreground activity");
                mProcessObserver = observer;
            } catch (RemoteException e) {
                CatLog.d(this, "Failed to register the process observer");
            }
        }
    }

    private void unregisterProcessObserver(AppInterface.CommandType command, int slotId) {
        // Check if there is any pending command which still needs the process observer
        // except for the current command and slot.
        for (int slot = PhoneConstants.SIM_ID_1; slot < mSimCount; slot++) {
            if (command != AppInterface.CommandType.SET_UP_IDLE_MODE_TEXT || slot != slotId) {
                if (mStkContext[slot].mIdleModeTextCmd != null
                        && !mStkContext[slot].mIdleModeTextVisible) {
                    // Keep the process observer registered
                    // as there is an idle mode text which has not been visible yet.
                    return;
                }
            }
            if (command != AppInterface.CommandType.SET_UP_EVENT_LIST || slot != slotId) {
                if (mStkContext[slot].mSetupEventListSettings != null) {
                    if (findEvent(IDLE_SCREEN_AVAILABLE_EVENT,
                                mStkContext[slot].mSetupEventListSettings.eventList)) {
                        // Keep the process observer registered
                        // as there is a SIM card which still want IDLE SCREEN AVAILABLE event.
                        return;
                    }
                }
            }
        }
        unregisterProcessObserver();
    }

    private synchronized void unregisterProcessObserver() {
        if (mProcessObserver != null) {
            try {
                ActivityManagerNative.getDefault().unregisterProcessObserver(mProcessObserver);
                CatLog.d(this, "Stopped to observe the foreground activity");
                mProcessObserver = null;
            } catch (RemoteException e) {
                CatLog.d(this, "Failed to unregister the process observer");
            }
        }
    }

    private synchronized void registerLocaleChangeReceiver() {
        if (mLocaleChangeReceiver == null) {
            mLocaleChangeReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                        Message message = mServiceHandler.obtainMessage();
                        message.arg1 = OP_LOCALE_CHANGED;
                        mServiceHandler.sendMessage(message);
                    }
                }
            };
            registerReceiver(mLocaleChangeReceiver, new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
        }
    }

    private synchronized void unregisterLocaleChangeReceiver() {
        if (mLocaleChangeReceiver != null) {
            unregisterReceiver(mLocaleChangeReceiver);
            mLocaleChangeReceiver = null;
        }
    }

    private void sendSetUpEventResponse(int event, byte[] addedInfo, int slotId) {
        CatLog.d(this, "sendSetUpEventResponse: event : " + event + "slotId = " + slotId);

        if (mStkContext[slotId].mCurrentSetupEventCmd == null){
            CatLog.e(this, "mCurrentSetupEventCmd is null");
            return;
        }

        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentSetupEventCmd);

        resMsg.setResultCode(ResultCode.OK);
        resMsg.setEventDownload(event, addedInfo);

        mStkService[slotId].onCmdResponse(resMsg);
    }

    private void checkForSetupEvent(int event, Bundle args, int slotId) {
        boolean eventPresent = false;
        byte[] addedInfo = null;
        CatLog.d(this, "Event :" + event);

        if (mStkContext[slotId].mSetupEventListSettings != null) {
            /* Checks if the event is present in the EventList updated by last
             * SetupEventList Proactive Command */
            for (int i : mStkContext[slotId].mSetupEventListSettings.eventList) {
                 if (event == i) {
                     eventPresent =  true;
                     break;
                 }
            }

            /* If Event is present send the response to ICC */
            if (eventPresent == true) {
                CatLog.d(this, " Event " + event + "exists in the EventList");

                switch (event) {
                    case USER_ACTIVITY_EVENT:
                    case IDLE_SCREEN_AVAILABLE_EVENT:
                        sendSetUpEventResponse(event, addedInfo, slotId);
                        removeSetUpEvent(event, slotId);
                        break;
                    case LANGUAGE_SELECTION_EVENT:
                        String language =  mContext
                                .getResources().getConfiguration().locale.getLanguage();
                        CatLog.d(this, "language: " + language);
                        // Each language code is a pair of alpha-numeric characters.
                        // Each alpha-numeric character shall be coded on one byte
                        // using the SMS default 7-bit coded alphabet
                        addedInfo = GsmAlphabet.stringToGsm8BitPacked(language);
                        sendSetUpEventResponse(event, addedInfo, slotId);
                        break;
                    default:
                        break;
                }
            } else {
                CatLog.e(this, " Event does not exist in the EventList");
            }
        } else {
            CatLog.e(this, "SetupEventList is not received. Ignoring the event: " + event);
        }
    }

    private void removeSetUpEvent(int event, int slotId) {
        CatLog.d(this, "Remove Event :" + event);

        if (mStkContext[slotId].mSetupEventListSettings != null) {
            /*
             * Make new  Eventlist without the event
             */
            for (int i = 0; i < mStkContext[slotId].mSetupEventListSettings.eventList.length; i++) {
                if (event == mStkContext[slotId].mSetupEventListSettings.eventList[i]) {
                    mStkContext[slotId].mSetupEventListSettings.eventList[i] = INVALID_SETUP_EVENT;

                    switch (event) {
                        case USER_ACTIVITY_EVENT:
                            // The broadcast receiver can be unregistered
                            // as the event has already been sent to the card.
                            unregisterUserActivityReceiver();
                            break;
                        case IDLE_SCREEN_AVAILABLE_EVENT:
                            // The process observer can be unregistered
                            // as the idle screen has already been available.
                            unregisterProcessObserver();
                            break;
                        default:
                            break;
                    }
                    break;
                }
            }
        }
    }

    private void launchEventMessage(int slotId) {
        launchEventMessage(slotId, mStkContext[slotId].mCurrentCmd.geTextMessage());
    }

    private void launchEventMessage(int slotId, TextMessage msg) {
        if (msg == null || msg.text == null || (msg.text != null && msg.text.length() == 0)) {
            CatLog.d(LOG_TAG, "launchEventMessage return");
            return;
        }

        Toast toast = new Toast(mContext.getApplicationContext());
        LayoutInflater inflate = (LayoutInflater) mContext
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(R.layout.stk_event_msg, null);
        TextView tv = (TextView) v
                .findViewById(com.android.internal.R.id.message);
        ImageView iv = (ImageView) v
                .findViewById(com.android.internal.R.id.icon);
        if (msg.icon != null) {
            iv.setImageBitmap(msg.icon);
        } else {
            iv.setVisibility(View.GONE);
        }
        /* In case of 'self explanatory' stkapp should display the specified
         * icon in proactive command (but not the alpha string).
         * If icon is non-self explanatory and if the icon could not be displayed
         * then alpha string or text data should be displayed
         * Ref: ETSI 102.223,section 6.5.4
         */
        if (mStkContext[slotId].mCurrentCmd.hasIconLoadFailed() ||
                msg.icon == null || !msg.iconSelfExplanatory) {
            tv.setText(msg.text);
            tv.setTextColor(Color.BLACK);
        }

        toast.setView(v);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        /*UNISOC: Feature for orange Feature @{*/
        if (mContext.getResources().getBoolean(R.bool.config_support_authentification)) {
            if (!TextUtils.isEmpty(tv.getText().toString())) {
                toast.show();
            }
        } else {
            toast.show();
        }
        /*UNISOC: @}*/
    }

    private void launchConfirmationDialog(TextMessage msg, int slotId) {
        msg.title = mStkContext[slotId].lastSelectedItem;
        Intent newIntent = new Intent();
        String targetActivity = STK_DIALOG_ACTIVITY_NAME;
        String uriString = STK_DIALOG_URI + System.currentTimeMillis();
        //Set unique URI to create a new instance of activity for different slotId.
        Uri uriData = Uri.parse(uriString);

        newIntent.setClassName(this, targetActivity);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.putExtra("TEXT", msg);
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        startActivity(newIntent);
    }

    private void launchBrowser(BrowserSettings settings) {
        if (settings == null) {
            return;
        }

        Uri data = null;
        String url;
        if (settings.url == null) {
            // if the command did not contain a URL,
            // launch the browser to the default homepage.
            CatLog.d(this, "no url data provided by proactive command." +
                       " launching browser with stk default URL ... ");
            url = SystemProperties.get(STK_BROWSER_DEFAULT_URL_SYSPROP,
                    "http://www.google.com");
        } else {
            CatLog.d(this, "launch browser command has attached url = " + settings.url);
            url = settings.url;
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            data = Uri.parse(url);
            CatLog.d(this, "launching browser with url = " + url);
        } else {
            String modifiedUrl = "http://" + url;
            data = Uri.parse(modifiedUrl);
            CatLog.d(this, "launching browser with modified url = " + modifiedUrl);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(data);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        switch (settings.mode) {
        case USE_EXISTING_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        case LAUNCH_NEW_BROWSER:
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            break;
        case LAUNCH_IF_NOT_ALREADY_LAUNCHED:
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            break;
        }
        // start browser activity
        startActivity(intent);
        // a small delay, let the browser start, before processing the next command.
        // this is good for scenarios where a related DISPLAY TEXT command is
        // followed immediately.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {}
    }

    private void cancelIdleText(int slotId) {
        unregisterProcessObserver(AppInterface.CommandType.SET_UP_IDLE_MODE_TEXT, slotId);
        mNotificationManager.cancel(getNotificationId(slotId));
        mStkContext[slotId].mIdleModeTextCmd = null;
        mStkContext[slotId].mIdleModeTextVisible = false;
    }

    private void launchIdleText(int slotId) {
        TextMessage msg = mStkContext[slotId].mIdleModeTextCmd.geTextMessage();

        if (msg != null && !TextUtils.isEmpty(msg.text)) {
            CatLog.d(LOG_TAG, "launchIdleText - text[" + msg.text
                    + "] iconSelfExplanatory[" + msg.iconSelfExplanatory
                    + "] icon[" + msg.icon + "], sim id: " + slotId);
            CatLog.d(LOG_TAG, "Add IdleMode text");
            /*UNISOC: Feature for Idle Mode(case 27.22.4.22.2/4) @{ */
//          notification can not display long text, so we start activity to support  @{*/
//          PendingIntent pendingIntent = PendingIntent.getService(mContext, 0,
//                    new Intent(mContext, StkAppService.class), 0);
            Intent pendIntentData = new Intent();
            pendIntentData.setClassName(PACKAGE_NAME, STK_MESSAGE_ACTIVITY_NAME);

            idleModeText = msg.text;
            idleModeIcon = msg.icon;
            idleModeIconSelfExplanatory = msg.iconSelfExplanatory;

            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0,
                    pendIntentData, PendingIntent.FLAG_UPDATE_CURRENT);
            /*UNISOC: @}*/
            createAllChannels();
            final Notification.Builder notificationBuilder = new Notification.Builder(
                    StkAppService.this, STK_NOTIFICATION_CHANNEL_ID);
            if (mStkContext[slotId].mMainCmd != null &&
                    mStkContext[slotId].mMainCmd.getMenu() != null) {
                notificationBuilder.setContentTitle(mStkContext[slotId].mMainCmd.getMenu().title);
            } else {
                notificationBuilder.setContentTitle("");
            }
            notificationBuilder
                    .setSmallIcon(com.android.internal.R.drawable.stat_notify_sim_toolkit);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setOngoing(true);
            notificationBuilder.setOnlyAlertOnce(true);
            // Set text and icon for the status bar and notification body.
            if (mStkContext[slotId].mIdleModeTextCmd.hasIconLoadFailed() ||
                    !msg.iconSelfExplanatory) {
                notificationBuilder.setContentText(msg.text);
                notificationBuilder.setTicker(msg.text);
                notificationBuilder.setStyle(new Notification.BigTextStyle(notificationBuilder)
                        .bigText(msg.text));
            }
            if (msg.icon != null) {
                notificationBuilder.setLargeIcon(msg.icon);
            } else {
                Bitmap bitmapIcon = BitmapFactory.decodeResource(StkAppService.this
                    .getResources().getSystem(),
                    com.android.internal.R.drawable.stat_notify_sim_toolkit);
                notificationBuilder.setLargeIcon(bitmapIcon);
            }
            notificationBuilder.setColor(mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color));
            mNotificationManager.notify(getNotificationId(slotId), notificationBuilder.build());
            mStkContext[slotId].mIdleModeTextVisible = true;
        }
    }

    /** Creates the notification channel and registers it with NotificationManager.
     * If a channel with the same ID is already registered, NotificationManager will
     * ignore this call.
     */
    private void createAllChannels() {
        NotificationChannel notificationChannel = new NotificationChannel(
                STK_NOTIFICATION_CHANNEL_ID,
                getResources().getString(R.string.stk_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);

        notificationChannel.enableVibration(true);
        notificationChannel.setVibrationPattern(VIBRATION_PATTERN);

        mNotificationManager.createNotificationChannel(notificationChannel);
    }

    private void launchToneDialog(int slotId) {
        Intent newIntent = new Intent(this, ToneDialog.class);
        String uriString = STK_TONE_URI + slotId;
        Uri uriData = Uri.parse(uriString);
        //Set unique URI to create a new instance of activity for different slotId.
        CatLog.d(LOG_TAG, "launchToneDialog, slotId: " + slotId);
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
        newIntent.putExtra("TEXT", mStkContext[slotId].mCurrentCmd.geTextMessage());
        newIntent.putExtra("TONE", mStkContext[slotId].mCurrentCmd.getToneSettings());
        newIntent.putExtra(SLOT_ID, slotId);
        newIntent.setData(uriData);
        startActivity(newIntent);
    }

    private void handlePlayTone(int slotId) {
        TextMessage toneMsg = mStkContext[slotId].mCurrentCmd.geTextMessage();

        boolean showUser = true;
        boolean displayDialog = true;
        Resources resource = Resources.getSystem();
        try {
            displayDialog = !resource.getBoolean(
                    com.android.internal.R.bool.config_stkNoAlphaUsrCnf);
        } catch (NotFoundException e) {
            displayDialog = true;
        }

        // As per the spec 3GPP TS 11.14, 6.4.5. Play Tone.
        // If there is no alpha identifier tlv present, UE may show the
        // user information. 'config_stkNoAlphaUsrCnf' value will decide
        // whether to show it or not.
        // If alpha identifier tlv is present and its data is null, play only tone
        // without showing user any information.
        // Alpha Id is Present, but the text data is null.
        if (toneMsg != null) {
            if ((toneMsg.text != null ) && (toneMsg.text.equals(""))) {
                CatLog.d(this, "Alpha identifier data is null, play only tone");
                showUser = false;
            }
            // Alpha Id is not present AND we need to show info to the user.
            if (toneMsg.text == null && displayDialog) {
                CatLog.d(this, "toneMsg.text " + toneMsg.text
                        + " Starting ToneDialog activity with default message.");
                toneMsg.text = getResources().getString(R.string.default_tone_dialog_msg);
                showUser = true;
            }
            // Dont show user info, if config setting is true.
            if (toneMsg.text == null && !displayDialog) {
                CatLog.d(this, "config value stkNoAlphaUsrCnf is true");
                showUser = false;
            }

            CatLog.d(this, "toneMsg.text: " + toneMsg.text + "showUser: " +showUser +
                "displayDialog: " +displayDialog);
        }
        playTone(showUser, slotId);
    }

    private void playTone(boolean showUserInfo, int slotId) {
        // Start playing tone and vibration
        ToneSettings settings = mStkContext[slotId].mCurrentCmd.getToneSettings();
        if (null == settings) {
            CatLog.d(this, "null settings, not playing tone.");
            return;
        }

        mVibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE);
        mTonePlayer = new TonePlayer();
        mTonePlayer.play(settings.tone);
        int timeout = StkApp.calculateDurationInMilis(settings.duration);
        if (timeout == 0) {
            timeout = StkApp.TONE_DEFAULT_TIMEOUT;
        }

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = OP_STOP_TONE;
        msg.arg2 = slotId;
        msg.obj = (Integer)(showUserInfo ? 1 : 0);
        msg.what = STOP_TONE_WHAT;
        mServiceHandler.sendMessageDelayed(msg, timeout);
        if (settings.vibrate) {
            mVibrator.vibrate(timeout);
        }

        // Start Tone dialog Activity to show user the information.
        if (showUserInfo) {
            Intent newIntent = new Intent(sInstance, ToneDialog.class);
            String uriString = STK_TONE_URI + slotId;
            Uri uriData = Uri.parse(uriString);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | getFlagActivityNoUserAction(InitiatedByUserAction.unknown, slotId));
            newIntent.putExtra("TEXT", mStkContext[slotId].mCurrentCmd.geTextMessage());
            newIntent.putExtra(SLOT_ID, slotId);
            newIntent.setData(uriData);
            startActivity(newIntent);
        }
    }

    private void finishToneDialogActivity() {
        Intent finishIntent = new Intent(FINISH_TONE_ACTIVITY_ACTION);
        sendBroadcast(finishIntent);
    }

    private void handleStopTone(Message msg, int slotId) {
        int resId = 0;

        // Stop the play tone in following cases:
        // 1.OP_STOP_TONE: play tone timer expires.
        // 2.STOP_TONE_USER: user pressed the back key.
        if (msg.arg1 == OP_STOP_TONE) {
            resId = RES_ID_DONE;
            // Dismiss Tone dialog, after finishing off playing the tone.
            int finishActivity = (Integer) msg.obj;
            if (finishActivity == 1) finishToneDialogActivity();
        } else if (msg.arg1 == OP_STOP_TONE_USER) {
            resId = RES_ID_END_SESSION;
        }

        sendResponse(resId, slotId, true);
        mServiceHandler.removeMessages(STOP_TONE_WHAT);
        if (mTonePlayer != null)  {
            mTonePlayer.stop();
            mTonePlayer.release();
            mTonePlayer = null;
        }
        if (mVibrator != null) {
            mVibrator.cancel();
            mVibrator = null;
        }
    }

    private void launchOpenChannelDialog(final int slotId) {
        TextMessage msg = mStkContext[slotId].mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(LOG_TAG, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);
        if (msg.text == null) {
            msg.text = getResources().getString(R.string.default_open_channel_msg);
        }

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(R.string.stk_dialog_accept),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, YES);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.arg2 = slotId;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .setNegativeButton(getResources().getString(R.string.stk_dialog_reject),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            Bundle args = new Bundle();
                            args.putInt(RES_ID, RES_ID_CHOICE);
                            args.putInt(CHOICE, NO);
                            Message message = mServiceHandler.obtainMessage();
                            message.arg1 = OP_RESPONSE;
                            message.arg2 = slotId;
                            message.obj = args;
                            mServiceHandler.sendMessage(message);
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private void launchTransientEventMessage(int slotId) {
        TextMessage msg = mStkContext[slotId].mCurrentCmd.geTextMessage();
        if (msg == null) {
            CatLog.d(LOG_TAG, "msg is null, return here");
            return;
        }

        msg.title = getResources().getString(R.string.stk_dialog_title);

        final AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(msg.title)
                    .setMessage(msg.text)
                    .setCancelable(false)
                    .setPositiveButton(getResources().getString(android.R.string.ok),
                                       new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .create();

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        dialog.show();
    }

    private int getNotificationId(int slotId) {
        int notifyId = STK_NOTIFICATION_ID;
        if (slotId >= 0 && slotId < mSimCount) {
            notifyId += slotId;
        } else {
            CatLog.d(LOG_TAG, "invalid slotId: " + slotId);
        }
        CatLog.d(LOG_TAG, "getNotificationId, slotId: " + slotId + ", notifyId: " + notifyId);
        return notifyId;
    }

    private String getItemName(int itemId, int slotId) {
        Menu menu = mStkContext[slotId].mCurrentCmd.getMenu();
        if (menu == null) {
            return null;
        }
        for (Item item : menu.items) {
            if (item.id == itemId) {
                return item.text;
            }
        }
        return null;
    }

    private boolean removeMenu(int slotId) {
        try {
            if (mStkContext[slotId].mCurrentMenu.items.size() == 1 &&
                mStkContext[slotId].mCurrentMenu.items.get(0) == null) {
                mStkContext[slotId].mSetupMenuState = STATE_NOT_EXIST;
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(LOG_TAG, "Unable to get Menu's items size");
            mStkContext[slotId].mSetupMenuState = STATE_NOT_EXIST;
            return true;
        }
        mStkContext[slotId].mSetupMenuState = STATE_EXIST;
        return false;
    }

    StkContext getStkContext(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            return mStkContext[slotId];
        } else {
            CatLog.d(LOG_TAG, "invalid slotId: " + slotId);
            return null;
        }
    }

    private void handleAlphaNotify(Bundle args) {
        String alphaString = args.getString(AppInterface.ALPHA_STRING);

        CatLog.d(this, "Alpha string received from card: " + alphaString);
        Toast toast = Toast.makeText(sInstance, alphaString, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.TOP, 0, 0);
        toast.show();
    }

    private boolean isUrlAvailableToLaunchBrowser(BrowserSettings settings) {
        String url = SystemProperties.get(STK_BROWSER_DEFAULT_URL_SYSPROP, "");
        if (url == "" && settings.url == null) {
            return false;
        }
        return true;
    }

    /*UNISOC: Feature for REFRESH function @{*/
    private void launchRefreshMsg(int slotId) {
        if (mStkContext[slotId].mCurrentCmd == null) {
            CatLog.d(this, "[stkapp] launchRefreshMsg and mCurrentCmd is null");
            return;
        }

        /*UNISOC: Feature for claro & orange function @{*/
        if (mContext.getResources().getBoolean(R.bool.config_refresh_no_toast)) {
            CatLog.d(this, "[stkapp] Don't need launchRefreshMsg for STK Feature");
            return;
        }
        /*UNISOC: @}*/

        TextMessage msg = mStkContext[slotId].mCurrentCmd.geTextMessage();
        if (msg == null || msg.text == null || msg.text.length() == 0) {
            CatLog.d(this, "[stkapp] launchRefreshMsg is null");
            return;
        }
        if (mToast != null) {
            mToast.cancel();
        }
        mToast = Toast.makeText(mContext.getApplicationContext(), msg.text,
                Toast.LENGTH_LONG);
        mToast.setGravity(Gravity.BOTTOM, 0, 0);
        mToast.show();
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature for orange Feature patch SPCSS00430239 @{*/
    private boolean isScreenLocked(){
        boolean result = false;
        KeyguardManager kmgt = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (kmgt.inKeyguardRestrictedInputMode()) {
            result = true;
        }

        return result;
    }

    private void beep() {
        ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 500);
        tg.startTone(ToneGenerator.TONE_PROP_BEEP);
        tg.stopTone();
        tg.release();
    }

    private void wakeUp() {
        long screenOnTimeOut = 5000;
        beep();
        PowerManager pmgt = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wakeLock = pmgt.newWakeLock(PowerManager.FULL_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP, "StkWakeLock");
        wakeLock.acquire(screenOnTimeOut);
    }

    private boolean isScreenSecure() {
        boolean result = false;
        KeyguardManager kmgt = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        if (kmgt.isKeyguardSecure()) {
            result = true;
        }
        CatLog.d(LOG_TAG, "isScreenSecure : " + result);
        return result;
    }

    private void buildNotification(Intent embededIntent, String title, int slotId){
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, embededIntent, 0);

        final Notification.Builder notificationBuilder = new Notification.Builder(StkAppService.this);
        if (title != null) {
            notificationBuilder.setContentTitle(title);
            notificationBuilder.setTicker(title);
        }
        Bitmap bitmapIcon = BitmapFactory.decodeResource(StkAppService.this.getResources().getSystem(),
                com.android.internal.R.drawable.stat_notify_sim_toolkit);
        notificationBuilder.setLargeIcon(bitmapIcon);
        notificationBuilder.setSmallIcon(com.android.internal.R.drawable.stat_notify_sim_toolkit);
        notificationBuilder.setContentIntent(pendingIntent);
        notificationBuilder.setAutoCancel(true);
        notificationBuilder.setColor(mContext.getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color));
        if (mNotificationManager != null) {
            mNotificationManager.notify(getNotificationId(slotId), notificationBuilder.build());
        }
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature bug for @{ */
    private boolean isCallInStack() {
        ActivityManager mActivityManager = (ActivityManager) mContext
                .getSystemService(ACTIVITY_SERVICE);
        String currentPackageName = null;
        List<RunningTaskInfo> tasks = mActivityManager.getRunningTasks(1);
        if (tasks == null || tasks.get(0).topActivity == null) {
            return false;
        }
        currentPackageName = tasks.get(0).topActivity.getPackageName();
        CatLog.d(LOG_TAG, "currentPackageName : " + currentPackageName);
        if (null != currentPackageName) {
            return currentPackageName.equals(CALL_PACKAGE_NAME);
        }
        return false;
    }

    boolean isMainMenuExsit(int slotId) {
        CatLog.d(LOG_TAG, "isMainMenuExsit, sim id: " + slotId);
        if (slotId == 0 && mStkContext[slotId + 1].mMainCmd == null) {
            return false;
        } else if (slotId == 1 && mStkContext[slotId - 1].mMainCmd == null) {
            return false;
        }
        return true;
    }

    boolean isShowSetupMenuTitle() {
        int count = 0;
        int id = 0;

        if (isAllCardsExsit()) {
            return false;
        }

        for (int i = 0; i < mSimCount; i++) {
            if (mStkContext[i].mCurrentMenu != null) {
                ++count;
                id = i;
            }
        }
        CatLog.d(LOG_TAG, "isShowSetupMenuTitle count=" + count + " id=" + id);
        if (count == 1) {
            if (mContext.getResources().getBoolean(R.bool.config_show_setupMenu_title)) {
                return true;
            }
        }

        return false;

    }

    // Check if all SIMs are in the card slot.
    private boolean isAllCardsExsit() {
        TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        int i = 0;
        int count = 0;

        for (i = 0; i < mSimCount; i++) {
            if (mTm.hasIccCard(i)) {
                count++;
            }
        }

        if (count > 1) {
            return true;
        } else {
            return false;
        }
    }

    private void onCmdResponse(CatResponseMessage resMsg, int slotId){
        if(mStkService[slotId] == null){
            CatLog.d(LOG_TAG, "mStkService[" + slotId + "] is null, reget it from CatService");
            mStkService[slotId] = CatService.getInstance(slotId);
        }
        if (mStkService[slotId] == null) {
            // This should never happen (we should be responding only to a message
            // that arrived from StkService). It has to exist by this time
            CatLog.d(LOG_TAG, "Exception! mStkService is null when we need to send response.");
        }else{
            mStkService[slotId].onCmdResponse(resMsg);
        }
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature for Idle Mode(case 27.22.4.22.2/4) @{ */
    private boolean isBusyOnCall() {
        for (int i = 0; i < mSimCount; i++) {
            int callState = getCallStateForSlot(i);
            CatLog.d(this, "slotId : " + i + " isBusyOnCall callState : " + callState);
            if (TelephonyManager.CALL_STATE_IDLE != callState) {
                return true;
            }
        }
        return false;
    }
    /*UNISOC: @}*/

    /* UNISOC: Feature for Cucc function @{ */
    boolean isCuccOperator() {
        if (mContext.getResources().getBoolean(R.bool.config_show_two_app)) {
            CatLog.d(LOG_TAG, "isCuccOperator ,the operator is Cucc!");
            return true;
        }
        CatLog.d(LOG_TAG, "NOT Cucc");
        return false;
    }

    private boolean isCardReady(Context context, int id) {
        TelephonyManager tm = TelephonyManager.from(context);
        // Check if the card is inserted.
        if (id < 0) {
            return false;
        }
        CatLog.d(this, "tm.getSimState(id)= " + tm.getSimState(id));
        if (tm.getSimState(id) == TelephonyManager.SIM_STATE_READY) {
            if (mStkContext[id] != null && mStkContext[id].mMainCmd != null) {
                CatLog.d(this, "SIM " + id + " is ready.");
                return true;
            }
        } else {
            CatLog.d(this, "SIM " + id + " is not inserted.");
        }
        return false;
    }

    private boolean isCardReady(Context context) {
        int simCount = TelephonyManager.from(context).getSimCount();
        TelephonyManager tm = TelephonyManager.from(context);
        CatLog.d(this, "simCount: " + simCount);
        for (int i = 0; i < simCount; i++) {
            // Check if the card is inserted.
            if (tm.hasIccCard(i)) {
                CatLog.d(this, "SIM " + i + " is inserted");
                if (tm.getSimState(i) == TelephonyManager.SIM_STATE_READY && mStkContext[i] != null
                        && mStkContext[i].mMainCmd != null) {
                    CatLog.d(this, "SIM " + i + " is ready.");
                    return true;
                }
            } else {
                CatLog.d(this, "SIM " + i + " is not inserted.");
            }
        }
        return false;
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
    boolean getDisplayTextResponsed(int slotId) {
        if (slotId >= 0 && slotId < mSimCount) {
            return mStkContext[slotId].mDisplayTextResponsed;
        }
        return false;
    }
    /*UNISOC: @}*/

    /*UNISOC: bug for black screen @{*/
    private void delayToCheckUserUnlock(int slotId) {
        CatLog.d(LOG_TAG, "delayToCheckUserUnlock, slotId: " + slotId);
        Message msg1 = mServiceHandler.obtainMessage();
        msg1.arg1 = OP_DELAY_TO_CHECK_USER_UNLOCK;
        msg1.arg2 = slotId;
        mServiceHandler.sendMessageDelayed(msg1, DELAY_TO_CHECK_USER_UNLOCK_TIME);
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature for SET_UP_CALL @{ */
    private void showIconToast(TextMessage msg) {
        Toast t = new Toast(this);
        ImageView v = new ImageView(this);
        v.setImageBitmap(msg.icon);
        t.setView(v);
        t.setDuration(Toast.LENGTH_LONG);
        t.show();
    }

    private void showTextToast(TextMessage msg, int slotId) {
        msg.title = mStkContext[slotId].lastSelectedItem;

        Toast toast = Toast.makeText(mContext.getApplicationContext(), msg.text,
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    private void showIconAndTextToast(TextMessage msg) {
        Toast t = new Toast(this);
        ImageView v = new ImageView(this);
        v.setImageBitmap(msg.icon);
        t.setView(v);
        t.setDuration(Toast.LENGTH_LONG);
        t.show();
    }

    private void launchCallMsg(int slotId) {
        TextMessage msg = mStkContext[slotId].mCurrentCmd.getCallSettings().callMsg;
        if (msg.iconSelfExplanatory == true) {
            // only display Icon.
            if (msg.icon != null) {
                showIconToast(msg);
            } else {
                // do nothing.
                return;
            }
        } else {
            // show text & icon.
            if (msg.icon != null) {
                if (msg.text == null || msg.text.length() == 0) {
                    // show Icon only.
                    showIconToast(msg);
                }
                else {
                    showIconAndTextToast(msg);
                }
            } else {
                if (msg.text == null || msg.text.length() == 0) {
                    // do nothing
                    return;
                } else {
                    showTextToast(msg, slotId);
                }
            }
        }
    }


    private int getCallStateForSlot(int slotId) {
        return TelephonyManager.from(mContext).getCallStateForSlot(slotId);
    }

    private void processSetupCallResponse(int slotId, boolean confirmed) {
        CatLog.d(this, "processSetupCallResponse, sim id : " + slotId +
                " confirmed :" + confirmed);
        mStkContext[slotId].mCmdInProgress = false;
        if (mStkContext[slotId].mSetupCallInProcess == false) {
            return;
        }
        mStkContext[slotId].mSetupCallInProcess = false;
        if (mStkContext[slotId].mCurrentCmd == null
                || mStkContext[slotId].mCurrentCmd.getCmdType() == null) {
            CatLog.d(this, "processNormalResponse mCurrentCmd is null or cmdType is null");
            return;
        }
        CatLog.d(this, "processNormalResponse cmdName: "
                + mStkContext[slotId].mCurrentCmd.getCmdType().name());
        CatResponseMessage resMsg = new CatResponseMessage(mStkContext[slotId].mCurrentCmd);
        resMsg.setResultCode(ResultCode.OK);
        resMsg.setConfirmation(confirmed);
        if (confirmed) {
            launchCallMsg(slotId);
        }
        onCmdResponse(resMsg,slotId);
    }

    private void processNoCall(int slotId) {
        if (TelephonyManager.CALL_STATE_IDLE == getCallStateForSlot(slotId)) {
            CatLog.d(this, "No another call");
            launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.getCallSettings().confirmMsg, slotId);
        } else {
            CatLog.d(this, "currently busy on another call");
            processSetupCallResponse(slotId, false);
        }
    }

    private void processSetupCall(int slotId) {
        CatLog.d(this, "processSetupCall, sim id: " + slotId);
        mStkContext[slotId].mSetupCallInProcess = true;
        /*If other sim is busy,we should not start setup call,so we set false to reponse */
        for (int i = 0; i < mSimCount; i++) {
            if ((i != slotId) && (TelephonyManager.CALL_STATE_IDLE != getCallStateForSlot(i))) {
                CatLog.d(this, "The other sim is not idle, sim id: " + i);
                processSetupCallResponse(slotId, false);
                Toast.makeText(mContext.getApplicationContext(),
                        R.string.default_call_setup_msg, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        int cmdQualifier = mStkContext[slotId].mCurrentCmd.getCommandQualifier();
        CatLog.d(this, "Qualifier code is: " + cmdQualifier);
        switch(cmdQualifier) {
            case SETUP_CALL_NO_CALL_1:
            case SETUP_CALL_NO_CALL_2:
                processNoCall(slotId);
                break;
            case SETUP_CALL_HOLD_CALL_1:
            case SETUP_CALL_HOLD_CALL_2:
            case SETUP_CALL_END_CALL_1:
            case SETUP_CALL_END_CALL_2:
                launchConfirmationDialog(mStkContext[slotId].mCurrentCmd.getCallSettings().confirmMsg,
                        slotId);
                break;
        }
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature bug for home key @{*/
    void setmHomeKeyEvent(boolean homeKeyEvent) {
        CatLog.d(LOG_TAG, "setmHomeKeyEvent : " + homeKeyEvent);
        mHomePressedFlg = homeKeyEvent;
    }
    /*UNISOC: @}*/

    /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
    private boolean isAirPlaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }
    /*UNISOC: @}*/

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            CatLog.d(LOG_TAG, "onReceive, action: " + action );
            if (action == null ) return;

            boolean isAirPlaneModeOn = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            CatLog.d(this, "isAirPlaneModeOn: " + isAirPlaneModeOn);
            int simCount = TelephonyManager.from(context).getSimCount();
            /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (isAirPlaneModeOn) {
                    if (isCuccOperator()) {
                        for (int i = 0; i < simCount; i++) {
                            StkAppInstaller.unInstall(context, i);
                        }
                    } else {
                        StkAppInstaller.unInstall(context);
                    }
                } else {
                    if (isCuccOperator()) {
                        for (int i = 0; i < simCount; i++) {
                            if (isCardReady(context, i)) {
                                StkAppInstaller.install(context, i);
                            }
                        }
                    } else {
                        if (isCardReady(context)) {
                            StkAppInstaller.install(context);
                        }
                    }
                }
                /*UNISOC: @}*/
            /*UNISOC: Feature for ModemAseert not display text Feature @{*/
            } else if (ACTION_MODEM_CHANGE.equals(intent.getAction())) {
                String state = intent.getStringExtra(MODEM_STAT);
                CatLog.d(LOG_TAG, "modem start state : " + state);
                if (MODEM_ASSERT.equals(state)) {
                    for (int i = 0; i < simCount; i++) {
                        System.setProperty("gsm.stk.modem.recovery" + i, "1");
                    }
                }
            /*UNISOC: @}*/
            /*UNISOC: Feature for USER_SWITCHED, all secondary users @{ */
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                CatLog.d(LOG_TAG, "mCurrentUserId: " + mCurrentUserId);
                if (mCurrentUserId != UserHandle.USER_OWNER) {
                    for (int i = 0; i < simCount; i++) {
                        if (mStkContext[i].mCurrentCmd == null) {
                            CatLog.d(LOG_TAG, "secondary users,mCurrentCmd is null" );
                            break;
                        }
                        if (mStkContext[i].mCurrentCmd.getCmdType().value() ==
                                AppInterface.CommandType.DISPLAY_TEXT.value() &&
                                !mStkContext[i].mDisplayTextResponsed) {
                            CatLog.d(LOG_TAG, "secondary users, need send TR" );
                            sendResponse(RES_ID_CONFIRM, i, true);
                        }
                    }
                }
            /*UNISOC: @}*/
            /*UNISOC: Feature bug for home key @{*/
            } else if (TextUtils.equals(action, Intent.ACTION_CLOSE_SYSTEM_DIALOGS)){
                String reason = intent.getStringExtra(SYSTEM_REASON);
                CatLog.d(LOG_TAG, "HomeKeyEvent reason:" + reason);
                if (TextUtils.equals(reason, SYSTEM_HOME_KEY)) {
                    mHomePressedFlg = true;
                }
            /*UNISOC: @}*/
            }
        }
    };
    /*UNISOC: @}*/
}
