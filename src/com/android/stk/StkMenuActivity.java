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

import android.app.ActionBar;
import android.app.AlarmManager;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.cat.Item;
import com.android.internal.telephony.cat.Menu;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;

/**
 * ListActivity used for displaying STK menus. These can be SET UP MENU and
 * SELECT ITEM menus. This activity is started multiple times with different
 * menu content.
 *
 */
public class StkMenuActivity extends ListActivity implements View.OnCreateContextMenuListener {
    private Menu mStkMenu = null;
    private int mState = STATE_MAIN;
    private boolean mAcceptUsersInput = true;
    private int mSlotId = -1;
    private boolean mIsResponseSent = false;
    // Determines whether this is in the pending state.
    private boolean mIsPending = false;

    private TextView mTitleTextView = null;
    private ImageView mTitleIconView = null;
    private ProgressBar mProgressView = null;
    private static final String className = new Object(){}.getClass().getEnclosingClass().getName();
    private static final String LOG_TAG = className.substring(className.lastIndexOf('.') + 1);

    private StkAppService appService = StkAppService.getInstance();

    // Keys for saving the state of the dialog in the bundle
    private static final String STATE_KEY = "state";
    private static final String ACCEPT_USERS_INPUT_KEY = "accept_users_input";
    private static final String RESPONSE_SENT_KEY = "response_sent";
    private static final String ALARM_TIME_KEY = "alarm_time";
    private static final String PENDING = "pending";

    private static final String SELECT_ALARM_TAG = LOG_TAG;
    private static final long NO_SELECT_ALARM = -1;
    private long mAlarmTime = NO_SELECT_ALARM;

    // Internal state values
    static final int STATE_INIT = 0;
    static final int STATE_MAIN = 1;
    static final int STATE_SECONDARY = 2;

    private static final int CONTEXT_MENU_HELP = 0;
    /*UNISOC: Feature bug @{*/
    private Context mContext;
    /*UNISOC: @}*/

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CatLog.d(LOG_TAG, "onCreate");

        ActionBar actionBar = getActionBar();
        actionBar.setCustomView(R.layout.stk_title);
        actionBar.setDisplayShowCustomEnabled(true);

        // Set the layout for this activity.
        setContentView(R.layout.stk_menu_list);
        mTitleTextView = (TextView) findViewById(R.id.title_text);
        mTitleIconView = (ImageView) findViewById(R.id.title_icon);
        mProgressView = (ProgressBar) findViewById(R.id.progress_bar);
        getListView().setOnCreateContextMenuListener(this);

