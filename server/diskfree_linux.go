package main

import "syscall"

// diskFree returns the bytes available to unprivileged users on the
// filesystem holding path, or -1 if unknown.
func diskFree(path string) int64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return -1
	}
	return int64(st.Bavail) * int64(st.Bsize)
}
