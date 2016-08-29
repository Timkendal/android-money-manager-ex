/*
 * Copyright (C) 2012-2016 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.money.manager.ex.sync;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import com.cloudrail.si.types.CloudMetaData;
import com.money.manager.ex.R;
import com.money.manager.ex.core.RequestCode;
import com.money.manager.ex.dropbox.IOnDownloadUploadEntry;
import com.money.manager.ex.home.MainActivity;
import com.money.manager.ex.settings.SyncPreferences;
import com.money.manager.ex.sync.events.SyncStartingEvent;
import com.money.manager.ex.sync.events.SyncStoppingEvent;
import com.money.manager.ex.utils.MmxFileUtils;
import com.money.manager.ex.utils.NetworkUtils;

import org.greenrobot.eventbus.EventBus;
import org.joda.time.DateTime;

import java.io.File;
import java.io.IOException;

import rx.SingleSubscriber;
import rx.Subscriber;
import rx.functions.Action1;
import rx.subscriptions.CompositeSubscription;
import timber.log.Timber;

/**
 * The background service that synchronizes the database file.
 * It is being invoked by the timer.
 * It displays the sync notification and invokes the cloud api.
 */
public class SyncService
    extends IntentService {

    public static final String INTENT_EXTRA_MESSENGER = "com.money.manager.ex.sync.MESSENGER";

    public SyncService() {
        super("com.money.manager.ex.sync.SyncService");
    }

    private CompositeSubscription compositeSubscription;
    private Messenger mOutMessenger;
    private NotificationManager mNotificationManager;

    @Override
    public void onCreate() {
        super.onCreate();

        compositeSubscription = new CompositeSubscription();
        mNotificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
//        String intentString = intent != null ? intent.toString() : "null";
        String action = intent.getAction();
        Timber.d("Running sync service: %s", action);
        sendStartEvent();

        // Check if there is a messenger. Used to send the messages back.
        if (intent.getExtras().containsKey(SyncService.INTENT_EXTRA_MESSENGER)) {
            mOutMessenger = intent.getParcelableExtra(SyncService.INTENT_EXTRA_MESSENGER);
        }

        // check if the device is online.
        NetworkUtils network = new NetworkUtils(getApplicationContext());
        if (!network.isOnline()) {
            Timber.i("Can't sync. Device not online.");
            sendMessage(SyncServiceMessage.NOT_ON_WIFI);
            sendStopEvent();
            return;
        }

        SyncManager sync = new SyncManager(getApplicationContext());

        String localFilename = intent.getStringExtra(SyncConstants.INTENT_EXTRA_LOCAL_FILE);
        String remoteFilename = intent.getStringExtra(SyncConstants.INTENT_EXTRA_REMOTE_FILE);
        // check if file is correct
        if (TextUtils.isEmpty(localFilename) || TextUtils.isEmpty(remoteFilename)) {
            sendStopEvent();
            return;
        }

        CloudMetaData remoteFile = sync.loadMetadata(remoteFilename);
        if (remoteFile == null) {
            sendMessage(SyncServiceMessage.ERROR);
            sendStopEvent();
            return;
        }
        File localFile = new File(localFilename);

        // todo: modify this part after db initial upload has been implemented.
//        if (remoteFile == null) {
//            // file not found on remote server.
//            if (intent.getAction().equals(SyncConstants.INTENT_ACTION_UPLOAD)) {
//                // Create a new entry in the root?
//                Log.w(LOGCAT, "remoteFile is null. SyncService forcing creation of the new remote file.");
//                remoteFile = new CloudMetaData();
//                remoteFile.setPath(remoteFilename);
//            } else {
//                Timber.e("remoteFile is null. SyncService.onHandleIntent premature exit.");
//                sendMessage(SyncServiceMessage.ERROR);
//                return;
//            }
//        }

        // check if name is same
        if (!localFile.getName().toLowerCase().equals(remoteFile.getName().toLowerCase())) {
            Timber.w("Local filename different from the remote!");
            sendMessage(SyncServiceMessage.ERROR);
            sendStopEvent();
            return;
        }

        // Execute action.
        switch (action) {
            case SyncConstants.INTENT_ACTION_DOWNLOAD:
                triggerDownload(localFile, remoteFile);
                break;
            case SyncConstants.INTENT_ACTION_UPLOAD:
                triggerUpload(localFile, remoteFile);
                break;
            case SyncConstants.INTENT_ACTION_SYNC:
            default:
                triggerSync(localFile, remoteFile);
                break;
        }
    }

    @Override
    public void onDestroy() {
        if (!compositeSubscription.isUnsubscribed()) {
            compositeSubscription.unsubscribe();
        }

        super.onDestroy();
    }

    // private

    private void triggerDownload(final File localFile, CloudMetaData remoteFile) {
        SyncManager sync = new SyncManager(getApplicationContext());

        final Notification notification = new SyncNotificationFactory(getApplicationContext())
                .getNotificationForDownload();

        final NotificationManager notificationManager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        final File tempFile = new File(localFile.toString() + "-download");

        final IOnDownloadUploadEntry onDownloadHandler = new IOnDownloadUploadEntry() {
            @Override
            public void onPreExecute() {
            }

            @Override
            public void onPostExecute(boolean result) {
                if (notification == null || notificationManager == null) return;

                notificationManager.cancel(SyncConstants.NOTIFICATION_SYNC_IN_PROGRESS);

                if (!result) return;

                // copy file
                try {
                    MmxFileUtils.copy(tempFile, localFile);
                    tempFile.delete();
                } catch (IOException e) {
                    Timber.e(e, "copying downloaded database file");
                    return;
                }

                // create notification for open file
                // intent is passed to the notification and called if clicked on.
                Intent intent = new SyncCommon().getIntentForOpenDatabase(getApplicationContext(), localFile);
                PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(),
                        RequestCode.SELECT_FILE, intent, 0);

                Notification notification = new SyncNotificationFactory(getApplicationContext())
                    .getNotificationDownloadComplete(pendingIntent);

                // notify
                notificationManager.notify(SyncConstants.NOTIFICATION_SYNC_OPEN_FILE, notification);
            }
        };
        Timber.d("Download file. Local file: %s, remote file: %s", localFile.getPath(), remoteFile.getPath());

//        onDownloadHandler.onPreExecute();
        if (notification != null && notificationManager != null) {
            notificationManager.notify(SyncConstants.NOTIFICATION_SYNC_IN_PROGRESS, notification);
        }
        sendMessage(SyncServiceMessage.STARTING_DOWNLOAD);

//        boolean ret = sync.download(remoteFile, tempFile);
        compositeSubscription.add(
            sync.downloadSingle(remoteFile, tempFile)
                    // do not run on another thread as then the service will be destroyed.
//                    .subscribeOn(Schedulers.io())
                    .subscribe(new SingleSubscriber<Void>() {
                        @Override
                        public void onSuccess(Void value) {
                            onDownloadHandler.onPostExecute(true);
                            sendMessage(SyncServiceMessage.DOWNLOAD_COMPLETE);
                            sendStopEvent();
                        }

                        @Override
                        public void onError(Throwable error) {
                            Timber.e(error, "async download");
                            sendMessage(SyncServiceMessage.ERROR);
                            sendStopEvent();
                        }
                    })
        );
    }

    private void triggerUpload(final File localFile, CloudMetaData remoteFile) {
        Timber.d("Uploading db. Local file: %s, remote file: %s", localFile.getPath(), remoteFile.getPath());

        showNotificationUploading();
        sendMessage(SyncServiceMessage.STARTING_UPLOAD);

        // upload
        SyncManager sync = new SyncManager(getApplicationContext());
        boolean result = sync.upload(localFile.getPath(), remoteFile.getPath());

        // notification, upload complete
        showNotificationUploadComplete(result, localFile);
        sendMessage(SyncServiceMessage.UPLOAD_COMPLETE);
        sendStopEvent();
    }

    private void showNotificationUploadComplete(boolean result, File localFile) {
        if (mNotificationManager == null) return;

        mNotificationManager.cancel(SyncConstants.NOTIFICATION_SYNC_IN_PROGRESS);

        if (!result) return;

        // create notification for open file
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.setData(Uri.fromFile(localFile));
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), RequestCode.SELECT_FILE, intent, 0);
        // notification
        Notification notification = new SyncNotificationFactory(getApplicationContext())
                .getNotificationUploadComplete(pendingIntent);
        // notify
        mNotificationManager.notify(SyncConstants.NOTIFICATION_SYNC_OPEN_FILE, notification);

    }

    private void triggerSync(File localFile, CloudMetaData remoteFile) {
        SyncManager sync = new SyncManager(getApplicationContext());
        SyncPreferences preferences = new SyncPreferences(getApplicationContext());

        // are there local changes?
        boolean isLocalModified = preferences.isLocalFileChanged();
        Timber.d("local file has changes: %b", isLocalModified);

        // are there remote changes?
        boolean isRemoteModified = sync.isRemoteFileModified(remoteFile);
        Timber.d("Remote file has changes: %b", isRemoteModified);

        if (!isLocalModified && !isRemoteModified) {
            sendMessage(SyncServiceMessage.FILE_NOT_CHANGED);
            sendStopEvent();
            return;
        }
        // if both changed, there is a conflict!
        if (isLocalModified && isRemoteModified) {
            Timber.w(getString(R.string.both_files_modified));
            sendMessage(SyncServiceMessage.CONFLICT);
            sendStopEvent();
            showNotificationForConflict();
            return;
        }
        if (isRemoteModified) {
            // remoteLastModified.isAfter(localLastModified)
            Timber.d("Remote file %s changed. Triggering download.", remoteFile.getPath());
            // download file
            triggerDownload(localFile, remoteFile);
            return;
        }
        if (isLocalModified) {
            // remoteLastModified.isBefore(localLastModified)
            Timber.d("Local file %s has changed. Triggering upload.", localFile.getPath());
            // upload file
            triggerUpload(localFile, remoteFile);
            return;
        }
    }

    private boolean sendMessage(SyncServiceMessage message) {
        if (mOutMessenger == null) return true;

        Message msg = new Message();
        msg.what = message.code;

        try {
            mOutMessenger.send(msg);
        } catch (Exception e) {
            Timber.e(e, "sending message from the sync service");

            return false;
        }
        return true;
    }

    private void sendStartEvent() {
        // send notification via event bus
        if (EventBus.getDefault().hasSubscriberForEvent(SyncStartingEvent.class)) {
            EventBus.getDefault().post(new SyncStartingEvent());
        }
    }

    private void sendStopEvent() {
        if (EventBus.getDefault().hasSubscriberForEvent(SyncStoppingEvent.class)) {
            EventBus.getDefault().post(new SyncStoppingEvent());
        }
    }

    private void showNotificationUploading() {
        Notification notification = new SyncNotificationFactory(getApplicationContext())
                .getNotificationUploading();

        // send notification, upload starting
        if (notification == null || mNotificationManager == null) return;

        mNotificationManager.notify(SyncConstants.NOTIFICATION_SYNC_IN_PROGRESS, notification);
    }

    private void showNotificationForConflict() {
        Notification notification = new SyncNotificationFactory(getApplicationContext())
                .getNotificationForConflict();

        mNotificationManager.notify(SyncConstants.NOTIFICATION_SYNC_ERROR, notification);
    }
}
