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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.cat.TextMessage;
import com.android.internal.telephony.cat.CatLog;

/**
 * Activity used to display tone dialog.
 *
 */
public class ToneDialog extends Activity {
    TextMessage toneMsg = null;
    int mSlotId = -1;
    private AlertDialog mAlertDialog;

    private static final String LOG_TAG = new Object(){}.getClass().getEnclosingClass().getName();

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        CatLog.d(LOG_TAG, "onCreate");
        initFromIntent(getIntent());
        // Register receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(StkAppService.FINISH_TONE_ACTIVITY_ACTION);
        registerReceiver(mFinishActivityReceiver, filter);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.stk_tone_dialog, null);
        alertDialogBuilder.setView(dialogView);

        TextView tv = (TextView) dialogView.findViewById(R.id.message);
        ImageView iv = (ImageView) dialogView.findViewById(R.id.icon);

        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        IntentFilter intent = new IntentFilter();
        intent.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        getBaseContext().registerReceiver(mReceiver, intent);
        /*UNISOC: @}*/

        // set text and icon
        if (null == toneMsg) {
            finish();
            return;
        }
        if (TextUtils.isEmpty(toneMsg.text)) {
            CatLog.d(LOG_TAG, "onCreate - null tone text");
        } else {
            tv.setText(toneMsg.text);
        }

        if (toneMsg.icon == null) {
            iv.setImageResource(com.android.internal.R.drawable.ic_volume);
        } else {
            iv.setImageBitmap(toneMsg.icon);
        }

        if (toneMsg.iconSelfExplanatory && toneMsg.icon != null) {
            tv.setVisibility(View.GONE);
        }

        alertDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        sendStopTone();
                        finish();
                    }
                });

        mAlertDialog = alertDialogBuilder.create();
        mAlertDialog.show();
    }

    @Override
    protected void onDestroy() {
        CatLog.d(LOG_TAG, "onDestroy");
        super.onDestroy();
        if (mFinishActivityReceiver != null) {
            unregisterReceiver(mFinishActivityReceiver);
        }
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
        /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        /*UNISOC: @}*/
    }

    private BroadcastReceiver mFinishActivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Intent received from StkAppService to finish ToneDialog activity,
            // after finishing off playing the tone.
            if (intent.getAction().equals(StkAppService.FINISH_TONE_ACTIVITY_ACTION)) {
                CatLog.d(this, "Finishing Tone dialog activity");
                finish();
            }
        }
    };

    private void initFromIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        toneMsg = intent.getParcelableExtra("TEXT");
        mSlotId = intent.getIntExtra(StkAppService.SLOT_ID, -1);
    }

    // Send stop playing tone to StkAppService, when user presses back key.
    private void sendStopTone() {
        Bundle args = new Bundle();
        args.putInt(StkAppService.OPCODE, StkAppService.OP_STOP_TONE_USER);
        args.putInt(StkAppService.SLOT_ID, mSlotId);
        startService(new Intent(this, StkAppService.class).putExtras(args));
    }

    /*UNISOC: Feature for AirPlane install/unistall Stk @{*/
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            CatLog.d(LOG_TAG, "onReceive, action: " + action );
            if (action == null ) return;
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                CatLog.d(LOG_TAG, "ACTION_AIRPLANE_MODE_CHANGED rcvd finish");
                sendStopTone();
                finish();
            } else if(action.equals(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED)) {
                int slotId = intent.getIntExtra(PhoneConstants.PHONE_KEY, 0);
                if (slotId != mSlotId) return;
                int state = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                        TelephonyManager.SIM_STATE_UNKNOWN);
                if (TelephonyManager.SIM_STATE_ABSENT == state) {
                    CatLog.d(LOG_TAG, "ACTION_SIM_CARD_STATE_CHANGED absent, finish");
                    finish();
                }
            }
        }
    };
    /*UNISOC: @}*/
}
