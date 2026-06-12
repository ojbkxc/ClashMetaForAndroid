// +build linux

package platform

import (
	"os"
	"syscall"
)

var nullFd int
var maxFdCount int

func init() {
	fd, err := syscall.Open("/dev/null", os.O_WRONLY, 0644)
	if err != nil {
		// On some Android kernels /dev/null may not be accessible.
		// Fall back to a conservative fd limit rather than panicking.
		nullFd = -1
		maxFdCount = 1024
		return
	}

	nullFd = fd

	var limit syscall.Rlimit

	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &limit); err != nil {
		maxFdCount = 1024
	} else {
		maxFdCount = int(limit.Cur)
	}

	maxFdCount = maxFdCount / 4 * 3
}

func ShouldBlockConnection() bool {
	// If nullFd is invalid (e.g., /dev/null was not accessible on this kernel),
	// skip the fd check rather than blocking all connections.
	if nullFd < 0 {
		return false
	}

	fd, err := syscall.Dup(nullFd)
	if err != nil {
		return true
	}

	_ = syscall.Close(fd)

	if fd > maxFdCount {
		return true
	}

	return false
}
