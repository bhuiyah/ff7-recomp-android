// io.h - MSVC's POSIX-ish file API names, mapped to the real POSIX ones.
#pragma once
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#ifndef _access
#define _access access
#endif
#define _open open
#define _close close
#define _read read
#define _write write
#define _lseek lseek
#define _O_RDONLY O_RDONLY
#define _O_BINARY 0
