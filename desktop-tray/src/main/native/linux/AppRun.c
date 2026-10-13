#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
  char executable[PATH_MAX + 1];
  ssize_t length = readlink("/proc/self/exe", executable, PATH_MAX);
  if (length < 0 || length >= PATH_MAX) {
    perror("AppRun: cannot resolve its location");
    return 126;
  }
  executable[length] = '\0';
  char *separator = strrchr(executable, '/');
  if (separator == NULL) {
    fprintf(stderr, "AppRun: executable path has no parent directory\n");
    return 126;
  }
  *separator = '\0';

  char application[PATH_MAX + 1];
  int application_length = snprintf(application, sizeof(application), "%s/Skill Bill/bin/Skill Bill", executable);
  if (application_length < 0 || application_length >= (int)sizeof(application)) {
    fprintf(stderr, "AppRun: packaged application path is too long\n");
    return 126;
  }
  argv[0] = application;
  execv(application, argv);
  fprintf(stderr, "AppRun: cannot start packaged application: %s\n", strerror(errno));
  return 127;
}
