package com.chivits.saltaanuncios;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView estado, estadoCamara;
    private Button botonCamara;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("stats", Context.MODE_PRIVATE);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(pad, pad * 2, pad, pad);

        caja.addView(texto("Salta Anuncios y Scroll", 28, Color.BLACK));

        // ---------- Parte 1: accesibilidad ----------
        caja.addView(texto("\n1) Permiso de accesibilidad (obligatorio)", 20, Color.BLACK));
        estado = texto("", 18, Color.BLACK);
        caja.addView(estado);
        caja.addView(texto(
                "Sirve para pulsar \"Omitir anuncio\" en YouTube y para deslizar la pantalla.\n"
                + "• Toca el botón, entra a \"Salta Anuncios y Scroll\" y activa el interruptor.\n"
                + "• Si no te deja (Android 13 o más): Ajustes > Apps > Salta Anuncios y Scroll > menú de 3 puntos > \"Permitir ajustes restringidos\"; luego vuelve a intentar.",
                15, Color.DKGRAY));
        Button abrir = new Button(this);
        abrir.setText("Abrir ajustes de accesibilidad");
        abrir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        caja.addView(abrir);

        // ---------- Parte 2: cámara ----------
        caja.addView(texto("\n2) Control de scroll con la cámara", 20, Color.BLACK));
        estadoCamara = texto("", 18, Color.BLACK);
        caja.addView(estadoCamara);
        caja.addView(texto(
                "Pasos: toca \"Iniciar\", abre TikTok, y mueve la mano o el dedo hacia ARRIBA o hacia ABAJO frente a la cámara delantera (a 20-30 cm), con un movimiento claro y el resto quieto.\n"
                + "• Mano sube → pasa al siguiente video.\n"
                + "• Mano baja → vuelve al anterior.\n"
                + "Detecta movimiento, no sabe si es un dedo. Si alguien se mueve detrás o hay cambios de luz, puede equivocarse.",
                15, Color.DKGRAY));

        botonCamara = new Button(this);
        botonCamara.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                alternarCamara();
            }
        });
        caja.addView(botonCamara);

        caja.addView(texto("\nSensibilidad (más a la derecha = detecta movimientos más cortos)", 15, Color.DKGRAY));
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

        CheckBox inv = new CheckBox(this);
        inv.setText("Invertir dirección (mano sube = video anterior)");
        inv.setChecked(sp.getBoolean("invertir", false));
        inv.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton c, boolean marcado) {
                sp.edit().putBoolean("invertir", marcado).apply();
            }
        });
        caja.addView(inv);

        ScrollView sv = new ScrollView(this);
        sv.addView(caja);
        setContentView(sv);
    }

    private TextView texto(String t, int sp_, int color) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp_);
        tv.setTextColor(color);
        return tv;
    }

    private void alternarCamara() {
        if (CamaraService.corriendo) {
            stopService(new Intent(this, CamaraService.class));
            CamaraService.corriendo = false;
            actualizar();
            return;
        }
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
        iniciarCamara();
    }

    private void iniciarCamara() {
        Intent i = new Intent(this, CamaraService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        botonCamara.postDelayed(new Runnable() {
            @Override
            public void run() {
                actualizar();
            }
        }, 700);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permisos, int[] res) {
        super.onRequestPermissionsResult(code, permisos, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCamara();
        } else {
            estadoCamara.setText("❌ Sin permiso de cámara");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizar();
    }

    private void actualizar() {
        int n = sp.getInt("omitidos", 0);
        if (servicioActivo()) {
            estado.setText("✅ Activado — anuncios omitidos: " + n);
            estado.setTextColor(Color.rgb(0, 128, 0));
        } else {
            estado.setText("❌ Desactivado (anuncios omitidos hasta ahora: " + n + ")");
            estado.setTextColor(Color.rgb(176, 0, 32));
        }
        int g = sp.getInt("gestos", 0);
        if (CamaraService.corriendo) {
            estadoCamara.setText("✅ Cámara activa — gestos detectados: " + g);
            estadoCamara.setTextColor(Color.rgb(0, 128, 0));
            botonCamara.setText("Detener control por cámara");
        } else {
            estadoCamara.setText("⏸ Cámara detenida (gestos detectados: " + g + ")");
            estadoCamara.setTextColor(Color.rgb(176, 0, 32));
            botonCamara.setText("Iniciar control por cámara");
        }
    }

    private boolean servicioActivo() {
        String lista = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return lista != null && lista.contains(getPackageName() + "/");
    }
}
