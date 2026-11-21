cmake_minimum_required(VERSION 3.22.1)
project(remoteaudio LANGUAGES C CXX)

include(FetchContent)

# 首次配置需联网下载 opus 源码
FetchContent_Declare(
    opus
    URL https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

add_library(remoteaudio_jni SHARED opus_jni.cpp)
target_include_directories(remoteaudio_jni PRIVATE ${opus_SOURCE_DIR}/include)
target_link_libraries(remoteaudio_jni opus log)