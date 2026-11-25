package de.foxmusik.app;

import java.util.ArrayList;

// Lautheit eines Titels nach ITU-R BS.1770 (LUFS), wie sie auch Streamingdienste zum Angleichen nutzen.
// Wird mit dekodierten PCM-Daten gefuettert, braucht dabei nur wenig Speicher.
public class Loudness {
    private final int ch;
    private final int block;
    private final double[] b0 = new double[2], b1 = new double[2], b2 = new double[2], a1 = new double[2], a2 = new double[2];
    private final double[][] z;
    private final double[] sum;
    private int n = 0;
    private double peak = 0;
    private final ArrayList<Double> e100 = new ArrayList<Double>();

    public Loudness(int sampleRate, int channels) {
        ch = Math.max(1, channels);
        block = Math.max(1, sampleRate / 10);
        z = new double[ch][8];
        sum = new double[ch];
        // Stufe 1: Hoehenanhebung (Kopf-Effekt)
        double f0 = 1681.974450955533, g = 3.999843853973347, q = 0.7071752369554196;
        double k = Math.tan(Math.PI * f0 / sampleRate), vh = Math.pow(10, g / 20), vb = Math.pow(vh, 0.4996667741545416);
        double a0 = 1 + k / q + k * k;
        b0[0] = (vh + vb * k / q + k * k) / a0;
        b1[0] = 2 * (k * k - vh) / a0;
        b2[0] = (vh - vb * k / q + k * k) / a0;
        a1[0] = 2 * (k * k - 1) / a0;
        a2[0] = (1 - k / q + k * k) / a0;
        // Stufe 2: Hochpass (tiefe Frequenzen zaehlen weniger)
        f0 = 38.13547087602444;
        q = 0.5003270373238773;
        k = Math.tan(Math.PI * f0 / sampleRate);
        double d = 1 + k / q + k * k;
        b0[1] = 1;
        b1[1] = -2;
        b2[1] = 1;
        a1[1] = 2 * (k * k - 1) / d;
        a2[1] = (1 - k / q + k * k) / d;
    }

    // Ein Abtastwert je Kanal, Wertebereich -1..1, Kanaele abwechselnd
    public void feed(float[] interleaved, int count) {
        for (int i = 0; i + ch <= count; i += ch) {
            for (int c = 0; c < ch; c++) {
                double x = interleaved[i + c];
                double ax = Math.abs(x);
                if (ax > peak) {
                    peak = ax;
                }
                double[] s = z[c];
                double y = b0[0] * x + b1[0] * s[0] + b2[0] * s[1] - a1[0] * s[2] - a2[0] * s[3];
                s[1] = s[0];
                s[0] = x;
                s[3] = s[2];
                s[2] = y;
                double w = b0[1] * y + b1[1] * s[4] + b2[1] * s[5] - a1[1] * s[6] - a2[1] * s[7];
                s[5] = s[4];
                s[4] = y;
                s[7] = s[6];
                s[6] = w;
                sum[c] += w * w;
            }
            if (++n >= block) {
                double e = 0;
                for (int c = 0; c < ch; c++) {
                    e += sum[c] / n;
                    sum[c] = 0;
                }
                e100.add(e);
                n = 0;
            }
        }
    }

    private static double lufs(double meanSq) {
        return -0.691 + 10 * Math.log10(Math.max(meanSq, 1e-12));
    }

    // Gemessene Lautheit in LUFS (400-ms-Fenster, 75 % Ueberlappung, absolute und relative Schwelle)
    public double result() {
        int w = e100.size() - 3;
        if (w <= 0) {
            return -70;
        }
        double[] win = new double[w];
        for (int i = 0; i < w; i++) {
            win[i] = (e100.get(i) + e100.get(i + 1) + e100.get(i + 2) + e100.get(i + 3)) / 4;
        }
        double s = 0;
        int c = 0;
        for (double v : win) {
            if (lufs(v) > -70) {
                s += v;
                c++;
            }
        }
        if (c == 0) {
            return -70;
        }
        double rel = lufs(s / c) - 10;
        s = 0;
        c = 0;
        for (double v : win) {
            if (lufs(v) > -70 && lufs(v) > rel) {
                s += v;
                c++;
            }
        }
        return c == 0 ? -70 : lufs(s / c);
    }

    public double peakDb() {
        return 20 * Math.log10(Math.max(peak, 1e-9));
    }
}
