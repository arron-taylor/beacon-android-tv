package org.jointhebeacon.display;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.androidbrowserhelper.trusted.LauncherActivity;
import com.google.androidbrowserhelper.trusted.TwaLauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.net.ssl.SSLException;

/**
 * Native startup gate for the dedicated Beacon TV display.
 *
 * <p>The Activity immediately renders a small fullscreen loader, checks the existing public
 * Digital Asset Links document over HTTPS, and launches the unchanged Trusted Web Activity as
 * soon as the Beacon origin is reachable. Chrome remains the rendering engine.</p>
 */
public final class BeaconDisplayActivity extends LauncherActivity {
    private static final String TAG = "BeaconDisplayStartup";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final ComponentName STOCK_ANDROID_LAUNCHER = new ComponentName(
            "com.droidlogic.mboxlauncher",
            "com.droidlogic.mboxlauncher.Launcher");
    private static final String SESSION_PREFERENCES = "beacon_display_session";
    private static final String LAST_TWA_BOOT_COUNT = "last_twa_boot_count";
    private static final String GLOBAL_BOOT_COUNT = "boot_count";
    private static volatile boolean sessionActive;

    private static final long RETRY_DELAY_MILLIS = 3_000L;
    private static final long SHOW_RECOVERY_ACTIONS_DELAY_MILLIS = 10_000L;
    private static final int CONNECT_TIMEOUT_MILLIS = 2_500;
    private static final int READ_TIMEOUT_MILLIS = 2_500;

    private Handler mainHandler;
    private ExecutorService connectivityExecutor;
    private TextView statusText;
    private LinearLayout recoveryActions;
    private Button networkSettingsButton;
    private boolean recoveryButtonsUnavailable;

    private boolean activityResumed;
    private boolean checkInFlight;
    private volatile boolean destroyed;
    private boolean twaLaunchRequested;
    private int connectivityAttempt;
    private long loaderStartedAt;

    private final Runnable connectivityCheckRunnable = this::startConnectivityCheck;
    private final Runnable showRecoveryActionsRunnable = this::showRecoveryActions;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        super.onCreate(savedInstanceState);
        if (isFinishing()) {
            return;
        }

        sessionActive = true;

        mainHandler = new Handler(Looper.getMainLooper());
        connectivityExecutor = Executors.newSingleThreadExecutor();
        loaderStartedAt = SystemClock.elapsedRealtime();

        setContentView(R.layout.activity_beacon_display);
        statusText = findViewById(R.id.startup_status);
        recoveryActions = findViewById(R.id.recovery_actions);

