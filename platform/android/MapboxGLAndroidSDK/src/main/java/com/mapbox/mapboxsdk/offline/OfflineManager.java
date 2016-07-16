package com.mapbox.mapboxsdk.offline;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import com.mapbox.mapboxsdk.MapboxAccountManager;
import com.mapbox.mapboxsdk.constants.MapboxConstants;

import java.io.File;

/**
 * The offline manager is the main entry point for offline-related functionality.
 * It'll help you list and create offline regions.
 */
public class OfflineManager {

    private final static String LOG_TAG = "OfflineManager";

    //
    // Static methods
    //

    static {
        System.loadLibrary("mapbox-gl");
    }

    // Default database name
    private final static String DATABASE_NAME = "mbgl-offline.db";

    /*
     * The maximumCacheSize parameter is a limit applied to non-offline resources only,
     * i.e. resources added to the database for the "ambient use" caching functionality.
     * There is no size limit for offline resources.
     */
    private final static long DEFAULT_MAX_CACHE_SIZE = 50 * 1024 * 1024;

    // Holds the pointer to JNI DefaultFileSource
    private long mDefaultFileSourcePtr = 0;

    // Makes sure callbacks come back to the main thread
    private Handler handler;

    // This object is implemented as a singleton
    private static OfflineManager instance;

    /**
     * This callback receives an asynchronous response containing a list of all
     * {@link OfflineRegion} in the database, or an error message otherwise.
     */
    public interface ListOfflineRegionsCallback {
        /**
         * Receives the list of offline regions
         *
         * @param offlineRegions
         */
        void onList(OfflineRegion[] offlineRegions);

        /**
         * Receives the error message
         *
         * @param error
         */
        void onError(String error);
    }

    /**
     * This callback receives an asynchronous response containing the newly created
     * {@link OfflineRegion} in the database, or an error message otherwise.
     */
    public interface CreateOfflineRegionCallback {
        /**
         * Receives the newly created offline region
         * @param offlineRegion
         */
        void onCreate(OfflineRegion offlineRegion);

        /**
         * Receives the error message
         *
         * @param error
         */
        void onError(String error);
    }

    /*
     * Constructors
     */

    private OfflineManager(Context context) {
        // Get a pointer to the DefaultFileSource instance
        String assetRoot = getDatabasePath(context);
        String cachePath = assetRoot  + File.separator + DATABASE_NAME;
        mDefaultFileSourcePtr = createDefaultFileSource(cachePath, assetRoot, DEFAULT_MAX_CACHE_SIZE);

        if (MapboxAccountManager.getInstance() != null) {
            setAccessToken(mDefaultFileSourcePtr, MapboxAccountManager.getInstance().getAccessToken());
        }

        // Delete any existing previous ambient cache database
        deleteAmbientDatabase(context);
    }

    public static String getDatabasePath(Context context) {
        // Default value
        boolean setStorageExternal = MapboxConstants.DEFAULT_SET_STORAGE_EXTERNAL;

        try {
            // Try getting a custom value from the app Manifest
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            setStorageExternal = appInfo.metaData.getBoolean(
                    MapboxConstants.KEY_META_DATA_SET_STORAGE_EXTERNAL,
                    MapboxConstants.DEFAULT_SET_STORAGE_EXTERNAL);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Failed to read the package metadata: " + e.getMessage());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to read the storage key: " + e.getMessage());
        }

        String databasePath = null;
        if (setStorageExternal && isExternalStorageReadable()) {
            try {
                // Try getting the external storage path
                databasePath = context.getExternalFilesDir(null).getAbsolutePath();
            } catch (NullPointerException e) {
                Log.e(LOG_TAG, "Failed to obtain the external storage path: " + e.getMessage());
            }
        }

        if (databasePath == null) {
            // Default to internal storage
            databasePath = context.getFilesDir().getAbsolutePath();
        }

