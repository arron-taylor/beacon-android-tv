package org.jointhebeacon.display;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Launches the Beacon startup screen as soon as Android finishes booting. */
public final class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BeaconDisplayBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        if (BeaconDisplayActivity.hasActiveSessionForCurrentBoot(context)) {
            Log.i(TAG, "Received BOOT_COMPLETED, but a Beacon session is already active for "
                    + "this boot; skipping the fallback launch.");
            return;
        }

        Log.i(TAG, "Received BOOT_COMPLETED; attempting to launch BeaconDisplayActivity now.");

        Intent launchIntent = new Intent(context, BeaconDisplayActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            context.startActivity(launchIntent);
            Log.i(TAG, "BeaconDisplayActivity launch request sent.");
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to launch BeaconDisplayActivity after boot.", exception);
        }
    }
}
