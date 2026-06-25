package main

import (
	"context"
	"fmt"

	aiplatform "cloud.google.com/go/aiplatform/apiv1"
	aiplatformpb "cloud.google.com/go/aiplatform/apiv1/aiplatformpb"
	"google.golang.org/protobuf/types/known/structpb"
)

const embeddingModel = "text-embedding-004"

func embed(
	ctx context.Context,
	client *aiplatform.PredictionClient,
	projectID, region string,
	artifact *ArtifactPackage,
) ([]float32, error) {
	input := artifact.RedactedText
	if len(input) > 8000 {
		input = input[:8000]
	}

	endpoint := fmt.Sprintf(
		"projects/%s/locations/%s/publishers/google/models/%s",
		projectID, region, embeddingModel,
	)

	instance, err := structpb.NewValue(map[string]interface{}{"content": input})
	if err != nil {
		return nil, fmt.Errorf("build instance: %w", err)
	}

	resp, err := client.Predict(ctx, &aiplatformpb.PredictRequest{
		Endpoint:  endpoint,
		Instances: []*structpb.Value{instance},
	})
	if err != nil {
		return nil, fmt.Errorf("predict embedding: %w", err)
	}

	if len(resp.Predictions) == 0 {
		return nil, fmt.Errorf("no predictions returned")
	}

	vals := resp.Predictions[0].GetStructValue().Fields["embeddings"].
		GetStructValue().Fields["values"].GetListValue().Values
	embedding := make([]float32, len(vals))
	for i, v := range vals {
		embedding[i] = float32(v.GetNumberValue())
	}
	return embedding, nil
}

func upsertToVectorSearch(
	ctx context.Context,
	client *aiplatform.MatchServiceClient,
	indexEndpoint, jobID, docTypeHint string,
	embedding []float32,
) error {
	floats := make([]float64, len(embedding))
	for i, v := range embedding {
		floats[i] = float64(v)
	}

	req := &aiplatformpb.UpsertDatapointsRequest{
		Index: indexEndpoint,
		Datapoints: []*aiplatformpb.IndexDatapoint{
			{
				DatapointId:  jobID,
				FeatureVector: floats,
				Restricts: []*aiplatformpb.IndexDatapoint_Restriction{
					{
						Namespace: "doc_type",
						AllowList: []string{docTypeHint},
					},
				},
			},
		},
	}

	_, err := client.UpsertDatapoints(ctx, req)
	if err != nil {
		return fmt.Errorf("upsert datapoints: %w", err)
	}
	return nil
}
