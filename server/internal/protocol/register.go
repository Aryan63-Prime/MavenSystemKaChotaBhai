package protocol

type RegisterMessage struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	AppVersion string `json:"appVersion"`
	Model      string `json:"model"`
	FcmToken   string `json:"fcmToken,omitempty"`
}

type RegisterAckMessage struct {
	Success bool   `json:"success"`
	Token   string `json:"token,omitempty"`
}
