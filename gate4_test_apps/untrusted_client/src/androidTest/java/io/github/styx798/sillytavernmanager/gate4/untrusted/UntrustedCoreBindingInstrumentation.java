package io.github.styx798.sillytavernmanager.gate4.untrusted;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

public final class UntrustedCoreBindingInstrumentation extends Instrumentation {
    private static final String PRODUCT_PACKAGE =
            "io.github.styx798.sillytavernmanager";
    private static final String CORE_SERVICE =
            PRODUCT_PACKAGE + ".stmcore.StmCoreService";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        final Bundle result = new Bundle();
        try {
            final Context untrustedContext = getTargetContext().getApplicationContext();
            final ApplicationInfo product = untrustedContext.getPackageManager()
                    .getApplicationInfo(PRODUCT_PACKAGE, 0);
            final int untrustedUid = Process.myUid();
            if (untrustedUid == product.uid) {
                throw new IllegalStateException(
                        "The Gate 4 test client shares the product Linux UID");
            }

            final boolean[] connected = {false};
            final ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    connected[0] = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // No-op: an untrusted client must never reach this callback.
                }
            };
            final Intent intent = new Intent().setComponent(
                    new ComponentName(PRODUCT_PACKAGE, CORE_SERVICE));
            boolean bound = false;
            SecurityException rejection = null;
            try {
                bound = untrustedContext.bindService(
                        intent,
                        connection,
                        Context.BIND_AUTO_CREATE);
            } catch (SecurityException expected) {
                rejection = expected;
            } finally {
                if (bound) {
                    untrustedContext.unbindService(connection);
                }
            }
            if (bound || connected[0]) {
                throw new IllegalStateException(
                        "A non-authorized local app bound the private STM Core service");
            }

            result.putInt("gate4_untrusted_uid", untrustedUid);
            result.putInt("gate4_product_uid", product.uid);
            result.putString("gate4_untrusted_package", untrustedContext.getPackageName());
            result.putString("gate4_product_package", PRODUCT_PACKAGE);
            result.putString(
                    "gate4_binding_rejection",
                    rejection == null ? "bindService=false" : rejection.getClass().getSimpleName());
            result.putString("gate4_security_result", "passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString(
                    "failure",
                    error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
