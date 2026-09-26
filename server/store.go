package main

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

// Item is one entry of the shared clipboard.
type Item struct {
	ID   string    `json:"id"`
	Kind string    `json:"kind"` // "text", "url" or "file"
	From string    `json:"from"` // device name of the sender
	TS   int64     `json:"ts"`   // unix time in milliseconds
	Text string    `json:"text,omitempty"`
	File *FileMeta `json:"file,omitempty"`
}

// FileMeta describes a file stored on the hub. The file content is available
// at GET /api/files/{item id}.
type FileMeta struct {
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Mime   string `json:"mime"`
	SHA256 string `json:"sha256"`
}

const (
	KindText = "text"
	KindURL  = "url"
	KindFile = "file"
)

var (
	ErrEmpty        = errors.New("empty content")
	ErrTooLarge     = errors.New("content too large")
	ErrNoSpace      = errors.New("not enough free space on the hub")
	ErrHashMismatch = errors.New("sha256 mismatch")
	ErrNotFound     = errors.New("not found")
)

// Store keeps the clipboard history in memory and file contents on disk.
// The item index is mirrored to <dir>/index.json so the history survives a
// daemon restart (the default dir lives in RAM, so it does not survive a
// router reboot, which is fine for a clipboard).
type Store struct {
	cfg      Config
	dir      string
	filesDir string

	mu    sync.Mutex
	items []*Item // oldest first
}

func OpenStore(cfg Config) (*Store, error) {
	s := &Store{cfg: cfg, dir: cfg.DataDir, filesDir: filepath.Join(cfg.DataDir, "files")}
	if err := os.MkdirAll(s.filesDir, 0o700); err != nil {
		return nil, err
	}
	s.load()
	return s, nil
}

func (s *Store) indexPath() string { return filepath.Join(s.dir, "index.json") }

func (s *Store) FilePath(id string) string { return filepath.Join(s.filesDir, id) }

// load restores the index and removes files that are not referenced by it
// (e.g. an upload interrupted by a crash).
func (s *Store) load() {
	data, err := os.ReadFile(s.indexPath())
	if err == nil {
		var items []*Item
		if err := json.Unmarshal(data, &items); err != nil {
			log.Printf("store: ignoring broken index: %v", err)
		}
		for _, it := range items {
			if it == nil || it.ID == "" {
				continue
			}
			if it.Kind == KindFile {
				st, err := os.Stat(s.FilePath(it.ID))
				if err != nil || it.File == nil || st.Size() != it.File.Size {
					continue
				}
			}
			s.items = append(s.items, it)
		}
	}
	keep := map[string]bool{}
	for _, it := range s.items {
		keep[it.ID] = true
	}
	entries, _ := os.ReadDir(s.filesDir)
	for _, e := range entries {
		if !keep[e.Name()] {
			os.Remove(filepath.Join(s.filesDir, e.Name()))
		}
	}
	if len(s.items) > 0 {
		log.Printf("store: restored %d items", len(s.items))
	}
}

// saveLocked writes the index atomically. s.mu must be held.
func (s *Store) saveLocked() {
	data, err := json.Marshal(s.items)
	if err != nil {
		log.Printf("store: marshal index: %v", err)
		return
	}
	tmp := s.indexPath() + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		log.Printf("store: write index: %v", err)
		return
	}
	if err := os.Rename(tmp, s.indexPath()); err != nil {
		log.Printf("store: rename index: %v", err)
	}
}

// List returns a copy of the history, oldest first.
func (s *Store) List() []*Item {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]*Item(nil), s.items...)
}

func (s *Store) Get(id string) *Item {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, it := range s.items {
		if it.ID == id {
			return it
		}
	}
	return nil
}

