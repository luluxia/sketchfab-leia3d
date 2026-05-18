cmake_minimum_required(VERSION 3.22.1)

project(fpscontrol_jni)

add_library(fpscontrol_jni SHARED fps_control_jni.c)

target_link_libraries(fpscontrol_jni log binder_ndk)
