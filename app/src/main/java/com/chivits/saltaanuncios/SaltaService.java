package com.chivits.saltaanuncios;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/** Servicio de accesibilidad: solo actúa dentro de la app de YouTube y solo pulsa "Omitir anuncio". */
public class SaltaService extends AccessibilityService {

    private static final String[] IDS = {
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_container"
    };
    private static final String[] TEXTOS = {
            "Omitir anuncio", "Omitir anuncios", "Omitir", "Saltar anuncio", "Saltar",
            "Skip ad", "Skip ads", "Skip"
    };
    private static final String[] EMPIEZA_CON = {"omitir", "saltar", "skip"};

    static volatile SaltaService instancia;
    private long ultimo = 0;

    private static final String YOUTUBE = "com.google.android.youtube";
    private static final String[] TIKTOK = {
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill"
    };
    private SharedPreferences prefs;
    private View flotante;
    private View negra;
    private BroadcastReceiver receptorApagado;
    private long[] toques = new long[5];
    private int nToques = 0;
    private long ultimaComprobacion = 0;
    private boolean cacheTikTok = false;

    /** true si la app que está al frente ahora es TikTok (se consulta como máximo cada 0,6 s). */
    static boolean enTikTok() {
        SaltaService s = instancia;
        return s != null && s.comprobarTikTok(false);
    }

