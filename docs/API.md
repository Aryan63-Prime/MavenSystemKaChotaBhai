# Project Sentinel API Specification

Version: 1.0

Status: Frozen

---

# Overview

The server exposes two interfaces:

1. HTTP API
2. WebSocket API

HTTP is used for:

- Health
- Metrics
- Administration
- Future REST endpoints

WebSocket is used for:

- Authentication
- Device Registration
- Heartbeats
- Location
- Audio
- Live Events

---

# Base URL

Development

http://localhost:8080

Production

https://api.projectsentinel.com

---

# HTTP Endpoints

## GET /health

Purpose

Health check.

Response

200 OK

{
"status":"ok"
}

---

## GET /metrics

Purpose

Prometheus metrics.

Authentication

Required

---

## GET /devices

Purpose

Returns connected devices with realtime state.

Authentication

Required

Header

Authorization: Bearer JWT

Response

200 OK

{
"devices":[
{
"deviceId":"HOST-0001",
"connectionId":"CONNECTION_ID",
"authenticated":true,
"registered":true,
"registrationState":"registered",
"heartbeatStatus":"online",
"connectedAt":"2026-07-09T12:00:00Z",
"lastHeartbeat":"2026-07-09T12:00:20Z",
"deviceName":"Pixel 9",
"appVersion":"1.0.0",
"model":"Google Pixel",
"latestLocation":{
"deviceId":"HOST-0001",
"latitude":28.6139,
"longitude":77.2090,
"accuracy":5.4,
"battery":81,
"network":"5G",
"recordedAt":"2026-07-09T12:00:10Z"
}
}
]
}

---

## GET /devices/{deviceId}

Purpose

Returns one connected device with realtime state.

Authentication

Required

Header

Authorization: Bearer JWT

Response

200 OK

{
"deviceId":"HOST-0001",
"connectionId":"CONNECTION_ID",
"authenticated":true,
"registered":true,
"registrationState":"registered",
"heartbeatStatus":"online",
"connectedAt":"2026-07-09T12:00:00Z",
"lastHeartbeat":"2026-07-09T12:00:20Z",
"deviceName":"Pixel 9",
"appVersion":"1.0.0",
"model":"Google Pixel",
"latestLocation":null
}

---

## GET /sessions

Purpose

Returns active sessions.

---

## GET /locations

Purpose

Returns latest locations.

---

# WebSocket

Endpoint

/ws

Protocol

WebSocket Secure (WSS)

---

# Authentication

Client

↓

AUTH

↓

AUTH_ACK

Only authenticated clients may continue.

---

# Request Flow

Connect

↓

AUTH

↓

REGISTER

↓

Heartbeat

↓

Location

↓

Audio

↓

Disconnect

---

# Response Codes

200

Success

400

Bad Request

401

Unauthorized

403

Forbidden

404

Not Found

409

Conflict

500

Internal Error

---

# Versioning

API Version

v1

Future versions must remain backward compatible whenever practical.

---

# API Freeze

Changes require updating this document.

---

# Extended REST Endpoints (v1.1 Addendum)

## GET /devices/{id}/history
Returns chronological location breadcrumbs for route polyline visualization.

* **Authentication:** Admin JWT Bearer Token required.
* **Query Parameters:**
  * `start` (optional): RFC3339 timestamp (e.g. `2026-10-14T00:00:00Z`).
  * `end` (optional): RFC3339 timestamp.
  * `limit` (optional): Default `2000`, max `5000`.
* **Response (200 OK):**
```json
{
  "deviceId": "HOST-001-SM-S908E-A1B2",
  "locations": [
    {
      "latitude": 37.7749,
      "longitude": -122.4194,
      "accuracy": 4.5,
      "battery": 88,
      "recordedAt": "2026-10-21T12:00:00Z"
    }
  ]
}
```

---

## GET /devices/{id}/battery-history
Returns battery charge level time-series points.

* **Authentication:** Admin JWT Bearer Token required.
* **Query Parameters:** `limit` (default `100`).
* **Response (200 OK):**
```json
{
  "deviceId": "HOST-001-SM-S908E-A1B2",
  "history": [92, 91, 90, 89, 88]
}
```

---

## POST /devices/{id}/wake
Dispatches an out-of-band high-priority Firebase Cloud Messaging (FCM) wake-up data notification to reconnect sleeping Android devices.

* **Authentication:** Admin JWT Bearer Token required.
* **Response (200 OK):**
```json
{
  "status": "dispatched",
  "deviceId": "HOST-001-SM-S908E-A1B2",
  "messageId": "projects/project-sentinel/messages/0:17520500"
}
```

