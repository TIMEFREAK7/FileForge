cmake_minimum_required(VERSION 3.22)
project(fileforge_media C)

# LAME 3.100 (LGPL), vendored unchanged from lame-3.100.tar.gz
# (sha256 ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e).
# Built as its own libmp3lame.so so the LGPL part stays a separate, replaceable library.
file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c)
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC lame/include PRIVATE lame/libmp3lame)
# No config.h on Android: supply the two definitions autoconf would have written.
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS=1 ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O2 -w)
target_link_libraries(mp3lame m)

# MIT-licensed JNI wrapper, linked dynamically against libmp3lame.so.
add_library(fileforge_mp3 SHARED mp3_jni.c)
target_link_libraries(fileforge_mp3 mp3lame)