// AddText stores a text snippet. A text that is a single http(s) link gets
// kind "url". It returns the new item and the ids of items evicted to stay
// within the history limit.
func (s *Store) AddText(from, text string) (*Item, []string, error) {
	if strings.TrimSpace(text) == "" {
		return nil, nil, ErrEmpty
	}
	if int64(len(text)) > s.cfg.MaxTextBytes {
		return nil, nil, ErrTooLarge
	}
	if !utf8.ValidString(text) {
		text = strings.ToValidUTF8(text, "�")
	}
	it := &Item{ID: newID(), Kind: KindText, From: cleanDevice(from), TS: nowMillis(), Text: text}
	if isURL(text) {
		it.Kind = KindURL
		it.Text = strings.TrimSpace(text)
	}
	removed := s.insert(it)
	return it, removed, nil
}

// AddFile streams r to disk while hashing it. declaredSize may be -1 when
// unknown. If wantSHA is not empty the upload is rejected when the content
// hash differs.
func (s *Store) AddFile(from, name, mimeType string, r io.Reader, declaredSize int64, wantSHA string) (*Item, []string, error) {
	if declaredSize > s.cfg.MaxFileBytes {
		return nil, nil, ErrTooLarge
	}
	need := declaredSize
	if need < 0 {
		need = s.cfg.MaxFileBytes
	}
	if free := diskFree(s.filesDir); free >= 0 && free < need+reserveBytes {
		return nil, nil, ErrNoSpace
	}

	id := newID()
	tmp, err := os.CreateTemp(s.filesDir, ".upload-*")
	if err != nil {
		return nil, nil, err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename

	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(r, s.cfg.MaxFileBytes+1))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return nil, nil, fmt.Errorf("upload: %w", err)
	}
	if n > s.cfg.MaxFileBytes {
		return nil, nil, ErrTooLarge
	}
	if n == 0 {
		return nil, nil, ErrEmpty
	}
	if declaredSize >= 0 && n != declaredSize {
		return nil, nil, fmt.Errorf("upload: got %d bytes, expected %d", n, declaredSize)
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if wantSHA != "" && !strings.EqualFold(wantSHA, sum) {
		return nil, nil, ErrHashMismatch
	}
	if err := os.Rename(tmpName, s.FilePath(id)); err != nil {
		return nil, nil, err
	}

	name = cleanFileName(name)
	it := &Item{
		ID: id, Kind: KindFile, From: cleanDevice(from), TS: nowMillis(),
		File: &FileMeta{Name: name, Size: n, Mime: guessMime(name, mimeType), SHA256: sum},
	}
	removed := s.insert(it)
	return it, removed, nil
}

// insert appends an item and evicts the oldest items beyond the history
// limit and the oldest files beyond the storage quota.
func (s *Store) insert(it *Item) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items = append(s.items, it)

	var removed []string
	drop := func(i int) {
		old := s.items[i]
		s.items = append(s.items[:i], s.items[i+1:]...)
		if old.Kind == KindFile {
			os.Remove(s.FilePath(old.ID))
		}
		removed = append(removed, old.ID)
	}
	for len(s.items) > s.cfg.History {
		drop(0)
	}
	for s.fileBytesLocked() > s.cfg.QuotaBytes {
		// Evict the oldest file, but never the one just added.
		i := slices.IndexFunc(s.items, func(x *Item) bool { return x.Kind == KindFile && x != it })
		if i < 0 {
			break
		}
		drop(i)
	}
	s.saveLocked()
	return removed
}

func (s *Store) fileBytesLocked() int64 {
	var total int64
	for _, it := range s.items {
		if it.Kind == KindFile && it.File != nil {
			total += it.File.Size
		}
	}
	return total
}

// FileBytes is the total size of stored files.
func (s *Store) FileBytes() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fileBytesLocked()
}

func (s *Store) Delete(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, it := range s.items {
		if it.ID == id {
			s.items = append(s.items[:i], s.items[i+1:]...)
			if it.Kind == KindFile {
				os.Remove(s.FilePath(it.ID))
			}
			s.saveLocked()
			return true
		}
	}
	return false
}