        mContext = getBaseContext();
        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        IntentFilter intent = new IntentFilter();
        intent.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mReceiver, intent);

        boolean isAirPlaneModeOn = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        if (isAirPlaneModeOn) {
            CatLog.d(LOG_TAG, "Air Plane ModeOn");
            finish();
            return;
        }
        /* UNISOC: @}*/
        // appService can be null if this activity is automatically recreated by the system
        // with the saved instance state right after the phone process is killed.
        if (appService == null) {
            CatLog.d(LOG_TAG, "onCreate - appService is null");
            finish();
            return;
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(StkAppService.SESSION_ENDED);
        intentFilter.addAction(StkAppService.REFRESH_UNINSTALL);

        // For bug 1466545
        intentFilter.addAction(StkAppService.CARD_ABSENT);
        LocalBroadcastManager.getInstance(this).registerReceiver(mLocalBroadcastReceiver,
                intentFilter);

        initFromIntent(getIntent());
        TelephonyManager mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        if (!SubscriptionManager.isValidSlotIndex(mSlotId)
               || !mTm.hasIccCard(mSlotId)) {
            finish();
            return;
        }

        /*UNISOC: Feature bug 1401453 @{*/
        if (mState == STATE_SECONDARY) {
            appService.getStkContext(mSlotId).setPendingActivityInstance(this);
        }
        /*UNISOC: @}*/
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);

        if (!mAcceptUsersInput) {
            CatLog.d(LOG_TAG, "mAcceptUsersInput:false");
            return;
        }

        /*UNISOC: Feature bug @{*/
        if (appService != null) {
            appService.setmHomeKeyEvent(false);
        }
        /*UNISOC: @}*/

        Item item = getSelectedItem(position);
        if (item == null) {
            CatLog.d(LOG_TAG, "Item is null");
            return;
        }

        /*UNISOC: Feature for ModemAseert not display text Feature @{*/
        System.setProperty("gsm.stk.modem.recovery" + mSlotId, "0");
        /*UNISOC: @}*/
        CatLog.d(LOG_TAG, "onListItemClick Id: " + item.id + ", mState: " + mState);
        sendResponse(StkAppService.RES_ID_MENU_SELECTION, item.id, false);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        CatLog.d(LOG_TAG, "mAcceptUsersInput: " + mAcceptUsersInput);
        if (!mAcceptUsersInput) {
            return true;
        }

        /*UNISOC: Feature bug @{*/
        if (appService != null) {
            appService.setmHomeKeyEvent(false);
        }
        /*UNISOC: @}*/

        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            CatLog.d(LOG_TAG, "KEYCODE_BACK - mState[" + mState + "]");
            switch (mState) {
            case STATE_SECONDARY:
                CatLog.d(LOG_TAG, "STATE_SECONDARY");
                sendResponse(StkAppService.RES_ID_BACKWARD);
                return true;
            case STATE_MAIN:
                CatLog.d(LOG_TAG, "STATE_MAIN");
                finish();
                return true;
            }
            break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onResume() {
        super.onResume();

        CatLog.d(LOG_TAG, "onResume, slot id: " + mSlotId + "," + mState);
        /*UNISOC: Feature bug @{*/
        if (appService == null) {
            CatLog.d(LOG_TAG, "appService is null");
            cancelTimeOut();
            finish();
            return;
        }
        /*UNISOC: @}*/
        appService.indicateMenuVisibility(true, mSlotId);
        /*UNISOC: Feature bug @{*/
        appService.setmHomeKeyEvent(false);
        /*UNISOC: @}*/
        if (mState == STATE_MAIN) {
            mStkMenu = appService.getMainMenu(mSlotId);
        } else {
            mStkMenu = appService.getMenu(mSlotId);
        }
        if (mStkMenu == null) {
            CatLog.d(LOG_TAG, "menu is null");
            cancelTimeOut();
            finish();
            return;
        }
        displayMenu();

        // If the terminal has already sent response to the card when this activity is resumed,
        // keep this as a pending activity as this should be finished when the session ends.
        if (!mIsResponseSent) {
            setPendingState(false);
        }
        if (mAlarmTime == NO_SELECT_ALARM) {
            startTimeOut();
        }

        invalidateOptionsMenu();
        /*UNISOC: Feature bug @{*/
        showProgressBar(false);
        /*UNISOC: @}*/
    }

    @Override
    public void onPause() {
        super.onPause();
        CatLog.d(LOG_TAG, "onPause, slot id = " + mSlotId + "," + " mState = "
                + mState + ","  + " mAcceptUsersInput = " + mAcceptUsersInput);
        //If activity is finished in onResume and it reaults from null appService.
        if (appService != null) {
            appService.indicateMenuVisibility(false, mSlotId);
        } else {
            CatLog.d(LOG_TAG, "onPause: null appService.");
        }

        /*
         * do not cancel the timer here cancelTimeOut(). If any higher/lower
         * priority events such as incoming call, new sms, screen off intent,
         * notification alerts, user actions such as 'User moving to another activtiy'
         * etc.. occur during SELECT ITEM ongoing session,
         * this activity would receive 'onPause()' event resulting in
         * cancellation of the timer. As a result no terminal response is
         * sent to the card.
         */

    }

    @Override
    public void onStop() {
        super.onStop();
        CatLog.d(LOG_TAG, "onStop, slot id: " + mSlotId + "," + mIsResponseSent + "," + mState);

        // Nothing should be done here if this activity is being finished or restarted now.
        if (isFinishing() || isChangingConfigurations()) {
            return;
        }

        if (mIsResponseSent) {
            // It is unnecessary to keep this activity if the response was already sent and
            // the dialog activity is NOT on the top of this activity.
            if (mState == STATE_SECONDARY && !appService.isStkDialogActivated()) {
                finish();
            }
        } else {
            // This instance should be registered as the pending activity here
            // only when no response has been sent back to the card.
            setPendingState(true);
        }
    }

    @Override
    public void onDestroy() {
        getListView().setOnCreateContextMenuListener(null);
        super.onDestroy();
        CatLog.d(LOG_TAG, "onDestroy" + ", " + mState);
        if (appService == null || !SubscriptionManager.isValidSlotIndex(mSlotId)) {
            return;
        }
        //isMenuPending: if input act is finish by stkappservice when OP_LAUNCH_APP again,
        //we can not send TR here, since the input cmd is waiting user to process.
        if (mState == STATE_SECONDARY && !mIsResponseSent && !(appService != null && appService.isMenuPending(mSlotId))) {
            // Avoid sending the terminal response while the activty is being restarted
            // due to some kind of configuration change.\
            if (appService.getStkContext(mSlotId) != null
                    && appService.getStkContext(mSlotId).getImmediateDialogInstance() != null) {
                if (!isChangingConfigurations()) {
                    CatLog.d(LOG_TAG, "handleDestroy - Send End Session");
                    sendResponse(StkAppService.RES_ID_END_SESSION);
                }
            }
        }
        cancelTimeOut();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mLocalBroadcastReceiver);
        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        /*UNISOC: @} */
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, StkApp.MENU_ID_END_SESSION, 1, R.string.menu_end_session);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean mainVisible = false;

        if (mState == STATE_SECONDARY && mAcceptUsersInput) {
            mainVisible = true;
        }

        menu.findItem(StkApp.MENU_ID_END_SESSION).setVisible(mainVisible);

        return mainVisible;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!mAcceptUsersInput) {
            return true;
        }
        /*UNISOC: Feature bug @{*/
        if (appService != null) {
            appService.setmHomeKeyEvent(false);
        }
        /*UNISOC: @}*/
        switch (item.getItemId()) {
        case StkApp.MENU_ID_END_SESSION:
            // send session end response.
            sendResponse(StkAppService.RES_ID_END_SESSION);
            finish();
            return true;
        default:
            break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        CatLog.d(this, "onCreateContextMenu");
        boolean helpVisible = false;
        if (mStkMenu != null) {
            helpVisible = mStkMenu.helpAvailable;
        }
        if (helpVisible) {
            CatLog.d(this, "add menu");
            menu.add(0, CONTEXT_MENU_HELP, 0, R.string.help);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            return false;
        }
        switch (item.getItemId()) {
            case CONTEXT_MENU_HELP:
                int position = info.position;
                CatLog.d(this, "Position:" + position);
                Item stkItem = getSelectedItem(position);
                if (stkItem != null) {
                    CatLog.d(this, "item id:" + stkItem.id);
                    sendResponse(StkAppService.RES_ID_MENU_SELECTION, stkItem.id, true);
                }
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        CatLog.d(LOG_TAG, "onSaveInstanceState: " + mSlotId);
        outState.putInt(STATE_KEY, mState);
        outState.putBoolean(ACCEPT_USERS_INPUT_KEY, mAcceptUsersInput);
        outState.putBoolean(RESPONSE_SENT_KEY, mIsResponseSent);
        outState.putLong(ALARM_TIME_KEY, mAlarmTime);
        outState.putBoolean(PENDING, mIsPending);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        CatLog.d(LOG_TAG, "onRestoreInstanceState: " + mSlotId);
        mState = savedInstanceState.getInt(STATE_KEY);
        mAcceptUsersInput = savedInstanceState.getBoolean(ACCEPT_USERS_INPUT_KEY);
        if (!mAcceptUsersInput) {
            // Check the latest information as the saved instance state can be outdated.
            if ((mState == STATE_MAIN) && appService.isMainMenuAvailable(mSlotId)) {
                mAcceptUsersInput = true;
            } else {
                showProgressBar(true);
            }
        }
        mIsResponseSent = savedInstanceState.getBoolean(RESPONSE_SENT_KEY);

        mAlarmTime = savedInstanceState.getLong(ALARM_TIME_KEY, NO_SELECT_ALARM);
        if (mAlarmTime != NO_SELECT_ALARM) {
            startTimeOut();
        }

        if (!mIsResponseSent && !savedInstanceState.getBoolean(PENDING)) {
            // If this is in the foreground and no response has been sent to the card,
            // this must not be registered as pending activity by the previous instance.
            // No need to renew nor clear pending activity in this case.
        } else {
            // Renew the instance of the pending activity.
            setPendingState(true);
        }
    }

    private void setPendingState(boolean on) {
        if (mState == STATE_SECONDARY) {
            if (mIsPending != on) {
                appService.getStkContext(mSlotId).setPendingActivityInstance(on ? this : null);
                mIsPending = on;
            }
        }
    }

    private void cancelTimeOut() {
        if (mAlarmTime != NO_SELECT_ALARM) {
            CatLog.d(LOG_TAG, "cancelTimeOut - slot id: " + mSlotId);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(mAlarmListener);
            mAlarmTime = NO_SELECT_ALARM;
        }
    }

    private void startTimeOut() {
        // No need to set alarm if this is the main menu or device sent TERMINAL RESPONSE already.
        if (mState != STATE_SECONDARY || mIsResponseSent) {
            return;
        }

        if (mAlarmTime == NO_SELECT_ALARM) {
            mAlarmTime = SystemClock.elapsedRealtime() + StkApp.UI_TIMEOUT;
        }

        CatLog.d(LOG_TAG, "startTimeOut: " + mAlarmTime + "ms, slot id: " + mSlotId);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, mAlarmTime, SELECT_ALARM_TAG,
                mAlarmListener, null);
    }

    // Bind list adapter to the items list.
    private void displayMenu() {

        if (mStkMenu != null) {
            String title = mStkMenu.title == null ? getString(R.string.app_name) : mStkMenu.title;
            // Display title & title icon
            if (mStkMenu.titleIcon != null) {
                mTitleIconView.setImageBitmap(mStkMenu.titleIcon);
                mTitleIconView.setVisibility(View.VISIBLE);
                mTitleTextView.setVisibility(View.INVISIBLE);
                if (!mStkMenu.titleIconSelfExplanatory) {
                    mTitleTextView.setText(title);
                    mTitleTextView.setVisibility(View.VISIBLE);
                }
            } else {
                mTitleIconView.setVisibility(View.GONE);
                mTitleTextView.setVisibility(View.VISIBLE);
                mTitleTextView.setText(title);
            }
            // create an array adapter for the menu list
            StkMenuAdapter adapter = new StkMenuAdapter(this,
                    mStkMenu.items, mStkMenu.itemsIconSelfExplanatory);
            // Bind menu list to the new adapter.
            setListAdapter(adapter);
            // Set default item
            setSelection(mStkMenu.defaultItem);
        }
    }

    private void showProgressBar(boolean show) {
        if (show) {
            mProgressView.setIndeterminate(true);
            mProgressView.setVisibility(View.VISIBLE);
        } else {
            mProgressView.setIndeterminate(false);
            mProgressView.setVisibility(View.GONE);
        }
    }

    private void initFromIntent(Intent intent) {

        if (intent != null) {
            mState = intent.getIntExtra("STATE", STATE_MAIN);
            mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
            CatLog.d(LOG_TAG, "slot id: " + mSlotId + ", state: " + mState);
        } else {
            CatLog.d(LOG_TAG, "finish!");
            finish();
        }
    }

    private Item getSelectedItem(int position) {
        Item item = null;
        if (mStkMenu != null) {
            try {
                item = mStkMenu.items.get(position);
            } catch (IndexOutOfBoundsException e) {
                if (StkApp.DBG) {
                    CatLog.d(LOG_TAG, "IOOBE Invalid menu");
                }
            } catch (NullPointerException e) {
                if (StkApp.DBG) {
                    CatLog.d(LOG_TAG, "NPE Invalid menu");
                }
            }
        }
        return item;
    }

    private void sendResponse(int resId) {
        sendResponse(resId, 0, false);
    }

    private void sendResponse(int resId, int itemId, boolean help) {
        CatLog.d(LOG_TAG, "sendResponse resID[" + resId + "] itemId[" + itemId +
            "] help[" + help + "]");

        // Disallow user operation temporarily until receiving the result of the response.
        mAcceptUsersInput = false;
        if (resId == StkAppService.RES_ID_MENU_SELECTION) {
            showProgressBar(true);
        }
        cancelTimeOut();

        mIsResponseSent = true;
        Bundle args = new Bundle();
        args.putInt(StkAppService.RES_ID, resId);
        args.putInt(StkAppService.MENU_SELECTION, itemId);
        args.putBoolean(StkAppService.HELP, help);
        appService.sendResponse(args, mSlotId);

        // This instance should be set as a pending activity and finished by the service.
        if (resId != StkAppService.RES_ID_END_SESSION) {
            setPendingState(true);
        }
    }

    private final BroadcastReceiver mLocalBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (StkAppService.SESSION_ENDED.equals(intent.getAction())) {
                int slotId = intent.getIntExtra(StkAppService.SLOT_ID, 0);
                /* UNISOC: Feature bug @{ */
                CatLog.d(LOG_TAG, "mLocalBroadcastReceiver SESSION_ENDED, mState: "
                        + mState + " slotId:" + slotId);
                /*UNISOC: @}*/
                if ((mState == STATE_MAIN) && (mSlotId == slotId)) {
                    mAcceptUsersInput = true;
                    showProgressBar(false);
                }
            } else if (StkAppService.REFRESH_UNINSTALL.equals(intent.getAction())) {
                CatLog.d(LOG_TAG, "mLocalBroadcastReceiver REFRESH_UNINSTALL");
                cancelTimeOut();
                finish();

            /* UNISOC: For bug 1466545 @{ */
            } else if (StkAppService.CARD_ABSENT.equals(intent.getAction())) {
                CatLog.d(LOG_TAG, "mLocalBroadcastReceiver CARD_ABSENT");
                int slotId = intent.getIntExtra(StkAppService.SLOT_ID, 0);
                if (slotId != mSlotId) return;
                finish();
            }
            /* UNISOC: For bug 1466545 @} */
        }
    };

    private final AlarmManager.OnAlarmListener mAlarmListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    CatLog.d(LOG_TAG, "The alarm time is reached");
                    mAlarmTime = NO_SELECT_ALARM;
                    sendResponse(StkAppService.RES_ID_TIMEOUT);
                }
            };

    /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            CatLog.d(LOG_TAG, "mReceiver action: " + action);
            if (action == null ) return;
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                sendResponse(StkAppService.RES_ID_END_SESSION);
                finishAndRemoveTask();
            } else if(action.equals(Intent.ACTION_SHUTDOWN)){
                finishAndRemoveTask();
            }
        }
    };
    /*UNISOC: @} */

    /*UNISOC: Feature bug @{*/
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        CatLog.d(LOG_TAG, " onNewIntent");
        initFromIntent(intent);
        cancelTimeOut();
        mAcceptUsersInput = true;
    }
    /*UNISOC: @} */
}
