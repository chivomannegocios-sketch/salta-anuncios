package com.chivits.saltaanuncios;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Atajo del panel rápido (donde están el Wi-Fi y la linterna): apaga la pantalla sin parar la app. */
public class PantallaTile extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(SaltaService.instancia != null ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        SaltaService s = SaltaService.instancia;
        if (s == null) {
            Toast.makeText(this, "Activa primero la accesibilidad de Salta Anuncios", Toast.LENGTH_LONG).show();
            return;
        }
        s.accionDesdePanel();
    }
}
