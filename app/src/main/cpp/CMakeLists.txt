cmake_minimum_required(VERSION 3.22.1)
project(padmouse_evioctl C)

add_library(native_ioctl SHARED native_ioctl.c)
