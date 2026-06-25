package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"

	aiplatform "cloud.google.com/go/aiplatform/apiv1"
	"cloud.google.com/go/bigquery"
	"cloud.google.com/go/storage"
	"google.golang.org/api/option"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx := context.Background()
	projectID := mustEnv("GCP_PROJECT_ID")
	region := getEnv("VERTEX_AI_REGION", "us-central1")

	gcsClient, err := storage.NewClient(ctx)
	if err != nil {
		logger.Error("gcs client", "err", err)
		os.Exit(1)
	}
	defer gcsClient.Close()

	bqClient, err := bigquery.NewClient(ctx, projectID)
	if err != nil {
		logger.Error("bigquery client", "err", err)
		os.Exit(1)
	}
	defer bqClient.Close()

	endpoint := region + "-aiplatform.googleapis.com:443"
	predClient, err := aiplatform.NewPredictionClient(ctx, option.WithEndpoint(endpoint))
	if err != nil {
		logger.Error("aiplatform prediction client", "err", err)
		os.Exit(1)
	}
	defer predClient.Close()

	indexClient, err := aiplatform.NewMatchServiceClient(ctx, option.WithEndpoint(endpoint))
	if err != nil {
		logger.Error("aiplatform match client", "err", err)
		os.Exit(1)
	}
	defer indexClient.Close()

	h := NewHandler(logger, gcsClient, bqClient, predClient, indexClient, projectID, region)
	http.HandleFunc("/embed", h.Handle)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })

	port := getEnv("PORT", "8080")
	logger.Info("starting embedder", "port", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		logger.Error("server error", "err", err)
		os.Exit(1)
	}
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic("required env var not set: " + key)
	}
	return v
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
