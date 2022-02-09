package com.android.stk;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import com.android.internal.telephony.cat.CatLog;
import android.widget.Toast;

public class RemoveInputMethodEditText extends EditText {
    private static final String LOG_TAG = "RemoveInputMethodEditText";

    public RemoveInputMethodEditText(Context context) {
        super(context);
        // TODO Auto-generated constructor stub
    }

    public RemoveInputMethodEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        // TODO Auto-generated constructor stub
    }

    public RemoveInputMethodEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        // TODO Auto-generated constructor stub
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        // TODO Auto-generated method stub
        int keyCode = event.getKeyCode();
        CatLog.d(LOG_TAG, "dispatchKeyEventPreIme--removeInputMethod first "+ " keyCode = " + keyCode);
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            final InputMethodManager inputMethodManager = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager.isActive() && event.getAction() == MotionEvent.ACTION_DOWN) {
                inputMethodManager.hideSoftInputFromWindow(this.getWindowToken(),
                        InputMethodManager.HIDE_NOT_ALWAYS);
            }
        }
        return super.dispatchKeyEventPreIme(event);
    }
}
