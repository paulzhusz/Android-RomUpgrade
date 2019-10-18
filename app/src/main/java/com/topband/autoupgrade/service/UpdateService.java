package com.topband.autoupgrade.service;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.security.GeneralSecurityException;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RecoverySystem;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;

import com.baidu.commonlib.interfaces.IDownloadListener;
import com.baidu.commonlib.interfaces.IUpgradeInterface;
import com.baidu.commonlib.interfaces.IUpgradeListener;
import com.baidu.otasdk.ota.Constants;
import com.topband.autoupgrade.App;
import com.topband.autoupgrade.R;
import com.topband.autoupgrade.baidu.NewVersionBean;
import com.topband.autoupgrade.config.UsbConfigManager;
import com.topband.autoupgrade.helper.AndroidX;
import com.topband.autoupgrade.receiver.UpdateReceiver;
import com.topband.autoupgrade.util.AppUtils;
import com.topband.autoupgrade.util.FileUtils;

public class UpdateService extends Service {
    private static final String TAG = "UpdateService";

    /**
     * Command
     */
    public static final int COMMAND_NULL = 0;
    public static final int COMMAND_CHECK_LOCAL_UPDATING = 1;
    public static final int COMMAND_CHECK_REMOTE_UPDATING = 2;
    public static final int COMMAND_NEW_VERSION = 3;
    public static final int COMMAND_VERIFY_UPDATE_PACKAGE = 4;
    public static final int COMMAND_DELETE_UPDATE_PACKAGE = 5;

    /**
     * Local upgrade type
     */
    public static final int UPDATE_TYPE_RECOMMEND = 1;
    public static final int UPDATE_TYPE_FORCE = 2;
    private int mUpdateType = UPDATE_TYPE_RECOMMEND;

    /**
     * USB config filename
     */
    public static final String USB_CONFIG_FILENAME = "config.ini";

    /**
     * Local upgrade package search path
     */
    public static final String DATA_ROOT = "/data/media/0";
    public static final String FLASH_ROOT = Environment.getExternalStorageDirectory().getAbsolutePath();
    public static final String SDCARD_ROOT = "/mnt/external_sd";
    public static final String USB_ROOT = "/mnt/usb_storage";
    public static final String USB_ROOT_M = "/mnt/media_rw";
    public static final String CACHE_ROOT = Environment.getDownloadCacheDirectory().getAbsolutePath();
    private static final String[] PACKAGE_FILE_DIRS = {
            DATA_ROOT + "/",
            FLASH_ROOT + "/",
            SDCARD_ROOT + "/",
            USB_ROOT + "/",
    };

    /**
     * Recovery upgrade status storage file
     */
    private static final String RECOVERY_DIR = "/cache/recovery";
    private static final File UPDATE_FLAG_FILE = new File(RECOVERY_DIR + "/last_flag");
    private static final File OTHER_FLAG_FILE = new File(RECOVERY_DIR + "/last_other_flag");

    /**
     * Recovery upgrade result
     */
    private static final String COMMAND_FLAG_SUCCESS = "success";
    private static final String COMMAND_FLAG_UPDATING = "updating";

    /**
     * Upgrade package file name
     */
    public static String sOtaPackageName = "update.zip";

    private static volatile boolean sWorkHandleLocked = false;
    private static volatile boolean isNeedDeletePackage = false;
    private static volatile boolean isFirstStartUp = true;

    private Context mContext;
    private WorkHandler mWorkHandler;
    private Handler mMainHandler;
    private UpdateReceiver mUpdateReceiver;
    private AndroidX mAndroidX;
    private String mLastUpdatePath;
    private Dialog mDialog;
    private ProgressBar mDownloadPgr;

    @Override
    public IBinder onBind(Intent arg0) {
        return mBinder;
    }

    private final LocalBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        /**
         * Install package
         *
         * @param packagePath
         */
        void installPackage(String packagePath) {
            Log.i(TAG, "installPackage, path: " + packagePath);

            try {
                FileUtils.writeFile(OTHER_FLAG_FILE, "watchdog=" + (mAndroidX.watchdogIsOpen() ? "true" : "false"));
                FileUtils.writeFile(UPDATE_FLAG_FILE, "updating$path=" + packagePath);

                /*
                 * Always turn off the watchdog before installing the upgrade package.
                 * Otherwise, the watchdog timeout reset during the upgrade process
                 * will cause serious consequences.
                 */
                mAndroidX.toggleWatchdog(false);

                RecoverySystem.installPackage(mContext, new File(packagePath));
            } catch (IOException e) {
                Log.e(TAG, "installPackage, failed: " + e);
            }
        }

