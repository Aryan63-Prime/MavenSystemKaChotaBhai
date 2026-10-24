package gateway

import (
	"github.com/gorilla/websocket"
	"time"
)

func (g *Gateway) writeLoop(client *Client) {

	defer func() {
		_ = client.Conn.Close()
	}()

	for {

		msg, ok := <-client.Send

		if !ok {
			return
		}

		messageType := msg.MessageType
		if messageType == 0 {
			messageType = websocket.TextMessage
		}

		if err := client.Conn.SetWriteDeadline(time.Now().Add(WriteTimeout)); err != nil {
			return
		}
		if err := client.Conn.WriteMessage(messageType, msg.Payload); err != nil {
			return
		}

		if msg.CloseAfterWrite {
			code := msg.CloseCode
			if code == 0 {
				code = websocket.ClosePolicyViolation
			}
			reason := msg.CloseReason
			if reason == "" {
				reason = "Policy Violation"
			}
			closePayload := websocket.FormatCloseMessage(code, reason)
			_ = client.Conn.WriteControl(websocket.CloseMessage, closePayload, time.Now().Add(WriteTimeout))
			return
		}
	}
}
