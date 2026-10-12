package protocol

type ListenMessage struct {
	DeviceID           string `json:"deviceId"`
	StreamID           string `json:"streamId,omitempty"`
	AudioVersion       int    `json:"audioVersion,omitempty"`
	MaxDurationSeconds int64  `json:"maxDurationSeconds,omitempty"`
}
