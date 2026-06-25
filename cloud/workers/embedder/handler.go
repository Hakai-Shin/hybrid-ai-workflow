package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	aiplatform "cloud.google.com/go/aiplatform/apiv1"
	"cloud.google.com/go/bigquery"
	"cloud.google.com/go/storage"
)

type Handler struct {
	logger      *slog.Logger
	gcs         *storage.Client
	bq          *bigquery.Client
	predClient  *aiplatform.PredictionClient
	indexClient *aiplatform.MatchServiceClient
	projectID   string
	region      string
	bucket      string
	dataset     string
	indexEndpoint string
	indexID       string
}

type taskRequest struct {
	JobID  string `json:"job_id"`
	GcsURI string `json:"gcs_uri"`
}

func NewHandler(
	logger *slog.Logger,
	gcs *storage.Client,
	bq *bigquery.Client,
	predClient *aiplatform.PredictionClient,
	indexClient *aiplatform.MatchServiceClient,
	projectID, region string,
) *Handler {
	return &Handler{
		logger:        logger,
		gcs:           gcs,
		bq:            bq,
		predClient:    predClient,
		indexClient:   indexClient,
		projectID:     projectID,
		region:        region,
		bucket:        mustEnv("GCS_ARTIFACT_BUCKET"),
		dataset:       getEnv("BIGQUERY_DATASET", "docubrain"),
		indexEndpoint: mustEnv("VECTOR_SEARCH_INDEX_ENDPOINT"),
		indexID:       mustEnv("VECTOR_SEARCH_INDEX_ID"),
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

	h.logger.Info("embedding artifact", "job_id", req.JobID)

	artifact, err := downloadArtifact(r.Context(), h.gcs, h.bucket, req.JobID)
	if err != nil {
		h.logger.Error("download artifact", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	embedding, err := embed(r.Context(), h.predClient, h.projectID, h.region, artifact)
	if err != nil {
		h.logger.Error("embed", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if err := upsertToVectorSearch(r.Context(), h.indexClient, h.indexEndpoint, req.JobID, artifact.DocTypeHint, embedding); err != nil {
		h.logger.Error("vector search upsert", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	if err := h.writeToBigQuery(r.Context(), req.JobID, len(embedding)); err != nil {
		h.logger.Error("bigquery write", "job_id", req.JobID, "err", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	h.logger.Info("embedding complete", "job_id", req.JobID, "dims", len(embedding))
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

func (h *Handler) writeToBigQuery(ctx context.Context, jobID string, dims int) error {
	row := map[string]bigquery.Value{
		"job_id":          jobID,
		"model_version":   "text-embedding-004",
		"vector_dimension": int64(dims),
		"index_endpoint":  h.indexEndpoint,
		"created_at":      time.Now(),
	}
	inserter := h.bq.Dataset(h.dataset).Table("embeddings").Inserter()
	return inserter.Put(ctx, row)
}
