#version 120

// Liquid Glass：模糊背景 + 边缘透镜折射 + 色散 + 镜面边缘高光
uniform mat4 u_Transform;
uniform vec2 u_ScreenSize;

uniform vec4 u_Bounds;       // x y width height（逻辑坐标）
uniform float u_Radius;

uniform sampler2D u_Soft;    // 轻度模糊背景
uniform sampler2D u_Frost;   // 重度模糊背景
uniform float u_FrostMix;    // 0 = 清透, 1 = 磨砂

uniform vec4 u_Tint;         // rgb = 染色, a = 染色强度
uniform float u_MaxLuma;     // 亮度上限，保证白字可读
uniform float u_Saturation;

uniform float u_Refraction;  // 边缘折射位移（逻辑像素）
uniform float u_Bevel;       // 透镜斜面宽度（逻辑像素）
uniform float u_Dispersion;  // 色散强度
uniform float u_Highlight;   // 高光强度
uniform float u_Opacity;

varying vec2 v_Position;
varying vec2 v_Screen;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 backdrop(vec2 screenPos) {
    vec2 uv = vec2(screenPos.x / u_ScreenSize.x, 1.0 - screenPos.y / u_ScreenSize.y);
    uv = clamp(uv, vec2(0.001), vec2(0.999));
    return mix(texture2D(u_Soft, uv).rgb, texture2D(u_Frost, uv).rgb, u_FrostMix);
}

void main() {
    vec2 halfSize = u_Bounds.zw * 0.5;
    vec2 p = v_Position - u_Bounds.xy - halfSize;
    float r = min(u_Radius, min(halfSize.x, halfSize.y));

    float d = sdRoundBox(p, halfSize, r);
    float aa = max(fwidth(d), 1e-4);
    float mask = 1.0 - smoothstep(-aa, 0.0, d);
    if (mask <= 0.0) discard;

    // SDF 梯度 = 表面法线（指向外侧）
    const float e = 0.5;
    vec2 g = vec2(
        sdRoundBox(p + vec2(e, 0.0), halfSize, r) - sdRoundBox(p - vec2(e, 0.0), halfSize, r),
        sdRoundBox(p + vec2(0.0, e), halfSize, r) - sdRoundBox(p - vec2(0.0, e), halfSize, r)
    );
    vec2 n = length(g) > 1e-5 ? normalize(g) : vec2(0.0);

    // 透镜轮廓：越靠边弯折越强
    float t = clamp(-d / max(u_Bevel, 0.001), 0.0, 1.0);
    float edge = 1.0 - t;
    float bend = edge * edge;

    vec2 offLogical = -n * bend * u_Refraction;
    vec2 off = (u_Transform * vec4(offLogical, 0.0, 0.0)).xy;

    vec3 col;
    col.r = backdrop(v_Screen + off * (1.0 + u_Dispersion)).r;
    col.g = backdrop(v_Screen + off).g;
    col.b = backdrop(v_Screen + off * (1.0 - u_Dispersion)).b;

    // 饱和度增强 + 亮度上限（自适应暗化，亮背景下也能看清白字）
    float l = luma(col);
    col = max(mix(vec3(l), col, u_Saturation), 0.0);
    l = luma(col);
    if (l > u_MaxLuma) col *= u_MaxLuma / l;
    col = mix(col, u_Tint.rgb, u_Tint.a);

    // 顶部柔光
    float fy = (p.y + halfSize.y) / max(u_Bounds.w, 1.0);
    col += vec3(0.035 * (1.0 - fy) * u_Highlight);

    // 镜面边缘：左上主光，右下弱反光
    vec2 L = normalize(vec2(-0.55, -0.83));
    float facing = dot(n, L);
    float light = max(facing, 0.0) + 0.35 * max(-facing, 0.0);
    float rim = 1.0 - smoothstep(0.0, 1.4, -d);
    float inner = pow(edge, 4.0) * 0.14;
    col += vec3(rim * (0.10 + 0.75 * light) + inner * (0.25 + 0.75 * light)) * u_Highlight;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), mask * u_Opacity);
}
