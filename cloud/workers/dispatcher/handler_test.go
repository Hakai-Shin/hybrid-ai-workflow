package main

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHandleRejectsMalformedBody(t *testing.T) {
	h := &Handler{logger: testLogger(t)}
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader("not-json"))
	w := httptest.NewRecorder()
	h.Handle(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestHandleRejectsBadBase64(t *testing.T) {
	h := &Handler{logger: testLogger(t)}
	body, _ := json.Marshal(map[string]any{
		"message": map[string]any{
			"data":      "!!!not-base64!!!",
			"messageId": "1",
		},
	})
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(string(body)))
	w := httptest.NewRecorder()
	h.Handle(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func TestHandleRejectsBadNotificationJSON(t *testing.T) {
	h := &Handler{logger: testLogger(t)}
	encoded := base64.StdEncoding.EncodeToString([]byte("not-json"))
	body, _ := json.Marshal(map[string]any{
		"message": map[string]any{
			"data":      encoded,
			"messageId": "1",
		},
	})
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(string(body)))
	w := httptest.NewRecorder()
	h.Handle(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400, got %d", w.Code)
	}
}

func testLogger(t *testing.T) *slog.Logger {
	t.Helper()
	return slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{}))
}
