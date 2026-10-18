package com.chivits.saltaanuncios;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
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

    @Override
    protected void onServiceConnected() {
        instancia = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instancia = null;
        return super.onUnbind(intent);
    }

    /** Desliza la pantalla: arriba = pasa al siguiente video; abajo = vuelve al anterior. */
    public void deslizar(boolean haciaArriba) {
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

    @Override
    public void onInterrupt() { }
}
