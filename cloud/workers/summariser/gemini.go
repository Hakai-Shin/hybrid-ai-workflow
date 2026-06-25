package main

import (
	"context"
	"fmt"
	"strings"

	"cloud.google.com/go/vertexai/genai"
)

const summarisePrompt = `Write a 3-sentence abstractive summary.
Sentence 1: document type and main subject.
Sentence 2: key facts or figures.
Sentence 3: action items or conclusions if present, else key context.
Return plain text only.`

func summarise(ctx context.Context, client *genai.Client, redactedText string) (string, error) {
	model := client.GenerativeModel("gemini-1.5-flash")

	input := redactedText
	if len(input) > 5000 {
		input = input[:5000]
	}

	resp, err := model.GenerateContent(ctx, genai.Text(summarisePrompt+"\n\n"+input))
	if err != nil {
		return "", fmt.Errorf("gemini summarise: %w", err)
	}

	return extractText(resp), nil
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
