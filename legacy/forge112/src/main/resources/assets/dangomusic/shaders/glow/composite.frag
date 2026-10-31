#version 120

// Deep Glow 风格合成：曝光映射 + 热芯白化，配合 Screen 混合使用
uniform sampler2D u_Glow;
uniform sampler2D u_Original;   // 实体本身，用来减弱覆盖在人物上的光
uniform float u_Intensity;
uniform float u_Whiteness;

varying vec2 uv;

void main() {
    vec3 g = texture2D(u_Glow, uv).rgb * u_Intensity;

    // 越亮越白：核心白热，外圈保留颜色
    float peak = max(g.r, max(g.g, g.b));
    g = mix(g, vec3(peak), clamp((peak - 1.0) * 0.45, 0.0, 1.0) * u_Whiteness);

    // 指数曝光映射，永远不会硬截断
    vec3 c = 1.0 - exp(-g);

    float entity = texture2D(u_Original, uv).a;
    c *= 1.0 - 0.95 * entity;

    gl_FragColor = vec4(c, 1.0);
}
