package com.cordova.plugin.android.fingerprintauth;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaInterface;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.FragmentTransaction;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.fingerprint.FingerprintManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.lang.String;


@TargetApi(23)
public class FingerprintAuth extends CordovaPlugin {
    private static final String TAG = "FingerprintAuth";
    static String packageName;
    private static final String DIALOG_FRAGMENT_TAG = "FpAuthDialog";
    private static final int PERMISSIONS_REQUEST_FINGERPRINT = 346437;
    private static final int REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS = 1;
    private KeyguardManager mKeyguardManager;
    private FingerprintAuthenticationDialogFragment mFragment;
    private FingerprintManager mFingerPrintManager;
    private static CallbackContext mCallbackContext;
    private static PluginResult mPluginResult;

    static boolean mDisableBackup = false;
    static int mMaxAttempts = 5;  // one more than the device default to prevent a 2nd callback
    private String mLangCode = "en_US";
    static String mDialogTitle;
    static String mDialogMessage;
    static String mDialogHint;

    public enum PluginError {
        FINGERPRINT_CANCELLED,
        FINGERPRINT_ERROR,
        FINGERPRINT_NOT_AVAILABLE,
        FINGERPRINT_PERMISSION_DENIED,
        JSON_EXCEPTION,
        MINIMUM_SDK,
        SECURITY_EXCEPTION,
    }

