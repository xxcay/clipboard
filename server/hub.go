package main

import (
	"context"
	"encoding/json"
	"log"
	"sort"
	"sync"
)

// Messages sent over the WebSocket, server -> client:
//
//	{"type":"hello","version":"…","device":"pc","items":[…],"devices":[…],"limits":{…}}
//	{"type":"clip","item":{…}}          new item (also echoed to its sender)
//	{"type":"delete","id":"…"}          item removed (by a user, quota or TTL)
//	{"type":"devices","devices":[…]}    list of connected device names changed
//	{"type":"pong"}                     reply to {"type":"ping"}
//	{"type":"error","error":"…"}
//
// client -> server:
//
//	{"type":"clip","text":"…"}          publish text (files go over HTTP PUT)
//	{"type":"delete","id":"…"}
//	{"type":"ping"}
//
// An item may arrive both in "hello" and as a "clip" right after it, so
// clients must de-duplicate by item id.
type Message struct {
	Type    string   `json:"type"`
	Version string   `json:"version,omitempty"`
	Device  string   `json:"device,omitempty"`
	Item    *Item    `json:"item,omitempty"`
	Items   []*Item  `json:"items,omitempty"`
	ID      string   `json:"id,omitempty"`
	Text    string   `json:"text,omitempty"`
	Devices []string `json:"devices,omitempty"`
	Limits  *Limits  `json:"limits,omitempty"`
	Error   string   `json:"error,omitempty"`
}

type Limits struct {
	MaxTextBytes int64 `json:"maxTextBytes"`
	MaxFileBytes int64 `json:"maxFileBytes"`
	QuotaBytes   int64 `json:"quotaBytes"`
}

type client struct {
	device string
	send   chan []byte
	cancel context.CancelFunc
}

// Hub fans messages out to all connected WebSocket clients.
type Hub struct {
	mu      sync.Mutex
	clients map[*client]struct{}
}

func NewHub() *Hub { return &Hub{clients: map[*client]struct{}{}} }

// add registers c. hello is built while the hub is locked, so every item
// that is not in the hello snapshot is guaranteed to reach c as a broadcast.
func (h *Hub) add(c *client, hello func() []byte) {
	h.mu.Lock()
	c.send <- hello()
	h.clients[c] = struct{}{}
	h.mu.Unlock()
	h.broadcastDevices()
}

func (h *Hub) remove(c *client) {
	h.mu.Lock()
	_, ok := h.clients[c]
	delete(h.clients, c)
	h.mu.Unlock()
	if ok {
		h.broadcastDevices()
	}
}

func (h *Hub) Devices() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.devicesLocked()
}

func (h *Hub) devicesLocked() []string {
	seen := map[string]bool{}
	list := []string{}
	for c := range h.clients {
		if !seen[c.device] {
			seen[c.device] = true
			list = append(list, c.device)
		}
	}
	sort.Strings(list)
	return list
}

func (h *Hub) broadcastDevices() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sendLocked(Message{Type: "devices", Devices: h.devicesLocked()})
}

func (h *Hub) Broadcast(m Message) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sendLocked(m)
}

func (h *Hub) sendLocked(m Message) {
	data, err := json.Marshal(m)
	if err != nil {
		log.Printf("hub: marshal: %v", err)
		return
	}
	for c := range h.clients {
		select {
		case c.send <- data:
		default:
			// The client is not reading; drop it, it will reconnect and
			// get a fresh snapshot in "hello".
			log.Printf("hub: dropping slow client %q", c.device)
			delete(h.clients, c)
			c.cancel()
		}
	}
}

func (h *Hub) CloseAll() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		c.cancel()
	}
}