        return databasePath;
    }

    /**
     *  Checks if external storage is available to at least read. In order for this to work, make
     *  sure you include <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
     *  (or WRITE_EXTERNAL_STORAGE) for API level < 18 in your app Manifest.
     *
     *  Code from https://developer.android.com/guide/topics/data/data-storage.html#filesExternal
     */
    public static boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }

        Log.w(LOG_TAG, "External storage was requested but it isn't readable. For API level < 18"
                + " make sure you've requested READ_EXTERNAL_STORAGE or WRITE_EXTERNAL_STORAGE"
                + " permissions in your app Manifest (defaulting to internal storage).");

        return false;
    }

    private void deleteAmbientDatabase(final Context context) {
        // Delete the file in a separate thread to avoid affecting the UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String path = context.getCacheDir().getAbsolutePath() + File.separator + "mbgl-cache.db";
                    File file = new File(path);
                    if (file.exists()) {
                        file.delete();
                        Log.d(LOG_TAG, "Old ambient cache database deleted to save space: " + path);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to delete old ambient cache database: " + e.getMessage());
                }
            }
        }).start();
    }

    public static synchronized OfflineManager getInstance(Context context) {
        if (instance == null) {
            instance = new OfflineManager(context);
        }

        return instance;
    }

    /**
     * Access token getter/setter
     * @param accessToken
     *
     * @deprecated As of release 4.1.0, replaced by {@link MapboxAccountManager#start(Context, String)} ()}
     */
    @Deprecated
    public void setAccessToken(String accessToken) {
        setAccessToken(mDefaultFileSourcePtr, accessToken);
    }

    /**
     * Get Access Token
     * @return Access Token
     *
     * @deprecated As of release 4.1.0, replaced by {@link MapboxAccountManager#getAccessToken()}
     */
    @Deprecated
    public String getAccessToken() {
        return getAccessToken(mDefaultFileSourcePtr);
    }

    private Handler getHandler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }

        return handler;
    }

    /**
     * Retrieve all regions in the offline database.
     *
     * The query will be executed asynchronously and the results passed to the given
     * callback on the main thread.
     */
    public void listOfflineRegions(@NonNull final ListOfflineRegionsCallback callback) {
        listOfflineRegions(mDefaultFileSourcePtr, new ListOfflineRegionsCallback() {
            @Override
            public void onList(final OfflineRegion[] offlineRegions) {
                getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onList(offlineRegions);
                    }
                });
            }

            @Override
            public void onError(final String error) {
                getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError(error);
                    }
                });
            }
        });
    }

    /**
     * Create an offline region in the database.
     *
     * When the initial database queries have completed, the provided callback will be
     * executed on the main thread.
     *
     * Note that the resulting region will be in an inactive download state; to begin
     * downloading resources, call `OfflineRegion.setDownloadState(DownloadState.STATE_ACTIVE)`,
     * optionally registering an `OfflineRegionObserver` beforehand.
     */
    public void createOfflineRegion(
            @NonNull OfflineRegionDefinition definition,
            @NonNull byte[] metadata,
            @NonNull final CreateOfflineRegionCallback callback) {

        createOfflineRegion(mDefaultFileSourcePtr, definition, metadata, new CreateOfflineRegionCallback() {
            @Override
            public void onCreate(final OfflineRegion offlineRegion) {
                getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onCreate(offlineRegion);
                    }
                });
            }

            @Override
            public void onError(final String error) {
                getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError(error);
                    }
                });
            }
        });
    }

    /*
    * Changing or bypassing this limit without permission from Mapbox is prohibited
    * by the Mapbox Terms of Service.
    */
    public void setOfflineMapboxTileCountLimit(long limit) {
        setOfflineMapboxTileCountLimit(mDefaultFileSourcePtr, limit);
    }


    /*
     * Native methods
     */

    private native long createDefaultFileSource(
            String cachePath, String assetRoot, long maximumCacheSize);

    private native void setAccessToken(long defaultFileSourcePtr, String accessToken);
    private native String getAccessToken(long defaultFileSourcePtr);

    private native void listOfflineRegions(
            long defaultFileSourcePtr, ListOfflineRegionsCallback callback);

    private native void createOfflineRegion(
            long defaultFileSourcePtr, OfflineRegionDefinition definition,
            byte[] metadata, CreateOfflineRegionCallback callback);

    private native void setOfflineMapboxTileCountLimit(
            long defaultFileSourcePtr, long limit);

}
