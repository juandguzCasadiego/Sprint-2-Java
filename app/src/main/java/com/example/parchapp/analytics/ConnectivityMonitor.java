package com.example.parchapp.analytics;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/* Tells whether the phone has internet. Used by AnalyticsTracker to batch events and by the Dashboard for its offline banner (F3). Call stop() when the owner goes away. */
public class ConnectivityMonitor {

    public interface Listener {
        /*Listener is called on a background threaad when the device gets a connection back. */
        void onOnline();

        /* Called on a background thread when the connection is lost. */
        default void onOffline() {
        }
    }

    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback callback;
    private volatile boolean online;

    public ConnectivityMonitor(Context context, Listener listener) {
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        online = hasInternet(connectivityManager.getActiveNetwork());
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                online = true;
                listener.onOnline();
            }

            @Override
            public void onLost(@NonNull Network network) {
                online = false;
                listener.onOffline();
            }
        };
        connectivityManager.registerDefaultNetworkCallback(callback);
    }

    public void stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException alreadyStopped) {
            // stop() was called twice; nothing to unregister.
        }
    }

    public boolean isOnline() {
        return online;
    }

    private boolean hasInternet(Network network) {
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
