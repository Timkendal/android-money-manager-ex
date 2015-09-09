/*
 * Copyright (C) 2012-2015 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.money.manager.ex.core;

import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.money.manager.ex.common.WebViewActivity;

/**
 * Chrome web client customization.
 * Created by Alen Siljak on 09/09/2015.
 */
public class MyWebChromeClient extends WebChromeClient {

    public MyWebChromeClient(WebViewActivity parent) {
        this.MyActivity = parent;
    }

    private final WebViewActivity MyActivity;

    public void onProgressChanged(WebView view, int progress)
    {
        //Make the bar disappear after URL is loaded, and changes string to Loading...
//        MyActivity.setTitle("Loading...");
//        MyActivity.setProgress(progress * 100); //Make the bar disappear after URL is loaded
        MyActivity.updateProgress(progress);

//        // Return the app name after finish loading
//        if(progress == 100)
//            MyActivity.setTitle(R.string.loading);
    }

}