    public FingerprintAuth() {}

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.v(TAG, "Init FingerprintAuth");
        packageName = cordova.getActivity().getApplicationContext().getPackageName();
        mPluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }

        mKeyguardManager = cordova.getActivity().getSystemService(KeyguardManager.class);
        mFingerPrintManager = cordova.getActivity().getApplicationContext()
                .getSystemService(FingerprintManager.class);
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArray of arguments for the plugin.
     * @param callbackContext The callback id used when calling back into JavaScript.
     * @return A PluginResult object with a status and message.
     */
    public boolean execute(final String action, JSONArray args, CallbackContext callbackContext)
            throws JSONException {
        mCallbackContext = callbackContext;

        if (android.os.Build.VERSION.SDK_INT < 23) {
            Log.e(TAG, "minimum SDK version 23 required");
            mPluginResult = new PluginResult(PluginResult.Status.ERROR);
            mCallbackContext.error(PluginError.MINIMUM_SDK.name());
            mCallbackContext.sendPluginResult(mPluginResult);
            return true;
        }

        Log.v(TAG, "FingerprintAuth action: " + action);
        final JSONObject arg_object = args.getJSONObject(0);

        JSONObject resultJson = new JSONObject();

        if (action.equals("availability")) {
            if (!cordova.hasPermission(Manifest.permission.USE_FINGERPRINT)) {
                cordova.requestPermission(this, PERMISSIONS_REQUEST_FINGERPRINT,
                        Manifest.permission.USE_FINGERPRINT);
            } else {
                sendAvailabilityResult();
            }
            return true;
        } else if (action.equals("authenticate")) {
            Resources res = cordova.getActivity().getResources();
            // Change locale settings in the app.
            DisplayMetrics dm = res.getDisplayMetrics();
            Configuration conf = res.getConfiguration();
            // A length of 5 entales a region specific locale string, ex: zh_HK.
            // The two argument Locale constructor signature must be used in that case.
            if (arg_object.has("locale")) {
                mLangCode = arg_object.getString("locale");
                Log.d(TAG, "Change language to locale: " + mLangCode);
            }

            if (mLangCode.length() == 5) {
                conf.locale = new Locale(mLangCode.substring(0, 2).toLowerCase(),
                        mLangCode.substring(mLangCode.length() - 2).toUpperCase());
            } else {
                conf.locale = new Locale(mLangCode.toLowerCase());
            }
            res.updateConfiguration(conf, dm);
            if (isFingerprintAuthAvailable()) {
                cordova.getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        // Set up the crypto object for later. The object will be authenticated by use
                        // of the fingerprint.
                        mFragment = new FingerprintAuthenticationDialogFragment();
                        mFragment.setCancelable(false);
                        // Show the fingerprint dialog. The user has the option to use the fingerprint with
                        // crypto, or you can fall back to using a server-side verified password.
                        //mFragment.setCryptoObject(new FingerprintManager.CryptoObject(mCipher));
                        FragmentTransaction transaction = cordova.getActivity().getFragmentManager().beginTransaction();
                        transaction.add(mFragment, DIALOG_FRAGMENT_TAG);
                        transaction.commitAllowingStateLoss();
                    }
                });
                mPluginResult.setKeepCallback(true);
            } else {
                /***
                 Use backup
                 */
                Log.v(TAG, "In backup");
                if (useBackupLockScreen()) {
                    Log.v(TAG, "useBackupLockScreen: true");
                } else {
                    Log.v(TAG, "useBackupLockScreen: false");
                }

                if (useBackupLockScreen()) {
                    showAuthenticationScreen();
                } else {
                    Log.e(TAG, "Fingerprint authentication not available");
                    mPluginResult = new PluginResult(PluginResult.Status.ERROR);
                    mCallbackContext.error(PluginError.FINGERPRINT_NOT_AVAILABLE.name());
                    mCallbackContext.sendPluginResult(mPluginResult);
                }
            }
            return true;
        } else if (action.equals("useLuckScreen")) {
            showAuthenticationScreen();
            return true;
        } else if (action.equals("isLuckScreenAvailable")) {
            mPluginResult = new PluginResult(PluginResult.Status.OK);

            if (useBackupLockScreen()) {
                resultJson.put("isLuckScreen", true);
            } else {
                resultJson.put("isLuckScreen", false);
            }
            mCallbackContext.success(resultJson);
            mCallbackContext.sendPluginResult(mPluginResult);
            return true;
        }
        return false;
    }

    private boolean isFingerprintAuthAvailable() throws SecurityException {
        return mFingerPrintManager.isHardwareDetected() && mFingerPrintManager.hasEnrolledFingerprints();
    }

    private void sendAvailabilityResult() {
        String errorMessage = null;
        JSONObject resultJson = new JSONObject();
        try {
            resultJson.put("isAvailable", isFingerprintAuthAvailable());
            resultJson.put("isHardwareDetected", mFingerPrintManager.isHardwareDetected());
            resultJson.put("hasEnrolledFingerprints", mFingerPrintManager.hasEnrolledFingerprints());
            mPluginResult = new PluginResult(PluginResult.Status.OK);
            mCallbackContext.success(resultJson);
            mCallbackContext.sendPluginResult(mPluginResult);
        } catch (JSONException e) {
            Log.e(TAG, "Availability Result Error: JSONException: " + e.toString());
            errorMessage = PluginError.JSON_EXCEPTION.name();
        } catch (SecurityException e) {
            Log.e(TAG, "Availability Result Error: SecurityException: " + e.toString());
            errorMessage = PluginError.SECURITY_EXCEPTION.name();
        }
        if (null != errorMessage) {
            Log.e(TAG, errorMessage);
            setPluginResultError(errorMessage);
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        super.onRequestPermissionResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSIONS_REQUEST_FINGERPRINT: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    sendAvailabilityResult();
                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    Log.e(TAG, "Fingerprint permission denied.");
                    setPluginResultError(PluginError.FINGERPRINT_PERMISSION_DENIED.name());
                }
                return;
            }
        }
    }

    static void onAuthenticated(boolean withFingerprint, FingerprintManager.AuthenticationResult result) {
        JSONObject resultJson = new JSONObject();
        mPluginResult = new PluginResult(PluginResult.Status.OK);

        try {
            if (withFingerprint) {
                resultJson.put("withFingerprint", true);
                mCallbackContext.success(resultJson);

            } else {
                resultJson.put("withBackup", true);
                mCallbackContext.success(resultJson);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        mCallbackContext.sendPluginResult(mPluginResult);
    }

    static void onCancelled() {
        mCallbackContext.error(PluginError.FINGERPRINT_CANCELLED.name());
    }

    static void onError(CharSequence errString) {
        mCallbackContext.error(PluginError.FINGERPRINT_ERROR.name());
        Log.e(TAG, errString.toString());
    }

    private static void setPluginResultError(String errorMessage) {
        mCallbackContext.error(errorMessage);
        mPluginResult = new PluginResult(PluginResult.Status.ERROR);
    }

    /*********************************************************************
     Backup for older devices without fingerprint hardware/software
     **********************************************************************/
    private boolean useBackupLockScreen() {
        if (!mKeyguardManager.isKeyguardSecure()) {
            return false;
        } else {
            return true;
        }

    }

    private void showAuthenticationScreen() {
        Intent intent = mKeyguardManager.createConfirmDeviceCredentialIntent(null, null);
        if (intent != null) {
            cordova.setActivityResultCallback(this);
            cordova.getActivity().startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS) {
            if (resultCode == cordova.getActivity().RESULT_OK) {
                onAuthenticated(false, null);
            } else {
                onCancelled();
            }
        }
    }
}
