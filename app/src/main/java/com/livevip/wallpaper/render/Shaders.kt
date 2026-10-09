package com.livevip.wallpaper.render

/**
 * GLSL ES 1.00 shaders. They compile in both OpenGL ES 2.0 and ES 3.0 contexts, so the renderer can
 * fall back to ES 2.0 on devices without ES 3.0.
 */
object Shaders {

    /** Shared vertex shader: positions are NDC, offset/scale are applied around a center. */
    const val QUAD_VS = """
        attribute vec2 aPos;
        attribute vec2 aUv;
        uniform vec2 uOffset;
        uniform vec2 uCenter;
        uniform float uScale;
        varying vec2 vUv;
        void main() {
            vec2 p = (aPos - uCenter) * uScale + uCenter + uOffset;
            gl_Position = vec4(p, 0.0, 1.0);
            vUv = aUv;
        }
    """

    /**
     * Background: zoom for edge coverage, depth-weighted parallax shift, edge fill with a blurred
     * copy of the border, blur, brightness/contrast/saturation and color tint.
     */
    const val BACKGROUND_FS = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uBg;
        uniform sampler2D uDepth;
        uniform float uHasDepth;
        uniform vec2 uBgOffset;
        uniform float uZoom;
        uniform float uBlur;
        uniform float uEdgeFill;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uSaturation;
        uniform vec3 uTint;
        uniform float uTintAmount;

        vec3 boxBlur(sampler2D t, vec2 uv, float r) {
            vec3 s = texture2D(t, uv).rgb * 4.0;
            s += texture2D(t, uv + vec2(r, 0.0)).rgb * 2.0;
            s += texture2D(t, uv - vec2(r, 0.0)).rgb * 2.0;
            s += texture2D(t, uv + vec2(0.0, r)).rgb * 2.0;
            s += texture2D(t, uv - vec2(0.0, r)).rgb * 2.0;
            s += texture2D(t, uv + vec2(r, r)).rgb;
            s += texture2D(t, uv + vec2(-r, r)).rgb;
            s += texture2D(t, uv + vec2(r, -r)).rgb;
            s += texture2D(t, uv - vec2(r, r)).rgb;
            return s / 16.0;
        }

