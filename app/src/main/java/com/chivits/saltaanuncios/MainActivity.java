package com.chivits.saltaanuncios;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Pantalla principal: tema oscuro con tarjetas y círculos (vacío = apagado, verde = encendido). */
public class MainActivity extends Activity {

    // colores
    private static final int FONDO = Color.rgb(13, 15, 20);
    private static final int TARJETA = Color.rgb(24, 27, 34);
    private static final int BORDE = Color.rgb(40, 44, 54);
    private static final int VERDE = Color.rgb(52, 199, 89);
    private static final int ROJO = Color.rgb(255, 82, 82);
    private static final int BLANCO = Color.rgb(240, 242, 245);
    private static final int GRIS = Color.rgb(150, 156, 168);
    private static final int AZUL = Color.rgb(64, 156, 255);

    private SharedPreferences sp;
    private float d;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView estadoAcc, botonAcc;
    private LinearLayout tarjetaAcc;
    private TextView statAnuncios, statGestos, statVoz, statTiempo;
    private TextView textoDormir, textoAuto, textoDescanso;

    private static class Fila {
        View circulo;
        TextView detalle;
    }

    private Fila fAnuncios, fCamara, fVoz, fAuto, fFlotante, fDescanso;

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            actualizar();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;

        Window w = getWindow();
        w.setStatusBarColor(FONDO);
        w.setNavigationBarColor(FONDO);

        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(dp(16), dp(28), dp(16), dp(32));
        caja.setBackgroundColor(FONDO);

        // ---------- cabecera ----------
        TextView titulo = texto("Salta Anuncios y Scroll", 26, BLANCO);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        caja.addView(titulo);
        caja.addView(texto("Tu asistente para YouTube y TikTok", 14, GRIS));
        espacio(caja, 16);

