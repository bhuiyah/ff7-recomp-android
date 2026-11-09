/* The runtime without the lift (cmake/ff7core.cmake FF7_NO_GUEST): the APK's on-device kit
 * carries no game code. The player's device lifts its own ff7_en.exe, compiles the result
 * and links it with the runtime's archives (android/.../BuildService.java). */
typedef int ff7_no_guest_unit;
