
package com.example.quantizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// quantizer publicbeta1

public class QuantizerService extends Service {

    private static final String TAG = "quantizer";
    private static final double TAU  = 6.283185307179586;
    private static final double PI_2 = 1.5707963267948966;

    private static final int EFFECT_DURATION_MS = 15000;
    private static final int SHADER_COUNT       = 16;
    private static final int GDI_COUNT          = 5;
    private static final int AUDIO_COUNT        = 8;

    private int xsRender = 0xDEADBEEF;
    private int xsAudio  = 0xCAFEBABE;
    private final Random rnd = new Random();
    private final float[] pfSinVals = new float[4096];

    private WindowManager wm;
    private ImageView overlay;
    private MediaProjection mp;
    private VirtualDisplay vd;
    private ImageReader reader;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int SW, SH;
    private Bitmap screen, gdiCopy;
    private Canvas screenCanvas;
    private final Paint gdiPaint = new Paint();
    private final Path gdiPath = new Path();
    private final Rect gdiSrcRect = new Rect();
    private final Rect gdiDstRect = new Rect();
    private int[] src, rowTmp, bufA, bufB, gdiScratch;
    private final double[] hslScratch = new double[3];
    private final Object srcLock = new Object();

    private volatile boolean haveFrame = false;
    private volatile boolean destroying = false;

    private Thread renderThread;
    private volatile boolean rendering = false;

    private Thread audioThread;
    private volatile boolean audioRunning = false;
    private volatile AudioTrack currentTrack = null;

    private int frameCounter = 0;
    private int shaderIndex = 0;
    private long shaderStartMs = 0;
    private final List<Integer> shaderOrder = new ArrayList<>();
    private final List<Integer> audioOrder = new ArrayList<>();

    private int activeGdiId = -1;
    private int gdiFrameCounter = 0;
    private long gdiActiveUntilMs = 0;
    private long gdiNextSpawnMs = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getParcelableExtra("data") == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startFg();

        for (int i = 0; i < 4096; i++) {
            pfSinVals[i] = (float) Math.sin((double) i / 4096.0 * TAU);
        }

