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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/** Escucha el micrófono en segundo plano: "1" = siguiente video, "2" = video anterior. */
public class VozService extends Service {

    static volatile boolean corriendo = false;
    private static final String CANAL = "voz_scroll";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean disparado = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (corriendo) return START_STICKY;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || !SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        iniciarEnPrimerPlano();
        corriendo = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                escuchar();
            }
        });
        return START_STICKY;
    }

    private void iniciarEnPrimerPlano() {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Control por voz",
                    NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CANAL);
        } else {
            nb = new Notification.Builder(this);
        }
        Notification noti = nb.setContentTitle("Control por voz activo")
                .setContentText("Di \"uno\" o \"dos\" para mover el scroll")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(8, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(8, noti);
        }
    }

    private void escuchar() {
        if (!corriendo) return;
        if (!SaltaService.enTikTok()) {
            // fuera de TikTok no se escucha: se vuelve a mirar en 1 segundo
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    escuchar();
                }
            }, 1000);
            return;
        }
        disparado = false;
        try {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(this);
                sr.setRecognitionListener(oyente);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            sr.startListening(i);
        } catch (RuntimeException e) {
            reiniciar(1000, true);
        }
    }

    /** Vuelve a empezar a escuchar; si hace falta, crea el reconocedor de nuevo. */
    private void reiniciar(long demora, boolean recrear) {
        if (!corriendo) return;
        if (recrear && sr != null) {
            try {
                sr.destroy();
            } catch (RuntimeException e) {
                // nada
            }
            sr = null;
        } else if (sr != null) {
            try {
                sr.cancel();
            } catch (RuntimeException e) {
                // nada
            }
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                escuchar();
            }
        }, demora);
    }

    private final RecognitionListener oyente = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) { }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopSelf();
                return;
            }
            boolean sinVoz = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            reiniciar(sinVoz ? 150 : 1200, !sinVoz);
        }

        @Override
        public void onResults(Bundle results) {
            revisar(results);
            reiniciar(150, false);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            revisar(partialResults);
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    };

    private void revisar(Bundle b) {
        if (disparado || b == null) return;
        ArrayList<String> lista = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (lista == null) return;
        for (String s : lista) {
            if (s == null) continue;
            String t = s.trim().toLowerCase(Locale.ROOT).replace(".", "").replace(",", "");
            int orden = 0;
            if (t.equals("1") || t.equals("uno") || t.equals("un")) orden = 1;
            else if (t.equals("2") || t.equals("dos")) orden = 2;
            if (orden != 0) {
                disparado = true;
                ejecutar(orden);
                reiniciar(250, false);
                return;
            }
        }
    }

    private void ejecutar(int orden) {
        SaltaService srv = SaltaService.instancia;
        if (srv == null) return;
        SharedPreferences sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        boolean invertir = sp.getBoolean("invertir_voz", false);
        // por defecto: 1 = siguiente video (deslizar hacia arriba), 2 = video anterior
        boolean haciaArriba = orden == 1;
        srv.deslizar(invertir ? !haciaArriba : haciaArriba);
        sp.edit().putInt("voz", sp.getInt("voz", 0) + 1).apply();
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        main.removeCallbacksAndMessages(null);
        if (sr != null) {
            try {
                sr.destroy();
            } catch (RuntimeException e) {
                // nada
            }
            sr = null;
        }
        super.onDestroy();
    }
}
