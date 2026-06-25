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
	logger  *slog.Logger
	gcs     *storage.Client
	bq      *bigquery.Client
	genai   *genai.Client
	bucket  string
	dataset string
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

	h.logger.Info("extracting entities", "job_id", req.JobID)

	artifact, err := downloadArtifact(r.Context(), h.gcs, h.bucket, req.JobID)
	if err != nil {
		h.logger.Error("download artifact", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	result, err := extract(r.Context(), h.genai, artifact.RedactedText)
	if err != nil {
		h.logger.Error("extract", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if err := h.writeToBigQuery(r.Context(), req.JobID, result); err != nil {
		h.logger.Error("bigquery write", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	h.logger.Info("extraction complete", "job_id", req.JobID)
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

func marshalField(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func (h *Handler) writeToBigQuery(ctx context.Context, jobID string, result map[string]interface{}) error {
	row := map[string]bigquery.Value{
		"job_id":              jobID,
		"organizations":       marshalField(result["organizations"]),
		"dates":               marshalField(result["dates"]),
		"monetary_amounts":    marshalField(result["monetary_amounts"]),
		"locations":           marshalField(result["locations"]),
		"document_references": marshalField(result["document_references"]),
		"created_at":          time.Now(),
	}
	inserter := h.bq.Dataset(h.dataset).Table("entities").Inserter()
	return inserter.Put(ctx, row)
}