        void main() {
            vec2 uvZ = (vUv - 0.5) / uZoom + 0.5;
            float d = 0.4;
            if (uHasDepth > 0.5) {
                d = texture2D(uDepth, vUv).r;
            }
            vec2 suv = uvZ + uBgOffset * (0.5 + d);
            bool outside = suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0;
            vec2 cuv = clamp(suv, 0.0, 1.0);
            vec3 col = texture2D(uBg, cuv).rgb;
            if (uBlur > 0.001) {
                col = mix(col, boxBlur(uBg, cuv, uBlur * 0.012), uBlur);
            }
            if (outside && uEdgeFill > 0.5) {
                col = boxBlur(uBg, cuv, 0.04);
            }
            col = (col - 0.5) * uContrast + 0.5 + uBrightness;
            float lum = dot(col, vec3(0.299, 0.587, 0.114));
            col = mix(vec3(lum), col, uSaturation);
            col = mix(col, col * uTint, uTintAmount);
            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """

    /**
     * Character layer: mask-weighted procedural sway (hair / cloth / sword), outer glow from a
     * blurred silhouette, inner glow from the silhouette edge. Output is premultiplied alpha.
     */
    const val LAYER_FS = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform sampler2D uMask;
        uniform float uHasMask;
        uniform float uFallbackWeight;
        uniform sampler2D uGlow;
        uniform float uHasGlow;
        uniform vec2 uRigDir;
        uniform float uRigAmp;
        uniform float uRigFreq;
        uniform float uRigPhase;
        uniform vec2 uRigPivot;
        uniform float uRigMode;
        uniform float uRigStrength;
        uniform float uTime;
        uniform float uGlowSamples;
        uniform vec2 uGlowRadius;
        uniform float uOuterOn;
        uniform vec3 uOuterColor;
        uniform float uOuterIntensity;
        uniform float uInnerOn;
        uniform vec3 uInnerColor;
        uniform float uInnerIntensity;
        uniform float uPulseSpeed;

        void main() {
            vec2 uv = vUv;
            float w = uFallbackWeight;
            if (uHasMask > 0.5) {
                w = texture2D(uMask, uv).r;
            }
            float lever = clamp(abs(uv.y - uRigPivot.y) * 1.5, 0.0, 1.0);
            float t = uTime * uRigFreq * 6.2831853 + uRigPhase;
            float f = sin(t);
            if (uRigMode > 0.5 && uRigMode < 1.5) {
                f = sin(t + uv.y * 6.0) * 0.8 + sin(t * 0.5 + uv.x * 4.0) * 0.2;
            } else if (uRigMode >= 1.5) {
                f = sin(t * 2.7) * 0.5 + sin(t * 4.1 + uv.x * 9.0) * 0.5;
            }
            vec2 disp = uRigDir * (uRigAmp * uRigStrength * f * w * lever);
            vec2 suv = uv - disp;
            vec4 c = texture2D(uTex, suv);
            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) {
                c = vec4(0.0);
            }

            float m = c.a;
            if (uHasGlow > 0.5) {
                m = texture2D(uGlow, suv).r;
            }
            float pulse = 0.7 + 0.3 * sin(uTime * uPulseSpeed * 6.2831853);

            vec3 outerRgb = vec3(0.0);
            float outerA = 0.0;
            vec3 innerRgb = vec3(0.0);
            if ((uOuterOn > 0.5 || uInnerOn > 0.5) && uGlowSamples > 0.5) {
                float sum = 0.0;
                float n = 0.0;
                for (int i = 0; i < 12; i++) {
                    if (float(i) >= uGlowSamples) {
                        break;
                    }
                    float fi = float(i);
                    float ang = fi * 2.39996323;
                    float r = sqrt((fi + 0.5) / 12.0);
                    vec2 tuv = suv + vec2(cos(ang), sin(ang)) * r * uGlowRadius;
                    float sm = uHasGlow > 0.5 ? texture2D(uGlow, tuv).r : texture2D(uTex, tuv).a;
                    sum += sm;
                    n += 1.0;
                }
                float blur = sum / max(n, 1.0);
                if (uOuterOn > 0.5) {
                    outerA = clamp(blur - m, 0.0, 1.0) * uOuterIntensity * pulse;
                    outerRgb = uOuterColor * outerA;
                }
                if (uInnerOn > 0.5) {
                    float inner = m * clamp(1.0 - blur, 0.0, 1.0) * uInnerIntensity * pulse;
                    innerRgb = uInnerColor * inner;
                }
            }

            float outA = clamp(c.a + outerA * (1.0 - c.a), 0.0, 1.0);
            vec3 rgb = c.rgb + outerRgb * (1.0 - c.a) + innerRgb * c.a;
            gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), outA);
        }
    """

    /** Particles: GL_POINTS with a soft round sprite, drawn additively. */
    const val PARTICLE_VS = """
        attribute vec2 aPos;
        attribute float aSize;
        attribute vec4 aColor;
        uniform vec2 uOffset;
        varying vec4 vColor;
        void main() {
            gl_Position = vec4(aPos + uOffset, 0.0, 1.0);
            gl_PointSize = aSize;
            vColor = aColor;
        }
    """

    const val PARTICLE_FS = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            vec2 d = gl_PointCoord * 2.0 - 1.0;
            float r = length(d);
            float a = clamp(1.0 - r, 0.0, 1.0);
            a = a * a * (3.0 - 2.0 * a);
            a *= vColor.a;
            gl_FragColor = vec4(vColor.rgb * a, 0.0);
        }
    """

    /** Copies the offscreen render target to the window when render scale < 1. */
    const val BLIT_FS = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        void main() {
            gl_FragColor = texture2D(uTex, vUv);
        }
    """
}
