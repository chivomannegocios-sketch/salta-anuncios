package com.chivits.saltaanuncios;

/**
 * Detecta un movimiento vertical (de la mano o el dedo) comparando cuadros de la cámara.
 * Trabaja con una cuadrícula pequeña de brillo (GW x GH). No usa internet ni guarda imágenes.
 */
public class Detector {
    public static final int GW = 48, GH = 36;
    private static final int DIF = 28;          // cuánto debe cambiar el brillo para contar como movimiento
    private static final int MIN_CELDAS = 6;    // mínimo de celdas en movimiento
    private static final float MAX_FRACCION = 0.45f; // si se mueve casi toda la imagen, se ignora (cambio de luz)

    /** Cuánto debe recorrer el movimiento (0 a 1 del alto de la imagen). Menor = más sensible. */
    public volatile float umbral = 0.22f;

    private final int orient;
    private byte[] previo;
    private final long[] ts = new long[40];
    private final float[] ys = new float[40];
    private int n = 0;
    private long bloqueadoHasta = 0;

    /** orient: orientación del sensor de la cámara (0, 90, 180 o 270). */
    public Detector(int orient) {
        this.orient = orient;
    }

    /** Olvida lo anterior (por ejemplo, cuando se vuelve a entrar a TikTok). */
    public void reiniciar() {
        previo = null;
        n = 0;
    }

    /** Devuelve -1 si la mano subió, 1 si bajó, 0 si no hubo gesto. */
    public int alimentar(byte[] g, long t) {
        if (previo == null) {
            previo = g.clone();
            return 0;
        }
        int mov = 0;
        float sx = 0, sy = 0;
        for (int y = 0; y < GH; y++) {
            for (int x = 0; x < GW; x++) {
                int i = y * GW + x;
                int d = (g[i] & 0xFF) - (previo[i] & 0xFF);
                if (d < 0) d = -d;
                if (d > DIF) {
                    mov++;
                    sx += x + 0.5f;
                    sy += y + 0.5f;
                }
            }
        }
        System.arraycopy(g, 0, previo, 0, g.length);

        if (t < bloqueadoHasta) {
            n = 0;
            return 0;
        }
        if (mov < MIN_CELDAS || mov > GW * GH * MAX_FRACCION) return 0;

        float uy = vertical(sx / mov / GW, sy / mov / GH);
        if (n > 0 && t - ts[n - 1] > 300) n = 0; // hubo una pausa: empezar de nuevo
        if (n == ts.length) quitar(1);
        ts[n] = t;
        ys[n] = uy;
        n++;

        int viejos = 0;
        while (viejos < n - 1 && t - ts[viejos] > 700) viejos++;
        if (viejos > 0) quitar(viejos);
        if (n < 4) return 0;

        float dy = ys[n - 1] - ys[0];
        if (Math.abs(dy) < umbral) return 0;

        int pasos = 0, mismo = 0;
        for (int i = 1; i < n; i++) {
            float s = ys[i] - ys[i - 1];
            if (Math.abs(s) > 0.002f) {
                pasos++;
                if ((s > 0) == (dy > 0)) mismo++;
            }
        }
        if (pasos == 0 || mismo < 0.7f * pasos) return 0;

        n = 0;
        bloqueadoHasta = t + 1100;
        return dy < 0 ? -1 : 1;
    }

    private void quitar(int k) {
        System.arraycopy(ts, k, ts, 0, n - k);
        System.arraycopy(ys, k, ys, 0, n - k);
        n -= k;
    }

    /** Pasa la posición en el cuadro de la cámara a "altura real" (0 = arriba, 1 = abajo). */
    private float vertical(float nx, float ny) {
        switch (orient) {
            case 90: return nx;
            case 180: return 1f - ny;
            case 270: return 1f - nx;
            default: return ny;
        }
    }
}
