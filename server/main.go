// clipd is the hub of the shared home clipboard. It runs on the router
// (GL.iNet Beryl AX / OpenWrt), keeps the recent clipboard history and
// relays it to the PCs and the phone over WebSocket.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"
	"time"
)

var version = "dev"

type Config struct {
	Listen       string
	Token        string
	DataDir      string
	MaxTextBytes int64
	MaxFileBytes int64
	QuotaBytes   int64
	FileTTL      time.Duration
	ItemTTL      time.Duration
	History      int
	PingInterval time.Duration
}

func main() {
	var (
		cfg        Config
		maxTextKB  int64
		maxFileMB  int64
		quotaMB    int64
		memLimitMB int64
		showVer    bool
	)
	flag.StringVar(&cfg.Listen, "listen", ":8765", "address to listen on")
	flag.StringVar(&cfg.Token, "token", os.Getenv("CLIPD_TOKEN"), "access token (or env CLIPD_TOKEN); empty disables auth")
	flag.StringVar(&cfg.DataDir, "data", "/tmp/clipd", "directory for files and the history index")
	flag.Int64Var(&maxTextKB, "max-text-kb", 1024, "max text size, KB")
	flag.Int64Var(&maxFileMB, "max-file-mb", 20, "max file size, MB")
	flag.Int64Var(&quotaMB, "quota-mb", 80, "total size of stored files, MB (oldest are evicted)")
	flag.DurationVar(&cfg.FileTTL, "file-ttl", 24*time.Hour, "delete files older than this (0 = never)")
	flag.DurationVar(&cfg.ItemTTL, "item-ttl", 0, "delete any item older than this (0 = never)")
	flag.IntVar(&cfg.History, "history", 50, "number of items to keep")
	flag.Int64Var(&memLimitMB, "mem-limit-mb", 48, "soft Go heap limit, MB")
	flag.BoolVar(&showVer, "version", false, "print version and exit")
	flag.Parse()

	if showVer {
		fmt.Println(version)
		return
	}
	cfg.MaxTextBytes = maxTextKB << 10
	cfg.MaxFileBytes = maxFileMB << 20
	cfg.QuotaBytes = quotaMB << 20
	cfg.PingInterval = 30 * time.Second
	if cfg.History < 1 {
		cfg.History = 1
	}
	if cfg.QuotaBytes < cfg.MaxFileBytes {
		cfg.QuotaBytes = cfg.MaxFileBytes
	}
	if memLimitMB > 0 {
		debug.SetMemoryLimit(memLimitMB << 20)
	}
	log.SetFlags(0) // procd/logread adds timestamps

	srv, err := NewServer(cfg)
	if err != nil {
		log.Fatalf("clipd: %v", err)
	}
	if cfg.Token == "" {
		log.Printf("WARNING: no token set, anyone on the network can use the clipboard")
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go srv.RunJanitor(ctx, time.Minute)

	hs := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	go func() {
		<-ctx.Done()
		srv.hub.CloseAll()
		sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = hs.Shutdown(sctx)
	}()

	log.Printf("clipd %s listening on %s, data in %s, max file %d MB, quota %d MB",
		version, cfg.Listen, cfg.DataDir, maxFileMB, cfg.QuotaBytes>>20)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("clipd: %v", err)
	}
}
