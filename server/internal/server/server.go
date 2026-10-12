package server

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/app"
)

// Server wraps the HTTP server.
type Server struct {
	httpServer *http.Server
}

// New creates an HTTP server bound to the configured address.
func New(application *app.Application) *Server {

	return &Server{
		httpServer: &http.Server{
			Addr: fmt.Sprintf(
				"%s:%s",
				application.Config.Server.Host,
				application.Config.Server.Port,
			),
			Handler:           application.Gateway.Handler(),
			ReadHeaderTimeout: 10 * time.Second,
			ReadTimeout:       30 * time.Second,
			WriteTimeout:      30 * time.Second,
			IdleTimeout:       60 * time.Second,
			MaxHeaderBytes:    1 << 20,
		},
	}
}

// Start begins listening for HTTP connections. Blocks until the server stops.
func (s *Server) Start() error {
	return s.httpServer.ListenAndServe()
}

// Shutdown gracefully drains active HTTP requests within the deadline.
func (s *Server) Shutdown(ctx context.Context) error {
	return s.httpServer.Shutdown(ctx)
}
