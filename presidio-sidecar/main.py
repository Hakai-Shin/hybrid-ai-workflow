from fastapi import FastAPI
from presidio_analyzer import AnalyzerEngine
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

app = FastAPI(title="DocuBrain Presidio Sidecar")
analyzer = AnalyzerEngine()
anonymizer = AnonymizerEngine()

ENTITIES = [
    "PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER", "DATE_TIME",
    "MEDICAL_LICENSE", "US_SSN", "LOCATION", "NRP", "URL", "IP_ADDRESS",
]


@app.post("/analyze")
async def analyze(body: dict):
    results = analyzer.analyze(
        text=body["text"],
        entities=body.get("entities", ENTITIES),
        language=body.get("language", "en"),
    )
    return [r.to_dict() for r in results]


@app.post("/anonymize")
async def anonymize(body: dict):
    from presidio_analyzer import RecognizerResult

    results = [RecognizerResult.from_dict(r) for r in body["analyzer_results"]]
    operators = {
        e: OperatorConfig("replace", {"new_value": f"[REDACTED_{e}]"})
        for e in ENTITIES
    }
    anonymized = anonymizer.anonymize(
        text=body["text"],
        analyzer_results=results,
        operators=operators,
    )
    return {"text": anonymized.text, "entity_count": len(results)}


@app.get("/health")
async def health():
    return {"status": "ok"}
