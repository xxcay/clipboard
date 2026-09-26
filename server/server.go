package main

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/coder/websocket"
)

//go:embed web
var webFS embed.FS

type Server struct {
	cfg   Config
	store *Store
	hub   *Hub
}

func NewServer(cfg Config) (*Server, error) {
	st, err := OpenStore(cfg)
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, store: st, hub: NewHub()}, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	web, _ := fs.Sub(webFS, "web")
	mux.Handle("GET /", http.FileServerFS(web))
	mux.HandleFunc("GET /healthz", s.handleHealth)

	mux.HandleFunc("GET /ws", s.auth(s.handleWS))
	mux.HandleFunc("GET /api/items", s.auth(s.handleList))
	mux.HandleFunc("GET /api/items/latest", s.auth(s.handleLatest))
	mux.HandleFunc("DELETE /api/items/{id}", s.auth(s.handleDelete))
	mux.HandleFunc("POST /api/clip", s.auth(s.handleClip))
	mux.HandleFunc("PUT /api/files", s.auth(s.handleUpload))
	mux.HandleFunc("POST /api/files", s.auth(s.handleUpload))
	mux.HandleFunc("GET /api/files/{id}", s.auth(s.handleDownload))
	return mux
}

// RunJanitor removes expired items until ctx is done.
func (s *Server) RunJanitor(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-t.C:
			s.announceRemoved(s.store.Expire(now))
		}
	}
}

func (s *Server) publish(it *Item, removed []string) {
	s.announceRemoved(removed)
	s.hub.Broadcast(Message{Type: "clip", Item: it})
}

func (s *Server) announceRemoved(ids []string) {
	for _, id := range ids {
		s.hub.Broadcast(Message{Type: "delete", ID: id})
	}
}

// auth accepts the token as "Authorization: Bearer <token>" or "?token=".
// The query form exists for plain links (downloads, browser WebSocket).
func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Token != "" {
			got := r.URL.Query().Get("token")
			if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
				got = strings.TrimPrefix(h, "Bearer ")
			}
			if subtle.ConstantTimeCompare([]byte(got), []byte(s.cfg.Token)) != 1 {
				httpError(w, http.StatusUnauthorized, "bad or missing token")
				return
			}
		}
		next(w, r)
	}
}

func device(r *http.Request) string {
	if d := r.Header.Get("X-Device"); d != "" {
		if u, err := url.QueryUnescape(d); err == nil {
			return u
		}
		return d
	}
	return r.URL.Query().Get("device")
}

func (s *Server) limits() *Limits {
	return &Limits{MaxTextBytes: s.cfg.MaxTextBytes, MaxFileBytes: s.cfg.MaxFileBytes, QuotaBytes: s.cfg.QuotaBytes}
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"version":   version,
		"items":     len(s.store.List()),
		"fileBytes": s.store.FileBytes(),
		"devices":   s.hub.Devices(),
	})
}

func (s *Server) handleList(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.store.List())
}

func (s *Server) handleLatest(w http.ResponseWriter, r *http.Request) {
	items := s.store.List()
	kind := r.URL.Query().Get("kind")
	for i := len(items) - 1; i >= 0; i-- {
		if kind == "" || items[i].Kind == kind {
			writeJSON(w, http.StatusOK, items[i])
			return
		}
	}
	httpError(w, http.StatusNotFound, "clipboard is empty")
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !s.store.Delete(id) {
		httpError(w, http.StatusNotFound, "no such item")
		return
	}
	s.announceRemoved([]string{id})
	w.WriteHeader(http.StatusNoContent)
}

// handleClip publishes text. Body is either JSON {"text":"…"} or plain text.
func (s *Server) handleClip(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, s.cfg.MaxTextBytes+4096))
	if err != nil {
		httpError(w, http.StatusRequestEntityTooLarge, "text too large")
		return
	}
	text := string(body)
	if strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
		var req struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(body, &req); err != nil {
			httpError(w, http.StatusBadRequest, "bad json")
			return
		}
		text = req.Text
	}
	it, removed, err := s.store.AddText(device(r), text)
	if err != nil {
		storeError(w, err)
		return
	}
	log.Printf("clip: %s from %q (%d bytes)", it.Kind, it.From, len(it.Text))
	s.publish(it, removed)
	writeJSON(w, http.StatusCreated, it)
}

// handleUpload stores the raw request body as a file.
// Name: ?name= or X-File-Name header (URL-encoded). Optional X-Sha256.
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if h := r.Header.Get("X-File-Name"); h != "" {
		if u, err := url.QueryUnescape(h); err == nil {
			name = u
		} else {
			name = h
		}
	}
	if r.ContentLength > s.cfg.MaxFileBytes {
		httpError(w, http.StatusRequestEntityTooLarge,
			fmt.Sprintf("file too large (max %d MB)", s.cfg.MaxFileBytes>>20))
		return
	}
	// A slow phone upload of 20 MB must not hit a server-wide timeout.
	rc := http.NewResponseController(w)
	_ = rc.SetReadDeadline(time.Now().Add(10 * time.Minute))

	it, removed, err := s.store.AddFile(device(r), name, r.Header.Get("Content-Type"),
		r.Body, r.ContentLength, r.Header.Get("X-Sha256"))
	if err != nil {
		storeError(w, err)
		return
	}
	log.Printf("file: %q %s from %q", it.File.Name, humanBytes(it.File.Size), it.From)
	s.publish(it, removed)
	writeJSON(w, http.StatusCreated, it)
}

