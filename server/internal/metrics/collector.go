package metrics

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/auth"
)

// Collector records HTTP request metrics for Prometheus scraping.
type Collector struct {
	mu      sync.RWMutex
	started time.Time
	now     func() time.Time

	requests map[requestKey]requestStats
}

type requestKey struct {
	Method string
	Path   string
	Status int
}

type requestStats struct {
	Count       uint64
	DurationSum time.Duration
}

// NewCollector creates an empty metrics collector.
func NewCollector() *Collector {
	return NewCollectorWithClock(time.Now)
}

// NewCollectorWithClock creates a collector with a custom clock.
func NewCollectorWithClock(now func() time.Time) *Collector {
	if now == nil {
		now = time.Now
	}

	return &Collector{
		started:  now(),
		now:      now,
		requests: make(map[requestKey]requestStats),
	}
}

// ObserveRequest records one completed HTTP request.
func (c *Collector) ObserveRequest(method string, path string, status int, duration time.Duration) {
	if c == nil {
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	key := requestKey{
		Method: method,
		Path:   path,
		Status: status,
	}
	stats := c.requests[key]
	stats.Count++
	stats.DurationSum += duration
	c.requests[key] = stats
}

// RenderPrometheus returns metrics in Prometheus text exposition format.
func (c *Collector) RenderPrometheus() string {
	if c == nil {
		return ""
	}

	c.mu.RLock()
	rows := make([]metricRow, 0, len(c.requests))
	for key, stats := range c.requests {
		rows = append(rows, metricRow{
			key:   key,
			stats: stats,
		})
	}
	started := c.started
	now := c.now
	c.mu.RUnlock()

	sort.Slice(rows, func(i int, j int) bool {
		return rows[i].less(rows[j])
	})

	var builder strings.Builder
	builder.WriteString("# HELP sentinel_http_requests_total Total HTTP requests.\n")
	builder.WriteString("# TYPE sentinel_http_requests_total counter\n")
	for _, row := range rows {
		builder.WriteString(fmt.Sprintf(
			"sentinel_http_requests_total%s %d\n",
			labels(row.key),
			row.stats.Count,
		))
	}

	builder.WriteString("# HELP sentinel_http_request_duration_seconds_total Total HTTP request duration in seconds.\n")
	builder.WriteString("# TYPE sentinel_http_request_duration_seconds_total counter\n")
	for _, row := range rows {
		builder.WriteString(fmt.Sprintf(
			"sentinel_http_request_duration_seconds_total%s %s\n",
			labels(row.key),
			strconv.FormatFloat(row.stats.DurationSum.Seconds(), 'f', 6, 64),
		))
	}

	builder.WriteString("# HELP sentinel_http_request_duration_seconds_count Count of HTTP request durations.\n")
	builder.WriteString("# TYPE sentinel_http_request_duration_seconds_count counter\n")
	for _, row := range rows {
		builder.WriteString(fmt.Sprintf(
			"sentinel_http_request_duration_seconds_count%s %d\n",
			labels(row.key),
			row.stats.Count,
		))
	}

	builder.WriteString("# HELP sentinel_uptime_seconds Process uptime in seconds.\n")
	builder.WriteString("# TYPE sentinel_uptime_seconds gauge\n")
	builder.WriteString(fmt.Sprintf(
		"sentinel_uptime_seconds %s\n",
		strconv.FormatFloat(now().Sub(started).Seconds(), 'f', 0, 64),
	))

	return builder.String()
}

type metricRow struct {
	key   requestKey
	stats requestStats
}

func (r metricRow) less(other metricRow) bool {
	if r.key.Path != other.key.Path {
		return r.key.Path < other.key.Path
	}
	if r.key.Method != other.key.Method {
		return r.key.Method < other.key.Method
	}

	return r.key.Status < other.key.Status
}

func labels(key requestKey) string {
	return fmt.Sprintf(
		`{method="%s",path="%s",status="%d"}`,
		escapeLabel(key.Method),
		escapeLabel(key.Path),
		key.Status,
	)
}

func escapeLabel(value string) string {
	value = strings.ReplaceAll(value, `\`, `\\`)
	value = strings.ReplaceAll(value, "\n", `\n`)
	value = strings.ReplaceAll(value, `"`, `\"`)

	return value
}

// Authenticator validates bearer tokens for the metrics endpoint.
type Authenticator interface {
	Authenticate(token string) (string, error)
}

// Handler serves Prometheus metrics.
type Handler struct {
	collector     *Collector
	authenticator Authenticator
}

// NewHandler creates a metrics HTTP handler.
func NewHandler(collector *Collector, authenticator Authenticator) *Handler {
	if collector == nil {
		collector = NewCollector()
	}

	return &Handler{
		collector:     collector,
		authenticator: authenticator,
	}
}

// ServeHTTP writes metrics in Prometheus text format.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	if !h.authorize(w, r) {
		return
	}

	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = w.Write([]byte(h.collector.RenderPrometheus()))
}

func (h *Handler) authorize(w http.ResponseWriter, r *http.Request) bool {
	if h.authenticator == nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return false
	}

	token, ok := auth.BearerToken(r.Header.Get("Authorization"))
	if !ok {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return false
	}

	identity, err := h.authenticator.Authenticate(token)
	if err != nil {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return false
	}

	if !auth.IsAdminIdentity(identity) {
		http.Error(w, "Forbidden", http.StatusForbidden)
		return false
	}

	return true
}
