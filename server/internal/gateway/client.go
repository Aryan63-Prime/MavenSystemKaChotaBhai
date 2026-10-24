package gateway

import (
	"context"

	"github.com/gorilla/websocket"
)

type Client struct {
	ConnectionID string

	Context context.Context

	Cancel context.CancelFunc

	Conn *websocket.Conn

	Send chan outgoingMessage

	Session *Session
}

type outgoingMessage struct {
	MessageType     int
	Payload         []byte
	CloseAfterWrite bool
	CloseCode       int
	CloseReason     string
}
