//go:build !linux

package main

func diskFree(path string) int64 { return -1 }