    private boolean comprobarTikTok(boolean forzar) {
        long t = System.currentTimeMillis();
        if (!forzar && t - ultimaComprobacion < 600) return cacheTikTok;
        ultimaComprobacion = t;
        boolean v = false;
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) {
                String pkg = r.getPackageName().toString();
                for (String k : TIKTOK) {
                    if (k.equals(pkg)) v = true;
                }
            }
        } catch (RuntimeException e) {
            v = false;
        }
        cacheTikTok = v;
        return v;
    }

    @Override
    protected void onServiceConnected() {
        instancia = this;
        prefs = getSharedPreferences("stats", Context.MODE_PRIVATE);
        actualizarFlotante();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        quitarPantallaNegra();
        quitarFlotante();
        instancia = null;
        return super.onUnbind(intent);
    }

    /** Desliza la pantalla: arriba = pasa al siguiente video; abajo = vuelve al anterior. */
    public void deslizar(boolean haciaArriba) {
        if (!comprobarTikTok(true)) return; // solo se desliza dentro de TikTok
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f;
        float desde = dm.heightPixels * (haciaArriba ? 0.75f : 0.25f);
        float hasta = dm.heightPixels * (haciaArriba ? 0.25f : 0.75f);
        Path camino = new Path();
        camino.moveTo(x, desde);
        camino.lineTo(x, hasta);
        GestureDescription gesto = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(camino, 0, 250))
                .build();
        dispatchGesture(gesto, null, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent evento) {
        // El salta-anuncios solo actúa dentro de YouTube
        if (evento.getPackageName() == null || !YOUTUBE.equals(evento.getPackageName().toString())) return;
        if (prefs != null && !prefs.getBoolean("anuncios_on", true)) return; // función apagada
        long ahora = System.currentTimeMillis();
        if (ahora - ultimo < 400) return;

        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) return;

        AccessibilityNodeInfo boton = buscarBoton(raiz);
        if (boton != null && pulsar(boton)) {
            ultimo = ahora + 1000; // espera un poco para no pulsar dos veces
            sumarContador();
        }
    }

    private AccessibilityNodeInfo buscarBoton(AccessibilityNodeInfo raiz) {
        for (String id : IDS) {
            List<AccessibilityNodeInfo> lista = raiz.findAccessibilityNodeInfosByViewId(id);
            if (lista != null) {
                for (AccessibilityNodeInfo n : lista) {
                    if (n.isVisibleToUser()) return n;
                }
            }
        }
        for (String t : TEXTOS) {
            List<AccessibilityNodeInfo> lista = raiz.findAccessibilityNodeInfosByText(t);
            if (lista == null) continue;
            for (AccessibilityNodeInfo n : lista) {
                if (!n.isVisibleToUser()) continue;
                CharSequence texto = n.getText() != null ? n.getText() : n.getContentDescription();
                if (texto == null) continue;
                String s = texto.toString().trim().toLowerCase(Locale.ROOT);
                for (String inicio : EMPIEZA_CON) {
                    if (s.startsWith(inicio) && s.length() <= 30) return n;
                }
            }
        }
        return null;
    }

    private boolean pulsar(AccessibilityNodeInfo nodo) {
        AccessibilityNodeInfo x = nodo;
        for (int i = 0; i < 5 && x != null; i++) {
            if (x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            x = x.getParent();
        }
        // Plan B: tocar el centro del botón
        Rect r = new Rect();
        nodo.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path camino = new Path();
        camino.moveTo(r.centerX(), r.centerY());
        GestureDescription gesto = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(camino, 0, 60))
                .build();
        return dispatchGesture(gesto, null, null);
    }

    private void sumarContador() {
        SharedPreferences sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        sp.edit().putInt("omitidos", sp.getInt("omitidos", 0) + 1).apply();
    }

    // ---------------- Botón flotante para bloquear la pantalla ----------------

    /** Muestra u oculta el botón flotante según el ajuste "flotante_on". */
    public void actualizarFlotante() {
        boolean on = prefs != null && prefs.getBoolean("flotante_on", false);
        if (on && flotante == null) ponerFlotante();
        else if (!on && flotante != null) quitarFlotante();
    }

    private void ponerFlotante() {
        final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        int tam = (int) (52 * d);

        TextView boton = new TextView(this);
        boton.setText("\uD83D\uDD12"); // candado
        boton.setTextSize(22);
        boton.setGravity(Gravity.CENTER);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setShape(GradientDrawable.OVAL);
        fondo.setColor(0xAA000000);
        fondo.setStroke((int) (2 * d), 0xFF2E7D32);
        boton.setBackground(fondo);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(tam, tam,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("flotante_x", (int) (16 * d));
        lp.y = prefs.getInt("flotante_y", (int) (200 * d));

        final int umbralToque = (int) (8 * d);
        boton.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            int px, py;
            boolean movido;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX();
                        y0 = e.getRawY();
                        px = lp.x;
                        py = lp.y;
                        movido = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - x0), dy = (int) (e.getRawY() - y0);
                        if (Math.abs(dx) > umbralToque || Math.abs(dy) > umbralToque) movido = true;
                        if (movido) {
                            lp.x = px + dx;
                            lp.y = py + dy;
                            try {
                                wm.updateViewLayout(v, lp);
                            } catch (RuntimeException ex) {
                                // nada
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (movido) {
                            prefs.edit().putInt("flotante_x", lp.x).putInt("flotante_y", lp.y).apply();
                        } else {
                            accionBloqueo();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(boton, lp);
            flotante = boton;
        } catch (RuntimeException e) {
            flotante = null;
        }
    }

    private void quitarFlotante() {
        if (flotante == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(flotante);
        } catch (RuntimeException e) {
            // nada
        }
        flotante = null;
    }

    /** Lo que hace el botón de bloqueo: pantalla negra (por defecto) o bloqueo real del sistema. */
    public void accionBloqueo() {
        if (prefs != null && prefs.getBoolean("bloqueo_real", false)) bloquearPantalla();
        else pantallaNegra();
    }

    /**
     * "Apaga" la pantalla sin bloquear el celular: la cubre de negro, baja el brillo al mínimo
     * y bloquea el táctil. La app de abajo (TikTok, YouTube...) sigue funcionando y sonando.
     * Para volver: botón de encendido (o tocar 5 veces rápido como salida de emergencia).
     */
    public void pantallaNegra() {
        if (negra != null) return;
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics real = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        int lado = Math.max(real.widthPixels, real.heightPixels) + 400;

        View v = new View(this);
        v.setBackgroundColor(Color.BLACK);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View vista, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) contarToqueEmergencia();
                return true; // se come todos los toques
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(lado, lado,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -200;
        lp.y = -200;
        lp.screenBrightness = 0f; // brillo al mínimo
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(v, lp);
            negra = v;
        } catch (RuntimeException e) {
            negra = null;
            return;
        }

        // Al apretar el botón de encendido: quitar lo negro y volver a prender la pantalla
        receptorApagado = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                quitarPantallaNegra();
                despertar();
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receptorApagado, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receptorApagado, f);
    }

    private void contarToqueEmergencia() {
        long t = System.currentTimeMillis();
        toques[nToques % toques.length] = t;
        nToques++;
        if (nToques >= toques.length) {
            long masViejo = toques[nToques % toques.length];
            if (t - masViejo < 2000) quitarPantallaNegra(); // 5 toques en menos de 2 s
        }
    }

    public void quitarPantallaNegra() {
        if (receptorApagado != null) {
            try {
                unregisterReceiver(receptorApagado);
            } catch (RuntimeException e) {
                // nada
            }
            receptorApagado = null;
        }
        nToques = 0;
        if (negra == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(negra);
        } catch (RuntimeException e) {
            // nada
        }
        negra = null;
    }

    @SuppressWarnings("deprecation")
    private void despertar() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "saltaanuncios:despertar");
            wl.acquire(2000);
        } catch (RuntimeException e) {
            // si el celular no deja, basta con apretar otra vez el botón de encendido
        }
    }

    /** Bloquea y apaga la pantalla de verdad (necesita Android 9 o superior). */
    public void bloquearPantalla() {
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        } else {
            Toast.makeText(this, "Bloquear pantalla necesita Android 9 o superior", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onInterrupt() { }
}
