package bootstrap

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/xaiop/project-sentinel/server/internal/admin"
	"github.com/xaiop/project-sentinel/server/internal/app"
	"github.com/xaiop/project-sentinel/server/internal/audio"
	"github.com/xaiop/project-sentinel/server/internal/auth"
	"github.com/xaiop/project-sentinel/server/internal/command"
	"github.com/xaiop/project-sentinel/server/internal/config"
	"github.com/xaiop/project-sentinel/server/internal/database"
	"github.com/xaiop/project-sentinel/server/internal/device"
	"github.com/xaiop/project-sentinel/server/internal/dispatcher"
	"github.com/xaiop/project-sentinel/server/internal/fcm"
	"github.com/xaiop/project-sentinel/server/internal/file"
	"github.com/xaiop/project-sentinel/server/internal/gateway"
	"github.com/xaiop/project-sentinel/server/internal/health"
	"github.com/xaiop/project-sentinel/server/internal/heartbeat"
	"github.com/xaiop/project-sentinel/server/internal/location"
	"github.com/xaiop/project-sentinel/server/internal/logger"
	"github.com/xaiop/project-sentinel/server/internal/metrics"
	"github.com/xaiop/project-sentinel/server/internal/repository"
	"go.uber.org/zap"
)

func Build() (*app.Application, error) {

	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}

	log, err := logger.New()
	if err != nil {
		return nil, err
	}

	var deviceRepo repository.DeviceRepository
	var pgDB *database.PostgresDB
	if postgresConfigured(cfg.Database) {
		db, err := database.NewPostgresDB(cfg.Database, log)
		if err != nil {
			return nil, fmt.Errorf("initialize postgres database: %w", err)
		}
		pgDB = db
		deviceRepo = repository.NewPostgresDeviceRepository(db.DB())
		log.Info("PostgreSQL durable device enrollment and token storage active")
	} else {
		log.Info("PostgreSQL not configured, using in-memory device repository")
		deviceRepo = repository.NewMemoryDeviceRepository()
	}

	authService := auth.New(cfg.JWT.Secret)
	authHandler := auth.NewHandler(authService)
	deviceService := device.NewServiceWithRepository(deviceRepo)
	deviceHandler := device.NewHandler(deviceService)
	deviceHandler.SetTokenGenerator(authService)
	heartbeatService := heartbeat.NewService()
	heartbeatHandler := heartbeat.NewHandler(heartbeatService)
	redisClient := database.NewRedisClient(database.RedisOptions{
		Host:     cfg.Redis.Host,
		Port:     cfg.Redis.Port,
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
	})
	locationRepository := repository.NewRedisLocationRepository(redisClient, time.Duration(cfg.Redis.LocationTTLSeconds)*time.Second)
	locationService := location.NewServiceWithDependencies(locationRepository, nil, nil)
	locationHandler := location.NewHandler(locationService)
	locationHandler.SetBatteryLogger(deviceRepo)
	locationHandler.SetLocationHistoryLogger(deviceRepo)
	listenerRepository := repository.NewRedisListenerRepository(redisClient)
	audioService := audio.NewService(listenerRepository, nil)
	audioHandler := audio.NewHandler(audioService)

	fileListenerRepo := repository.NewRedisFileListenerRepository(redisClient)
	fileService := file.NewService(fileListenerRepo, nil)
	// We'll set the finder and forwarder after creating the gateway and dispatcher

	dispatch := dispatcher.New(authHandler, deviceHandler, heartbeatHandler, locationHandler, audioHandler, nil)
	gw := gateway.New(dispatch, heartbeatService, log)

	fileHandler := file.NewHandler(fileService, gw)
	dispatch.SetFileHandler(fileHandler)

	audioHandler.SetFinder(gw)
	audioHandler.SetRouter(gw)

	commandHandler := command.NewHandler(gw, gw)
	dispatch.SetCommandHandler(commandHandler)

	fcmService, fcmErr := fcm.NewService("serviceAccountKey.json", log)
	if fcmErr != nil {
		log.Warn("FCM service initialization failed", zap.Error(fcmErr))
	} else if fcmService != nil {
		fcmService.SetTokenStore(deviceRepo)
		dispatch.SetFcmRegistrar(fcmService)
		commandHandler.SetFcmWaker(fcmService)
		audioHandler.SetFcmWaker(fcmService)
	}

	dispatch.SetBroadcaster(gw)
	audioService.SetForwarder(gw)
	fileService.SetForwarder(gw)
	gw.SetHealthService(health.NewService(componentCheckers(cfg, redisClient, pgDB)...))
	adminService := admin.NewService(adminSessionSource{gateway: gw}, locationRepository, heartbeatService)
	adminService.SetEnrolledDeviceSource(deviceRepo)
	if fcmService != nil {
		adminService.SetFcmService(fcmService)
	}
	adminHandler := admin.NewHandler(adminService, authService)
	metricsHandler := metrics.NewHandler(gw.MetricsCollector(), authService)
	gw.HandleFunc("/metrics", metricsHandler.ServeHTTP)
	gw.HandleFunc("/devices", adminHandler.ListDevices)
	gw.HandleFunc("/devices/", adminHandler.RouteDevice)

	application := &app.Application{
		Config:  cfg,
		Logger:  log,
		Gateway: gw,
	}
	application.RegisterCloser(redisClient)
	if pgDB != nil {
		application.RegisterCloser(pgDB)
	}

	return application, nil
}

type adminSessionSource struct {
	gateway *gateway.Gateway
}

func (s adminSessionSource) ListSessions() []admin.SessionSnapshot {
	snapshots := s.gateway.SessionSnapshots()
	adminSnapshots := make([]admin.SessionSnapshot, 0, len(snapshots))

	for _, snapshot := range snapshots {
		adminSnapshots = append(adminSnapshots, toAdminSessionSnapshot(snapshot))
	}

	return adminSnapshots
}

func (s adminSessionSource) GetSessionByDeviceID(deviceID string) (admin.SessionSnapshot, bool) {
	snapshot, ok := s.gateway.SessionSnapshotByDeviceID(deviceID)
	if !ok {
		return admin.SessionSnapshot{}, false
	}

	return toAdminSessionSnapshot(snapshot), true
}

func toAdminSessionSnapshot(snapshot gateway.SessionSnapshot) admin.SessionSnapshot {
	return admin.SessionSnapshot{
		ConnectionID:  snapshot.ConnectionID,
		DeviceID:      snapshot.DeviceID,
		Authenticated: snapshot.Authenticated,
		Registered:    snapshot.Registered,
		ConnectedAt:   snapshot.ConnectedAt,
		LastHeartbeat: snapshot.LastHeartbeat,
		DeviceName:    snapshot.DeviceName,
		AppVersion:    snapshot.AppVersion,
		Model:         snapshot.Model,
	}
}

func componentCheckers(cfg *config.Config, redisClient *database.RedisClient, pgDB *database.PostgresDB) []health.Checker {
	checkers := []health.Checker{
		health.NewPingChecker("redis", redisClient),
	}

	if pgDB != nil {
		checkers = append(checkers, health.NewPingChecker("postgresql", pgDB))
	} else if postgresConfigured(cfg.Database) {
		checkers = append(checkers, health.NewStaticChecker("postgresql", health.StatusConfigured))
	}

	return checkers
}

func postgresConfigured(cfg config.DatabaseConfig) bool {
	if strings.TrimSpace(os.Getenv("DATABASE_URL")) != "" {
		return true
	}
	return cfg.Host != "" && cfg.Port != "" && cfg.Name != ""
}
