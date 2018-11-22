package com.syuban.pictureloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Syuban
 * on 2018/11/22.
 */

public class PictureLoader {
    private static final String TAG = "PictureLoader";
    // the message is to notify the mainThread to update UI;
    public static final int MESSAGE_POST_RESULT = 1;
    // get system's CPU count;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    // set ThreadPool's core thread count;
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    // set ThreadPool's max thread count;
    private static final int MAX_POOL_SIZE = CORE_POOL_SIZE * 2 + 1;
    // set every thread's time alive;
    private static final long KEEP_ALIVE = 10L;
    // get KEY_URI from imageView;
    private static final int TAG_KEY_URI = R.id.pictureloader_uri;
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int IO_BUFFER_SIZE = 1024 * 8;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean isDiskLruCacheCreated = false;

    private static class LoaderResult {
        String uri;
        ImageView imageView;
        Bitmap bitmap;

        LoaderResult(String uri, ImageView imageView, Bitmap bitmap) {
            this.uri = uri;
            this.imageView = imageView;
            this.bitmap = bitmap;
        }
    }

    // create a ThreadFactory to ThreadPool;
    private static final ThreadFactory pThreadFactory = new ThreadFactory() {
        private final AtomicInteger tIndex = new AtomicInteger(0);

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            return new Thread(runnable, "PictureLoader#" + tIndex.getAndIncrement());
        }
    };

    // create the ThreadPool;
    private static final Executor loaderThreadPoolExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            pThreadFactory
    );

    private Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.e(TAG, "image url has changed, so ignored");
            }
        }
    };

    private Context pContext;
    private PictureResizer pictureResizer = new PictureResizer();
    private LruCache<String, Bitmap> memoryCache;
    private DiskLruCache diskLruCache;

    private PictureLoader(Context context) {
        pContext = context.getApplicationContext();
        // get system max memory;
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        // set LruCache size;
        int cacheSize = maxMemory / 8;
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String uri, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight() / 1024;
            }
        };
        File diskCacheDir = getDiskCahceDir(pContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                diskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                isDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // get dir for disk cache in system;
    private File getDiskCahceDir(Context context, String cacheName) {
        boolean externalStorageAvailable = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + cacheName);
    }

    // get usable space in system;
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return stats.getBlockSize() * stats.getAvailableBlocks();
    }

    /**
     * @param context
     * @return PictureLoader
     */
    public static PictureLoader build(Context context) {
        return new PictureLoader(context);
    }

    public void setBitmap(final String uri, final ImageView imageView) {
        setBitmap(uri, imageView, 0, 0);
    }

    public void setBitmap(final String uri, final ImageView imageView
            , final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, uri);
        final Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }
        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap1 = null;
                try {
                    bitmap1 = loadBitmap(uri, reqWidth, reqHeight);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (bitmap1 != null) {
                    LoaderResult result = new LoaderResult(uri, imageView, bitmap1);
                    mainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };
        loaderThreadPoolExecutor.execute(loadBitmapTask);
    }

    // get bitmap from memory cache;
    private Bitmap loadBitmapFromMemoryCache(String uri) {
        // get key from uri by hashKey algorithm;
        final String key = hashKeyFromUri(uri);
        // get bitmap from Memory Cache;
        return getBitmapFromMemoryCache(key);
    }

    // the algorithm for uri to key;
    private String hashKeyFromUri(String uri) {
        String cacheKey = null;
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(uri.getBytes());
            cacheKey = bytesToHexString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return cacheKey;
    }

    // get hex string from bytes;
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xFF & b);
            sb.append(hex.length() == 1 ? '0' : hex);
        }
        return sb.toString();
    }

    // get bitmap from memory cache;
    private Bitmap getBitmapFromMemoryCache(String key) {
        return memoryCache.get(key);
    }

    /**
     * @param uri
     * @param reqWidth
     * @param reqHeight
     * @return Bitmap
     * */
    private Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) throws IOException {
        Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if (bitmap != null) {
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap == null && !isDiskLruCacheCreated) {
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    // if disk cache is not created, download new bitmap from network;
    private Bitmap downloadBitmapFromUrl(String uri)
            throws IOException {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(uri);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
            if (in != null) in.close();
        }
        return bitmap;
    }

    // if disk cache is created, download bitmap form http and put the stream to disk cache;
    private Bitmap loadBitmapFromHttp(String uri, int reqWidth, int reqHeight)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network in UI Thread.");
        }
        if (diskLruCache == null) {
            return null;
        }
        String key = hashKeyFromUri(uri);
        DiskLruCache.Editor editor = diskLruCache.edit(key);
        if (editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUriToStream(uri, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            diskLruCache.flush();
        }
        return loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
    }

    // put the bitmap stream to disk cache stream by stream;
    private boolean downloadUriToStream(String uri, OutputStream outputStream)
            throws IOException {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(uri);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);
            int size = 0;
            while((size = in.read()) != -1) {
                out.write(size);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) urlConnection.disconnect();
            if (out != null) out.close();
            if (in != null) in.close();
        }
        return false;
    }

    // get bitmap from disk cache;
    private Bitmap loadBitmapFromDiskCache(String uri, int reqWidth, int reqHeight)
            throws IOException{
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "load bitmap in MainThread");
        }
        if (diskLruCache == null) return null;
        Bitmap bitmap = null;
        String key = hashKeyFromUri(uri);
        DiskLruCache.Snapshot snapshot = diskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = pictureResizer.resizePictureFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if (bitmap != null) {
                // if bitmap is get, add the new bitmap to memory cache;
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }

    // add new bitmap to memory cache;
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemoryCache(key) == null) {
            memoryCache.put(key, bitmap);
        }
    }

}
