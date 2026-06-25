package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"

	"time"

	cloudtasks "cloud.google.com/go/cloudtasks/apiv2"
	taskspb "cloud.google.com/go/cloudtasks/apiv2/cloudtaskspb"
	"google.golang.org/protobuf/types/known/durationpb"
)

type Handler struct {
	logger      *slog.Logger
	tasksClient *cloudtasks.Client
	projectID   string
	region      string
	workerURLs  map[string]string
	queueNames  map[string]string
}

type pubsubPushMessage struct {
	Message struct {
		Data       string            `json:"data"`
		Attributes map[string]string `json:"attributes"`
		MessageID  string            `json:"messageId"`
	} `json:"message"`
	Subscription string `json:"subscription"`
}

type artifactNotification struct {
	JobID  string `json:"job_id"`
	GcsURI string `json:"gcs_uri"`
}

type taskPayload struct {
	JobID  string `json:"job_id"`
	GcsURI string `json:"gcs_uri"`
}

func NewHandler(logger *slog.Logger, tasksClient *cloudtasks.Client) *Handler {
	return &Handler{
		logger:      logger,
		tasksClient: tasksClient,
		projectID:   mustEnv("GCP_PROJECT_ID"),
		region:      getEnv("GCP_REGION", "us-central1"),
		workerURLs: map[string]string{
			"classification-queue":  mustEnv("CLASSIFICATION_WORKER_URL"),
			"extraction-queue":      mustEnv("EXTRACTION_WORKER_URL"),
			"summarisation-queue":   mustEnv("SUMMARISATION_WORKER_URL"),
			"embedding-queue":       mustEnv("EMBEDDING_WORKER_URL"),
		},
		queueNames: map[string]string{
			"classification-queue": "classification-queue",
			"extraction-queue":     "extraction-queue",
			"summarisation-queue":  "summarisation-queue",
			"embedding-queue":      "embedding-queue",
		},
	}
}

func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.logger.Error("failed to read request body", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	var push pubsubPushMessage
	if err := json.Unmarshal(body, &push); err != nil {
		h.logger.Error("failed to parse Pub/Sub message", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	decoded, err := base64.StdEncoding.DecodeString(push.Message.Data)
	if err != nil {
		h.logger.Error("failed to decode message data", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	var notification artifactNotification
	if err := json.Unmarshal(decoded, &notification); err != nil {
		h.logger.Error("failed to parse artifact notification", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	h.logger.Info("dispatching artifact", "job_id", notification.JobID, "gcs_uri", notification.GcsURI)

	payload, _ := json.Marshal(taskPayload{
		JobID:  notification.JobID,
		GcsURI: notification.GcsURI,
	})

	routes := map[string]string{
		"classification-queue": "/classify",
		"extraction-queue":     "/extract",
		"summarisation-queue":  "/summarise",
		"embedding-queue":      "/embed",
	}

	for queue, path := range routes {
		workerURL := h.workerURLs[queue]
		if err := h.enqueue(r.Context(), queue, workerURL+path, payload, notification.JobID); err != nil {
			h.logger.Error("failed to enqueue task", "queue", queue, "job_id", notification.JobID, "err", err)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		h.logger.Info("enqueued task", "queue", queue, "job_id", notification.JobID)
	}

	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func (h *Handler) enqueue(ctx context.Context, queueName, targetURL string, payload []byte, jobID string) error {
	parent := fmt.Sprintf("projects/%s/locations/%s/queues/%s", h.projectID, h.region, queueName)

	req := &taskspb.CreateTaskRequest{
		Parent: parent,
		Task: &taskspb.Task{
			MessageType: &taskspb.Task_HttpRequest{
				HttpRequest: &taskspb.HttpRequest{
					Url:        targetURL,
					HttpMethod: taskspb.HttpMethod_POST,
					Body:       payload,
					Headers:    map[string]string{"Content-Type": "application/json"},
					AuthorizationHeader: &taskspb.HttpRequest_OidcToken{
						OidcToken: &taskspb.OidcToken{
							ServiceAccountEmail: os.Getenv("TASKS_INVOKER_SA"),
						},
					},
				},
			},
			DispatchDeadline: durationpb.New(10 * time.Minute),
		},
	}

	_, err := h.tasksClient.CreateTask(ctx, req)
	return err
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
