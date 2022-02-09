
package com.android.stk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.android.internal.telephony.cat.CatLog;
import com.android.internal.telephony.PhoneConstants;

import android.telephony.TelephonyManager;

import android.view.Gravity;
import android.widget.Toast;

/**
 * Launcher class. Serve as the app's MAIN activity, send an intent to the StkAppService and finish.
 */
public class StkMain2 extends Activity {
    private static final String LOG_TAG = "StkMain2";
    private static final int mMySimId1 = 1;
    private Context mContext = null;
    private TelephonyManager mTm = null;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        CatLog.d(LOG_TAG, "onCreate+");
        mContext = getBaseContext();
        mTm = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        if (null != mTm &&
                mTm.getSimState(mMySimId1) == TelephonyManager.SIM_STATE_READY) {
            CatLog.d(LOG_TAG, "launchSTKMainMenu");
            launchSTKMainMenu(mMySimId1);
        } else {
            showTextToast(mContext, R.string.no_sim_card_inserted);
        }
        finish();
    }

    @Override
    protected void onPause() {
        CatLog.d(LOG_TAG, "onPause()");
        super.onPause();

        finish();
    }

    private void launchSTKMainMenu(int slotId) {
        Bundle args = new Bundle();
        CatLog.d(LOG_TAG, "launchSTKMainMenu.");
        args.putInt(StkAppService.OPCODE, StkAppService.OP_LAUNCH_APP);
        args.putInt(StkAppService.SLOT_ID
                , PhoneConstants.SIM_ID_1 + slotId);
        startService(new Intent(this, StkAppService.class)
                .putExtras(args));
    }

    private void showTextToast(Context context, int resId) {
        Toast toast = Toast.makeText(context, resId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }
}