// Expire removes files older than FileTTL and any item older than ItemTTL.
func (s *Store) Expire(now time.Time) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	var removed []string
	kept := s.items[:0]
	for _, it := range s.items {
		age := now.Sub(time.UnixMilli(it.TS))
		expired := (s.cfg.ItemTTL > 0 && age > s.cfg.ItemTTL) ||
			(it.Kind == KindFile && s.cfg.FileTTL > 0 && age > s.cfg.FileTTL)
		if expired {
			if it.Kind == KindFile {
				os.Remove(s.FilePath(it.ID))
			}
			removed = append(removed, it.ID)
			continue
		}
		kept = append(kept, it)
	}
	for i := len(kept); i < len(s.items); i++ {
		s.items[i] = nil
	}
	s.items = kept
	if len(removed) > 0 {
		s.saveLocked()
	}
	return removed
}

// reserveBytes is kept free on the data filesystem (tmpfs = router RAM).
const reserveBytes = 16 << 20

func newID() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}

func nowMillis() int64 { return time.Now().UnixMilli() }

func isURL(text string) bool {
	t := strings.TrimSpace(text)
	if t == "" || strings.ContainsFunc(t, unicode.IsSpace) {
		return false
	}
	u, err := url.Parse(t)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && u.Host != ""
}

func cleanDevice(name string) string {
	name = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, name))
	if name == "" {
		return "unknown"
	}
	return truncateUTF8(name, 64)
}

// cleanFileName keeps the base name only and drops characters that are not
// allowed in Windows or Android file names.
func cleanFileName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = name[strings.LastIndex(name, "/")+1:]
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`<>:"|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.Trim(name, " .")
	if name == "" {
		name = "file"
	}
	if len(name) > 200 {
		ext := filepath.Ext(name)
		if len(ext) > 20 {
			ext = ""
		}
		name = truncateUTF8(strings.TrimSuffix(name, ext), 200-len(ext)) + ext
	}
	return name
}

func truncateUTF8(s string, max int) string {
	if len(s) <= max {
		return s
	}
	for max > 0 && !utf8.RuneStart(s[max]) {
		max--
	}
	return s[:max]
}

// extraMime covers common types missing from Go's builtin table; OpenWrt
// has no /etc/mime.types.
var extraMime = map[string]string{
	".txt":  "text/plain; charset=utf-8",
	".md":   "text/markdown; charset=utf-8",
	".csv":  "text/csv; charset=utf-8",
	".heic": "image/heic",
	".heif": "image/heif",
	".bmp":  "image/bmp",
	".tif":  "image/tiff",
	".tiff": "image/tiff",
	".dng":  "image/x-adobe-dng",
	".mp4":  "video/mp4",
	".mov":  "video/quicktime",
	".mkv":  "video/x-matroska",
	".mp3":  "audio/mpeg",
	".m4a":  "audio/mp4",
	".ogg":  "audio/ogg",
	".zip":  "application/zip",
	".7z":   "application/x-7z-compressed",
	".rar":  "application/vnd.rar",
	".apk":  "application/vnd.android.package-archive",
	".doc":  "application/msword",
	".xls":  "application/vnd.ms-excel",
	".ppt":  "application/vnd.ms-powerpoint",
	".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	".odt":  "application/vnd.oasis.opendocument.text",
	".ods":  "application/vnd.oasis.opendocument.spreadsheet",
	".odp":  "application/vnd.oasis.opendocument.presentation",
}

func guessMime(name, given string) string {
	given = strings.TrimSpace(given)
	if given != "" && !strings.HasPrefix(given, "application/octet-stream") {
		return given
	}
	ext := strings.ToLower(filepath.Ext(name))
	if m, ok := extraMime[ext]; ok {
		return m
	}
	if m := mime.TypeByExtension(ext); m != "" {
		return m
	}
	return "application/octet-stream"
}

