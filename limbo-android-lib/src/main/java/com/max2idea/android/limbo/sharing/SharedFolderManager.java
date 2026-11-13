package com.max2idea.android.limbo.sharing;

import android.content.Context;
import android.util.Log;

import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.machine.Machine;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboSettingsManager;
import com.max2idea.android.limbo.toast.ToastUtils;

import java.io.File;

/**
 * Owns the lifetime of the live shared folder server. It is started right before the vm
 * process starts and stopped when the vm exits.
 */
public class SharedFolderManager {
    private static final String TAG = "SharedFolderManager";
    private static WebDavServer server;

    /**
     * Starts the server if the machine has a shared folder and uses the user network.
     * Failures are reported with a toast and never prevent the vm from starting.
     */
    public static synchronized void start(Context context, Machine machine) {
        stop();
        try {
            if (!Config.enableSharedFolder || machine == null
                    || !LimboSettingsManager.getEnableSharedFolderServer(context))
                return;
            String path = machine.getSharedFolderPath();
            if (path == null || path.trim().length() == 0 || path.equals("None"))
                return;

            if (!"User".equals(machine.getNetwork())) {
                ToastUtils.toastLong(context, context.getString(R.string.sharedFolderNeedsUserNetwork));
                return;
            }
            if (path.startsWith("content://")) {
                ToastUtils.toastLong(context, context.getString(R.string.sharedFolderNotSupported));
                return;
            }

            int port = LimboSettingsManager.getSharedFolderPort(context);
            server = new WebDavServer(new File(path), port,
                    LimboSettingsManager.getSharedFolderReadOnly(context),
                    LimboSettingsManager.getSharedFolderPassword(context),
                    new WebDavServer.Logger() {
                        @Override
                        public void log(String message, Throwable error) {
                            Log.w(TAG, message, error);
                        }
                    });
            server.start();
            String url = "http://" + Config.sharedFolderGuestHost + ":" + server.getPort() + "/";
            Log.i(TAG, "Sharing " + path + " at " + url);
            ToastUtils.toastLong(context, context.getString(R.string.sharedFolderStarted, url));
        } catch (Exception e) {
            Log.e(TAG, "Could not start shared folder server", e);
            server = null;
            ToastUtils.toastLong(context, context.getString(R.string.sharedFolderFailed, e.getMessage()));
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
            Log.i(TAG, "Shared folder server stopped");
        }
    }
}
