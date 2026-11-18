package com.abhijit.lifeengine.core;

/**
 * Physically based single-scattering atmosphere (Rayleigh + Mie) evaluated on the CPU into a small
 * lat-long radiance table that shaders sample for sky, fog, reflections and ambient light.
 * Also derives sun light colour (transmittance) and hemispheric ambient irradiance.
 *
 * LUT mapping: u = atan2(z, x) / 2π + 0.5 ; v = 0.5 + 0.5 * sign(y) * sqrt(|y|)   (y = sin elevation)
 */
public final class Atmosphere {
    public static final int LUT_W = 96, LUT_H = 48;

    private static final double R_EARTH = 6360e3, R_ATMO = 6420e3;
    private static final double[] BETA_R = {5.8e-6, 13.5e-6, 33.1e-6};
    private static final double BETA_M = 21e-6;
    private static final double HR = 7994, HM = 1200, G = 0.76;
    private static final float SUN_INTENSITY = 22f;
    private static final float SKY_GAIN = 3.0f;

    public final float[] lut = new float[LUT_W * LUT_H * 3];
    public final float[] sunColor = new float[3];
    public final float[] moonColor = new float[3];
    public final float[] ambientSky = new float[3];
    public final float[] ambientGround = new float[3];
    public final float[] horizonColor = new float[3];
    public float exposure = 0.1f;

    /**
     * @param sun unit vector towards the sun
     * @param moon unit vector towards the moon
     * @param turbidity 1 = clear, up to ~4 hazy/overcast (scales Mie + dims sun)
     * @param overcast 0..1 cloud cover (greys sky, dims direct light)
     */
    public void update(float[] sun, float[] moon, float turbidity, float overcast) {
        double camH = R_EARTH + 400;
        double mieScale = turbidity;
        float[] col = new float[3];
        float[] rowCache = new float[LUT_W * 3];
        java.util.Arrays.fill(rowCache, -1f);
        for (int j = 0; j < LUT_H; j++) {
            double s = (j + 0.5) / LUT_H * 2 - 1;
            double y = Math.signum(s) * s * s;
            double yy = Math.max(y, 0.0);
            double ch = Math.sqrt(1 - yy * yy);
            for (int i = 0; i < LUT_W; i++) {
                double az = ((i + 0.5) / LUT_W - 0.5) * 2 * Math.PI;
                double dx = Math.cos(az) * ch, dz = Math.sin(az) * ch;
                if (y < 0 && rowCache[i * 3] >= 0) {
                    col[0] = rowCache[i * 3]; col[1] = rowCache[i * 3 + 1]; col[2] = rowCache[i * 3 + 2];
                } else {
                    scatter(camH, dx, yy, dz, sun, mieScale, col);
                    if (y < 0) { rowCache[i * 3] = col[0]; rowCache[i * 3 + 1] = col[1]; rowCache[i * 3 + 2] = col[2]; }
                }
                // night sky base + moon glow
                double moonCos = dx * moon[0] + yy * moon[1] + dz * moon[2];
                float moonUp = Noise.clamp(moon[1] * 4f + 0.2f, 0, 1);
                float glow = (float) Math.pow(Math.max(0, moonCos), 12) * 0.02f * moonUp;
                float night = 0.0026f * (1f - 0.5f * (float) yy);
                col[0] += night * 0.45f + glow * 0.8f; col[1] += night * 0.6f + glow * 0.9f; col[2] += night * 1.0f + glow;
                if (y < 0) { // below horizon: dim haze towards ground
                    float k = (float) Math.max(0.25, 1 + y * 3);
                    col[0] *= k; col[1] *= k; col[2] *= k;
                }
                // overcast: desaturate and darken towards grey
                if (overcast > 0) {
                    float l = 0.3f * col[0] + 0.59f * col[1] + 0.11f * col[2];
                    float o = overcast * 0.85f;
                    for (int c = 0; c < 3; c++) col[c] = (col[c] * (1 - o) + l * o) * (1 - 0.45f * overcast);
                }
                int o = (j * LUT_W + i) * 3;
                lut[o] = col[0]; lut[o + 1] = col[1]; lut[o + 2] = col[2];
            }
        }
        // sun transmittance
        double[] od = opticalDepth(camH, sun[0], Math.max(sun[1], -0.2), sun[2]);
        float horizonFade = Noise.smoothstep(-0.06f, 0.04f, sun[1]);
        for (int c = 0; c < 3; c++) {
            double tau = BETA_R[c] * od[0] + BETA_M * 1.1 * mieScale * od[1];
            sunColor[c] = (float) (SUN_INTENSITY * Math.exp(-tau)) * horizonFade * (1f - 0.8f * overcast);
        }
        float moonUp = Noise.smoothstep(-0.05f, 0.15f, moon[1]) * (1f - Noise.smoothstep(-0.1f, 0.1f, sun[1]));
        moonColor[0] = 0.10f * moonUp; moonColor[1] = 0.13f * moonUp; moonColor[2] = 0.2f * moonUp;
        moonColor[0] *= (1 - 0.8f * overcast); moonColor[1] *= (1 - 0.8f * overcast); moonColor[2] *= (1 - 0.8f * overcast);

        // hemispherical irradiance (cosine weighted average of upper LUT)
        double[] acc = new double[3];
        double wsum = 0;
        for (int j = LUT_H / 2; j < LUT_H; j++) {
            double s = (j + 0.5) / LUT_H * 2 - 1;
            double y = s * s;
            for (int i = 0; i < LUT_W; i += 2) {
                int o = (j * LUT_W + i) * 3;
                double wgt = y * (2 * s); // cos * dSolidAngle in mapped coords
                acc[0] += lut[o] * wgt; acc[1] += lut[o + 1] * wgt; acc[2] += lut[o + 2] * wgt; wsum += wgt;
            }
        }
        for (int c = 0; c < 3; c++) {
            ambientSky[c] = (float) (acc[c] / wsum) * 1.6f + moonColor[c] * 0.25f;
            float sunBounce = sunColor[c] * Math.max(0, sun[1]) * 0.06f;
            ambientGround[c] = ambientSky[c] * 0.35f + sunBounce + moonColor[c] * 0.05f;
        }
        int hj = LUT_H / 2 + 1;
        float[] h = {0, 0, 0};
        for (int i = 0; i < LUT_W; i++) for (int c = 0; c < 3; c++) h[c] += lut[(hj * LUT_W + i) * 3 + c] / LUT_W;
        System.arraycopy(h, 0, horizonColor, 0, 3);

        // auto exposure from scene illuminance
        float lum = 0.25f * (0.3f * sunColor[0] + 0.59f * sunColor[1] + 0.11f * sunColor[2]) * Math.max(0.05f, sun[1] + 0.1f)
                + 1.2f * (0.3f * ambientSky[0] + 0.59f * ambientSky[1] + 0.11f * ambientSky[2])
                + 0.3f * (moonColor[1]);
        exposure = Noise.clamp(0.42f / (lum + 0.012f), 0.05f, 18f);
    }

