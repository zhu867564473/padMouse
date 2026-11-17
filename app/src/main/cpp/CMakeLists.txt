cmake_minimum_required(VERSION 3.22.1)
project(padmouse_evioctl C)

add_library(padmouse_evioctl SHARED native_ioctl.c)
