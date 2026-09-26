package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const testToken = "secret"

func TestMain(m *testing.M) {
	flag.Parse()
	if !testing.Verbose() {
		log.SetOutput(io.Discard)
	}
	os.Exit(m.Run())
}

func testConfig(t *testing.T) Config {
	return Config{
		Token:        testToken,
		DataDir:      t.TempDir(),
		MaxTextBytes: 1 << 20,
		MaxFileBytes: 20 << 20,
		QuotaBytes:   80 << 20,
		FileTTL:      24 * time.Hour,
		History:      50,
		PingInterval: time.Second,
	}
}

type env struct {
	t   *testing.T
	srv *Server
	ts  *httptest.Server
}

func newEnv(t *testing.T, cfg Config) *env {
	srv, err := NewServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(func() { srv.hub.CloseAll(); ts.Close() })
	return &env{t: t, srv: srv, ts: ts}
}

func (e *env) do(method, path string, body io.Reader, hdr map[string]string) *http.Response {
	e.t.Helper()
	req, err := http.NewRequest(method, e.ts.URL+path, body)
	if err != nil {
		e.t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+testToken)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	e.t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func decode[T any](t *testing.T, r *http.Response) T {
	t.Helper()
	var v T
	if err := json.NewDecoder(r.Body).Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func (e *env) upload(name string, data []byte, hdr map[string]string) *http.Response {
	return e.do("PUT", "/api/files?name="+url.QueryEscape(name), bytes.NewReader(data), hdr)
}

type wsClient struct {
	t    *testing.T
	conn *websocket.Conn
}

func (e *env) dial(dev string) (*wsClient, Message) {
	e.t.Helper()
	u := "ws" + strings.TrimPrefix(e.ts.URL, "http") + "/ws?device=" + url.QueryEscape(dev)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(ctx, u, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + testToken}},
	})
	if err != nil {
		e.t.Fatal(err)
	}
	e.t.Cleanup(func() { conn.CloseNow() })
	c := &wsClient{t: e.t, conn: conn}
	hello := c.next("hello")
	return c, hello
}

// next reads messages until one of the given type arrives.
func (c *wsClient) next(typ string) Message {
	c.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		_, data, err := c.conn.Read(ctx)
		if err != nil {
			c.t.Fatalf("waiting for %q: %v", typ, err)
		}
		var m Message
		if err := json.Unmarshal(data, &m); err != nil {
			c.t.Fatal(err)
		}
		if m.Type == typ {
			return m
		}
	}
}

// waitDevices reads "devices" updates until the list equals want.
func (c *wsClient) waitDevices(want ...string) {
	c.t.Helper()
	for {
		m := c.next("devices")
		if strings.Join(m.Devices, ",") == strings.Join(want, ",") {
			return
		}
	}
}

func (c *wsClient) send(m Message) {
	data, _ := json.Marshal(m)
	if err := c.conn.Write(context.Background(), websocket.MessageText, data); err != nil {
		c.t.Fatal(err)
	}
}

func randomBytes(t *testing.T, n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

func TestAuth(t *testing.T) {
	e := newEnv(t, testConfig(t))
	resp, err := http.Get(e.ts.URL + "/api/items")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no token: got %d", resp.StatusCode)
	}
	resp, _ = http.Get(e.ts.URL + "/api/items?token=" + testToken)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("query token: got %d", resp.StatusCode)
	}
	resp, _ = http.Get(e.ts.URL + "/healthz")
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("healthz: got %d", resp.StatusCode)
	}
	resp, _ = http.Get(e.ts.URL + "/")
	page, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Contains(page, []byte("Общий буфер")) {
		t.Fatal("web page not served")
	}
	// WebSocket without token is refused before the upgrade.
	_, _, err = websocket.Dial(context.Background(), "ws"+strings.TrimPrefix(e.ts.URL, "http")+"/ws", nil)
	if err == nil {
		t.Fatal("ws without token accepted")
	}
}

func TestTextAndURL(t *testing.T) {
	e := newEnv(t, testConfig(t))
	pc, _ := e.dial("pc")

	resp := e.do("POST", "/api/clip", strings.NewReader(`{"text":"привет, мир"}`),
		map[string]string{"Content-Type": "application/json", "X-Device": url.QueryEscape("телефон")})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("post: %d", resp.StatusCode)
	}
	it := decode[Item](t, resp)
	if it.Kind != KindText || it.Text != "привет, мир" || it.From != "телефон" {
		t.Fatalf("unexpected item %+v", it)
	}
	if m := pc.next("clip"); m.Item.ID != it.ID {
		t.Fatalf("broadcast got %+v", m.Item)
	}

	resp = e.do("POST", "/api/clip", strings.NewReader("  https://example.com/a?b=1 \n"), nil)
	it = decode[Item](t, resp)
	if it.Kind != KindURL || it.Text != "https://example.com/a?b=1" {
		t.Fatalf("url not detected: %+v", it)
	}
	latest := decode[Item](t, e.do("GET", "/api/items/latest", nil, nil))
	if latest.ID != it.ID {
		t.Fatal("latest mismatch")
	}
	latestText := decode[Item](t, e.do("GET", "/api/items/latest?kind=text", nil, nil))
	if latestText.Text != "привет, мир" {
		t.Fatal("latest?kind=text mismatch")
	}

	if r := e.do("POST", "/api/clip", strings.NewReader("   "), nil); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("empty text: %d", r.StatusCode)
	}
	big := strings.Repeat("x", 2<<20)
	if r := e.do("POST", "/api/clip", strings.NewReader(big), nil); r.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("big text: %d", r.StatusCode)
	}
}