        // ---------- accesibilidad ----------
        tarjetaAcc = tarjeta(caja);
        LinearLayout filaAcc = new LinearLayout(this);
        filaAcc.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tAcc = new LinearLayout(this);
        tAcc.setOrientation(LinearLayout.VERTICAL);
        TextView tit = texto("Permiso de accesibilidad", 16, BLANCO);
        tit.setTypeface(Typeface.DEFAULT_BOLD);
        tAcc.addView(tit);
        estadoAcc = texto("", 13, GRIS);
        tAcc.addView(estadoAcc);
        filaAcc.addView(tAcc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        botonAcc = boton("Activar", VERDE, new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        filaAcc.addView(botonAcc);
        tarjetaAcc.addView(filaAcc);
        tarjetaAcc.addView(nota("Si sale opaco: Ajustes > Apps > Salta Anuncios y Scroll > ⋮ > \"Permitir ajustes restringidos\"."));

        // ---------- estadísticas ----------
        LinearLayout stats = tarjeta(caja);
        stats.addView(subtitulo("📊  Resumen"));
        LinearLayout g1 = new LinearLayout(this);
        LinearLayout g2 = new LinearLayout(this);
        statAnuncios = stat(g1, "Anuncios omitidos");
        statTiempo = stat(g1, "En TikTok hoy");
        statGestos = stat(g2, "Gestos de cámara");
        statVoz = stat(g2, "Órdenes de voz");
        stats.addView(g1);
        stats.addView(g2);

        // ---------- YouTube ----------
        LinearLayout yt = tarjeta(caja);
        yt.addView(subtitulo("▶️  YouTube"));
        fAnuncios = fila(yt, "Saltar anuncios", "Pulsa \"Omitir anuncio\" en cuanto aparece.", new Runnable() {
            @Override
            public void run() {
                sp.edit().putBoolean("anuncios_on", !sp.getBoolean("anuncios_on", true)).apply();
                actualizar();
            }
        });

        // ---------- TikTok ----------
        LinearLayout tk = tarjeta(caja);
        tk.addView(subtitulo("🎵  TikTok (solo funciona con TikTok abierto)"));

        fCamara = fila(tk, "Scroll con la cámara", "Mano arriba = siguiente · mano abajo = anterior.", new Runnable() {
            @Override
            public void run() {
                alternarCamara();
            }
        });
        tk.addView(nota("Sensibilidad (derecha = movimientos más cortos)"));
        tk.addView(barra("sensibilidad", 50, 100, null));
        tk.addView(casilla("Invertir dirección", "invertir"));
        separador(tk);

        fVoz = fila(tk, "Control por voz", "Habla claro cerca del celular.", new Runnable() {
            @Override
            public void run() {
                alternarVoz();
            }
        });
        tk.addView(nota("Comandos:  \"uno\" → siguiente  ·  \"dos\" → anterior  ·  \"me gusta\" → ❤️  ·  \"pausa\" → pausar/seguir  ·  \"apagar\" → pantalla negra"));
        tk.addView(casilla("Invertir uno / dos", "invertir_voz"));
        separador(tk);

        fAuto = fila(tk, "Auto-scroll", "Pasa al siguiente video solo, cada cierto tiempo.", new Runnable() {
            @Override
            public void run() {
                sp.edit().putBoolean("auto_on", !sp.getBoolean("auto_on", false)).apply();
                if (SaltaService.instancia == null) avisoAccesibilidad();
                actualizar();
            }
        });
        textoAuto = nota("");
        tk.addView(textoAuto);
        tk.addView(barra("auto_seg", 15, 60, new Runnable() {
            @Override
            public void run() {
                if (sp.getInt("auto_seg", 15) < 3) sp.edit().putInt("auto_seg", 3).apply();
            }
        }));

        // ---------- Pantalla ----------
        LinearLayout pant = tarjeta(caja);
        pant.addView(subtitulo("🌙  Pantalla y batería"));
        fFlotante = fila(pant, "Botón flotante para apagar", "Un círculo con luna sobre todas las apps.", new Runnable() {
            @Override
            public void run() {
                boolean nuevo = !sp.getBoolean("flotante_on", false);
                sp.edit().putBoolean("flotante_on", nuevo).apply();
                SaltaService s = SaltaService.instancia;
                if (s != null) s.actualizarFlotante();
                else if (nuevo) avisoAccesibilidad();
                actualizar();
            }
        });
        pant.addView(nota("Al tocarlo, la pantalla se pone negra con brillo mínimo y el táctil se bloquea, pero la app sigue sonando. Para volver: botón de encendido (o 5 toques rápidos). Arrástralo para moverlo."));
        pant.addView(casilla("Usar bloqueo real (apaga de verdad, pero pausa los videos)", "bloqueo_real"));
        LinearLayout filaBotones = new LinearLayout(this);
        filaBotones.addView(boton("Probar ahora", AZUL, new Runnable() {
            @Override
            public void run() {
                SaltaService s = SaltaService.instancia;
                if (s == null) avisoAccesibilidad();
                else s.accionBloqueo();
            }
        }));
        pant.addView(filaBotones);
        pant.addView(nota("Atajo: baja el panel rápido (donde está la linterna), toca el lápiz ✏️ y agrega \"Pantalla negra\"."));
        separador(pant);

        TextView tDorm = texto("Temporizador para dormir", 16, BLANCO);
        tDorm.setTypeface(Typeface.DEFAULT_BOLD);
        pant.addView(tDorm);
        textoDormir = nota("");
        pant.addView(textoDormir);
        LinearLayout filaDorm = new LinearLayout(this);
        int[] mins = {15, 30, 60};
        for (final int m : mins) {
            filaDorm.addView(boton(m + " min", AZUL, new Runnable() {
                @Override
                public void run() {
                    sp.edit().putLong("dormir_fin", System.currentTimeMillis() + m * 60000L).apply();
                    if (SaltaService.instancia == null) avisoAccesibilidad();
                    actualizar();
                }
            }));
        }
        filaDorm.addView(boton("Cancelar", GRIS, new Runnable() {
            @Override
            public void run() {
                sp.edit().putLong("dormir_fin", 0).apply();
                actualizar();
            }
        }));
        pant.addView(filaDorm);
        pant.addView(nota("Al terminar: apaga cámara, voz y auto-scroll, y bloquea el celular."));

        // ---------- Bienestar ----------
        LinearLayout bien = tarjeta(caja);
        bien.addView(subtitulo("💚  Bienestar"));
        fDescanso = fila(bien, "Recordatorio de descanso", "Te avisa cuando llevas mucho tiempo en TikTok.", new Runnable() {
            @Override
            public void run() {
                sp.edit().putBoolean("descanso_on", !sp.getBoolean("descanso_on", false)).apply();
                actualizar();
            }
        });
        textoDescanso = nota("");
        bien.addView(textoDescanso);
        bien.addView(barra("descanso_min", 30, 120, new Runnable() {
            @Override
            public void run() {
                if (sp.getInt("descanso_min", 30) < 5) sp.edit().putInt("descanso_min", 5).apply();
            }
        }));

        TextView pie = texto("Hecho por CHlVOMAN · v2.0", 12, GRIS);
        pie.setGravity(Gravity.CENTER);
        espacio(caja, 8);
        caja.addView(pie);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(FONDO);
        sv.addView(caja);
        setContentView(sv);
    }

    // ======================= piezas de la interfaz =======================

    private int dp(float v) {
        return (int) (v * d);
    }

    private TextView texto(String t, int tam, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(tam);
        tv.setTextColor(color);
        return tv;
    }

    private TextView nota(String t) {
        TextView tv = texto(t, 13, GRIS);
        tv.setPadding(0, dp(4), 0, dp(4));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    private TextView subtitulo(String t) {
        TextView tv = texto(t, 15, AZUL);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private void espacio(LinearLayout p, int alto) {
        p.addView(new View(this), new LinearLayout.LayoutParams(1, dp(alto)));
    }

    private void separador(LinearLayout p) {
        View v = new View(this);
        v.setBackgroundColor(BORDE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        p.addView(v, lp);
    }

    private GradientDrawable redondo(int color, int radio, int borde) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radio));
        if (borde != 0) g.setStroke(dp(1), borde);
        return g;
    }

    private LinearLayout tarjeta(LinearLayout padre) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(redondo(TARJETA, 18, BORDE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        padre.addView(t, lp);
        return t;
    }

    private TextView boton(String t, int color, final Runnable accion) {
        TextView b = texto(t, 14, color == GRIS ? BLANCO : Color.BLACK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setBackground(redondo(color == GRIS ? BORDE : color, 20, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                accion.run();
            }
        });
        return b;
    }

    private TextView stat(LinearLayout fila, String etiqueta) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView num = texto("0", 24, BLANCO);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(num);
        c.addView(texto(etiqueta, 12, GRIS));
        fila.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return num;
    }

    private CheckBox casilla(String t, final String clave) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setTextColor(BLANCO);
        c.setTextSize(14);
        c.setChecked(sp.getBoolean(clave, false));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean marcado) {
                sp.edit().putBoolean(clave, marcado).apply();
            }
        });
        return c;
    }

    private SeekBar barra(final String clave, int def, int max, final Runnable despues) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(sp.getInt(clave, def));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean user) {
                sp.edit().putInt(clave, p).apply();
                if (despues != null) despues.run();
                actualizar();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    private Fila fila(LinearLayout padre, String titulo, String desc, final Runnable accion) {
        Fila f = new Fila();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));

        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);
        TextView t = texto(titulo, 16, BLANCO);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        textos.addView(t);
        textos.addView(texto(desc, 13, GRIS));
        f.detalle = texto("", 12, GRIS);
        textos.addView(f.detalle);
        row.addView(textos, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        f.circulo = new View(this);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(dp(32), dp(32));
        lc.leftMargin = dp(12);
        row.addView(f.circulo, lc);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                accion.run();
            }
        });
        padre.addView(row);
        return f;
    }

    private void pintar(Fila f, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(3), on ? VERDE : GRIS);
        g.setColor(on ? VERDE : Color.TRANSPARENT);
        f.circulo.setBackground(g);
        f.detalle.setText(on ? "● Activado" : "○ Desactivado");
        f.detalle.setTextColor(on ? VERDE : GRIS);
    }

    // ======================= acciones =======================

    private void avisoAccesibilidad() {
        Toast.makeText(this, "Primero activa el permiso de accesibilidad (arriba)", Toast.LENGTH_LONG).show();
    }

    private void alternarCamara() {
        if (CamaraService.corriendo) {
            stopService(new Intent(this, CamaraService.class));
            CamaraService.corriendo = false;
            actualizar();
            return;
        }
        if (SaltaService.instancia == null) avisoAccesibilidad();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pedir(Manifest.permission.CAMERA, 1);
            return;
        }
        iniciarServicio(CamaraService.class);
    }

    private void alternarVoz() {
        if (VozService.corriendo) {
            stopService(new Intent(this, VozService.class));
            VozService.corriendo = false;
            actualizar();
            return;
        }
        if (SaltaService.instancia == null) avisoAccesibilidad();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pedir(Manifest.permission.RECORD_AUDIO, 2);
            return;
        }
        iniciarServicio(VozService.class);
    }

    private void pedir(String permiso, int codigo) {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{permiso, Manifest.permission.POST_NOTIFICATIONS}, codigo);
        } else {
            requestPermissions(new String[]{permiso}, codigo);
        }
    }

    private void iniciarServicio(Class<?> clase) {
        Intent i = new Intent(this, clase);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permisos, int[] res) {
        super.onRequestPermissionsResult(code, permisos, res);
        if (code == 1) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                iniciarServicio(CamaraService.class);
            } else {
                Toast.makeText(this, "Sin permiso de cámara", Toast.LENGTH_LONG).show();
            }
        } else if (code == 2) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                iniciarServicio(VozService.class);
            } else {
                Toast.makeText(this, "Sin permiso de micrófono", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SaltaService s = SaltaService.instancia;
        if (s != null) s.actualizarFlotante();
        h.removeCallbacks(refresco);
        h.post(refresco);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresco);
    }

    private void actualizar() {
        if (estadoAcc == null) return;
        boolean acc = servicioActivo();
        estadoAcc.setText(acc ? "✅ Activado, todo listo" : "❌ Desactivado: sin esto nada funciona");
        estadoAcc.setTextColor(acc ? VERDE : ROJO);
        botonAcc.setVisibility(acc ? View.GONE : View.VISIBLE);
        tarjetaAcc.setBackground(redondo(TARJETA, 18, acc ? BORDE : ROJO));

        statAnuncios.setText(String.valueOf(sp.getInt("omitidos", 0)));
        statGestos.setText(String.valueOf(sp.getInt("gestos", 0)));
        statVoz.setText(String.valueOf(sp.getInt("voz", 0)));
        int seg = SaltaService.instancia != null ? SaltaService.segundosHoy() : 0;
        statTiempo.setText(seg >= 3600 ? (seg / 3600) + " h " + (seg % 3600 / 60) + " min" : (seg / 60) + " min");

        pintar(fAnuncios, sp.getBoolean("anuncios_on", true));
        pintar(fCamara, CamaraService.corriendo);
        pintar(fVoz, VozService.corriendo);
        pintar(fAuto, sp.getBoolean("auto_on", false));
        pintar(fFlotante, sp.getBoolean("flotante_on", false));
        pintar(fDescanso, sp.getBoolean("descanso_on", false));

        textoAuto.setText("Cada " + Math.max(3, sp.getInt("auto_seg", 15)) + " segundos");
        textoDescanso.setText("Avisar cada " + Math.max(5, sp.getInt("descanso_min", 30)) + " minutos de TikTok");

        long fin = sp.getLong("dormir_fin", 0);
        long resta = fin - System.currentTimeMillis();
        if (fin > 0 && resta > 0) {
            long m = resta / 60000, s = (resta / 1000) % 60;
            textoDormir.setText("⏳ Se apaga en " + m + ":" + (s < 10 ? "0" : "") + s);
            textoDormir.setTextColor(VERDE);
        } else {
            textoDormir.setText("Elige en cuánto tiempo quieres que se apague todo.");
            textoDormir.setTextColor(GRIS);
        }
    }

    private boolean servicioActivo() {
        String lista = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return lista != null && lista.contains(getPackageName() + "/");
    }
}