        /**
         * Verify package
         *
         * @param packagePath
         * @return
         */
        boolean verifyPackage(String packagePath) {
            Log.i(TAG, "verifyPackage, path: " + packagePath);

            try {
                RecoverySystem.verifyPackage(new File(packagePath), null, null);
            } catch (GeneralSecurityException e) {
                Log.i(TAG, "verifyPackage, failed: " + e);
                return false;
            } catch (IOException e) {
                Log.i(TAG, "verifyPackage, failed: " + e);
                return false;
            }
            return true;
        }

        /**
         * Delete package
         *
         * @param packagePath
         */
        void deletePackage(String packagePath) {
            Log.i(TAG, "deletePackage, try to delete package");

            File f = new File(packagePath);
            if (f.exists()) {
                f.delete();
            } else {
                Log.i(TAG, "deletePackage, path: " + packagePath + ", file not exists!");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "onCreate...");

        mContext = this;
        mAndroidX = new AndroidX(this);

        // Configure Baidu otasdk custom upgrade interface
        App.getOtaAgent().setCustomUpgrade(new CustomUpgradeInterface());

        String otaPackageFileName = getOtaPackageFileName();
        if (!TextUtils.isEmpty(otaPackageFileName)) {
            sOtaPackageName = otaPackageFileName;
            Log.i(TAG, "onCreate, get ota package name is: " + otaPackageFileName);
        }

        mMainHandler = new Handler(Looper.getMainLooper());
        HandlerThread workThread = new HandlerThread("UpdateService: workThread");
        workThread.start();
        mWorkHandler = new WorkHandler(workThread.getLooper());

        mUpdateReceiver = new UpdateReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction("android.hardware.usb.action.USB_STATE");
        intentFilter.addAction("android.os.storage.action.VOLUME_STATE_CHANGED");
        intentFilter.addAction(Constants.BROADCAST_NEWVERSION); // For Baidu otasdk
        this.registerReceiver(mUpdateReceiver, intentFilter);

        checkUpdateFlag();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy...");

        this.unregisterReceiver(mUpdateReceiver);

        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand...");

        if (intent == null) {
            return Service.START_NOT_STICKY;
        }

        int command = intent.getIntExtra("command", COMMAND_NULL);
        int delayTime = intent.getIntExtra("delay", 1000);
        Bundle bundle = intent.getBundleExtra("bundle");

        Log.i(TAG, "onStartCommand, command=" + command + " delayTime=" + delayTime);
        if (command == COMMAND_NULL) {
            return Service.START_NOT_STICKY;
        }

        if (isNeedDeletePackage) {
            command = COMMAND_DELETE_UPDATE_PACKAGE;
            delayTime = 20000;
            sWorkHandleLocked = true;
        }

        Message msg = new Message();
        msg.what = command;
        msg.obj = bundle;
        mWorkHandler.sendMessageDelayed(msg, delayTime);

        return Service.START_REDELIVER_INTENT;
    }

    private class WorkHandler extends Handler {
        WorkHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            String path = "";

