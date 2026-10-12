package metrics

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestObserveRequest_Increments(t *testing.T) {
	c := NewCollector()
	c.ObserveRequest("GET", "/health", 200, 10*time.Millisecond)
	c.ObserveRequest("GET", "/health", 200, 20*time.Millisecond)

	output := c.RenderPrometheus()
	if !strings.Contains(output, `sentinel_http_requests_total{method="GET",path="/health",status="200"} 2`) {
		t.Errorf("expected count 2 in output:\n%s", output)
	}
}

func TestObserveRequest_SeparateKeys(t *testing.T) {
	c := NewCollector()
	c.ObserveRequest("GET", "/health", 200, time.Millisecond)
	c.ObserveRequest("GET", "/devices", 200, time.Millisecond)
	c.ObserveRequest("GET", "/health", 404, time.Millisecond)

	output := c.RenderPrometheus()
	lines := strings.Split(output, "\n")

	countLines := 0
	for _, line := range lines {
		if strings.HasPrefix(line, "sentinel_http_requests_total{") {
			countLines++
		}
	}
	if countLines != 3 {
		t.Errorf("expected 3 request count lines, got %d", countLines)
	}
}

func TestRenderPrometheus_Empty(t *testing.T) {
	c := NewCollector()
	output := c.RenderPrometheus()

	if !strings.Contains(output, "sentinel_uptime_seconds") {
		t.Error("expected uptime metric in empty output")
	}
	if !strings.Contains(output, "# HELP") {
		t.Error("expected HELP comments")
	}
	if !strings.Contains(output, "# TYPE") {
		t.Error("expected TYPE comments")
	}
}

func TestRenderPrometheus_Duration(t *testing.T) {
	c := NewCollector()
	c.ObserveRequest("GET", "/health", 200, 500*time.Millisecond)

	output := c.RenderPrometheus()
	if !strings.Contains(output, "sentinel_http_request_duration_seconds_total") {
		t.Error("expected duration metric")
	}
}

func TestRenderPrometheus_Uptime(t *testing.T) {
	fixedTime := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	calls := 0
	clock := func() time.Time {
		calls++
		if calls <= 1 {
			return fixedTime
		}
		return fixedTime.Add(60 * time.Second)
	}
	c := NewCollectorWithClock(clock)
	output := c.RenderPrometheus()

	if !strings.Contains(output, "sentinel_uptime_seconds 60") {
		t.Errorf("expected 60 second uptime:\n%s", output)
	}
}

func TestCollector_NilSafe(t *testing.T) {
	var c *Collector
	// ObserveRequest should not panic on nil collector.
	c.ObserveRequest("GET", "/health", 200, time.Millisecond)

	output := c.RenderPrometheus()
	if output != "" {
		t.Errorf("expected empty output from nil collector, got %q", output)
	}
}

func TestNewCollectorWithClock_NilFallback(t *testing.T) {
	c := NewCollectorWithClock(nil)
	if c == nil {
		t.Fatal("expected non-nil collector")
	}
	// Should use time.Now as fallback, so uptime should be >= 0.
	output := c.RenderPrometheus()
	if !strings.Contains(output, "sentinel_uptime_seconds") {
		t.Error("expected uptime metric")
	}
}

// --- Handler tests ---

type mockAuthenticator struct {
	identity string
	err      error
}

func (m *mockAuthenticator) Authenticate(token string) (string, error) {
	if m.err != nil {
		return "", m.err
	}
	if m.identity != "" {
		return m.identity, nil
	}
	return "ADMIN-0001", nil
}

func TestHandler_Unauthorized_NoHeader(t *testing.T) {
	h := NewHandler(NewCollector(), &mockAuthenticator{})
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestHandler_Unauthorized_InvalidToken(t *testing.T) {
	h := NewHandler(NewCollector(), &mockAuthenticator{err: errors.New("invalid")})
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer bad-token")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestHandler_Unauthorized_NilAuthenticator(t *testing.T) {
	h := NewHandler(NewCollector(), nil)
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer some-token")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rec.Code)
	}
}

func TestHandler_MethodNotAllowed(t *testing.T) {
	h := NewHandler(NewCollector(), &mockAuthenticator{})
	req := httptest.NewRequest(http.MethodPost, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer valid")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", rec.Code)
	}
}

func TestHandler_Authorized(t *testing.T) {
	c := NewCollector()
	c.ObserveRequest("GET", "/health", 200, time.Millisecond)

	h := NewHandler(c, &mockAuthenticator{})
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer valid-token")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
	contentType := rec.Header().Get("Content-Type")
	if !strings.Contains(contentType, "text/plain") {
		t.Errorf("expected text/plain content type, got %q", contentType)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "sentinel_http_requests_total") {
		t.Error("expected metrics in response body")
	}
}

func TestHandler_NilCollectorFallback(t *testing.T) {
	h := NewHandler(nil, &mockAuthenticator{})
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer valid-token")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rec.Code)
	}
}

func TestMetricsRejectsHostCredential(t *testing.T) {
	h := NewHandler(NewCollector(), &mockAuthenticator{identity: "HOST-0001"})
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	req.Header.Set("Authorization", "Bearer valid-token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", rec.Code)
	}
}