        int seed = (int) SystemClock.uptimeMillis();
        xsRender = (seed == 0) ? 0xDEADBEEF : seed;
        xsAudio  = (seed ^ 0x9E3779B9) == 0 ? 0xCAFEBABE : (seed ^ 0x9E3779B9);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int rawW, rawH;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            rawW = b.width(); rawH = b.height();
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            rawW = dm.widthPixels; rawH = dm.heightPixels;
        }
        SW = Math.max(1, rawW);
        SH = Math.max(1, rawH);

        int resultCode = intent.getIntExtra("resultCode", android.app.Activity.RESULT_OK);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mp = mpm.getMediaProjection(resultCode, data);
        if (mp == null) { stopSelf(); return START_NOT_STICKY; }

        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, ui);

        try {
            buildOverlay();
        } catch (Throwable t) {
            Log.e(TAG, "overlay failed", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        buildCapture();

        for (int i = 0; i < SHADER_COUNT; i++) shaderOrder.add(i);
        Collections.shuffle(shaderOrder, rnd);
        for (int i = 0; i < AUDIO_COUNT; i++) audioOrder.add(i);
        Collections.shuffle(audioOrder, rnd);

        long now = SystemClock.uptimeMillis();
        shaderStartMs = now;
        gdiNextSpawnMs = now + 2000;

        rendering = true;
        renderThread = new Thread(this::renderLoop, "quantizer-render");
        renderThread.start();

        audioRunning = true;
        audioThread = new Thread(this::audioLoop, "quantizer-audio");
        audioThread.start();

        return START_STICKY;
    }

    private void startFg() {
        NotificationChannel ch = new NotificationChannel("qz", "quantizer", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "qz")
                .setContentTitle("quantizer")
                .setContentText("running effects…")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private void buildOverlay() {
        if (overlay != null) { try { wm.removeView(overlay); } catch (Exception ignored) {} }
        overlay = new ImageView(this);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setBackgroundColor(0x00000000);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_FULLSCREEN;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                flags, PixelFormat.TRANSLUCENT);
        params.alpha = 1.0f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        overlay.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        wm.addView(overlay, params);

        int n = SW * SH;
        src        = new int[n];
        rowTmp     = new int[SW];
        bufA       = new int[n];
        bufB       = new int[n];
        gdiScratch = new int[n];
        screen     = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
        gdiCopy    = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
        screenCanvas = new Canvas(screen);
        overlay.setImageBitmap(screen);
    }

    private void buildCapture() {
        reader = ImageReader.newInstance(SW, SH, PixelFormat.RGBA_8888, 4);
        vd = mp.createVirtualDisplay("quantizer", SW, SH, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ui);

        reader.setOnImageAvailableListener(r -> {
            if (destroying) return;
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer().duplicate();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                int w = Math.min(img.getWidth(), SW);
                int h = Math.min(img.getHeight(), SH);
                int rowInts = plane.getRowStride() / 4;
                IntBuffer ib = buf.asIntBuffer();

                synchronized (srcLock) {
                    for (int y = 0; y < h; y++) {
                        ib.position(y * rowInts);
                        ib.get(rowTmp, 0, w);
                        int rowOff = y * SW;
                        for (int x = 0; x < w; x++) {
                            int p = rowTmp[x];
                            src[rowOff + x] = 0xFF000000
                                    | ((p & 0x000000FF) << 16)
                                    |  (p & 0x0000FF00)
                                    | ((p & 0x00FF0000) >>> 16);
                        }
                    }
                    haveFrame = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "capture", t);
            } finally {
                img.close();
            }
        }, ui);
    }

    private void renderLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY); } catch (Throwable ignored) {}

        while (rendering) {
            try {
                if (haveFrame && !destroying) {
                    int w = SW, h = SH, n = w * h;
                    synchronized (srcLock) {
                        System.arraycopy(src, 0, bufA, 0, n);
                    }
                    System.arraycopy(bufA, 0, bufB, 0, n);

                    int shaderId = shaderOrder.get(shaderIndex);
                    applyShader(shaderId, frameCounter, w, h, bufA, bufB);
                    frameCounter++;

                    screen.setPixels(bufB, 0, w, 0, 0, w, h);

                    long now = SystemClock.uptimeMillis();
                    stepGdiOverlay(now, w, h);

                    ImageView ov = overlay;
                    if (ov != null) ov.postInvalidateOnAnimation();

                    if (now - shaderStartMs >= EFFECT_DURATION_MS) {
                        shaderStartMs = now;
                        frameCounter = 0;
                        shaderIndex = (shaderIndex + 1) % shaderOrder.size();
                    }
                } else {
                    try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                }
            } catch (Throwable t) {
                Log.e(TAG, "renderLoop", t);
            }
        }
    }

    private void stepGdiOverlay(long now, int w, int h) {
        if (now >= gdiNextSpawnMs) {
            activeGdiId = (xorshiftRender() & 0x7FFFFFFF) % GDI_COUNT;
            gdiFrameCounter = 0;
            gdiActiveUntilMs = now + EFFECT_DURATION_MS;
            gdiNextSpawnMs = now + ((xorshiftRender() & 0x7FFFFFFF) % 0x57E4);
        }
        if (activeGdiId >= 0 && now < gdiActiveUntilMs) {
            applyGdiEffect(activeGdiId, gdiFrameCounter++, w, h);
        } else {
            activeGdiId = -1;
        }
    }

    private void applyGdiEffect(int id, int frame, int w, int h) {
        int n = w * h;
        switch (id) {
            case 0: {
                screen.getPixels(bufB, 0, w, 0, 0, w, h);
                System.arraycopy(bufB, 0, gdiScratch, 0, n);
                for (int i = -32; i < 64; i += 16) {
                    double scale = (((xorshiftRender() & 0x7FFFFFFF) % 1001) + 500) / 1000.0;
                    int dw = Math.max(1, (int) (w / scale));
                    int dh = Math.max(1, (int) (h / scale));
                    int sw = Math.max(1, (int) (w * scale));
                    int sh = Math.max(1, (int) (h * scale));
                    int maxY = Math.min(h, dh), maxX = Math.min(w, dw);
                    for (int dy = 0; dy < maxY; dy++) {
                        int sy = i + (dy * sh) / dh;
                        if (sy < 0 || sy >= h) continue;
                        int rowDst = dy * w, rowSrc = sy * w;
                        for (int dx = 0; dx < maxX; dx++) {
                            int sx = i + (dx * sw) / dw;
                            if (sx < 0 || sx >= w) continue;
                            int res = (~bufB[rowSrc + sx]) & (~gdiScratch[rowDst + dx]);
                            gdiScratch[rowDst + dx] = 0xFF000000 | (res & 0x00FFFFFF);
                        }
                    }
                }
                screen.setPixels(gdiScratch, 0, w, 0, 0, w, h);
                break;
            }
            case 1: {
                int cpt = ((xorshiftRender() & 0x7FFFFFFF) % 6) + 3;
                int rMax = ((xorshiftRender() & 0x7FFFFFFF) % 51) + 25;
                int cx = (xorshiftRender() & 0x7FFFFFFF) % (w + rMax);
                int cy = (xorshiftRender() & 0x7FFFFFFF) % (h + rMax);
                gdiPath.reset();
                for (int k = 0; k < cpt; k++) {
                    int rx = (xorshiftRender() & 0x7FFFFFFF) % 200000001 - 100000000;
                    int ry = (xorshiftRender() & 0x7FFFFFFF) % 200000001 - 100000000;
                    float px = cx + (float) ((rx / 100000000.0) * rMax);
                    float py = cy + (float) ((ry / 100000000.0) * rMax);
                    if (k == 0) gdiPath.moveTo(px, py);
                    else gdiPath.lineTo(px, py);
                }
                gdiPath.close();
                int penColor = 0xFF000000 | (xorshiftRender() & 0x00FFFFFF);
                int penWidth = (xorshiftRender() & 3) + 1;
                int brushColor = 0xFF000000 | (xorshiftRender() & 0x00FFFFFF);
                gdiPaint.setStyle(Paint.Style.FILL);
                gdiPaint.setColor(brushColor);
                screenCanvas.drawPath(gdiPath, gdiPaint);
                gdiPaint.setStyle(Paint.Style.STROKE);
                gdiPaint.setStrokeWidth(penWidth);
                gdiPaint.setColor(penColor);
                screenCanvas.drawPath(gdiPath, gdiPaint);
                break;
            }
            case 2: {
                if (frame % 25 == 0) {
                    screen.setPixels(bufA, 0, w, 0, 0, w, h);
                }
                screen.getPixels(gdiScratch, 0, w, 0, 0, w, h);
                gdiCopy.setPixels(gdiScratch, 0, w, 0, 0, w, h);
                int dx = ((xorshiftRender() & 0x7FFFFFFF) % (2 * w + 1)) - w;
                int dy = ((xorshiftRender() & 0x7FFFFFFF) % (2 * h + 1)) - h;
                int dw = ((xorshiftRender() & 0x7FFFFFFF) % (2 * w + 1)) - w;
                int dh = ((xorshiftRender() & 0x7FFFFFFF) % (2 * h + 1)) - h;
                int sx = ((xorshiftRender() & 0x7FFFFFFF) % (2 * w + 1)) - w;
                int sy = ((xorshiftRender() & 0x7FFFFFFF) % (2 * h + 1)) - h;
                int sw = ((xorshiftRender() & 0x7FFFFFFF) % (2 * w + 1)) - w;
                int sh = ((xorshiftRender() & 0x7FFFFFFF) % (2 * h + 1)) - h;
                gdiSrcRect.set(Math.max(0, Math.min(sx, sx + sw)), Math.max(0, Math.min(sy, sy + sh)),
                               Math.min(w, Math.max(sx, sx + sw)), Math.min(h, Math.max(sy, sy + sh)));
                gdiDstRect.set(Math.min(dx, dx + dw), Math.min(dy, dy + dh),
                               Math.max(dx, dx + dw), Math.max(dy, dy + dh));
                if (!gdiSrcRect.isEmpty() && !gdiDstRect.isEmpty()) {
                    gdiPaint.setAlpha(255);
                    screenCanvas.drawBitmap(gdiCopy, gdiSrcRect, gdiDstRect, gdiPaint);
                }
                break;
            }
            case 3: {
                screen.getPixels(bufB, 0, w, 0, 0, w, h);
                int ox = ((xorshiftRender() & 0x7FFFFFFF) % (2 * w + 1)) - w;
                int oy = ((xorshiftRender() & 0x7FFFFFFF) % (2 * h + 1)) - h;
                System.arraycopy(bufB, 0, gdiScratch, 0, n);
                for (int y = 0; y < h; y++) {
                    int sy = y + oy;
                    if (sy < 0 || sy >= h) continue;
                    int dstRow = y * w, srcRow = sy * w;
                    for (int x = 0; x < w; x++) {
                        int sx = x + ox;
                        if (sx < 0 || sx >= w) continue;
                        gdiScratch[dstRow + x] = 0xFF000000 | ((bufB[dstRow + x] ^ bufB[srcRow + sx]) & 0x00FFFFFF);
                    }
                }
                screen.setPixels(gdiScratch, 0, w, 0, 0, w, h);
                break;
            }
            case 4: {
                screen.getPixels(bufB, 0, w, 0, 0, w, h);
                System.arraycopy(bufB, 0, gdiScratch, 0, n);
                int maxX = Math.max(1, w - 50), maxY = Math.max(1, h - 50);
                for (int k = 0; k < 50; k++) {
                    int rx = (xorshiftRender() & 0x7FFFFFFF) % maxX;
                    int ry = (xorshiftRender() & 0x7FFFFFFF) % maxY;
                    int mode = xorshiftRender();
                    if ((mode & 1) != 0) {
                        int rop = (xorshiftRender() & 0x7FFFFFFF) % 3;
                        int ox = rx - 2 + ((xorshiftRender() & 0x7FFFFFFF) % 5);
                        int oy = ry - 2 + ((xorshiftRender() & 0x7FFFFFFF) % 5);
                        for (int by = 0; by < 100; by++) {
                            int dy = ry + by, sy = oy + by;
                            if (dy < 0 || dy >= h || sy < 0 || sy >= h) continue;
                            for (int bx = 0; bx < 100; bx++) {
                                int dx = rx + bx, sx = ox + bx;
                                if (dx < 0 || dx >= w || sx < 0 || sx >= w) continue;
                                int sp = gdiScratch[sy * w + sx];
                                int dp = gdiScratch[dy * w + dx];
                                int rp = (rop == 0) ? sp : (rop == 1 ? (sp | dp) : (sp & dp));
                                gdiScratch[dy * w + dx] = 0xFF000000 | (rp & 0x00FFFFFF);
                            }
                        }
                    } else {
                        int oy = ry - 10 + ((xorshiftRender() & 0x7FFFFFFF) % 21);
                        int ox = rx - 10 + ((xorshiftRender() & 0x7FFFFFFF) % 21);
                        for (int by = 0; by < 200; by++) {
                            int dy = ry + by, sy = oy + by;
                            if (dy < 0 || dy >= h || sy < 0 || sy >= h) continue;
                            for (int bx = 0; bx < 200; bx++) {
                                int dx = rx + bx, sx = ox + bx;
                                if (dx < 0 || dx >= w || sx < 0 || sx >= w) continue;
                                int sp = bufB[sy * w + sx];
                                int dp = gdiScratch[dy * w + dx];
                                int r = ((((sp >> 16) & 0xFF) * 50) + (((dp >> 16) & 0xFF) * 205)) / 255;
                                int g = ((((sp >> 8)  & 0xFF) * 50) + (((dp >> 8)  & 0xFF) * 205)) / 255;
                                int b = ((( sp        & 0xFF) * 50) + (( dp        & 0xFF) * 205)) / 255;
                                gdiScratch[dy * w + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                    }
                }
                screen.setPixels(gdiScratch, 0, w, 0, 0, w, h);
                break;
            }
        }
    }

    private void applyShader(int id, int frame, int w, int h, int[] src, int[] dst) {
        switch (id) {
            case 0:  shader403880(frame, w, h, src, dst); break;
            case 1:  shader403A80(frame, w, h, src, dst); break;
            case 2:  shader403B90(frame, w, h, src, dst); break;
            case 3:  shader403BF0(frame, w, h, src, dst); break;
            case 4:  shader404110(frame, w, h, src, dst); break;
            case 5:  shader404280(frame, w, h, src, dst); break;
            case 6:  shader404310(frame, w, h, src, dst); break;
            case 7:  shader4043E0(frame, w, h, src, dst); break;
            case 8:  shader4044A0(frame, w, h, src, dst); break;
            case 9:  shader404790(frame, w, h, src, dst); break;
            case 10: shader404830(frame, w, h, src, dst); break;
            case 11: shader404860(frame, w, h, src, dst); break;
            case 12: shader4048A0(frame, w, h, src, dst); break;
            case 13: shader4048E0(frame, w, h, src, dst); break;
            case 14: shader4049E0(frame, w, h, src, dst); break;
            case 15: shader404AF0(frame, w, h, src, dst); break;
        }
        int n = w * h;
        for (int i = 0; i < n; i++) {
            dst[i] = 0xFF000000 | (dst[i] & 0x00FFFFFF);
        }
    }

    private int xorshiftRender() {
        xsRender ^= (xsRender << 13);
        xsRender ^= (xsRender >>> 17);
        xsRender ^= (xsRender << 5);
        return xsRender;
    }

    private int xorshiftAudio() {
        xsAudio ^= (xsAudio << 13);
        xsAudio ^= (xsAudio >>> 17);
        xsAudio ^= (xsAudio << 5);
        return xsAudio;
    }

    private float FastSine(double rad) {
        int i = (int) (rad / TAU * 4096.0);
        return pfSinVals[i & 0xFFF];
    }

    private float FastCosine(double rad) {
        return FastSine(rad + PI_2);
    }

    private static int mirror(int v, int maxIdx) {
        if (maxIdx <= 0) return 0;
        int a = (v == Integer.MIN_VALUE) ? 0 : Math.abs(v);
        int m = a % maxIdx;
        return (((a / maxIdx) & 1) == 0) ? m : (maxIdx - m - 1);
    }

    private void rgb2hsl(int argb, double[] out) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8)  & 0xFF) / 255.0;
        double b = ( argb        & 0xFF) / 255.0;
        double mn = Math.min(Math.min(r, g), b);
        double mx = Math.max(Math.max(r, g), b);
        double d = mx - mn;
        double l = (mx + mn) * 0.5;
        double h = 0.0, s = 0.0;
        if (d != 0.0) {
            s = (l < 0.5) ? (d / (mx + mn)) : (d / (2.0 - mx - mn));
            double dR = ((mx - r) / 6.0 + d * 0.5) / d;
            double dG = ((mx - g) / 6.0 + d * 0.5) / d;
            double dB = ((mx - b) / 6.0 + d * 0.5) / d;
            if (r == mx) h = dB - dG;
            else if (g == mx) h = (1.0 / 3.0) + dR - dB;
            else if (b == mx) h = (2.0 / 3.0) + dG - dR;
            if (h < 0.0) h += 1.0;
            if (h > 1.0) h -= 1.0;
        }
        out[0] = h;
        out[1] = s;
        out[2] = l;
    }

    private int hsl2rgb(double h, double s, double l) {
        double r = l, g = l, b = l, v;
        if (l > 0.5) v = l + s - l * s;
        else         v = (s + 1.0) * l;
        if (v > 0.0) {
            double m    = l + l - v;
            double sf   = h * 6.0;
            int    sext = (int) sf;
            double frac = sf - sext;
            double vsf  = (v - m) * frac;
            double mid1 = vsf + m;
            double mid2 = v - vsf;
            switch (sext) {
                case 0: r = v;    g = mid1; b = m;    break;
                case 1: r = mid2; g = v;    b = m;    break;
                case 2: r = m;    g = v;    b = mid1; break;
                case 3: r = m;    g = mid2; b = v;    break;
                case 4: r = mid1; g = m;    b = v;    break;
                case 5: r = v;    g = m;    b = mid2; break;
            }
        }
        return 0xFF000000
             | (((int) (r * 255.0) & 0xFF) << 16)
             | (((int) (g * 255.0) & 0xFF) << 8)
             |  ((int) (b * 255.0) & 0xFF);
    }

    private void shader403880(int t, int w, int h, int[] src, int[] dst) {
        double v3 = t / 80.0;
        double v5 = FastSine(t / (FastCosine(v3) * 4.0 + 6.0));
        double v6 = FastCosine(t / (FastSine(v3) * 4.0 + 6.0));
        double v7 = FastSine(t / (FastCosine(t / 160.0) * 8.0 + 12.0));
        double v8 = FastCosine(t / (FastSine(t / 160.0) * 8.0 + 12.0));

        double rGain = (v6 + v6 + 3.0) * FastSine(t / (v7 + v7 + 6.0));
        double gGain = (v5 + v5 + 3.0) * FastCosine(t / (v6 + v6 + 8.0));
        double bGain = (v8 + v8 + 3.0) * FastSine(t / (v5 + v5 + 10.0));
        double tHalf = t * 0.5;
        int n = w * h;

        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            double diffXy = (x - y) / 2;
            double diffYx = (y - x) / 2;
            double sxD = v8 * 75.0 * FastCosine((v5 * diffYx + diffYx + y) / 80.0) * FastSine(y / 80.0 + tHalf) + x + v7 * 40.0;
            double syD = v6 * 75.0 * FastSine((diffXy * v5 + diffXy + x) / 80.0) * FastCosine(x / 80.0 + tHalf) + y + v5 * 40.0;
            int ix = mirror((int) sxD, w - 1);
            int iy = mirror((int) syD, h - 1);
            int s = src[iy * w + ix];
            int r = ((int) (rGain * ((s >> 16) & 0xFF))) & 0xFF;
            int g = ((int) (gGain * ((s >> 8)  & 0xFF))) & 0xFF;
            int b = ((int) (bGain * ( s        & 0xFF))) & 0xFF;
            dst[i] = (r << 16) | (g << 8) | b;
        }
    }

    private void shader403A80(int t, int w, int h, int[] src, int[] dst) {
        double v5 = FastSine(t / 10.0);
        double v6 = FastCosine(t / 10.0);
        double v7 = v5 + v5;
        int packed = hsl2rgb((t / 20.0) % 1.0, 1.0, 0.5);
        int mask = (int) (v7 * v6 * 16777215.0);
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int a = Math.floorMod((int) (x * v7 - (x / 2) * v7) + y * w, n);
            int b = Math.floorMod((t % 7) * x - (t % 10) * y, n);
            dst[i] = packed ^ (src[a] & src[b] & mask);
        }
    }

    private void shader403B90(int t, int w, int h, int[] src, int[] dst) {
        int randByte = xorshiftRender() & 0xFF;
        int mask = randByte * 0x10101;
        int op = (t / 10) % 3;
        int c1 = (h & w) & 0xFF;
        int c2 = (h * w * t) & 0xFF;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int py = (t * 10 + y) % h;
            int idx = py * w + x;
            int p = src[idx];
            int mix = p | (p >>> 8) | (p >>> 16);
            int inv = (~mix) & mask;
            int patByte = (((y & x & 0xFF) | c1 | (t & 0xFF)) + c2 * (x & 0xFF) * (y & 0xFF)) & 0xFF;
            int pat = patByte * 0x10101;
            int out;
            if (op == 0) out = pat ^ inv;
            else if (op == 1) out = pat | inv;
            else out = pat & inv;
            dst[idx] = out ^ mix;
        }
    }

    private void shader403BF0(int t, int w, int h, int[] src, int[] dst) {
        int r1 = xorshiftRender();
        int r2 = xorshiftRender();
        int r3 = xorshiftRender();
        int n = w * h;
        if ((r3 & r2 & r1 & 0x10) != 0) {
            for (int i = 0; i < n; i++) {
                dst[i] = xorshiftRender() & xorshiftRender() & xorshiftRender() & 0xFFFFFF;
            }
            return;
        }
        int modeRot = r3 & 3;
        int modeMod = xorshiftRender() & 7;
        int passes = (xorshiftRender() & 3) + 1;
        int tick = (int) SystemClock.uptimeMillis();
        double hw = w * 0.5, hh = h * 0.5;
        int angleTime = 0;

        for (int p = 0; p < passes; p++) {
            double v30 = FastSine(angleTime / 10.0) * 2.0 + 2.0;
            int tickMask = (int) ((double) tick * v30);
            for (int i = 0; i < n; i++) {
                int x = i % w, y = i / w;
                int py = (t * 100 + y) % h;
                int idx = py * w + x;
                int destIdx = idx;
                if (modeRot == 0) {
                    double theta = v30 * 0.7853981633974483;
                    int nxI;
                    double nyD;
                    if ((modeMod & 1) != 0) {
                        theta = theta % TAU;
                        if (theta < 0.0) theta += TAU;
                        double s = FastSine(theta), c = FastCosine(theta);
                        double dx = x - hw, dy = py - hh;
                        nyD = dx * s + c * dy + hh;
                        nxI = (int) (dx * c - s * dy + hw);
                    } else {
                        double s = FastSine(theta), c = FastCosine(theta);
                        double dy = py - hh;
                        nxI = (int) ((x - hw) * c - dy * s + hw);
                        nyD = (nxI - hw) * s + dy * c + hh;
                    }
                    int bx = mirror(nxI, w - 1);
                    int by = mirror((int) nyD, h - 1);
                    idx = by * w + bx;
                    if (modeMod % 3 == 0) destIdx = idx;
                }
                dst[destIdx] |= tickMask;
                dst[idx] &= src[i];
            }
            angleTime += t;
            xorshiftRender();
        }
    }

    private void shader404110(int t, int w, int h, int[] src, int[] dst) {
        double sinT10 = FastSine(t / 10.0);
        int cnt = ((xorshiftRender() & 0x7FFFFFFF) % 7) + 1;
        int op  = (xorshiftRender() & 0x7FFFFFFF) % 9;
        int neg = xorshiftRender() & 1;
        int[] ops = new int[cnt];
        for (int k = 0; k < cnt; k++) ops[k] = xorshiftRender() & 7;

        int tc = t & 0xFF;
        int grayT = (tc << 16) | (tc << 8) | tc;
        int xorSin = (int) (sinT10 * 8388607.5 + 8388607.5);
        int cVal = (neg != 0) ? ~t : t;
        double hScale = h * 0.0625;
        double tQuarter = t * 0.25;
        int n = w * h;

        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int xh = x * h, yh = y * h, xw = x * w, yw = y * w;
            int acc = 0;
            for (int k = 0; k < cnt; k++) {
                int v;
                switch (ops[k]) {
                    case 0: v = xh + y; break;
                    case 1: v = yh + x; break;
                    case 2: v = xw + y; break;
                    case 3: v = i; break;
                    case 4: v = (x + y) * w; break;
                    case 5: v = (x + y) * h; break;
                    case 6: v = xw + yh; break;
                    default: v = yw + xh; break;
                }
                switch (op) {
                    case 1: acc += v - cVal; break;
                    case 2: acc += v * cVal; break;
                    case 3: acc += v / (cVal == 0 ? 1 : cVal); break;
                    case 4: acc += v & cVal; break;
                    case 5: acc += v | cVal; break;
                    case 6: acc += v ^ cVal; break;
                    case 7: acc += v << (cVal & 31); break;
                    case 8: acc += v >> (cVal & 31); break;
                    default: acc += v + cVal; break;
                }
            }
            int sx = Math.floorMod((int) (FastSine(y / hScale + tQuarter) * 50.0 + x), w);
            int avgIdx = Math.floorMod(acc / cnt, n);
            dst[i] = (src[avgIdx] ^ grayT) + (src[y * w + sx] ^ xorSin);
        }
    }

    private void shader404280(int t, int w, int h, int[] src, int[] dst) {
        double v8 = FastSine(t * 0.5) * 0.5 + 0.5;
        int n = w * h;
        double[] hsl = hslScratch;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            rgb2hsl(src[i], hsl);
            double v13 = FastSine(x / 20.0);
            double v = Math.abs(FastSine(y / 50.0 + t * 2.0 + v13 + v13) * 0.25 + hsl[0]);
            dst[i] = hsl2rgb(v % 1.0, v8, hsl[2]);
        }
    }

    private void shader404310(int t, int w, int h, int[] src, int[] dst) {
        double v8 = FastSine(t / 25.0) / 100.0;
        double v9 = FastCosine(t / 40.0) / 10.0;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int nx = mirror((int) ((w - x) * x * v8 * 0.5 + x * v8), w - 1);
            int ny = mirror((int) ((1.0 - v9 * 25.0) * y), h - 1);
            dst[i] = src[ny * w + nx];
        }
    }

    private void shader4043E0(int t, int w, int h, int[] src, int[] dst) {
        double v8 = FastCosine(t / 15.0) * FastSine(t / 10.0) * 10.0;
        int tc   = t & 0xFF;
        int mask = (((int) ((double) tc / 1.5) & 0xFF) << 8 | tc) << 8;
        double f50 = t * 50.0, f30 = t * 30.0, w50 = w / 50.0;
        int orX = (int) ((v8 * 0.5 + 5.0) * w50);
        int tick = (int) SystemClock.uptimeMillis();
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int srcY = y + (int) Math.sqrt(Math.abs(Math.tan(Math.sqrt(x * 10.0) + f30)));
            int srcX = ((int) Math.sqrt(Math.abs(Math.tan(y * v8 + f50) * 20.0)) + x) | orX;
            int nx = mirror(srcX, w - 1);
            int ny = mirror(srcY, h - 1);
            dst[i] = (tick | mask) ^ ((xorshiftRender() & 0x3F) * 0x10101) ^ src[ny * w + nx];
        }
    }

    private void shader4044A0(int t, int w, int h, int[] src, int[] dst) {
        double s10 = FastSine(t / 10.0);
        double c10 = FastCosine(t / 10.0);
        double theta2 = (t / 15.0) % TAU;
        if (theta2 < 0.0) theta2 += TAU;
        double s2 = FastSine(theta2);
        double c2 = FastCosine(theta2);
        double hw = w * 0.5, hh = h * 0.5;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int v16 = (int) (y * s10);
            double v17 = (int) ((x - hw) * c10 - (x - hh) * s10 + hw);
            double v20 = (int) ((v17 - hw) * s10 + (v17 - hh) * c10 + hh);
            double v23 = v20 - hw;
            double v24 = v16 - hh;
            int bx = mirror((int) (v23 * c2 - v24 * s2 + hw), w - 1);
            int by = mirror((int) (v24 * c2 + v23 * s2 + hh), h - 1);
            int p = src[i];
            dst[i] = p & xorshiftRender();
            dst[by * w + bx] = p;
        }
    }

    private void shader404790(int t, int w, int h, int[] src, int[] dst) {
        double v9 = (FastSine(t / 10.0) * 0.5 + 0.5) * 254.0 + 1.0;
        int tick = (int) SystemClock.uptimeMillis();
        int v16 = (t + (tick >>> 4)) & 0xFF;
        int xorMask = (tick % 0xFFFFFF) & 0x3F3F3F;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int ix = mirror(x - 5 + (y % 10 & t), w - 1);
            int iy = mirror(y - 5 + (x % 10 & t), h - 1);
            int s = src[iy * w + ix];
            int r = ((int) ((double) ((int) (((s >> 16) & 0xFF) / v9) & 0xFF) * v9) & v16) & 0xFF;
            int g = ((int) ((double) ((int) (((s >> 8)  & 0xFF) / v9) & 0xFF) * v9) ^ v16) & 0xFF;
            int b = ((int) ((double) ((int) (( s        & 0xFF) / v9) & 0xFF) * v9) | v16) & 0xFF;
            dst[i] = xorMask ^ ((r << 16) | (g << 8) | b);
        }
    }

    private void shader404830(int t, int w, int h, int[] src, int[] dst) {
        int v19 = t * 8;
        int v8  = t * 0x7FF8;
        int v18 = (t << 9) | (t * 3) | (t * -0x20000);
        int n = w * h;
        boolean alt = ((t / 100) & 1) != 0;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int mask = ((v8 - (y + x) / 2) << 17) | ((x / 2 + v19) << 9) | ((y / 2 + v19) * 3);
            if (alt) dst[i] = mask & (((src[i] & v18) / 0x10101) * 0x10101);
            else     dst[i] = mask & src[i] & v18;
        }
    }

    private void shader404860(int t, int w, int h, int[] src, int[] dst) {
        int tMask = (t << 9) | (t * 0x30000) | t;
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int ix = mirror(x + (x % ((y & t) + 1)), w - 1);
            int iy = mirror((y + t) & y, h - 1);
            dst[i] = src[iy * w + ix] & tMask;
        }
    }

    private void shader4048A0(int t, int w, int h, int[] src, int[] dst) {
        int n = w * h;
        int halfN = n / 2;
        double v9 = FastSine(t * 0.125) + 1.0;
        double t6 = t / 6.0, t125 = t * 0.125, t25 = t * 0.25;
        double[] hsl = hslScratch;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int cyDiv = (int) (FastCosine((x + y - halfN) / 20.0 - t6) * 10.0 + 20.0);
            int cxDiv = (int) (FastSine((x + y + halfN) / 25.0 + t25) * 10.0 + 20.0);
            int u3 = y + x % (y / (cyDiv == 0 ? 1 : cyDiv) + 1);
            int u4 = x + y % (x / (cxDiv == 0 ? 1 : cxDiv) + 1);
            int ix = mirror(u4, w - 1);
            int iy = mirror(u3, h - 1);
            int sum = src[iy * w + ix] + src[y * w + ix] + src[iy * w + x] + src[i];
            rgb2hsl(sum, hsl);
            dst[i] = hsl2rgb((t125 + hsl[0]) % 1.0, hsl[1] * v9, hsl[2]);
        }
    }

    private void shader4048E0(int t, int w, int h, int[] src, int[] dst) {
        int v19 = t * (t / 32);
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int ix = mirror(v19 + x, w - 1);
            int iy = mirror(y - v19, h - 1);
            int tap = iy * w + ix;
            dst[i] = src[tap] + dst[tap] - (src[i] & 0xFFFFFF) / 0x101010;
        }
    }

    private void shader4049E0(int t, int w, int h, int[] src, int[] dst) {
        double v7  = t * 0.25;
        double v10 = FastSine(v7);
        double v11 = FastCosine(v7);
        int packed = hsl2rgb((t * 0.125) % 1.0, 1.0, 0.5);
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int ix = mirror((int) Math.cbrt((double) (y * w) * 64.0 * v10) + x, w - 1);
            int iy = mirror((int) Math.cbrt((double) (x * h) * 64.0 * v11) + y, h - 1);
            dst[i] = ((src[iy * w + x] & src[y * w + ix]) | packed) & src[iy * w + ix];
        }
    }

    private void shader404AF0(int t, int w, int h, int[] src, int[] dst) {
        double v9  = t * 0.03125;
        double v10 = FastSine(v9);
        double v11 = FastCosine(v9);
        int packed = hsl2rgb(v9 % 1.0, 1.0, 0.5);
        int n = w * h;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            int ix = mirror(x + (int) (y * v10), w - 1);
            int iy = mirror(y + (int) (x * v11), h - 1);
            dst[i] = (src[iy * w + x] & src[y * w + ix]) | src[iy * w + ix] | packed;
        }
    }

    private void audioLoop() {
        while (audioRunning && !destroying) {
            List<Integer> order = new ArrayList<>(audioOrder);
            Collections.shuffle(order, rnd);

            for (int genIndex : order) {
                if (!audioRunning || destroying) break;

                int origRate    = ((xorshiftAudio() & 0x7FFFFFFF) % 40001) + 2000;
                int origSamples = 30 * origRate;

                int trackParam = 0;
                if (genIndex == 2) {
                    trackParam = (xorshiftAudio() & 0x7FFFFFFF) % 50;
                } else if (genIndex == 3) {
                    trackParam = (xorshiftAudio() & 0x7FFFFFFF) % 10001;
                    if (trackParam < 3 || (trackParam & (trackParam - 1)) == 0) {
                        trackParam += 3;
                    }
                }

                if (!streamTrack(genIndex, origRate, origSamples, origRate, trackParam)
                        && audioRunning && !destroying) {
                    int fbRate = (origRate < 4000) ? (origRate * 2) : 44100;
                    if (!streamTrack(genIndex, origRate, origSamples, fbRate, trackParam)
                            && fbRate != 44100 && audioRunning && !destroying) {
                        streamTrack(genIndex, origRate, origSamples, 44100, trackParam);
                    }
                }
            }
        }
    }

    private boolean streamTrack(int type, int origRate, int origSamples, int targetRate, int trackParam) {
        final int CHUNK_FRAMES = 2048;
        AudioTrack track = null;
        try {
            int minBytes = AudioTrack.getMinBufferSize(
                    targetRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            if (minBytes <= 0) minBytes = CHUNK_FRAMES * 2 * 4;
            int bufBytes = Math.max(minBytes * 2, CHUNK_FRAMES * 2 * 4 * 2);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(targetRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            currentTrack = track;
            track.play();

            boolean directRate = (targetRate == origRate);
            int playFrames = directRate ? origSamples : (int) ((long) origSamples * targetRate / origRate);
            int fadeFrames = Math.min(targetRate / 50, playFrames / 2);
            double step = directRate ? 1.0 : ((double) origRate / (double) targetRate);
            float[] chunk = new float[CHUNK_FRAMES * 2];

            int cachedFrame = -1;
            float l0 = 0f, r0 = 0f, l1 = 0f, r1 = 0f;

            for (int base = 0; base < playFrames && audioRunning && !destroying; base += CHUNK_FRAMES) {
                int count = Math.min(CHUNK_FRAMES, playFrames - base);
                for (int j = 0; j < count; j++) {
                    int outFrame = base + j;
                    float outL, outR;

                    if (directRate) {
                        outL = safeSample(outFrame * 2,     origRate, origSamples, type, trackParam);
                        outR = safeSample(outFrame * 2 + 1, origRate, origSamples, type, trackParam);
                    } else {
                        double inPos = outFrame * step;
                        int f0 = (int) inPos;
                        if (f0 >= origSamples) f0 = origSamples - 1;
                        int f1 = Math.min(f0 + 1, origSamples - 1);
                        float frac = (float) (inPos - f0);

                        if (f0 != cachedFrame) {
                            if (f0 == cachedFrame + 1) {
                                l0 = l1; r0 = r1;
                            } else {
                                l0 = safeSample(f0 * 2,     origRate, origSamples, type, trackParam);
                                r0 = safeSample(f0 * 2 + 1, origRate, origSamples, type, trackParam);
                            }
                            if (f1 == f0) {
                                l1 = l0; r1 = r0;
                            } else {
                                l1 = safeSample(f1 * 2,     origRate, origSamples, type, trackParam);
                                r1 = safeSample(f1 * 2 + 1, origRate, origSamples, type, trackParam);
                            }
                            cachedFrame = f0;
                        }

                        outL = l0 + (l1 - l0) * frac;
                        outR = r0 + (r1 - r0) * frac;
                    }

                    float gain = 1f;
                    if (fadeFrames > 0) {
                        if (outFrame < fadeFrames) gain = (float) outFrame / fadeFrames;
                        else if (outFrame >= playFrames - fadeFrames) gain = (float) (playFrames - 1 - outFrame) / fadeFrames;
                    }

                    chunk[j * 2]     = outL * gain;
                    chunk[j * 2 + 1] = outR * gain;
                }
                track.write(chunk, 0, count * 2, AudioTrack.WRITE_BLOCKING);
            }
            return true;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "AudioTrack rejected rate=" + targetRate + ", falling back", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Audio stream error rate=" + targetRate, e);
            return false;
        } finally {
            if (track != null) {
                try { track.stop();    } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
            if (currentTrack == track) currentTrack = null;
        }
    }

    private float safeSample(int i, int a1, int a2, int type, int trackParam) {
        float v = computeSample(i, a1, a2, type, trackParam);
        if (!Float.isFinite(v)) return 0f;
        if (v > 1f) return 1f;
        if (v < -1f) return -1f;
        return v;
    }

    private float computeSample(int i, int a1, int a2, int type, int trackParam) {
        switch (type) {
            case 0: {
                float v7  = (float) a2 / 200.0f;
                float s   = (((int) ((float) i / (float) a2 * 100.0f * 250.0f)) & 1) != 0 ? 1f : -1f;
                float p   = (float) i / v7;
                float tri = ((float) Math.abs(p - Math.floor(p) - 0.5) * 4.0f - 1.0f) * 20.0f;

                float c1000   = FastCosine((double) i / 1000.0);
                float c10000  = FastCosine((double) i / 10000.0);
                float c20000  = FastCosine((double) i / 20000.0);
                float s200000 = FastSine((double) i / 200000.0);

                double tanV   = (Math.abs(s200000) > 1e-4f) ? Math.tan((double) i / s200000) : 0.0;
                double atan2V = Math.atan2((double) i / 10000.0, c1000);
                double term1  = atan2V * FastSine(tanV * c1000);
                double term2  = (Math.abs(c10000) > 1e-4f) ? (FastSine((double) i / c10000) * c20000) : 0.0;

                return (tri + (float) (term1 + term2) * s * 20.0f) / 40.0f;
            }
            case 1: {
                double sec    = (double) i / a1;
                double tanVal = Math.tan(FastSine(sec / 12.5));
                double invTan = (Math.abs(tanVal) > 1e-4) ? (1.0 / tanVal) : 0.0;
                double f1     = (invTan != 0.0) ? (((double) i / a2 * 125.0) % invTan) : 0.0;
                float  v10    = (invTan != 0.0) ? ((float) f1 / (float) invTan) : 0f;

                float s1 = (((int) (FastCosine(sec / 5.0) * 2000.0 * ((float) i / (float) a2) * 250.0f)) & 1) != 0 ? 1f : -1f;
                int partA = (int) ((v10 + v10 - 1.0f) * 90000.0f + (float) ((i * i) % 10000));
                int partB = (int) (s1 * 100000.0f);
                return (float) (partA ^ partB) / 100000.0f;
            }
            case 2: {
                int v  = trackParam;
                int v9 = 5 * (v + 1);
                int a  = (i / v9) % (8 * v + 8) + 1;
                int u  = i % ((i / a) + 1);
                int b  = (i / (10 * (v + 1))) % (4 * v + 4) + 1;
                int c  = i % ((i / b) + 1);

                float amp = 0f;
                if (u != 0) {
                    double phase = (double) i / (double) a2 * 125.0;
                    amp = (float) (phase % (1.0 / u)) / (1.0f / (float) u);
                }

                float s2 = FastSine(TAU * ((float) c * (float) i / (float) a2));
                int hi = (int) ((amp + amp - 1.0f) * 1000.0f);
                int lo = (int) (-1000.0f * s2);
                return (hi - lo) / 2000.0f;
            }
            case 3: {
                int v6_3 = trackParam;
                long v8  = (long) i * v6_3;
                int inner = ~((int) (v8 / a2) ^ v6_3) % ((v6_3 & i) + 1);
                return (((int) (inner * ((float) i / (float) a2) * 250.0f)) & 1) != 0 ? 1f : -1f;
            }
            case 4: {
                double u = (double) i / (double) a2;
                double m1 = (Math.tan(u / 24.0) * (double) i) % 200.0;
                float s4 = (((int) ((float) m1 * ((float) i / (float) a2) * 250.0f)) & 1) != 0 ? 1f : -1f;

                double m2 = (Math.sqrt(u * 0.03125) * (double) i) % 200.0;
                float v9f = (float) m2;
                if (v9f == 0f) return -0.5f + 0.5f * s4;
                double fmod2 = (u * 125.0) % (1.0 / m2);
                float v10 = (float) fmod2 / (1.0f / v9f);
                return ((v10 + v10 - 1.0f) * 0.5f) + (s4 * 0.5f);
            }
            case 5: {
                double sinVal = FastSine((double) i / (double) a2);
                int intVal = (int) ((double) a2 * sinVal);
                int xorVal = i ^ intVal;
                double x = (xorVal != 0) ? (double) ((float) i / ((float) a2 / (float) xorVal)) : 0.0;
                return (float) (Math.abs(x - Math.floor(x) - 0.5) * 4.0 - 1.0);
            }
            case 6: {
                double u = (double) i / (double) a2;
                double d1 = FastSine(u * FastCosine(u * 40.0) * 80.0) * 100.0;
                float saw = 0f;
                if (Math.abs(d1) > 1e-4) {
                    double fmod = (u * 125.0) % (1.0 / d1);
                    saw = (float) fmod / (1.0f / (float) d1);
                }
                float f2 = (float) (FastSine(u * 60.0) * 100.0 + 200.0) * (float) i / (float) a2;
                return FastSine(TAU * f2) * 0.5f + (saw + saw - 1.0f) * 0.5f;
            }
            case 7: {
                float s7 = (((int) ((a2 ^ (i / 100)) % 500 * ((float) i / (float) a2) * 250.0f)) & 1) != 0 ? 1f : -1f;
                int quad = (i * i) % 500;
                float phase = (float) quad * (float) i / (float) a2;
                return (FastSine(TAU * phase) + s7) * 0.5f;
            }
            default:
                return 0f;
        }
    }

    @Override
    public void onDestroy() {
        destroying = true;
        super.onDestroy();
        rendering = false;
        if (renderThread != null) { try { renderThread.join(500); } catch (InterruptedException ignored) {} }

        audioRunning = false;
        AudioTrack p = currentTrack;
        if (p != null) { try { p.stop(); p.release(); } catch (Exception ignored) {} currentTrack = null; }
        if (audioThread != null) { try { audioThread.interrupt(); audioThread.join(500); } catch (InterruptedException ignored) {} }

        if (reader != null) { reader.setOnImageAvailableListener(null, null); reader.close(); reader = null; }
        if (vd != null) { vd.release(); vd = null; }
        if (mp != null) { mp.stop(); mp = null; }
        if (overlay != null) { overlay.setImageBitmap(null); try { wm.removeView(overlay); } catch (Exception ignored) {} overlay = null; }
        if (screen != null && !screen.isRecycled()) screen.recycle();
        if (gdiCopy != null && !gdiCopy.isRecycled()) gdiCopy.recycle();
        src = rowTmp = bufA = bufB = gdiScratch = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