        applyImmersiveMode();
        mainHandler.postDelayed(
                showRecoveryActionsRunnable,
                SHOW_RECOVERY_ACTIONS_DELAY_MILLIS);
        Log.i(TAG, "Native startup screen visible; waiting for Beacon HTTPS reachability.");
    }

    @Override
    protected boolean shouldLaunchImmediately() {
        return false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(TAG, "Received another launch intent; preserving the existing Beacon session.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainHandler == null || isFinishing() || twaLaunchRequested) {
            return;
        }

        activityResumed = true;
        applyImmersiveMode();
        Log.i(TAG, "Startup screen resumed; checking Beacon connectivity now.");
        scheduleConnectivityCheck(0L);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(connectivityCheckRunnable);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sessionActive = false;
        destroyed = true;
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (connectivityExecutor != null) {
            connectivityExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected TwaLauncher createTwaLauncher() {
        return new TwaLauncher(this, CHROME_PACKAGE);
    }

    static boolean hasActiveSessionForCurrentBoot(Context context) {
        if (sessionActive) {
            return true;
        }

        int currentBootCount = getBootCount(context);
        return currentBootCount >= 0
                && context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
                .getInt(LAST_TWA_BOOT_COUNT, -1) == currentBootCount;
    }

    private void scheduleConnectivityCheck(long delayMillis) {
        if (destroyed || twaLaunchRequested || !activityResumed || mainHandler == null) {
            return;
        }

        mainHandler.removeCallbacks(connectivityCheckRunnable);
        mainHandler.postDelayed(connectivityCheckRunnable, delayMillis);
    }

    private void startConnectivityCheck() {
        if (destroyed || twaLaunchRequested || !activityResumed || checkInFlight) {
            return;
        }

        checkInFlight = true;
        int attempt = ++connectivityAttempt;
        Log.d(TAG, "Starting Beacon connectivity check #" + attempt + ".");

        try {
            connectivityExecutor.execute(() -> {
                ProbeResult result = probeBeacon();
                if (!destroyed) {
                    mainHandler.post(() -> handleProbeResult(attempt, result));
                }
            });
        } catch (RejectedExecutionException exception) {
            checkInFlight = false;
            if (!destroyed) {
                Log.e(TAG, "Unable to schedule Beacon connectivity check.", exception);
                scheduleConnectivityCheck(RETRY_DELAY_MILLIS);
            }
        }
    }

    private ProbeResult probeBeacon() {
        HttpURLConnection connection = null;
        try {
            URL healthUrl = new URL(getString(R.string.health_check_url));
            connection = (HttpURLConnection) healthUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Beacon-Display/0.3");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return ProbeResult.unreachable("HTTP " + responseCode);
            }

            try (InputStream response = connection.getInputStream()) {
                if (response.read() == -1) {
                    return ProbeResult.unreachable("HTTP 200 with an empty response");
                }
            }
            return ProbeResult.reachable();
        } catch (UnknownHostException exception) {
            return ProbeResult.unreachable("DNS unavailable");
        } catch (SocketTimeoutException exception) {
            return ProbeResult.unreachable("connection timed out");
        } catch (SSLException exception) {
            return ProbeResult.unreachable("HTTPS/TLS failed");
        } catch (IOException exception) {
            return ProbeResult.unreachable(exception.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void handleProbeResult(int attempt, ProbeResult result) {
        checkInFlight = false;
        if (destroyed || twaLaunchRequested) {
            return;
        }
        if (!activityResumed) {
            Log.d(TAG, "Ignoring connectivity check #" + attempt
                    + " while the startup screen is not visible.");
            return;
        }

        if (result.reachable) {
            Log.i(TAG, "Beacon reachable on connectivity check #" + attempt
                    + "; launching the Trusted Web Activity.");
            launchTrustedWebActivity();
            return;
        }

        Log.w(TAG, "Beacon connectivity check #" + attempt + " failed: " + result.detail + ".");
        if (SystemClock.elapsedRealtime() - loaderStartedAt
                >= SHOW_RECOVERY_ACTIONS_DELAY_MILLIS) {
            showRecoveryActions();
        }
        scheduleConnectivityCheck(RETRY_DELAY_MILLIS);
    }

    private void showRecoveryActions() {
        if (destroyed || twaLaunchRequested || statusText == null || recoveryActions == null) {
            return;
        }

        statusText.setText(R.string.waiting_for_connection);
        if (recoveryActions.getVisibility() != View.VISIBLE) {
            if (!ensureRecoveryButtons()) {
                return;
            }
            recoveryActions.setVisibility(View.VISIBLE);
            networkSettingsButton.requestFocus();
            Log.i(TAG, "Beacon is still unreachable; showing connection recovery actions.");
        }
    }

    private boolean ensureRecoveryButtons() {
        if (networkSettingsButton != null) {
            return true;
        }
        if (recoveryButtonsUnavailable) {
            return false;
        }

        try {
            Button networkButton = createPlainButton(R.string.open_network_settings);
            Button homeButton = createPlainButton(R.string.open_android_launcher);
            networkButton.setOnClickListener(view -> openNetworkSettings());
            homeButton.setOnClickListener(view -> openAndroidLauncher());

            LinearLayout.LayoutParams networkLayout = new LinearLayout.LayoutParams(
                    dp(220), LinearLayout.LayoutParams.WRAP_CONTENT);
            LinearLayout.LayoutParams homeLayout = new LinearLayout.LayoutParams(
                    dp(220), LinearLayout.LayoutParams.WRAP_CONTENT);
            homeLayout.leftMargin = dp(24);

            recoveryActions.addView(networkButton, networkLayout);
            recoveryActions.addView(homeButton, homeLayout);
            networkSettingsButton = networkButton;
            Log.i(TAG, "Created recovery buttons programmatically for API 25 compatibility.");
            return true;
        } catch (RuntimeException exception) {
            recoveryButtonsUnavailable = true;
            recoveryActions.removeAllViews();
            Log.e(TAG, "Unable to create recovery buttons; connectivity retries will continue.",
                    exception);
            return false;
        }
    }

    private Button createPlainButton(int textResource) {
        Button button = new Button(this, null, 0);
        button.setText(textResource);
        button.setTextColor(Color.BLACK);
        button.setTextSize(17f);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setMinHeight(dp(56));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setBackgroundColor(Color.LTGRAY);
        button.setOnFocusChangeListener((view, hasFocus) ->
                view.setBackgroundColor(hasFocus ? Color.WHITE : Color.LTGRAY));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void launchTrustedWebActivity() {
        if (destroyed || twaLaunchRequested || !activityResumed) {
            return;
        }

        twaLaunchRequested = true;
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Attempting to launch Beacon TWA with Chrome package " + CHROME_PACKAGE + ".");
        try {
            launchTwa();
            rememberTwaLaunchForCurrentBoot();
            connectivityExecutor.shutdownNow();
            Log.i(TAG, "Beacon TWA launch request sent.");
        } catch (RuntimeException exception) {
            twaLaunchRequested = false;
            Log.e(TAG, "Beacon TWA launch failed; keeping the native recovery screen active.",
                    exception);
            showRecoveryActions();
            scheduleConnectivityCheck(RETRY_DELAY_MILLIS);
        }
    }

    @SuppressLint("ApplySharedPref")
    private void rememberTwaLaunchForCurrentBoot() {
        int currentBootCount = getBootCount(this);
        if (currentBootCount < 0) {
            Log.w(TAG, "Android boot count is unavailable; using in-process session tracking.");
            return;
        }

        // This tiny synchronous write keeps the boot receiver idempotent even if Android reclaims
        // the wrapper process after Chrome takes over the screen.
        boolean saved = getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(LAST_TWA_BOOT_COUNT, currentBootCount)
                .commit();
        if (!saved) {
            Log.w(TAG, "Unable to persist the TWA boot marker; using in-process tracking.");
        }
    }

    private static int getBootCount(Context context) {
        return Settings.Global.getInt(
                context.getContentResolver(),
                GLOBAL_BOOT_COUNT,
                -1);
    }

    private void openNetworkSettings() {
        Log.i(TAG, "Opening Android Wi-Fi settings.");
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException exception) {
            Log.w(TAG, "Wi-Fi settings are unavailable; opening general Android settings.");
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException fallbackException) {
                Log.e(TAG, "Android settings are unavailable on this device.", fallbackException);
            }
        }
    }

    private void openAndroidLauncher() {
        Log.i(TAG, "Opening the stock X96 Android launcher by user request.");
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_HOME);
        launcherIntent.setComponent(STOCK_ANDROID_LAUNCHER);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launcherIntent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(TAG, "The stock X96 Android launcher is unavailable; keeping Beacon open.",
                    exception);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private static final class ProbeResult {
        private final boolean reachable;
        private final String detail;

        private ProbeResult(boolean reachable, String detail) {
            this.reachable = reachable;
            this.detail = detail;
        }

        private static ProbeResult reachable() {
            return new ProbeResult(true, "HTTP 200");
        }

        private static ProbeResult unreachable(String detail) {
            return new ProbeResult(false, detail);
        }
    }
}
