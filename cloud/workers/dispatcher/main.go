package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"

	cloudtasks "cloud.google.com/go/cloudtasks/apiv2"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	ctx := context.Background()
	tasksClient, err := cloudtasks.NewClient(ctx)
	if err != nil {
		logger.Error("failed to create Cloud Tasks client", "err", err)
		os.Exit(1)
	}
	defer tasksClient.Close()

	h := NewHandler(logger, tasksClient)

	http.HandleFunc("/", h.Handle)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	logger.Info("starting dispatcher", "port", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		logger.Error("server error", "err", err)
		os.Exit(1)
	}
}