func TestWebSocketFanout(t *testing.T) {
	e := newEnv(t, testConfig(t))
	e.do("POST", "/api/clip", strings.NewReader("old"), nil)

	pc, hello := e.dial("pc")
	if len(hello.Items) != 1 || hello.Items[0].Text != "old" || hello.Limits.MaxFileBytes != 20<<20 {
		t.Fatalf("bad hello: %+v", hello)
	}
	laptop, _ := e.dial("laptop")
	pc.waitDevices("laptop", "pc")

	laptop.send(Message{Type: "clip", Text: "from laptop"})
	for _, c := range []*wsClient{pc, laptop} { // the sender gets its own item back too
		m := c.next("clip")
		if m.Item.Text != "from laptop" || m.Item.From != "laptop" {
			t.Fatalf("got %+v", m.Item)
		}
	}

	pc.send(Message{Type: "ping"})
	pc.next("pong")

	id := e.srv.store.List()[1].ID
	pc.send(Message{Type: "delete", ID: id})
	if m := laptop.next("delete"); m.ID != id {
		t.Fatalf("delete id %q", m.ID)
	}
	if len(e.srv.store.List()) != 1 {
		t.Fatal("item not deleted")
	}

	laptop.conn.Close(websocket.StatusNormalClosure, "")
	pc.waitDevices("pc")
}

func TestFileRoundTrip(t *testing.T) {
	e := newEnv(t, testConfig(t))
	phone, _ := e.dial("s24")

	data := randomBytes(t, 20<<20) // exactly the limit
	sum := sha256.Sum256(data)
	resp := e.upload("Отчёт за 2026.pptx", data, map[string]string{
		"X-Device": "s24", "X-Sha256": hex.EncodeToString(sum[:]),
	})
	if resp.StatusCode != http.StatusCreated {
		b, _ := io.ReadAll(resp.Body)
		t.Fatalf("upload: %d %s", resp.StatusCode, b)
	}
	it := decode[Item](t, resp)
	if it.Kind != KindFile || it.File.Size != int64(len(data)) || it.File.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("bad item %+v %+v", it, it.File)
	}
	if !strings.Contains(it.File.Mime, "presentationml") {
		t.Fatalf("mime %q", it.File.Mime)
	}
	if m := phone.next("clip"); m.Item.File == nil || m.Item.ID != it.ID {
		t.Fatalf("no file broadcast: %+v", m.Item)
	}

	resp = e.do("GET", "/api/files/"+it.ID, nil, nil)
	got, _ := io.ReadAll(resp.Body)
	if !bytes.Equal(got, data) {
		t.Fatal("downloaded content differs")
	}
	cd := resp.Header.Get("Content-Disposition")
	if !strings.Contains(cd, "filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82%20%D0%B7%D0%B0%202026.pptx") {
		t.Fatalf("content-disposition %q", cd)
	}
	if resp.Header.Get("X-Sha256") != it.File.SHA256 {
		t.Fatal("missing X-Sha256")
	}

	// Resumable download.
	resp = e.do("GET", "/api/files/"+it.ID, nil, map[string]string{"Range": "bytes=100-199"})
	part, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusPartialContent || !bytes.Equal(part, data[100:200]) {
		t.Fatalf("range: %d len %d", resp.StatusCode, len(part))
	}

	// No temp files left behind.
	entries, _ := os.ReadDir(e.srv.store.filesDir)
	if len(entries) != 1 || entries[0].Name() != it.ID {
		t.Fatalf("files dir: %v", entries)
	}
}

func TestFileRejects(t *testing.T) {
	e := newEnv(t, testConfig(t))

	if r := e.upload("big.bin", randomBytes(t, 20<<20+1), nil); r.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("too large with length: %d", r.StatusCode)
	}
	// Unknown length (chunked) must be cut off too.
	req, _ := http.NewRequest("PUT", e.ts.URL+"/api/files?name=x.bin",
		io.MultiReader(bytes.NewReader(randomBytes(t, 20<<20)), strings.NewReader("extra")))
	req.Header.Set("Authorization", "Bearer "+testToken)
	req.ContentLength = -1
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("too large chunked: %d", resp.StatusCode)
	}
	if r := e.upload("a.txt", []byte("hello"), map[string]string{"X-Sha256": strings.Repeat("0", 64)}); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("hash mismatch: %d", r.StatusCode)
	}
	if r := e.upload("empty.txt", nil, nil); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("empty: %d", r.StatusCode)
	}
	if n := len(e.srv.store.List()); n != 0 {
		t.Fatalf("rejected uploads stored: %d", n)
	}
	entries, _ := os.ReadDir(e.srv.store.filesDir)
	if len(entries) != 0 {
		t.Fatalf("leftover files: %v", entries)
	}
	if r := e.do("GET", "/api/files/nope", nil, nil); r.StatusCode != http.StatusNotFound {
		t.Fatalf("missing file: %d", r.StatusCode)
	}
}

