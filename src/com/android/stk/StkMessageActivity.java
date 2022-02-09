
package com.android.stk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.telephony.cat.CatLog;

public class StkMessageActivity extends Activity {
    private static final String LOG_TAG = "StkMessageActivity";

    private String mMessageText = "";
    private Bitmap mIcon = null;
    private boolean mIconSelfExplanatory = false;
    private AlertDialog mAlertDialog;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setPositiveButton(R.string.button_ok, new
                DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        CatLog.d(LOG_TAG, "OK Clicked!");
                        finish();
                    }
                });

        alertDialogBuilder.setNegativeButton(R.string.button_cancel, new
                DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog,int id) {
                        CatLog.d(LOG_TAG, "Cancel Clicked!");
                        finish();
                    }
                });

        alertDialogBuilder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                CatLog.d(LOG_TAG, "Moving backward!");
                finish();
            }
        });
        alertDialogBuilder.create();

        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.stk_msg_dialog, null);
        alertDialogBuilder.setView(dialogView);
        TextView tv = (TextView) dialogView.findViewById(R.id.message);
        ImageView iv = (ImageView) dialogView.findViewById(R.id.icon);

        mMessageText = StkAppService.idleModeText;
        mIcon = StkAppService.idleModeIcon;
        mIconSelfExplanatory = StkAppService.idleModeIconSelfExplanatory;

        if (!(mIconSelfExplanatory && mIcon != null)) {
            tv.setText(mMessageText);
        }

        if (mIcon == null) {
            iv.setImageResource(com.android.internal.R.drawable.stat_notify_sim_toolkit);
        } else {
            iv.setImageBitmap(mIcon);
        }

        mAlertDialog = alertDialogBuilder.create();
        mAlertDialog.setCanceledOnTouchOutside(false);
        mAlertDialog.show();
    }

    public void onStop() {
        super.onStop();
        this.finish();
    }
}