    private static double[] opticalDepth(double h0, double dx, double dy, double dz) {
        double len = rayExit(h0, dx, dy, dz);
        int n = 16;
        double seg = len / n, odR = 0, odM = 0;
        for (int k = 0; k < n; k++) {
            double t = (k + 0.5) * seg;
            double px = dx * t, py = h0 + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - R_EARTH;
            if (h < 0) { odR += 1e9; break; }
            odR += Math.exp(-h / HR) * seg; odM += Math.exp(-h / HM) * seg;
        }
        return new double[]{odR, odM};
    }

    private static double rayExit(double h0, double dx, double dy, double dz) {
        // ray from (0,h0,0) to atmosphere sphere
        double b = 2 * h0 * dy, c = h0 * h0 - R_ATMO * R_ATMO;
        double disc = b * b - 4 * c;
        return (-b + Math.sqrt(Math.max(0, disc))) / 2;
    }

    private void scatter(double h0, double dx, double dy, double dz, float[] sun, double mieScale, float[] out) {
        double len = rayExit(h0, dx, dy, dz);
        int n = 12;
        double seg = len / n;
        double mu = dx * sun[0] + dy * sun[1] + dz * sun[2];
        double phaseR = 3.0 / (16 * Math.PI) * (1 + mu * mu);
        double g2 = G * G;
        double phaseM = 3.0 / (8 * Math.PI) * ((1 - g2) * (1 + mu * mu)) / ((2 + g2) * Math.pow(1 + g2 - 2 * G * mu, 1.5));
        double odR = 0, odM = 0;
        double[] sumR = new double[3], sumM = new double[3];
        for (int k = 0; k < n; k++) {
            double t = (k + 0.5) * seg;
            double px = dx * t, py = h0 + dy * t, pz = dz * t;
            double r = Math.sqrt(px * px + py * py + pz * pz);
            double h = r - R_EARTH;
            double hr = Math.exp(-h / HR) * seg, hm = Math.exp(-h / HM) * seg;
            odR += hr; odM += hm;
            // light optical depth from sample to sun
            double[] lod = opticalDepth(r, (px * 0 + sun[0]), sun[1], sun[2]);
            // (approximation: evaluates sun path from altitude r along sun dir in local frame)
            if (lod[0] > 1e8) continue;
            for (int c = 0; c < 3; c++) {
                double tau = BETA_R[c] * (odR + lod[0]) + BETA_M * 1.1 * mieScale * (odM + lod[1]);
                double att = Math.exp(-tau);
                sumR[c] += att * hr; sumM[c] += att * hm;
            }
        }
        float sunAbove = Noise.smoothstep(-0.12f, 0.02f, sun[1]);
        for (int c = 0; c < 3; c++)
            out[c] = (float) (SKY_GAIN * SUN_INTENSITY * (sumR[c] * BETA_R[c] * phaseR + sumM[c] * BETA_M * mieScale * phaseM)) * sunAbove;
    }
}