func TestQuotaEvictsOldestFiles(t *testing.T) {
	cfg := testConfig(t)
	cfg.MaxFileBytes = 1 << 20
	cfg.QuotaBytes = 3 << 20
	e := newEnv(t, cfg)
	pc, _ := e.dial("pc")

	e.do("POST", "/api/clip", strings.NewReader("keep me"), nil)
	var ids []string
	for i := 0; i < 4; i++ {
		it := decode[Item](t, e.upload("f.bin", randomBytes(t, 1<<20), nil))
		ids = append(ids, it.ID)
	}
	if m := pc.next("delete"); m.ID != ids[0] {
		t.Fatalf("evicted %q, want oldest %q", m.ID, ids[0])
	}
	if _, err := os.Stat(e.srv.store.FilePath(ids[0])); !os.IsNotExist(err) {
		t.Fatal("evicted file still on disk")
	}
	if got := e.srv.store.FileBytes(); got != 3<<20 {
		t.Fatalf("stored %d bytes", got)
	}
	if items := e.srv.store.List(); len(items) != 4 || items[0].Text != "keep me" {
		t.Fatalf("text item should stay: %d items", len(items))
	}
}

func TestHistoryLimit(t *testing.T) {
	cfg := testConfig(t)
	cfg.History = 3
	e := newEnv(t, cfg)
	file := decode[Item](t, e.upload("a.jpg", []byte("jpeg"), nil))
	for i := 0; i < 3; i++ {
		e.do("POST", "/api/clip", strings.NewReader("t"), nil)
	}
	if n := len(e.srv.store.List()); n != 3 {
		t.Fatalf("history %d", n)
	}
	if _, err := os.Stat(e.srv.store.FilePath(file.ID)); !os.IsNotExist(err) {
		t.Fatal("file of evicted item still on disk")
	}
}

func TestExpire(t *testing.T) {
	e := newEnv(t, testConfig(t))
	e.do("POST", "/api/clip", strings.NewReader("text"), nil)
	file := decode[Item](t, e.upload("a.jpg", []byte("jpeg"), nil))

	removed := e.srv.store.Expire(time.Now().Add(25 * time.Hour))
	if len(removed) != 1 || removed[0] != file.ID {
		t.Fatalf("removed %v", removed)
	}
	if items := e.srv.store.List(); len(items) != 1 || items[0].Kind != KindText {
		t.Fatal("text should survive file TTL")
	}
	if _, err := os.Stat(e.srv.store.FilePath(file.ID)); !os.IsNotExist(err) {
		t.Fatal("expired file still on disk")
	}
}

func TestRestart(t *testing.T) {
	cfg := testConfig(t)
	e := newEnv(t, cfg)
	e.do("POST", "/api/clip", strings.NewReader("persist"), nil)
	file := decode[Item](t, e.upload("фото.jpg", []byte("jpeg data"), nil))
	os.WriteFile(e.srv.store.FilePath("orphan"), []byte("x"), 0o600)

	st, err := OpenStore(cfg)
	if err != nil {
		t.Fatal(err)
	}
	items := st.List()
	if len(items) != 2 || items[0].Text != "persist" || items[1].File.Name != "фото.jpg" {
		t.Fatalf("restored %+v", items)
	}
	if _, err := os.Stat(st.FilePath("orphan")); !os.IsNotExist(err) {
		t.Fatal("orphan not cleaned")
	}
	if _, err := os.Stat(st.FilePath(file.ID)); err != nil {
		t.Fatal("file lost on restart")
	}
}

func TestCleanFileName(t *testing.T) {
	cases := map[string]string{
		`C:\Users\me\Desktop\photo.jpg`: "photo.jpg",
		"../../etc/passwd":              "passwd",
		"a<b>c:d\"e|f?g*.txt":           "a_b_c_d_e_f_g_.txt",
		"  .. ":                         "file",
		"":                              "file",
		"Презентация.pptx":              "Презентация.pptx",
	}
	for in, want := range cases {
		if got := cleanFileName(in); got != want {
			t.Errorf("cleanFileName(%q) = %q, want %q", in, got, want)
		}
	}
	long := strings.Repeat("я", 300) + ".jpeg"
	if got := cleanFileName(long); len(got) > 200 || !strings.HasSuffix(got, ".jpeg") {
		t.Errorf("long name: %d bytes %q", len(got), got[len(got)-10:])
	}
}
