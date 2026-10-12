package protocol

type StopMessage struct {
	DeviceID string `json:"deviceId"`
	StreamID string `json:"streamId,omitempty"`
}
