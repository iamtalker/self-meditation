package com.selfmeditation.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Runs right after this app was replaced by a new version: shows the "update done" notice.
public class ReplacedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Updater.notifyDone(ctx);
    }
}
