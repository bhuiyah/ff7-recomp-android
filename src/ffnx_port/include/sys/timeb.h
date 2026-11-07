// sys/timeb.h - bionic has no ftime; FFNx only uses struct timeb + ftime for timestamps.
#pragma once
#include <time.h>
#include <sys/time.h>
struct timeb { time_t time; unsigned short millitm; short timezone; short dstflag; };
inline int ftime(struct timeb *tb) { struct timeval tv; gettimeofday(&tv, nullptr); tb->time = tv.tv_sec; tb->millitm = (unsigned short)(tv.tv_usec / 1000); tb->timezone = 0; tb->dstflag = 0; return 0; }
