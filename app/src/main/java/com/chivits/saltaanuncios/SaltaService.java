package com.chivits.saltaanuncios;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Servicio de accesibilidad principal.
 * - En YouTube: pulsa "Omitir anuncio".
 * - En TikTok: desliza, da "me gusta", pausa (pedido por la cámara, la voz o el auto-scroll).
 * - En cualquier app: botón flotante y "pantalla negra" (apaga la pantalla sin parar la app).
 * - Cuenta el tiempo en TikTok, recordatorio de descanso y temporizador para dormir.
 */
public class SaltaService extends AccessibilityService {

    static volatile SaltaService instancia;

    private static final String YOUTUBE = "com.google.android.youtube";
    private static final String[] TIKTOK = {
            "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill"
    };
    private static final String[] IDS = {
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_container"
    };
    private static final String[] TEXTOS = {
            "Omitir anuncio", "Omitir anuncios", "Omitir", "Saltar anuncio", "Saltar",
            "Skip ad", "Skip ads", "Skip"
    };
    private static final String[] EMPIEZA_CON = {"omitir", "saltar", "skip"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private long ultimo = 0;

    // TikTok al frente
    private long ultimaComprobacion = 0;
    private boolean cacheTikTok = false;

    // flotante y pantalla negra
    private View flotante;
    private View negra;
    private WindowManager.LayoutParams negraLp;
    private BroadcastReceiver receptorApagado;
    private final long[] toques = new long[5];
    private int nToques = 0;

    // reloj interno (cada segundo)
    private int segAuto = 0, segDescanso = 0, segSinGuardar = 0;
    private int usoHoy = 0;
    private String diaUso = "";

    // ======================= ciclo de vida =======================

    @Override
    protected void onServiceConnected() {
        instancia = this;
        prefs = getSharedPreferences("stats", Context.MODE_PRIVATE);
        diaUso = hoy();
        usoHoy = prefs.getString("uso_dia", "").equals(diaUso) ? prefs.getInt("uso_seg", 0) : 0;
        actualizarFlotante();
        h.removeCallbacks(reloj);
        h.postDelayed(reloj, 1000);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        guardarUso();
        h.removeCallbacksAndMessages(null);
        quitarPantallaNegra();
        quitarFlotante();
        instancia = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() { }

    // ======================= YouTube: saltar anuncios =======================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent evento) {
        if (evento.getPackageName() == null || !YOUTUBE.equals(evento.getPackageName().toString())) return;
        if (prefs != null && !prefs.getBoolean("anuncios_on", true)) return;

        long ahora = System.currentTimeMillis();
        if (ahora - ultimo < 400) return;
        AccessibilityNodeInfo raiz = getRootInActiveWindow();
        if (raiz == null) return;
        AccessibilityNodeInfo boton = buscarBoton(raiz);
        if (boton != null && pulsar(boton)) {
            ultimo = ahora + 1000;
            sumar("omitidos");
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
            if (x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            x = x.getParent();
        }
        Rect r = new Rect();
        nodo.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        tocar(r.centerX(), r.centerY(), false);
        return true;
    }

    // ======================= TikTok =======================

    /** true si la app que está al frente es TikTok (se consulta como máximo cada 0,6 s). */
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

    /** Desliza: arriba = siguiente video; abajo = anterior. Solo dentro de TikTok. */
    public void deslizar(boolean haciaArriba) {
        if (!comprobarTikTok(true)) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f;
        Path camino = new Path();
        camino.moveTo(x, dm.heightPixels * (haciaArriba ? 0.75f : 0.25f));
        camino.lineTo(x, dm.heightPixels * (haciaArriba ? 0.25f : 0.75f));
        enviarGesto(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(camino, 0, 250)).build());
        segAuto = 0; // reinicia la cuenta del auto-scroll
    }

    /** Doble toque en el centro = "me gusta" en TikTok. */
    public void meGusta() {
        if (!comprobarTikTok(true)) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels / 2f, y = dm.heightPixels * 0.45f;
        Path p1 = new Path();
        p1.moveTo(x, y);
        Path p2 = new Path();
        p2.moveTo(x, y);
        enviarGesto(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 40))
                .addStroke(new GestureDescription.StrokeDescription(p2, 140, 40)).build());
    }

    /** Un toque en el centro = pausar / continuar en TikTok. */
    public void pausar() {
        if (!comprobarTikTok(true)) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        tocar(dm.widthPixels / 2f, dm.heightPixels * 0.45f, true);
    }

    private void tocar(float x, float y, boolean soloSiNegra) {
        Path p = new Path();
        p.moveTo(x, y);
        enviarGesto(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build());
    }

    /**
     * Envía un gesto. Si la pantalla negra está puesta, la vuelve "transparente al tacto"
     * mientras dura el gesto, para que llegue a la app de abajo.
     */
    private void enviarGesto(GestureDescription g) {
        final boolean conNegra = negra != null && negraLp != null;
        if (conNegra) tactilNegra(false);
        GestureResultCallback fin = new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                if (conNegra) h.postDelayed(restaurarNegra, 150);
            }

            @Override
            public void onCancelled(GestureDescription d) {
                if (conNegra) h.postDelayed(restaurarNegra, 150);
            }
        };
        boolean ok = dispatchGesture(g, fin, null);
        if (conNegra) {
            if (!ok) restaurarNegra.run();
            else h.postDelayed(restaurarNegra, 1500); // por si acaso
        }
    }

    private final Runnable restaurarNegra = new Runnable() {
        @Override
        public void run() {
            tactilNegra(true);
        }
    };

    private void tactilNegra(boolean bloquear) {
        if (negra == null || negraLp == null) return;
        if (bloquear) negraLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else negraLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(negra, negraLp);
        } catch (RuntimeException e) {
            // nada
        }
    }

    // ======================= reloj: uso, auto-scroll, descanso, dormir =======================

    private final Runnable reloj = new Runnable() {
        @Override
        public void run() {
            try {
                cadaSegundo();
            } catch (RuntimeException e) {
                // nunca debe tumbar el servicio
            }
            h.postDelayed(this, 1000);
        }
    };

    private void cadaSegundo() {
        if (prefs == null) return;
        boolean tk = comprobarTikTok(false);

        // tiempo en TikTok hoy
        String d = hoy();
        if (!d.equals(diaUso)) {
            diaUso = d;
            usoHoy = 0;
        }
        if (tk) {
            usoHoy++;
            if (++segSinGuardar >= 10) guardarUso();
        }

        // auto-scroll
        if (tk && prefs.getBoolean("auto_on", false)) {
            int cada = Math.max(3, prefs.getInt("auto_seg", 15));
            if (++segAuto >= cada) deslizar(true);
        } else {
            segAuto = 0;
        }

        // recordatorio de descanso
        if (tk && prefs.getBoolean("descanso_on", false)) {
            int min = Math.max(1, prefs.getInt("descanso_min", 30));
            if (++segDescanso >= min * 60) {
                segDescanso = 0;
                Toast.makeText(this, "⏰ Llevas " + min + " minutos en TikTok. ¡Descansa un ratito!",
                        Toast.LENGTH_LONG).show();
            }
        }

        // temporizador para dormir
        long fin = prefs.getLong("dormir_fin", 0);
        if (fin > 0 && System.currentTimeMillis() >= fin) {
            prefs.edit().putLong("dormir_fin", 0).apply();
            stopService(new Intent(this, CamaraService.class));
            stopService(new Intent(this, VozService.class));
            CamaraService.corriendo = false;
            VozService.corriendo = false;
            prefs.edit().putBoolean("auto_on", false).apply();
            quitarPantallaNegra();
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            else performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void guardarUso() {
        segSinGuardar = 0;
        if (prefs != null) prefs.edit().putString("uso_dia", diaUso).putInt("uso_seg", usoHoy).apply();
    }

    static int segundosHoy() {
        SaltaService s = instancia;
        return s != null ? s.usoHoy : 0;
    }

    private static String hoy() {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
    }

    private void sumar(String clave) {
        if (prefs != null) prefs.edit().putInt(clave, prefs.getInt(clave, 0) + 1).apply();
    }

    // ======================= botón flotante =======================

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
        boton.setText("🌙"); // luna
        boton.setTextSize(22);
        boton.setGravity(Gravity.CENTER);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setShape(GradientDrawable.OVAL);
        fondo.setColor(0xCC15171C);
        fondo.setStroke((int) (2 * d), 0xFF34C759);
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
                        if (movido) prefs.edit().putInt("flotante_x", lp.x).putInt("flotante_y", lp.y).apply();
                        else accionBloqueo();
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

    // ======================= pantalla negra / bloqueo =======================

    /** Botón de bloqueo: pantalla negra (por defecto) o bloqueo real del sistema. */
    public void accionBloqueo() {
        if (prefs != null && prefs.getBoolean("bloqueo_real", false)) bloquearPantalla();
        else pantallaNegra();
    }

    /** Desde el panel rápido: primero cierra el panel y luego apaga. */
    public void accionDesdePanel() {
        if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        else performGlobalAction(GLOBAL_ACTION_BACK);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                accionBloqueo();
            }
        }, 600);
    }

    static boolean negraActiva() {
        SaltaService s = instancia;
        return s != null && s.negra != null;
    }

    /**
     * Cubre la pantalla de negro, con brillo mínimo y táctil bloqueado. La app de abajo sigue
     * funcionando. Para volver: botón de encendido, o 5 toques rápidos.
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
                return true;
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
        lp.screenBrightness = 0f;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(v, lp);
            negra = v;
            negraLp = lp;
        } catch (RuntimeException e) {
            negra = null;
            negraLp = null;
            return;
        }

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
        if (nToques >= toques.length && t - toques[nToques % toques.length] < 2000) quitarPantallaNegra();
    }

    public void quitarPantallaNegra() {
        h.removeCallbacks(restaurarNegra);
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
        negraLp = null;
    }

    @SuppressWarnings("deprecation")
    private void despertar() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "saltaanuncios:despertar");
            wl.acquire(2000);
        } catch (RuntimeException e) {
            // basta con apretar otra vez el botón de encendido
        }
    }

    /** Bloquea y apaga la pantalla de verdad (Android 9 o superior). */
    public void bloquearPantalla() {
        if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } else {
            Toast.makeText(this, "El bloqueo real necesita Android 9 o superior", Toast.LENGTH_LONG).show();
        }
    }
}
