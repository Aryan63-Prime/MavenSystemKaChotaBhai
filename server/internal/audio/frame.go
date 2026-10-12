package audio

import (
	"encoding/binary"
	"errors"

	"github.com/google/uuid"
)

// PacketTypeAudio identifies a legacy Opus audio binary frame (V1, 13 bytes).
const PacketTypeAudio byte = 0x01

// PacketTypeAudioV2 identifies a UUID-isolated Opus audio binary frame (V2, 29 bytes).
const PacketTypeAudioV2 byte = 0x03

// Header sizes for V1 and V2 binary audio frames.
const (
	HeaderSizeV1 = 13
	HeaderSizeV2 = 29
	headerSize   = HeaderSizeV1
)

var (
	// ErrFrameTooSmall indicates the binary frame is shorter than the header.
	ErrFrameTooSmall = errors.New("audio frame too small")

	// ErrInvalidPacketType indicates an unrecognized packet type byte.
	ErrInvalidPacketType = errors.New("invalid packet type")
)

// FrameHeader contains the parsed binary audio frame header.
type FrameHeader struct {
	PacketType byte
	StreamID   uuid.UUID
	Sequence   uint32
	Timestamp  int64
	HeaderSize int
}

// ParseHeader extracts the header fields from a binary audio frame.
// The returned header does not copy the underlying data.
func ParseHeader(data []byte) (FrameHeader, error) {
	if len(data) < 1 {
		return FrameHeader{}, ErrFrameTooSmall
	}

	switch data[0] {
	case PacketTypeAudio:
		if len(data) < HeaderSizeV1 {
			return FrameHeader{}, ErrFrameTooSmall
		}
		return FrameHeader{
			PacketType: data[0],
			StreamID:   uuid.Nil,
			Sequence:   binary.BigEndian.Uint32(data[1:5]),
			Timestamp:  int64(binary.BigEndian.Uint64(data[5:13])),
			HeaderSize: HeaderSizeV1,
		}, nil

	case PacketTypeAudioV2:
		if len(data) < HeaderSizeV2 {
			return FrameHeader{}, ErrFrameTooSmall
		}
		streamID, err := uuid.FromBytes(data[1:17])
		if err != nil {
			return FrameHeader{}, err
		}
		return FrameHeader{
			PacketType: data[0],
			StreamID:   streamID,
			Sequence:   binary.BigEndian.Uint32(data[17:21]),
			Timestamp:  int64(binary.BigEndian.Uint64(data[21:29])),
			HeaderSize: HeaderSizeV2,
		}, nil

	default:
		return FrameHeader{}, ErrInvalidPacketType
	}
}

// ValidateFrame checks that data is a well-formed audio binary frame.
func ValidateFrame(data []byte) error {
	if len(data) < 1 {
		return ErrFrameTooSmall
	}

	switch data[0] {
	case PacketTypeAudio:
		if len(data) < HeaderSizeV1 {
			return ErrFrameTooSmall
		}
		return nil

	case PacketTypeAudioV2:
		if len(data) < HeaderSizeV2 {
			return ErrFrameTooSmall
		}
		return nil

	default:
		return ErrInvalidPacketType
	}
}
