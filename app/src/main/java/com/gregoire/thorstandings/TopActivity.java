package com.gregoire.thorstandings;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Two-screen mode: this runs on the TOP screen and shows a giant Board for the followed game.
 * All data and taps go through MainActivity (on the bottom screen), so both screens stay in sync.
 */
public class TopActivity extends Activity {
    static TopActivity current;
    private ThorView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivity main = MainActivity.instance;
        if (main == null) { finish(); return; }
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        view = new ThorView(this);
        view.setTopMode(true);
        setContentView(view);
        hideBars();
        main.attachTop(view);
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        MainActivity m = MainActivity.instance;
        if (m != null && m.ignoreRepeat(keyCode, event)) return true;
        if (m != null && view != null && m.handleKey(view, keyCode)) return true;
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;   // closing the top screen is done with the two-screen button
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        MainActivity m = MainActivity.instance;
        if (m != null && view != null && m.handleStick(view, e)) return true;
        return super.onGenericMotionEvent(e);
    }

    /** Home pressed while the top screen had focus: send both screens home. */
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (MainActivity.instance != null && !isFinishing()) MainActivity.instance.leaveHome(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
        if (MainActivity.instance != null && view != null) MainActivity.instance.detachTop(view);
    }
}
