package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"

	"cloud.google.com/go/bigquery"
	"cloud.google.com/go/storage"
	"cloud.google.com/go/vertexai/genai"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx := context.Background()
	projectID := mustEnv("GCP_PROJECT_ID")

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

	genaiClient, err := genai.NewClient(ctx, projectID, getEnv("VERTEX_AI_REGION", "us-central1"))
	if err != nil {
		logger.Error("genai client", "err", err)
		os.Exit(1)
	}
	defer genaiClient.Close()

	h := NewHandler(logger, gcsClient, bqClient, genaiClient)
	http.HandleFunc("/summarise", h.Handle)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusOK) })

	port := getEnv("PORT", "8080")
	logger.Info("starting summariser", "port", port)
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
