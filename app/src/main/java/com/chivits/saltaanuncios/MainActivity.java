package com.chivits.saltaanuncios;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int VERDE = Color.rgb(46, 125, 50);
    private static final int GRIS = Color.rgb(150, 150, 150);

    private SharedPreferences sp;
    private float d;
    private TextView estadoAcc;

    /** Una fila con un círculo (vacío = apagado, verde = encendido) y su texto. */
    private static class Fila {
        View circulo;
        TextView titulo, detalle;
    }

    private Fila filaAnuncios, filaCamara, filaVoz, filaBloqueo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);

        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(pad, pad * 2, pad, pad);

        caja.addView(texto("Salta Anuncios y Scroll", 28, Color.BLACK));

        // ---------- Permiso de accesibilidad ----------
        caja.addView(texto("\nPermiso de accesibilidad (obligatorio)", 18, Color.BLACK));
        estadoAcc = texto("", 16, Color.BLACK);
        caja.addView(estadoAcc);
        caja.addView(texto("Sin este permiso nada funciona. Si sale opaco: Ajustes > Apps > Salta Anuncios y Scroll > ⋮ > \"Permitir ajustes restringidos\".",
                14, Color.DKGRAY));
        Button abrir = new Button(this);
        abrir.setText("Abrir ajustes de accesibilidad");
        abrir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        caja.addView(abrir);

        // ---------- Funciones ----------
        caja.addView(texto("\nFunciones (toca para activar o desactivar)", 18, Color.BLACK));

        filaAnuncios = fila(caja, "Saltar anuncios en YouTube", new Runnable() {
            @Override
            public void run() {
                sp.edit().putBoolean("anuncios_on", !sp.getBoolean("anuncios_on", true)).apply();
                actualizar();
            }
        });

        filaCamara = fila(caja, "Scroll con la cámara (solo en TikTok)", new Runnable() {
            @Override
            public void run() {
                alternarCamara();
            }
        });
        caja.addView(texto("Mueve la mano hacia arriba (siguiente video) o hacia abajo (anterior) frente a la cámara delantera, a 20-30 cm.",
                13, Color.DKGRAY));
        caja.addView(texto("Sensibilidad (más a la derecha = detecta movimientos más cortos)", 13, Color.DKGRAY));
        SeekBar barra = new SeekBar(this);
        barra.setMax(100);
        barra.setProgress(sp.getInt("sensibilidad", 50));
        barra.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                sp.edit().putInt("sensibilidad", p).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        });
        caja.addView(barra);
        caja.addView(casilla("Invertir dirección de la cámara", "invertir"));

        filaVoz = fila(caja, "Scroll con la voz (solo en TikTok)", new Runnable() {
            @Override
            public void run() {
                alternarVoz();
            }
        });
        caja.addView(texto("Di \"uno\" (siguiente video) o \"dos\" (anterior). Usa la voz de Google; en algunos celulares suena un pitido.",
                13, Color.DKGRAY));
        caja.addView(casilla("Invertir voz (uno = anterior, dos = siguiente)", "invertir_voz"));

        filaBloqueo = fila(caja, "Botón flotante para apagar la pantalla", new Runnable() {
            @Override
            public void run() {
                alternarBloqueo();
            }
        });
        caja.addView(texto("Aparece un círculo con candado encima de todas las apps (arrástralo para moverlo). Al tocarlo, la pantalla se pone negra con el brillo al mínimo y el táctil se bloquea, pero la app de abajo SIGUE funcionando (el video sigue sonando, la voz sigue escuchando).\n"
                + "Para volver: aprieta el botón de encendido.\n"
                + "Salida de emergencia: toca la pantalla 5 veces rápido.",
                13, Color.DKGRAY));
        caja.addView(casilla("Usar bloqueo real del sistema (apaga de verdad, pero pausa los videos; Android 9+)", "bloqueo_real"));

        Button probar = new Button(this);
        probar.setText("Probar: apagar pantalla ahora");
        probar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SaltaService s = SaltaService.instancia;
                if (s == null) avisoAccesibilidad();
                else s.accionBloqueo();
            }
        });
        caja.addView(probar);

        ScrollView sv = new ScrollView(this);
        sv.addView(caja);
        setContentView(sv);
    }

    // ---------- piezas de la interfaz ----------

    private TextView texto(String t, int tam, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(tam);
        tv.setTextColor(color);
        return tv;
    }

    private CheckBox casilla(String t, final String clave) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(sp.getBoolean(clave, false));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean marcado) {
                sp.edit().putBoolean(clave, marcado).apply();
            }
        });
        return c;
    }

    private Fila fila(LinearLayout padre, String titulo, final Runnable accion) {
        Fila f = new Fila();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (14 * d), 0, (int) (6 * d));

        f.circulo = new View(this);
        int tam = (int) (34 * d);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(tam, tam);
        lc.rightMargin = (int) (14 * d);
        row.addView(f.circulo, lc);

        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);
        f.titulo = texto(titulo, 17, Color.BLACK);
        f.detalle = texto("", 13, GRIS);
        textos.addView(f.titulo);
        textos.addView(f.detalle);
        row.addView(textos, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

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

    private void pintar(Fila f, boolean on, String detalle) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke((int) (3 * d), on ? VERDE : GRIS);
        g.setColor(on ? VERDE : Color.TRANSPARENT);
        f.circulo.setBackground(g);
        f.detalle.setText((on ? "Activado" : "Desactivado") + (detalle.isEmpty() ? "" : " · " + detalle));
        f.detalle.setTextColor(on ? VERDE : GRIS);
    }

    // ---------- acciones ----------

    private void avisoAccesibilidad() {
        Toast.makeText(this, "Primero activa el permiso de accesibilidad", Toast.LENGTH_LONG).show();
    }

    private void alternarBloqueo() {
        boolean nuevo = !sp.getBoolean("flotante_on", false);
        sp.edit().putBoolean("flotante_on", nuevo).apply();
        SaltaService s = SaltaService.instancia;
        if (s != null) s.actualizarFlotante();
        else if (nuevo) avisoAccesibilidad();
        actualizar();
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
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.CAMERA,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            }
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
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, 2);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            }
            return;
        }
        iniciarServicio(VozService.class);
    }

    private void iniciarServicio(Class<?> clase) {
        Intent i = new Intent(this, clase);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        estadoAcc.postDelayed(new Runnable() {
            @Override
            public void run() {
                actualizar();
            }
        }, 700);
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
        actualizar();
    }

    private void actualizar() {
        boolean acc = servicioActivo();
        estadoAcc.setText(acc ? "✅ Activado" : "❌ Desactivado: toca el botón y actívalo");
        estadoAcc.setTextColor(acc ? VERDE : Color.rgb(176, 0, 32));

        pintar(filaAnuncios, sp.getBoolean("anuncios_on", true),
                "omitidos: " + sp.getInt("omitidos", 0));
        pintar(filaCamara, CamaraService.corriendo, "gestos: " + sp.getInt("gestos", 0));
        pintar(filaVoz, VozService.corriendo, "órdenes: " + sp.getInt("voz", 0));
        pintar(filaBloqueo, sp.getBoolean("flotante_on", false), "");
    }

    private boolean servicioActivo() {
        String lista = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return lista != null && lista.contains(getPackageName() + "/");
    }
}
