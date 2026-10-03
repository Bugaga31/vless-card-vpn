#include <sys/prctl.h>
#include <sys/types.h>
#include <unistd.h>
#include <signal.h>
#ifdef main
#undef main
#endif
int byedpi_main(int argc, char **argv);
int main(int argc, char **argv) {
    pid_t parent = getppid();
    if (parent == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent) return 1;
    return byedpi_main(argc, argv);
}
