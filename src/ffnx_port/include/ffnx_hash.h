// ffnx_hash.h - MD5 / SHA-1 hex digests for FFNx's utils.cpp on Android (see ffnx_hash.cpp).
#pragma once
#include <cstddef>
#include <string>
std::string ffnx_md5_hex(const unsigned char *data, size_t length);
std::string ffnx_sha1_file_hex(const char *filename);
bool ffnx_hash_selftest();