func (s *Server) handleDownload(w http.ResponseWriter, r *http.Request) {
	it := s.store.Get(r.PathValue("id"))
	if it == nil || it.Kind != KindFile {
		httpError(w, http.StatusNotFound, "no such file")
		return
	}
	f, err := os.Open(s.store.FilePath(it.ID))
	if err != nil {
		httpError(w, http.StatusNotFound, "file is gone")
		return
	}
	defer f.Close()
	disp := "attachment"
	if r.URL.Query().Get("inline") == "1" {
		disp = "inline"
	}
	w.Header().Set("Content-Type", it.File.Mime)
	w.Header().Set("Content-Disposition", contentDisposition(disp, it.File.Name))
	w.Header().Set("X-Sha256", it.File.SHA256)
	w.Header().Set("ETag", `"`+it.File.SHA256+`"`)
	w.Header().Set("Cache-Control", "private, max-age=86400, immutable")
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	http.ServeContent(w, r, "", time.UnixMilli(it.TS), f)
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(s.cfg.MaxTextBytes + 64<<10)

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	c := &client{device: cleanDevice(device(r)), send: make(chan []byte, 256), cancel: cancel}
	s.hub.add(c, func() []byte {
		data, _ := json.Marshal(Message{
			Type: "hello", Version: version, Device: c.device,
			Items: s.store.List(), Devices: s.hub.devicesLocked(), Limits: s.limits(),
		})
		return data
	})
	defer s.hub.remove(c)
	log.Printf("ws: %q connected from %s", c.device, r.RemoteAddr)
	defer log.Printf("ws: %q disconnected", c.device)

	go s.wsWriter(ctx, cancel, conn, c)

	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			break
		}
		var m Message
		if err := json.Unmarshal(data, &m); err != nil {
			s.reply(c, Message{Type: "error", Error: "bad json"})
			continue
		}
		switch m.Type {
		case "ping":
			s.reply(c, Message{Type: "pong"})
		case "clip":
			it, removed, err := s.store.AddText(c.device, m.Text)
			if err != nil {
				s.reply(c, Message{Type: "error", Error: err.Error()})
				continue
			}
			log.Printf("clip: %s from %q (%d bytes)", it.Kind, it.From, len(it.Text))
			s.publish(it, removed)
		case "delete":
			if s.store.Delete(m.ID) {
				s.announceRemoved([]string{m.ID})
			}
		default:
			s.reply(c, Message{Type: "error", Error: "unknown message type"})
		}
	}
	conn.CloseNow()
}

func (s *Server) reply(c *client, m Message) {
	data, _ := json.Marshal(m)
	select {
	case c.send <- data:
	default:
	}
}

// wsWriter owns all writes to conn and keeps the connection alive with
// pings, which also detects phones that silently left the Wi-Fi.
func (s *Server) wsWriter(ctx context.Context, cancel context.CancelFunc, conn *websocket.Conn, c *client) {
	defer cancel()
	ping := time.NewTicker(s.cfg.PingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case data := <-c.send:
			wctx, wcancel := context.WithTimeout(ctx, 15*time.Second)
			err := conn.Write(wctx, websocket.MessageText, data)
			wcancel()
			if err != nil {
				return
			}
		case <-ping.C:
			pctx, pcancel := context.WithTimeout(ctx, 15*time.Second)
			err := conn.Ping(pctx)
			pcancel()
			if err != nil {
				return
			}
		}
	}
}

func contentDisposition(disp, name string) string {
	ascii := strings.Map(func(r rune) rune {
		if r < 0x20 || r > 0x7e || r == '"' || r == '\\' {
			return '_'
		}
		return r
	}, name)
	return fmt.Sprintf(`%s; filename="%s"; filename*=UTF-8''%s`, disp, ascii, url.PathEscape(name))
}

func storeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrEmpty):
		httpError(w, http.StatusBadRequest, err.Error())
	case errors.Is(err, ErrTooLarge):
		httpError(w, http.StatusRequestEntityTooLarge, err.Error())
	case errors.Is(err, ErrHashMismatch):
		httpError(w, http.StatusBadRequest, err.Error())
	case errors.Is(err, ErrNoSpace):
		httpError(w, http.StatusInsufficientStorage, err.Error())
	default:
		log.Printf("error: %v", err)
		httpError(w, http.StatusBadRequest, err.Error())
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func humanBytes(n int64) string {
	switch {
	case n >= 1<<20:
		return strconv.FormatFloat(float64(n)/(1<<20), 'f', 1, 64) + " MB"
	case n >= 1<<10:
		return strconv.FormatFloat(float64(n)/(1<<10), 'f', 1, 64) + " KB"
	}
	return strconv.FormatInt(n, 10) + " B"
}
