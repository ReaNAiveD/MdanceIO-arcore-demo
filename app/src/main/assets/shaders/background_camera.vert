#version 300 es

layout(location = 0) in vec4 a_Position;
layout(location = 1) in vec2 a_CameraTexCoord;

out vec2 v_CameraTexCoord;
void main() {
    gl_Position = a_Position;
    v_CameraTexCoord = a_CameraTexCoord;
}
