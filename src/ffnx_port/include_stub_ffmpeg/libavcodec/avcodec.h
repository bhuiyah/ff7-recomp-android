// libavcodec/avcodec.h - placeholder until FFmpeg is built for Android (movies).
// globals.h includes the FFmpeg headers; common.cpp only installs a log callback. The log
// API is declared here and does nothing (src/ffnx_port/win32_compat.cpp); any file that
// really uses FFmpeg is not compiled yet.
#pragma once
#include <stdarg.h>
#define AV_LOG_QUIET (-8)
#define AV_LOG_PANIC 0
#define AV_LOG_FATAL 8
#define AV_LOG_ERROR 16
#define AV_LOG_WARNING 24
#define AV_LOG_INFO 32
#define AV_LOG_VERBOSE 40
#define AV_LOG_DEBUG 48
#define AV_LOG_TRACE 56
#ifdef __cplusplus
extern "C" {
#endif
void av_log_set_level(int level);
void av_log_set_callback(void (*cb)(void *, int, const char *, va_list));
void av_log_format_line(void *ptr, int level, const char *fmt, va_list vl, char *line, int line_size, int *print_prefix);
#ifdef __cplusplus
}
#endif
