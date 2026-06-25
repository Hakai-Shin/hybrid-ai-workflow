package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"cloud.google.com/go/vertexai/genai"
)

const classifyPrompt = `Classify this document. Return JSON only, no markdown:
{"doc_type": "<type>", "sensitivity": "<low|medium|high>", "language": "<iso_code>", "topics": ["<topic>"]}`

func classify(ctx context.Context, client *genai.Client, redactedText string) (map[string]interface{}, error) {
	model := client.GenerativeModel("gemini-1.5-flash")
	model.GenerationConfig.ResponseMIMEType = "application/json"

	input := redactedText
	if len(input) > 3000 {
		input = input[:3000]
	}

	resp, err := model.GenerateContent(ctx, genai.Text(classifyPrompt+"\n\n"+input))
	if err != nil {
		return nil, fmt.Errorf("gemini classify: %w", err)
	}

	raw := extractText(resp)
	var result map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		return nil, fmt.Errorf("parse gemini response: %w (raw: %s)", err, raw)
	}
	return result, nil
}

func extractText(resp *genai.GenerateContentResponse) string {
	var sb strings.Builder
	for _, cand := range resp.Candidates {
		if cand.Content != nil {
			for _, part := range cand.Content.Parts {
				if t, ok := part.(genai.Text); ok {
					sb.WriteString(string(t))
				}
			}
		}
	}
	return strings.TrimSpace(sb.String())
}
