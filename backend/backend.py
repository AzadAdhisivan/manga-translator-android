from fastapi import FastAPI, File, UploadFile
from manga_ocr import MangaOcr
from PIL import Image
import io

from transformers import pipeline

print("RUNNING:", __file__, flush=True)

app = FastAPI()

ocr = MangaOcr()  # loads the OCR model once
translator = pipeline("translation", model="Helsinki-NLP/opus-mt-ja-en")

@app.get("/")
def root():
    return {"message": "Backend is alive 🚀"}

@app.post("/ocr")
async def ocr_image(file: UploadFile = File(...)):
    image_bytes = await file.read()
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

    jp_text = ocr(image).strip()
    print("JP:", repr(jp_text), flush=True)

    en_text = ""
    if jp_text:
        en_text = translator(jp_text, max_length=512)[0]["translation_text"]
    print("EN:", repr(en_text), flush=True)

    return {"jp_text": jp_text, "en_text": en_text}
