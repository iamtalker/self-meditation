package com.selfmeditation.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

// Receives the install result: opens the system "install?" confirmation, reports failures.
public class UpdateReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(confirm);
                } catch (Exception ignored) {
                }
            }
            Updater.step = 3;
            Updater.status = "설치 확인 화면이 떴습니다 — ‘설치’(또는 ‘업데이트’)를 눌러 주세요";
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.step = 5;
            Updater.status = "설치했습니다 — 앱이 새 버전으로 다시 시작됩니다";
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Updater.status = "설치하지 못했습니다: " + (msg != null ? msg : "알 수 없는 오류");
        }
        Updater.changed();
    }
}
