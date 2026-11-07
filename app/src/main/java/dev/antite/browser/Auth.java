package dev.antite.browser;

import android.app.Activity;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.widget.Toast;

final class Auth {
    private static final long GRACE_MS = 60_000;
    private static long lastOk;

    private Auth() {
    }

    static void require(Activity a, String reason, Runnable ok, Runnable cancelled) {
        if (SystemClock.elapsedRealtime() - lastOk < GRACE_MS && lastOk != 0) {
            ok.run();
            return;
        }
        KeyguardManager km = a.getSystemService(KeyguardManager.class);
        if (km == null || !km.isDeviceSecure()) {
            Toast.makeText(a, "Чтобы открыть пароли, включите блокировку экрана в настройках Android",
                    Toast.LENGTH_LONG).show();
            cancelled.run();
            return;
        }
        new BiometricPrompt.Builder(a)
                .setTitle("Antite")
                .setSubtitle(reason)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(new CancellationSignal(), a.getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                                lastOk = SystemClock.elapsedRealtime();
                                ok.run();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                cancelled.run();
                            }
                        });
    }
}
