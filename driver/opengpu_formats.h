/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FORMATS_H
#define OPENGPU_FORMATS_H
/* Bytes at increasing addresses; zero preserves legacy A,B,G,R storage. */
#define OPENGPU_FORMAT_NATIVE 0u
#define OPENGPU_FORMAT_RGBA8  1u
#define OPENGPU_FORMAT_BGRA8  2u
#define OPENGPU_FORMAT_RGBX8  3u
#define OPENGPU_FORMAT_BGRX8  4u
#define OPENGPU_FORMAT_MAX    4u
#endif
