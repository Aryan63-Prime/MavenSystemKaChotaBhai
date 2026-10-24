#!/usr/bin/env python3
"""
Sentinel Red-Team Suite: Ultrasonic Carrier Generator
Generates a 5-second 48,000 Hz 16-bit mono WAV file with a pure 20,000 Hz tone.
Used to trigger physical over-the-air Goertzel ultrasonic beacon detectors.
"""

import math
import struct
import wave
import os

def generate_ultrasonic_wav(output_path="ultrasonic_20khz_beacon.wav", duration_sec=5.0, freq_hz=20000.0, sample_rate=48000, amplitude=0.85):
    num_samples = int(duration_sec * sample_rate)
    
    with wave.open(output_path, "wb") as wav_file:
        wav_file.setnchannels(1)  # Mono
        wav_file.setsampwidth(2)  # 16-bit
        wav_file.setframerate(sample_rate)
        
        frames = bytearray()
        for i in range(num_samples):
            t = float(i) / sample_rate
            sample_val = amplitude * math.sin(2.0 * math.pi * freq_hz * t)
            int_val = int(sample_val * 32767.0)
            frames.extend(struct.pack("<h", int_val))
            
        wav_file.writeframes(frames)
    
    print(f"[✓] Generated {output_path} ({duration_sec}s @ {freq_hz}Hz, {sample_rate}Hz sample rate, size: {os.path.getsize(output_path)} bytes)")

if __name__ == "__main__":
    script_dir = os.path.dirname(os.path.abspath(__file__))
    out_file = os.path.join(script_dir, "ultrasonic_20khz_beacon.wav")
    generate_ultrasonic_wav(out_file)
