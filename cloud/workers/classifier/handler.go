package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"cloud.google.com/go/bigquery"
	"cloud.google.com/go/storage"
	"cloud.google.com/go/vertexai/genai"
)

type Handler struct {
	logger    *slog.Logger
	gcs       *storage.Client
	bq        *bigquery.Client
	genai     *genai.Client
	bucket    string
	dataset   string
}

type taskRequest struct {
	JobID  string `json:"job_id"`
	GcsURI string `json:"gcs_uri"`
}

func NewHandler(logger *slog.Logger, gcs *storage.Client, bq *bigquery.Client, g *genai.Client) *Handler {
	return &Handler{
		logger:  logger,
		gcs:     gcs,
		bq:      bq,
		genai:   g,
		bucket:  mustEnv("GCS_ARTIFACT_BUCKET"),
		dataset: getEnv("BIGQUERY_DATASET", "docubrain"),
	}
}

func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.logger.Error("read body", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	var req taskRequest
	if err := json.Unmarshal(body, &req); err != nil {
		h.logger.Error("parse task request", "err", err)
		w.WriteHeader(http.StatusBadRequest)
		return
	}

	h.logger.Info("classifying artifact", "job_id", req.JobID)

	artifact, err := downloadArtifact(r.Context(), h.gcs, h.bucket, req.JobID)
	if err != nil {
		h.logger.Error("download artifact", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	result, err := classify(r.Context(), h.genai, artifact.RedactedText)
	if err != nil {
		h.logger.Error("classify", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if err := h.writeToBigQuery(r.Context(), req.JobID, result); err != nil {
		h.logger.Error("bigquery write", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	h.logger.Info("classification complete", "job_id", req.JobID, "doc_type", result["doc_type"])
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok", "job_id": req.JobID})
}

func downloadArtifact(ctx context.Context, gcs *storage.Client, bucket, jobID string) (*ArtifactPackage, error) {
	obj := gcs.Bucket(bucket).Object(jobID + ".json")
	r, err := obj.NewReader(ctx)
	if err != nil {
		return nil, fmt.Errorf("open GCS object: %w", err)
	}
	defer r.Close()

	var pkg ArtifactPackage
	if err := json.NewDecoder(r).Decode(&pkg); err != nil {
		return nil, fmt.Errorf("decode artifact: %w", err)
	}
	return &pkg, nil
}

func (h *Handler) writeToBigQuery(ctx context.Context, jobID string, result map[string]interface{}) error {
	topics, _ := json.Marshal(result["topics"])
	row := map[string]bigquery.Value{
		"job_id":      jobID,
		"doc_type":    fmt.Sprintf("%v", result["doc_type"]),
		"sensitivity": fmt.Sprintf("%v", result["sensitivity"]),
		"language":    fmt.Sprintf("%v", result["language"]),
		"topics":      string(topics),
		"created_at":  time.Now(),
	}
	inserter := h.bq.Dataset(h.dataset).Table("classifications").Inserter()
	return inserter.Put(ctx, row)
}