            switch (msg.what) {
                case COMMAND_CHECK_LOCAL_UPDATING:
                    Log.i(TAG, "WorkHandler, COMMAND_CHECK_LOCAL_UPDATING");
                    if (sWorkHandleLocked) {
                        Log.w(TAG, "WorkHandler, locked!!!");
                        return;
                    }

                    path = searchLocalPackage();
                    if (!TextUtils.isEmpty(path)) {
                        Log.i(TAG, "WorkHandler, found package: " + path);
                        if (UPDATE_TYPE_FORCE == mUpdateType) {
                            showNewVersionOfForce(path);
                        } else {
                            showNewVersion(path);
                        }
                    } else {
                        Log.i(TAG, "WorkHandler, not found package");
                    }
                    break;

                case COMMAND_CHECK_REMOTE_UPDATING:
                    Log.i(TAG, "WorkHandler, COMMAND_CHECK_REMOTE_UPDATING");
                    if (sWorkHandleLocked) {
                        Log.i(TAG, "WorkHandler, locked !!!");
                        return;
                    }

                    if (AppUtils.isConnNetWork(getApplicationContext())) {
                        //TODO
                    } else {
                        Log.e(TAG, "WorkHandler, network is disconnect!");
                    }
                    break;

                case COMMAND_NEW_VERSION:
                    Log.i(TAG, "WorkHandler, COMMAND_NEW_VERSION");
                    Bundle bundle = (Bundle) msg.obj;
                    if (null != bundle) {
                        showNewVersion((NewVersionBean) bundle.getSerializable("new_version"));
                    }
                    break;

                case COMMAND_VERIFY_UPDATE_PACKAGE:
                    Log.i(TAG, "WorkHandler, COMMAND_VERIFY_UPDATE_PACKAGE");
                    Bundle b = msg.getData();
                    path = b.getString("path");
                    if (mBinder.verifyPackage(path)) {
                        mBinder.installPackage(path);
                    } else {
                        Log.e(TAG, path + " verify failed!");
                        showInvalidPackage(path);
                    }
                    break;

                case COMMAND_DELETE_UPDATE_PACKAGE:
                    Log.i(TAG, "WorkHandler, COMMAND_DELETE_UPDATE_PACKAGE");
                    File f = new File(mLastUpdatePath);
                    if (f.exists()) {
                        f.delete();
                        Log.i(TAG, "WorkHandler, path=" + mLastUpdatePath + ", delete complete!");
                    } else {
                        Log.i(TAG, "WorkHandler, path=" + mLastUpdatePath + " , file not exists!");
                    }
                    isNeedDeletePackage = false;
                    sWorkHandleLocked = false;
                    break;

                default:
                    break;
            }
        }

    }

    /**
     * Search for local upgrade packages
     *
     * @return package path
     */
    private String searchLocalPackage() {
        String packageFile = "";
        for (String dirPath : UpdateService.PACKAGE_FILE_DIRS) {
            String path = dirPath + USB_CONFIG_FILENAME;
            if ((new File(path)).exists()) {
                mUpdateType = new UsbConfigManager(this, new File(path)).getUpdateType();
                Log.i(TAG, "searchLocalPackage, find config file: " + path);
            }

            path = dirPath + sOtaPackageName;
            if ((new File(path)).exists()) {
                packageFile = path;
                Log.i(TAG, "searchLocalPackage, find package file: " + packageFile);
            }
        }
        if (!packageFile.isEmpty()) {
            return packageFile;
        }

        // Find in U disk
        String usbRootDir = USB_ROOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            usbRootDir = USB_ROOT_M;
        }
        Log.i(TAG, "searchLocalPackage, find usb: " + usbRootDir);
        File usbRoot = new File(usbRootDir);
        if (usbRoot.listFiles() == null) {
            return "";
        }

        for (File file : usbRoot.listFiles()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles(new FileFilter() {

                    @Override
                    public boolean accept(File tmpFile) {
                        Log.i(TAG, "searchLocalPackage, scan usb files: " + tmpFile.getAbsolutePath());
                        return (!tmpFile.isDirectory() && (tmpFile.getName().equals(sOtaPackageName)
                                || tmpFile.getName().equals(USB_CONFIG_FILENAME)));
                    }
                });

                if (files != null && files.length > 0) {
                    for (File tmpFile : files) {
                        if (tmpFile.getName().equals(USB_CONFIG_FILENAME)) {
                            mUpdateType = new UsbConfigManager(this, new File(tmpFile.getAbsolutePath())).getUpdateType();
                            Log.i(TAG, "searchLocalPackage, found config file: " + tmpFile.getAbsolutePath());
                        }

                        if (tmpFile.getName().equals(sOtaPackageName)) {
                            packageFile = tmpFile.getAbsolutePath();
                            Log.i(TAG, "searchLocalPackage, found package file: " + packageFile);
                        }
                    }
                    if (!packageFile.isEmpty()) {
                        return packageFile;
                    }
                }
            }
        }

        return "";
    }

    /**
     * Local new version dialog
     *
     * @param path
     */
    private void showNewVersion(final String path) {
        if (!TextUtils.isEmpty(path)) {
            sWorkHandleLocked = true;
            AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
            builder.setTitle("系统升级");
            builder.setMessage("发现新的系统版本，是否升级？\n" + path);
            builder.setPositiveButton("立即升级", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Notification verification upgrade package
                    Message msg = new Message();
                    msg.what = COMMAND_VERIFY_UPDATE_PACKAGE;
                    Bundle b = new Bundle();
                    b.putString("path", path);
                    msg.setData(b);
                    mWorkHandler.sendMessage(msg);

                    dialog.dismiss();
                }
            });
            builder.setNegativeButton("暂不升级", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int i) {
                    sWorkHandleLocked = false;
                    dialog.dismiss();
                }
            });
            final Dialog dialog = builder.create();
            dialog.setCancelable(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();

            mDialog = dialog;
        }
    }

    /**
     * Local new version dialog for forced upgrade
     *
     * @param path
     */
    private void showNewVersionOfForce(final String path) {
        if (!TextUtils.isEmpty(path)) {
            sWorkHandleLocked = true;
            AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
            builder.setTitle("系统升级");
            builder.setMessage("发现新的系统版本，5秒后将自动升级！\n" + path);
            final Dialog dialog = builder.create();
            dialog.setCancelable(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();
            mDialog = dialog;

            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // Notification verification upgrade package
                    Message msg = new Message();
                    msg.what = COMMAND_VERIFY_UPDATE_PACKAGE;
                    Bundle b = new Bundle();
                    b.putString("path", path);
                    msg.setData(b);
                    mWorkHandler.sendMessage(msg);

                    dialog.dismiss();
                }
            }, 5000);
        }
    }

    /**
     * OTA Upgrade new version dialog for baidu
     *
     * @param newVersion NewVersionBean
     */
    private void showNewVersion(final NewVersionBean newVersion) {
        if (null != newVersion) {
            sWorkHandleLocked = true;
            AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
            builder.setTitle("系统升级");
            builder.setMessage("发现新的系统版本，是否升级？\n"
                    + "版本：" + newVersion.getVersion()
                    + "\n" + newVersion.getInfo());
            builder.setPositiveButton("立即升级", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showDownloading();
                    dialog.dismiss();

                    // Download by Baidu
                    App.getOtaAgent().downLoad(newVersion.getPackageX(), new DownloadListener());
                }
            });
            builder.setNegativeButton("暂不升级", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int i) {
                    sWorkHandleLocked = false;
                    dialog.dismiss();
                }
            });
            final Dialog dialog = builder.create();
            dialog.setCancelable(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();

            mDialog = dialog;
        }
    }

    /**
     * Download progress dialog
     */
    private void showDownloading() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.layout_download, null);
        mDownloadPgr = (ProgressBar) view.findViewById(R.id.pgr_download);

        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
        builder.setTitle("系统升级");
        builder.setMessage("正在下载新版本，请稍候...");
        builder.setView(view);
        builder.setPositiveButton("隐藏", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                sWorkHandleLocked = false;
                // Abort download
                App.getOtaAgent().downLoadAbortAll();
                dialog.dismiss();
            }
        });
        final Dialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();

        mDialog = dialog;
    }

    /**
     * Download completion dialog
     *
     * @param pkgName package name
     */
    private void showDownloaded(final String pkgName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
        builder.setTitle("系统升级");
        builder.setMessage("新版本已经下载完成，是否升级？");
        builder.setPositiveButton("立即升级", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // install package
                App.getOtaAgent().upgrade(pkgName, null);
                dialog.dismiss();
            }
        });
        builder.setNegativeButton("暂不升级", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                sWorkHandleLocked = false;
                dialog.dismiss();
            }
        });
        final Dialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();

        mDialog = dialog;
    }

    /**
     * Upgrade package verification failure dialog
     *
     * @param path
     */
    private void showInvalidPackage(final String path) {
        if (!TextUtils.isEmpty(path)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
            builder.setTitle("系统升级");
            builder.setMessage("无效的升级文件！\n" + path);
            builder.setPositiveButton("重试", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Notification verification upgrade package
                    Message msg = new Message();
                    msg.what = COMMAND_VERIFY_UPDATE_PACKAGE;
                    Bundle b = new Bundle();
                    b.putString("path", path);
                    msg.setData(b);
                    mWorkHandler.sendMessage(msg);

                    dialog.dismiss();
                }
            });
            builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int i) {
                    sWorkHandleLocked = false;
                    dialog.dismiss();
                }
            });
            final Dialog dialog = builder.create();
            dialog.setCancelable(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            dialog.show();

            mDialog = dialog;
        }
    }

    /**
     * Upgrade success dialog
     */
    private void showUpdateSuccess() {
        sWorkHandleLocked = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
        builder.setTitle("系统升级");
        builder.setMessage("新版本升级成功！");
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        final Dialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();

        mDialog = dialog;
    }

    /**
     * Upgrade failed dialog
     */
    private void showUpdateFailed() {
        sWorkHandleLocked = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext());
        builder.setTitle("系统升级");
        builder.setMessage("新版本升级失败！");
        builder.setPositiveButton("确认", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                sWorkHandleLocked = false;
                dialog.dismiss();
            }
        });
        final Dialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();

        mDialog = dialog;
    }

    /**
     * After the upgrade is complete, check the upgrade results.
     */
    private void checkUpdateFlag() {
        if (isFirstStartUp) {
            Log.i(TAG, "checkUpdateFlag, first startup!!!");

            isFirstStartUp = false;

            // Check the upgrade flag
            String flag = null;
            try {
                flag = FileUtils.readFile(UPDATE_FLAG_FILE);
            } catch (IOException e) {
                Log.w(TAG, "checkUpdateFlag, " + e.getMessage());
            }
            Log.i(TAG, "checkUpdateFlag, upgrade flag = " + flag);

            if (!TextUtils.isEmpty(flag)) {
                String[] array = flag.split("\\$");
                if (array.length == 2) {
                    if (array[1].startsWith("path")) {
                        mLastUpdatePath = array[1].substring(array[1].indexOf('=') + 1);
                    }

                    isNeedDeletePackage = true;
                    if (TextUtils.equals(COMMAND_FLAG_SUCCESS, array[0])) {
                        showUpdateSuccess();
                    } else if (TextUtils.equals(COMMAND_FLAG_UPDATING, array[0])) {
                        showUpdateFailed();
                    }
                }
                UPDATE_FLAG_FILE.delete();
            }

            // Check the other(watchdog) flag
            flag = null;
            try {
                flag = FileUtils.readFile(OTHER_FLAG_FILE);
            } catch (IOException e) {
                Log.w(TAG, "checkUpdateFlag, " + e.getMessage());
            }
            Log.i(TAG, "checkUpdateFlag, other flag = " + flag);

            if (!TextUtils.isEmpty(flag)) {
                String[] array = flag.split("\\$");
                for (String param : array) {
                    if (param.startsWith("watchdog")) {
                        String value = param.substring(param.indexOf('=') + 1);
                        Log.i(TAG, "checkUpdateFlag, watchdog=" + value);

                        if (TextUtils.equals("true", value)) {
                            mAndroidX.toggleWatchdog(true);
                        }
                    }
                }
                OTHER_FLAG_FILE.delete();
            }
        }
    }

    /**
     * Configure a new upgrade package name
     *
     * @return new package name
     */
    private String getOtaPackageFileName() {
        String str = AppUtils.getProperty("ro.ota.packagename", "");
        if (!TextUtils.isEmpty(str) && !str.endsWith(".zip")) {
            return str + ".zip";
        }

        return str;
    }

    /**
     * Download listener for Baidu
     */
    private class DownloadListener implements IDownloadListener {

        @Override
        public void onPending(String pkgName) {

        }

        @Override
        public void onPrepare(String pkgName) {

        }

        @Override
        public void onProgress(String pkgName, int sofarBytes, int totalBytes) {
            long progress = sofarBytes / (totalBytes / 100);
            Log.i(TAG, "download->progress, " + sofarBytes
                    + "/" + totalBytes + " " + progress + "%");
            mDownloadPgr.setProgress((int) progress);
        }

        @Override
        public void onPaused(String pkgName) {

        }

        @Override
        public void onFailed(String pkgName, int errCode, String reason) {

        }

        @Override
        public void onFinished(String pkgName) {
            Log.i(TAG, "download->completed");
            if (null != mDialog && mDialog.isShowing()) {
                mDialog.dismiss();
            }
            showDownloaded(pkgName);
        }
    }

    /**
     * Custom upgrade interface for Baidu
     */
    private class CustomUpgradeInterface implements IUpgradeInterface {

        @Override
        public String installPackage(String pkgName, String file, boolean silence) {
            Message msg = new Message();
            msg.what = COMMAND_VERIFY_UPDATE_PACKAGE;
            Bundle b = new Bundle();
            b.putString("path", file);
            msg.setData(b);
            mWorkHandler.sendMessage(msg);

            return "";
        }

        @Override
        public String unInstallPackage(String pkgName, boolean silence) {
            return null;
        }

        @Override
        public void setListener(IUpgradeListener listener) {

        }
    }
}
