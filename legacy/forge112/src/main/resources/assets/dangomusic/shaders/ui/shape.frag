#version 120

uniform vec4 u_Bounds;// x y width height
uniform float u_Radius;
uniform vec4 u_FillColor;

uniform float u_BorderWidth;
uniform vec4 u_BorderColor;

uniform vec4 u_ShadowColor;
uniform float u_ShadowSize;
uniform vec2 u_ShadowOffset;

uniform float u_Opacity;

varying vec2 v_Position;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 local = v_Position - u_Bounds.xy;
    vec2 center = u_Bounds.zw * 0.5;

    float fillDist = sdRoundBox(local - center, center, u_Radius);
    float aa = fwidth(fillDist);
    float fillAlpha = 1.0 - smoothstep(-aa, 0.0, fillDist);

    vec4 shapeColor = u_FillColor * fillAlpha;

    float hasRadius = step(0.1, u_Radius);
    if (u_BorderWidth > 0.0) {
        float expandedDist = fillDist - u_BorderWidth;
        float rectOuterDist = sdRoundBox(local - center, center + vec2(u_BorderWidth), 0.0);
        float outerDist = mix(rectOuterDist, expandedDist, hasRadius);

        float outerAlpha = 1.0 - smoothstep(-aa, 0.0, outerDist);
        vec4 rawColor = mix(u_BorderColor, u_FillColor, fillAlpha);
        shapeColor = rawColor * outerAlpha;
    }

    vec4 shadowColor = vec4(0.0);
    if (u_ShadowSize > 0.0) {
        vec2 shadowLocal = local - vec2(u_ShadowOffset.x, -u_ShadowOffset.y);

        float shadowDistRound = sdRoundBox(shadowLocal - center, center, u_Radius) - u_BorderWidth;
        float shadowDistRect = sdRoundBox(shadowLocal - center, center + vec2(u_BorderWidth), 0.0);
        float shadowDist = mix(shadowDistRect, shadowDistRound, hasRadius);

        float shadowMask = 1.0 - smoothstep(0.0, u_ShadowSize, shadowDist);
        float shadowAlpha = shadowMask * u_ShadowColor.a;
        shadowColor = vec4(u_ShadowColor.rgb * shadowAlpha, shadowAlpha);
    }

    vec4 color = shapeColor + shadowColor * (1.0 - shapeColor.a);
    gl_FragColor = vec4(color.rgb, color.a * u_Opacity);
}