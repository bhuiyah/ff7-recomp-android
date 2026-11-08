/* ff7_savestate.h - save states (src/host/ff7_savestate.c). */
#ifndef FF7_SAVESTATE_H
#define FF7_SAVESTATE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Called first thing every frame (FFNx's core loop): the only place a state is taken or
 * applied. Also polls the save/load buttons. */
void ff7_savestate_frame(void);

/* Ask for a save (load = 0) or a load into/from a slot ("quick", "slot1", ...). */
void ff7_savestate_request(int load, const char *slot);

/* State the rest of the program keeps outside guest memory (the FFNx port's graphics and
 * audio): `save` appends bytes with `put`, `restore` gets them back, `after_load` runs once
 * guest memory and everything else is restored. */
typedef int (*ff7_ss_writer)(void *buf, const void *data, size_t n);
typedef struct {
    const char *name;
    void *ctx;
    int (*save)(void *ctx, ff7_ss_writer put, void *buf);
    void (*before_load)(void *ctx);   /* the state is about to replace the running game */
    void (*restore)(void *ctx, const void *data, size_t n);
    void (*after_load)(void *ctx);
} ff7_savestate_hook;
void ff7_savestate_register(const ff7_savestate_hook *h);

/* Supplied by the FFNx port (weak defaults otherwise). */
int ff7_ss_driver_mode(void);                 /* the game's current mode, for compatibility */
int ff7_ss_can_save(const char **why);        /* 0 + reason when the game cannot be saved now */
void ff7_ss_notify(const char *msg);          /* an on-screen note */
void ff7_ss_location(char *out, unsigned n, uint32_t *play_seconds);   /* where in the game, play time */

#ifdef __cplusplus
}
#endif
#endif
