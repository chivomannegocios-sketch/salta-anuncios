package com.chivits.saltaanuncios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/** Mantiene la cámara frontal abierta en segundo plano y detecta gestos de subir/bajar la mano. */
public class CamaraService extends Service {

    static volatile boolean corriendo = false;
    private static final String CANAL = "camara_scroll";

    private HandlerThread hilo;
    private Handler handler;
    private CameraDevice camara;
    private CameraCaptureSession sesion;
    private ImageReader lector;
    private Detector detector;
    private boolean invertir = false;
    private long ultimosAjustes = 0;
    private final byte[] cuadricula = new byte[Detector.GW * Detector.GH];

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (corriendo) return START_STICKY;
        iniciarEnPrimerPlano();
        corriendo = true;
        hilo = new HandlerThread("camara-scroll");
        hilo.start();
        handler = new Handler(hilo.getLooper());
        abrirCamara();
        return START_STICKY;
    }

    private void iniciarEnPrimerPlano() {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Control por cámara",
                    NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CANAL);
        } else {
            nb = new Notification.Builder(this);
        }
        Notification noti = nb.setContentTitle("Control de scroll activo")
                .setContentText("Mueve la mano hacia arriba o abajo frente a la cámara")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(7, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(7, noti);
        }
    }

    private void abrirCamara() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics cc = null;
            for (String s : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(s);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) {
                    id = s;
                    cc = c;
                    break;
                }
            }
            if (id == null) {
                stopSelf();
                return;
            }
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            detector = new Detector(so == null ? 270 : so);
            leerAjustes();

            StreamConfigurationMap mapa = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size elegido = null;
            for (Size sz : mapa.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (elegido == null || Math.abs(sz.getWidth() * sz.getHeight() - 76800)
                        < Math.abs(elegido.getWidth() * elegido.getHeight() - 76800)) {
                    elegido = sz;
                }
            }
            lector = ImageReader.newInstance(elegido.getWidth(), elegido.getHeight(), ImageFormat.YUV_420_888, 2);
            lector.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    procesar(r);
                }
            }, handler);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    camara = c;
                    crearSesion();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    c.close();
                    stopSelf();
                }

                @Override
                public void onError(CameraDevice c, int error) {
                    c.close();
                    stopSelf();
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | NullPointerException e) {
            stopSelf();
        }
    }

    private void crearSesion() {
        try {
            final CaptureRequest.Builder pedido = camara.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pedido.addTarget(lector.getSurface());
            camara.createCaptureSession(Collections.singletonList(lector.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            sesion = s;
                            try {
                                s.setRepeatingRequest(pedido.build(), null, handler);
                            } catch (CameraAccessException e) {
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            stopSelf();
                        }
                    }, handler);
        } catch (CameraAccessException e) {
            stopSelf();
        }
    }

    private void leerAjustes() {
        SharedPreferences sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        // sensibilidad 0..100  ->  umbral 0.40 (poco sensible) .. 0.10 (muy sensible)
        int sens = sp.getInt("sensibilidad", 50);
        detector.umbral = 0.40f - 0.30f * (sens / 100f);
        invertir = sp.getBoolean("invertir", false);
    }

    private void procesar(ImageReader r) {
        Image im = r.acquireLatestImage();
        if (im == null) return;
        try {
            long ahora = System.currentTimeMillis();
            if (ahora - ultimosAjustes > 1000) {
                ultimosAjustes = ahora;
                leerAjustes();
            }
            Image.Plane p = im.getPlanes()[0];
            ByteBuffer b = p.getBuffer();
            int w = im.getWidth(), h = im.getHeight(), rs = p.getRowStride(), ps = p.getPixelStride();
            for (int gy = 0; gy < Detector.GH; gy++) {
                int y = Math.min(h - 2, gy * h / Detector.GH);
                for (int gx = 0; gx < Detector.GW; gx++) {
                    int x = Math.min(w - 2, gx * w / Detector.GW);
                    int s = (b.get(y * rs + x * ps) & 0xFF) + (b.get(y * rs + (x + 1) * ps) & 0xFF)
                            + (b.get((y + 1) * rs + x * ps) & 0xFF) + (b.get((y + 1) * rs + (x + 1) * ps) & 0xFF);
                    cuadricula[gy * Detector.GW + gx] = (byte) (s / 4);
                }
            }
            int gesto = detector.alimentar(cuadricula, ahora);
            if (gesto != 0) {
                SaltaService srv = SaltaService.instancia;
                if (srv != null) {
                    boolean mano_sube = gesto < 0;
                    // por defecto: mano sube -> deslizar hacia arriba (siguiente video)
                    srv.deslizar(invertir ? !mano_sube : mano_sube);
                    SharedPreferences sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
                    sp.edit().putInt("gestos", sp.getInt("gestos", 0) + 1).apply();
                }
            }
        } catch (RuntimeException e) {
            // un cuadro mal leído no debe tumbar el servicio
        } finally {
            im.close();
        }
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        try {
            if (sesion != null) sesion.close();
            if (camara != null) camara.close();
            if (lector != null) lector.close();
        } catch (RuntimeException e) {
            // nada
        }
        if (hilo != null) hilo.quitSafely();
        super.onDestroy();
    }
}
