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
import android.content.Intent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.schedulers.Schedulers;
import timber.log.Timber;

/**
 * The service that executes a delayed db upload.
 */

public class DelayedUploadService
    extends IntentService {
//    public DelayedUploadService(String name) {
//        super(name);
//    }
    public DelayedUploadService() {
        super("com.money.manager.ex.sync.DelayedUploadService");
    }

    private AtomicReference<Subscription> delayedSubscription;

    @Override
    protected void onHandleIntent(Intent intent) {
        // Cancel any existing subscriptions.
        unsubscribe();

        String action = intent.getAction();
        if (action.equals(SyncSchedulerBroadcastReceiver.ACTION_STOP)) {
            // do not schedule a sync.
            return;
        }

        // schedule a delayed upload.
        Subscription subscription = Observable.timer(30, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.io())
//                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onCompleted() {
//                        Timber.d("complete");
                        unsubscribe();
                    }

                    @Override
                    public void onError(Throwable e) {
                        Timber.e(e, "scheduled upload");
                    }

                    @Override
                    public void onNext(Long aLong) {
                        Timber.d("what's this? %d", aLong);
                    }
                });

        delayedSubscription = new AtomicReference<>(subscription);
    }

    private void unsubscribe() {
        if (delayedSubscription == null) return;
        if (delayedSubscription.get() == null) return;
        if (delayedSubscription.get().isUnsubscribed()) return;

        delayedSubscription.get().unsubscribe();
    }


}